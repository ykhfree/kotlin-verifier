#!/bin/bash
# Judge 1: compiles each implementation with KeyLock.kt and the harness, then runs the harness.
# The clock shadow (src/shadow) goes first on the run classpath; the implementation is compiled unchanged.
set -u
W=/Users/ykhfree/AI/kotlin-verifier/aicode/round3g/judge-work/1
LIB=/Users/ykhfree/AI/kotlin-verifier/translator/build/kv/aicode-work/lib
C=$LIB/compiler
COMPILER_CP=$C/kotlin-compiler-embeddable-2.4.21.jar:$C/kotlin-stdlib-2.4.21.jar:$C/annotations-13.0.jar:$C/kotlin-build-tools-api-2.4.21.jar:$C/kotlin-script-runtime-2.4.21.jar:$C/kotlin-reflect-1.6.10.jar:$C/kotlin-daemon-embeddable-2.4.21.jar:$C/kotlinx-coroutines-core-jvm-1.8.0.jar
P=$LIB/classpath
CP=$P/support-1.0.0.jar:$P/kotlinx-coroutines-core-jvm-1.8.1.jar:$P/kotlin-stdlib-2.0.21.jar:$P/slf4j-api-1.7.36.jar:$P/annotations-23.0.0.jar
FLAGS="-no-stdlib -no-reflect -language-version 2.0 -api-version 2.0 -jvm-target 17 -Xjsr305=strict"
kotlinc() { java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler $FLAGS "$@" 2>&1 | grep -v 'language version 2.0 is deprecated'; }

rm -rf "$W/build/shadow" && mkdir -p "$W/build/shadow"
kotlinc -classpath "$CP" -d "$W/build/shadow" "$W/src/shadow/TimeUtils.kt" "$W/src/shadow/FakeClock.kt"

for id in "$@"; do
  out="$W/build/$id"
  rm -rf "$out" && mkdir -p "$out"
  echo "### $id: compile"
  kotlinc -classpath "$CP:$W/build/shadow" -d "$out" "$W/src/harness/KeyLock.kt" \
    "/Users/ykhfree/AI/kotlin-verifier/aicode/round3g/impl/$id/KeyLocalLock.kt" "$W/src/harness/Harness.kt"
  echo "### $id: run"
  java -cp "$W/build/shadow:$out:$CP" com.linecorp.cse.reqshield.kotlin.coroutine.HarnessKt 2>&1 | grep -v '^SLF4J'
done
