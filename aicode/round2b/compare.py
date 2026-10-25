"""Per-action comparison of the round-2 and round-2b tool verdicts with the ground truth of AI-CODE.md 11.

Usage: python3 -I aicode/round2b/compare.py REPO
The verdict reader, the ground truth and the agreement categories are those of aicode/round2/compare.py (loaded
from that file); only the two report directories and the output differ. Prints the actions whose verdict changed,
the UNSUPPORTED actions whose reasons changed, and the counts per agreement category."""
import importlib.util, os, sys

repo = sys.argv[1]
spec = importlib.util.spec_from_file_location("round2_compare", os.path.join(repo, "aicode/round2/compare.py"))
r2 = importlib.util.module_from_spec(spec)
# round2/compare.py runs its round-1 vs round-2 table at import; keep its output out of this one.
saved, sys.stdout = sys.stdout, open(os.devnull, "w")
try:
    spec.loader.exec_module(r2)
finally:
    sys.stdout.close()
    sys.stdout = saved

ROUNDS = (("round 2", "aicode/round2/logs"), ("round 2b", "aicode/round2b/logs"))
verdict_rows, reason_rows = [], []
counts = {n: {} for n, _ in ROUNDS}
per_action = {n: {} for n, _ in ROUNDS}
for id in r2.IDS:
    reports = [r2.verdicts(os.path.join(repo, d, id, "tool.report"))[0] for _, d in ROUNDS]
    for a in r2.ACTIONS:
        gt = r2.truth(id, a)
        cells = []
        for (name, _), rep in zip(ROUNDS, reports):
            kind, text = rep.get(a, ("missing", "missing"))
            g = r2.agreement(kind, gt)
            counts[name][g.strip("*")] = counts[name].get(g.strip("*"), 0) + 1
            per_action[name].setdefault(a, {}).setdefault(g.strip("*"), 0)
            per_action[name][a][g.strip("*")] += 1
            cells.append((kind, text, g))
        (k2, t2, g2), (kb, tb, gb) = cells
        row = (id, a, t2, tb, "defect" if gt == "defect" else "correct", g2, gb)
        if k2 != kb:
            verdict_rows.append(row)
        elif t2 != tb:
            reason_rows.append(row)

head = ["| ID | action | round 2 tool | round 2b tool | ground truth | round 2 | round 2b |", "|---|---|---|---|---|---|---|"]
out = ["### Verdict changes", ""] + head + ["| %s | %s | %s | %s | %s | %s | %s |" % r for r in verdict_rows]
out += ["", "### Reason changes (same verdict)", ""] + head + ["| %s | %s | %s | %s | %s | %s | %s |" % r for r in reason_rows]
out += ["", "### Counts", ""]
keys = ["detected", "detected (cause not confirmed)", "correct PROVEN", "miss", "false alarm", "not resolved", "not judged"]
names = [n for n, _ in ROUNDS]
out.append("| agreement | " + " | ".join("%s %s" % (n, a) for n in names for a in r2.ACTIONS) + " | " + " | ".join("%s total" % n for n in names) + " |")
out.append("|---" * 9 + "|")
for k in keys:
    if not any(counts[n].get(k) for n in names):
        continue
    cells = [str(per_action[n].get(a, {}).get(k, 0)) for n in names for a in r2.ACTIONS]
    out.append("| %s | %s | %s |" % (k, " | ".join(cells), " | ".join(str(counts[n].get(k, 0)) for n in names)))
print("\n".join(out))
