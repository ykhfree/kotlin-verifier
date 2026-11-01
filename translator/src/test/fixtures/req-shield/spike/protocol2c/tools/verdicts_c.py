"""Verdicts of the E5 spike entries from their full failing sets (as spike/protocol2/verdicts.py).
Usage: python3 -I verdicts_c.py OUT_DIR ISOLATION.txt   (exit 0 iff every entry is as expected; `info` is never wrong)
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
            elif expect == "masked":
                good = got == original
                verdict = "MASKED" if good else "DIFFERS"
            elif expect == "pass":
                good = not got
                verdict = "PROVEN" if good else "FAILED"
            else:
                good = True
                verdict = "FAILED" if got else "PROVEN"
            ok = ok and good
            extra = sorted(got - original)
            print("%-9s %-42s %-6s %-9s %s%s%s" % (
                base, name, expect.split("=")[0], verdict, "; ".join(sorted(got)) or "-",
                "   [beyond the original: %s]" % "; ".join(extra) if extra and not expect.startswith("base=") else "",
                "   (UNEXPECTED)" if not good else ""))
    return ok


if __name__ == "__main__":
    sys.exit(0 if main(sys.argv[1], sys.argv[2]) else 1)
