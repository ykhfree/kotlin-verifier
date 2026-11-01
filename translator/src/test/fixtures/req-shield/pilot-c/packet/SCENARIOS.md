# Scenarios (phase 2)

Answer each scenario from the team's text (SOURCES.md) alone, not from your DRAFT.kv. Do not change DRAFT.kv,
COVERAGE.md or QUESTIONS.md.

All scenarios use one local lock with `lockTimeoutMillis = 10` and one lock key `k`, unless they say otherwise.
"Acquired at time 0" means: `tryLock(k)` ran at clock reading 0 on an empty map with no cap and returned token `t1`.
Times are clock readings in milliseconds.

For each scenario, write in `ANSWERS.md` one row with these columns:
- ID;
- the outcome (pick one of the listed outcomes, or write your own if none fits);
- status: `settled` (a sentence of the text decides it), `assumed` (the text suggests it but does not decide it),
  or `open` (the text does not decide it);
- the anchor and exact quote that decides or suggests it (or "none").

| ID | Scenario | Outcomes to pick from |
|---|---|---|
| C01 | Empty map, no cap: `tryLock(k)` | a token that an entry backs / a token no entry backs / null |
| C02 | The map is full (a cap is set and the size check refuses new entries); `k` has no entry: `tryLock(k)` | a token that an entry backs / a token no entry backs / null |
| C03 | Acquired at time 0, not released: `tryLock(k)` at time 5 | null / a new token |
| C04 | Acquired at time 0, not released: `tryLock(k)` at time 10 | null / a new token |
| C05 | Acquired at time 0, not released, no monitor pass since: `tryLock(k)` at time 11 | null / a new token |
| C06 | After C05 returned a new token `t2`: the first holder calls `unLock(k, t1)` | true / false |
| C07 | After C05 returned `t2`: `unLock(k, t2)` | true / false |
| C08 | Acquired at time 0: `unLock(k, t1)` at time 5 | true / false |
| C09 | Acquired at time 0; at time 11 no `tryLock(k)` and no monitor pass has run since: `unLock(k, t1)` | true / false |
| C10 | After C08 returned true: `unLock(k, t1)` again | true / false |
| C11 | Acquired at time 0: `unLock(k, x)` with a token `x` that was never issued | true / false |
| C12 | After C02 returned a token `u`: `unLock(k, u)` | true / false |
| C13 | After C02 returned `u`, the map is still full: another `tryLock(k)` | a token that an entry backs / a token no entry backs / null |
| C14 | Acquired at time 0: a monitor pass at time 5 looks at `k` | the entry stays / the entry is dropped |
| C15 | Acquired at time 0: a monitor pass at time 10 looks at `k` | the entry stays / the entry is dropped |
| C16 | Acquired at time 0: a monitor pass at time 11 looks at `k` | the entry stays / the entry is dropped |
| C17 | After C16 dropped the entry: `unLock(k, t1)` | true / false |
| C18 | A cap is set but the map is not full; `k` has no entry: `tryLock(k)` | a token that an entry backs / a token no entry backs / null |
| C19 | Acquired at time 0, not released; the map is full: `tryLock(k)` at time 11 | null / a new token that an entry backs / a new token no entry backs |
| C20 | Acquired at time 0 for key `k`, not released: `tryLock(k2)` at time 5 for another key `k2` with no entry, no cap | a token / null |
