#!/bin/bash
# Builds the mutants and verifies each one (whole file) in parallel.
# A "fail" mutant is detected when Silicon reports errors; a "pass" control must verify.
cd "$(dirname "$0")"
dir=keylocallock/mutants
python3 -I keylocallock/mutants.py "$dir" > "$dir.list" || exit 2
while read -r name expect; do
  ( perl -e 'alarm shift; exec @ARGV' 300 ./silicon.sh --numberOfErrorsToReport 0 "$dir/$name.vpr" > "$dir/$name.out" 2>&1
    echo "exit=$?" >> "$dir/$name.out" ) &
done < "$dir.list"
wait
pkill -f 'z3/bin/z3' 2>/dev/null
ok=1
while read -r name expect; do
  code=$(tail -1 "$dir/$name.out")
  if [ "$expect" = fail ] && [ "$code" = exit=1 ]; then verdict=DETECTED
  elif [ "$expect" = pass ] && [ "$code" = exit=0 ]; then verdict=ACCEPTED
  else verdict="UNEXPECTED($code)"; ok=0; fi
  echo "== $name expect=$expect -> $verdict"
  grep -E '^  \[ *[0-9]+\] ' "$dir/$name.out" | sed 's/(baseline.vpr@/(@/; s/(\(SB\|E4\)[^@]*@/(@/' | cut -c1-230
done < "$dir.list"
[ $ok = 1 ]
