"""Pilot comparison of mutant verdicts: each draft against the signed sidecar on the same 89 roots.

Usage: python3 -I compare.py RESULTS_DIR

Reads RESULTS_DIR/{signed,haiku,sonnet,opus}.jsonl (tools/mutset.py output). Prints:
  1. the module verdict counts per spec;
  2. per draft, the mutants whose module verdict differs from the signed one, with the failing obligations;
  3. the decision mutants, the AI implementations and the hand-picked set as a table (one column per spec);
  4. per draft, the agreement with the signed verdicts on the mechanical set (same verdict / signed FAILED but draft
     PROVEN = the draft lets it pass / signed PROVEN but draft FAILED = the draft rejects it).
"""
import collections
import json
import os
import sys

SPECS = ["signed", "haiku", "sonnet", "opus"]


def load(d, s):
    p = os.path.join(d, s + ".jsonl")
    if not os.path.exists(p):
        return None
    return {json.loads(l)["mutant"]: json.loads(l) for l in open(p)}


def short(rec):
    fails = []
    for a, (v, ids) in sorted(rec["actions"].items()):
        if v == "FAILED":
            fails.append("%s{%s}" % (a, ",".join(ids)))
        elif v not in ("PROVEN",):
            fails.append("%s:%s" % (a, v))
    return "; ".join(fails)


def main():
    d = sys.argv[1]
    res = {s: load(d, s) for s in SPECS}
    res = {s: r for s, r in res.items() if r}
    for s, r in res.items():
        print(s, dict(collections.Counter(x["verdict"] for x in r.values())))
    base = res["signed"]
    names = list(base)
    print()
    for s in [x for x in res if x != "signed"]:
        print("== %s: module verdicts that differ from signed" % s)
        for n in names:
            if res[s][n]["verdict"] != base[n]["verdict"]:
                print("  %-34s signed %-7s %-8s | %s" % (n, base[n]["verdict"], res[s][n]["verdict"], short(res[s][n]) or short(base[n])))
    print()
    print("%-34s %s" % ("mutant", "  ".join("%-30s" % s for s in res)))
    for n in names:
        if n.startswith(("dm:", "ai:", "hand:")):
            print("%-34s %s" % (n, "  ".join("%-30s" % (res[s][n]["verdict"] + " " + short(res[s][n]))[:30] for s in res)))
    print()
    for s in [x for x in res if x != "signed"]:
        c = collections.Counter()
        for n in names:
            if not n.startswith("mech:"):
                continue
            a, b = base[n]["verdict"], res[s][n]["verdict"]
            c["same" if a == b else "%s->%s" % (a, b)] += 1
        print("mechanical set, %s vs signed: %s" % (s, dict(c)))


if __name__ == "__main__":
    main()
