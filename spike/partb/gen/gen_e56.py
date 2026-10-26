# E5 and E6 of DESIGN-D: hand-lowered Viper models that test the soundness of options (a), (b) and (c).
# E5 "fence": a hypothetical KeyLocalLock whose expired takeover compares the reserved counter value with the
#   holder's (a fencing epoch). The real code refuses an expired lock when its reservation is older than the
#   holder's, so expired_takeover is false for it.
# E6 "steal": a minimal module with two issuing operations. Y reserves outside its action; X issues the counter's
#   current value. The real code can issue one token twice (Y reserves 5, X issues 5, Y issues 5).
# Usage: python3 -I gen_e56.py OUTDIR
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_kll as g  # noqa: E402

EPOCH_FIELD = "field epoch: Int  // epoch (hypothetical fencing field: the holder's reserved counter value)\n"
EPOCH_INV = """// Each entry's epoch is its token (the counter value that the holder reserved).
define MI_INV_6(S) ((forall qk: Key :: { S.m[qk] } has(S, qk) ==> S.m[qk].epoch == S.m[qk].token))
// @HOLE-END MI_INV"""


def fence_header(option):
    h = g.header("a" if option == "a" else "c", extra_fields=EPOCH_FIELD)
    h = h.replace("// @HOLE-END MI_INV", EPOCH_INV)
    if option == "b":
        # (b): the outside increment has no effect on the modeled counter, so the hole parts about S.seq go (a
        # hole only has to be inductive; AI-CODE.md 6.3 lets a hole writer rewrite it).
        h = h.replace("define MI_INV(S) (MI_INV_1(S) && MI_INV_2(S) && MI_INV_3(S) && MI_INV_4(S) && MI_INV_5(S))",
                      "define MI_INV(S) (MI_INV_1(S) && MI_INV_4(S) && MI_INV_5(S) && MI_INV_6(S))")
    else:
        h = h.replace("define MI_INV(S) (MI_INV_1(S) && MI_INV_2(S) && MI_INV_3(S) && MI_INV_4(S) && MI_INV_5(S))",
                      "define MI_INV(S) (MI_INV_1(S) && MI_INV_2(S) && MI_INV_3(S) && MI_INV_4(S) && MI_INV_5(S) && MI_INV_6(S))")
    return h


def fence_inv(option):
    s = g.inv("a" if option == "a" else "c")
    if option == "b":
        s = s.replace("  ensures MI_INV_2(S)\n  ensures MI_INV_3(S)\n", "")
    return s.replace("  ensures MI_INV_5(S)\n", "  ensures MI_INV_5(S)\n  ensures MI_INV_6(S)\n")


FENCE_BODY = """  var granted__f: Int := NULL
  if (existing__f == null) {
    var n_size: Int  // mappingCount: arbitrary
    if (rej) {
      granted__f := n__r
      S.m := S.m[k := null]
    } else {
      granted__f := n__r
      var li: Ref := new(permits, isHeld, token, expiresAt, epoch)
      S.created := S.created union Set(li)
      li.permits := 0
      li.isHeld := true
      li.token := n__r
      li.expiresAt := now + timeout
      li.epoch := n__r
      S.m := S.m[k := li]
    }
  } else {
    if (now <= existing__f.expiresAt) {
      // live: refuse
    } else {
      if (n__r > existing__f.epoch) {
        granted__f := n__r
        var li2: Ref := new(permits, isHeld, token, expiresAt, epoch)
        S.created := S.created union Set(li2)
        li2.permits := 0
        li2.isHeld := true
        li2.token := n__r
        li2.expiresAt := now + timeout
        li2.epoch := n__r
        S.m := S.m[k := li2]
      } else {
        // expired, but the holder reserved later: refuse (fencing)
      }
    }
  }
  res := granted__f
"""


def fence_try(option):
    params = "S: Ref, k: Key, now: Int, timeout: Int, rej: Bool, limit: Int"
    if option in ("a", "b"):
        params += ", n__r: Int"
    s = "method tryLock(%s) returns (res: Int)\n" % params
    s += g.requires("a" if option == "a" else "c") + "  requires rej ==> limit != 0\n"
    if option == "a":
        s += "  requires tok(n__r).gPend  // reservation (DESIGN-D a)\n"
    if option == "b":
        s += "  requires 0 <= n__r && !tok(n__r).gIssued && (forall qt: Int :: { tok(qt).gIssued } tok(qt).gIssued ==> qt < n__r)  // (b): trusted fresh value, greater than every earlier value\n"
    s += fence_inv(option) + g.TRY_FACTS
    if option == "a":
        s += "  ensures forall qt: Int :: { tok(qt).gPend } tok(qt).gPend == (old(tok(qt).gPend) && qt != n__r)\n"
    s += g.TRY_SPEC + "{\n"
    s += "  var existing__f: Ref := has(S, k) ? S.m[k] : null\n"
    s += "  var pre_tok: Int := existing__f != null ? existing__f.token : NULL\n"
    if option == "c":
        s += "  S.seq := S.seq + 1  // moved into the action (DESIGN-D c, C3 ignored)\n  var n__r: Int := S.seq\n  assert 0 <= n__r\n"
    s += FENCE_BODY + g.END_COMMIT + g.issue_commit("a" if option == "a" else "c", "n__r" if option == "a" else None) + "}\n\n"
    return s


