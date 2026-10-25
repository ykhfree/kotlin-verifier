"""Pilot trace check: every `// source: [ANCHOR] "QUOTE"` of a draft must quote its anchor's block exactly.

Usage: python3 -I tracecheck.py SOURCES.json DRAFT.kv [MORE.md...]

The match rule is the one a `kv spec` check would use: whitespace runs become one space on both sides, then the
quote must be a substring of the anchor's block. Prints per file: sources, exact matches, quotes found only in
another block (wrong anchor), quotes found nowhere, unknown anchors, and `source: none` lines; then, for the draft,
how many clauses carry at least one quoted source.
"""
import json
import re
import sys


def norm(s):
    return re.sub(r"\s+", " ", s).strip()


def main():
    blocks = {b["anchor"]: norm(b["text"]) for b in json.load(open(sys.argv[1]))}
    for path in sys.argv[2:]:
        text = open(path).read()
        refs = re.findall(r"\[([^\]\n]+?)\]\s*\"([^\"\n]+)\"", text)
        stats = {"refs": 0, "exact": 0, "other_block": 0, "nowhere": 0, "unknown_anchor": 0}
        bad = []
        for anchor, quote in refs:
            if "#" not in anchor:
                continue
            stats["refs"] += 1
            q = norm(quote)
            if anchor not in blocks:
                stats["unknown_anchor"] += 1
                bad.append(("unknown anchor", anchor, quote))
            elif q in blocks[anchor]:
                stats["exact"] += 1
            elif any(q in t for t in blocks.values()):
                stats["other_block"] += 1
                bad.append(("other block", anchor, quote))
            else:
                stats["nowhere"] += 1
                bad.append(("nowhere", anchor, quote))
        none = len(re.findall(r"//\s*source:\s*none", text))
        print("%s: %s, source-none lines %d" % (path.split("/")[-2] + "/" + path.split("/")[-1], stats, none))
        for b in bad:
            print("   ", b)
        if path.endswith(".kv"):
            # Clauses = issue/ensures/ends_only_if/unbacked_only_if/invariant/lemma/probe lines; a clause is traced
            # if the comment lines right above it hold a quoted source.
            lines = text.split("\n")
            total = traced = 0
            for i, l in enumerate(lines):
                if re.match(r"\s*(issue|ensures|ends_only_if|unbacked_only_if|invariant|lemma|probe)\b", l):
                    total += 1
                    j = i - 1
                    above = []
                    while j >= 0 and lines[j].strip().startswith("//"):
                        above.append(lines[j])
                        j -= 1
                    if any(re.search(r"source:\s*\[", a) for a in above):
                        traced += 1
            print("    clauses %d, with a quoted source %d" % (total, traced))


if __name__ == "__main__":
    main()
