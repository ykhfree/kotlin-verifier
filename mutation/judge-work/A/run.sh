#!/bin/sh
# Run Silicon on judge-A probe models, 4 at a time, with the kv budget (DESIGN-v0.md 8.3).
# Usage: run.sh SET   (SET = equiv | gap | rerun)
# Writes out/SET/<name>.out with the Silicon output and the exit code.
# The 'rerun' set runs the unmodified generated mutant models of the 30 judged mutants.
set -u
cd "$(dirname "$0")" || exit 2
SET=$1
SIL=/Users/ykhfree/AI/kotlin-verifier/spike/silicon.sh
MECH=/Users/ykhfree/AI/kotlin-verifier/translator/build/kv/mechanical
mkdir -p "out/$SET"

# Print "<model file> <output name>" lines.
list() {
  if [ "$SET" = rerun ]; then
    python3 -I -c '
import sys; sys.path.insert(0, ".")
import mkprobes
for v, ids in mkprobes.ITEMS.items():
    for i in ids:
        print(f"{mkprobes.MECH}/keylocallock.{v}/models/{i}.vpr {v}__{i}")'
  else
    for f in "$SET"/*.vpr; do
      echo "$f $(basename "$f" .vpr)"
    done
  fi
}

list | xargs -P 4 -n 2 sh -c '
  out="out/'"$SET"'/$2.out"
  perl -e "alarm shift; exec @ARGV" 300 '"$SIL"' "$1" --reportReasonUnknown --numberOfErrorsToReport 0 \
    --assertTimeout 20000 --checkTimeout 20000 > "$out" 2>&1
  echo "exit $?" >> "$out"
' sh
