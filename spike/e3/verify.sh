#!/bin/bash
# E3 run wrapper: checks the hole, then verifies skeleton.vpr with Silicon. At most 20 calls.
# Every call is appended to calls.log with a timestamp, the result, and a copy of the file.
cd "$(dirname "$0")"
HARNESS=/private/tmp/claude-501/kv-e3/_harness
BUDGET=20
n=$(( $(cat calls.count 2>/dev/null || echo 0) + 1 ))
if [ "$n" -gt "$BUDGET" ]; then
  echo "BUDGET EXHAUSTED: $BUDGET verifier calls used. Stop and report."
  exit 3
fi
echo "$n" > calls.count
mkdir -p attempts; cp skeleton.vpr "attempts/$n.vpr"
if ! python3 -I "$HARNESS/e3_harness.py" check "$HARNESS/skeleton.pristine.vpr" skeleton.vpr; then
  echo "call $n $(date +%T) REJECTED" >> calls.log
  echo "(call $n of $BUDGET used)"
  exit 2
fi
out=$(perl -e 'alarm shift; exec @ARGV' 300 "$HARNESS/silicon.sh" --numberOfErrorsToReport 0 skeleton.vpr 2>&1); code=$?
printf '%s\n' "$out"
[ $code = 142 ] && echo "TIME LIMIT: verification did not finish in 300 s."
echo "call $n $(date +%T) exit=$code" >> calls.log
echo "(call $n of $BUDGET used)"
exit $code
