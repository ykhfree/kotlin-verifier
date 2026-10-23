"""M8 (DESIGN-v0.md section 6, 7): apply Kotlin mutants to copies of KeyLocalLock.kt, run `kv verify`, check verdicts.

Usage:
  python3 -I mutants.py --kv KV --req-shield DIR --classpath CP --kotlinc ARGS --specs DIR --expected FILE --out DIR
                        [--jobs N] [--only NAME[,NAME...]]

For each variant and mutant (the unmutated original included), the script:
  1. copies KeyLock.kt and KeyLocalLock.kt of the variant into OUT/roots/<variant>-<mutant>/<same relative path>,
  2. applies the mutant's exact string edits; each edit must match exactly once (rule 1),
  3. runs `KV verify --root <copy> ... <specs>/keylocallock.<variant>.kv` (rule 2: req-shield is not touched),
  4. compares the module verdict (from the exit code, DESIGN-v0.md 8.3 Q7) and, for FAILED, the FAILED action and
     the set of failing obligation IDs (the sidecar IDs `kv verify` prints) with the EXPECTED table.

Exit code: 0 every row matches; 1 a row does not match; 2 a script error (bad usage, an edit that does not match
exactly once, a mutant that does not compile: rule 3, never counted as a verdict).
UNSUPPORTED or ERROR is never expected, so it always fails the script (rule 4).
"""
import argparse
import concurrent.futures
import os
import re
import shutil
import subprocess
import sys
import time

VARIANT_DIRS = {
    "coroutine": "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine",
    "core": "core/src/main/kotlin/com/linecorp/cse/reqshield",
}

# Kotlin edits, as written for the coroutine variant (DESIGN-v0.md 6). The core variant has the same strings,
# except where CORE_EDITS overrides a mutant. A mutant with no edits is the original.
EDITS = {
    "original": [],
    # 213:17: unLock no longer checks the token.
    "SB1": [("existing.token == token && ", "")],
    # unLock checks the token outside compute (a racy read) and trusts that answer inside.
    "SB2": [
        ("        val released = AtomicBoolean(false)\n",
         "        val released = AtomicBoolean(false)\n        val sawMatch = lockMap[completeKey]?.token == token\n"),
        ("if (existing.token == token && existing.isHeld", "if (sawMatch && existing.isHeld"),
    ],
    # 172: a reacquired entry keeps its old expiry.
    "SB3": [("                    existing.expiresAt = now + lockTimeoutMillis\n", "")],
    # 80: the token counter is read, not incremented.
    "SB4": [("tokenSequence.incrementAndGet()", "tokenSequence.get()")],
    # 168: acquire without taking the permit.
    "SB6": [("if (existing.semaphore.tryAcquire()) {", "if (true) {")],
    # tryLock checks for an entry outside compute (a racy read) and trusts that answer inside.
    "E4": [
        ("        val acquiredToken = AtomicReference<String?>(null)\n",
         "        val acquiredToken = AtomicReference<String?>(null)\n        val sawEntry = lockMap.containsKey(completeKey)\n"),
        ("            if (existing != null) {\n", "            if (sawEntry && existing != null) {\n"),
    ],
    # Control 161: no CAS before the expired takeover (equivalent: every entry is held).
    "SB5": [("if (now > existing.expiresAt && existing.isHeld.compareAndSet(true, false)) {", "if (now > existing.expiresAt) {")],
    # Control 104: no CAS in the sweep (equivalent for the same reason).
    "SB5b": [("if (lockInfo.isHeld.compareAndSet(true, false)) {", "if (true) {")],
    # Refactoring control R1: unLock clears the token right before it removes the entry.
    "R1": [
        ("                return@compute null\n            }\n            existing\n",
         "                existing.token = null\n                return@compute null\n            }\n            existing\n"),
    ],
    # Refactoring control R2: an expired takeover returns a fresh LockInfo from the compute lambda.
    "R2": [
        ("                    existing.semaphore.release()\n                }\n\n                // Existing entry: try to acquire semaphore\n",
         "                    existing.semaphore.release()\n"
         "                    val freshToken = nextToken()\n"
         "                    val fresh = LockInfo(Semaphore(1), now + lockTimeoutMillis)\n"
         "                    fresh.semaphore.tryAcquire()\n"
         "                    fresh.isHeld.set(true)\n"
         "                    fresh.token = freshToken\n"
         "                    acquiredToken.set(freshToken)\n"
         "                    return@compute fresh\n"
         "                }\n\n                // Existing entry: try to acquire semaphore\n"),
    ],
}
CORE_EDITS = {"SB4": [("tokenCounter.incrementAndGet()", "tokenCounter.get()")]}

