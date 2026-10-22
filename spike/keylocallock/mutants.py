"""Build seeded-bug mutants (E2), equivalent-mutant controls, and the atomicity probe (E4) from baseline.vpr.

Each mutant is a list of exact (old, new) replacements. A replacement must match exactly once,
so a baseline edit that breaks a mutant fails loudly instead of producing a silent copy.
Usage: python3 mutants.py OUT_DIR   (writes OUT_DIR/<id>.vpr and prints "<id> <expect>" per line)
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# expect: "fail" = a real bug the proof must reject; "pass" = equivalent change the proof must accept.
MUTANTS = {
    # unLock without the token comparison: any caller releases the current owner.
    "SB1_no_token_check": ("fail", [
        ("if (e.token == t && e.isHeld) {", "if (e.isHeld) {"),
    ]),
    # TOCTOU unLock: the token is compared in an earlier atomic step (lockMap[k]?.token == token),
    # and compute() releases without comparing again. The earlier result can be stale,
    # so it is a free value at the release step.
    "SB2_toctou_unlock": ("fail", [
        ("  res := false\n  if (has(S, k)) {\n    var e: Ref := S.m[k]\n    // existing.token == token",
         "  res := false\n  var sawMatch: Bool // result of the token read in an earlier atomic step\n"
         "  if (has(S, k)) {\n    var e: Ref := S.m[k]\n    // existing.token == token"),
        ("if (e.token == t && e.isHeld) {", "if (sawMatch && e.isHeld) {"),
    ]),
    # Takeover without refreshing expiresAt: the new holder inherits the old, already passed expiry.
    "SB3_no_expiry_refresh": ("fail", [
        ("      e.token := tok1\n      e.expiresAt := now + timeout\n", "      e.token := tok1\n"),
    ]),
    # nextToken() without increment: "local-${tokenSequence.get()}" repeats a token.
    "SB4_no_token_increment": ("fail", [
        ("      S.seq := S.seq + 1 // nextToken(): tokenSequence.incrementAndGet()\n",
         "      // nextToken(): tokenSequence.get() (no increment)\n"),
        ("      // At the cap: a token that no map entry backs.\n      S.seq := S.seq + 1\n",
         "      // At the cap: a token that no map entry backs.\n"),
        ("    } else {\n      S.seq := S.seq + 1\n      var tok2: Int := S.seq",
         "    } else {\n      var tok2: Int := S.seq"),
    ]),
    # Live entry: if (true) instead of if (semaphore.tryAcquire()); the holder is displaced.
    "SB6_acquire_without_permit": ("fail", [
        ("    if (e.permits > 0) {\n      e.permits := e.permits - 1\n", "    if (true) {\n"),
    ]),
    # Expiry path in tryLock without the isHeld CAS. Equivalent under INV-E (every entry is held).
    "SB5_no_cas_trylock_expiry": ("pass", [
        ("if (now > e.expiresAt && e.isHeld) {", "if (now > e.expiresAt) {"),
    ]),
    # Expiry path in the monitor sweep without the isHeld CAS. Equivalent under INV-E.
    "SB5b_no_cas_sweep": ("pass", [
        ("      if (e.isHeld) { // isHeld.compareAndSet(true, false)", "      if (true) {"),
    ]),
    # E4: tryLock loses its atomicity: "is there an entry?" is answered in an earlier atomic step,
    # and the insert (lockMap[k] = newLock) runs later, so the answer can be stale.
    "E4_split_trylock": ("fail", [
        ("  var acquired: Int := NULL\n  if (has(S, k)) {",
         "  var acquired: Int := NULL\n  var sawEntry: Bool // result of the check made in an earlier atomic step\n"
         "  if (sawEntry && has(S, k)) {"),
    ]),
}


def build(out_dir):
    src = open(os.path.join(HERE, "baseline.vpr")).read()
    os.makedirs(out_dir, exist_ok=True)
    for name, (expect, edits) in MUTANTS.items():
        text = src
        for old, new in edits:
            count = text.count(old)
            if count != 1:
                sys.exit("%s: replacement matched %d times: %r" % (name, count, old[:60]))
            text = text.replace(old, new)
        with open(os.path.join(out_dir, name + ".vpr"), "w") as f:
            f.write(text)
        print(name, expect)


if __name__ == "__main__":
    build(sys.argv[1])
