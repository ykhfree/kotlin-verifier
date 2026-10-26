"""Full set of broken obligations per model (DESIGN-E 6.3), as DESIGN-B.md 0 measured it for the aspect spike.

Silicon stops a path at its first failing check, so one run shows only the first failure per path. This script makes
one copy per property ID that keeps only that ID's tagged checks (`ensures|assert|invariant <e> // <ID>`) and turns the
other tagged checks into `true`, plus one copy with every tagged check off. It verifies all copies and maps each error to
a property ID. Untagged checks stay on in every copy: the obligation transfer of a spawn (LD_transfer), the unLock
precondition (LD_own), the loop invariants of the hole (HOLE) and the loop measure (TERM).
Usage: python3 -I isolate.py OUT_DIR FILE.vpr...   (prints one line per file: name, then the IDs that failed)
"""
import concurrent.futures
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SILICON = os.path.join(HERE, "..", "silicon.sh")
IDS = ["LD_noleak", "LD_supplier", "LD_write", "LD_wait", "SUP_once", "MODE_create", "MODE_update", "POLL_bound",
       "RET_hit", "RET_get_error", "RET_recheck", "RET_poll", "RET_supplier", "RET_supplier_error"]
TAGGED = re.compile(r"^(\s*(?:ensures|assert|invariant)\s+)(.*?)(\s*//\s*(" + "|".join(IDS) + r")\s*)$")
ERROR = re.compile(r"^\s*\[\s*\d+\] (.*)\(([^()@]*)@(\d+)\.\d+--\d+\.\d+\)\s*$")


def copy_with(text, keep):
    """The text with every tagged check whose ID is not `keep` turned into `true`."""
    out = []
    for line in text.split("\n"):
        m = TAGGED.match(line)
        if m and m.group(4) != keep:
            line = m.group(1) + "true" + m.group(3)
        out.append(line)
    return "\n".join(out)


def classify(message, line):
    m = TAGGED.match(line)
    if m:
        return m.group(4)
    if "precondition of method KeyLock_unLock" in message:
        return "LD_own"
    if message.startswith("Exhale might fail"):
        return "LD_transfer"
    if message.startswith("Loop might not terminate"):
        return "TERM"
    if message.startswith("Loop invariant"):
        return "HOLE"
    return "OTHER(%s)" % message[:40]


def verify(path):
    proc = subprocess.run([SILICON, "--numberOfErrorsToReport", "0", path], capture_output=True, text=True, timeout=600)
    lines = open(path).read().split("\n")
    found = set()
    for err in proc.stdout.split("\n"):
        m = ERROR.match(err)
        if m:
            found.add(classify(m.group(1), lines[int(m.group(3)) - 1]))
    if proc.returncode not in (0, 1) or (proc.returncode == 1 and not found):
        found.add("ERROR(exit %d)" % proc.returncode)
    return found


def main(out_dir, files):
    os.makedirs(out_dir, exist_ok=True)
    jobs = {}
    for f in files:
        name = os.path.splitext(os.path.basename(f))[0]
        text = open(f).read()
        for keep in IDS + ["none"]:
            path = os.path.join(out_dir, "%s.%s.vpr" % (name, keep))
            with open(path, "w") as g:
                g.write(copy_with(text, keep))
            jobs[path] = name
    result = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        for path, found in zip(jobs, pool.map(verify, jobs)):
            result.setdefault(jobs[path], set()).update(found)
    for f in files:
        name = os.path.splitext(os.path.basename(f))[0]
        print("%s: %s" % (name, ", ".join(sorted(result[name])) or "none"))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
