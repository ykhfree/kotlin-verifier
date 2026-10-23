# Golden KVIR and its review against target.vpr

This directory holds the golden KVIR text that `GoldenTest` compares with the lowering of req-shield
`KeyLocalLock.kt` (branch `feature/NOISSUE/upgrade-kotlin2-and-deps`, HEAD `2e792d2`).

| File | Contents |
|---|---|
| `tryLock.kvir` | M1: the hand-built tryLock KVIR of DESIGN-v0.md 1.3 (printer test only) |
| `coroutine/{tryLock,unLock,sweep}.kvir` | M3: lowered actions of `core-kotlin-coroutine/.../KeyLocalLock.kt` |
| `core/{tryLock,unLock,sweep}.kvir` | M3: lowered actions of `core/.../KeyLocalLock.kt` |
| `coroutine/ledger.kvir`, `core/ledger.kvir` | M4: the ledger of each module file and its ASSUMPTION diagnostics |

How to read and update:

1. `@line:col` is a position in the variant's `KeyLocalLock.kt`. Nodes of the inlined helper `nextToken()` carry
   the helper's position (coroutine line 80, core line 76).
2. If the lowering output changes, the test writes the new text to `build/golden-actual/<file>`. Review the new
   text against the tables below before you copy it here.
3. `./gradlew lowerCoroutine` and `./gradlew lowerCore` print the whole module and check W1-W14.

Rule: a golden action that disagrees with `spike/effect_ghost/target.vpr` without a reason listed here is a bug,
not a golden.

## Reference models

- Coroutine: `spike/effect_ghost/target.vpr`. Its comments use the coroutine positions, which are also the
  positions of the coroutine golden files.
- Core: no separate target file is committed. The core analogue is target.vpr with the `CORE_variant` edit of
  `spike/effect_ghost/mutants.py` (lines 179-185; `python3 -I spike/effect_ghost/mutants.py OUT` writes it to
  `OUT/CORE_variant.vpr`, measured PROVEN). The edit changes only the new entry's token: the constructor gives
  `token := NULL`, and `newLock.token := token` comes after `newLock.isHeld := true`. The core tables below map to
  target.vpr line numbers; the core positions differ from the target comments.

## Differences that hold for every action

| ID | Difference | Reason |
|---|---|---|
| D1 | KVIR has no ghost code: no `pre_tok`, no END or ISSUE block, empty `commit`. target.vpr has them (tryLock 139, 192-205; unLock 224, 244-247; sweep 258, 274-277). | DESIGN-v0.md 1.1 rule 1: the lowering never makes ghost updates; the ghost binder (M6) fills `Action.commit`. |
| D2 | The method header, `requires`, the entry binding `var e: Ref := has(S, k) ? S.m[k] : null` and `res := <cell>` are not KVIR statements. | They are the emitter template (DESIGN-v0.md 5.2), made from the KVIR header lines `key`, `entry`, `free`, `cell` and `result`. |
| D3 | One KVIR `ModelOp` is two or three Viper lines (compareAndSet, tryAcquire, incrementAndGet). `tokenOf(n)` adds `assert 0 <= n`. `exit remove` and `exit put(e)` become map writes; `exit keep` writes nothing. | The KVIR -> Viper table of DESIGN-v0.md 5.2. |
| D4 | An `if` statement is placed at its `if` keyword (tryLock `@156:13`); target comments give the condition (`@156:17`). The condition itself carries `@156:17`. | Statement and expression positions are kept apart; both are Kotlin positions (W1). Not a semantic difference. |
| D5 | Header comments: target writes `now <- now @150:9 = nowToEpochTime() @150:19 (clock read outside the action)`. KVIR prints `now: Int <- now (captured @150:9)`. | The clock call is computed outside the action. The ledger records it as `free-source val now = nowToEpochTime()`; the free value's origin is the captured local (2.12). |

## coroutine tryLock (`coroutine/tryLock.kvir`, target.vpr 117-206)

