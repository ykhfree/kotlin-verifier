"""DESIGN-C.md C5 acceptance 5: deliberate breaks of scripts/spec.py. Each break is applied, the tests that must catch
it run (./gradlew cleanTest test --tests ...), and spec.py is restored, also on an error.

Usage: python3 -I specdraft/breaks.py [BREAK...]   (default: all; one line per break and test: passed or FAILED)
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

R = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SPEC = R + "/translator/scripts/spec.py"
CLS = "kv.scripts.SpecPipelineScriptTest"

BREAKS = {
    # A correction round carries the first line of the check report (the draft verdict note).
    "B1a-report-line": (
        '    errors += [error_line(run, e, work) for e in check.get("errors") or []]\n',
        '    errors += [error_line(run, e, work) for e in check.get("errors") or []]\n'
        '    errors += [read(os.path.join(work, "check", "report.txt")).splitlines()[0]]\n',
        ["collect - a correction round*"]),
    # A correction round carries the reason of the Silicon run instead of its findings.
    "B1b-silicon-reason": (
        '        found = [error_line(run, f, work) for f in b.get("findings") or []]\n',
        '        found = [b["error"]] + [error_line(run, f, work) for f in b.get("findings") or []]\n',
        ["collect - a correction round*"]),
    # The phase-2 box gets a copy of the draft.
    "B2a-draft-in-box2": (
        '    write(os.path.join(box2, "TASK.md"), TASK2.format(budget=CHECK_BUDGET))\n',
        '    write(os.path.join(box2, "TASK.md"), TASK2.format(budget=CHECK_BUDGET))\n'
        '    shutil.copy(os.path.join(box1, "DRAFT.kv"), box2)\n',
        ["cells and phase 2*"]),
    # The phase-1 box stays writable.
    "B2b-box1-writable": (
        '            os.chmod(p, stat.S_IRUSR | stat.S_IRGRP | stat.S_IROTH)\n',
        '            pass\n',
        ["cells and phase 2*"]),
    # The align check takes an OPEN cell as decided.
    "B3a-open-accepted": (
        '            if not (short(got) == want if exact else kind == "DETERMINED" and want in outs):\n',
        '            if not (short(got) == want if exact else kind == "OPEN" or kind == "DETERMINED" and want in outs):\n',
        ["align*"]),
    # The align check takes any DETERMINED cell as decided, whatever its outcome.
    "B3b-any-outcome": (
        '            if not (short(got) == want if exact else kind == "DETERMINED" and want in outs):\n',
        '            if not (short(got) == want if exact else kind == "DETERMINED"):\n',
        ["align*"]),
}


def run_tests(patterns):
    cmd = ["./gradlew", "cleanTest", "test"] + [a for p in patterns for a in ("--tests", "%s.%s" % (CLS, p))]
    subprocess.run(cmd, cwd=R + "/translator", capture_output=True, text=True)
    root = ET.parse(R + "/translator/build/test-results/test/TEST-%s.xml" % CLS).getroot()
    out = []
    for tc in root.findall("testcase"):
        f = tc.find("failure")
        out.append((tc.get("name"), "FAILED" if f is not None else "passed", (f.get("message") or "")[:300] if f is not None else ""))
    return out


def main():
    original = open(SPEC).read()
    names = sys.argv[1:] or list(BREAKS)
    try:
        for name in names:
            old, new, tests = BREAKS[name]
            assert original.count(old) == 1, name
            open(SPEC, "w").write(original.replace(old, new))
            for test, verdict, msg in run_tests(tests):
                print("%s | %s | %s | %s" % (name, test, verdict, " ".join(msg.split())[:200]))
            open(SPEC, "w").write(original)
    finally:
        open(SPEC, "w").write(original)


if __name__ == "__main__":
    main()
