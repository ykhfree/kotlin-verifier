#!/bin/bash
# Usage: verify_methods.sh FILE.vpr [SECONDS] [METHOD...]
# Verifies each method in its own Silicon process (in parallel) under a time limit.
# Prints one line per method: name, exit code (0 ok, 1 errors, 142 time limit), time, first error.
cd "$(dirname "$0")"
file=$1; limit=${2:-300}; shift 2
methods=${*:-init tryLock unLock sweep lemma_P3c probe}
out=$(mktemp -d)
for meth in $methods; do
  ( s=$(date +%s)
    perl -e 'alarm shift; exec @ARGV' "$limit" ./silicon.sh --assertTimeout 20000 --checkTimeout 20000 \
      --numberOfParallelVerifiers 1 --includeMethods "$meth" "$file" > "$out/$meth" 2>&1
    echo "exit=$? $(( $(date +%s) - s ))s" >> "$out/$meth" ) &
done
wait
pkill -f 'z3/bin/z3' 2>/dev/null
status=0
for meth in $methods; do
  last=$(tail -1 "$out/$meth")
  printf '%-12s %-14s %s\n' "$meth" "$last" "$(grep -m1 -E '^  \[ *0\]' "$out/$meth" | cut -c1-170)"
  case "$last" in exit=0*) ;; *) status=1 ;; esac
done
rm -rf "$out"
exit $status