| # | KVIR | Kotlin | target.vpr | Note |
|---|---|---|---|---|
| H1 | `operation tryLock = fun KeyLocalLock.tryLock` | 145:5 | 117, 122 | |
| H2 | `cell acquiredToken__151: Token? := null` | 151:9 | 140 `var acquiredToken__151: Int := NULL` | `Token?` null is `NULL` (2.9 rule 4) |
| H3 | `result res: Token? := acquiredToken__151` | 195:16 | 122 `returns (res: Int)`, 191 `res := acquiredToken__151` | D2 |
| H4 | `key k: Key <- completeKey (action key @155:25)` | 149:9 | 118, 122 `k: Key` | |
| H5 | `entry existing__155: LockInfo?` | 155:43 | 138 | D2 |
| H6 | `free now`, `free timeout` | 150:9, 172:48 | 119-120, 122 `now: Int, timeout: Int` | D5 |
| H7 | `free rej`, `free limit` (nondet `LocalLockLimit.rejectsNewEntry`) | 181:21 | 121, 122 `rej: Bool, limit: Int`, 124 `requires rej ==> limit != 0` | named non-deterministic values are parameters with the trusted contract (2.12 rule 4) |
| 1 | `if existing__155 != null` | 156:13 | 141 | D4 |
| 2 | `var cond_161_21: Bool := false` | 161:21 | 143 | `&&` with a CAS on the right (2.6) |
| 3 | `if now > existing__155.expiresAt` | 161:21 | 144 | |
| 4 | `cond_161_21 := jdk.AtomicBoolean.compareAndSet(existing__155.isHeld, true, false)` | 161:49 | 145-146 | D3 |
| 5 | `if cond_161_21` | 161:17 | 148 | |
| 6 | `existing__155.token := null` | 163:21 | 149 `:= NULL` | |
| 7 | `jdk.Semaphore.release(existing__155.permits)` | 164:21 | 150 | D3 |
| 8 | `cond_168_21 := jdk.Semaphore.tryAcquire(existing__155.permits)` | 168:21 | 152-153 | D3 |
| 9 | `if cond_168_21` | 168:17 | 154 | |
| 10 | `n_80_52 := jdk.AtomicLong.incrementAndGet(seq) [ASSUME A80]` | 80:52 | 155-156 | `nextToken()` 169:33 inlined |
| 11 | `let token__169: Token := tokenOf(n_80_52)` | 169:21 | 157-158 | D3 (`assert 0 <= n_80_52`) |
| 12 | `jdk.AtomicBoolean.set(existing__155.isHeld, true)` | 170:21 | 159 | |
| 13 | `existing__155.token := token__169` | 171:21 | 160 | |
| 14 | `existing__155.expiresAt := now + timeout [ASSUME A172]` | 172:21 | 161 | |
| 15 | `acquiredToken__151 := token__169` | 173:21 | 162 | cell `set` (2.5) |
| 16 | `exit keep` | 175:17 | 164 (comment) | D3 |
| 17 | `else` | | 165 | |
| 18 | `nondet n_181_52 := jdk.ConcurrentHashMap.mappingCount` | 181:52 | none | D6 below |
| 19 | `nondet rej, limit := LocalLockLimit.rejectsNewEntry` | 181:21 | none at this point; 122, 124 | marks the call site of the named values H7 |
| 20 | `if rej` | 181:17 | 166 | |
| 21 | `n_80_52b := jdk.AtomicLong.incrementAndGet(seq) [ASSUME A80]` | 80:52 | 167-168 | `nextToken()` 182:39 inlined |
| 22 | `acquiredToken__151 := tokenOf(n_80_52b)` | 182:21 | 169-170 | D3 |
| 23 | `exit remove` | 183:21 | 171 `S.m := S.m[k := null]` | |
| 24 | `else` | | 172 | |
| 25 | `n_80_52c := jdk.AtomicLong.incrementAndGet(seq) [ASSUME A80]` | 80:52 | 173-174 | `nextToken()` 187:29 inlined |
| 26 | `let token__187: Token := tokenOf(n_80_52c)` | 187:17 | 175-176 | D3 |
| 27 | `new newLock__188: LockInfo {permits := 1, isHeld := false, token := token__187, expiresAt := now + timeout} [ASSUME A188]` | 188:31 | 177-183 | D7 below; `isHeld := false` is the constructor default @57:37 (2.11 rule 6) |
| 28 | `cond_189_17 := jdk.Semaphore.tryAcquire(newLock__188.permits)` | 189:17 | 184-185 | result unused, as in target |
| 29 | `jdk.AtomicBoolean.set(newLock__188.isHeld, true)` | 190:17 | 186 | |
| 30 | `acquiredToken__151 := token__187` | 191:17 | 187 | |
| 31 | `exit put(newLock__188)` | 192:17 | 188 `S.m := S.m[k := newLock__188]` | |
| A | `A80`, `A172`, `A188` | 80:52, 172:42, 188:54 | 155, 161, 181 (comments) | the Long sites of DESIGN-v0.md 2.8 |

