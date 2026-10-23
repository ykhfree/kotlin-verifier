"""M8 (DESIGN-v0.md section 6, 7) and B4 (DESIGN-B.md 7, 8): apply Kotlin mutants to copies of req-shield sources, run
`kv verify`, check verdicts.

Usage:
  python3 -I mutants.py [--suite keylocallock|aspect] --kv KV --req-shield DIR --classpath CP --kotlinc ARGS
                        --specs DIR --expected FILE --out DIR [--jobs N] [--only NAME[,NAME...]]
  python3 -I mutants.py --suite SUITE --req-shield DIR --out DIR --roots-only [--only NAME[,NAME...]]

Suites: `keylocallock` (default) has the three KeyLocalLock variants and the mutants of DESIGN-v0.md 6; `aspect` has
the three Spring aspect modules and the mutants of DESIGN-B.md 7.2 (spike/aspect/mutants.py, in Kotlin).
For each unit (variant or module) and mutant (the unmutated original included), the script:
  1. copies the unit's sources into OUT/roots/<unit>-<mutant>/<same relative path>: KeyLock.kt and KeyLocalLock.kt of
     a variant with the support module's main sources (the library trusted funs, B6), or the whole main source
     directory of an aspect module (its sidecar `source` is that directory),
  2. applies the mutant's exact string edits to the unit's edited file; each edit must match exactly once (rule 1),
  3. runs `KV verify --root <copy> ... <specs>/<sidecar of the unit>` (rule 2: req-shield is not touched),
  4. compares the module verdict (from the exit code, DESIGN-v0.md 8.3 Q7) and, for FAILED, the FAILED action and
     the set of failing obligation IDs (the sidecar IDs `kv verify` prints) with the EXPECTED table.
With --roots-only it stops after step 2 and prints `<unit> <mutant> <root>` per copy (the B3 gate reads them).

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
    "reactor": "core-reactor/src/main/kotlin/com/linecorp/cse/reqshield/reactor",
}

# Kotlin edits, as written for the coroutine variant (DESIGN-v0.md 6). The other variants use the same strings,
# except where VARIANT_EDITS overrides a mutant. A mutant with no edits is the original.
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
# The variants' own strings, where their text differs from the coroutine text. The reactor bodies of tryLock and
# unLock run inside `Mono.fromCallable { ... }`, so they are indented 4 more spaces; its tryLock releases the
# semaphore before it clears the token, and its unLock logs between the release block and `existing`.
VARIANT_EDITS = {
    "core": {"SB4": [("tokenCounter.incrementAndGet()", "tokenCounter.get()")]},
    "reactor": {
        "SB2": [
            ("            val released = AtomicBoolean(false)\n",
             "            val released = AtomicBoolean(false)\n            val sawMatch = lockMap[completeKey]?.token == token\n"),
            ("if (existing.token == token && existing.isHeld", "if (sawMatch && existing.isHeld"),
        ],
        "SB3": [("                        existing.expiresAt = now + lockTimeoutMillis\n", "")],
        "E4": [
            ("            val acquiredToken = AtomicReference<String?>(null)\n",
             "            val acquiredToken = AtomicReference<String?>(null)\n            val sawEntry = lockMap.containsKey(completeKey)\n"),
            ("                if (existing != null) {\n", "                if (sawEntry && existing != null) {\n"),
        ],
        "R1": [
            ("                    return@compute null\n                }\n\n                log.debug(",
             "                    existing.token = null\n                    return@compute null\n                }\n\n                log.debug("),
        ],
        "R2": [
            ("                        existing.token = null\n                    }\n\n                    // Existing entry: try to acquire semaphore\n",
             "                        existing.token = null\n"
             "                        val freshToken = nextToken()\n"
             "                        val fresh = LockInfo(Semaphore(1), now + lockTimeoutMillis)\n"
             "                        fresh.semaphore.tryAcquire()\n"
             "                        fresh.isHeld.set(true)\n"
             "                        fresh.token = freshToken\n"
             "                        acquiredToken.set(freshToken)\n"
             "                        return@compute fresh\n"
             "                    }\n\n                    // Existing entry: try to acquire semaphore\n"),
        ],
    },
}

# The main sources of req-shield's support module: nowToEpochTime and rejectsNewEntry, the trusted funs of the
# KeyLocalLock specs (B6: `kv` reads them to check the reviewed fingerprints).
SUPPORT_DIR = "support/src/main/kotlin"

# The Spring aspect modules (DESIGN-B.md 6.2): the main source directory, which the sidecar's `source` names, and the
# aspect file in it.
ASPECT_DIRS = {
    "core-spring": "core-spring/src/main/kotlin",
    "webflux": "core-spring-webflux/src/main/kotlin",
    "coroutine": "core-spring-webflux-kotlin-coroutine/src/main/kotlin",
}
ASPECT_FILES = {
    "core-spring": "com/linecorp/cse/reqshield/spring/aspect/ReqShieldAspect.kt",
    "webflux": "com/linecorp/cse/reqshield/spring/webflux/aspect/ReqShieldAspect.kt",
    "coroutine": "com/linecorp/cse/reqshield/spring/webflux/kotlin/coroutine/aspect/ReqShieldAspect.kt",
}

# getOrCreateReqShield is the same text in the three aspect files (core-spring 111, webflux 124, coroutine 231).
GET_OR_CREATE = ("    private fun getOrCreateReqShield(joinPoint: ProceedingJoinPoint): ReqShield<T> =\n"
                 "        reqShieldMap.computeIfAbsent(getTargetMethod(joinPoint)) {\n"
                 "            createReqShield(joinPoint)\n"
                 "        }\n")
EXPR_HEAD = "    private fun getOrCreateReqShield(joinPoint: ProceedingJoinPoint): ReqShield<T> =\n"
BLOCK_HEAD = "    private fun getOrCreateReqShield(joinPoint: ProceedingJoinPoint): ReqShield<T> {\n"


def get_or_create(body, block=True):
    """The edit that replaces getOrCreateReqShield with a new body (a block body, or an expression body)."""
    return (GET_OR_CREATE, (BLOCK_HEAD + body + "    }\n") if block else (EXPR_HEAD + body))


# Kotlin edits of DESIGN-B.md 7.2, the Kotlin side of spike/aspect/mutants.py (its comments give the same code). With
# the edit, the positions of the core-spring file are the ones mutants.py names (getOrCreateReqShield starts at 111).
ASPECT_EDITS = {
    "original": [],
    # R1: compute with an elvis in its lambda, and !! on its value.
    "R1": [get_or_create("        reqShieldMap.compute(getTargetMethod(joinPoint)) { _, old -> old ?: createReqShield(joinPoint) }!!\n", block=False)],
    # R2: a racy fast path before computeIfAbsent.
    "R2": [get_or_create("        val method = getTargetMethod(joinPoint)\n"
                         "        return reqShieldMap[method] ?: reqShieldMap.computeIfAbsent(method) { createReqShield(joinPoint) }\n")],
    # M1: get, then put.
    "M1": [get_or_create("        val method = getTargetMethod(joinPoint)\n"
                         "        return reqShieldMap[method] ?: createReqShield(joinPoint).also { reqShieldMap[method] = it }\n")],
    # M2: create, then an unconditional put.
    "M2": [get_or_create("        val created = createReqShield(joinPoint)\n"
                         "        reqShieldMap[getTargetMethod(joinPoint)] = created\n"
                         "        return created\n")],
    # M3: getOrPut.
    "M3": [get_or_create("        reqShieldMap.getOrPut(getTargetMethod(joinPoint)) { createReqShield(joinPoint) }\n", block=False)],
    # M4: create first, then putIfAbsent.
    "M4": [get_or_create("        val created = createReqShield(joinPoint)\n"
                         "        return reqShieldMap.putIfAbsent(getTargetMethod(joinPoint), created) ?: created\n")],
    # M5: one shared ReqShield field for every method. TODO() keeps the constructor arguments short; the field is a
    # free value of the action, and its ReqShield constructor makes the module INCOMPLETE (DESIGN-B.md 3.3 rule 4).
    "M5": [
        get_or_create("        reqShieldMap.computeIfAbsent(getTargetMethod(joinPoint)) {\n            sharedReqShield\n        }\n", block=False),
        ("    internal val reqShieldMap = ConcurrentHashMap<Method, ReqShield<T>>()\n",
         "    internal val reqShieldMap = ConcurrentHashMap<Method, ReqShield<T>>()\n"
         "    private val sharedReqShield: ReqShield<T> = ReqShield(ReqShieldConfiguration({ _, _, _ -> TODO() }, { TODO() }))\n"),
    ],
    # M6: a failing call drops the mapping (a stray remove). webflux and coroutine: ASPECT_MODULE_EDITS.
    "M6": [(
        "        return reqShield\n"
        "            .getAndSetReqShieldData(\n"
        "                cacheKey,\n"
        "                { joinPoint.proceed() as? T },\n"
        "                annotation.timeToLiveMillis,\n"
        "            ).value\n"
        "    }\n",
        "        try {\n"
        "            return reqShield\n"
        "                .getAndSetReqShieldData(\n"
        "                    cacheKey,\n"
        "                    { joinPoint.proceed() as? T },\n"
        "                    annotation.timeToLiveMillis,\n"
        "                ).value\n"
        "        } catch (e: Exception) {\n"
        "            reqShieldMap.remove(getTargetMethod(joinPoint))\n"
        "            throw e\n"
        "        }\n"
        "    }\n")],
    # M7: create first, then putIfAbsent, and ignore its result.
    "M7": [get_or_create("        val created = createReqShield(joinPoint)\n"
                         "        reqShieldMap.putIfAbsent(getTargetMethod(joinPoint), created)\n"
                         "        return created\n")],
}
# The M6 edit of the other two modules: their around functions differ from core-spring's, so the stray remove goes
# into a try around the getOrCreateReqShield call (the same mutant: a failure path that drops the mapping).
M6_TRY = (
    "val reqShield = getOrCreateReqShield(joinPoint)\n",
    "val reqShield = try { getOrCreateReqShield(joinPoint) } catch (e: Exception) { reqShieldMap.remove(getTargetMethod(joinPoint)); throw e }\n",
)
ASPECT_MODULE_EDITS = {"webflux": {"M6": [M6_TRY]}, "coroutine": {"M6": [M6_TRY]}}


class Suite:
    """A set of units (KeyLocalLock variants or aspect modules), their mutants, and how a copy of a unit is made."""

    def __init__(self, units, edits, unit_edits, sidecar, copies, edited):
        self.units = units            # unit -> directory relative to the req-shield checkout
        self.edits = edits            # mutant -> edits
        self.unit_edits = unit_edits  # unit -> mutant -> edits that replace the shared ones
        self.sidecar = sidecar        # unit -> sidecar file name
        self.copies = copies          # unit -> paths to copy, relative to the checkout (files or directories)
        self.edited = edited          # unit -> the file the edits apply to, relative to the checkout

    def edits_for(self, unit, mutant):
        return self.unit_edits.get(unit, {}).get(mutant, self.edits[mutant])


SUITES = {
    "keylocallock": Suite(
        VARIANT_DIRS, EDITS, VARIANT_EDITS, lambda v: "keylocallock.%s.kv" % v,
        # The support sources hold the library trusted funs, whose reviewed fingerprints `kv` checks (B6).
        lambda v: [os.path.join(VARIANT_DIRS[v], n) for n in ("KeyLock.kt", "KeyLocalLock.kt")] + [SUPPORT_DIR],
        lambda v: os.path.join(VARIANT_DIRS[v], "KeyLocalLock.kt")),
    "aspect": Suite(
        ASPECT_DIRS, ASPECT_EDITS, ASPECT_MODULE_EDITS, lambda m: "aspect.%s.kv" % m,
        lambda m: [ASPECT_DIRS[m]],
        lambda m: os.path.join(ASPECT_DIRS[m], ASPECT_FILES[m])),
}

# `kv verify` exit codes (DESIGN-v0.md 8.3, Q7).
VERDICTS = {0: "PROVEN", 1: "FAILED", 3: "INCOMPLETE", 4: "ERROR"}

ROW = re.compile(r"^  (?:action (\S+)|(stray action \S+))\s+\S+\s+(PROVEN|FAILED|UNSUPPORTED|ERROR)$")
OTHERS = re.compile(r"^  ((?:init|lemma \S+|probe) (?:PROVEN|FAILED|UNSUPPORTED|ERROR)(?: · |$))+")
# A generated obligation is named by what the tool generated (e.g. MI_OWN), without Silicon's reason in parentheses.
FINDING = re.compile(r"^      (?P<msg>[^:]+): (?:sidecar (?P<id>.+?) [\w./-]+\.kv:\d+:\d+|generated (?P<gen>[^()]+?)(?: \(.*\))?$|(?P<other>kotlin .+|no source range .+))")


class ScriptError(Exception):
    pass


def make_root(suite, req_shield, out, unit, mutant):
    root = os.path.join(out, "roots", "%s-%s" % (unit, mutant))
    shutil.rmtree(root, ignore_errors=True)
    for rel in suite.copies(unit):
        src, dst = os.path.join(req_shield, rel), os.path.join(root, rel)
        if os.path.isdir(src):
            shutil.copytree(src, dst)
        else:
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy(src, dst)
    path = os.path.join(root, suite.edited(unit))
    with open(path) as f:
        text = f.read()
    for old, new in suite.edits_for(unit, mutant):
        n = text.count(old)
        if n != 1:
            raise ScriptError("%s %s: edit matches %d times (must be 1): %r" % (unit, mutant, n, old))
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
            rows[current][1].add(f.group("id") or f.group("gen") or f.group("other"))
            continue
        if not line.startswith("      "):
            current = None
    return rows, others


def run_one(args, suite, variant, mutant):
    root = make_root(suite, args.req_shield, args.out, variant, mutant)
    vpr = os.path.join(args.out, "%s-%s.vpr" % (variant, mutant))
    command = [args.kv, "verify", "--root", root, "--classpath", args.classpath, "--kotlinc", args.kotlinc,
               "--out", vpr, os.path.join(args.specs, suite.sidecar(variant))]
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


def read_expected(path, suite):
    """Rows `unit  mutant  verdict  action  obligations`, columns separated by two or more spaces."""
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
            if variant not in suite.units or mutant not in suite.edits:
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
    ap.add_argument("--suite", choices=sorted(SUITES), default="keylocallock")
    for name in ("--req-shield", "--out"):
        ap.add_argument(name, required=True)
    for name in ("--kv", "--classpath", "--kotlinc", "--specs", "--expected"):
        ap.add_argument(name)
    ap.add_argument("--roots-only", action="store_true", help="write the mutant copies and list them; run nothing")
    ap.add_argument("--jobs", type=int, default=2)
    ap.add_argument("--timeout", type=int, default=900, help="seconds per kv verify run")
    ap.add_argument("--only", help="comma-separated mutant names")
    args = ap.parse_args()
    suite = SUITES[args.suite]
    try:
        only = set(args.only.split(",")) if args.only else None
        if only and not only <= set(suite.edits):
            raise ScriptError("unknown mutant(s): %s" % sorted(only - set(suite.edits)))
        if args.roots_only:
            for u in suite.units:
                for m in suite.edits:
                    if only is None or m in only:
                        print(u, m, make_root(suite, args.req_shield, args.out, u, m))
            return 0
        absent = [n for n in ("kv", "classpath", "kotlinc", "specs", "expected") if getattr(args, n) is None]
        if absent:
            raise ScriptError("a verify run needs %s" % ", ".join("--" + n for n in absent))
        expected = read_expected(args.expected, suite)
        work = [k for k in expected if only is None or k[1] in only]
        missing = [(v, m) for v in suite.units for m in suite.edits if (v, m) not in expected and (only is None or m in only)]
        if missing:
            raise ScriptError("no expected row for %s" % missing)
        os.makedirs(args.out, exist_ok=True)
        start = time.time()
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
            results = list(pool.map(lambda k: run_one(args, suite, *k), work))
    except ScriptError as e:
        print("mutants.py: script error: %s" % e, file=sys.stderr)
        return 2
    bad = 0
    print("%-10s %-9s %-8s %-6s %s" % ("variant" if args.suite == "keylocallock" else "module", "mutant", "verdict", "time", "failing obligations"))
    for r in results:
        problems = check(r, expected[(r["variant"], r["mutant"])])
        bad += bool(problems)
        print("%-10s %-9s %-8s %5.1fs %s%s" % (r["variant"], r["mutant"], r["verdict"], r["seconds"], fmt(r["failed"]),
                                              "" if not problems else "   MISMATCH: " + "; ".join(problems)))
    print("%d run(s), %d mismatch(es), %.1f s wall clock; reports and Viper files in %s" % (len(results), bad, time.time() - start, args.out))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
