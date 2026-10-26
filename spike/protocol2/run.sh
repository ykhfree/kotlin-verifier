#!/bin/bash
# Usage: run.sh OUT_DIR
# Verifies target.vpr, the reachability probes (reach.py) and every mutants.py entry with Silicon, in parallel.
# Writes all files under OUT_DIR (keep it outside the repository; ../run_mutants.sh would write into spike/).
# A "fail" entry is detected when Silicon reports errors; a "pass" control must verify.
# Prints one verdict line per entry, the reported errors, and Silicon's own time. Exit 0 iff all match.
here="$(cd "$(dirname "$0")" && pwd)"
silicon="$here/../silicon.sh"
out="${1:?usage: run.sh OUT_DIR}"
mkdir -p "$out/mutants"
cp "$here/target.vpr" "$out/target.vpr"
python3 -I "$here/reach.py" "$out/reach.vpr" > /dev/null || exit 2
python3 -I "$here/mutants.py" "$out/mutants" > "$out/mutants.list" || exit 2
run() { # FILE: verify, store the output and the exit code next to it
  perl -e 'alarm shift; exec @ARGV' 300 "$silicon" --numberOfErrorsToReport 0 "$1" > "${1%.vpr}.out" 2>&1
  echo "exit=$?" >> "${1%.vpr}.out"
}
run "$out/target.vpr" &
run "$out/reach.vpr" &
while read -r name expect; do run "$out/mutants/$name.vpr" & done < "$out/mutants.list"
wait
ok=1
report() { # NAME EXPECT FILE.out
  code=$(tail -1 "$3")
  if [ "$2" = fail ] && [ "$code" = exit=1 ]; then verdict=DETECTED
  elif [ "$2" = pass ] && [ "$code" = exit=0 ]; then verdict=ACCEPTED
  else verdict="UNEXPECTED($code)"; ok=0; fi
  time=$(grep -oE 'in [0-9.]+s' "$3" | head -1)
  echo "== $1 expect=$2 -> $verdict ($time)"
  grep -E '^  \[ *[0-9]+\] ' "$3" | cut -c1-230
}
report target pass "$out/target.out"
report reach pass "$out/reach.out"
while read -r name expect; do report "$name" "$expect" "$out/mutants/$name.out"; done < "$out/mutants.list"
[ $ok = 1 ]
