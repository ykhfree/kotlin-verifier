"""E3 harness: build the skeleton (MI_INV hole emptied) and check an edited skeleton.

  python3 e3_harness.py skeleton BASELINE OUT      write the skeleton
  python3 e3_harness.py check PRISTINE EDITED      exit 0 if EDITED differs from PRISTINE only inside the hole
                                                   and the hole has no forbidden statements
"""
import re
import sys

BEGIN = "// @HOLE-BEGIN MI_INV\n"
END = "// @HOLE-END MI_INV\n"
EMPTY_HOLE = "define MI_INV(S) (\n  true\n)\n"
# Statements and constructs that would let a run assume what it must prove.
FORBIDDEN = re.compile(
    r"\b(assume|inhale|exhale|refute|import|method|function|predicate|axiom)\b"
    r"|\bdomain\s+\w+\s*\{|\bfield\s+\w+\s*:"
)


def split(text):
    i, j = text.find(BEGIN), text.find(END)
    if i < 0 or j < 0 or j < i or text.count(BEGIN) != 1 or text.count(END) != 1:
        sys.exit("hole markers missing or duplicated")
    return text[: i + len(BEGIN)], text[i + len(BEGIN): j], text[j:]


def skeleton(baseline, out):
    head, _, tail = split(open(baseline).read())
    # Drop header lines that name other files, so the skeleton points nowhere else.
    head = "".join(l for l in head.splitlines(True) if "baseline" not in l and ".vpr" not in l)
    open(out, "w").write(head + EMPTY_HOLE + tail)


def check(pristine, edited):
    p_head, _, p_tail = split(open(pristine).read())
    e_head, hole, e_tail = split(open(edited).read())
    if (p_head, p_tail) != (e_head, e_tail):
        sys.exit("REJECTED: text outside the hole was changed")
    stripped = re.sub(r"//[^\n]*", "", hole)
    bad = FORBIDDEN.findall(stripped)
    if bad:
        sys.exit("REJECTED: forbidden keyword(s) in the hole: %s" % sorted(set(bad)))
    if len(re.findall(r"\bdefine\s+MI_INV\s*\(", stripped)) != 1:
        sys.exit("REJECTED: the hole must define MI_INV exactly once")
    print("hole check OK")


if __name__ == "__main__":
    {"skeleton": skeleton, "check": check}[sys.argv[1]](*sys.argv[2:])
