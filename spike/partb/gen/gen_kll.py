# Generates hand-lowered Viper models of AI KeyLocalLock implementations under the DESIGN-D options.
# The text follows the kv emitter output for the reference (out/ref.vpr): same header, macros, contract order.
# Usage: python3 -I gen_kll.py HEADER.vpr OUTDIR
import os
import sys

HEADER = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "header_ref.vpr")).read()

# ---- Option (a): tool ghost gPend, MI_PEND, reserve step method ----
A_FIELD = "field gPend: Bool  // tool ghost (DESIGN-D a): reserved by a counter step outside an action, not consumed yet\n"
A_PEND = """
// Tool fact PEND (DESIGN-D a): a reserved token is not issued, and the counter has passed it.
define MI_PEND(S) (
  forall qt: Int :: { tok(qt).gPend } tok(qt).gPend ==> !tok(qt).gIssued && 0 <= qt && qt <= S.seq
)
"""


def header(option, extra_fields=""):
    h = HEADER
    if option == "a":
        h = h.replace("field gLease: Int  // frozen\n", "field gLease: Int  // frozen\n" + A_FIELD)
        h = h.replace("acc(tok(qt).gLease)\n", "acc(tok(qt).gLease) && acc(tok(qt).gPend)\n")
        h = h.replace("// Tool fact WITNESS", A_PEND + "\n// Tool fact WITNESS")
    if extra_fields:
        h = h.replace("field expiresAt: Int  // expiresAt\n", "field expiresAt: Int  // expiresAt\n" + extra_fields)
        h = h.replace("acc(qr.token) && acc(qr.expiresAt))", "acc(qr.token) && acc(qr.expiresAt) && acc(qr.epoch))")
    assert option != "a" or ("gPend" in h and "MI_PEND" in h)
    return h


def requires(option):
    r = "  requires MI(S)\n  requires MI_WITNESS(S)\n"
    if option == "a":
        r += "  requires MI_PEND(S)\n"
    return r


INV = """  ensures MI_PERMS(S)
  ensures forall qk: Key :: { S.m[qk] } qk != k ==> (has(S, qk) == old(has(S, qk))) && (has(S, qk) ==> S.m[qk] == old(S.m[qk]))
  ensures MI_OWN(S)
  ensures MI_WITNESS(S)
{pend}  ensures P2(S)
  ensures entry_lease(S)
  ensures entry_held(S)
  ensures MI_INV_1(S)
  ensures MI_INV_2(S)
  ensures MI_INV_3(S)
  ensures MI_INV_4(S)
  ensures MI_INV_5(S)
  ensures HIST
"""


def inv(option):
    return INV.replace("{pend}", "  ensures MI_PEND(S)\n" if option == "a" else "")


TRY_FACTS = """  ensures forall qt: Int :: { tok(qt).gHeld } qt != NULL && old(carried(S, k, qt)) && !carried(S, k, qt) && qt != res ==> !tok(qt).gHeld
  ensures forall qt: Int :: { tok(qt).gHeld } old(tok(qt).gHeld) && !old(carried(S, k, qt)) ==> tok(qt).gHeld
  ensures res != NULL ==> !old(tok(res).gIssued) && tok(res).gIssued && tok(res).gKey == k && tok(res).gBacked == carried(S, k, res) && tok(res).gHeld && tok(res).gLease == now + timeout
  ensures forall qt: Int :: { tok(qt).gIssued } !old(tok(qt).gIssued) && qt != res ==> !tok(qt).gIssued
"""

