"""Build L3 mutants and assumption toggles from l3_client.vpr.

Same format as ../keylocallock/mutants.py: exact (old, new) replacements that must match once.
Usage: python3 mutants.py OUT_DIR   (writes OUT_DIR/<id>.vpr and prints "<id> <expect>" per line)
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

CALLER_WRITES = """
// A caller that writes the ReqShieldData it got back, while the background write may still read it.
method callerWritesResult(k: Key) returns (o: Int)
{
  var t: Int
  t := tryLock(k)
  var data: Ref
  o, data := createReqShieldData(k, t)
  if (o == OK) {
    data.value := 1
  }
}
"""

MUTANTS = {
    # executeCallable forgets to release in its catch block: the obligation leaks on a supplier exception.
    "L3M1_no_release_on_supplier_exception": ("fail", [
        ("    if (t != NULL) {\n      var r: Bool\n      r := unLock(k, t) // withContext(NonCancellable)",
         "    if (false) {\n      var r: Bool\n      r := unLock(k, t) // withContext(NonCancellable)"),
    ]),
    # handleLockForCacheCreation's finally releases even on a miss, while creation still owns the token:
    # the background write releases a second time.
    "L3M2_double_release": ("fail", [
        ("    if (!cacheCreationRequired) {\n      var r: Bool\n      r := unLock(k, t)",
         "    if (true) {\n      var r: Bool\n      r := unLock(k, t)"),
    ]),
    # F5 demonstration: a caller writes the shared ReqShieldData (data race with the background write).
    "L3M3_caller_writes_shared_data": ("fail", [
        ("// updateReqShieldData (lock acquired path).", CALLER_WRITES + "\n// updateReqShieldData (lock acquired path)."),
    ]),
    # F4: drop the assumption that a java.lang.Error is process-fatal.
    "A_F4_errors_not_fatal": ("fail", [
        ("define ERRORS_ARE_FATAL true", "define ERRORS_ARE_FATAL false"),
    ]),
    # F6: drop the assumption that dispatch() never throws.
    "A_F6_dispatch_may_throw": ("fail", [
        ("define DISPATCH_NEVER_THROWS true", "define DISPATCH_NEVER_THROWS false"),
    ]),
}


def build(out_dir):
    src = open(os.path.join(HERE, "l3_client.vpr")).read()
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