def fence(option):
    out = fence_header(option)
    if option == "b":
        # The (b) contract does not read the counter, so init needs no counter fact beyond the emitted one.
        pass
    out += g.init_method("a" if option == "a" else "c")
    if option == "a":
        out += g.reserve_method("n__r", "fence").replace("  ensures MI_INV_5(S)\n", "  ensures MI_INV_5(S)\n  ensures MI_INV_6(S)\n")
    out += fence_try(option)
    return out


# ---- E6 "steal": a minimal module ----

def steal(option):
    pend = option == "a"
    perms = "acc(tok(qt).gIssued)" + (" && acc(tok(qt).gPend)" if pend else "")
    s = """// E6 (DESIGN-D): minimal module. Counter seq; tokens are counter values; one ghost record per token.
domain TokRec {
  function tok(t: Int): Ref
  function tokOf(r: Ref): Int
  axiom tok_injective { forall t: Int :: { tok(t) } tokOf(tok(t)) == t }
}
define NULL (-1)
field seq: Int
field gIssued: Bool
%s
define GHOST_PERMS (forall qt: Int :: { tok(qt) } %s)
define MI_PERMS(S) (acc(S.seq) && GHOST_PERMS)
// Hole (an inductive invariant a hole writer could give):
define MI_INV(S) (0 <= S.seq && (forall qt: Int :: { tok(qt).gIssued } tok(qt).gIssued ==> 0 <= qt && qt <= S.seq) && !tok(S.seq).gIssued)
define MI(S) (MI_PERMS(S) && MI_INV(S))
define MI_PEND(S) (forall qt: Int :: { tok(qt).gPend } tok(qt).gPend ==> !tok(qt).gIssued && 0 <= qt && qt <= S.seq)
define HIST (forall qt: Int :: { tok(qt).gIssued } old(tok(qt).gIssued) ==> tok(qt).gIssued)

method init() returns (S: Ref)
  ensures MI(S)
%s{
  S := new(seq)
  S.seq := 0
  inhale GHOST_PERMS && (forall qt: Int :: { tok(qt) } !tok(qt).gIssued%s)
}

""" % ("field gPend: Bool" if pend else "", perms, "  ensures MI_PEND(S)\n" if pend else "", " && !tok(qt).gPend" if pend else "")
    req = "  requires MI(S)\n" + ("  requires MI_PEND(S)\n" if pend else "")
    ens = "  ensures MI(S)\n" + ("  ensures MI_PEND(S)\n" if pend else "") + "  ensures HIST\n"
    if option in ("a", "bc"):
        # Y's step R: val n = seq.incrementAndGet() outside the action. (b counted): the step only increments.
        s += "method reserve_Y(S: Ref) returns (n: Int)\n" + req + ens
        if pend:
            s += "  ensures forall qt: Int :: { tok(qt).gPend } tok(qt).gPend == (old(tok(qt).gPend) || qt == n)\n"
        else:
            s += "  ensures n == S.seq\n"
        s += "{\n  S.seq := S.seq + 1\n  n := S.seq\n  assert 0 <= n\n"
        if pend:
            s += "  assert !tok(n).gIssued\n  assert !tok(n).gPend\n  tok(n).gPend := true\n"
        s += "}\n\n"
    # Y's action B: if (n != seq.get()) issue n else refuse.
    params = "S: Ref" + (", n: Int" if option != "c" else "")
    s += "method Y(%s) returns (res: Int)\n" % params + req
    if pend:
        s += "  requires tok(n).gPend\n"
    if option == "bc":
        s += "  requires 0 <= n && n <= S.seq && !tok(n).gIssued  // (b counted): trusted fresh value\n"
    s += ens + "  ensures res != NULL ==> !old(tok(res).gIssued) && tok(res).gIssued  // P1a\n"
    if pend:
        s += "  ensures forall qt: Int :: { tok(qt).gPend } tok(qt).gPend == (old(tok(qt).gPend) && qt != n)\n"
    s += "{\n"
    if option == "c":
        s += "  S.seq := S.seq + 1  // moved into the action (DESIGN-D c, C1 ignored)\n  var n: Int := S.seq\n"
    s += "  var c: Int := S.seq  // seq.get() in the action\n  if (n != c) { res := n } else { res := NULL }\n"
    if pend:
        s += "  tok(n).gPend := false\n"
    s += "  if (res != NULL) {\n    assert !tok(res).gIssued\n"
    if pend:
        s += "    assert !tok(res).gPend\n"
    s += "    tok(res).gIssued := true\n  }\n}\n\n"
    # X's action: val t = seq.get(); issue t; seq.incrementAndGet().
    s += "method X(S: Ref) returns (res: Int)\n" + req + ens + "  ensures res != NULL ==> !old(tok(res).gIssued) && tok(res).gIssued  // P1a\n"
    if pend:
        s += "  ensures forall qt: Int :: { tok(qt).gPend } tok(qt).gPend == old(tok(qt).gPend)\n"
    s += "{\n  res := S.seq\n  S.seq := S.seq + 1\n  if (res != NULL) {\n    assert !tok(res).gIssued\n"
    if pend:
        s += "    assert !tok(res).gPend\n"
    s += "    tok(res).gIssued := true\n  }\n}\n"
    return s


if __name__ == "__main__":
    outdir = sys.argv[1]
    os.makedirs(outdir, exist_ok=True)
    for option in ("a", "b", "c"):
        with open(os.path.join(outdir, "%s_fence.vpr" % option), "w") as f:
            f.write(fence(option))
    for option in ("a", "bc", "c"):
        with open(os.path.join(outdir, "%s_steal.vpr" % option), "w") as f:
            f.write(steal(option))
    print("written to", outdir)
