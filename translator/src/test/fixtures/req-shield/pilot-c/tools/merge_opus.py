"""Merges the two aligned-opus runs into results/aligned-opus.jsonl.

The first run (aligned-opus2, all 91 roots in INDEX order) was stopped after it covered the first 46 roots; the
second run (aligned-opus3) ran the other 45. Records of the first part are rebuilt from the per-root report files
with the same parser that tools/mutset.py uses; the verdict comes from the report's first line ("module: X").
"""
import json
import os
import re
import sys

SD = sys.argv[1]
sys.path.insert(0, "/Users/ykhfree/AI/kotlin-verifier/translator/scripts")
import mutants as kvm  # noqa: E402

names = [l.strip() for l in open(os.path.join(SD, "mutants", "roots", "INDEX.txt")) if l.strip()]
second = {json.loads(l)["mutant"]: json.loads(l) for l in open(os.path.join(SD, "results", "aligned-opus3.jsonl"))}
work = os.path.join(SD, "results", "aligned-opus2.jsonl.work")
out = []
missing = []
for n in names:
    if n in second:
        out.append(second[n])
        continue
    p = os.path.join(work, n.replace(":", "_") + ".report")
    if not os.path.exists(p):
        missing.append(n)
        continue
    text = open(p).read().split("\n--- stderr ---\n")[0]
    m = re.search(r"module: (PROVEN|FAILED|INCOMPLETE|ERROR)", text.splitlines()[0] if text else "")
    rows, others, checks = kvm.parse_report(text)
    out.append({"mutant": n, "verdict": m.group(1) if m else "ERROR",
                "actions": {a: [v, sorted(ids)] for a, (v, ids) in rows.items()},
                "others": [" ".join(o) for o in others],
                "pass2": {a: [s, sorted(ids)] for a, (s, ids) in checks.items()}, "from": "report"})
if missing:
    raise SystemExit("no record for %d roots: %s" % (len(missing), missing[:5]))
with open(os.path.join(SD, "results", "aligned-opus.jsonl"), "w") as f:
    for r in out:
        f.write(json.dumps(r) + "\n")
print("%d records (%d from the second run)" % (len(out), len(second)))
