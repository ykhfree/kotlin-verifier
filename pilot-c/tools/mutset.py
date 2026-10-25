"""Pilot mutant set and runner (coroutine KeyLocalLock).

Usage:
  python3 -I mutset.py roots              builds mutants/roots/<name>/ for every mutant (idempotent)
  python3 -I mutset.py run SPEC_DIR OUT_JSONL [--jobs N] [--only a,b]
      runs kv verify of SPEC_DIR/keylocallock.coroutine.kv on every root; one JSON line per mutant

The set:
  hand:<name>   the 11 hand-picked mutants and the original (translator/scripts/mutants.py --roots-only)
  mech:<id>     coroutine mechanical mutants of mutation/rerun/results.jsonl on KeyLocalLock.kt with a PROVEN or
                FAILED verdict under the signed sidecar (offset edits re-applied to the 2e792d2 source)
  dm:<name>     decision mutants: the reference with the other option of one catalog decision (PREREG.md 2)
  ai:<id>       the aicode round-2 implementations whose sweep the tool judged, with their binding adaptations
"""
import concurrent.futures
import json
import os
import re
import shutil
import subprocess
import sys
import time

SD = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, "/Users/ykhfree/AI/kotlin-verifier/translator/scripts")
import mutants as kvm  # noqa: E402  (read only: parse_report and the report regexes)

KV = os.path.join(SD, "kv-frozen", "bin", "kv")
CP = open(os.path.join(SD, "cp.txt")).read().strip()
KOTLINC = open(os.path.join(SD, "kotlinc.txt")).read().strip()
REQ = "/Users/ykhfree/IdeaProjects/req-shield"
REL = "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLocalLock.kt"
HAND = os.path.join(SD, "mutants", "hand", "roots")
ROOTS = os.path.join(SD, "mutants", "roots")
MECH = "/Users/ykhfree/AI/kotlin-verifier/mutation/rerun/results.jsonl"
AI = "/Users/ykhfree/AI/kotlin-verifier/aicode/round2/impl"
AI_IDS = ["T-haiku-2", "T-haiku-4", "T-sonnet-1", "T-sonnet-3", "T-sonnet-4", "T-opus-1", "T-opus-2"]

# Decision mutants: (edits as exact strings, each must match once). The comment names the decision of PREREG.md 2.
DECISION = {
    # D1: expired at now == expiresAt, in tryLock and in the monitor (what the 12 AI implementations did).
    "D1-both": [("if (now > existing.expiresAt && existing.isHeld", "if (now >= existing.expiresAt && existing.isHeld"),
                ("if (now > lockInfo.expiresAt) {", "if (now >= lockInfo.expiresAt) {")],
    # D2: the holder's unLock fails once its lease is over, even if nobody took the lock over.
    "D2-expired-unlock-false": [("if (existing.token == token && existing.isHeld.compareAndSet(true, false)) {",
                                 "if (existing.token == token && nowToEpochTime() <= existing.expiresAt && existing.isHeld.compareAndSet(true, false)) {")],
    # D4: tryLock never takes an expired entry over; the key waits for the monitor.
    "D4-no-takeover": [("if (now > existing.expiresAt && existing.isHeld.compareAndSet(true, false)) {", "if (false) {")],
    # D9: the monitor never drops an entry.
    "D9-sweep-never": [("if (now > lockInfo.expiresAt) {", "if (false) {")],
    # D3: at the cap, a new key gets null instead of an unbacked token.
    "D3-cap-null": [("                    acquiredToken.set(nextToken())\n", "")],
}


def copy_original(dst):
    shutil.rmtree(dst, ignore_errors=True)
    shutil.copytree(os.path.join(HAND, "coroutine-original"), dst)


def build_roots():
    os.makedirs(ROOTS, exist_ok=True)
    made = []
    for d in sorted(os.listdir(HAND)):
        if d.startswith("coroutine-"):
            name = "hand:" + d[len("coroutine-"):]
            dst = os.path.join(ROOTS, name.replace(":", "_"))
            shutil.rmtree(dst, ignore_errors=True)
            shutil.copytree(os.path.join(HAND, d), dst)
            made.append(name)
    original = open(os.path.join(HAND, "coroutine-original", REL)).read()
    for line in open(MECH):
        r = json.loads(line)
        if not r["id"].startswith("keylocallock.coroutine/") or r["verdict"] not in ("PROVEN", "FAILED"):
            continue
        if not r["location"].startswith(REL):
            continue
        text = original
        for e in sorted(r["edits"], key=lambda e: -e["start"]):
            if text[e["start"]:e["end"]] != e["original"]:
                raise SystemExit("%s: edit does not match the source at %d" % (r["id"], e["start"]))
            text = text[:e["start"]] + e["replacement"] + text[e["end"]:]
        name = "mech:" + r["id"].split("/", 1)[1]
        dst = os.path.join(ROOTS, name.replace(":", "_"))
        copy_original(dst)
        open(os.path.join(dst, REL), "w").write(text)
        made.append(name)
    for dm, edits in DECISION.items():
        text = original
        for old, new in edits:
            if text.count(old) != 1:
                raise SystemExit("%s: edit matches %d times" % (dm, text.count(old)))
            text = text.replace(old, new)
        name = "dm:" + dm
        dst = os.path.join(ROOTS, name.replace(":", "_"))
        copy_original(dst)
        open(os.path.join(dst, REL), "w").write(text)
        made.append(name)
    for i in AI_IDS:
        name = "ai:" + i
        dst = os.path.join(ROOTS, name.replace(":", "_"))
        copy_original(dst)
        shutil.copy(os.path.join(AI, i, "KeyLocalLock.kt"), os.path.join(dst, REL))
        made.append(name)
    with open(os.path.join(ROOTS, "INDEX.txt"), "w") as f:
        f.write("\n".join(made) + "\n")
    print("%d roots" % len(made))


