# T-haiku-1 under DESIGN-D options (a) and (c): needs A1 (Semaphore(1).apply { tryAcquire() }) and A5 (the
# member helper isExpired, inlined) besides Part B. Usage: python3 -I gen_h1.py OUTDIR
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_kll as g  # noqa: E402


def body_haiku1(expired):
    return """  var grantedToken__42: Int := NULL  // cell @42:9
  if (existing__43 == null) {
    grantedToken__42 := token__41
    var n_48_63: Int  // jdk.ConcurrentHashMap.mappingCount @48:63: arbitrary
    if (rej) {
      S.m := S.m[k := null]  // exit @49:25: remove
    } else {
      var li__51: Ref := new(permits, isHeld, token, expiresAt)  // LockInfo(...) @51:25
      S.created := S.created union Set(li__51)
      li__51.permits := 1  // Semaphore(1) @52:41 (A1)
      var cond_52_54: Bool := li__51.permits > 0  // apply { tryAcquire() } (A1)
      if (cond_52_54) { li__51.permits := li__51.permits - 1 }
      li__51.isHeld := true
      li__51.token := token__41
      li__51.expiresAt := now + timeout
      S.m := S.m[k := li__51]  // exit @51:25: put
    }
  } else {
    if (%s) {  // existing.isExpired(now) @59:17 (A5, inlined)
      existing__43.token := token__41
      existing__43.expiresAt := now + timeout
      grantedToken__42 := token__41
      // exit @64:17: mapping unchanged
    } else {
      // exit @67:25: mapping unchanged
    }
  }
  res := grantedToken__42  // result @70:16
""" % expired


def unlock_h1(option):
    s = "method unLock(S: Ref, k: Key, t: Int) returns (res: Bool)\n" + g.requires(option)
    s += "  requires t != NULL\n" + g.inv(option) + g.UNLOCK_SPEC.replace("{pendframe}", g.pend_frame(option))
    s += """{
  var existing__80: Ref := has(S, k) ? S.m[k] : null
  var pre_tok: Int := existing__80 != null ? existing__80.token : NULL
  var released__79: Bool := false
  if (existing__80 == null || existing__80.token != t) {
    // exit @82:17: mapping unchanged
  } else {
    existing__80.permits := existing__80.permits + 1  // jdk.Semaphore.release @84:17 (A1)
    released__79 := true
    S.m := S.m[k := null]  // exit @87:17: remove
  }
  res := released__79
"""
    s += g.END_COMMIT + "}\n\n"
    return s


def model(option, fixed):
    out = g.header(option) + g.init_method(option)
    cmp = "now > existing__43.expiresAt" if fixed else "now >= existing__43.expiresAt"
    swept = "now > existing__s.expiresAt" if fixed else "now >= existing__s.expiresAt"
    if option == "a":
        out += g.reserve_method("token__41", "41:30")
    out += g.try_method(option, "existing__43", "token__41", "41:30", body_haiku1(cmp))
    out += unlock_h1(option) + g.sweep_method(option, swept) + g.LEMMA + g.probe(option)
    return out


outdir = sys.argv[1]
for option in ("a", "c"):
    for fixed in (False, True):
        with open(os.path.join(outdir, "%s_h1%s.vpr" % (option, "_fixed" if fixed else "")), "w") as f:
            f.write(model(option, fixed))
print("written to", outdir)
