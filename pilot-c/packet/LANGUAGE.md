# The specification language (kv-sidecar/0)

A team checks every implementation of a Kotlin class against a formal specification, the "sidecar". A verifier
proves that the code meets the sidecar in every interleaving of concurrent calls. The sidecar for the local lock
`KeyLocalLock` must say what the team's text (SOURCES.md) requires. This file describes the language.

## 1. The model

- `S` is the module: the state that all instances of the class share (one per JVM).
- `S.m` is the lock map: a map from `Key` to an entry. `has(S, k)` is true when key `k` has an entry.
- An entry `S.m[k]` has four fields: `permits` (Int: the free permits of the entry's semaphore), `isHeld` (Bool),
  `token` (Int: the token the entry carries, or `NULL`) and `expiresAt` (Int: an expiry time in epoch milliseconds).
- A `Token` is a non-negative Int (the string "local-" + n is the number n). `NULL` (-1) is "no token".
- `carried(S, k, t)` is `has(S, k) && S.m[k].token == t`.
- Integers are mathematical integers (no overflow).

## 2. Operations

Each operation is ONE atomic step on one key `k` (the map's `compute` call). Calls of different operations and
different callers interleave only between steps. Inside a clause:
- `old(e)` is the value of `e` just before the step; `e` alone is the value just after it.
- `k` is the key of the step. `res` is the operation's result.
- The `free` names are the operation's inputs that the step does not compute itself:

| Operation | Inputs | Result |
|---|---|---|
| `tryLock` | `now`: the clock reading taken before the step. `timeout`: the instance's `lockTimeoutMillis`. `rej`: the answer of `LocalLockLimit.rejectsNewEntry(currentSize)`, i.e. whether the map-size cap refuses a new entry. `limit`: the cap value that call read (`0` means no cap); the trusted contract gives `rej ==> limit != 0` | `res`: a Token or `NULL` |
| `unLock` | `t`: the token the caller presents (never `NULL`) | `res`: Bool |
| `sweep` | `now`: the clock reading of one cleanup-monitor pass. One `sweep` step is the monitor's step on one key | none |

The verifier treats every input as an arbitrary value. It never assumes that the clock moves forward or that
`timeout` has a particular value, unless a clause says so.

## 3. Ghost state: the record of each token

`tok(t)` is the ghost record of token `t`. The tool updates it at the end of each step, by these rules only:
1. **Issue.** If `tryLock` returns `res != NULL`, then `tok(res).gIssued` becomes true (the tool checks that it was
   false before: a token is never handed out twice), `tok(res).gHeld` becomes true, and every `frozen` field gets the
   value that the operation's `issue` rule gives.
2. **End.** If the key's entry carried a token `t0` before the step and does not carry it after the step (the entry
   was removed or replaced, or its `token` field changed), then `tok(t0).gHeld` becomes false: that acquisition ended.
3. Nothing else changes a ghost field. A `frozen` field never changes after issue; `gIssued` never becomes false;
   `gHeld` never becomes true again.

Fields: `gIssued` (Bool), `gKey` (Key), `gBacked` (Bool: an entry carried the token when it was issued),
`gHeld` (Bool), `gLease` (Int). A token with `gBacked == false` is "unbacked": no map entry ever carried it, so rule 2
never ends it.

## 4. Clauses

All clauses go inside the skeleton (SKELETON.kv) where a line says DRAFT.

| Clause | Where | Meaning |
|---|---|---|
| `issue res { gKey = E1; gBacked = E2; gLease = E3 }` | `tryLock` | The values of the `frozen` fields of a newly issued token. Each value may use only `k`, the operation's inputs, `res`, constants, `+`, `-` and `carried(S, k, res)`. It may not read other module state. |
| `ensures ID: E` | any operation | After every step of this operation, `E` holds. `E` may use `old(...)`. |
| `ends_only_if ID: C` | any operation | An acquisition may end (rule 2) in a step of this operation only if `C` holds. Inside `C`, `qt` is the token whose acquisition ends. The tool compiles it to: for every `qt`, `old(tok(qt).gIssued) && old(tok(qt).gBacked) && old(tok(qt).gHeld) && !tok(qt).gHeld ==> (C)`. **Default when absent: no acquisition may end in this operation.** |
| `unbacked_only_if ID: C` | `tryLock` | The operation may return an unbacked token only if `C` holds: `res != NULL && !tok(res).gBacked ==> (C)`. **Default when absent: never.** |
| `invariant ID: E` | top level | A property of the state (no `old`) that every step keeps. |
| `lemma ID: E` | top level | A property of the state that must follow from the invariants. |
| `probe(PARAMS) requires E { STEPS }` | top level, at most one | A scenario that starts from the empty module. It shows that the clauses are not vacuous: its `assert` steps must follow from the operations' `ensures` clauses alone. |

Naming convention: an `ensures` clause that obliges an operation to succeed or to make progress has an ID that
starts with `progress_`. A probe can only prove what such clauses (and the other `ensures`) promise.

Probe steps: `x := OP(KEY, NAME = VALUE, ...)` calls an operation; the key comes first and every input of the
operation is given by name, each call on one line. `assert E` states a fact. Example of the syntax only:

```text
probe(k: Key, a: Int) requires a > 0 {
  x := tryLock(k, now = a, timeout = a, rej = false, limit = 0)
  ok := unLock(k, t = x)
  assert ...
}
```

## 5. Expressions

Expressions are Viper expressions: `&&`, `||`, `!`, `==>`, `==`, `!=`, `<`, `<=`, `>`, `>=`, `+`, `-`, parentheses,
`old(e)`, `has(S, e)`, `carried(S, e1, e2)`, `S.m[e].FIELD`, `tok(e).FIELD`, and quantifiers
`forall x: Int :: { TRIGGER } E` (use a trigger term such as `{ tok(x).gHeld }`; `x` must not be a reserved name).
Read `S.m[k].FIELD` only when `has(S, k)` holds (write `has(S, k) && S.m[k].FIELD ...` or `has(S, k) ==> ...`).
A clause may continue on the next lines if they are indented deeper than its first line.

Reserved names that you must not use for your own names: `S`, `k`, `res`, `qt`, `qk`, `qr`, `NULL`, the field names.

An operation has no `requires` clause: an operation must meet its clauses for every input.

## 6. What the language cannot say

- Time passing between two steps is visible only through the `now` inputs of the steps. `unLock` has no clock input.
- "Eventually" (liveness, timing of the monitor) cannot be stated; only what one step does.
- Logging, metrics, configuration parsing and other classes are outside the model.

If the text requires something that you cannot state, do not force it into a clause: list it (see TASK.md).
