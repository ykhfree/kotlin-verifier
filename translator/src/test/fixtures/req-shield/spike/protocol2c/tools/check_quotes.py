"""Checks every `source "<anchor>" quote "<q>" sha256 "<fp>"` line of a sidecar against a kv spec snapshot:
the anchor exists, the quote occurs in the block text (whitespace runs collapsed), the fingerprint is the block's.
Usage: python3 -I check_quotes.py SNAPSHOT_DIR SIDECAR"""
import json
import re
import sys

blocks = {b["anchor"]: b for b in json.load(open(sys.argv[1] + "/sources.json"))}
norm = lambda s: re.sub(r"\s+", " ", s).strip()
bad = 0
n = 0
for i, line in enumerate(open(sys.argv[2]), 1):
    m = re.search(r'source "([^"]+)" quote "((?:[^"\\]|\\.)*)" sha256 "([0-9a-f]+)"', line)
    if not m:
        continue
    n += 1
    anchor, quote, fp = m.groups()
    b = blocks.get(anchor)
    if b is None:
        print("line %d: no block %s" % (i, anchor)); bad += 1; continue
    if norm(quote) not in norm(b["text"]):
        print("line %d: quote not in %s: %r" % (i, anchor, quote)); bad += 1
    if fp != b["sha256"]:
        print("line %d: %s has sha256 %s, the line says %s" % (i, anchor, b["sha256"], fp)); bad += 1
print("%d quote lines, %d problems" % (n, bad))
sys.exit(1 if bad else 0)