TRY_SPEC = """  // ends_only_if P3a (sidecar, reviewed)
  ensures forall qt: Int :: { tok(qt).gHeld }
    old(tok(qt).gIssued) && old(tok(qt).gBacked) && old(tok(qt).gHeld) && !tok(qt).gHeld ==>
      (now > old(tok(qt).gLease))
  // unbacked_only_if P3b (sidecar, reviewed)
  ensures res != NULL && !tok(res).gBacked ==> (!old(has(S, k)) && rej && limit != 0)
  ensures res != NULL ==> !old(tok(res).gIssued) && tok(res).gIssued &&
      tok(res).gKey == k && tok(res).gHeld && tok(res).gLease == now + timeout
  ensures res != NULL && tok(res).gBacked ==> has(S, k) && S.m[k].token == res
  ensures !old(has(S, k)) && !rej ==> res != NULL && tok(res).gBacked
  ensures old(has(S, k)) && old(S.m[k].isHeld) && now <= old(S.m[k].expiresAt) ==> res == NULL
  ensures !old(has(S, k)) && rej ==> res != NULL && !tok(res).gBacked && !has(S, k)
  ensures old(has(S, k)) && now > old(S.m[k].expiresAt) ==> res != NULL && tok(res).gBacked
  ensures res == NULL ==> old(has(S, k)) && has(S, k) &&
      S.m[k].token == old(S.m[k].token) && S.m[k].expiresAt == old(S.m[k].expiresAt)
"""

# A property that observes token order (not in the signed sidecar): each new token is greater than every earlier one.
P_MONO = "  ensures res != NULL ==> (forall qt: Int :: { tok(qt).gIssued } old(tok(qt).gIssued) ==> qt < res)  // P_mono (hypothetical)\n"

UNLOCK_SPEC = """  ensures forall qt: Int :: { tok(qt).gHeld } qt != NULL && old(carried(S, k, qt)) && !carried(S, k, qt) ==> !tok(qt).gHeld
  ensures forall qt: Int :: { tok(qt).gHeld } old(tok(qt).gHeld) && !old(carried(S, k, qt)) ==> tok(qt).gHeld
  ensures forall qt: Int :: { tok(qt).gIssued } !old(tok(qt).gIssued) ==> !tok(qt).gIssued
{pendframe}  // ends_only_if P3a (sidecar, reviewed)
  ensures forall qt: Int :: { tok(qt).gHeld }
    old(tok(qt).gIssued) && old(tok(qt).gBacked) && old(tok(qt).gHeld) && !tok(qt).gHeld ==>
      (qt == t && res)
  ensures res ==> old(has(S, k)) && old(S.m[k].token) == t && old(tok(t).gIssued) &&
      old(tok(t).gKey) == k && old(tok(t).gBacked) && old(tok(t).gHeld) && !tok(t).gHeld && !has(S, k)
  ensures old(has(S, k)) && old(S.m[k].token) == t ==> res
  ensures !old(has(S, k)) ==> !has(S, k)
"""

SWEEP_SPEC = """  ensures forall qt: Int :: { tok(qt).gHeld } qt != NULL && old(carried(S, k, qt)) && !carried(S, k, qt) ==> !tok(qt).gHeld
  ensures forall qt: Int :: { tok(qt).gHeld } old(tok(qt).gHeld) && !old(carried(S, k, qt)) ==> tok(qt).gHeld
  ensures forall qt: Int :: { tok(qt).gIssued } !old(tok(qt).gIssued) ==> !tok(qt).gIssued
{pendframe}  // ends_only_if P3a (sidecar, reviewed)
  ensures forall qt: Int :: { tok(qt).gHeld }
    old(tok(qt).gIssued) && old(tok(qt).gBacked) && old(tok(qt).gHeld) && !tok(qt).gHeld ==>
      (now > old(tok(qt).gLease))
  ensures !old(has(S, k)) ==> !has(S, k)
"""

PEND_SAME = "  ensures forall qt: Int :: { tok(qt).gPend } tok(qt).gPend == old(tok(qt).gPend)  // tool fact PEND: no reservation made or consumed\n"


def pend_frame(option):
    return PEND_SAME if option == "a" else ""


END_COMMIT = """  // ---- commit point: effect-bound ghost rules ----
  if (pre_tok != NULL && !carried(S, k, pre_tok)) {
    tok(pre_tok).gHeld := false
  }
"""


