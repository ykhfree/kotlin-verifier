#!/usr/bin/env python3
"""Converts the DESIGN-C pilot's scenario answers (pilot-c/frozen-final/D-<m>/ANSWERS.md) to kv-answers/1 (spec.py).

Usage: python3 -I pilot_answers.py PILOT_DIR CELLS OUT_DIR

PILOT_DIR is pilot-c; CELLS is the pilot cell file (translator/src/test/resources/spec/cells/keylocallock.cells); the
files OUT_DIR/<m>.json are written for haiku, sonnet and opus. The answer words map to the cell's outcome names as
pilot-c/tools/questions.py maps them; the converter checks that each name is an outcome of the cell. The pilot's C09
has the steps of C08 (unLock has no clock), so its formal cell is C08. The notes below each table are not converted.
"""
import json
import os
import re
import sys

MODELS = ["haiku", "sonnet", "opus"]
FORMAL = {"C09": "C08"}
# The words of an answer that name each outcome (pilot-c/packet/SCENARIOS.md outcome lists; questions.py WORDS).
WORDS = [("no entry backs", "unbacked"), ("an entry backs", "backed"), ("null", "null"), ("new token", "token"),
         ("a token", "token"), ("stays", "stays"), ("dropped", "dropped"), ("true", "true"), ("false", "false")]


def outcome_of(text, outcomes):
    t = text.lower()
    if "undetermined" in t or "not decided" in t:
        return None
    for w, o in WORDS:
        if w in t:
            if o not in outcomes:
                sys.exit("answer %r names %s, which is no outcome of its cell (%s)" % (text, o, ", ".join(outcomes)))
            return o
    return None


def cell_outcomes(path):
    out = {}
    cell = None
    for line in open(path):
        m = re.match(r"cell (\w+)\(", line)
        if m:
            cell = m.group(1)
            out[cell] = []
        m = re.match(r"\s+outcome (\w+):", line)
        if m:
            out[cell].append(m.group(1))
    return out


def convert(pilot, cells, model):
    rows = []
    for line in open(os.path.join(pilot, "frozen-final", "D-%s" % model, "ANSWERS.md")):
        c = [x.strip() for x in line.split("|")]
        if len(c) > 4 and re.fullmatch(r"C\d\d", c[1]):
            formal = FORMAL.get(c[1], c[1])
            row = {"cell": c[1]}
            if formal != c[1]:
                row["formal_cell"] = formal
            row["text"] = c[2]
            row["outcome"] = outcome_of(c[2], cells[formal])
            row["status"] = c[3].split()[0].lower()
            row["sources"] = [{"anchor": a, "quote": q} for a, q in re.findall(r'\[([^\]]+)\]\s*"([^"]*)"', c[4])]
            rows.append(row)
    return {"format": "kv-answers/1", "author": model,
            "from": "pilot-c/frozen-final/D-%s/ANSWERS.md (converted by translator/scripts/pilot_answers.py)" % model,
            "answers": rows}


def main(argv):
    pilot, cells_path, out = argv
    cells = cell_outcomes(cells_path)
    os.makedirs(out, exist_ok=True)
    for m in MODELS:
        with open(os.path.join(out, "%s.json" % m), "w") as f:
            json.dump(convert(pilot, cells, m), f, indent=1, ensure_ascii=False)
            f.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
