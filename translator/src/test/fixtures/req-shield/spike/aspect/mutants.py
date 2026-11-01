"""Design experiment for DESIGN-B.md: Kotlin-source mutants of ReqShieldAspect.getOrCreateReqShield (core-spring),
as the extended (B) lowering would emit them.

Each entry applies exact edits to target.vpr (the encoding of the unmodified Kotlin source).
An edit is (method, old, new): `old` must occur exactly once inside that method's text, so a target edit
that breaks a mutant fails loudly. The Kotlin change each mutant stands for is in its comment.
Kotlin positions in the comments refer to the edited ReqShieldAspect.kt (getOrCreateReqShield starts at 111).
Same output contract as ../effect_ghost/mutants.py:
Usage: python3 -I mutants.py OUT_DIR   (writes OUT_DIR/<id>.vpr and prints "<id> <expect>" per line)
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# The body of the getOrCreate action method in target.vpr: computeIfAbsent { createReqShield(joinPoint) }.
BODY = (
    "  exc := false\n"
    "  var cur_112_9: Ref := has(S, k) ? S.m[k] : null  // computeIfAbsent: the current value of k, if any\n"
    "  if (cur_112_9 == null) {\n"
    "    // mapping function { createReqShield(joinPoint) } @112:66, run because k is absent\n"
    "    if (thrown) {\n"
    "      exc := true                                    // rethrown; the mapping is left unestablished\n"
    "    } else {\n"
    "      var r_113_13: Ref := new(gFor)                 // createReqShield(joinPoint) @113:13: a fresh object\n"
    "      S.created := S.created union Set(r_113_13)\n"
    "      r_113_13.gFor := k\n"
    "      S.m := S.m[k := r_113_13]                      // non-null result: entered into the map\n"
    "      res := r_113_13\n"
    "    }\n"
    "  } else {\n"
    "    res := cur_112_9                                 // k is present: the function is not invoked\n"
    "  }\n"
)

# A racy read of the set-once map m (DESIGN-B.md 2.4): null, or the value the map holds now.
def racy_get(v):
    return "  inhale %s == null || (has(S, k) && S.m[k] == %s)   // racy get of a set-once map\n" % (v, v)

def fresh(r, pos, indent):
    """A call of the trusted fresh factory createReqShield: allocation bookkeeping (S.created, gFor := k)."""
    pad = " " * indent
    return (pad + "var %s: Ref := new(gFor)                 // createReqShield(joinPoint) %s: a fresh object\n" % (r, pos) +
            pad + "S.created := S.created union Set(%s)\n" % r +
            pad + "%s.gFor := k\n" % r)

# Kotlin M1 (get-then-put):
#   val method = getTargetMethod(joinPoint)
#   return reqShieldMap[method] ?: createReqShield(joinPoint).also { reqShieldMap[method] = it }
GET_THEN_PUT = (
    "  exc := false\n"
    "  var v_113_16: Ref                                  // reqShieldMap[method] @113:16 (racy read)\n" +
    racy_get("v_113_16") +
    "  if (v_113_16 != null) {\n"
    "    res := v_113_16                                  // elvis: the read value\n"
    "  } else {\n"
    "    if (thrown) {\n"
    "      exc := true                                    // createReqShield threw, outside any action\n"
    "    } else {\n" +
    fresh("r_113_40", "@113:40", 6) +
    "      S.m := S.m[k := r_113_40]                      // action: reqShieldMap[method] = it @113:74 (put)\n"
    "      res := r_113_40                                // also returns its receiver\n"
    "    }\n"
    "  }\n"
)

# Kotlin M3 (getOrPut): reqShieldMap.getOrPut(getTargetMethod(joinPoint)) { createReqShield(joinPoint) }
# Trusted model kotlin.collections.getOrPut on a ConcurrentMap (stdlib 2.0.21 MapsJVM.kt:70-75):
#   get(key) ?: defaultValue().let { default -> putIfAbsent(key, default) ?: default }
def get_or_put(read_contract):
    return (
        "  exc := false\n"
        "  var v_112_9: Ref                                   // getOrPut: get(key) @112:9 (racy read)\n" +
        (racy_get("v_112_9") if read_contract else "") +
        "  if (v_112_9 != null) {\n"
        "    res := v_112_9\n"
        "  } else {\n"
        "    // getOrPut: defaultValue() = { createReqShield(joinPoint) } @112:59, outside any action\n"
        "    if (thrown) {\n"
        "      exc := true\n"
        "    } else {\n" +
        fresh("r_112_61", "@112:61", 6) +
        "      var cur_112_9: Ref := has(S, k) ? S.m[k] : null  // action: putIfAbsent(key, default) @112:9\n"
        "      if (cur_112_9 == null) { S.m := S.m[k := r_112_61] }\n"
        "      res := cur_112_9 != null ? cur_112_9 : r_112_61  // putIfAbsent(...) ?: default\n"
        "    }\n"
        "  }\n"
    )

# Kotlin R2 (fast path before computeIfAbsent):
#   val method = getTargetMethod(joinPoint)
#   return reqShieldMap[method] ?: reqShieldMap.computeIfAbsent(method) { createReqShield(joinPoint) }
def fast_path(read_contract):
    return (
        "  exc := false\n"
        "  var v_113_16: Ref                                  // reqShieldMap[method] @113:16 (racy read)\n" +
        (racy_get("v_113_16") if read_contract else "") +
        "  if (v_113_16 != null) {\n"
        "    res := v_113_16                                  // elvis: the read value\n"
        "  } else {\n"
        "    var cur_113_40: Ref := has(S, k) ? S.m[k] : null  // action: computeIfAbsent(method) @113:40\n"
        "    if (cur_113_40 == null) {\n"
        "      if (thrown) {\n"
        "        exc := true\n"
        "      } else {\n" +
        fresh("r_113_79", "@113:79", 8) +
        "        S.m := S.m[k := r_113_79]\n"
        "        res := r_113_79\n"
        "      }\n"
        "    } else {\n"
        "      res := cur_113_40\n"
        "    }\n"
        "  }\n"
    )

# Kotlin M4 (create first, then putIfAbsent):
#   val created = createReqShield(joinPoint)
#   return reqShieldMap.putIfAbsent(getTargetMethod(joinPoint), created) ?: created
def put_if_absent_after_create(bookkeeping_when_mapped_only):
    if bookkeeping_when_mapped_only:
        # Design alternative: creation counted only when the object reaches the map (effect-bound counting).
        return (
            "  exc := false\n"
            "  if (thrown) {\n"
            "    exc := true                                      // createReqShield(joinPoint) @112:23 threw\n"
            "  } else {\n"
            "    var r_112_23: Ref := new(gFor)                   // createReqShield(joinPoint) @112:23: a fresh object\n"
            "    var cur_113_16: Ref := has(S, k) ? S.m[k] : null  // action: putIfAbsent @113:16\n"
            "    if (cur_113_16 == null) {\n"
            "      S.created := S.created union Set(r_112_23)     // effect-bound: recorded when it is mapped\n"
            "      r_112_23.gFor := k\n"
            "      S.m := S.m[k := r_112_23]\n"
            "    }\n"
            "    res := cur_113_16 != null ? cur_113_16 : r_112_23  // ?: created\n"
            "  }\n"
        )
    return (
        "  exc := false\n"
        "  if (thrown) {\n"
        "    exc := true                                      // createReqShield(joinPoint) @112:23 threw\n"
        "  } else {\n" +
        fresh("r_112_23", "@112:23", 4) +
        "    var cond_113_41: Bool                            // getTargetMethod(joinPoint) @113:41 runs after the fresh call\n"
        "    if (cond_113_41) {                               // and may throw (question 5 of DESIGN-B.md 9.4)\n"
        "      exc := true                                    // the created object is dropped\n"
        "    } else {\n"
        "      var cur_113_16: Ref := has(S, k) ? S.m[k] : null  // action: putIfAbsent @113:16\n"
        "      if (cur_113_16 == null) { S.m := S.m[k := r_112_23] }\n"
        "      res := cur_113_16 != null ? cur_113_16 : r_112_23  // ?: created\n"
        "    }\n"
        "  }\n"
    )

# Design alternative for getOrPut: creation counted only when the object reaches the map (effect-bound counting).
GET_OR_PUT_EFFECT_COUNT = (
    "  exc := false\n"
    "  var v_112_9: Ref                                   // getOrPut: get(key) @112:9 (racy read)\n" +
    racy_get("v_112_9") +
    "  if (v_112_9 != null) {\n"
    "    res := v_112_9\n"
    "  } else {\n"
    "    if (thrown) {\n"
    "      exc := true\n"
    "    } else {\n"
    "      var r_112_61: Ref := new(gFor)                 // createReqShield(joinPoint) @112:61: a fresh object\n"
    "      var cur_112_9: Ref := has(S, k) ? S.m[k] : null  // action: putIfAbsent(key, default) @112:9\n"
    "      if (cur_112_9 == null) {\n"
    "        S.created := S.created union Set(r_112_61)   // effect-bound: recorded when it is mapped\n"
    "        r_112_61.gFor := k\n"
    "        S.m := S.m[k := r_112_61]\n"
    "      }\n"
    "      res := cur_112_9 != null ? cur_112_9 : r_112_61\n"
    "    }\n"
    "  }\n"
)

STRAY_REMOVE = (
    "\n"
    "// Stray action: reqShieldMap.remove(getTargetMethod(joinPoint)) @100:13 in ReqShieldAspect.aroundReqShieldCacheable.\n"
    "// No sidecar operation names it, so it gets the default contract: MI and P_stable.\n"
    "method stray_aroundReqShieldCacheable_100(S: Ref, k: MethodKey)\n"
    "  requires MI(S)\n"
    "  ensures MI_PERMS(S)\n"
    "  ensures forall qk: MethodKey :: { S.m[qk] } qk != k ==> (has(S, qk) == old(has(S, qk))) && (has(S, qk) ==> S.m[qk] == old(S.m[qk]))\n"
    "  ensures MI_OWN(S)\n"
    "  ensures P_stable(S)\n"
    "  ensures P_once(S)\n"
    "  ensures MI_INV_1(S)\n"
    "{\n"
    "  S.m := S.m[k := null]                              // remove(key)\n"
    "}\n"
    "\nmethod probe("
)

MUTANTS = {
    # Refactoring control R1 (must stay proven):
    #   reqShieldMap.compute(getTargetMethod(joinPoint)) { _, old -> old ?: createReqShield(joinPoint) }!!
    # compute returns the new value; `!!` throws only on null.
    "R1_compute_elvis": ("pass", [
        ("getOrCreate", BODY,
         "  exc := false\n"
         "  var old__112: Ref := has(S, k) ? S.m[k] : null     // compute @112:9: the lambda's value parameter old\n"
         "  if (old__112 != null) {\n"
         "    res := old__112                                  // elvis: old; exit keep (mapping unchanged)\n"
         "  } else {\n"
         "    if (thrown) {\n"
         "      exc := true                                    // rethrown; the mapping is unchanged\n"
         "    } else {\n" +
         fresh("r_112_77", "@112:77", 6) +
         "      S.m := S.m[k := r_112_77]                      // exit put\n"
         "      res := r_112_77\n"
         "    }\n"
         "  }\n"
         "  if (!exc && res == null) { exc := true }         // `!!` @112:9 throws on null\n"),
    ]),
    # Refactoring control R2 (must stay proven): a racy fast path before computeIfAbsent.
    "R2_fast_path_get": ("pass", [("getOrCreate", BODY, fast_path(True))]),
    # Kotlin M1 (get-then-put), see GET_THEN_PUT.
    "M1_get_then_put": ("fail", [("getOrCreate", BODY, GET_THEN_PUT)]),
    # Kotlin M2 (unconditional put):
    #   val created = createReqShield(joinPoint)
    #   reqShieldMap[getTargetMethod(joinPoint)] = created
    #   return created
    "M2_unconditional_put": ("fail", [
        ("getOrCreate", BODY,
         "  exc := false\n"
         "  if (thrown) {\n"
         "    exc := true                                      // createReqShield(joinPoint) @112:23 threw\n"
         "  } else {\n" +
         fresh("r_112_23", "@112:23", 4) +
         "    var cond_113_22: Bool                            // getTargetMethod(joinPoint) @113:22 runs after the fresh call\n"
         "    if (cond_113_22) {                               // and may throw (question 5 of DESIGN-B.md 9.4)\n"
         "      exc := true                                    // the created object is dropped\n"
         "    } else {\n"
         "      S.m := S.m[k := r_112_23]                      // action: reqShieldMap[...] = created @113:9 (put)\n"
         "      res := r_112_23\n"
         "    }\n"
         "  }\n"),
    ]),
    # Kotlin M3 (getOrPut), see get_or_put.
    "M3_getOrPut": ("fail", [("getOrCreate", BODY, get_or_put(True))]),
    # Kotlin M4 (create first, then putIfAbsent), see put_if_absent_after_create.
    "M4_putIfAbsent_after_create": ("fail", [("getOrCreate", BODY, put_if_absent_after_create(False))]),
    # Kotlin M5 (the mapping function returns one shared object for every method):
    #   private val sharedReqShield: ReqShield<T> = ReqShield(ReqShieldConfiguration(...))   // new field
    #   reqShieldMap.computeIfAbsent(getTargetMethod(joinPoint)) { sharedReqShield }
    # The field is not module state: inside the action its value is a free value (instance val).
    "M5_shared_instance": ("fail", [
        ("getOrCreate", BODY,
         "  exc := false\n"
         "  var sharedReqShield__113: Ref                      // this.sharedReqShield @113:13 (instance val): free\n"
         "  inhale sharedReqShield__113 != null               // type fact: ReqShield<T> is not nullable\n"
         "  var cur_112_9: Ref := has(S, k) ? S.m[k] : null  // computeIfAbsent: the current value of k, if any\n"
         "  if (cur_112_9 == null) {\n"
         "    S.m := S.m[k := sharedReqShield__113]           // non-null result: entered into the map\n"
         "    res := sharedReqShield__113\n"
         "  } else {\n"
         "    res := cur_112_9\n"
         "  }\n"),
    ]),
    # Kotlin M6 (a new path drops the mapping): in aroundReqShieldCacheable,
    #   try { ... } catch (e: Exception) { reqShieldMap.remove(getTargetMethod(joinPoint)); throw e }
    # The remove is a stray action (no sidecar operation names it).
    "M6_remove_on_failure": ("fail", [("probe", "\nmethod probe(", STRAY_REMOVE)]),
    # Kotlin M7 (create first, then putIfAbsent, and ignore its result):
    #   val created = createReqShield(joinPoint)
    #   reqShieldMap.putIfAbsent(getTargetMethod(joinPoint), created)
    #   return created
    "M7_putIfAbsent_ignore_result": ("fail", [
        ("getOrCreate", BODY,
         "  exc := false\n"
         "  if (thrown) {\n"
         "    exc := true                                      // createReqShield(joinPoint) @112:23 threw\n"
         "  } else {\n" +
         fresh("r_112_23", "@112:23", 4) +
         "    var cond_113_34: Bool                            // getTargetMethod(joinPoint) @113:34 runs after the fresh call\n"
         "    if (cond_113_34) {                               // and may throw (question 5 of DESIGN-B.md 9.4)\n"
         "      exc := true                                    // the created object is dropped\n"
         "    } else {\n"
         "      var cur_113_9: Ref := has(S, k) ? S.m[k] : null // action: putIfAbsent @113:9 (result unused)\n"
         "      if (cur_113_9 == null) { S.m := S.m[k := r_112_23] }\n"
         "      res := r_112_23                                // return created @114:16\n"
         "    }\n"
         "  }\n"),
    ]),
    # Design alternative (v0 racy read: an arbitrary value) on the fast-path control R2. Expected pass; a failure
    # is a false alarm.
    "ARB_R2_fast_path_get": ("pass", [("getOrCreate", BODY, fast_path(False))]),
    # Design alternative (v0 racy read) on getOrPut: which obligations fail.
    "ARB_M3_getOrPut": ("fail", [("getOrCreate", BODY, get_or_put(False))]),
    # Design alternative (creation counted only when the object reaches the map) on M3 and M4. Expected fail
    # (real bugs); a pass is a missed bug.
    "EFF_M3_getOrPut": ("fail", [("getOrCreate", BODY, GET_OR_PUT_EFFECT_COUNT)]),
    "EFF_M4_putIfAbsent_after_create": ("fail", [("getOrCreate", BODY, put_if_absent_after_create(True))]),
}


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
