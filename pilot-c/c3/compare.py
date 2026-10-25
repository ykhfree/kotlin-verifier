"""Compare kv spec check results (runs/NAME/check.json) with the pilot results (pilot-c/results).

Usage: python3 -I compare.py SP   (SP: the folder that run_pilot.sh printed)
"""
import json, os, sys
SP = sys.argv[1]  # the folder run_pilot.sh wrote (it holds runs/)
P = "/Users/ykhfree/AI/kotlin-verifier/pilot-c/results"
names = {"signed": "signed", "final-haiku": "haiku", "final-sonnet": "sonnet", "final-opus": "opus",
         "aligned-haiku": "aligned-haiku", "aligned-sonnet": "aligned-sonnet", "aligned-opus": "aligned-opus", "current": None}
for name, pilot in names.items():
    f = os.path.join(SP, "runs", name, "check.json")
    if not os.path.exists(f):
        print(name, "no check.json"); continue
    d = json.load(open(f))
    nr = json.load(open(os.path.join(SP, "runs", name + "-noroot", "check.json")))
    for mode in ("a", "b"):
        tool = d["cells"][mode]["cells"]
        err = d["cells"][mode]["error"]
        if pilot:
            exp = json.load(open(os.path.join(P, "det", "%s-%s.json" % (pilot, mode))))["cells"]
            diff = {c: (exp.get(c), tool.get(c)) for c in set(exp) | set(tool) if exp.get(c) != tool.get(c)}
            print("%-15s mode %s: %s %s" % (name, mode, "SAME as pilot" if not diff else "DIFF %s" % diff, err or ""))
        else:
            print("%-15s mode %s: %s" % (name, mode, " ".join("%s=%s" % (c, v.replace("DETERMINED", "D")) for c, v in tool.items())))
    nb = nr["cells"]["b"]["cells"]
    print("%-15s no-root b == root b: %s %s" % (name, nb == d["cells"]["b"]["cells"], nr["cells"]["b"]["error"] or ""))
    iso = d["isolation"]
    fails = [(c["action"], c["index"], c["clause"]) for c in iso["clauses"] if c["verdict"] != "PROVEN"]
    print("%-15s isolation: %d runs, %d clauses, non-PROVEN %s, hole %s, other %d" % (name, iso["runs"], len(iso["clauses"]), fails, iso["hole"], len(iso["other"])))
    if pilot in ("haiku", "sonnet", "opus", "signed"):
        exp = json.load(open(os.path.join(P, "iso", "%s.json" % pilot)))
        expv = {(a, r["index"]): r["verdict"] for a, rs in exp.items() for r in rs}
        toolv = {(c["action"], c["index"]): c["verdict"] for c in iso["clauses"]}
        diff = {k: (expv.get(k), toolv.get(k)) for k in set(expv) | set(toolv) if expv.get(k) != toolv.get(k)}
        print("%-15s isolation vs pilot: %s" % (name, "SAME" if not diff else "DIFF %s" % diff))
    print("%-15s field warnings: %s" % (name, [(w["clause"], w["fields"]) for w in d["field_fixing"]]))
