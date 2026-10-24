#!/usr/bin/env python3
"""Copy a kv model and edit its MI hole, or reorder its ensures, for a judge-B probe.

Usage: weaken.py MODE IN.vpr OUT.vpr
MODE dead:  MI_INV_1 also admits a dead entry (permits 0, not held, no token); MI_INV_4 covers entries with a token.
MODE free:  MI_INV_1 also admits a free entry (permits 1, not held, no token); MI_INV_4 covers entries with a token.
MODE order: in every action method, the sidecar ensures come before the MI_INV_i ensures (nothing else changes).
"""
import re
import sys

OLD_INV1 = """define MI_INV_1(S) ((forall qk: Key :: { S.m[qk] } has(S, qk) ==>
     S.m[qk].permits == 0 && S.m[qk].isHeld && S.m[qk].token != NULL))"""
OLD_INV4_HEAD = "define MI_INV_4(S) ((forall qk: Key :: { S.m[qk] } has(S, qk) ==>"
EXTRA = {
    "dead": "(S.m[qk].permits == 0 && !S.m[qk].isHeld && S.m[qk].token == NULL)",
    "free": "(S.m[qk].permits == 1 && !S.m[qk].isHeld && S.m[qk].token == NULL)",
}


def weaken(text, mode):
    assert text.count(OLD_INV1) == 1 and text.count(OLD_INV4_HEAD) == 1
    new_inv1 = ("define MI_INV_1(S) ((forall qk: Key :: { S.m[qk] } has(S, qk) ==>\n"
                "     (S.m[qk].permits == 0 && S.m[qk].isHeld && S.m[qk].token != NULL) ||\n"
                f"     {EXTRA[mode]}))  // judge B: weakened ({mode} entry admitted)")
    text = text.replace(OLD_INV1, new_inv1)
    text = text.replace(OLD_INV4_HEAD, "define MI_INV_4(S) ((forall qk: Key :: { S.m[qk] } has(S, qk) && "
                                       "S.m[qk].token != NULL ==>  // judge B: weakened")
    return text


def reorder(text):
    # In each action method, move the MI_INV_i ensures lines to the end of the contract.
    lines = text.splitlines()
    out, i = [], 0
    while i < len(lines):
        line = lines[i]
        if re.match(r"^method (tryLock|unLock|sweep)\(", line):
            j = i + 1
            while lines[j] != "{":
                j += 1
            contract = lines[i + 1:j]
            inv = [c for c in contract if re.match(r"^  ensures MI_INV_\d\(S\)$", c)]
            rest = [c for c in contract if c not in inv]
            out.append(line)
            out.extend(rest + inv)
            i = j
            continue
        out.append(line)
        i += 1
    return "\n".join(out) + "\n"


def main():
    mode, src, dst = sys.argv[1:4]
    text = open(src).read()
    text = reorder(text) if mode == "order" else weaken(text, mode)
    open(dst, "w").write(text)


if __name__ == "__main__":
    main()
