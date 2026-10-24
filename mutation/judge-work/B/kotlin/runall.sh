#!/bin/sh
# Runs every judge-B real-code scenario on the original build and on its mutant build. Output: out/<variant>__<scenario>__<build>.out
cd "$(dirname "$0")" || exit 2
while read -r var scen mid; do
  for build in original "$mid"; do
    o=out/${var}__${scen}__${build}.out
    perl -e 'alarm shift; exec @ARGV' 120 ./run.sh "$var" "$build" "$scen" > "$o" 2>&1
    echo "$var $scen $build exit=$? $(grep RESULT "$o")"
  done
done <<'LIST'
core AOR-03 AOR-03
core COR-01 COR-01
core RET-06 RET-06
core RET-08 RET-08
core SDL-12 SDL-12
core RACY-01 RACY-01
core RACY-01-cap RACY-01
core RACY-03 RACY-03
core RACY-06 RACY-06
coroutine RACY-01 RACY-01
coroutine RACY-01-cap RACY-01
coroutine RACY-03 RACY-03
coroutine RACY-06 RACY-06
coroutine RET-11 RET-11
coroutine RET-11-clock RET-11
coroutine SDL-06 SDL-06
reactor AOR-03 AOR-03
reactor MOP-02 MOP-02
reactor SDL-02 SDL-02
reactor RACY-01 RACY-01
reactor RACY-01-cap RACY-01
reactor RACY-03 RACY-03
reactor RACY-06 RACY-06
LIST
