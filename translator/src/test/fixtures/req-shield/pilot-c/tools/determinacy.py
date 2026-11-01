"""Pilot determinacy check: which scenario cells (SCENARIOS.md) do a sidecar's contracts decide?

Usage: python3 -I determinacy.py MODEL.vpr OUT_PREFIX [--no-hole]

MODEL.vpr is a model that `kv verify --out` wrote for the sidecar. The harness:
  1. removes every method body (the operations become abstract: a call assumes only the contract) and the probe;
  2. with --no-hole, replaces every hole part MI_INV_i by true (the clauses alone, without the implementation's
     inductive invariant);
  3. appends, per cell, one method per outcome (`assert OUTCOME`) and one feasibility method (`refute false`);
  4. runs Silicon once and classifies each cell: DETERMINED(outcome), OPEN, or INFEASIBLE.
A cell is DETERMINED(o) when `assert o` verifies; OPEN when no outcome verifies and the path is feasible;
INFEASIBLE when `false` is provable at the end (the contracts contradict the scenario's setup).
"""
import json
import os
import re
import subprocess
import sys

VIPER = os.path.expanduser("~/.local/opt/viper/v.26.08-release/tools")

NULL_T, BACKED, UNBACKED = "r == NULL", "r != NULL && tok(r).gBacked", "r != NULL && !tok(r).gBacked"
TOK = {"null": NULL_T, "backed": BACKED, "unbacked": UNBACKED}
BOOL = {"true": "b", "false": "!b"}
SWEPT = {"stays": "carried(S, k, t1)", "dropped": "!has(S, k)"}

ACQ = ["t1 := tryLock(S, k, 0, 10, false, 0)", "assume t1 != NULL && tok(t1).gBacked"]

# cell -> (steps, outcomes). Steps use S, k, k2, t1, t2, u, x, r, b; every scenario starts from init().
CELLS = {
    "C01": (["r := tryLock(S, k, 0, 10, false, 0)"], TOK),
    "C02": (["r := tryLock(S, k, 0, 10, true, 5)"], TOK),
    "C03": (ACQ + ["r := tryLock(S, k, 5, 10, false, 0)"], {"null": NULL_T, "token": "r != NULL"}),
    "C04": (ACQ + ["r := tryLock(S, k, 10, 10, false, 0)"], {"null": NULL_T, "token": "r != NULL"}),
    "C05": (ACQ + ["r := tryLock(S, k, 11, 10, false, 0)"], {"null": NULL_T, "token": "r != NULL"}),
    "C06": (ACQ + ["t2 := tryLock(S, k, 11, 10, false, 0)", "assume t2 != NULL", "b := unLock(S, k, t1)"], BOOL),
    "C07": (ACQ + ["t2 := tryLock(S, k, 11, 10, false, 0)", "assume t2 != NULL", "b := unLock(S, k, t2)"], BOOL),
    "C08": (ACQ + ["b := unLock(S, k, t1)"], BOOL),
    "C10": (ACQ + ["b := unLock(S, k, t1)", "assume b", "b := unLock(S, k, t1)"], BOOL),
    "C11": (ACQ + ["assume x >= 0 && x != t1 && !tok(x).gIssued", "b := unLock(S, k, x)"], BOOL),
    "C12": (["u := tryLock(S, k, 0, 10, true, 5)", "assume u != NULL && !tok(u).gBacked", "b := unLock(S, k, u)"], BOOL),
    "C13": (["u := tryLock(S, k, 0, 10, true, 5)", "assume u != NULL && !tok(u).gBacked",
             "r := tryLock(S, k, 1, 10, true, 5)"], TOK),
    "C14": (ACQ + ["sweep(S, k, 5)"], SWEPT),
    "C15": (ACQ + ["sweep(S, k, 10)"], SWEPT),
    "C16": (ACQ + ["sweep(S, k, 11)"], SWEPT),
    "C17": (ACQ + ["sweep(S, k, 11)", "assume !has(S, k)", "b := unLock(S, k, t1)"], BOOL),
    "C18": (["r := tryLock(S, k, 0, 10, false, 5)"], TOK),
    "C19": (ACQ + ["r := tryLock(S, k, 11, 10, true, 5)"], TOK),
    "C20": (ACQ + ["assume k2 != k", "r := tryLock(S, k2, 5, 10, false, 0)"], {"null": NULL_T, "token": "r != NULL"}),
}


def ghost_facts(text):
    """The commit block's ghost writes, stated as postconditions (tool facts: END and ISSUE are the only ghost
    writes, DESIGN-v0.md 3.5). Returns method name -> list of ensures lines."""
    facts = {}
    for m in re.finditer(r"(?ms)^method (tryLock|unLock|sweep)\(.*?^}", text):
        body = m.group(0)
        name = m.group(1)
        issue = re.findall(r"^    tok\(res\)\.(\w+) := (.+?)\s*(?://.*)?$", body, re.M)
        lines = ["  ensures forall qt: Int :: { tok(qt).gHeld } qt != NULL && old(carried(S, k, qt)) && !carried(S, k, qt)%s ==> !tok(qt).gHeld  // pilot END fact"
                 % (" && qt != res" if issue else ""),
                 "  ensures forall qt: Int :: { tok(qt).gHeld } old(tok(qt).gHeld) && !old(carried(S, k, qt)) ==> tok(qt).gHeld  // pilot END fact"]
        if issue:
            parts = ["!old(tok(res).gIssued)"] + ["tok(res).%s == (%s)" % (f, v) for f, v in issue]
            lines.append("  ensures res != NULL ==> %s  // pilot ISSUE fact" % " && ".join(parts))
        facts[name] = lines
    return facts


