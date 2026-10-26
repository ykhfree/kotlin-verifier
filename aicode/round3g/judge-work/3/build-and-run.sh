#!/bin/bash
# Judge 3: builds the fake clock, the four implementations and the harness, then runs the harness on each implementation.
# It does not call the generators' compile.sh, so their compile budget stays unchanged.
W=/Users/ykhfree/AI/kotlin-verifier/aicode/round3g/judge-work/3
L=/Users/ykhfree/AI/kotlin-verifier/translator/build/kv/aicode-work/lib
SPEC=/Users/ykhfree/AI/kotlin-verifier/translator/build/kv/aicode-work/sandboxes/G-haiku-3/KeyLock.kt
IMPL=/Users/ykhfree/AI/kotlin-verifier/aicode/round3g/impl
IDS="G-haiku-3 G-sonnet-4 G-opus-1 G-opus-2"

KC="$L/compiler/kotlin-compiler-embeddable-2.4.21.jar:$L/compiler/kotlin-stdlib-2.4.21.jar:$L/compiler/annotations-13.0.jar:$L/compiler/kotlin-build-tools-api-2.4.21.jar:$L/compiler/kotlin-script-runtime-2.4.21.jar:$L/compiler/kotlin-reflect-1.6.10.jar:$L/compiler/kotlin-daemon-embeddable-2.4.21.jar:$L/compiler/kotlinx-coroutines-core-jvm-1.8.0.jar"
LIBS="$L/classpath/kotlinx-coroutines-core-jvm-1.8.1.jar:$L/classpath/kotlin-stdlib-2.0.21.jar:$L/classpath/slf4j-api-1.7.36.jar:$L/classpath/annotations-23.0.0.jar"
SUPPORT="$L/classpath/support-1.0.0.jar"

kotlinc() {
  java -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
    -language-version 2.0 -api-version 2.0 -jvm-target 17 -Xjsr305=strict "$@" > "$W/out/kotlinc.log" 2>&1
  local code=$?
  grep -v 'language version 2.0 is deprecated' "$W/out/kotlinc.log"
  return $code
}

rm -rf "$W/out" && mkdir -p "$W/out"

kotlinc -classpath "$SUPPORT:$LIBS" -d "$W/out/fakeclock" "$W/fakeclock/TimeUtils.kt" || { echo "fake clock: compile failed"; exit 1; }
for id in $IDS; do
  kotlinc -classpath "$SUPPORT:$LIBS" -d "$W/out/impl-$id" "$SPEC" "$IMPL/$id/KeyLocalLock.kt" || { echo "$id: compile failed"; exit 1; }
done
kotlinc -classpath "$W/out/fakeclock:$W/out/impl-G-haiku-3:$SUPPORT:$LIBS" -d "$W/out/harness" "$W/harness/Harness.kt" \
  || { echo "harness: compile failed"; exit 1; }

# The fake clock comes first, so it shadows support-1.0.0.jar's TimeUtilsKt.
for id in $IDS; do
  java -cp "$W/out/fakeclock:$W/out/impl-$id:$W/out/harness:$SUPPORT:$LIBS" judge3.HarnessKt "$id" 2>&1 | grep -v '^SLF4J:'
done
