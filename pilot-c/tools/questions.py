"""Pilot question rule (DESIGN-C.md 3.3) applied mechanically to the pilot data.

Usage: python3 -I questions.py SD

Inputs: SD/frozen-final/D-<m>/ANSWERS.md (status per cell), SD/results/det/<m>-a.json (the draft's cells, mode a),
SD/results/det/signed-a.json (the reference behavior: the signed sidecar is proven for the reference, so its
DETERMINED cells are the reference's behavior). C09 maps to the formal cell C08 (unLock has no clock).
Rules: 1 an `open` answer, or `assumed` by two or more; 2 two drafts decide differently (or INFEASIBLE);
3 a draft decides a cell that its own author answered differently or `open`; 4 a draft decides a cell differently
from the reference; 5 a boundary cell (C04, C15: clock = lease).
"""
import json
import re
import sys

SD = sys.argv[1]
MODELS = ["haiku", "sonnet", "opus"]
BOUNDARY = {"C04", "C15"}
FORMAL = {"C09": "C08"}
# The words of an answer that name each formal outcome (SCENARIOS.md outcome lists).
WORDS = [("no entry backs", "unbacked"), ("an entry backs", "backed"), ("null", "null"), ("new token", "token"),
         ("a token", "token"), ("stays", "stays"), ("dropped", "dropped"), ("true", "true"), ("false", "false")]


def outcome_of(text):
    t = text.lower()
    if "undetermined" in t or "not decided" in t:
        return None
    for w, o in WORDS:
        if w in t:
            return o
    return None


def answers(m):
    rows = {}
    for line in open("%s/frozen-final/D-%s/ANSWERS.md" % (SD, m)):
        c = [x.strip() for x in line.split("|")]
        if len(c) > 4 and re.fullmatch(r"C\d\d", c[1]):
            rows[c[1]] = (outcome_of(c[2]), c[3].split()[0].lower())
    return rows


def cells(name):
    d = json.load(open("%s/results/det/%s-a.json" % (SD, name)))["cells"]
    out = {}
    for k, v in d.items():
        m = re.fullmatch(r"DETERMINED\((\w+)\)", v)
        out[k] = m.group(1) if m else v
    return out


def norm(o):
    # A determinacy outcome 'token' covers 'backed' answers for cells whose outcomes are null/token.
    return {"backed": "token"}.get(o, o)


def main():
    ans = {m: answers(m) for m in MODELS}
    det = {m: cells(m) for m in MODELS}
    ref = cells("signed")
    all_cells = sorted(set().union(*[set(a) for a in ans.values()]))
    hits = {}
    for c in all_cells:
        f = FORMAL.get(c, c)
        why = []
        st = [ans[m].get(c, (None, "?"))[1] for m in MODELS]
        if "open" in st or st.count("assumed") >= 2:
            why.append(1)
        ds = [det[m].get(f) for m in MODELS]
        if len(set(ds)) > 1:
            why.append(2)
        for m in MODELS:
            o, s = ans[m].get(c, (None, "?"))
            d = det[m].get(f)
            if d not in ("OPEN", "INFEASIBLE", None) and (s == "open" or (o is not None and norm(o) != norm(d))):
                why.append(3)
                break
        r = ref.get(f)
        if r not in ("OPEN", None) and any(det[m].get(f) not in (r, "OPEN") for m in MODELS):
            why.append(4)
        if c in BOUNDARY:
            why.append(5)
        if why:
            hits[c] = why
    for c, w in hits.items():
        print(c, "rules", w, "| answers", [ans[m].get(c) for m in MODELS], "| drafts", [det[m].get(FORMAL.get(c, c)) for m in MODELS], "| reference", ref.get(FORMAL.get(c, c)))
    print("question cells:", len(hits), sorted(hits))


if __name__ == "__main__":
    main()
