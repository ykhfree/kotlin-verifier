#!/bin/sh
# Generate every scenario model from its spec and run Silicon on it. Usage: run_scenarios.sh [NAME_PATTERN]
cd "$(dirname "$0")/.." || exit 2
pat="${1:-*}"
for spec in scenarios/specs/$pat.json; do
  name=$(basename "$spec" .json)
  python3 gen/scenario.py "$spec" "scenarios/$name.vpr" || { echo "$name GEN-ERROR"; continue; }
  perl -e 'alarm shift; exec @ARGV' 300 /Users/ykhfree/AI/kotlin-verifier/spike/silicon.sh "scenarios/$name.vpr" > "out/$name.out" 2>&1
  echo "$name exit=$? $(head -1 out/$name.out)"
done
