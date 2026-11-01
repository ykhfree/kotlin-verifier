"""Reachability probes for the E5 model (as spike/protocol2/reach.py): `refute false` at chosen branches.
A probe whose branch is unreachable fails with "Refute holds"; a reachable one gives no error.
Usage: python3 -I reach_c.py BASE.vpr OUT.vpr   (prints the probe names)
"""
import sys

# (name, anchor line start (exact, with indentation), probe text inserted after the anchor line; {i} = anchor indent)
PROBES = [
    ("first read cancelled", "    if (e_203_13_at48 == CANCEL) {", "{i}  refute false"),
    ("first read failed, GET_CACHE_ERROR", "    } elseif (IS_EXCEPTION(e_203_13_at48)) {", "{i}  refute false"),
    ("refresh tryLock cancelled", "          EV_raised_CANCEL(tr, e_71_52)", "{i}if (e_71_52 == CANCEL) {{ refute false }}"),
    ("refresh tryLock threw RTE", "          EV_raised_CANCEL(tr, e_71_52)", "{i}if (e_71_52 == RTE) {{ refute false }}"),
    ("refresh skipped: no token", "            // return @73", "{i}refute false"),
    ("refresh task handed over", "                exhale TASK_75_PRE(", "{i}refute false"),
    ("refresh task without lock (ONLY_CREATE_CACHE)", "                exhale TASK_75_PRE(", "{i}if (token__71 == NULL) {{ refute false }}"),
    ("recheck cancelled, then released", "            e_112_25, released_112_25 := KeyLock_unLock(", "{i}if (e_203_13_at105 == CANCEL) {{ refute false }}"),
    ("recheck hit, then released", "            e_112_25, released_112_25 := KeyLock_unLock(", "{i}if (cachedData__105 != null) {{ refute false }}"),
    ("recheck cancelled, release threw RTE", "            e_112_25, released_112_25 := KeyLock_unLock(", "{i}if (e_203_13_at105 == CANCEL && e_112_25 == RTE) {{ refute false }}"),
    ("creation: ensureActive cancelled, released", "                e_247_21_at135b, released_247_21_at135b := KeyLock_unLock(", "{i}if (e_241_13_at135 == CANCEL) {{ refute false }}"),
    ("creation: supplier cancelled, released", "                  e_247_21_at135, released_247_21_at135 := KeyLock_unLock(", "{i}if (e_242_13_at135 == CANCEL) {{ refute false }}"),
    ("creation: supplier threw RTE, released", "                  e_247_21_at135, released_247_21_at135 := KeyLock_unLock(", "{i}if (e_242_13_at135 == RTE) {{ refute false }}"),
    ("creation task handed over", "                  exhale TASK_138_PRE(", "{i}refute false"),
    ("creation without lock (ONLY_UPDATE_CACHE)", "                  exhale TASK_138_PRE(", "{i}if (token__99 == NULL) {{ refute false }}"),
    ("poll hit", "                  ret_121_13 := true", "{i}refute false"),
    ("poll read cancelled", "                var caught_170_22: Int := exc", "{i}refute false"),
    ("poll read failed, counted", "                consecutiveFailures__163 := consecutiveFailures__163 + 1", "{i}refute false"),
    ("poll delay cancelled", "                  EV_raised_CANCEL(tr, e_181_13)", "{i}if (e_181_13 == CANCEL) {{ refute false }}"),
    ("poll gave up: 3 failures", "                  brk_165_9 := true  // break", "{i}refute false"),
    ("fallback after all attempts", "              // executeCallable @186:35 (inlined)", "{i}if (!brk_165_9 && maxAttempt > 0) {{ refute false }}"),
    ("fallback supplier ok", "                  arg_186_35 := r_242_13_at186  // return", "{i}refute false"),
    ("fallback supplier cancelled", "                  if (e_242_13_at186 == CANCEL) {", "{i}  refute false"),
    ("task_75: ensureActive cancelled, released", "      e_247_21_at79b, released_247_21_at79b := KeyLock_unLock(", "{i}if (e_241_13_at79 == CANCEL) {{ refute false }}"),
    ("task_75: supplier cancelled, released", "        e_247_21_at79, released_247_21_at79 := KeyLock_unLock(", "{i}if (e_242_13_at79 == CANCEL) {{ refute false }}"),
    ("task_75: write ensureActive cancelled, released in finally", "      e_228_21_at82, released_228_21_at82 := KeyLock_unLock(", "{i}if (e_218_13_at82 == CANCEL) {{ refute false }}"),
    ("task_75: write cancelled", "        if (e_219_13_at82 == CANCEL) {", "{i}  refute false"),
    ("task_138: ensureActive cancelled, released in finally", "    e_228_21_at140, released_228_21_at140 := KeyLock_unLock(", "{i}if (e_218_13_at140 == CANCEL) {{ refute false }}"),
    ("task_138: write failed, SET_CACHE_ERROR", "      } elseif (IS_EXCEPTION(e_219_13_at140)) {", "{i}  refute false"),
]


def main(src_path, out_path):
    text = open(src_path).read()
    lines = text.split("\n")
    inserts = {}
    for name, anchor, probe in PROBES:
        hits = [i for i, l in enumerate(lines) if l.startswith(anchor)]
        if len(hits) != 1:
            sys.exit("probe %r: anchor matched %d lines" % (name, len(hits)))
        i = hits[0]
        indent = " " * (len(lines[i]) - len(lines[i].lstrip(" ")))
        inserts.setdefault(i, []).append(probe.format(i=indent) + "  // reach: " + name)
        print(name)
    out = []
    for i, l in enumerate(lines):
        out.append(l)
        out.extend(inserts.get(i, []))
    open(out_path, "w").write("\n".join(out))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