# `kv verify` exit codes (DESIGN-v0.md 8.3, Q7).
VERDICTS = {0: "PROVEN", 1: "FAILED", 3: "INCOMPLETE", 4: "ERROR"}

ROW = re.compile(r"^  (?:action (\S+)|(stray action \S+))\s+\S+\s+(PROVEN|FAILED|UNSUPPORTED|ERROR)$")
OTHERS = re.compile(r"^  ((?:init|lemma \S+|probe) (?:PROVEN|FAILED|UNSUPPORTED|ERROR)(?: · |$))+")
FINDING = re.compile(r"^      (?P<msg>[^:]+): (?:sidecar (?P<id>.+?) [\w./-]+\.kv:\d+:\d+|(?P<other>generated .+|kotlin .+|no source range .+))")


class ScriptError(Exception):
    pass


def edits_for(variant, mutant):
    if variant == "core" and mutant in CORE_EDITS:
        return CORE_EDITS[mutant]
    return EDITS[mutant]


def make_root(req_shield, out, variant, mutant):
    rel = VARIANT_DIRS[variant]
    root = os.path.join(out, "roots", "%s-%s" % (variant, mutant))
    shutil.rmtree(root, ignore_errors=True)
    os.makedirs(os.path.join(root, rel))
    for name in ("KeyLock.kt", "KeyLocalLock.kt"):
        shutil.copy(os.path.join(req_shield, rel, name), os.path.join(root, rel, name))
    path = os.path.join(root, rel, "KeyLocalLock.kt")
    with open(path) as f:
        text = f.read()
    for old, new in edits_for(variant, mutant):
        n = text.count(old)
        if n != 1:
            raise ScriptError("%s %s: edit matches %d times (must be 1): %r" % (variant, mutant, n, old))
        text = text.replace(old, new)
    with open(path, "w") as f:
        f.write(text)
    return root


def parse_report(text):
    """The FAILED action rows and, per row, the set of failing obligation IDs; also the other rows' verdicts."""
    rows, current = {}, None
    others = []
    for line in text.splitlines():
        m = ROW.match(line)
        if m:
            current = m.group(1) or m.group(2)
            rows[current] = (m.group(3), set())
            continue
        if OTHERS.match(line):
            others += [p.rsplit(" ", 1) for p in line.strip().split(" · ")]
            current = None
            continue
        if current and line.startswith("      ") and not line.startswith("       "):
            f = FINDING.match(line)
            if not f:
                if rows[current][0] == "UNSUPPORTED":
                    continue
                raise ScriptError("cannot read the finding line: %r" % line)
            rows[current][1].add(f.group("id") or f.group("other"))
            continue
        if not line.startswith("      "):
            current = None
    return rows, others


def run_one(args, variant, mutant):
    root = make_root(args.req_shield, args.out, variant, mutant)
    vpr = os.path.join(args.out, "%s-%s.vpr" % (variant, mutant))
    command = [args.kv, "verify", "--root", root, "--classpath", args.classpath, "--kotlinc", args.kotlinc,
               "--out", vpr, os.path.join(args.specs, "keylocallock.%s.kv" % variant)]
    start = time.time()
    try:
        p = subprocess.run(command, capture_output=True, text=True, timeout=args.timeout)
    except subprocess.TimeoutExpired:
        raise ScriptError("%s %s: kv verify did not finish in %d s" % (variant, mutant, args.timeout))
    seconds = time.time() - start
    with open(os.path.join(args.out, "%s-%s.report" % (variant, mutant)), "w") as f:
        f.write(p.stdout + p.stderr)
    if p.returncode == 4 and "compilation failed" in p.stderr:
        raise ScriptError("%s %s: the mutant does not compile (rule 3):\n%s" % (variant, mutant, p.stderr[-2000:]))
    if p.returncode == 2:
        raise ScriptError("%s %s: kv verify usage error:\n%s" % (variant, mutant, p.stderr))
    verdict = VERDICTS.get(p.returncode, "exit %d" % p.returncode)
    first = p.stdout.splitlines()[0] if p.stdout else ""
    if p.stdout and "module: %s" % verdict not in first:
        raise ScriptError("%s %s: exit code says %s, the report says %r" % (variant, mutant, verdict, first))
    rows, others = parse_report(p.stdout)
    failed = {name: ids for name, (v, ids) in rows.items() if v == "FAILED"}
    not_proven = [name for name, (v, _) in rows.items() if v not in ("PROVEN", "FAILED")]
    not_proven += [" ".join(o) for o in others if o[-1] != "PROVEN"]
    return {"variant": variant, "mutant": mutant, "verdict": verdict, "failed": failed, "other": not_proven,
            "seconds": seconds}