Differences of this action:

| ID | Difference | Reason |
|---|---|---|
| D6 | KVIR row 18 evaluates `lockMap.mappingCount()` into the unnamed `n_181_52`; target.vpr has no such line. | The lowering translates every FIR node of the action. `mappingCount()` is the argument of `rejectsNewEntry`, and the model of `mappingCount` inside an action is a non-deterministic Long (4.3). A v0 trusted contract cannot read its arguments, so nothing reads `n_181_52`. The emitter would write an unused `var n_181_52: Int`, which changes no verdict. Open question for M5: drop an unnamed Nondet that nothing reads, or add the line to target.vpr. |
| D7 | `new` lists the field values in sidecar field order (`permits, isHeld, token, expiresAt`); target.vpr assigns them in Kotlin argument order (`permits, expiresAt, isHeld, token`). | `New.init` is a map from field to value (KVIR 1.2). The values are constants, a local and free values, so the order does not change the state. |

## coroutine unLock (`coroutine/unLock.kvir`, target.vpr 208-248)

| # | KVIR | Kotlin | target.vpr | Note |
|---|---|---|---|---|
| H1 | `operation unLock = fun KeyLocalLock.unLock` | 198:5 | 208, 210 | |
| H2 | `cell released__204: Bool := false` | 204:9 | 225 | |
| H3 | `result res: Bool := released__204` | 227:16 | 210 `returns (res: Bool)`, 243 | D2 |
| H4 | `key k: Key <- completeKey (action key @209:25)` | 203:9 | 209, 210 | |
| H5 | `entry existing__209: LockInfo?` | 209:43 | 223 | D2 |
| H6 | `free t: Token <- token (captured @201:9)` | 201:9 | 209, 210 `t: Int`, 212 `requires t != NULL` | a non-null `String` parameter is a non-null Token; the emitter adds `t != NULL` (5.2 type facts) |
| 1 | `if existing__209 == null` | 210:13 | 226 | |
| 2 | `exit remove` | 210:35 | 227 | `return@compute null` |
| 3 | `else` | | 228 | the statements after the early return move here (2.4 rule 4) |
| 4 | `var cond_213_17: Bool := false` | 213:17 | 230 | |
| 5 | `if existing__209.token == t` | 213:17 | 231 | `Token? == Token` (2.3) |
| 6 | `cond_213_17 := jdk.AtomicBoolean.compareAndSet(existing__209.isHeld, true, false)` | 213:44 | 232-233 | D3 |
| 7 | `if cond_213_17` | 213:13 | 235 | |
| 8 | `jdk.Semaphore.release(existing__209.permits)` | 214:17 | 236 | |
| 9 | `released__204 := true` | 215:17 | 237 | cell `set` |
| 10 | `exit remove` | 219:17 | 238 | |
| 11 | `else` | | 239 | |
| 12 | `exit keep` | 221:13 | 240 (comment) | |

No difference beyond D1-D5.

## coroutine sweep (`coroutine/sweep.kvir`, target.vpr 250-278)

| # | KVIR | Kotlin | target.vpr | Note |
|---|---|---|---|---|
| H1 | `operation sweep = compute in KeyLocalLock.Companion` | 82:9 | 250, 252 | placed at the enclosing `ensureMonitorStarted` |
| H2 | `key k: Key <- key (action key @97:53)` | 96:56 | 251, 252 | the `forEach` parameter over the racy read `lockMap.keys` 96:33 |
| H3 | `entry lockInfo__97: LockInfo?` | 97:63 | 257 | D2 |
| H4 | `free now: Int <- now (captured @90:33)` | 90:33 | 251, 252 `now: Int` | D5 |
| 1 | `if lockInfo__97 == null` | 98:41 | 259 | |
| 2 | `exit remove` | 98:63 | 260 | |
| 3 | `else` | | 261 | |
| 4 | `if now > lockInfo__97.expiresAt` | 100:41 | 262 | the `if` is the lambda result (2.4 rule 2) |
| 5 | `cond_104_49 := jdk.AtomicBoolean.compareAndSet(lockInfo__97.isHeld, true, false)` | 104:49 | 263-264 | a CAS as the whole condition needs no `false` initial value |
| 6 | `if cond_104_49` | 104:45 | 265 | |
| 7 | `lockInfo__97.token := null` | 105:49 | 266 | |
| 8 | `jdk.Semaphore.release(lockInfo__97.permits)` | 106:49 | 267 | |
| 9 | `exit remove` | 108:45 | 269 | the block value `null` |
| 10 | `else` | | 270 | |
| 11 | `exit keep` | 110:45 | 271 (comment) | |

