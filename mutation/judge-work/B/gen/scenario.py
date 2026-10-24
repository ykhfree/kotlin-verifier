#!/usr/bin/env python3
"""Build a deterministic Viper scenario from a kv-generated model.

The scenario runs the init body, then inlines action bodies one after another with fixed free values.
Each step is wrapped in `if (true) { ... }` so that the locals of two steps do not clash.
A `label` before each step lets an assert refer to that step's pre-state with old[label](...).
Usage: scenario.py SPEC.json OUT.vpr
"""
import json
import re
import sys

PARAMS = {
    # op: (free-value parameters in model order, result type or None)
    "tryLock": (["now", "timeout", "rej", "limit"], "Int"),
    "unLock": (["t"], "Bool"),
    "sweep": (["now"], None),
}
TYPES = {"now": "Int", "timeout": "Int", "rej": "Bool", "limit": "Int", "t": "Int"}


def split_model(text):
    lines = text.splitlines()
    start = next(i for i, line in enumerate(lines) if line.startswith("method init()"))
    while start > 0 and lines[start - 1].startswith("//"):
        start -= 1
    header = lines[:start]
    bodies = {}
    for name in ["init", "tryLock", "unLock", "sweep"]:
        i = next(i for i, line in enumerate(lines) if line.startswith(f"method {name}("))
        j = next(j for j in range(i, len(lines)) if lines[j] == "{")
        e = next(e for e in range(j + 1, len(lines)) if lines[e] == "}")
        bodies[name] = lines[j + 1:e]
    return header, bodies


def apply_racy(body, racy):
    # A racy read is an uninitialized local in the model; the scenario fixes its value.
    out = []
    for line in body:
        m = re.match(r"^(\s*)var (racy_\w+): Bool\b.*$", line)
        if m:
            name = m.group(2)
            if name not in racy:
                sys.exit(f"racy value {name} has no fixed value in the spec")
            line = f"{m.group(1)}var {name}: Bool := {racy[name]}  // racy read fixed by the scenario"
        out.append(line)
    return out


def main():
    spec = json.load(open(sys.argv[1]))
    header, bodies = split_model(open(spec["model"]).read())
    out = list(header)
    out.append(f"// ---- Scenario {spec['name']} (judge B): {spec.get('doc', '')}")
    out.append(f"method scenario(k: Key)")
    out.append("{")
    out.append("  var S: Ref")
    out.extend(bodies["init"])
    for step in spec["steps"]:
        if "assert" in step:
            out.append(f"  assert {step['assert']}")
            continue
        op = step["op"]
        names, rtype = PARAMS[op]
        out.append(f"  label {step['label']}  // {step.get('doc', op)}")
        if rtype:
            out.append(f"  var {step['out']}: {rtype}")
        out.append("  if (true) {")
        for n in names:
            out.append(f"    var {n}: {TYPES[n]} := {step[n]}")
        if rtype:
            out.append(f"    var res: {rtype}")
        out.extend("  " + line for line in apply_racy(bodies[op], step.get("racy", {})))
        if rtype:
            out.append(f"    {step['out']} := res")
        out.append("  }")
    # Guard against vacuity: this refute fails if the path above is infeasible.
    out.append("  refute false")
    out.append("}")
    open(sys.argv[2], "w").write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
