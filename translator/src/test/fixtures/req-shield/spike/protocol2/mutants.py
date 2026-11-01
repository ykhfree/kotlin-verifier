"""DESIGN-E spike: Kotlin-source mutants of req-shield ReqShield (core), as the L3 lowering of DESIGN-E would emit them.

Two bases (DESIGN-E 5): `canonical` is target.vpr, the code as it is. `fixed` is target.vpr with FIX applied, the
Viper form of fixed-core.patch (fixed.vpr is a copy of it). Each entry applies exact edits to the base. An edit is
(old, new, count): `old` must occur exactly `count` times (count > 1 when the edited Kotlin helper is inlined at several
call sites), so a base edit that breaks a mutant fails loudly. On the fixed base, FIX is also applied to the text of
every edit, so a mutant of the fixed code keeps the fix wherever it keeps the fixed lines.
The Kotlin change each mutant stands for is in its comment; lines are ReqShield.kt at 2e792d2.
Usage: python3 -I mutants.py OUT_DIR [--base canonical|fixed]
  Writes OUT_DIR/original.vpr (the base) and OUT_DIR/<id>.vpr, and prints "<id> <expect>" per line. Expectations
  (verdicts.py checks them against isolate.py): base=<IDs> = the original fails exactly these obligations; fail = the
  entry fails an obligation the original does not; same = the entry fails exactly what the original fails; pass = none.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# The fix (fixed-core.patch) in Viper: (old, new, count in target.vpr). releaseQuietly and the refresh-path try/catch
# catch an Exception; under A1 no call throws an Error, so these branches never set exc. The last two lines adapt C2's
# own release calls, which the fixed code makes through releaseQuietly (count 0 in target.vpr).
FIX = [
    ("// DESIGN-E spike: req-shield ReqShield (core), lock discipline.",
     "// DESIGN-E spike: req-shield ReqShield (core) with spike/protocol2/fixed-core.patch, lock discipline.", 1),
    ("          if (e_66 != NONE) {\n            exc := e_66                 // propagates as is\n",
     "          if (e_66 != NONE && !IS_EXCEPTION(e_66)) {\n"
     "            exc := e_66                 // FIX: catch (e: Exception) skips the refresh; an Error propagates\n", 1),
    ("          if (e_101 != NONE) {\n            exc := e_101                // replaces the pending return or exception\n",
     "          if (e_101 != NONE && !IS_EXCEPTION(e_101)) {\n"
     "            exc := e_101                // FIX: releaseQuietly logs an Exception; an Error replaces the result\n", 1),
    ("      if (e_265_at72 != NONE) {\n",
     "      if (e_265_at72 != NONE && !IS_EXCEPTION(e_265_at72)) {   // FIX: releaseQuietly logs an Exception\n", 1),
    ("              if (e_265_at122 != NONE) {\n",
     "              if (e_265_at122 != NONE && !IS_EXCEPTION(e_265_at122)) {   // FIX: releaseQuietly logs an Exception\n", 1),
    ("          exc := e_c2a != NONE ? e_c2a : CE_GET\n",
     "          exc := e_c2a != NONE && !IS_EXCEPTION(e_c2a) ? e_c2a : CE_GET   // FIX: releaseQuietly\n", 0),
    ("          if (e_c2b != NONE) {\n",
     "          if (e_c2b != NONE && !IS_EXCEPTION(e_c2b)) {   // FIX: releaseQuietly\n", 0),
]

# What the original of each base fails (DESIGN-E 5.2): the user's UE3 decision makes the canonical original fail.
BASE_FAILS = {"canonical": "RET_hit,RET_recheck,RET_supplier_error", "fixed": ""}


def fixify(text):
    """FIX applied to any text: the fixed form of every unfixed line it holds."""
    for old, new, _ in FIX:
        text = text.replace(old, new)
    return text


def base_text(base):
    text = open(os.path.join(HERE, "target.vpr")).read()
    if base == "canonical":
        return text
    for old, new, count in FIX:
        found = text.count(old)
        if found != count:
            sys.exit("FIX matched %d times in target.vpr, expected %d: %r" % (found, count, old[:70]))
    return fixify(text)


# executeCallable's release on failure, at its two inlined copies with a token (task_69 @72, createReqShieldData @122).
FAIL72 = (
    "    if (t != NULL) {                                  // token != null && lockType != null @264\n"
    "      var e_265_at72: Int\n"
    "      var u_265_at72: Bool\n"
    "      e_265_at72, u_265_at72 := KeyLock_unLock(k, lt, t)   // @265\n"
    "      if (e_265_at72 != NONE) {\n"
    "        exc := e_265_at72\n"
    "      } else {\n"
    "        exc := CE_SUP                                 // throw ClientException(SUPPLIER_ERROR) @267\n"
    "      }\n"
    "    } else {\n"
    "      exc := CE_SUP\n"
    "    }\n")
FAIL122 = (
    "            if (token__89 != NULL) {                           // token != null && lockType != null @264\n"
    "              var e_265_at122: Int\n"
    "              var u_265_at122: Bool\n"
    "              e_265_at122, u_265_at122 := KeyLock_unLock(k, lockType__85, token__89)   // @265\n"
    "              if (e_265_at122 != NONE) {\n"
    "                exc := e_265_at122\n"
    "              } else {\n"
    "                exc := CE_SUP                                  // @267\n"
    "              }\n"
    "            } else {\n"
    "              exc := CE_SUP\n"
    "            }\n")

# runInBackground's release after a rejection @149-156, at its two inlined copies.
REJECT69 = (
    "              if (token__66 != NULL) {                         // @149\n"
    "                var e_152_at69: Int\n"
    "                var u_152_at69: Bool\n"
    "                e_152_at69, u_152_at69 := KeyLock_unLock(k, lockType__62, token__66)   // @152\n"
    "                if (e_152_at69 != NONE && !IS_EXCEPTION(e_152_at69)) {\n"
    "                  exc := e_152_at69           // catch (unlockError: Exception) @153 does not catch an Error\n"
    "                }\n"
    "              }\n")
REJECT125 = (
    "              if (token__89 != NULL) {                         // @149\n"
    "                var e_152_at125: Int\n"
    "                var u_152_at125: Bool\n"
    "                e_152_at125, u_152_at125 := KeyLock_unLock(k, lockType__85, token__89)   // @152\n"
    "                if (e_152_at125 != NONE && !IS_EXCEPTION(e_152_at125)) {\n"
    "                  exc := e_152_at125\n"
    "                }\n"
    "              }\n")

# executeSetCacheFunction's finally @240-248, at its two inlined copies (task_69 @75, task_125 @126).
FINALLY75 = (
    "    if (t != NULL) {\n"
    "      var e_243_at75: Int\n"
    "      var u_243_at75: Bool\n"
    "      e_243_at75, u_243_at75 := KeyLock_unLock(k, lt, t)   // @243\n")
FINALLY126 = (
    "  if (t != NULL) {\n"
    "    var e_243_at126: Int\n"
    "    var u_243_at126: Bool\n"
    "    e_243_at126, u_243_at126 := KeyLock_unLock(k, lt, t)   // @243\n")

FALLBACK_SUPPLIER = (
    "              if (e_262_at197 != NONE) {\n"
    "                exc := CE_SUP                                  // token == null: nothing to release @196, @267\n"
    "              } else {\n"
    "                var d_197: Ref := new(value)\n"
    "                d_197.value := v_262_at197\n"
    "                res := d_197\n"
    "              }\n")

MUTANTS = {
    # Kotlin: executeCallable without the release in getOrElse (@264-266 deleted).
    "M1_no_release_on_supplier_failure": ("fail", [
        (FAIL72, "    exc := CE_SUP                                     // M1: @264-266 deleted\n", 1),
        (FAIL122, "            exc := CE_SUP                                   // M1: @264-266 deleted\n", 1),
    ]),
    # Kotlin: runInBackground's catch only logs (@149-156 deleted): a rejected task's lock is never released.
    "M2_no_release_after_rejection": ("fail", [
        (REJECT69, "              // M2: @149-156 deleted\n", 1),
        (REJECT125, "              // M2: @149-156 deleted\n", 1),
    ]),
    # Kotlin: handleLockForCacheCreation's finally releases on every path (`if (!cacheCreationRequired)` @100 deleted),
    # while the creation path still owns the token: a double release (finally, then the background write).
    "M3_double_release": ("fail", [
        ("        if (!cacheCreationRequired__92) {\n", "        if (true) {                         // M3: @100 deleted\n", 1),
    ]),
    # Kotlin: handleLockFailure also writes what the fallback supplier returned:
    #   return buildReqShieldData(...).also { reqShieldConfig.setCacheFunction(key, it, timeToLiveMillis) }
    "M4_write_without_lock_after_fallback": ("fail", [
        ("                res := d_197\n",
         "                res := d_197\n"
         "                var e_w197: Int                              // M4: .also { setCacheFunction(key, it, ttl) }\n"
         "                var b_w197: Bool\n"
         "                assert WRITE_OK(k, PHASE(tr), mode)                   // LD_write\n"
         "                e_w197, b_w197 := setCacheFunction(k, d_197)\n"
         "                if (e_w197 != NONE) {\n"
         "                  exc := e_w197\n"
         "                  res := null\n"
         "                }\n", 1),
    ]),
    # Kotlin: a field `@Volatile private var lastToken: String? = null`, set after each tryLock; executeSetCacheFunction
    # releases `lastToken ?: token` (@243). A racy read of a shared field is an arbitrary value (DESIGN-v0 2.12).
    "M5_unlock_with_stale_token": ("fail", [
        (FINALLY75,
         "    if (t != NULL) {\n"
         "      var e_243_at75: Int\n"
         "      var u_243_at75: Bool\n"
         "      var lastToken_243_at75: Int                     // M5: racy read of this.lastToken\n"
         "      e_243_at75, u_243_at75 := KeyLock_unLock(k, lt, lastToken_243_at75 != NULL ? lastToken_243_at75 : t)   // @243\n", 1),
        (FINALLY126,
         "  if (t != NULL) {\n"
         "    var e_243_at126: Int\n"
         "    var u_243_at126: Bool\n"
         "    var lastToken_243_at126: Int                      // M5: racy read of this.lastToken\n"
         "    e_243_at126, u_243_at126 := KeyLock_unLock(k, lt, lastToken_243_at126 != NULL ? lastToken_243_at126 : t)   // @243\n", 1),
    ]),
    # Kotlin: executeCallable releases `LockType.CREATE` instead of its lockType (@265). Right on the creation path,
    # wrong on the refresh path.
    "M6_unlock_wrong_lock_type": ("fail", [
        ("      e_265_at72, u_265_at72 := KeyLock_unLock(k, lt, t)   // @265\n",
         "      e_265_at72, u_265_at72 := KeyLock_unLock(k, CREATE, t)   // @265 M6\n", 1),
        ("              e_265_at122, u_265_at122 := KeyLock_unLock(k, lockType__85, token__89)   // @265\n",
         "              e_265_at122, u_265_at122 := KeyLock_unLock(k, CREATE, token__89)   // @265 M6\n", 1),
    ]),
    # Kotlin: updateReqShieldData refreshes even when another request holds the update lock (`if (true)` @68).
    # The branch condition is gone, so the tool derives no `mode == OCC || t != NULL` fact for the task.
    "M7_refresh_without_lock": ("fail", [
        ("          if (onlyCreateCache__63 || token__66 != NULL) {      // @68\n",
         "          if (true) {                                         // @68 M7\n", 1),
        ("  lt == UPDATE && phase == UPDATE && (mode == OCC || t != NULL) &&\n",
         "  lt == UPDATE && phase == UPDATE &&\n", 1),
    ]),
    # Kotlin: handleLockForCacheCreation takes the create lock in every mode (@89 without the ONLY_UPDATE_CACHE check).
    "M8_create_lock_in_update_only_mode": ("fail", [
        ("      if (!onlyUpdateCache__86) {\n        var e_89: Int\n",
         "      if (true) {                       // M8: @89 without the mode check\n        var e_89: Int\n", 1),
    ]),
    # Kotlin: executeCallable releases and then returns null instead of throwing SUPPLIER_ERROR (@267):
    # a failing supplier becomes a cached null, written after the release.
    "M9_swallow_supplier_error": ("fail", [
        ("        exc := CE_SUP                                 // throw ClientException(SUPPLIER_ERROR) @267\n",
         "        // M9: getOrElse returns null @267\n", 1),
        ("      exc := CE_SUP\n    }\n  }\n  if (exc == NONE) {\n    // buildReqShieldData(...) @71\n",
         "      // M9: getOrElse returns null @267\n    }\n  }\n  if (exc == NONE) {\n    // buildReqShieldData(...) @71\n", 1),
        ("                exc := CE_SUP                                  // @267\n",
         "                // M9: getOrElse returns null @267\n", 1),
        ("              exc := CE_SUP\n            }\n          }\n          if (exc == NONE) {\n            // buildReqShieldData(...) @121\n",
         "              // M9: getOrElse returns null @267\n            }\n          }\n          if (exc == NONE) {\n            // buildReqShieldData(...) @121\n", 1),
        (FALLBACK_SUPPLIER,
         "              var d_197: Ref := new(value)                   // M9: getOrElse returns null @267\n"
         "              d_197.value := v_262_at197\n"
         "              res := d_197\n", 1),
    ]),
    # Kotlin: handleLockFailure retries a failing fallback supplier once:
    #   runCatching { executeCallable(callable, key, null, null) }.getOrElse { executeCallable(callable, key, null, null) }
    "M10_retry_fallback_supplier": ("fail", [
        (FALLBACK_SUPPLIER,
         "              if (e_262_at197 != NONE) {\n"
         "                var e_r197: Int                              // M10: the retry\n"
         "                var v_r197: Int\n"
         "                assert SUP_OK(k, PHASE(tr), mode, GAVE_UP(tr, maxAttempt))   // LD_supplier\n"
         "                e_r197, v_r197 := Callable_call()\n"
         "                EV_SUP(tr, e_r197, v_r197)\n"
         "                if (e_r197 != NONE) {\n"
         "                  exc := CE_SUP\n"
         "                } else {\n"
         "                  var d_r197: Ref := new(value)\n"
         "                  d_r197.value := v_r197\n"
         "                  res := d_r197\n"
         "                }\n"
         "              } else {\n"
         "                var d_197: Ref := new(value)\n"
         "                d_197.value := v_262_at197\n"
         "                res := d_197\n"
         "              }\n", 1),
    ]),
    # Kotlin: `attempts++` moved from @182 into the catch block: only a failed read counts as an attempt, so a cache that
    # keeps answering null keeps the request polling.
    "M11_only_failed_reads_count": ("fail", [
        ("            attempts__178 := attempts__178 + 1                 // @182\n", "            // M11: @182 moved into the catch\n", 1),
        ("                consecutiveFailures__179 := consecutiveFailures__179 + 1\n",
         "                attempts__178 := attempts__178 + 1             // M11\n"
         "                consecutiveFailures__179 := consecutiveFailures__179 + 1\n", 1),
    ]),
    # Kotlin: createReqShieldData releases right after the supplier, before the background write (@124):
    #   executeCallable(...).also { if (token != null) reqShieldConfig.keyLock.unLock(key, lockType, token) }
    "M12_release_before_write": ("fail", [
        ("            var reqShieldData__120: Ref := new(value)\n",
         "            if (token__89 != NULL) {                          // M12: release before the write\n"
         "              var e_m12: Int\n"
         "              var u_m12: Bool\n"
         "              e_m12, u_m12 := KeyLock_unLock(k, lockType__85, token__89)\n"
         "              if (e_m12 != NONE) {\n"
         "                exc := e_m12\n"
         "              }\n"
         "            }\n"
         "            var reqShieldData__120: Ref := new(value)\n", 1),
    ]),

    # ---- Controls: equivalent Kotlin rewrites; they fail exactly what the base original fails ----
    # Kotlin: executeSetCacheFunction without finally: release in a `catch (e: Throwable)` that rethrows (wrapping an
    # Exception as SET_CACHE_ERROR), and again after a normal return.
    "C1_write_release_without_finally": ("same", [
        ("  e_237_at126, b_237_at126 := setCacheFunction(k, reqShieldData)   // try @236-237\n"
         "  if (e_237_at126 != NONE) {\n"
         "    if (IS_EXCEPTION(e_237_at126)) {\n"
         "      exc := CE_SET                                   // catch (e: Exception) @238-239\n"
         "    } else {\n"
         "      exc := e_237_at126\n"
         "    }\n"
         "  }\n"
         "  // finally @240-248\n"
         "  if (t != NULL) {\n"
         "    var e_243_at126: Int\n"
         "    var u_243_at126: Bool\n"
         "    e_243_at126, u_243_at126 := KeyLock_unLock(k, lt, t)   // @243\n"
         "    if (e_243_at126 != NONE) {\n"
         "      exc := e_243_at126\n"
         "    }\n"
         "  }\n",
         "  e_237_at126, b_237_at126 := setCacheFunction(k, reqShieldData)   // C1\n"
         "  if (e_237_at126 != NONE) {\n"
         "    // catch (e: Throwable) { release; rethrow }\n"
         "    if (t != NULL) {\n"
         "      var e_c1a: Int\n"
         "      var u_c1a: Bool\n"
         "      e_c1a, u_c1a := KeyLock_unLock(k, lt, t)\n"
         "      if (e_c1a != NONE) {\n"
         "        exc := e_c1a\n"
         "      }\n"
         "    }\n"
         "    if (exc == NONE) {\n"
         "      exc := IS_EXCEPTION(e_237_at126) ? CE_SET : e_237_at126\n"
         "    }\n"
         "  } else {\n"
         "    if (t != NULL) {\n"
         "      var e_c1b: Int\n"
         "      var u_c1b: Bool\n"
         "      e_c1b, u_c1b := KeyLock_unLock(k, lt, t)\n"
         "      if (e_c1b != NONE) {\n"
         "        exc := e_c1b\n"
         "      }\n"
         "    }\n"
         "  }\n", 1),
    ]),
    # Kotlin: handleLockForCacheCreation without the flag:
    #   val cachedData = try { executeGetCacheFunction(...) } catch (e: Throwable) { unLock(...); throw e }
    #   if (cachedData != null) { unLock(...); return cachedData }
    "C2_recheck_release_without_flag": ("same", [
        ("        var cacheCreationRequired__92: Bool := false\n", "", 1),
        ("        if (e_224_at95 != NONE) {\n"
         "          exc := CE_GET\n"
         "        } else {\n"
         "          if (cachedData__95 != null) {\n"
         "            res := cachedData__95       // return cachedData @96\n"
         "            ret_96 := true\n"
         "          } else {\n"
         "            cacheCreationRequired__92 := true   // @97\n"
         "          }\n"
         "        }\n"
         "        // finally @98-103\n"
         "        if (!cacheCreationRequired__92) {\n"
         "          var e_101: Int\n"
         "          var u_101: Bool\n"
         "          e_101, u_101 := KeyLock_unLock(k, lockType__85, token__89)   // @101\n"
         "          if (e_101 != NONE) {\n"
         "            exc := e_101                // replaces the pending return or exception\n"
         "            ret_96 := false\n"
         "            res := null\n"
         "          }\n"
         "        }\n",
         "        if (e_224_at95 != NONE) {\n"
         "          var e_c2a: Int                // C2: catch (e: Throwable) { unLock; throw e }\n"
         "          var u_c2a: Bool\n"
         "          e_c2a, u_c2a := KeyLock_unLock(k, lockType__85, token__89)\n"
         "          exc := e_c2a != NONE ? e_c2a : CE_GET\n"
         "        } elseif (cachedData__95 != null) {\n"
         "          var e_c2b: Int                // C2: unLock; return cachedData\n"
         "          var u_c2b: Bool\n"
         "          e_c2b, u_c2b := KeyLock_unLock(k, lockType__85, token__89)\n"
         "          if (e_c2b != NONE) {\n"
         "            exc := e_c2b\n"
         "          } else {\n"
         "            res := cachedData__95\n"
         "            ret_96 := true\n"
         "          }\n"
         "        }\n", 1),
    ]),
    # Kotlin: the refresh lambda releases in one finally around the supplier and the write:
    #   try { val data = build(wrapSupplier { callable.call() }); wrapSet { setCacheFunction(...) } }
    #   finally { if (token != null) reqShieldConfig.keyLock.unLock(key, lockType, token) }
    "C3_refresh_release_in_one_finally": ("same", [
        ("  if (e_262_at72 != NONE) {\n"
         "    // getOrElse { ... } @263: runCatching caught every Throwable\n" + FAIL72 + "  }\n",
         "  if (e_262_at72 != NONE) {\n"
         "    exc := CE_SUP                                     // C3: no release here\n"
         "  }\n", 1),
        ("    // finally @240-248\n"
         "    if (t != NULL) {\n"
         "      var e_243_at75: Int\n"
         "      var u_243_at75: Bool\n"
         "      e_243_at75, u_243_at75 := KeyLock_unLock(k, lt, t)   // @243\n"
         "      if (e_243_at75 != NONE) {\n"
         "        exc := e_243_at75                             // a throw in finally replaces the pending one\n"
         "      }\n"
         "    }\n"
         "  }\n",
         "  }\n"
         "  // C3: the one finally around supplier and write\n"
         "  if (t != NULL) {\n"
         "    var e_c3: Int\n"
         "    var u_c3: Bool\n"
         "    e_c3, u_c3 := KeyLock_unLock(k, lt, t)\n"
         "    if (e_c3 != NONE) {\n"
         "      exc := e_c3\n"
         "    }\n"
         "  }\n", 1),
    ]),

    # ---- Assumption toggles (DESIGN-E 4): what each signed assumption carries ----
    "A1_off_errors_not_fatal": ("fail", [
        ("define ERRORS_ARE_FATAL true", "define ERRORS_ARE_FATAL false", 1),
    ]),
    # The model before the UE3 decision: lock calls never throw.
    "A2_on_lock_calls_return": ("pass", [
        ("define LOCK_CALLS_THROW true", "define LOCK_CALLS_THROW false", 1),
    ]),
    "A3_off_accepted_task_may_be_dropped": ("fail", [
        ("define ACCEPTED_TASKS_RUN true", "define ACCEPTED_TASKS_RUN false", 1),
    ]),
    "A4_off_throwing_unlock_keeps_obligation": ("fail", [
        ("define THROWING_UNLOCK_RELEASES true", "define THROWING_UNLOCK_RELEASES false", 1),
    ]),
    "A5_off_throwing_trylock_may_take_lock": ("fail", [
        ("define THROWING_TRYLOCK_TAKES_NOTHING true", "define THROWING_TRYLOCK_TAKES_NOTHING false", 1),
    ]),
}


def build(out_dir, base):
    src = base_text(base)
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "original.vpr"), "w") as f:
        f.write(src)
    print("original", "base=" + BASE_FAILS[base])
    for name, (expect, edits) in MUTANTS.items():
        text = src
        for old, new, count in edits:
            if base == "fixed":
                old, new = fixify(old), fixify(new)
            found = text.count(old)
            if found != count:
                sys.exit("%s: replacement matched %d times, expected %d: %r" % (name, found, count, old[:70]))
            text = text.replace(old, new)
        with open(os.path.join(out_dir, name + ".vpr"), "w") as f:
            f.write(text)
        print(name, expect)


if __name__ == "__main__":
    args = sys.argv[1:]
    chosen = "canonical"
    if "--base" in args:
        i = args.index("--base")
        chosen = args[i + 1]
        del args[i:i + 2]
    if chosen not in BASE_FAILS or len(args) != 1:
        sys.exit("usage: mutants.py OUT_DIR [--base canonical|fixed]")
    build(args[0], chosen)