def strip(text, no_hole):
    facts = ghost_facts(text)
    lines = text.split("\n")
    out, i = [], 0
    in_action = False
    while i < len(lines):
        line = lines[i]
        if line.startswith("method probe("):
            # Drop the probe method entirely (header, contract, body).
            while i < len(lines) and lines[i] != "}":
                i += 1
            i += 1
            continue
        if line == "{" or line == "{}":
            # A method body at column 0: drop it.
            if line == "{}":
                i += 1
                continue
            depth = 0
            while i < len(lines):
                depth += lines[i].count("{") - lines[i].count("}")
                i += 1
                if depth == 0:
                    break
            continue
        if re.match(r"method (tryLock|unLock|sweep)\(", line):
            in_action = True
            current = re.match(r"method (\w+)", line).group(1)
        elif line.startswith("method "):
            in_action = False
        if in_action and line.strip() == "ensures HIST":
            out.append(line)
            out.extend(facts[current])
            i += 1
            continue
        out.append(line)
        if in_action and line.strip() == "ensures MI_PERMS(S)":
            # A tool fact that the contracts do not state: an action changes the map only at its own key.
            # It goes right after MI_PERMS, which gives the permission to read S.m after the step.
            out.append("  ensures forall qk: Key :: { S.m[qk] } qk != k ==> (has(S, qk) == old(has(S, qk))) && "
                       "(has(S, qk) ==> S.m[qk] == old(S.m[qk]))  // pilot frame fact")
        i += 1
    text = "\n".join(out)
    if no_hole:
        region = re.search(r"// @HOLE-BEGIN.*?// @HOLE-END[^\n]*", text, re.S)
        names = re.findall(r"define (MI_INV_\d+)\(S\)", region.group(0))
        text = text[:region.start()] + "\n".join("define %s(S) (true)" % n for n in names) + text[region.end():]
    return text


def method(name, steps, tail):
    body = ["  var S: Ref", "  var t1: Int", "  var t2: Int", "  var u: Int", "  var x: Int", "  var r: Int",
            "  var b: Bool", "  S := init()"]
    body += ["  " + s for s in steps]
    body.append("  " + tail)
    return "method %s(k: Key, k2: Key)\n{\n%s\n}\n" % (name, "\n".join(body))


def main():
    model, prefix = sys.argv[1], sys.argv[2]
    no_hole = "--no-hole" in sys.argv
    text = strip(open(model).read(), no_hole)
    spans = {}
    parts = [text, "\n// ---- Determinacy scenarios (pilot harness) ----\n"]
    cur = text.count("\n") + 3
    for cell, (steps, outcomes) in CELLS.items():
        for oname, expr in list(outcomes.items()) + [("FEASIBLE", None)]:
            name = "scen_%s_%s" % (cell, oname)
            m = method(name, steps, "refute false" if expr is None else "assert %s" % expr)
            spans[name] = (cur, cur + m.count("\n"))
            parts.append(m)
            cur += m.count("\n")
    vpr = prefix + ".vpr"
    with open(vpr, "w") as f:
        f.write("".join(parts))
    # Spans from the written file: a scenario method runs from its header to the line before the next header.
    lines = open(vpr).read().split("\n")
    heads = [(i + 1, re.match(r"method (scen_\w+)\(", l).group(1)) for i, l in enumerate(lines) if l.startswith("method scen_")]
    spans = {name: (a, (heads[j + 1][0] - 1) if j + 1 < len(heads) else len(lines)) for j, (a, name) in enumerate(heads)}
    java = "java"
    cmd = [java, "-Xss128m", "-cp", os.path.join(VIPER, "backends/viperserver.jar"), "viper.silicon.SiliconRunner",
           "--logLevel", "ERROR", "--z3Exe", os.path.join(VIPER, "z3/bin/z3"), "--numberOfErrorsToReport", "0",
           "--assertTimeout", "20000", "--checkTimeout", "20000", vpr]
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=1800)
    out = p.stdout + p.stderr
    with open(prefix + ".silicon", "w") as f:
        f.write(out)
    failed = set()
    other = []
    for m in re.finditer(r"\[\s*\d+\] (.*?)\(([^()]*?)@(\d+)\.\d+--\d+\.\d+\)", out):
        line = int(m.group(3))
        hit = [n for n, (a, b) in spans.items() if a <= line <= b]
        if hit:
            failed.add(hit[0])
        else:
            other.append(m.group(0)[:200])
    if "Silicon finished verification successfully" not in out and not re.search(r"Silicon found \d+ error", out):
        raise SystemExit("Silicon did not finish normally:\n" + out[-3000:])
    if other:
        # A parse, type or contract-consistency error outside the scenarios makes every cell meaningless.
        raise SystemExit("errors outside the scenarios (%d), first: %s" % (len(other), other[0]))
    result = {}
    for cell, (steps, outcomes) in CELLS.items():
        # `refute false` fails ("holds in all cases or could not be reached") exactly when the end is unreachable.
        if "scen_%s_FEASIBLE" % cell in failed:
            result[cell] = "INFEASIBLE"
            continue
        det = [o for o in outcomes if "scen_%s_%s" % (cell, o) not in failed]
        result[cell] = ("DETERMINED(%s)" % "|".join(det)) if det else "OPEN"
    with open(prefix + ".json", "w") as f:
        json.dump({"cells": result, "errors_outside_scenarios": other}, f, indent=1)
    print(" ".join("%s=%s" % (c, v) for c, v in result.items()))
    if other:
        print("errors outside the scenarios: %d (first: %s)" % (len(other), other[0]))


if __name__ == "__main__":
    main()
