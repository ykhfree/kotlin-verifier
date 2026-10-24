#!/bin/bash
# Builds each implementation with the stub helpers and the judge harness, then runs the harness.
set -euo pipefail
W=/Users/ykhfree/AI/kotlin-verifier/aicode/judge-work/1
C=$HOME/.gradle/caches/modules-2/files-2.1
jar() { find "$C/$1" -name "$2" ! -name '*sources*' | head -1; }
STDLIB=$(jar org.jetbrains.kotlin/kotlin-stdlib/2.0.21 'kotlin-stdlib-2.0.21.jar')
CORO=$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1 'kotlinx-coroutines-core-jvm-1.8.1.jar')
COMPILER_CP=$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21 '*.jar'):$STDLIB:$(jar org.jetbrains.kotlin/kotlin-script-runtime/2.0.21 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-reflect/1.6.10 '*.jar'):$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable/2.0.21 '*.jar'):$(jar org.jetbrains.intellij.deps/trove4j '*.jar'):$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4 '*.jar'):$(jar org.jetbrains/annotations/13.0 '*.jar')
for ID in "$@"; do
  if [ "$ID" = CTRL-strict ]; then SRC=$W/control/KeyLocalLock.kt; else SRC=/Users/ykhfree/AI/kotlin-verifier/aicode/impl/$ID/KeyLocalLock.kt; fi
  OUT=$W/build/$ID
  rm -rf "$OUT"; mkdir -p "$OUT"
  java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -nowarn -jvm-target 17 \
    -cp "$STDLIB:$CORO" -d "$OUT" "$W"/stubs/*.kt "$W/common/KeyLock.kt" "$SRC" "$W/harness/Judge.kt"
  java -cp "$OUT:$STDLIB:$CORO" judge.JudgeKt "$ID"
done
