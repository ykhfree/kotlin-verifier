"""DESIGN-E5 spike: Kotlin-source mutants of req-shield ReqShield (core-kotlin-coroutine) in the Viper form the E5
lowering would emit, on two bases, like spike/protocol2/mutants.py for core.

Bases: `canonical` is target-coroutine.vpr (the code at 2e792d2). `fixed` is target-coroutine.vpr with FIX applied, the
Viper form of fixed-coroutine.patch. Each entry applies exact edits (old, new, count) to the base; `old` must occur
exactly `count` times. On the fixed base FIX is applied to the text of every edit too. An entry may name the bases it
runs on (default: both).
Usage: python3 -I mutants_c.py TARGET.vpr OUT_DIR --base canonical|fixed
  Writes OUT_DIR/original.vpr and OUT_DIR/<id>.vpr; prints "<id> <expect>" per line. Expectations (verdicts_c.py):
  base=<IDs> = the original fails exactly these obligations; fail = the entry fails an obligation the original does not;
  same = the entry fails exactly what the original fails; masked = the same, but only because the original
  already fails the entry's one defect; pass = none; info = recorded only.
"""
import os
import sys

# ---- FIX: fixed-coroutine.patch in Viper (DESIGN-E5 4.3) ----
# 1. The refresh tryLock @71 gets `catch (e: CancellationException) { throw e } catch (e: Exception) { log; return }`:
#    a lock store failure skips the refresh (token stays null, so the early return @73 also skips the launch).
# 2. releaseQuietly: `try { withContext(NonCancellable) { unLock } } catch (unlockError: Exception) { log }` at the
#    recheck finally @110-113 and the supplier failure @245-249 (all inlined copies). @227-229 (task finally) stays.
FIX = [
    ("// DESIGN-E5 hand model: req-shield ReqShield (core-kotlin-coroutine), lock discipline under cancellation.",
     "// DESIGN-E5 hand model: req-shield ReqShield (core-kotlin-coroutine) with fixed-coroutine.patch.", 1),
    ("          if (e_71_52 != NONE) {\n            exc := e_71_52\n",
     "          if (e_71_52 != NONE && (e_71_52 == CANCEL || !IS_EXCEPTION(e_71_52))) {   // FIX: an Exception skips the refresh\n"
     "            exc := e_71_52\n", 1),
    ("            if (e_112_25 != NONE) {\n",
     "            if (e_112_25 != NONE && !IS_EXCEPTION(e_112_25)) {   // FIX: releaseQuietly\n", 1),
    ("        if (e_247_21_at79 != NONE) {\n",
     "        if (e_247_21_at79 != NONE && !IS_EXCEPTION(e_247_21_at79)) {   // FIX: releaseQuietly\n", 1),
    ("      if (e_247_21_at79b != NONE) {\n",
     "      if (e_247_21_at79b != NONE && !IS_EXCEPTION(e_247_21_at79b)) {   // FIX: releaseQuietly\n", 1),
    ("                  if (e_247_21_at135 != NONE) {\n",
     "                  if (e_247_21_at135 != NONE && !IS_EXCEPTION(e_247_21_at135)) {   // FIX: releaseQuietly\n", 1),
    ("                if (e_247_21_at135b != NONE) {\n",
     "                if (e_247_21_at135b != NONE && !IS_EXCEPTION(e_247_21_at135b)) {   // FIX: releaseQuietly\n", 1),
    # C2's own releases (count 0 in the target) go through releaseQuietly in the fixed code too.
    ("          if (e_c2a != NONE) {\n", "          if (e_c2a != NONE && !IS_EXCEPTION(e_c2a)) {   // FIX: releaseQuietly\n", 0),
    ("          if (e_c2b != NONE) {\n", "          if (e_c2b != NONE && !IS_EXCEPTION(e_c2b)) {   // FIX: releaseQuietly\n", 0),
]

BASE_FAILS = {"canonical": "RET_hit,RET_recheck,RET_supplier_error,RET_cancel", "fixed": ""}


def fixify(text):
    for old, new, _ in FIX:
        text = text.replace(old, new)
    return text


def base_text(target, base):
    text = open(target).read()
    if base == "canonical":
        return text
    for old, new, count in FIX:
        found = text.count(old)
        if found != count:
            sys.exit("FIX matched %d times, expected %d: %r" % (found, count, old[:70]))
    return fixify(text)


