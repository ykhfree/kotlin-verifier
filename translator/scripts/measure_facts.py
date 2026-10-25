#!/usr/bin/env python3
"""Measures which contract facts the cells of a cell file need (DESIGN-C.md 10.3, C4 acceptance 4).

Usage: python3 -I measure_facts.py --kv KV --sidecar SIDECAR --cells CELLS --out DIR
                                   [--root DIR --classpath CP --kotlinc ARGS] [--jobs N] FACTS...

Each FACTS argument is one run: `none`, or facts joined with `+` (the values of kv spec check --assume-fact, e.g.
`frame-domain+field-frame`). --jobs runs go in parallel (default 1: a run with --root starts many Silicon processes).
The script prints, per mode, the count of OPEN, INFEASIBLE and DETERMINED cells per run, and every cell whose verdict
differs between the runs. C4 measured WITNESS and ISSUE_ONLY this way before they became tool facts.
"""
import argparse
import json
import os
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor


def run(args, facts):
    name = facts.replace("+", "_")
    out = os.path.join(args.out, name)
    cmd = [args.kv, "spec", "check", "--snapshot", args.snapshot, "--cells", args.cells, "--out", out]
    if args.root:
        cmd += ["--root", args.root, "--classpath", args.classpath, "--kotlinc", args.kotlinc]
    for f in ([] if facts == "none" else facts.split("+")):
        cmd += ["--assume-fact", f]
    cmd.append(args.sidecar)
    p = subprocess.run(cmd, capture_output=True, text=True)
    with open(out + ".txt", "w") as f:
        f.write(p.stdout + p.stderr)
    check = os.path.join(out, "check.json")
    if not os.path.exists(check):
        sys.exit("no check.json for %s (exit %d):\n%s" % (facts, p.returncode, p.stdout + p.stderr))
    d = json.load(open(check))
    for m, c in d["cells"].items():
        if c["error"]:
            sys.exit("%s mode %s: %s" % (facts, m, c["error"]))
    return {m: c["cells"] for m, c in d["cells"].items()}


def main(argv):
    p = argparse.ArgumentParser()
    p.add_argument("--kv", required=True)
    p.add_argument("--sidecar", required=True)
    p.add_argument("--cells", required=True)
    p.add_argument("--snapshot", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--root")
    p.add_argument("--classpath")
    p.add_argument("--kotlinc", default="")
    p.add_argument("--jobs", type=int, default=1)
    p.add_argument("facts", nargs="+")
    args = p.parse_args(argv)
    os.makedirs(args.out, exist_ok=True)
    with ThreadPoolExecutor(args.jobs) as pool:
        results = dict(zip(args.facts, pool.map(lambda f: run(args, f), args.facts)))
    for mode in sorted(next(iter(results.values()))):
        cells = list(next(iter(results.values()))[mode])
        print("mode %s:" % mode)
        for f, r in results.items():
            v = r[mode].values()
            print("  %-40s %2d OPEN, %2d INFEASIBLE, %2d DETERMINED" % (f, sum(x == "OPEN" for x in v), sum(x == "INFEASIBLE" for x in v), sum(x.startswith("DETERMINED") for x in v)))
        for c in cells:
            vs = [results[f][mode][c] for f in args.facts]
            if len(set(vs)) > 1:
                print("  %-5s %s" % (c, "  ".join("%s=%s" % (f, v.replace("DETERMINED", "D")) for f, v in zip(args.facts, vs))))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