def adapt_spec(spec_dir, name, work):
    """The aicode binding adaptations of an AI implementation, applied to a copy of SPEC_DIR."""
    if not name.startswith("ai:"):
        return spec_dir
    path = os.path.join(AI, name[3:], "bindings.txt")
    if not os.path.exists(path):
        return spec_dir
    dst = os.path.join(work, "spec-" + name.replace(":", "_"))
    shutil.rmtree(dst, ignore_errors=True)
    shutil.copytree(spec_dir, dst)
    common = os.path.join(dst, "keylocallock.common.kv")
    variant = os.path.join(dst, "keylocallock.coroutine.kv")
    for line in open(path):
        words = line.split("#", 1)[0].split()
        if not words:
            continue
        if words[0] == "class":
            t = open(common).read()
            t2, n = re.subn(r"(?m)^class %s = \S+" % re.escape(words[1]), "class %s = %s" % (words[1], words[2]), t)
            if n != 1:
                raise SystemExit("%s: class adaptation matched %d" % (name, n))
            open(common, "w").write(t2)
        elif words[0] == "use":
            open(variant, "a").write("use %s\n" % words[1])
        else:
            raise SystemExit("%s: adaptation %r is not supported here" % (name, words))
    return dst


def run_one(spec_dir, name, work):
    root = os.path.join(ROOTS, name.replace(":", "_"))
    spec = adapt_spec(spec_dir, name, work)
    vpr = os.path.join(work, name.replace(":", "_") + ".vpr")
    cmd = [KV, "verify", "--root", root, "--classpath", CP, "--kotlinc", KOTLINC, "--out", vpr,
           os.path.join(spec, "keylocallock.coroutine.kv")]
    start = time.time()
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=1200)
    secs = time.time() - start
    with open(os.path.join(work, name.replace(":", "_") + ".report"), "w") as f:
        f.write(p.stdout + "\n--- stderr ---\n" + p.stderr)
    verdict = kvm.VERDICTS.get(p.returncode, "exit %d" % p.returncode)
    rec = {"mutant": name, "verdict": verdict, "seconds": round(secs, 1), "actions": {}, "others": [], "pass2": {}}
    try:
        rows, others, checks = kvm.parse_report(p.stdout)
        rec["actions"] = {a: [v, sorted(ids)] for a, (v, ids) in rows.items()}
        rec["others"] = [" ".join(o) for o in others]
        rec["pass2"] = {a: [s, sorted(ids)] for a, (s, ids) in checks.items()}
    except kvm.ScriptError as e:
        rec["parse_error"] = str(e)
    if p.returncode == 4:
        rec["error"] = (p.stderr or p.stdout)[-600:]
    return rec


def run(spec_dir, out, jobs=3, only=None):
    names = [l.strip() for l in open(os.path.join(ROOTS, "INDEX.txt")) if l.strip()]
    if only:
        names = [n for n in names if n in only or n.split(":")[0] + ":*" in only]
    work = out + ".work"
    os.makedirs(work, exist_ok=True)
    start = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        recs = list(pool.map(lambda n: run_one(spec_dir, n, work), names))
    with open(out, "w") as f:
        for r in recs:
            f.write(json.dumps(r) + "\n")
    print("%d runs, %.0f s wall clock -> %s" % (len(recs), time.time() - start, out))


def main():
    if sys.argv[1] == "roots":
        build_roots()
    elif sys.argv[1] == "run":
        jobs = int(sys.argv[sys.argv.index("--jobs") + 1]) if "--jobs" in sys.argv else 3
        only = set(sys.argv[sys.argv.index("--only") + 1].split(",")) if "--only" in sys.argv else None
        run(sys.argv[2], sys.argv[3], jobs, only)


if __name__ == "__main__":
    main()
