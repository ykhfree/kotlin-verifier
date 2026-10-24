#!/bin/sh
# Judge-work build: compiles the support stubs, then each implementation with the driver, then runs the driver.
set -e
W=/Users/ykhfree/AI/kotlin-verifier/aicode/judge-work/2
C=$HOME/.gradle/caches/modules-2/files-2.1
STDLIB=$C/org.jetbrains.kotlin/kotlin-stdlib/2.0.21/618b539767b4899b4660a83006e052b63f1db551/kotlin-stdlib-2.0.21.jar
COROUTINES=$C/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.1/bb0e192bd7c2b6b8217440d36e9758e377e450/kotlinx-coroutines-core-jvm-1.8.1.jar
SLF4J=$C/org.slf4j/slf4j-api/1.7.36/6c62681a2f655b49963a5983b8b0950a6120ae14/slf4j-api-1.7.36.jar
ANNOT=$C/org.jetbrains/annotations/13.0/919f0dfe192fb4e063e7dacadee7f8bb9a2672a9/annotations-13.0.jar
KC_CP=$C/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21/79346ed53db48b18312a472602eb5c057070c54d/kotlin-compiler-embeddable-2.0.21.jar
KC_CP=$KC_CP:$STDLIB
KC_CP=$KC_CP:$C/org.jetbrains.kotlin/kotlin-script-runtime/2.0.21/c9b044380ad41f89aa89aa896c2d32a8c0b2129d/kotlin-script-runtime-2.0.21.jar
KC_CP=$KC_CP:$C/org.jetbrains.kotlin/kotlin-reflect/1.6.10/1cbe9c92c12a94eea200d23c2bbaedaf3daf5132/kotlin-reflect-1.6.10.jar
KC_CP=$KC_CP:$C/org.jetbrains.kotlin/kotlin-daemon-embeddable/2.0.21/c9e933b23287de9b5a17e2116b4657bb91aea72c/kotlin-daemon-embeddable-2.0.21.jar
KC_CP=$KC_CP:$C/org.jetbrains.intellij.deps/trove4j/1.0.20200330/3afb14d5f9ceb459d724e907a21145e8ff394f02/trove4j-1.0.20200330.jar
KC_CP=$KC_CP:$ANNOT:$COROUTINES
kc() { java -cp "$KC_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect "$@"; }

MODE=${1:-all}
if [ "$MODE" = all ] || [ "$MODE" = build ]; then
  rm -rf $W/out/support
  kc -cp "$STDLIB:$SLF4J" -d $W/out/support $W/src/support-common/*.kt $W/src/support-fake/TimeUtils.kt
  for id in T-sonnet-1 T-opus-2 T-haiku-3 T-sonnet-4; do
    rm -rf $W/out/$id
    echo "--- compile $id"
    kc -cp "$STDLIB:$COROUTINES:$SLF4J:$W/out/support" -d $W/out/$id \
      $W/src/coroutine/KeyLock.kt $W/impl/$id/KeyLocalLock.kt $W/src/driver/Driver.kt
  done
fi
if [ "$MODE" = all ] || [ "$MODE" = run ]; then
  for id in ${2:-T-sonnet-1 T-opus-2 T-haiku-3 T-sonnet-4}; do
    java -cp "$W/out/$id:$W/out/support:$STDLIB:$COROUTINES:$SLF4J:$ANNOT" \
      com.linecorp.cse.reqshield.kotlin.coroutine.DriverKt "$id" 2>&1 | grep -v '^SLF4J'
  done
fi
