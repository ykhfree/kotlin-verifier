#!/bin/sh
# Runs Silicon on each file with the kv verify options (Silicon.kt) and prints the result and the wall time.
# Usage: run.sh FILE.vpr...
S=/Users/ykhfree/AI/kotlin-verifier/spike/silicon.sh
for f in "$@"; do
  echo "=== $(basename "$f")"
  start=$(python3 -c 'import time; print(time.time())')
  sh "$S" --reportReasonUnknown --numberOfErrorsToReport 0 --assertTimeout 20000 --checkTimeout 20000 "$f" 2>&1 | tail -40
  end=$(python3 -c 'import time; print(time.time())')
  python3 -c "print('wall: %.2f s' % ($end - $start))"
done
