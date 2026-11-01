#!/bin/bash
# Usage: run_c.sh TARGET.vpr OUT_DIR
# Builds both bases (canonical: TARGET; fixed: TARGET with FIX of mutants_c.py), their entries, and the reachability
# probes; verifies every file once (first-reports.txt); measures the full failing set of every file (isolation.txt);
# prints the verdicts (verdicts.txt). Exit 0 iff the probes are reachable and every verdict is as expected.
here="$(cd "$(dirname "$0")" && pwd)"
silicon=/Users/ykhfree/AI/kotlin-verifier/spike/silicon.sh
target="${1:?usage: run_c.sh TARGET.vpr OUT_DIR}"
out="${2:?usage: run_c.sh TARGET.vpr OUT_DIR}"
for base in canonical fixed; do
  mkdir -p "$out/$base"
  python3 -I "$here/mutants_c.py" "$target" "$out/$base" --base "$base" > "$out/$base/list.txt" || exit 2
  python3 -I "$here/reach_c.py" "$out/$base/original.vpr" "$out/$base.reach.vpr" > "$out/$base.reach.list" || exit 2
done
ls "$out"/canonical/*.vpr "$out"/fixed/*.vpr "$out"/*.reach.vpr |
  xargs -P 8 -I{} sh -c '"$0" --numberOfErrorsToReport 0 "$1" > "${1%.vpr}.out" 2>&1; echo "exit=$?" >> "${1%.vpr}.out"' "$silicon" {}
ok=1
for base in canonical fixed; do
  n=$(grep -c "Refute holds" "$out/$base.reach.out")
  echo "== $base reach: $(wc -l < "$out/$base.reach.list" | tr -d ' ') probes, $n unreachable"
  grep -B1 -A1 "Refute holds" "$out/$base.reach.out" | head -20
  [ "$n" = 0 ] || ok=0
done
for f in "$out"/canonical/*.out "$out"/fixed/*.out; do
  echo "-- $(basename "$(dirname "$f")")/$(basename "$f" .out) $(tail -1 "$f") $(grep -oE 'in [0-9.]+s' "$f" | head -1)"
  grep -E '^  \[ *[0-9]+\] ' "$f" | cut -c1-230
done > "$out/first-reports.txt"
python3 -I "$here/isolate_c.py" "$silicon" "$out/iso" "$out"/canonical/*.vpr "$out"/fixed/*.vpr > "$out/isolation.txt" || exit 2
python3 -I "$here/verdicts_c.py" "$out" "$out/isolation.txt" > "$out/verdicts.txt" || ok=0
cat "$out/verdicts.txt"
[ $ok = 1 ]