def read_expected(path):
    """Rows `variant  mutant  verdict  action  obligations`, columns separated by two or more spaces."""
    out = {}
    with open(path) as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            cols = re.split(r"\s{2,}", line.strip())
            if len(cols) != 5:
                raise ScriptError("%s:%d: expected 5 columns, found %d: %r" % (path, n, len(cols), line))
            variant, mutant, verdict, action, obligations = cols
            if variant not in VARIANT_DIRS or mutant not in EDITS:
                raise ScriptError("%s:%d: unknown variant or mutant: %r" % (path, n, line))
            ids = set() if obligations == "-" else {o.strip() for o in obligations.split(";")}
            out[(variant, mutant)] = (verdict, None if action == "-" else action, ids)
    return out


def check(result, expected):
    """The differences between a run and its expected row, as text; empty if it matches."""
    verdict, action, ids = expected
    problems = []
    if result["verdict"] != verdict:
        problems.append("verdict %s, expected %s" % (result["verdict"], verdict))
    if verdict == "FAILED":
        want = {action: ids}
        if result["failed"] != want:
            problems.append("FAILED rows %s, expected %s" % (fmt(result["failed"]), fmt(want)))
    if result["other"]:
        problems.append("rows that are neither PROVEN nor FAILED: %s" % ", ".join(result["other"]))
    return problems


def fmt(failed):
    return "; ".join("%s {%s}" % (a, ", ".join(sorted(ids))) for a, ids in sorted(failed.items())) or "none"


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    for name in ("--kv", "--req-shield", "--classpath", "--kotlinc", "--specs", "--expected", "--out"):
        ap.add_argument(name, required=True)
    ap.add_argument("--jobs", type=int, default=2)
    ap.add_argument("--timeout", type=int, default=900, help="seconds per kv verify run")
    ap.add_argument("--only", help="comma-separated mutant names")
    args = ap.parse_args()
    try:
        expected = read_expected(args.expected)
        only = set(args.only.split(",")) if args.only else None
        if only and not only <= set(EDITS):
            raise ScriptError("unknown mutant(s): %s" % sorted(only - set(EDITS)))
        work = [k for k in expected if only is None or k[1] in only]
        missing = [(v, m) for v in VARIANT_DIRS for m in EDITS if (v, m) not in expected and (only is None or m in only)]
        if missing:
            raise ScriptError("no expected row for %s" % missing)
        os.makedirs(args.out, exist_ok=True)
        start = time.time()
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
            results = list(pool.map(lambda k: run_one(args, *k), work))
    except ScriptError as e:
        print("mutants.py: script error: %s" % e, file=sys.stderr)
        return 2
    bad = 0
    print("%-10s %-9s %-8s %-6s %s" % ("variant", "mutant", "verdict", "time", "failing obligations"))
    for r in results:
        problems = check(r, expected[(r["variant"], r["mutant"])])
        bad += bool(problems)
        print("%-10s %-9s %-8s %5.1fs %s%s" % (r["variant"], r["mutant"], r["verdict"], r["seconds"], fmt(r["failed"]),
                                              "" if not problems else "   MISMATCH: " + "; ".join(problems)))
    print("%d run(s), %d mismatch(es), %.1f s wall clock; reports and Viper files in %s" % (len(results), bad, time.time() - start, args.out))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