def issue_commit(option, own):
    s = ""
    if option == "a" and own:
        s += "  tok(%s).gPend := false  // consume the reservation (DESIGN-D a)\n" % own
    s += "  if (res != NULL) {\n    assert !tok(res).gIssued\n"
    if option == "a":
        s += "    assert !tok(res).gPend  // DESIGN-D a: no token another thread reserved\n"
    s += """    tok(res).gIssued := true
    tok(res).gKey := k
    tok(res).gBacked := carried(S, k, res)
    tok(res).gHeld := true
    tok(res).gLease := now + timeout
  }
"""
    return s


def reserve_method(tokname, pos):
    return """// Step reserve_tryLock: tokenSequence.incrementAndGet() @%s outside the action (DESIGN-D a)
method reserve_tryLock(S: Ref) returns (%s: Int)
  requires MI(S)
  requires MI_WITNESS(S)
  requires MI_PEND(S)
  ensures MI_PERMS(S)
  ensures forall qk: Key :: { S.m[qk] } (has(S, qk) == old(has(S, qk))) && (has(S, qk) ==> S.m[qk] == old(S.m[qk]))
  ensures MI_OWN(S)
  ensures MI_WITNESS(S)
  ensures MI_PEND(S)
  ensures P2(S)
  ensures entry_lease(S)
  ensures entry_held(S)
  ensures MI_INV_1(S)
  ensures MI_INV_2(S)
  ensures MI_INV_3(S)
  ensures MI_INV_4(S)
  ensures MI_INV_5(S)
  ensures HIST
  ensures forall qt: Int :: { tok(qt).gHeld } tok(qt).gHeld == old(tok(qt).gHeld)  // tool fact: the step ends nothing
  ensures forall qt: Int :: { tok(qt).gIssued } tok(qt).gIssued == old(tok(qt).gIssued)  // tool fact: the step issues nothing
  ensures forall qt: Int :: { tok(qt).gPend } tok(qt).gPend == (old(tok(qt).gPend) || qt == %s)  // tool fact PEND
{
  S.seq := S.seq + 1  // jdk.AtomicLong.incrementAndGet
  var n_r: Int := S.seq
  assert 0 <= n_r  // token template: n is not the null sentinel
  %s := n_r
  assert !tok(%s).gIssued  // reserve: the token was never issued
  assert !tok(%s).gPend  // reserve: no thread holds it
  tok(%s).gPend := true
}

""" % (pos, tokname, tokname, tokname, tokname, tokname, tokname)


def init_method(option, extra_init=""):
    pend = " && !tok(qt).gPend" if option == "a" else ""
    ens = "  ensures MI_PEND(S)\n" if option == "a" else ""
    return """method init() returns (S: Ref)
  ensures MI(S)
  ensures MI_WITNESS(S)
%s  ensures S.m == Map[Key, Ref]()
  ensures S.seq == 0
{
  S := new(m, seq, created)
  S.m := Map[Key, Ref]()
  S.seq := 0
  S.created := Set[Ref]()
  inhale GHOST_PERMS && (forall qt: Int :: { tok(qt) } !tok(qt).gIssued%s)
}

""" % (ens, pend)


LEMMA = """method lemma_P3c(S: Ref)
  requires MI(S)
  ensures MI(S)
  ensures forall t1: Int, t2: Int :: { tok(t1).gHeld, tok(t2).gHeld }
    tok(t1).gIssued && tok(t2).gIssued && tok(t1).gBacked && tok(t1).gHeld &&
    tok(t2).gBacked && tok(t2).gHeld && tok(t1).gKey == tok(t2).gKey ==> t1 == t2
{}

"""


