"""Pilot table B.4 of DESIGN-C.md: module verdicts of the aligned drafts against the signed sidecar.

Usage: python3 -I b4.py SD   (reads SD/results/signed.jsonl, x-signed.jsonl, aligned-<m>.jsonl or aligned-<m>2.jsonl)
Prints Markdown: per aligned draft, the 2x2 counts on the 89 roots both runs share, the roots whose verdict differs
(with the failing obligations), and the two extra decision mutants (D13, D14) with their sub-status.
"""
import json
import os
import sys

SD = sys.argv[1]


def load(name):
    p = os.path.join(SD, "results", name + ".jsonl")
    return {json.loads(l)["mutant"]: json.loads(l) for l in open(p)} if os.path.exists(p) else None


def why(r):
    out = []
    for a, (v, ids) in sorted(r["actions"].items()):
        if v == "FAILED":
            sub = r.get("pass2", {}).get(a, [None])[0]
            out.append("%s %s%s" % (a, ",".join(ids), " (%s)" % sub if sub else ""))
        elif v != "PROVEN":
            out.append("%s %s" % (a, v))
    return "; ".join(out)


def main():
    signed = load("signed")
    extra = load("x-signed") or {}
    print("| 결정을 맞춘 초안 | 서명 FAILED, 초안 FAILED | 서명 PROVEN, 초안 PROVEN | 서명 PROVEN, 초안 FAILED | 서명 FAILED, 초안 PROVEN | D13 | D14 |")
    print("|---|---|---|---|---|---|---|")
    diffs = {}
    for m in ["haiku", "sonnet", "opus"]:
        a = load("aligned-" + m) or load("aligned-%s2" % m)
        if a is None:
            print("| %s | (없음) | | | | | |" % m)
            continue
        c = {"FF": 0, "PP": 0, "PF": 0, "FP": 0, "other": 0}
        diffs[m] = []
        for n, r in signed.items():
            x = a.get(n)
            if x is None:
                continue
            key = r["verdict"][0] + x["verdict"][0]
            c[key if key in c else "other"] += 1
            if r["verdict"] != x["verdict"]:
                diffs[m].append((n, r["verdict"], x["verdict"], why(x) or why(r)))
        d13, d14 = a.get("dm:D13-renew-on-refusal"), a.get("dm:D14-lease-twice")
        print("| %s | %d | %d | %d | %d | %s | %s |" % (m, c["FF"], c["PP"], c["PF"], c["FP"],
              "%s: %s" % (d13["verdict"], why(d13)) if d13 else "-", "%s: %s" % (d14["verdict"], why(d14)) if d14 else "-"))
    for m, ds in diffs.items():
        print()
        print("%s, 판정이 다른 뿌리: %s" % (m, "; ".join("%s (서명 %s, 초안 %s: %s)" % d for d in ds) or "없음"))
    if extra:
        print()
        print("서명된 sidecar의 D13, D14: " + "; ".join("%s %s: %s" % (n, r["verdict"], why(r)) for n, r in extra.items()))


if __name__ == "__main__":
    main()
