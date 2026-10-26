"""Per-action comparison of the round-2b and round-3a tool verdicts with the ground truth of AI-CODE.md 11, and the
attribution of each change to the lowering (AI-CODE.md 14.1 A1-A5) or to the sidecar.

Usage: python3 -I aicode/round3a/compare.py REPO
The verdict reader, the ground truth and the agreement categories are those of aicode/round2/compare.py (loaded
from that file), as in aicode/round2b/compare.py. Round 3a runs the sidecar at the round-3a commit, which has
clauses that round 2b did not have. aicode/round3a/base holds the pre-change tool (2d479ee) on that same sidecar, so
base -> 3a is the effect of the lowering alone. Prints the actions whose verdict changed (2b -> 3a), the UNSUPPORTED
actions whose reasons changed, the counts per agreement category, and the base -> 3a changes."""
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

ROUNDS = (("round 2b", "aicode/round2b/logs"), ("round 3a", "aicode/round3a/logs"))
BASE = ("base", "aicode/round3a/base/logs")
KEYS = ["detected", "detected (cause not confirmed)", "correct PROVEN", "miss", "false alarm", "not resolved", "not judged"]


def cells(id, dirs):
    """(kind, text, agreement) of each action of [id] in each report directory of [dirs]."""
    reports = [r2.verdicts(os.path.join(repo, d, id, "tool.report"))[0] for _, d in dirs]
    out = {}
    for a in r2.ACTIONS:
        gt = r2.truth(id, a)
        out[a] = [(kind, text, r2.agreement(kind, gt)) for kind, text in (rep.get(a, ("missing", "missing")) for rep in reports)]
    return out


def table(rows, names):
    head = ["| ID | action | %s tool | %s tool | ground truth | %s | %s |" % (names[0], names[1], names[0], names[1]), "|---|---|---|---|---|---|---|"]
    return head + ["| %s | %s | %s | %s | %s | %s | %s |" % r for r in rows]


def counts_table(per, names):
    out = ["| agreement | " + " | ".join("%s %s" % (n, a) for n in names for a in r2.ACTIONS) + " | " + " | ".join("%s total" % n for n in names) + " |"]
    out.append("|---" * (1 + 4 * len(names)) + "|")
    for k in KEYS:
        if not any(per[n].get(a, {}).get(k) for n in names for a in r2.ACTIONS):
            continue
        row = [str(per[n].get(a, {}).get(k, 0)) for n in names for a in r2.ACTIONS]
        totals = [str(sum(per[n].get(a, {}).get(k, 0) for a in r2.ACTIONS)) for n in names]
        out.append("| %s | %s | %s |" % (k, " | ".join(row), " | ".join(totals)))
    return out


verdict_rows, reason_rows, base_rows = [], [], []
per = {n: {} for n, _ in ROUNDS + (BASE,)}
for id in r2.IDS:
    c = cells(id, ROUNDS + (BASE,))
    for a in r2.ACTIONS:
        gt = "defect" if r2.truth(id, a) == "defect" else "correct"
        (k2, t2, g2), (k3, t3, g3), (kb, tb, gbase) = c[a]
        for (name, _), g in zip(ROUNDS + (BASE,), (g2, g3, gbase)):
            per[name].setdefault(a, {}).setdefault(g.strip("*"), 0)
            per[name][a][g.strip("*")] += 1
        if k2 != k3:
            verdict_rows.append((id, a, t2, t3, gt, g2, g3))
        elif t2 != t3:
            reason_rows.append((id, a, t2, t3, gt, g2, g3))
        if (kb, tb) != (k3, t3):
            base_rows.append((id, a, tb, t3, gt, gbase, g3))

out = ["### Verdict changes (round 2b -> round 3a)", ""] + table(verdict_rows, ["round 2b", "round 3a"])
out += ["", "### Reason changes, same verdict (round 2b -> round 3a)", ""] + table(reason_rows, ["round 2b", "round 3a"])
out += ["", "### Counts", ""] + counts_table(per, ["round 2b", "round 3a"])
out += ["", "### Changes of the lowering alone (base: the 2d479ee tool on the round-3a sidecar -> round 3a)", ""] + table(base_rows, ["base", "round 3a"])
out += ["", "### Counts of the base run", ""] + counts_table(per, ["base"])
print("\n".join(out))