def probe(option):
    if option == "a":
        c1 = "  var r1: Int\n  r1 := reserve_tryLock(S)\n  var t1: Int\n  t1 := tryLock(S, k, now, timeout, false, 0, r1)\n"
        c2 = "  var r2: Int\n  r2 := reserve_tryLock(S)\n  var t2: Int\n  t2 := tryLock(S, k, now, timeout, false, 0, r2)\n"
    else:
        c1 = "  var t1: Int\n  t1 := tryLock(S, k, now, timeout, false, 0)\n"
        c2 = "  var t2: Int\n  t2 := tryLock(S, k, now, timeout, false, 0)\n"
    return """method probe(k: Key, now: Int, timeout: Int)
  requires timeout > 0
{
  var S: Ref
  S := init()
%s  assert t1 != NULL && tok(t1).gBacked
%s  assert t2 == NULL
  var ok: Bool
  ok := unLock(S, k, t1)
  assert ok
  var ok2: Bool
  ok2 := unLock(S, k, t1)
  assert !ok2
}

""" % (c1, c2)


# ---- tryLock bodies (hand-lowered) ----

def body_haiku2(live):
    """T-haiku-2 tryLock 47:9. live: the Viper condition for `existing.expiresAt > now` (original) or a fixed one."""
    return """  var grantedToken__46: Int := NULL  // cell @46:9
  if (existing__47 != null && %s) {
    // exit @49:62: mapping unchanged
  } else {
    var n_50_63: Int  // jdk.ConcurrentHashMap.mappingCount @50:63: arbitrary
    if (existing__47 == null && rej) {
      grantedToken__46 := newToken__45
      S.m := S.m[k := null]  // exit @53:21: remove
    } else {
      grantedToken__46 := newToken__45
      var lockInfo__58: Ref := new(permits, isHeld, token, expiresAt)  // LockInfo(...) @58:21
      S.created := S.created union Set(lockInfo__58)
      lockInfo__58.permits := 0
      lockInfo__58.isHeld := true
      lockInfo__58.token := newToken__45
      lockInfo__58.expiresAt := now + timeout
      S.m := S.m[k := lockInfo__58]  // exit @58:21: put
    }
  }
  res := grantedToken__46  // result @68:16
""" % live


def body_haiku4(live):
    """T-haiku-4 tryLock 39:9, with the result `if (granted.get()) token else null` (60:16)."""
    return """  var granted__38: Bool := false  // cell @38:9
  if (existing__39 != null && existing__39.isHeld && %s) {
    // exit @42:96: mapping unchanged
  } else {
    var n_44_63: Int  // jdk.ConcurrentHashMap.mappingCount @44:63: arbitrary
    if (existing__39 == null && rej) {
      granted__38 := true
      S.m := S.m[k := null]  // exit @46:21: remove
    } else {
      granted__38 := true
      var li__51: Ref := new(permits, isHeld, token, expiresAt)  // LockInfo(...) @51:21
      S.created := S.created union Set(li__51)
      li__51.permits := 1  // Semaphore(1) @52:37 (A1)
      var cond_52_55: Bool := li__51.permits > 0  // apply { tryAcquire() } @52:55 (A1)
      if (cond_52_55) { li__51.permits := li__51.permits - 1 }
      li__51.isHeld := true
      li__51.token := token__37
      li__51.expiresAt := now + timeout
      S.m := S.m[k := li__51]  // exit @51:21: put
    }
  }
  res := granted__38 ? token__37 : NULL  // result @60:16 (W10 relaxed: a cell and the reserved token)
""" % live


