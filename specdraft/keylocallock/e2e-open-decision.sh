#!/bin/bash
# DESIGN-C.md 10.6 findings 2, 3 and 5 on the data of the real run (specdraft/e2e-2026-10-26, read only), with the code:
#   1. questions: rule 6 with the current signed sidecar (specs/reqshield/keylocallock.coroutine.kv) as the reference;
#   2. align: the run's decisions and the user's decision UC1 as D9 "open" (H14s must stay undecided);
#   3. align --check on the run's aligned draft after the loop-back (DRAFT-aligned-loopback.kv, with the monitor duty):
#      it must fail and write FEEDBACK-1.md;
#   4. align --check on a copy of it without the monitor-duty clause (progress_sw_drops_expired): it must pass.
# The grouping is translator/src/test/resources/spec/e2e/groups.json (the run's groups and D9 [H14s]); the decisions
# are decisions-open-H14s.json next to it.
# Usage: specdraft/keylocallock/e2e-open-decision.sh [WORK]   (WORK default: translator/build/kv/e2e-open; replaced)
# Needs Silicon and the req-shield checkout of ./gradlew aicodeEnv. Writes into WORK only.
set -euo pipefail
R="$(cd "$(dirname "$0")/../.." && pwd)"
E="$R/specdraft/e2e-2026-10-26"
F="$R/translator/src/test/resources/spec/e2e"
WORK="${1:-$R/translator/build/kv/e2e-open}"
RUN="$WORK/run"
S=(python3 -I "$R/translator/scripts/spec.py")
rm -rf "$WORK"
mkdir -p "$WORK"
(cd "$R/translator" && ./gradlew -q installDist aicodeEnv)
# The protocol's run config with absolute paths, and the current signed sidecar as the reference.
python3 -I - "$R" "$WORK/config.json" <<'PY'
import json, os, sys
r, out = sys.argv[1], sys.argv[2]
p = os.path.join(r, "specdraft/keylocallock")
c = json.load(open(os.path.join(p, "config.json")))
for k in ("snapshot", "skeleton", "language", "variant", "hole"):
    c[k] = os.path.normpath(os.path.join(p, c[k]))
c["reference"] = os.path.join(r, "specs/reqshield/keylocallock.coroutine.kv")
json.dump(c, open(out, "w"), indent=1)
PY
"${S[@]}" task --run "$RUN" --drafter d1 --config "$WORK/config.json" --env "$R/translator/build/kv/aicode-env.json"
cp "$E/"{DRAFT.kv,COVERAGE.md,QUESTIONS.md} "$RUN/boxes/d1/phase1/"
"${S[@]}" collect --run "$RUN" --drafter d1
"${S[@]}" cells --run "$RUN" --cells "$E/scenario.cells"
cp "$E/answers.json" "$RUN/boxes/d1/phase2/ANSWERS.json"
"${S[@]}" collect --run "$RUN" --drafter d1
"${S[@]}" questions --run "$RUN" --groups "$F/groups.json"
"${S[@]}" align --run "$RUN" --decisions "$F/decisions-open-H14s.json"
cp "$E/DRAFT-aligned-loopback.kv" "$RUN/align/box/DRAFT.kv"
if "${S[@]}" align --run "$RUN" --check; then echo "UNEXPECTED: align --check passed on the draft with the monitor duty"; exit 1; fi
cat "$RUN/align/box/FEEDBACK-1.md"
# The same draft without the monitor-duty clause and its source comments.
python3 -I - "$E/DRAFT-aligned-loopback.kv" "$RUN/align/box/DRAFT.kv" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
cut = re.sub(r"(?m)^(  // source:.*\n)*  ensures progress_sw_drops_expired:\n(    .*\n)*\n?", "", text, count=1)
assert cut != text and "progress_sw_drops_expired" not in cut
open(sys.argv[2], "w").write(cut)
PY
"${S[@]}" align --run "$RUN" --check
echo "work: $WORK"
