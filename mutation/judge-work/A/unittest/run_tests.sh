#!/bin/sh
# Run one req-shield module's KeyLocalLockTest on a copy of req-shield 2e792d2 with one mutant file swapped in.
# The req-shield checkout is only read (git archive). Gradle runs offline.
# Usage: run_tests.sh <core|coroutine|reactor> <mutant id|original>
# Env: WORK = scratch directory (default: a new temp directory). Appends one line to $WORK/test-summary.txt
# and copies the JUnit XML to $WORK/result-<variant>-<id>.xml.
set -u
RS=/Users/ykhfree/IdeaProjects/req-shield
MECH=/Users/ykhfree/AI/kotlin-verifier/translator/build/kv/mechanical
WORK=${WORK:-$(mktemp -d)}
v=$1; id=$2
case $v in
  core) mod=core; rel=core/src/main/kotlin/com/linecorp/cse/reqshield/KeyLocalLock.kt; cls=com.linecorp.cse.reqshield.KeyLocalLockTest;;
  coroutine) mod=core-kotlin-coroutine; rel=core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLocalLock.kt; cls=com.linecorp.cse.reqshield.kotlin.coroutine.KeyLocalLockTest;;
  reactor) mod=core-reactor; rel=core-reactor/src/main/kotlin/com/linecorp/cse/reqshield/reactor/KeyLocalLock.kt; cls=com.linecorp.cse.reqshield.reactor.KeyLocalLockTest;;
  *) echo "unknown variant $v"; exit 2;;
esac
dir=$WORK/work/$v-$id
rm -rf "$dir"; mkdir -p "$dir"
git -C "$RS" archive 2e792d2 | tar -x -C "$dir"
if [ "$id" != original ]; then cp "$MECH/keylocallock.$v/roots/$id/$rel" "$dir/$rel"; fi
cd "$dir" || exit 2
perl -e 'alarm shift; exec @ARGV' 900 ./gradlew --offline -q ":$mod:test" --tests "$cls" > "$WORK/log-$v-$id.txt" 2>&1
code=$?
x=$(ls "$mod"/build/test-results/test/TEST-*.xml 2>/dev/null | head -1)
summary=$(head -2 "$x" 2>/dev/null | grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"')
echo "$v $id exit=$code $summary" >> "$WORK/test-summary.txt"
[ -n "$x" ] && cp "$x" "$WORK/result-$v-$id.xml"
