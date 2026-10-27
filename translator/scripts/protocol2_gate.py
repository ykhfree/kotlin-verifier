"""DESIGN-E 6.10 E3 gate: is a generated client model the hand model of spike/protocol2 up to the E3 rename table, the
E3 structural edits and normalization?

The hand models are spike/protocol2/target.vpr (`--base canonical`) and fixed.vpr (`--base fixed`, target.vpr with FIX
of spike/protocol2/mutants.py). This script reads them and never writes into spike/. It applies, to a copy:
1. STRUCTURAL: edits where the tool's encoding differs from the hand model but means the same (each with its reason).
   On the fixed base the edits pass through FIX first (as mutants.py does for its mutants), then FIXED_ONLY applies.
2. RENAMES: names that differ only (the tool picks its names; the main session renames the hand model).
Then it compares with the normalizer spike/vpr_normalize.py plus the two changes E3 needs (NORMALIZER below).
Usage: python3 -I protocol2_gate.py --spike DIR --base canonical|fixed GENERATED.vpr [--write EXPECTED.vpr]
Exit 0: the same program. Exit 1: different (a unified diff of the normalized forms). Exit 2: an edit did not match.
"""
import argparse
import difflib
import importlib.util
import os
import re
import sys

# ---- 2. names that differ only: hand model name -> tool name ----
RENAMES = [
    # Enum entries keep their Kotlin names (DESIGN-E 6.2 `enum`).
    ("CAU", "CREATE_AND_UPDATE_CACHE"), ("OCC", "ONLY_CREATE_CACHE"), ("OUC", "ONLY_UPDATE_CACHE"),
    # A kind the code throws is its class and error code (DESIGN-E 3.5, KVIR Thrown).
    ("CE_GET", "ClientException_GET_CACHE_ERROR"), ("CE_SET", "ClientException_SET_CACHE_ERROR"),
    ("CE_SUP", "ClientException_SUPPLIER_ERROR"),
    # Event macros are named by their model; guard macros by their guard ID.
    ("EV_READ", "EV_getCacheFunction"), ("EV_TRY", "EV_KeyLock_tryLock"), ("EV_SUP", "EV_Callable_call"),
    ("WRITE_OK", "GUARD_LD_write"), ("SUP_OK", "GUARD_LD_supplier"),
    # The lowering folds the Kotlin const MAX_CONSECUTIVE_GET_CACHE_FAILURES to its value (golden KVIR `>= 3`).
    ("MAX_FAIL", "3"),
]
# A task's parameters are its captured locals (KVIR Task.params), in the task method and its precondition macro.
SCOPED = [
    ("define TASK69_PRE", "// Task precondition of the lambda @125", [("lt", "lockType__62"), ("t", "token__66")]),
    ("define TASK125_PRE", "// ---- The public function ----",
     [("lt", "lockType__85"), ("t", "token__89"), ("reqShieldData", "reqShieldData__120")]),
]
# The precondition macro is named by the task, whose id is the lambda's line: TASK_<line>_PRE (read from GENERATED).
PRE_MACROS = ["TASK69_PRE", "TASK125_PRE"]

# ---- 1. encodings that differ but mean the same: (old, new, count, reason), on target.vpr text ----
FAIL_RELEASE = (
    "{i}if (t != NULL) {{                                  // token != null && lockType != null @264\n"
    "{i}  var e_265_at72: Int\n"
    "{i}  var u_265_at72: Bool\n"
    "{i}  e_265_at72, u_265_at72 := KeyLock_unLock(k, lt, t)   // @265\n"
    "{i}  if (e_265_at72 != NONE) {{\n"
    "{i}    exc := e_265_at72\n"
    "{i}  }} else {{\n"
    "{i}    exc := CE_SUP                                 // throw ClientException(SUPPLIER_ERROR) @267\n"
    "{i}  }}\n"
    "{i}}} else {{\n"
    "{i}  exc := CE_SUP\n"
    "{i}}}\n")
