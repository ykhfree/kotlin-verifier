"""Pilot clause isolation: which sidecar clauses of each action fail on the implementation, one clause at a time.

Usage: python3 -I isolate.py MODEL.vpr OUT_PREFIX

Silicon reports only the first failing postcondition of a path, so a FAILED report can hide other failing
clauses. For each index j, the harness writes a copy of MODEL.vpr in which every action method keeps the tool's
postconditions (everything up to and including `ensures HIST`) and only its j-th sidecar clause (the allowance
clauses and the sidecar ensures, in the emitted order). It runs Silicon on each copy and reports, per action and
clause, FAILED or PROVEN, with the clause's first Viper line as its label.
"""
import json
import os
import re
import subprocess
import sys

VIPER = os.path.expanduser("~/.local/opt/viper/v.26.08-release/tools")
ACTIONS = ("tryLock", "unLock", "sweep")


def methods(lines):
    """(name, header index, body index) of every action method."""
    out = []
    for i, l in enumerate(lines):
        m = re.match(r"method (\w+)\(", l)
        if m and m.group(1) in ACTIONS:
            j = i + 1
            while lines[j] != "{":
                j += 1
            out.append((m.group(1), i, j))
    return out


def items(lines, head, body):
    """The contract of one method as a list of (kind, [line indexes]): 'tool' up to HIST, then 'clause' items."""
    res, cur, after_hist, pending_comments = [], None, False, []
    for i in range(head + 1, body):
        l = lines[i]
        if l.startswith("  //"):
            pending_comments.append(i)
            continue
        if l.startswith("  requires") or l.startswith("  ensures"):
            kind = "clause" if (after_hist and l.startswith("  ensures")) else "tool"
            cur = (kind, pending_comments + [i])
            res.append(cur)
            pending_comments = []
            if l.strip() == "ensures HIST":
                after_hist = True
        elif cur is not None:
            cur[1].append(i)
    return res


def main():
    model, prefix = sys.argv[1], sys.argv[2]
    lines = open(model).read().split("\n")
    ms = methods(lines)
    other_methods = []
    for i, l in enumerate(lines):
        if l.startswith("method probe(") or l.startswith("method lemma_"):
            j = i + 1
            while j < len(lines) and not (lines[j] == "}" or lines[j] == "{}"):
                j += 1
            other_methods.append((i, j + 1))
    per = {name: [it for it in items(lines, h, b) if it[0] == "clause"] for name, h, b in ms}
    n = max(len(v) for v in per.values())
    result = {name: [] for name in per}
    for j in range(n):
        drop = set()
        kept = {}
        for name, cl in per.items():
            for idx, (_, ls) in enumerate(cl):
                if idx != j:
                    drop.update(ls)
                else:
                    kept[name] = ls
        # The probe and the lemmas are not about one action's clauses; without the other clauses the probe can
        # only time out, so the variant leaves them out.
        for a, b in other_methods:
            drop.update(range(a, b))
        variant = [l for i, l in enumerate(lines) if i not in drop]
        # Map the kept clause of each method to its line range in the variant.
        newpos = {}
        k = 0
        for i in range(len(lines)):
            if i not in drop:
                k += 1
                newpos[i] = k
        vpr = "%s.%d.vpr" % (prefix, j)
        open(vpr, "w").write("\n".join(variant))
        cmd = ["java", "-Xss128m", "-cp", os.path.join(VIPER, "backends/viperserver.jar"), "viper.silicon.SiliconRunner",
               "--logLevel", "ERROR", "--z3Exe", os.path.join(VIPER, "z3/bin/z3"), "--numberOfErrorsToReport", "0",
               "--assertTimeout", "20000", "--checkTimeout", "20000", vpr]
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=1800)
        out = p.stdout + p.stderr
        bad = [int(m.group(1)) for m in re.finditer(r"@(\d+)\.\d+--\d+\.\d+\)", out)]
        if "Silicon finished verification successfully" not in out and not re.search(r"Silicon found \d+ error", out):
            raise SystemExit("Silicon did not finish normally on %s:\n%s" % (vpr, out[-2000:]))
        for name, ls in kept.items():
            rng = {newpos[i] for i in ls}
            label = lines[[i for i in ls if not lines[i].startswith("  //")][0]].strip()
            comment = " ".join(lines[i].strip() for i in ls if lines[i].startswith("  //"))
            failed = any(b in rng for b in bad)
            result[name].append({"index": j, "clause": (comment + " " + label).strip()[:220], "verdict": "FAILED" if failed else "PROVEN"})
    json.dump(result, open(prefix + ".json", "w"), indent=1)
    for name, rs in result.items():
        for r in rs:
            print("%-8s %-7s %s" % (name, r["verdict"], r["clause"]))


if __name__ == "__main__":
    main()
