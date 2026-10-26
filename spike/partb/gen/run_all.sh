#!/bin/sh
# Regenerates every DESIGN-D experiment model and runs Silicon on each, one after another.
# Usage: run_all.sh  (writes vpr/*.vpr and results.txt next to gen/)
set -e
G=$(cd "$(dirname "$0")" && pwd)
D=$(dirname "$G")
mkdir -p "$D/vpr"
python3 -I "$G/gen_kll.py" "$D/vpr"
python3 -I "$G/gen_h1.py" "$D/vpr"
python3 -I "$G/gen_e56.py" "$D/vpr"
cd "$D/vpr"
python3 - <<'PY'
def edit(src, dst, old, new):
    s = open(src).read()
    assert s.count(old) == 1, (src, s.count(old))
    open(dst, "w").write(s.replace(old, new))
edit("a_steal.vpr", "a_steal_ok.vpr", "  res := S.seq\n  S.seq := S.seq + 1\n",
     "  S.seq := S.seq + 1  // control: X increments, then issues the new value\n  res := S.seq\n")
edit("a_steal_ok.vpr", "a_steal_ok.vpr", " && !tok(S.seq).gIssued)", ")  // control hole: without !issued(S.seq)")
edit("a_h4_fixed.vpr", "pb6_a_h4_always_token.vpr", "  res := granted__38 ? token__37 : NULL", "  res := token__37  // PB6: return token")
edit("a_steal.vpr", "brk1_a_steal_no_pend_check.vpr",
     "  res := S.seq\n  S.seq := S.seq + 1\n  if (res != NULL) {\n    assert !tok(res).gIssued\n    assert !tok(res).gPend\n",
     "  res := S.seq\n  S.seq := S.seq + 1\n  if (res != NULL) {\n    assert !tok(res).gIssued\n")
edit("a_h2_fixed.vpr", "brk2_a_h2_no_consume.vpr", "  tok(newToken__45).gPend := false  // consume the reservation (DESIGN-D a)\n", "")
PY
rm -f exp_nopendframe.vpr exp_pendinmi.vpr
grep -v "tool fact PEND" a_h2_fixed.vpr > exp_nopendframe.vpr
python3 - <<'PY'
s = open("a_h2_fixed.vpr").read()
s = s.replace("define MI(S) (MI_PERMS(S) && MI_OWN(S) &&", "define MI(S) (MI_PERMS(S) && MI_OWN(S) && MI_PEND(S) &&")
s = s.replace("  requires MI_PEND(S)\n", "").replace("  ensures MI_PEND(S)\n", "")
open("exp_pendinmi.vpr", "w").write(s)
PY
cp "$D/out/ref.vpr" ref.vpr 2>/dev/null || true
sh "$G/run.sh" ref.vpr \
  a_h1.vpr a_h1_fixed.vpr a_h2.vpr a_h2_fixed.vpr a_h4.vpr a_h4_fixed.vpr \
  c_h1.vpr c_h1_fixed.vpr c_h2.vpr c_h2_fixed.vpr c_h4.vpr c_h4_fixed.vpr \
  a_h2_fixed_mono.vpr c_h2_fixed_mono.vpr \
  a_fence.vpr b_fence.vpr c_fence.vpr a_steal.vpr bc_steal.vpr c_steal.vpr a_steal_ok.vpr \
  pb6_a_h4_always_token.vpr brk1_a_steal_no_pend_check.vpr brk2_a_h2_no_consume.vpr \
  exp_nopendframe.vpr exp_pendinmi.vpr > "$D/results.txt" 2>&1
echo "results in $D/results.txt"
