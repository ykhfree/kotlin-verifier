"""Full set of broken obligations per model, as spike/protocol2/isolate.py (DESIGN-E 5.2), with RET_cancel (E5).

One copy per property ID keeps only that ID's tagged checks (`ensures|assert|invariant <e> // <ID>`) and turns the other
tagged checks into `true`, plus one copy with every tagged check off. Untagged checks stay on in every copy: the spawn's
exhale (LD_transfer), the unLock precondition (LD_own), the hole invariants (HOLE) and the measure (TERM).
Usage: python3 -I isolate_c.py SILICON OUT_DIR FILE.vpr...   (prints "<base>/<name>: <IDs>" per file)
"""
import concurrent.futures
import os
import re
import subprocess
import sys

IDS = ["LD_noleak", "LD_supplier", "LD_write", "LD_wait", "SUP_once", "MODE_create", "MODE_update", "POLL_bound",
       "RET_hit", "RET_get_error", "RET_recheck", "RET_poll", "RET_supplier", "RET_supplier_error", "RET_cancel"]
TAGGED = re.compile(r"^(\s*(?:ensures|assert|invariant)\s+)(.*?)(\s*//\s*(" + "|".join(IDS) + r")\s*)$")
ERROR = re.compile(r"^\s*\[\s*\d+\] (.*)\(([^()@]*)@(\d+)\.\d+--\d+\.\d+\)\s*$")


def copy_with(text, keep):
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
    if message.startswith("Loop might not terminate") or "might not decrease" in message or "might not be bounded" in message:
        return "TERM"
    if message.startswith("Loop invariant"):
        return "HOLE"
    return "OTHER(%s)" % message[:60]


def verify(args):
    silicon, path = args
    proc = subprocess.run([silicon, "--numberOfErrorsToReport", "0", path], capture_output=True, text=True, timeout=900)
    lines = open(path).read().split("\n")
    found = set()
    for err in proc.stdout.split("\n"):
        m = ERROR.match(err)
        if m:
            found.add(classify(m.group(1), lines[int(m.group(3)) - 1]))
    if proc.returncode not in (0, 1) or (proc.returncode == 1 and not found):
        found.add("ERROR(exit %d: %s)" % (proc.returncode, proc.stdout.strip()[:200].replace("\n", " ")))
    return found


def key(path):
    return os.path.basename(os.path.dirname(os.path.abspath(path))) + "/" + os.path.splitext(os.path.basename(path))[0]


def main(silicon, out_dir, files):
    os.makedirs(out_dir, exist_ok=True)
    jobs = {}
    for f in files:
        name = key(f)
        text = open(f).read()
        for keep in IDS + ["none"]:
            path = os.path.join(out_dir, "%s.%s.vpr" % (name.replace("/", "."), keep))
            with open(path, "w") as g:
                g.write(copy_with(text, keep))
            jobs[path] = name
    result = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=int(os.environ.get("JOBS", "8"))) as pool:
        for path, found in zip(jobs, pool.map(verify, [(silicon, p) for p in jobs])):
            result.setdefault(jobs[path], set()).update(found)
    for f in files:
        name = key(f)
        print("%s: %s" % (name, ", ".join(sorted(result[name])) or "none"))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3:])
