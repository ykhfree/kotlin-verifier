#!/bin/bash
# DESIGN-C.md C5 acceptance 1-4: runs the KeyLocalLock pilot from the start with the spec.py commands, one draft (JC1).
# The LLM steps are stand-ins from the pilot (pilot-c is read only):
#   phase 1   pilot-c/frozen-phase1/D-opus (DRAFT.kv, COVERAGE.md, QUESTIONS.md)
#   phase 2   pilot-c/frozen-final/D-opus/ANSWERS.md, converted to kv-answers/1 by scripts/pilot_answers.py
#   grouping  specdraft/pilot-replay/groups-opus.json (the pilot grouping of the 6 opus question cells)
#   options   option A of Q-D1 = pilot-c/aligned/opus.kv, option B = the opus draft itself
#   decisions specdraft/pilot-replay/decisions-opus.json (the pilot's choices: D1 strict, D4, D2, D5 as signed)
#   alignment pilot-c/aligned/opus.kv as the alignment agent's DRAFT.kv
# The phase-2 cells are the pilot's 20 (19 formal) cells, because the frozen answers answer them. A second run on the
# same draft builds the phase-2 box of the generated cells (kv spec cells, DESIGN-C.md 10.4) for the cost data.
# Usage: specdraft/pilot-replay/replay.sh [WORK]   (WORK default: translator/build/kv/spec-replay; it is replaced)
# Needs Silicon and the req-shield checkout of ./gradlew aicodeEnv. Writes the results to specdraft/pilot-replay/results.
set -euo pipefail
R="$(cd "$(dirname "$0")/../.." && pwd)"
HERE="$R/specdraft/pilot-replay"
P="$R/specdraft/keylocallock"
WORK="${1:-$R/translator/build/kv/spec-replay}"
OUT="$HERE/results"
S=(python3 -I "$R/translator/scripts/spec.py")
ENV="$R/translator/build/kv/aicode-env.json"
RUN="$WORK/run"
GEN="$WORK/run-generated"
rm -rf "$WORK"
mkdir -p "$WORK" "$OUT"
(cd "$R/translator" && ./gradlew -q installDist aicodeEnv)
log() { echo "\$ spec.py $*" | sed "s#$R/##g"; }
step() { local name=$1; shift; { log "$@"; "${S[@]}" "$@"; } 2>&1 | tee "$OUT/$name.txt"; return "${PIPESTATUS[0]}"; }

# Step 3 (phase 1): the box, then the stand-in drafter's files.
step 01-task task --run "$RUN" --drafter opus --config "$P/config.json" --env "$ENV"
cp "$R/pilot-c/frozen-phase1/D-opus/"{DRAFT.kv,COVERAGE.md,QUESTIONS.md} "$RUN/boxes/opus/phase1/"
# Step 4: collection and the grammar check (the frozen draft passes without a correction round, as in the pilot).
step 02-collect-phase1 collect --run "$RUN" --drafter opus
# Steps 5-6: the pilot cells, the checks on the code, the phase-2 box.
step 03-cells cells --run "$RUN" --cells "$R/translator/src/test/resources/spec/cells/keylocallock.cells" --notes "$P/pilot-cells-notes.md"
python3 -I "$R/translator/scripts/pilot_answers.py" "$R/pilot-c" "$R/translator/src/test/resources/spec/cells/keylocallock.cells" "$WORK/answers"
cp "$WORK/answers/opus.json" "$RUN/boxes/opus/phase2/ANSWERS.json"
step 04-collect-phase2 collect --run "$RUN" --drafter opus
# Step 7: the question rules (one draft: rule 1 = open or assumed), the groups and the bundle.
step 05-questions questions --run "$RUN" --groups "$HERE/groups-opus.json"
# DESIGN-C.md 3.6: the results per option of Q-D1.
IMPLS=()
for i in T-haiku-2 T-haiku-4 T-sonnet-1 T-sonnet-3 T-sonnet-4 T-opus-1 T-opus-2; do IMPLS+=(--impl "$i=$R/aicode/round2/impl/$i"); done
step 06-outcomes-D1 outcomes --run "$RUN" --group D1 --option "A=$R/pilot-c/aligned/opus.kv" --option "B=$RUN/drafts/opus/DRAFT.kv" \
  --mutants "$P/decision-mutants.json" "${IMPLS[@]}"
# Step 8: the alignment box, the stand-in alignment, the check.
step 07-align align --run "$RUN" --decisions "$HERE/decisions-opus.json"
cp "$R/pilot-c/aligned/opus.kv" "$RUN/align/box/DRAFT.kv"
step 08-align-check align --run "$RUN" --check

# Cost data (DESIGN-C.md 10.4): the phase-2 box of the generated cells, on the same draft.
step 09-generated-task task --run "$GEN" --drafter opus --config "$P/config.json" --env "$ENV"
cp "$R/pilot-c/frozen-phase1/D-opus/"{DRAFT.kv,COVERAGE.md,QUESTIONS.md} "$GEN/boxes/opus/phase1/"
step 10-generated-collect collect --run "$GEN" --drafter opus
step 11-generated-cells cells --run "$GEN"

cp "$RUN/bundle.md" "$RUN/questions.json" "$OUT/"
cp "$RUN/outcomes/D1.json" "$OUT/outcomes-D1.json"
cp "$RUN/align/result.json" "$OUT/align-result.json"
cp "$RUN/cells/cost.json" "$OUT/cost-pilot-cells.json"
cp "$GEN/cells/cost.json" "$OUT/cost-generated-cells.json"
cp "$GEN/cells/scenario.cells" "$OUT/generated.cells"
for f in "$RUN/drafts/opus/check/report.txt" "$RUN/reference/check/report.txt" "$RUN/align/check/report.txt" "$GEN/drafts/opus/check/report.txt"; do
  name=$(echo "${f#$WORK/}" | tr '/' '-')
  sed "s#$WORK/##g" "$f" > "$OUT/$name"
done
sed -i '' "s#$WORK/##g; s#$R/##g" "$OUT"/*.txt "$OUT"/*.json "$OUT"/*.md
echo "results: $OUT"