No difference beyond D1-D5.

## core tryLock (`core/tryLock.kvir`, analogue: target.vpr 117-206 with `CORE_variant`)

The rows are those of coroutine tryLock with core positions: lines 145-195 move to 163-214, `nextToken()` is at
76:52, and the names follow (`existing__173`, `acquiredToken__169`, `token__187`, `token__205`, `newLock__206`,
`cond_179_21`, `cond_186_21`, `cond_207_17`, `n_76_52`, `n_199_52`). Only the rows below differ.

| # | KVIR | Kotlin | analogue line | Note |
|---|---|---|---|---|
| H4 | `key k: Key <- completeKey (action key @173:25)` | 167:9 | 122 | `completeKey = buildLockKey(key, lockType)`: computed outside the action and not translated (2.13 rule 5) |
| 27 | `new newLock__206: LockInfo {permits := 1, isHeld := false, token := null, expiresAt := now + timeout} [ASSUME A206]` | 206:31 | 177-183, with 183 `newLock__188.token := NULL` | `LockInfo(Semaphore(1), now + lockTimeoutMillis)` passes no token, so the default `null` @62:40 is used |
| 29a | `newLock__206.token := token__205` | 209:17 | the line `CORE_variant` inserts after 186 | the core code sets the token after `newLock.isHeld.set(true)` (208:17) |
| A | `A76`, `A190`, `A206` | 76:52, 190:42, 206:54 | | the core Long sites of DESIGN-v0.md 2.8 |

Differences: D1-D7 as for coroutine tryLock (D6 is `n_199_52`).

## core unLock (`core/unLock.kvir`, analogue: target.vpr 208-248)

Rows 1-12 and H1-H6 of coroutine unLock, with core positions: function 217:5, cell `released__223` 223:9, key
222:9, entry `existing__229` 229:43, `t` 220:9, early exit 230:35, condition 232:17 with CAS 232:44, release
233:17, `released__223 := true` 234:17, `exit remove` 238:17, `exit keep` 240:13, result 246:16. No difference
beyond D1-D5.

## core sweep (`core/sweep.kvir`, analogue: target.vpr 250-278)

Rows 1-11 and H1-H4 of coroutine sweep, with core positions: operation 97:9 (`startMonitoring`, whose
`scheduleWithFixedDelay` Runnable lambda holds the loop), compute 109:25, key `key` 108:44 over the racy read
`lockMap.keys` 108:21, `now` 101:21, entry `lockInfo__109` 109:51, CAS `cond_116_37` 116:37, exits 110:51, 120:33
and 122:33. No difference beyond D1-D5.

## Ledgers (`coroutine/ledger.kvir`, `core/ledger.kvir`)

Each line is one construct and the range of FIR node numbers it holds (`first-last kind what @position`).
Numbers missing from the list are the nodes the actions translated (their `translates FIR nodes` lines).

| Kind | Coroutine | Core |
|---|---|---|
| racy-read | `lockMap.keys` 96:33 | `lockMap.size` 102:34, `lockMap.keys` 108:21, `lockMap.size` 126:33 |
| free-source | `lockTimeoutMillis`, both `completeKey = lockKeyOf(...)`, `now = nowToEpochTime()`, sweep `now = System.currentTimeMillis()`, parameter `token`, `forEach` parameter `key`, the three key arguments | the same, with `buildLockKey(...)` |
| cell | `acquiredToken`, `released`, and their `get()` after the action | same |
| structure | module object `Companion`, state `lockMap` and `tokenSequence`, class `LockInfo`, its constructor and four fields | same, with `tokenCounter` |
| not-modeled | `log`, `monitorJob`, `ensureMonitorStarted` (launch, while, try, delay), `stopMonitoring`, `job`, `coroutineContext`, `init`, `cancel`, and the rest of `tryLock` and `unLock` | `log`, `sharedScheduler`, `getOrCreateScheduler`, `createScheduler`, `startMonitoring` (scheduleWithFixedDelay, try, logging), both `init` blocks, `buildLockKey`, `shutdown`, and the rest of `tryLock` and `unLock` |
| unsupported | none | none |

The node count is 397 (coroutine: 191 in the ledger, 206 translated) and 479 (core: 270 and 209). The `structure`
kind is not in the table of DESIGN-v0.md 2.14; see the M3/M4 report.
