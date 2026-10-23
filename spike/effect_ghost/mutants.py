"""Design experiment for DESIGN-v0.md: Kotlin-source mutants as the v0 lowering would emit them.

Each entry applies exact edits to target.vpr (the effect-bound encoding of the unmodified Kotlin source).
An edit is (method, old, new): `old` must occur exactly once inside that method's text, so a target edit
that breaks a mutant fails loudly. The Kotlin change each mutant stands for is in its comment.
Same output contract as ../keylocallock/mutants.py:
Usage: python3 -I mutants.py OUT_DIR   (writes OUT_DIR/<id>.vpr and prints "<id> <expect>" per line)
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

END_RULE = (
    "  if (pre_tok != NULL && !(has(S, k) && S.m[k].token == pre_tok)) {\n"
    "    tok(pre_tok).gHeld := false\n"
    "  }\n"
)

# Design (b): the END ghost update is bound to a library event instead of the commit point:
# "on AtomicBoolean.compareAndSet(true, false) on an entry's isHeld: end that entry's token".
CAS_BOUND = [
    ("tryLock", END_RULE, ""),
    ("tryLock", "      if (cond_161_21) { existing__155.isHeld := false }\n",
     "      if (cond_161_21) {\n        tok(existing__155.token).gHeld := false\n        existing__155.isHeld := false\n      }\n"),
    ("unLock", END_RULE, ""),
    ("unLock", "      if (cond_213_17) { existing__209.isHeld := false }\n",
     "      if (cond_213_17) {\n        tok(existing__209.token).gHeld := false\n        existing__209.isHeld := false\n      }\n"),
    ("sweep", END_RULE, ""),
    ("sweep", "      if (cond_104_49) { lockInfo__97.isHeld := false }\n",
     "      if (cond_104_49) {\n        tok(lockInfo__97.token).gHeld := false\n        lockInfo__97.isHeld := false\n      }\n"),
]

# Design (a): the END ghost update sits at a code point, as a `//@ ghost` comment would put it: first statement
# of the block guarded by the release condition. The comment stays in place when a mutant edits the condition.
CODE_POINT_BOUND = [
    ("tryLock", END_RULE, ""),
    ("tryLock", "    if (cond_161_21) {\n      existing__155.token := NULL                        // @163:21\n",
     "    if (cond_161_21) {\n      tok(existing__155.token).gHeld := false        //@ ghost end(existing.token)\n"
     "      existing__155.token := NULL                        // @163:21\n"),
    ("unLock", END_RULE, ""),
    ("unLock", "    if (cond_213_17) {\n      existing__209.permits := existing__209.permits + 1 // Semaphore.release() @214:17\n",
     "    if (cond_213_17) {\n      tok(existing__209.token).gHeld := false        //@ ghost end(existing.token)\n"
     "      existing__209.permits := existing__209.permits + 1 // Semaphore.release() @214:17\n"),
    ("sweep", END_RULE, ""),
    ("sweep", "      if (cond_104_49) {\n        lockInfo__97.token := NULL                       // @105:49\n",
     "      if (cond_104_49) {\n        tok(lockInfo__97.token).gHeld := false       //@ ghost end(lockInfo.token)\n"
     "        lockInfo__97.token := NULL                       // @105:49\n"),
]

# Kotlin SB5 (KeyLocalLock.kt:161): `if (now > existing.expiresAt && existing.isHeld.compareAndSet(true, false))`
#   -> `if (now > existing.expiresAt)`. The CAS call disappears, so isHeld is not reset.
SB5_TRYLOCK = [
    ("tryLock",
     "    var cond_161_21: Bool := false\n"
     "    if (now > existing__155.expiresAt) {\n"
     "      cond_161_21 := existing__155.isHeld == true // AtomicBoolean.compareAndSet @161:49\n"
     "      if (cond_161_21) { existing__155.isHeld := false }\n"
     "    }\n"
     "    if (cond_161_21) {\n",
     "    if (now > existing__155.expiresAt) {\n"),
]
SB5_TRYLOCK_CAS_BOUND = [
    ("tryLock",
     "    var cond_161_21: Bool := false\n"
     "    if (now > existing__155.expiresAt) {\n"
     "      cond_161_21 := existing__155.isHeld == true // AtomicBoolean.compareAndSet @161:49\n"
     "      if (cond_161_21) {\n        tok(existing__155.token).gHeld := false\n        existing__155.isHeld := false\n      }\n"
     "    }\n"
     "    if (cond_161_21) {\n",
     "    if (now > existing__155.expiresAt) {\n"),
]

# Kotlin SB5b (KeyLocalLock.kt:104): `if (lockInfo.isHeld.compareAndSet(true, false))` -> `if (true)`.
SB5B_SWEEP = [
    ("sweep",
     "      var cond_104_49: Bool := lockInfo__97.isHeld == true // AtomicBoolean.compareAndSet @104:49\n"
     "      if (cond_104_49) { lockInfo__97.isHeld := false }\n"
     "      if (cond_104_49) {\n",
     "      if (true) {\n"),
]
SB5B_SWEEP_CAS_BOUND = [
    ("sweep",
     "      var cond_104_49: Bool := lockInfo__97.isHeld == true // AtomicBoolean.compareAndSet @104:49\n"
     "      if (cond_104_49) {\n        tok(lockInfo__97.token).gHeld := false\n        lockInfo__97.isHeld := false\n      }\n"
     "      if (cond_104_49) {\n",
     "      if (true) {\n"),
]

# Kotlin SB4 (KeyLocalLock.kt:80): `"local-${tokenSequence.incrementAndGet()}"` -> `"local-${tokenSequence.get()}"`.
# nextToken() is inlined at its three call sites, so one Kotlin edit removes three increments.
SB4_EDITS = [
    ("tryLock", "      S.seq := S.seq + 1                       // nextToken() @169:33 -> AtomicLong.incrementAndGet() @80:52\n", ""),
    ("tryLock", "      S.seq := S.seq + 1                       // nextToken() @182:39\n", ""),
    ("tryLock", "      S.seq := S.seq + 1                       // nextToken() @187:29\n", ""),
]

MUTANTS = {
    # Refactoring control R1 (must stay proven): unLock clears the token before dropping the entry,
    # `existing.token = null` ahead of `return@compute null` @219:17. Equivalent behavior.
    "R1_unlock_clears_token_before_remove": ("pass", [
        ("unLock",
         "      S.m := S.m[k := null]                    // exit: return@compute null @219:17 (remove)\n",
         "      existing__209.token := NULL              // refactor: clear the token first\n"
         "      S.m := S.m[k := null]                    // exit: return@compute null @219:17 (remove)\n"),
    ]),
    # Refactoring control R2 (must stay proven): an expired takeover puts a fresh LockInfo for the key
    # instead of reusing the existing one (`return@compute LockInfo(...)` after the forced release).
    "R2_takeover_with_fresh_lockinfo": ("pass", [
        ("tryLock",
         "    if (cond_161_21) {\n"
         "      existing__155.token := NULL                        // @163:21\n"
         "      existing__155.permits := existing__155.permits + 1 // Semaphore.release() @164:21\n"
         "    }\n"
         "    var cond_168_21: Bool := existing__155.permits > 0   // Semaphore.tryAcquire() @168:21\n",
         "    var cond_168_21: Bool := false\n"
         "    if (cond_161_21) {\n"
         "      existing__155.token := NULL\n"
         "      existing__155.permits := existing__155.permits + 1\n"
         "      S.seq := S.seq + 1\n"
         "      var n_fresh: Int := S.seq\n"
         "      assert 0 <= n_fresh\n"
         "      var fresh: Ref := new(permits, isHeld, token, expiresAt)\n"
         "      S.created := S.created union Set(fresh)\n"
         "      fresh.permits := 0\n"
         "      fresh.isHeld := true\n"
         "      fresh.token := n_fresh\n"
         "      fresh.expiresAt := now + timeout\n"
         "      acquiredToken__151 := n_fresh\n"
         "      S.m := S.m[k := fresh]                 // refactor: return@compute fresh (put)\n"
         "    } else {\n"
         "      cond_168_21 := existing__155.permits > 0\n"
         "    }\n"),
    ]),
    # Kotlin SB1 (KeyLocalLock.kt:213): drop `existing.token == token &&` from the unLock condition.
    "SB1_no_token_check": ("fail", [
        ("unLock",
         "    var cond_213_17: Bool := false\n"
         "    if (existing__209.token == t) {\n"
         "      cond_213_17 := existing__209.isHeld == true // @213:44\n"
         "      if (cond_213_17) { existing__209.isHeld := false }\n"
         "    }\n",
         "    var cond_213_17: Bool := existing__209.isHeld == true // @213:17\n"
         "    if (cond_213_17) { existing__209.isHeld := false }\n"),
    ]),
    # Kotlin SB2: before compute, `val sawMatch = lockMap[completeKey]?.token == token` (a racy read outside the
    # action), and the condition becomes `sawMatch && existing.isHeld.compareAndSet(true, false)`.
    # The sidecar does not name sawMatch, so it is an unconstrained local, not a method parameter.
    "SB2_toctou_unlock": ("fail", [
        ("unLock", "  var released__204: Bool := false             // cell AtomicBoolean(false) @204:24\n",
         "  var released__204: Bool := false             // cell AtomicBoolean(false) @204:24\n"
         "  var sawMatch__205: Bool                      // racy read lockMap[completeKey]?.token == token: free\n"),
        ("unLock", "    if (existing__209.token == t) {\n", "    if (sawMatch__205) {\n"),
    ]),
    # Kotlin SB3 (KeyLocalLock.kt:172): delete `existing.expiresAt = now + lockTimeoutMillis`.
    "SB3_no_expiry_refresh": ("fail", [
        ("tryLock", "      existing__155.expiresAt := now + timeout // @172:21 (Long.plus @172:42, unbounded)\n", ""),
    ]),
    "SB4_no_token_increment": ("fail", SB4_EDITS),
    # Kotlin SB6 (KeyLocalLock.kt:168): `if (existing.semaphore.tryAcquire())` -> `if (true)`.
    "SB6_acquire_without_permit": ("fail", [
        ("tryLock",
         "    var cond_168_21: Bool := existing__155.permits > 0   // Semaphore.tryAcquire() @168:21\n"
         "    if (cond_168_21) { existing__155.permits := existing__155.permits - 1 }\n"
         "    if (cond_168_21) {\n",
         "    if (true) {\n"),
    ]),
    "SB5_no_cas_trylock_expiry": ("pass", SB5_TRYLOCK),
    "SB5b_no_cas_sweep": ("pass", SB5B_SWEEP),
    # Kotlin E4: before compute, `val sawEntry = lockMap.containsKey(completeKey)` (racy read), and
    # `if (existing != null)` -> `if (sawEntry && existing != null)`. The else branch can now run while an
    # entry exists; its `return@compute null` and `newLock` exits then remove or replace a live entry.
    "E4_split_trylock": ("fail", [
        ("tryLock", "  var acquiredToken__151: Int := NULL                                    // cell AtomicReference(null) @151:29\n",
         "  var acquiredToken__151: Int := NULL                                    // cell AtomicReference(null) @151:29\n"
         "  var sawEntry__152: Bool                                                // racy read lockMap.containsKey(completeKey): free\n"),
        ("tryLock", "  if (existing__155 != null) { // @156:17\n", "  if (sawEntry__152 && existing__155 != null) { // @156:17\n"),
    ]),
    # Core variant (core/.../KeyLocalLock.kt:206-209): LockInfo(Semaphore(1), now + lockTimeoutMillis) takes the
    # token default null, and `newLock.token = token` runs after `newLock.isHeld.set(true)`.
    "CORE_variant": ("pass", [
        ("tryLock", "      newLock__188.token := token__187\n", "      newLock__188.token := NULL\n"),
        ("tryLock", "      newLock__188.isHeld := true              // @190:17\n",
         "      newLock__188.isHeld := true              // @190:17\n      newLock__188.token := token__187\n"),
    ]),
    # Design (b) with the unmodified code: CAS-bound END must verify too.
    "CASB_original": ("pass", CAS_BOUND),
    # Design (b) on the equivalent-mutant controls: the bound event disappears with the CAS call.
    "CASB_SB5_no_cas_trylock_expiry": ("pass", CAS_BOUND + SB5_TRYLOCK_CAS_BOUND),
    "CASB_SB5b_no_cas_sweep": ("pass", CAS_BOUND + SB5B_SWEEP_CAS_BOUND),
    # Design (a) with the unmodified code and on the two controls.
    "CPB_original": ("pass", CODE_POINT_BOUND),
    "CPB_SB5_no_cas_trylock_expiry": ("pass", CODE_POINT_BOUND + SB5_TRYLOCK),
    "CPB_SB5b_no_cas_sweep": ("pass", CODE_POINT_BOUND + SB5B_SWEEP),
}

# Design (a) on the real-bug mutants: the same Kotlin edits, applied after the code-point binding.
for _name in ["SB1_no_token_check", "SB2_toctou_unlock", "SB3_no_expiry_refresh", "SB4_no_token_increment",
              "SB6_acquire_without_permit", "E4_split_trylock"]:
    MUTANTS["CPB_" + _name] = ("fail", CODE_POINT_BOUND + MUTANTS[_name][1])


def method_span(text, name):
    """Returns (start, end) of the text from `method <name>(` to the closing brace at column 0."""
    start = text.find("\nmethod %s(" % name)
    if start < 0:
        sys.exit("method %s not found" % name)
    body_end = text.find("\n}\n", start)
    if body_end < 0:
        sys.exit("method %s has no closing brace at column 0" % name)
    return start, body_end + 3


def apply(text, edits, name):
    for method, old, new in edits:
        start, end = method_span(text, method)
        part = text[start:end]
        count = part.count(old)
        if count != 1:
            sys.exit("%s: edit in %s matched %d times: %r" % (name, method, count, old[:70]))
        text = text[:start] + part.replace(old, new) + text[end:]
    return text


def build(out_dir):
    src = open(os.path.join(HERE, "target.vpr")).read()
    os.makedirs(out_dir, exist_ok=True)
    for name, (expect, edits) in MUTANTS.items():
        with open(os.path.join(out_dir, name + ".vpr"), "w") as f:
            f.write(apply(src, edits, name))
        print(name, expect)


if __name__ == "__main__":
    build(sys.argv[1])