# ---- reusable pieces of target-coroutine.vpr ----
def rel(indent, flag, lt, t):
    """executeCallable's inlined `if (token != null) withContext(NonCancellable) { unLock }` block @245-249."""
    i = " " * indent
    return (
        "%sif (%s != NULL) {\n"
        "%s  // withContext(NonCancellable) @246:17\n"
        "%s  var e_%s: Int\n"
        "%s  var released_%s: Bool\n"
        "%s  e_%s, released_%s := KeyLock_unLock(k, %s, %s, false)  // KeyLock.unLock @247\n"
        "%s  if (e_%s != NONE) {\n"
        "%s    exc := e_%s\n"
        "%s  }\n"
        "%s}\n" % (i, t, i, i, flag, i, flag, i, flag, flag, lt, t, i, flag, i, flag, i, i))


def supplier_catch_edits(new_test_79, new_test_135, new_test_186):
    return [
        ("    } elseif (IS_EXCEPTION(e_242_13_at79)) {\n", "    } elseif (%s) {\n" % new_test_79("e_242_13_at79"), 1),
        ("  } elseif (IS_EXCEPTION(e_241_13_at79)) {\n", "  } elseif (%s) {\n" % new_test_79("e_241_13_at79"), 1),
        ("              } elseif (IS_EXCEPTION(e_242_13_at135)) {\n", "              } elseif (%s) {\n" % new_test_135("e_242_13_at135"), 1),
        ("            } elseif (IS_EXCEPTION(e_241_13_at135)) {\n", "            } elseif (%s) {\n" % new_test_135("e_241_13_at135"), 1),
        ("                } elseif (IS_EXCEPTION(e_242_13_at186)) {\n", "                } elseif (%s) {\n" % new_test_186("e_242_13_at186"), 1),
        ("              } elseif (IS_EXCEPTION(e_241_13_at186)) {\n", "              } elseif (%s) {\n" % new_test_186("e_241_13_at186"), 1),
    ]


GET_COPIES = [(4, "48"), (12, "105"), (16, "168")]


def get_wrap_edits():
    out = []
    for indent, at in GET_COPIES:
        i = " " * indent
        old = ("%sif (e_203_13_at%s == CANCEL) {\n%s  exc := e_203_13_at%s\n%s} elseif (IS_EXCEPTION(e_203_13_at%s)) {\n"
               % (i, at, i, at, i, at))
        new = "%sif (IS_EXCEPTION(e_203_13_at%s)) {   // K7: no catch (e: CancellationException) @204\n" % (i, at)
        out.append((old, new, 1))
    return out


SUP_WRAPS = [(8, "e_242_13_at79"), (6, "e_241_13_at79"), (18, "e_242_13_at135"), (16, "e_241_13_at135"),
             (18, "e_242_13_at186"), (16, "e_241_13_at186")]


def sup_wrap_edits():
    out = []
    for indent, e in SUP_WRAPS:
        i = " " * indent
        old = ("%sif (%s == CANCEL) {\n%s  exc := %s\n%s} else {\n%s  exc := ClientException_SUPPLIER_ERROR\n%s}\n"
               % (i, e, i, e, i, i, i))
        new = "%sexc := ClientException_SUPPLIER_ERROR   // K8: no `if (e is CancellationException) throw e` @250\n" % i
        out.append((old, new, 1))
    return out


TASK75_FINALLY = (
    "    // finally\n"
    "    if (token__71 != NULL) {\n"
    "      // withContext(NonCancellable) @227:17\n"
    "      var e_228_21_at82: Int\n"
    "      var released_228_21_at82: Bool\n"
    "      e_228_21_at82, released_228_21_at82 := KeyLock_unLock(k, lockType__68, token__71, false)  // KeyLock.unLock @228\n"
    "      if (e_228_21_at82 != NONE) {\n"
    "        exc := e_228_21_at82\n"
    "      }\n"
    "    }\n")
TASK138_FINALLY = (
    "  // finally\n"
    "  if (token__99 != NULL) {\n"
    "    // withContext(NonCancellable) @227:17\n"
    "    var e_228_21_at140: Int\n"
    "    var released_228_21_at140: Bool\n"
    "    e_228_21_at140, released_228_21_at140 := KeyLock_unLock(k, lockType__96, token__99, false)  // KeyLock.unLock @228\n"
    "    if (e_228_21_at140 != NONE) {\n"
    "      exc := e_228_21_at140\n"
    "    }\n"
    "  }\n")

