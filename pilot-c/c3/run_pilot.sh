#!/bin/bash
# Runs kv spec check on the pilot sidecars and the current sidecar, with and without --root.
SP=${SP:-$(mktemp -d)}  # output folder; set SP to keep the runs
mkdir -p $SP/runs
echo "runs in $SP"
R=/Users/ykhfree/AI/kotlin-verifier
KV=$R/translator/build/install/kv/bin/kv
CELLS=$R/translator/src/test/resources/spec/cells/keylocallock.cells
CP="$(cat $R/pilot-c/cp.txt)"; KC="$(cat $R/pilot-c/kotlinc.txt)"
one() {
  name=$1; dir=$2; shift 2
  $KV spec check --root /Users/ykhfree/IdeaProjects/req-shield --classpath "$CP" --kotlinc "$KC" --snapshot $R/pilot-c/packet --cells $CELLS --out $SP/runs/$name "$@" $dir/keylocallock.coroutine.kv > $SP/runs/$name.txt 2>&1
  $KV spec check --snapshot $R/pilot-c/packet --cells $CELLS --out $SP/runs/$name-noroot "$@" $dir/keylocallock.coroutine.kv > $SP/runs/$name-noroot.txt 2>&1
  echo "done $name"
}
one signed $R/pilot-c/specs-signed &
one final-haiku $R/pilot-c/checks/final-haiku &
wait
one final-sonnet $R/pilot-c/checks/final-sonnet &
one final-opus $R/pilot-c/checks/final-opus &
wait
one aligned-haiku $R/pilot-c/checks/aligned-haiku &
one aligned-sonnet $R/pilot-c/checks/aligned-sonnet &
wait
one aligned-opus $R/pilot-c/checks/aligned-opus &
one current $R/specs/reqshield &
wait
