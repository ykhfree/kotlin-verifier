"""Verdicts of the spike entries from their full failing sets (DESIGN-E 5.2).

Reads the lists that mutants.py printed (OUT_DIR/<base>/list.txt) and the isolate.py output, and decides per entry:
  base=<IDs>  the original of the base fails exactly these obligations
  fail        DETECTED if the entry fails an obligation the base original does not fail
  same        ACCEPTED if the entry fails exactly what the base original fails (an equivalent rewrite)
  pass        ACCEPTED if the entry fails nothing
Usage: python3 -I verdicts.py OUT_DIR ISOLATION.txt   (exit 0 iff every entry is as expected)
"""
import os
import sys


def ids(text):
    text = text.strip()
    return set() if text in ("", "none") else {i.strip() for i in text.split(",")}


def main(out_dir, isolation):
    fails = {}
    for line in open(isolation):
        name, _, found = line.partition(":")
        fails[name.strip()] = ids(found)
    ok = True
    for base in ("canonical", "fixed"):
        entries = [line.split() for line in open(os.path.join(out_dir, base, "list.txt")) if line.strip()]
        original = fails[base + "/original"]
        for name, expect in entries:
            got = fails[base + "/" + name]
            if expect.startswith("base="):
                good = got == ids(expect[len("base="):])
                verdict = "FAILED" if got else "PROVEN"
            elif expect == "fail":
                good = bool(got - original)
                verdict = "DETECTED" if good else "MISSED"
            elif expect == "same":
                good = got == original
                verdict = "ACCEPTED" if good else "DIFFERS"
            else:
                good = not got
                verdict = "PROVEN" if good else "FAILED"
            ok = ok and good
            extra = sorted(got - original)
            print("== %s/%s expect=%s -> %s%s: %s%s" % (
                base, name, expect, verdict, "" if good else " (UNEXPECTED)",
                ", ".join(sorted(got)) or "none",
                "  [beyond the original: %s]" % ", ".join(extra) if extra and expect != "pass" and not expect.startswith("base=") else ""))
    return ok


if __name__ == "__main__":
    sys.exit(0 if main(sys.argv[1], sys.argv[2]) else 1)
