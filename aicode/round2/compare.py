"""Per-action comparison of the round-1 and round-2 tool verdicts with the ground truth of AI-CODE.md 11."""
import os, re, sys
repo = sys.argv[1]
IDS = ["T-haiku-1", "T-haiku-2", "T-haiku-3", "T-haiku-4", "T-sonnet-1", "T-sonnet-2", "T-sonnet-3", "T-sonnet-4", "T-opus-1", "T-opus-2", "T-opus-3", "T-opus-4"]
ACTIONS = ["tryLock", "unLock", "sweep"]
ROW = re.compile(r"^  action (\S+)\s+\S+\s+(PROVEN|FAILED|UNSUPPORTED|ERROR)(?: \(([^()]+)\))?$")
CONSTRUCT = re.compile(r"UNSUPPORTED (.+?) in action ")
# AI-CODE.md 11 ground truth: tryLock and sweep defective in all 12; unLock defective in T-haiku-3 and T-sonnet-3.
def truth(id, action):
    if action == "unLock":
        return "defect" if id in ("T-haiku-3", "T-sonnet-3") else "correct"
    return "defect"

def verdicts(report_path):
    text = open(report_path).read()
    lines = text.splitlines()
    out = {}
    for i, l in enumerate(lines):
        m = ROW.match(l)
        if not m:
            continue
        a, v, sub = m.groups()
        if v == "UNSUPPORTED":
            note = lines[i + 1].strip() if i + 1 < len(lines) else ""
            cons = []
            for c in CONSTRUCT.findall(note):
                if c not in cons:
                    cons.append(c)
            out[a] = ("UNSUPPORTED", "UNSUPPORTED (%s)" % "; ".join(cons))
        elif v == "FAILED":
            out[a] = ("FAILED " + sub, "FAILED (%s)" % sub)
        else:
            out[a] = (v, v)
    error = next((l for l in lines if l.startswith("  ERROR: ")), None)
    if error:
        # The module was not verified; an action without a row of its own has no verdict.
        ws = sorted(set(re.findall(r"\b(W\d+) at ", error)))
        for a in ACTIONS:
            out.setdefault(a, ("ERROR", "ERROR (tool defect: KVIR violates %s)" % ", ".join(ws)))
    if not out:
        errs = [l for l in lines if "error[" in l]
        code = re.search(r"error\[([\w-]+)\]", errs[0]).group(1) if errs else "?"
        return {a: ("out of range", "out of range (binding failure: %s)" % code) for a in ACTIONS}, text
    return out, text

def agreement(kind, gt):
    if kind == "PROVEN":
        return "**miss**" if gt == "defect" else "correct PROVEN"
    if kind == "FAILED property":
        return "detected" if gt == "defect" else "**false alarm**"
    if kind.startswith("FAILED invariant only"):
        return "detected (cause not confirmed)" if gt == "defect" else "not resolved"
    return "not judged"

rows, counts = [], {1: {}, 2: {}}
per_action = {1: {}, 2: {}}
for id in IDS:
    r1, _ = verdicts(os.path.join(repo, "aicode/logs", id, "tool.report"))
    r2, _ = verdicts(os.path.join(repo, "aicode/round2/logs", id, "tool.report"))
    for a in ACTIONS:
        gt = truth(id, a)
        k1, t1 = r1.get(a, ("missing", "missing"))
        k2, t2 = r2.get(a, ("missing", "missing"))
        g1, g2 = agreement(k1, gt), agreement(k2, gt)
        for rnd, g in ((1, g1), (2, g2)):
            key = g.strip("*")
            counts[rnd][key] = counts[rnd].get(key, 0) + 1
            per_action[rnd].setdefault(a, {}).setdefault(key, 0)
            per_action[rnd][a][key] += 1
        rows.append((id, a, t1, t2, "defect" if gt == "defect" else "correct", g1, g2))

out = ["| ID | action | round 1 tool | round 2 tool | ground truth | round 1 | round 2 |", "|---|---|---|---|---|---|---|"]
for r in rows:
    out.append("| %s | %s | %s | %s | %s | %s | %s |" % r)
out.append("")
keys = ["detected", "detected (cause not confirmed)", "correct PROVEN", "miss", "false alarm", "not resolved", "not judged"]
out.append("| agreement | " + " | ".join("round %d %s" % (n, a) for n in (1, 2) for a in ACTIONS) + " | round 1 total | round 2 total |")
out.append("|---" * 9 + "|")
for k in keys:
    if not any(counts[n].get(k) for n in (1, 2)):
        continue
    cells = [str(per_action[n].get(a, {}).get(k, 0)) for n in (1, 2) for a in ACTIONS]
    out.append("| %s | %s | %d | %d |" % (k, " | ".join(cells), counts[1].get(k, 0), counts[2].get(k, 0)))
print("\n".join(out))