FAIL_RELEASE_122 = (
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
STRUCTURAL = [
    ("define MAX_FAIL 3\n", "", 1,
     "the tool writes the folded constant 3 (see RENAMES), so it has no define"),
    ("  ensures NO_LEASES                                   // LD_noleak\n  ensures TR(tr)\n",
     "  ensures TR(tr)\n  ensures NO_LEASES\n", 1,
     "every method states its trace frame TR(tr) first, then LD_noleak (task_69 had them the other way)"),
    (FAIL_RELEASE.format(i="    "),
     "    if (t != NULL) {\n"
     "      var e_265_at72: Int\n"
     "      var u_265_at72: Bool\n"
     "      e_265_at72, u_265_at72 := KeyLock_unLock(k, lt, t)\n"
     "      if (e_265_at72 != NONE) {\n"
     "        exc := e_265_at72\n"
     "      }\n"
     "    }\n"
     "    if (exc == NONE) {\n"
     "      exc := CE_SUP\n"
     "    }\n", 1,
     "rule G1: the throw after the release runs under `if (exc == NONE)`; the hand model copies it into both branches"),
    (FAIL_RELEASE_122,
     "            if (token__89 != NULL) {\n"
     "              var e_265_at122: Int\n"
     "              var u_265_at122: Bool\n"
     "              e_265_at122, u_265_at122 := KeyLock_unLock(k, lockType__85, token__89)\n"
     "              if (e_265_at122 != NONE) {\n"
     "                exc := e_265_at122\n"
     "              }\n"
     "            }\n"
     "            if (exc == NONE) {\n"
     "              exc := CE_SUP\n"
     "            }\n", 1,
     "as above, at the creation path's supplier call (122)"),
    ("      if (exc == NONE && token__89 != NULL) {\n",
     "      if (exc == NONE) {\n        if (token__89 != NULL) {\n", 1,
     "rule G1: the rest after the create tryLock is one block under `if (exc == NONE)`, the guard of the next part "
     "`exc == NONE && !ret` nested in it; the hand model merges the first if and puts the second guard beside it "
     "(its closing brace is the one the loop edit below removes, so the token stream needs no other change)"),
    ("        if (e_224_at95 != NONE) {\n          exc := CE_GET\n        } else {\n",
     "        if (e_224_at95 != NONE) {\n          exc := CE_GET\n        }\n        if (exc == NONE) {\n", 1,
     "rule G1: the rest of the recheck try runs under `if (exc == NONE)`, not in the else of the read's dispatch"),
    ("          var found_187: Ref := null    // `return cachedData` @187 is pending\n",
     "          var found_187: Bool := false\n", 1,
     "a pending return is a flag per function (ret_<function>) and its value goes to the function's out (here res) "
     "at once; the hand model keeps the value itself (null = no return). Meaning: found_187 != null is the flag"),
    ("          while (exc == NONE && found_187 == null && !break_192 && attempts__178 < maxAttempt)   // @181\n",
     "          while (exc == NONE && !found_187 && !break_192 && attempts__178 < maxAttempt)\n", 1,
     "the loop condition tests the flag"),
    ("            invariant exc == NONE && found_187 == null ==> tr.consec == consecutiveFailures__179\n",
     "            invariant exc == NONE && !found_187 ==> tr.consec == consecutiveFailures__179\n", 1,
     "the hole in the sidecar's E3 vocabulary: `returned` is the flag"),
    ("            invariant (found_187 != null) == tr.pollHit && (found_187 != null ==> tr.pollObj == found_187 && exc == NONE)\n",
     "            invariant found_187 == tr.pollHit && (found_187 ==> tr.pollObj == res && exc == NONE)\n", 1,
     "the hole in the sidecar's E3 vocabulary: `returned` and `result` (res)"),
    ("                  found_187 := cachedData__186                 // return cachedData @187\n",
     "                  res := cachedData__186\n                  found_187 := true\n", 1,
     "the return writes the out and sets the flag"),
    ("          if (exc == NONE) {\n            if (found_187 != null) {\n              res := found_187\n            } else {\n",
     "          if (exc == NONE && !found_187) {\n", 1,
     "after the loop the rest runs under `exc == NONE && !ret` (G1); res already holds the returned value "
     "(one closing brace fewer: see the create-path edit above)"),
    ("            if (e_202_at183 == INTR) {\n"
     "              exc := CE_GET                                    // interrupt restored, GET_CACHE_ERROR @203-206\n"
     "            } elseif (e_202_at183 != NONE) {\n"
     "              exc := e_202_at183\n"
     "            }\n",
     "            if (e_202_at183 != NONE) {\n"
     "              if (e_202_at183 == INTR) {\n"
     "                exc := CE_GET\n"
     "              } else {\n"
     "                exc := e_202_at183\n"
     "              }\n"
     "            }\n", 1,
     "a dispatch without a success branch is `if (e != NONE) { <catches>, else propagate }` (the setCacheFunction form "
     "of the hand model); the sleep dispatch was a flat chain"),
    ("              if (e_262_at197 != NONE) {\n"
     "                exc := CE_SUP                                  // token == null: nothing to release @196, @267\n"
     "              } else {\n",
     "              if (e_262_at197 != NONE) {\n                exc := CE_SUP\n              }\n              if (exc == NONE) {\n", 1,
     "rule G1: the rest after the fallback supplier runs under `if (exc == NONE)`, not in the else of its dispatch"),
]
# ---- on the fixed base only, after FIX ----
FIXED_ONLY = [
    ("        var token__66: Int := NULL\n        if (!onlyCreateCache__63) {\n",
     "        var token__66: Int := NULL\n        var ret_46: Bool := false\n        if (!onlyCreateCache__63) {\n", 1,
     "fixed-core.patch's `catch (e: Exception) { ...; return }` around the refresh tryLock is a pending return of "
     "updateReqShieldData (a flag); the hand model encodes it by `token == NULL` after a throw"),
    ("          if (e_66 != NONE && !IS_EXCEPTION(e_66)) {\n"
     "            exc := e_66                 // FIX: catch (e: Exception) skips the refresh; an Error propagates\n"
     "          }\n        }\n        if (exc == NONE) {\n",
     "          if (e_66 != NONE) {\n            if (IS_EXCEPTION(e_66)) {\n              ret_46 := true\n            } else {\n"
     "              exc := e_66\n            }\n          }\n        }\n        if (exc == NONE && !ret_46) {\n", 1,
     "as above: the catch sets the flag, and the refresh runs under `exc == NONE && !ret`"),
]

# ---- the normalizer change E3 needs (DESIGN-E 6.10 E3), on top of spike/vpr_normalize.py ----
# a) LOCAL also matches the tool's and the hand model's flag and temp names: <name>_<line>[_<col>][_at<line>...]
#    (e_224_13_at43, ret_50_20, brk_181_9, dropped_145_at69, found_187, d_197) and _at suffixes on <name>__<line>.
LOCAL = re.compile(r"^(?:[A-Za-z]\w*__\d+(?:_\d+)?(?:_at\d+)*|(?:cond|n|cur|r|v)_\d+_\d+[a-z]?"
                   r"|[A-Za-z][A-Za-z0-9]*_\d+(?:_\d+)?(?:_at\d+)*[a-z]?)$")


def unparen(toks):
    """b) Parentheses around one token that are no call's do not count: `(e) != NONE` is `e != NONE`; `f(x)` stays."""
    out, i = [], 0
    while i < len(toks):
        if toks[i] == "(" and i + 2 < len(toks) and toks[i + 2] == ")" and toks[i + 1] not in ("(", ")") and \
                not (out and (re.match(r"[A-Za-z_$]", out[-1]) or out[-1] in (")", "]"))):
            out.append(toks[i + 1])
            i += 3
            continue
        out.append(toks[i])
        i += 1
    return out


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def word(text, old, new):
    return re.sub(r"(?<![\w$])%s(?![\w$])" % re.escape(old), new, text)


def edit(text, edits, what):
    for old, new, count, _ in edits:
        found = text.count(old)
        if found != count:
            raise SystemExit("%s: an edit matched %d times, expected %d: %r" % (what, found, count, old[:80]))
        text = text.replace(old, new)
    return text


def expected(spike, base, generated):
    p2 = os.path.join(spike, "protocol2")
    text = open(os.path.join(p2, "target.vpr")).read()
    edits = STRUCTURAL
    if base == "fixed":
        fix = load(os.path.join(p2, "mutants.py"), "p2mutants")
        text = fix.base_text("fixed")
        edits = [(fix.fixify(o), fix.fixify(n), c, r) for o, n, c, r in STRUCTURAL] + FIXED_ONLY
    text = edit(text, edits, "structural")
    for start, end, pairs in SCOPED:
        a, b = text.index(start), text.index(end)
        part = text[a:b]
        for old, new in pairs:
            part = word(part, old, new)
        text = text[:a] + part + text[b:]
    pres = re.findall(r"define (TASK_\d+_PRE)\(", open(generated).read())
    if len(pres) != len(PRE_MACROS):
        raise SystemExit("the generated model has %d task precondition macros, expected %d" % (len(pres), len(PRE_MACROS)))
    for old, new in RENAMES + list(zip(PRE_MACROS, pres)):
        text = word(text, old, new)
    return text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--spike", required=True)
    ap.add_argument("--base", choices=("canonical", "fixed"), required=True)
    ap.add_argument("--write")
    ap.add_argument("generated")
    args = ap.parse_args()
    norm = load(os.path.join(args.spike, "vpr_normalize.py"), "vpr_normalize")
    norm.LOCAL = LOCAL
    want = expected(args.spike, args.base, args.generated)
    if args.write:
        with open(args.write, "w") as f:
            f.write(want)
    exp_path = args.write or os.path.join(os.path.dirname(os.path.abspath(args.generated)), "expected.%s.vpr" % args.base)
    if not args.write:
        with open(exp_path, "w") as f:
            f.write(want)
    a = norm.rename(unparen(norm.tokens(exp_path)))
    b = norm.rename(unparen(norm.tokens(args.generated)))
    if a == b:
        print("same program: %d tokens" % len(a))
        return 0
    sys.stdout.writelines(difflib.unified_diff([l + "\n" for l in norm.lines(a)], [l + "\n" for l in norm.lines(b)],
                                               exp_path, args.generated, n=2))
    return 1


if __name__ == "__main__":
    sys.exit(main())