TASK138_TRY = (
    "  var e_218_13_at140: Int\n"
    "  e_218_13_at140 := kotlinx_ensureActive(true)  // kotlinx.ensureActive @218\n"
    "  if (e_218_13_at140 == NONE) {\n"
    "    var e_219_13_at140: Int\n"
    "    var b_219_13_at140: Bool\n"
    "    assert GUARD_LD_write(k, phase, mode)  // LD_write\n"
    "    e_219_13_at140, b_219_13_at140 := setCacheFunction(k, reqShieldData__133, true)  // ReqShieldConfiguration.setCacheFunction @219\n"
    "    if (e_219_13_at140 != NONE) {\n"
    "      if (e_219_13_at140 == CANCEL) {\n"
    "        exc := e_219_13_at140\n"
    "      } elseif (IS_EXCEPTION(e_219_13_at140)) {\n"
    "        exc := ClientException_SET_CACHE_ERROR\n"
    "      } else {\n"
    "        exc := e_219_13_at140\n"
    "      }\n"
    "    }\n"
    "  } elseif (e_218_13_at140 == CANCEL) {\n"
    "    exc := e_218_13_at140\n"
    "  } else {\n"
    "    exc := e_218_13_at140\n"
    "  }\n") + TASK138_FINALLY

RECHECK = (
    "          var e_203_13_at105: Int\n"
    "          var cachedData__105: Ref\n"
    "          e_203_13_at105, cachedData__105 := getCacheFunction(k, true)  // ReqShieldConfiguration.getCacheFunction @203\n"
    "          EV_getCacheFunction(tr, k, e_203_13_at105, cachedData__105)\n"
    "          EV_raised_CANCEL(tr, e_203_13_at105)\n"
    "          if (e_203_13_at105 != NONE) {\n"
    "            if (e_203_13_at105 == CANCEL) {\n"
    "              exc := e_203_13_at105\n"
    "            } elseif (IS_EXCEPTION(e_203_13_at105)) {\n"
    "              exc := ClientException_GET_CACHE_ERROR\n"
    "            } else {\n"
    "              exc := e_203_13_at105\n"
    "            }\n"
    "          }\n"
    "          if (exc == NONE) {\n"
    "            if (cachedData__105 != null) {\n"
    "              res := cachedData__105  // return\n"
    "              ret_55_20 := true\n"
    "            } else {\n"
    "              cacheCreationRequired__102 := true\n"
    "            }\n"
    "          }\n"
    "          // finally\n"
    "          if (!cacheCreationRequired__102) {\n"
    "            // withContext(NonCancellable) @111:21\n"
    "            var e_112_25: Int\n"
    "            var released_112_25: Bool\n"
    "            e_112_25, released_112_25 := KeyLock_unLock(k, lockType__96, token__99, false)  // KeyLock.unLock @112\n"
    "            if (e_112_25 != NONE) {\n"
    "              exc := e_112_25\n"
    "              ret_55_20 := false\n"
    "              res := null\n"
    "            }\n"
    "          }\n")

FALLBACK_OK = "                  arg_186_35 := r_242_13_at186  // return\n"

