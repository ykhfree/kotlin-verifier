"""Branch reachability check for target.vpr (DESIGN-E 6.2): is the model vacuous on a path it should cover?

Inserts `refute false` after each anchor line below. A reachable branch makes its refute succeed; an unreachable one
fails with "Refute holds in all cases". So the generated file verifies with no error iff every probed branch is reachable.
Usage: python3 -I reach.py OUT.vpr
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

# (probe id, anchor line, optional guard): the anchor must occur exactly once in target.vpr.
PROBES = [
    ("first_read_throws", "    exc := CE_GET                       // runCatching catches every Throwable; getOrElse throws GET_CACHE_ERROR @226", None),
    ("hit_no_refresh", "        res := currentReqShieldData__43 // return it @48", "!upd__45"),
    ("refresh_spawned_with_lock", "                exhale TASK69_PRE(k, mode, PHASE(tr), lockType__62, token__66)   // spawn task_69", "token__66 != NULL"),
    ("refresh_spawned_without_lock_OCC", "                exhale TASK69_PRE(k, mode, PHASE(tr), lockType__62, token__66)   // spawn task_69", "mode == OCC"),
    ("refresh_lock_busy", "            exc := e_66                 // propagates as is\n          }\n        }", "exc == NONE && !onlyCreateCache__63 && token__66 == NULL"),
    ("refresh_rejected_release", "                e_152_at69, u_152_at69 := KeyLock_unLock(k, lockType__62, token__66)   // @152", None),
    ("recheck_hit", "            ret_96 := true", None),
    ("recheck_throws", "        EV_READ(tr, k, e_224_at95, cachedData__95)", "e_224_at95 != NONE"),
    ("create_supplier_throws_release", "              e_265_at122, u_265_at122 := KeyLock_unLock(k, lockType__85, token__89)   // @265", None),
    ("create_spawned_with_lock", "                exhale TASK125_PRE(k, mode, PHASE(tr), lockType__85, token__89)   // spawn task_125", "token__89 != NULL"),
    ("create_spawned_OUC_no_lock", "                exhale TASK125_PRE(k, mode, PHASE(tr), lockType__85, token__89)   // spawn task_125", "mode == OUC"),
    ("create_rejected_release", "                e_152_at125, u_152_at125 := KeyLock_unLock(k, lockType__85, token__89)   // @152", None),
    ("poll_hit", "                  found_187 := cachedData__186                 // return cachedData @187", None),
    ("poll_break", "                  break_192 := true                            // @192", None),
    ("poll_interrupted", "              exc := CE_GET                                    // interrupt restored, GET_CACHE_ERROR @203-206", None),
    ("fallback_after_budget", "                res := d_197", "attempts__178 == maxAttempt && maxAttempt > 0"),
    ("fallback_after_failures", "                res := d_197", "break_192"),
    ("fallback_supplier_throws", "                exc := CE_SUP                                  // token == null: nothing to release @196, @267", None),
    ("task69_supplier_throws_release", "      e_265_at72, u_265_at72 := KeyLock_unLock(k, lt, t)   // @265", None),
    ("task69_write_then_release", "      e_243_at75, u_243_at75 := KeyLock_unLock(k, lt, t)   // @243", None),
    ("task125_write_throws_release", "    e_243_at126, u_243_at126 := KeyLock_unLock(k, lt, t)   // @243", "e_237_at126 != NONE"),
]


def build(out_path):
    text = open(os.path.join(HERE, "target.vpr")).read()
    for pid, anchor, guard in PROBES:
        count = text.count(anchor)
        if count != 1:
            sys.exit("%s: anchor matched %d times: %r" % (pid, count, anchor[:70]))
        last = anchor.split("\n")[-1]
        indent = last[: len(last) - len(last.lstrip())]
        if guard:
            probe = "\n%sif (%s) { refute false } // reach: %s" % (indent, guard, pid)
        else:
            probe = "\n%srefute false // reach: %s" % (indent, pid)
        text = text.replace(anchor, anchor + probe)
    with open(out_path, "w") as f:
        f.write(text)
    print(len(PROBES), "probes")


if __name__ == "__main__":
    build(sys.argv[1])
