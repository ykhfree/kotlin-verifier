#!/bin/sh
# Judge-B real-code runs. Compiles the original or a mutant source tree with the harness, then runs one scenario.
# Usage: run.sh VARIANT BUILD SCENARIO   (VARIANT core|coroutine|reactor, BUILD original|<mutant id>)
# The mutant trees are the verifier's own: translator/build/kv/mechanical/keylocallock.VARIANT/roots/<id>.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
VAR=$1; BUILD=$2; SCEN=$3
WORK=${KV_JUDGE_WORK:-$HERE/build}
. "$HERE/env.sh"   # KC (compiler classpath) and LIB (runtime classpath), from the local Gradle cache
case $VAR in
  core) DIR=core/src/main/kotlin/com/linecorp/cse/reqshield; MAIN=com.linecorp.cse.reqshield.MainCoreKt; H=MainCore.kt;;
  coroutine) DIR=core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine
     MAIN=com.linecorp.cse.reqshield.kotlin.coroutine.MainCoroutineKt; H=MainCoroutine.kt;;
  reactor) DIR=core-reactor/src/main/kotlin/com/linecorp/cse/reqshield/reactor
     MAIN=com.linecorp.cse.reqshield.reactor.MainReactorKt; H=MainReactor.kt;;
esac
if [ "$BUILD" = original ]; then ROOT=/Users/ykhfree/IdeaProjects/req-shield; MODE=original
else ROOT=/Users/ykhfree/AI/kotlin-verifier/translator/build/kv/mechanical/keylocallock.$VAR/roots/$BUILD; MODE=mutant; fi
OUT=$WORK/classes/$VAR-$BUILD
if [ ! -d "$OUT" ]; then
  mkdir -p "$OUT"
  java -Xmx2g -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 21 -nowarn \
    -cp "$LIB" -d "$OUT" "$ROOT/$DIR/KeyLocalLock.kt" "$ROOT/$DIR/KeyLock.kt" \
    $(find "$ROOT/support/src/main/kotlin" -name '*.kt') "$HERE/Hooks.kt" "$HERE/Scenarios.kt" "$HERE/$H" \
    || { rm -rf "$OUT"; echo "COMPILE ERROR"; exit 2; }
fi
# -Xint: the swapped static final lockMap must not be constant-folded by the JIT.
exec java -Xint -cp "$OUT:$LIB" "$MAIN" "$MODE" "$SCEN"
