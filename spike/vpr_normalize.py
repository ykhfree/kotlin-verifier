"""Gate for the v0 emitter: are two Viper files the same program up to layout, comments and local names?

Normalization (DESIGN-v0.md 7, M5 acceptance test 2):
1. `//` comments are removed. A `/*` comment is an input error.
2. The text is split into tokens. Whitespace and line breaks do not count.
3. Inside each method, every position-suffixed local (`<name>__<line>`, `<name>__<line>_<col>`, and
   `cond_`, `n_`, `cur_`, `r_`, `v_` + `<line>_<col>` with an optional letter suffix) is renamed to `_v1`, `_v2`, ... by first occurrence.
   Distinct names stay distinct. The core variant needs this: its locals carry core line numbers.
Statement order is not normalized.

Usage: python3 -I vpr_normalize.py EXPECTED.vpr ACTUAL.vpr
Exit 0: same program. Exit 1: different; a unified diff of the normalized forms is printed. Exit 2: input error.
"""
import difflib
import re
import sys


def fail(msg):
    print(msg, file=sys.stderr)
    sys.exit(2)


TOKEN = re.compile(r"\s*(<==>|==>|--\*|==|!=|<=|>=|&&|\|\||:=|::|\+\+|[A-Za-z_$][A-Za-z0-9_$]*|\d+|\S)")
LOCAL = re.compile(r"^(?:[A-Za-z]\w*__\d+(?:_\d+)?|(?:cond|n|cur|r|v)_\d+_\d+[a-z]?)$")
# Tokens that start a new line in the printed form, so that a diff points at one statement.
LINE_START = {"domain", "function", "axiom", "define", "field", "method", "requires", "ensures", "var", "if",
              "else", "assert", "inhale", "exhale", "}"}


def tokens(path):
    text = open(path).read()
    if "/*" in text:
        fail("%s: block comments are not supported" % path)
    text = re.sub(r"//[^\n]*", "", text)
    out, pos = [], 0
    while True:
        m = TOKEN.match(text, pos)
        if not m:
            if text[pos:].strip():
                fail("%s: cannot tokenize at offset %d" % (path, pos))
            return out
        out.append(m.group(1))
        pos = m.end()


def rename(toks):
    names, out = {}, []
    for t in toks:
        if t == "method":
            names = {}
        if LOCAL.match(t):
            t = names.setdefault(t, "_v%d" % (len(names) + 1))
        out.append(t)
    return out


def starts_assignment(toks, i):
    """True if toks[i] starts `x := e` or `x.f := e`: not the `k` of `m[k := v]`, the `f` of `x.f`, the `T` of `x: T`."""
    if i > 0 and toks[i - 1] in ("[", ".", ":"):
        return False
    rest = toks[i:i + 4]
    return rest[1:2] == [":="] or (len(rest) == 4 and rest[1] == "." and rest[3] == ":=")


def lines(toks):
    out, cur = [], []
    for i, t in enumerate(toks):
        if cur and (t in LINE_START or (re.match(r"[A-Za-z_]", t) and starts_assignment(toks, i))):
            out.append(" ".join(cur))
            cur = []
        cur.append(t)
        if t == "{":
            out.append(" ".join(cur))
            cur = []
    if cur:
        out.append(" ".join(cur))
    return out


def main(expected, actual):
    a, b = rename(tokens(expected)), rename(tokens(actual))
    if a == b:
        print("same program: %d tokens" % len(a))
        return 0
    sys.stdout.writelines(difflib.unified_diff(
        [l + "\n" for l in lines(a)], [l + "\n" for l in lines(b)], expected, actual, n=2))
    return 1


if __name__ == "__main__":
    if len(sys.argv) != 3:
        fail(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