def try_method(option, entry, tokname, tokpos, body, mono=False, extra_req=""):
    params = "S: Ref, k: Key, now: Int, timeout: Int, rej: Bool, limit: Int"
    if option == "a":
        params += ", %s: Int" % tokname
    s = "method tryLock(%s) returns (res: Int)\n" % params
    s += requires(option)
    s += "  requires rej ==> limit != 0\n"
    if option == "a":
        s += "  requires tok(%s).gPend  // the thread holds the reservation of step reserve_tryLock (DESIGN-D a)\n" % tokname
    s += extra_req
    s += inv(option) + TRY_FACTS
    if option == "a":
        s += "  ensures forall qt: Int :: { tok(qt).gPend } tok(qt).gPend == (old(tok(qt).gPend) && qt != %s)  // tool fact PEND: only its own reservation is consumed\n" % tokname
    s += TRY_SPEC
    if mono:
        s += P_MONO
    s += "{\n"
    s += "  var %s: Ref := has(S, k) ? S.m[k] : null\n" % entry
    s += "  var pre_tok: Int := %s != null ? %s.token : NULL\n" % (entry, entry)
    if option == "c":
        s += "  S.seq := S.seq + 1  // tokenSequence.incrementAndGet() @%s moved into the action (DESIGN-D c)\n" % tokpos
        s += "  var n_moved: Int := S.seq\n  assert 0 <= n_moved  // token template\n"
        s += "  var %s: Int := n_moved\n" % tokname
    s += body
    s += END_COMMIT + issue_commit(option, tokname if option == "a" else None)
    s += "}\n\n"
    return s


def unlock_method(option, entry, cell):
    s = "method unLock(S: Ref, k: Key, t: Int) returns (res: Bool)\n" + requires(option)
    s += "  requires t != NULL\n" + inv(option) + UNLOCK_SPEC.replace("{pendframe}", pend_frame(option))
    s += """{
  var %(e)s: Ref := has(S, k) ? S.m[k] : null
  var pre_tok: Int := %(e)s != null ? %(e)s.token : NULL
  var %(c)s: Bool := false
  if (%(e)s != null && %(e)s.token == t) {
    %(c)s := true
    S.m := S.m[k := null]
  } else {
    // mapping unchanged
  }
  res := %(c)s
""" % {"e": entry, "c": cell}
    s += END_COMMIT + "}\n\n"
    return s


def sweep_method(option, expired):
    s = "method sweep(S: Ref, k: Key, now: Int)\n" + requires(option) + inv(option)
    s += SWEEP_SPEC.replace("{pendframe}", pend_frame(option))
    s += """{
  var existing__s: Ref := has(S, k) ? S.m[k] : null
  var pre_tok: Int := existing__s != null ? existing__s.token : NULL
  if (existing__s != null && %s) {
    S.m := S.m[k := null]
  } else {
    // mapping unchanged
  }
""" % expired
    s += END_COMMIT + "}\n\n"
    return s


def model(option, impl, fixed, mono=False):
    out = header(option)
    out += init_method(option)
    if impl == "h2":
        live = "existing__47.expiresAt >= now" if fixed else "existing__47.expiresAt > now"
        expired = "existing__s.expiresAt < now" if fixed else "existing__s.expiresAt <= now"
        if option == "a":
            out += reserve_method("newToken__45", "45:33")
        out += try_method(option, "existing__47", "newToken__45", "45:33", body_haiku2(live), mono)
        out += unlock_method(option, "existing__77", "released__76")
    else:
        live = "now <= existing__39.expiresAt" if fixed else "now < existing__39.expiresAt"
        expired = "existing__s.expiresAt < now" if fixed else "existing__s.expiresAt <= now"
        if option == "a":
            out += reserve_method("token__37", "37:30")
        out += try_method(option, "existing__39", "token__37", "37:30", body_haiku4(live), mono)
        out += unlock_method(option, "existing__69", "released__68")
    out += sweep_method(option, expired)
    out += LEMMA + probe(option)
    return out


if __name__ == "__main__":
  outdir = sys.argv[1]
  os.makedirs(outdir, exist_ok=True)
  for option in ("a", "c"):
      for impl in ("h2", "h4"):
          for fixed in (False, True):
              name = "%s_%s%s.vpr" % (option, impl, "_fixed" if fixed else "")
              with open(os.path.join(outdir, name), "w") as f:
                  f.write(model(option, impl, fixed))
      with open(os.path.join(outdir, "%s_h2_fixed_mono.vpr" % option), "w") as f:
          f.write(model(option, "h2", True, mono=True))
  print("written to", outdir)
