#!/bin/bash
# Compiles each implementation with the harness into its own output directory, then runs the harness.
# Usage: run.sh [ID ...]   (default: all four)
set -euo pipefail
W=/Users/ykhfree/AI/kotlin-verifier/aicode/judge-work/3
G=$HOME/.gradle/caches/modules-2/files-2.1
jar() { find "$G/$1" -name "$2" | grep -v sources | head -1; }

COMPILER_CP="$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-stdlib/2.0.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-script-runtime/2.0.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-reflect/1.6.10 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable/2.0.21 '*.jar'):$(jar org.jetbrains.intellij.deps/trove4j '*.jar'):$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4 '*.jar'):$(jar org.jetbrains/annotations/13.0 '*.jar')"
LIB_CP="$(jar org.jetbrains.kotlin/kotlin-stdlib/2.0.21 '*.jar'):$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1 '*.jar'):$(jar org.slf4j/slf4j-api/1.7.36 '*.jar')"

IDS=("$@")
if [ ${#IDS[@]} -eq 0 ]; then IDS=(T-opus-1 T-haiku-2 T-sonnet-3 T-opus-4); fi

for id in "${IDS[@]}"; do
  OUT=$W/out/$id
  rm -rf "$OUT" && mkdir -p "$OUT"
  java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -cp "$LIB_CP" -d "$OUT" \
    "$W/support/TimeUtils.kt" "$W/support/LocalLockLimit.kt" "$W/support/ConfigValuesStub.kt" \
    "$W/api/KeyLock.kt" "$W/impl/$id/KeyLocalLock.kt" "$W/harness/Judge.kt" "$W/harness/MonitorBoundary.kt" 2>&1 | grep -v '^warning' || true
  java -Dimpl.id="$id" -cp "$OUT:$LIB_CP" "${MAIN:-com.linecorp.cse.reqshield.kotlin.coroutine.JudgeKt}" 2>&1 | grep -v -i 'slf4j' || true
done
