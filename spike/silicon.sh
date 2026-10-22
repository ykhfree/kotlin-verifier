#!/bin/sh
# Run Silicon (Viper's symbolic-execution verifier) on one .vpr file.
# Usage: silicon.sh FILE.vpr [extra Silicon options]
# Prints the summary and errors only. Exit code: 0 = verified, 1 = errors found.
# With TIMES=1, also prints the verification time of each method (for criterion E1).
VIPER_HOME="${VIPER_HOME:-$HOME/.local/opt/viper/v.26.08-release/tools}"

run() {
  java -Xss128m -cp "$VIPER_HOME/backends/viperserver.jar" viper.silicon.SiliconRunner \
    --z3Exe "$VIPER_HOME/z3/bin/z3" "$@"
}

if [ "${TIMES:-0}" = 1 ]; then
  out=$(run --logLevel DEBUG "$@" 2>&1); code=$?
  printf '%s\n' "$out" | grep -o 'finished verification of method `[^`]*` in [0-9.]*s' | sed 's/finished verification of method //'
  printf '%s\n' "$out" | grep -E '^(Silicon (finished|found)|  \[ *[0-9]+\] )'
  exit $code
fi
out=$(run --logLevel ERROR "$@" 2>&1); code=$?
printf '%s\n' "$out" | grep -v '^Silicon [0-9].*SNAPSHOT'
exit $code