MUTANTS = {
    # ---- the core mutants that apply (DESIGN-E 5.2), ported ----
    # executeCallable's catch without the release (@245-249 deleted), at its four inlined copies with a token.
    "M1_no_release_on_supplier_failure": ("fail", None, [
        (rel(6, "247_21_at79", "lockType__68", "token__71"), "", 1),
        (rel(4, "247_21_at79b", "lockType__68", "token__71"), "", 1),
        (rel(16, "247_21_at135", "lockType__96", "token__99"), "", 1),
        (rel(14, "247_21_at135b", "lockType__96", "token__99"), "", 1),
    ]),
    # The recheck's finally releases on every path (`if (!cacheCreationRequired)` @110 deleted): a double release.
    "M3_double_release": ("fail", None, [
        ("          if (!cacheCreationRequired__102) {\n", "          if (true) {   // M3: @110 deleted\n", 1),
    ]),
    # The fallback also writes the cache: `return buildReqShieldData(...).also { reqShieldConfig.setCacheFunction(key, it, timeToLiveMillis) }`.
    "M4_write_without_lock_after_fallback": ("fail", None, [
        ("                res := new_193_9_at186  // return\n",
         "                res := new_193_9_at186  // return\n"
         "                var e_m4: Int   // M4: .also { setCacheFunction(key, it, ttl) }\n"
         "                var b_m4: Bool\n"
         "                assert GUARD_LD_write(k, PHASE(tr), mode)  // LD_write\n"
         "                e_m4, b_m4 := setCacheFunction(k, new_193_9_at186, true)\n"
         "                EV_raised_CANCEL(tr, e_m4)\n"
         "                if (e_m4 != NONE) {\n"
         "                  exc := e_m4\n"
         "                  res := null\n"
         "                }\n", 1),
    ]),
    # executeSetCacheFunction releases `lastToken ?: token` (@228), lastToken a racy @Volatile field (arbitrary).
    "M5_unlock_with_stale_token": ("fail", None, [
        ("      e_228_21_at82, released_228_21_at82 := KeyLock_unLock(k, lockType__68, token__71, false)  // KeyLock.unLock @228\n",
         "      var lastToken_m5a: Int   // M5: racy read of this.lastToken\n"
         "      e_228_21_at82, released_228_21_at82 := KeyLock_unLock(k, lockType__68, lastToken_m5a != NULL ? lastToken_m5a : token__71, false)\n", 1),
        ("    e_228_21_at140, released_228_21_at140 := KeyLock_unLock(k, lockType__96, token__99, false)  // KeyLock.unLock @228\n",
         "    var lastToken_m5b: Int   // M5: racy read of this.lastToken\n"
         "    e_228_21_at140, released_228_21_at140 := KeyLock_unLock(k, lockType__96, lastToken_m5b != NULL ? lastToken_m5b : token__99, false)\n", 1),
    ]),
    # executeCallable releases `LockType.CREATE` instead of its lockType (@247): wrong on the refresh path only.
    "M6_unlock_wrong_lock_type": ("fail", None, [
        ("KeyLock_unLock(k, lockType__68, token__71, false)  // KeyLock.unLock @247", "KeyLock_unLock(k, CREATE, token__71, false)  // M6 @247", 2),
        ("KeyLock_unLock(k, lockType__96, token__99, false)  // KeyLock.unLock @247", "KeyLock_unLock(k, CREATE, token__99, false)  // M6 @247", 2),
    ]),
    # updateReqShieldData without the early return @73: the refresh runs although another request holds the lock.
    "M7_refresh_without_lock": ("fail", None, [
        ("          if (!onlyCreateCache__70 && token__71 == NULL) {\n            // return @73\n          } else {\n",
         "          if (true) {   // M7: @73 deleted\n", 1),
        (" && !(!(mode == ONLY_CREATE_CACHE) && token__71 == NULL)", "", 1),
    ]),
    # handleLockForCacheCreation takes the create lock in every mode (@99 without the ONLY_UPDATE_CACHE check).
    "M8_create_lock_in_update_only_mode": ("fail", None, [
        ("      if (!onlyUpdateCache__98) {\n", "      if (true) {   // M8: @99 without the mode check\n", 1),
    ]),
    # executeCallable returns null instead of throwing SUPPLIER_ERROR (@251); a cancellation still propagates.
    "M9_swallow_supplier_error": ("fail", None, [
        ("exc := ClientException_SUPPLIER_ERROR\n", "exc := NONE   // M9: null instead of SUPPLIER_ERROR @251\n", 6),
    ]),
    # The fallback retries a failing supplier once (not on a cancellation).
    "M10_retry_fallback_supplier": ("fail", None, [
        ("                  if (e_242_13_at186 == CANCEL) {\n"
         "                    exc := e_242_13_at186\n"
         "                  } else {\n"
         "                    exc := ClientException_SUPPLIER_ERROR\n"
         "                  }\n",
         "                  if (e_242_13_at186 == CANCEL) {\n"
         "                    exc := e_242_13_at186\n"
         "                  } else {\n"
         "                    var e_m10: Int   // M10: the retry\n"
         "                    var r_m10: Int\n"
         "                    assert GUARD_LD_supplier(k, PHASE(tr), mode, GAVE_UP(tr, maxAttempt))  // LD_supplier\n"
         "                    e_m10, r_m10 := callable(true)\n"
         "                    EV_callable(tr, e_m10, r_m10)\n"
         "                    EV_raised_CANCEL(tr, e_m10)\n"
         "                    if (e_m10 == NONE) {\n"
         "                      arg_186_35 := r_m10\n"
         "                    } elseif (e_m10 == CANCEL || !IS_EXCEPTION(e_m10)) {\n"
         "                      exc := e_m10\n"
         "                    } else {\n"
         "                      exc := ClientException_SUPPLIER_ERROR\n"
         "                    }\n"
         "                  }\n", 1),
    ]),
    # `attempts++` @180 moved into the ClientException catch: only a failed read counts as an attempt.
    "M11_only_failed_reads_count": ("fail", None, [
        ("                  attempts__162 := attempts__162 + 1\n", "                  // M11: @180 moved into the catch\n", 1),
        ("                consecutiveFailures__163 := consecutiveFailures__163 + 1\n",
         "                attempts__162 := attempts__162 + 1   // M11\n"
         "                consecutiveFailures__163 := consecutiveFailures__163 + 1\n", 1),
    ]),
    # createReqShieldData releases right after the supplier, before the background write:
    #   executeCallable(...).also { if (token != null) withContext(NonCancellable) { unLock(key, lockType, token) } }
    "M12_release_before_write": ("fail", None, [
        ("                arg_135_17 := r_242_13_at135  // return\n",
         "                arg_135_17 := r_242_13_at135  // return\n"
         "                if (token__99 != NULL) {   // M12: release before the write\n"
         "                  var e_m12: Int\n"
         "                  var u_m12: Bool\n"
         "                  e_m12, u_m12 := KeyLock_unLock(k, lockType__96, token__99, false)\n"
         "                  if (e_m12 != NONE) {\n"
         "                    exc := e_m12\n"
         "                  }\n"
         "                }\n", 1),
    ]),

    # ---- controls ported from core: equivalent rewrites ----
    # executeSetCacheFunction (task_138 copy) without finally: release in a catch of Throwable that rethrows (a
    # cancellation as is, another Exception as SET_CACHE_ERROR), and again after a normal return.
    "C1_write_release_without_finally": ("same", None, [
        (TASK138_TRY,
         "  var e_218_13_at140: Int\n"
         "  e_218_13_at140 := kotlinx_ensureActive(true)\n"
         "  var e_set_c1: Int := e_218_13_at140   // C1\n"
         "  if (e_218_13_at140 == NONE) {\n"
         "    var b_219_13_at140: Bool\n"
         "    assert GUARD_LD_write(k, phase, mode)  // LD_write\n"
         "    e_set_c1, b_219_13_at140 := setCacheFunction(k, reqShieldData__133, true)\n"
         "  }\n"
         "  if (e_set_c1 != NONE) {\n"
         "    if (token__99 != NULL) {\n"
         "      var e_c1a: Int\n"
         "      var u_c1a: Bool\n"
         "      e_c1a, u_c1a := KeyLock_unLock(k, lockType__96, token__99, false)\n"
         "      if (e_c1a != NONE) {\n"
         "        exc := e_c1a\n"
         "      }\n"
         "    }\n"
         "    if (exc == NONE) {\n"
         "      exc := e_set_c1 != CANCEL && IS_EXCEPTION(e_set_c1) ? ClientException_SET_CACHE_ERROR : e_set_c1\n"
         "    }\n"
         "  } else {\n"
         "    if (token__99 != NULL) {\n"
         "      var e_c1b: Int\n"
         "      var u_c1b: Bool\n"
         "      e_c1b, u_c1b := KeyLock_unLock(k, lockType__96, token__99, false)\n"
         "      if (e_c1b != NONE) {\n"
         "        exc := e_c1b\n"
         "      }\n"
         "    }\n"
         "  }\n", 1),
    ]),
    # The recheck without the flag: try { get } catch (e: Throwable) { NC unLock; throw e }; if (hit) { NC unLock; return }.
    "C2_recheck_release_without_flag": ("same", None, [
        ("          var cacheCreationRequired__102: Bool := false\n", "", 1),
        (RECHECK,
         "          var e_203_13_at105: Int\n"
         "          var cachedData__105: Ref\n"
         "          e_203_13_at105, cachedData__105 := getCacheFunction(k, true)\n"
         "          EV_getCacheFunction(tr, k, e_203_13_at105, cachedData__105)\n"
         "          EV_raised_CANCEL(tr, e_203_13_at105)\n"
         "          if (e_203_13_at105 != NONE) {\n"
         "            var e_c2a: Int   // C2: catch (e: Throwable) { withContext(NonCancellable) { unLock }; throw e }\n"
         "            var u_c2a: Bool\n"
         "            e_c2a, u_c2a := KeyLock_unLock(k, lockType__96, token__99, false)\n"
         "            if (e_c2a != NONE) {\n"
         "              exc := e_c2a\n"
         "            } else {\n"
         "              exc := e_203_13_at105 == CANCEL || !IS_EXCEPTION(e_203_13_at105) ? e_203_13_at105 : ClientException_GET_CACHE_ERROR\n"
         "            }\n"
         "          } elseif (cachedData__105 != null) {\n"
         "            var e_c2b: Int   // C2: withContext(NonCancellable) { unLock }; return cachedData\n"
         "            var u_c2b: Bool\n"
         "            e_c2b, u_c2b := KeyLock_unLock(k, lockType__96, token__99, false)\n"
         "            if (e_c2b != NONE) {\n"
         "              exc := e_c2b\n"
         "            } else {\n"
         "              res := cachedData__105\n"
         "              ret_55_20 := true\n"
         "            }\n"
         "          }\n", 1),
    ]),
    # The refresh lambda releases once, in one finally around the supplier and the write (task_75 only): its supplier
    # call has no release of its own, and the write has no finally.
    "C3_refresh_release_in_one_finally": ("same", None, [
        (rel(6, "247_21_at79", "lockType__68", "token__71"), "", 1),
        (rel(4, "247_21_at79b", "lockType__68", "token__71"), "", 1),
        (TASK75_FINALLY, "", 1),
        ("  if (exc != NONE && exc == CANCEL) {\n    var caught_83_22: Int := exc\n",
         "  // C3: the one finally around the supplier and the write\n"
         "  if (token__71 != NULL) {\n"
         "    var e_c3: Int\n"
         "    var u_c3: Bool\n"
         "    e_c3, u_c3 := KeyLock_unLock(k, lockType__68, token__71, false)\n"
         "    if (e_c3 != NONE) {\n"
         "      exc := e_c3\n"
         "    }\n"
         "  }\n"
         "  if (exc != NONE && exc == CANCEL) {\n    var caught_83_22: Int := exc\n", 1),
    ]),

    # ---- coroutine mutants (DESIGN-E5 3.4) ----
    # The recheck's finally releases without withContext(NonCancellable) @111: a cancelled release may not run.
    "K1_recheck_release_cancellable": ("fail", None, [
        ("KeyLock_unLock(k, lockType__96, token__99, false)  // KeyLock.unLock @112", "KeyLock_unLock(k, lockType__96, token__99, true)  // K1 @112", 1),
    ]),
    # executeCallable's release without withContext(NonCancellable) @246.
    "K2_supplier_release_cancellable": ("fail", None, [
        ("token__71, false)  // KeyLock.unLock @247", "token__71, true)  // K2 @247", 2),
        ("token__99, false)  // KeyLock.unLock @247", "token__99, true)  // K2 @247", 2),
    ]),
    # executeSetCacheFunction's finally releases without withContext(NonCancellable) @227 (both tasks).
    "K3_write_release_cancellable": ("fail", None, [
        ("token__71, false)  // KeyLock.unLock @228", "token__71, true)  // K3 @228", 1),
        ("token__99, false)  // KeyLock.unLock @228", "token__99, true)  // K3 @228", 1),
    ]),
    # scope.launch { } without `start = CoroutineStart.ATOMIC` @75 and @138: a cancelled scope drops the block.
    "K4_launch_not_atomic": ("fail", None, [
        ("kotlinx_launch(true)", "kotlinx_launch(false)", 2),
    ]),
    # executeCallable catches `IOException` only @243 (hand model: it takes no modeled kind; the tool rejects the type).
    "K5_supplier_catch_ioexception": ("fail", None, supplier_catch_edits(lambda e: "false", lambda e: "false", lambda e: "false")),
    # executeCallable catches `RuntimeException` only @243: a checked exception or an interrupt skips the release.
    "K5b_supplier_catch_runtime": ("fail", None, supplier_catch_edits(
        lambda e: "%s == RTE || %s == CANCEL" % (e, e), lambda e: "%s == RTE || %s == CANCEL" % (e, e),
        lambda e: "%s == RTE || %s == CANCEL" % (e, e))),
    # executeSetCacheFunction releases after its try instead of in `finally` (both tasks).
    "K6_write_release_outside_finally": ("fail", None, [
        ("    // finally\n    if (token__71 != NULL) {\n", "    // K6: after the try, not in finally\n    if (exc == NONE && token__71 != NULL) {\n", 1),
        ("  // finally\n  if (token__99 != NULL) {\n", "  // K6: after the try, not in finally\n  if (exc == NONE && token__99 != NULL) {\n", 1),
    ]),
    # executeGetCacheFunction without `catch (e: CancellationException) { throw e }` @204: a cancellation becomes
    # ClientException(GET_CACHE_ERROR).
    "K7_get_wraps_cancel": ("fail", None, get_wrap_edits()),
    # executeCallable without `if (e is CancellationException) throw e` @250: a cancellation becomes SUPPLIER_ERROR.
    # On the canonical base its one new failure (RET_cancel) is masked: the canonical original fails RET_cancel already.
    "K8_supplier_wraps_cancel": ({"canonical": "masked", "fixed": "fail"}, None, sup_wrap_edits()),
    # The polling catch takes `Exception` @172 and the cancellation catch @170 is gone: a cancellation counts as a failed read.
    "K9_poll_swallows_cancel": ("fail", None, [
        ("              if (exc != NONE && exc == CANCEL) {\n"
         "                var caught_170_22: Int := exc\n"
         "                exc := NONE\n"
         "                exc := caught_170_22\n"
         "              } elseif (exc != NONE && exc == ClientException_GET_CACHE_ERROR) {\n",
         "              if (exc != NONE && IS_EXCEPTION(exc)) {   // K9: catch (e: Exception) @172, no @170\n", 1),
    ]),
    # A naive port of the core fix: the refresh tryLock catches `Exception` only, so a cancellation is swallowed too.
    "K10_naive_fix_swallows_cancel": ("fail", ("fixed",), [
        ("          if (e_71_52 != NONE && (e_71_52 == CANCEL || !IS_EXCEPTION(e_71_52))) {",
         "          if (e_71_52 != NONE && !IS_EXCEPTION(e_71_52)) {   // K10: catch (e: Exception) only", 1),
    ]),
    # ---- coroutine controls ----
    # The polling loop without `catch (e: CancellationException) { throw e }` @170: ClientException does not take it.
    "KC1_poll_without_cancel_catch": ("same", None, [
        ("              if (exc != NONE && exc == CANCEL) {\n"
         "                var caught_170_22: Int := exc\n"
         "                exc := NONE\n"
         "                exc := caught_170_22\n"
         "              } elseif (exc != NONE && exc == ClientException_GET_CACHE_ERROR) {\n",
         "              if (exc != NONE && exc == ClientException_GET_CACHE_ERROR) {   // KC1: no @170\n", 1),
    ]),
    # The refresh task swallows its own cancellation (`catch (e: CancellationException) { throw e }` @83 deleted): a
    # task's exception reaches no caller.
    "KC2_task_swallows_cancel": ("same", None, [
        ("  if (exc != NONE && exc == CANCEL) {\n"
         "    var caught_83_22: Int := exc\n"
         "    exc := NONE\n"
         "    exc := caught_83_22\n"
         "  } elseif (exc != NONE && IS_EXCEPTION(exc)) {\n",
         "  if (exc != NONE && IS_EXCEPTION(exc)) {   // KC2: no @83\n", 1),
    ]),
    # A non-suspending KeyLock (KeyLocalLock: compute(), no suspension) cannot be cancelled: no CANCEL from KeyLock.
    "KC3_lock_calls_not_cancellable": ("same", None, [
        ("  ensures exc == NONE || (LOCK_CALLS_THROW && exc == RTE) || (cx && exc == CANCEL) || (MAY_ERR && exc == ERR)\n"
         "  ensures exc != NONE ==> t == NULL\n",
         "  ensures exc == NONE || (LOCK_CALLS_THROW && exc == RTE) || (MAY_ERR && exc == ERR)\n"
         "  ensures exc != NONE ==> t == NULL\n", 1),
    ]),

    # ---- assumption toggles (DESIGN-E 4 and UE6) ----
    "A1_off_errors_not_fatal": ("fail", None, [("define ERRORS_ARE_FATAL true", "define ERRORS_ARE_FATAL false", 1)]),
    "A2_on_lock_calls_return": ("pass", None, [("define LOCK_CALLS_THROW true", "define LOCK_CALLS_THROW false", 1)]),
    "A3_off_accepted_task_may_be_dropped": ("fail", None, [("define ACCEPTED_TASKS_RUN true", "define ACCEPTED_TASKS_RUN false", 1)]),
    "A4_off_throwing_unlock_keeps_obligation": ("fail", None, [("define THROWING_UNLOCK_RELEASES true", "define THROWING_UNLOCK_RELEASES false", 1)]),
    "A5_off_throwing_trylock_may_take_lock": ("fail", None, [("define THROWING_TRYLOCK_TAKES_NOTHING true", "define THROWING_TRYLOCK_TAKES_NOTHING false", 1)]),
    "A6_off_dispatch_may_throw": ("fail", None, [("define DISPATCH_NEVER_THROWS true", "define DISPATCH_NEVER_THROWS false", 1)]),
    # A_F4 of Phase 0: with A1 off and only the supplier able to throw an Error, executeCallable's catch (e: Exception) @243
    # does not release (the other Error sources are cut so that the leak has this one cause).
    "AF4_supplier_error_only": ("fail", None, [
        ("define ERRORS_ARE_FATAL true", "define ERRORS_ARE_FATAL false", 1),
        ("(MAY_ERR && exc == ERR)\n", "false\n", 7),
        ("|| (MAY_ERR && exc == ERR)  // A6\n", "|| false  // A6\n", 1),
        ("  ensures exc == NONE || exc == RTE || exc == CHK || exc == INTR || (cx && exc == CANCEL) || false\n",
         "  ensures exc == NONE || exc == RTE || exc == CHK || exc == INTR || (cx && exc == CANCEL) || (MAY_ERR && exc == ERR)\n", 1),
    ]),

    # ---- decision and encoding probes (DESIGN-E5 3.5) ----
    # UE7 (b): no cancellation escape in the RET clauses.
    "UE7b_no_cancel_escape": ("info", None, [(" && exc != CANCEL ==>", " ==>", 6)]),
    # The lowering gap: the task precondition without the early-return fact of @73 (the lowering keeps no condition of
    # an `if (c) return` for the statements after it).
    "G1_no_early_return_fact": ("info", None, [(" && !(!(mode == ONLY_CREATE_CACHE) && token__71 == NULL)", "", 1)]),
    # A4 stretched to cancellation: a cancelled unLock always released. K1 is then not detected (see K1x).
    "K1x_recheck_cancellable_A4_covers_cancel": ("info", None, [
        ("KeyLock_unLock(k, lockType__96, token__99, false)  // KeyLock.unLock @112", "KeyLock_unLock(k, lockType__96, token__99, true)  // K1 @112", 1),
        ("  ensures exc == CANCEL && cancelKeeps(k, lt, t) ==> Lease(k, lt, t)  // a cancelled release may not have run\n", "", 1),
    ]),
    # A cancelled release that always keeps the obligation (no cancelKeeps): an equivalent encoding for this code.
    "X1_cancelled_release_always_keeps": ("info", None, [
        ("  ensures exc == CANCEL && cancelKeeps(k, lt, t) ==> Lease(k, lt, t)", "  ensures exc == CANCEL ==> Lease(k, lt, t)", 1),
    ]),
}


def build(target, out_dir, base):
    src = base_text(target, base)
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "original.vpr"), "w") as f:
        f.write(src)
    print("original", "base=" + BASE_FAILS[base])
    for name, (expect, bases, edits) in MUTANTS.items():
        if bases is not None and base not in bases:
            continue
        text = src
        for old, new, count in edits:
            if base == "fixed":
                old, new = fixify(old), fixify(new)
            found = text.count(old)
            if found != count:
                sys.exit("%s: replacement matched %d times, expected %d: %r" % (name, found, count, old[:90]))
            text = text.replace(old, new)
        with open(os.path.join(out_dir, name + ".vpr"), "w") as f:
            f.write(text)
        print(name, expect[base] if isinstance(expect, dict) else expect)


if __name__ == "__main__":
    a = sys.argv[1:]
    if len(a) != 4 or a[2] != "--base" or a[3] not in BASE_FAILS:
        sys.exit("usage: mutants_c.py TARGET.vpr OUT_DIR --base canonical|fixed")
    build(a[0], a[1], a[3])
