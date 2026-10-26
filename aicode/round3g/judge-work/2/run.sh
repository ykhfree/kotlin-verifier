#!/bin/bash
# Builds and runs Harness.kt against one implementation: ./run.sh <ID>
# The copy of KeyLocalLock.kt reads the monitor clock from FakeClock.sweepNow(); nothing else changes.
set -e
ID="$1"
W=/Users/ykhfree/AI/kotlin-verifier/aicode/round3g/judge-work/2
LIB=/Users/ykhfree/AI/kotlin-verifier/translator/build/kv/aicode-work/lib
SANDBOX=/Users/ykhfree/AI/kotlin-verifier/translator/build/kv/aicode-work/sandboxes/G-haiku-2
SRC=/Users/ykhfree/AI/kotlin-verifier/aicode/round3g/impl/$ID/KeyLocalLock.kt
OUT=$W/$ID
mkdir -p "$OUT"
rm -rf "$OUT/classes"
sed 's/System\.currentTimeMillis()/com.linecorp.cse.reqshield.support.utils.FakeClock.sweepNow()/' "$SRC" > "$OUT/KeyLocalLock.kt"
echo "== diff original vs harness copy"
diff "$SRC" "$OUT/KeyLocalLock.kt" || true
CC=$LIB/compiler
CP=$LIB/classpath/kotlinx-coroutines-core-jvm-1.8.1.jar:$LIB/classpath/kotlin-stdlib-2.0.21.jar:$LIB/classpath/slf4j-api-1.7.36.jar:$LIB/classpath/annotations-23.0.0.jar
java -cp $CC/kotlin-compiler-embeddable-2.4.21.jar:$CC/kotlin-stdlib-2.4.21.jar:$CC/annotations-13.0.jar:$CC/kotlin-build-tools-api-2.4.21.jar:$CC/kotlin-script-runtime-2.4.21.jar:$CC/kotlin-reflect-1.6.10.jar:$CC/kotlin-daemon-embeddable-2.4.21.jar:$CC/kotlinx-coroutines-core-jvm-1.8.0.jar \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -classpath "$CP" \
  -language-version 2.0 -api-version 2.0 -jvm-target 17 -Xjsr305=strict -d "$OUT/classes" \
  $W/stubs/*.kt "$SANDBOX/KeyLock.kt" "$OUT/KeyLocalLock.kt" $W/Harness.kt 2>&1 | grep -v 'language version 2.0 is deprecated' || true
echo "== run $ID"
java -cp "$OUT/classes:$CP" judge.HarnessKt
