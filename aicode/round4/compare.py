"""Per-action comparison of round 4 (the tool after DESIGN-D (a), AI-CODE.md 15) with the rounds before it, against
the ground truth of the implementations:

  r2  the 12 second-round implementations: aicode/round3a/logs -> aicode/round4/r2/logs; ground truth AI-CODE.md 11
  g   the 12 fresh implementations: aicode/round3g/logs -> aicode/round4/g/logs; ground truth AI-CODE.md 14.7

Usage: python3 -I aicode/round4/compare.py REPO
The report reader, the round-2 ground truth and the agreement categories are those of aicode/round2/compare.py
(loaded from that file), as in aicode/round3a/compare.py. The ground truth of the fresh 12 is the summary of
AI-CODE.md 14.7: tryLock and sweep are defective in all 12, unLock only in G-haiku-3. Prints, per set, the actions
whose verdict changed, the actions whose reasons changed, the counts per agreement category, and the reserve steps."""
import importlib.util, os, re, sys

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

G_IDS = ["G-haiku-%d" % i for i in range(1, 5)] + ["G-sonnet-%d" % i for i in range(1, 5)] + ["G-opus-%d" % i for i in range(1, 5)]


def g_truth(id, action):
    """AI-CODE.md 14.7: tryLock and sweep defective in all 12 fresh implementations; unLock only in G-haiku-3."""
    if action == "unLock":
        return "defect" if id == "G-haiku-3" else "correct"
    return "defect"


SETS = (
    ("r2", r2.IDS, r2.truth, ("round 3a", "aicode/round3a/logs"), ("round 4", "aicode/round4/r2/logs")),
    ("g", G_IDS, g_truth, ("round 3g", "aicode/round3g/logs"), ("round 4", "aicode/round4/g/logs")),
)
KEYS = ["detected", "detected (cause not confirmed)", "correct PROVEN", "miss", "false alarm", "not resolved", "not judged"]
STEP = re.compile(r"^  step (reserve \S+)\s+(\S+)\s+(PROVEN|FAILED|UNSUPPORTED|ERROR)(?: \(([^()]+)\))?$")
FIRST = re.compile(r"^      [^:]+: (?:sidecar (.+?) [\w./-]+\.kv:\d+:\d+|generated ([^()]+?)(?: \(.*\))?$)")


def first_failures(text, action):
    """The obligations of the pass-1 finding lines of [action]'s row, in order, and of its pass-2 lines."""
    lines = text.splitlines()
    out, pass2, inside = [], [], False
    for l in lines:
        if l.startswith("  action ") or l.startswith("  step ") or l.startswith("  init "):
            inside = l.split()[1] == action and l.startswith("  action ")
            continue
        if not inside:
            continue
        if l.startswith("        pass 2 (property check): "):
            m = FIRST.match("      " + l[len("        pass 2 (property check): "):])
            if m:
                pass2.append(m.group(1) or m.group(2))
            continue
        m = FIRST.match(l)
        if m:
            out.append(m.group(1) or m.group(2))
        elif not l.startswith("      "):
            inside = False
    return out, pass2


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


out = []
for name, ids, truth, before, after in SETS:
    verdict_rows, reason_rows, failures, steps = [], [], [], []
    per = {before[0]: {}, after[0]: {}}
    for id in ids:
        reports = [r2.verdicts(os.path.join(repo, d, id, "tool.report")) for _, d in (before, after)]
        for a in r2.ACTIONS:
            gt = truth(id, a)
            (kb, tb), (ka, ta) = [rep.get(a, ("missing", "missing")) for rep, _ in reports]
            gb, ga = r2.agreement(kb, gt), r2.agreement(ka, gt)
            for n, g in ((before[0], gb), (after[0], ga)):
                per[n].setdefault(a, {}).setdefault(g.strip("*"), 0)
                per[n][a][g.strip("*")] += 1
            row = (id, a, tb, ta, "defect" if gt == "defect" else "correct", gb, ga)
            if kb != ka:
                verdict_rows.append(row)
                p1, p2 = first_failures(reports[1][1], a)
                failures.append("| %s | %s | %s | %s | %s |" % (id, a, ta, ", ".join(p1) or "-", ", ".join(p2) or "-"))
            elif tb != ta:
                reason_rows.append(row)
        for l in reports[1][1].splitlines():
            m = STEP.match(l)
            if m:
                steps.append("| %s | %s | %s | %s |" % (id, m.group(1), m.group(2), m.group(3) + (" (%s)" % m.group(4) if m.group(4) else "")))
    names = [before[0], after[0]]
    out += ["## Set %s: %s -> %s" % (name, before[1], after[1]), ""]
    out += ["### Verdict changes", ""] + table(verdict_rows, names)
    out += ["", "### Failing obligations of the newly judged actions (round 4 report; pass 1, pass 2)", "", "| ID | action | round 4 tool | pass 1 | pass 2 |", "|---|---|---|---|---|"] + failures
    out += ["", "### Reason changes, same verdict", ""] + table(reason_rows, names)
    out += ["", "### Counts", ""] + counts_table(per, names)
    out += ["", "### Reserve steps (round 4)", "", "| ID | step | increment | verdict |", "|---|---|---|---|"] + steps + [""]
print("\n".join(out))
