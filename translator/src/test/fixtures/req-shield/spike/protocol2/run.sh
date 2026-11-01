#!/bin/bash
# Usage: run.sh OUT_DIR
# Builds both bases (canonical: target.vpr; fixed: target.vpr with FIX of mutants.py, the Viper form of
# fixed-core.patch), their mutants, controls and assumption toggles, and the reachability probes of each base.
# Checks that fixed.vpr is the generated fixed base. Verifies every file once (first reported errors and Silicon time
# go to OUT_DIR/first-reports.txt), then measures the full failing set of every file except the probes (isolate.py,
# OUT_DIR/isolation.txt) and prints the verdicts (verdicts.py). Exit 0 iff the probes are reachable and every verdict
# is as expected. Keep OUT_DIR outside the repository; ../run_mutants.sh would write into spike/.
here="$(cd "$(dirname "$0")" && pwd)"
silicon="$here/../silicon.sh"
out="${1:?usage: run.sh OUT_DIR}"
for base in canonical fixed; do
  mkdir -p "$out/$base"
  python3 -I "$here/mutants.py" "$out/$base" --base "$base" > "$out/$base/list.txt" || exit 2
  python3 -I "$here/reach.py" "$out/$base/original.vpr" "$out/$base.reach.vpr" > /dev/null || exit 2
done
cmp -s "$out/fixed/original.vpr" "$here/fixed.vpr" || { echo "fixed.vpr is not the generated fixed base"; exit 2; }

# One Silicon run per file, ten at a time.
ls "$out"/canonical/*.vpr "$out"/fixed/*.vpr "$out"/*.reach.vpr |
  xargs -P 10 -I{} sh -c '"$0" --numberOfErrorsToReport 0 "$1" > "${1%.vpr}.out" 2>&1; echo "exit=$?" >> "${1%.vpr}.out"' "$silicon" {}
ok=1
for base in canonical fixed; do
  n=$(grep -c "Refute holds" "$out/$base.reach.out")
  echo "== $base reach: $(grep -c 'reach:' "$out/$base.reach.vpr") probes, $n unreachable"
  [ "$n" = 0 ] || ok=0
done
for f in "$out"/canonical/*.out "$out"/fixed/*.out; do
  echo "-- $(basename "$(dirname "$f")")/$(basename "$f" .out) $(tail -1 "$f") $(grep -oE 'in [0-9.]+s' "$f" | head -1)"
  grep -E '^  \[ *[0-9]+\] ' "$f" | cut -c1-230
done > "$out/first-reports.txt"

# Full failing sets and verdicts.
python3 -I "$here/isolate.py" "$out/iso" "$out"/canonical/*.vpr "$out"/fixed/*.vpr > "$out/isolation.txt" || exit 2
python3 -I "$here/verdicts.py" "$out" "$out/isolation.txt" || ok=0
[ $ok = 1 ]
