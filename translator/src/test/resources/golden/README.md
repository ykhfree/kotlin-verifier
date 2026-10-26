# Golden KVIR and its review against target.vpr

This directory holds the golden KVIR text that `GoldenTest` compares with the lowering of req-shield
`KeyLocalLock.kt` (branch `feature/NOISSUE/upgrade-kotlin2-and-deps`, HEAD `2e792d2`).

| File | Contents |
|---|---|
| `tryLock.kvir` | M1: the hand-built tryLock KVIR of DESIGN-v0.md 1.3 (printer test only) |
| `coroutine/{tryLock,unLock,sweep}.kvir` | M3: lowered actions of `core-kotlin-coroutine/.../KeyLocalLock.kt` |
| `core/{tryLock,unLock,sweep}.kvir` | M3: lowered actions of `core/.../KeyLocalLock.kt` |
| `reactor/{tryLock,unLock,sweep}.kvir` | lowered actions of `core-reactor/.../KeyLocalLock.kt` (DESIGN-v0.md 8.3, reactor variant) |
| `coroutine/ledger.kvir`, `core/ledger.kvir`, `reactor/ledger.kvir` | M4: the ledger of each module file and its ASSUMPTION diagnostics |
| `aspect/{core-spring,webflux,coroutine}/getOrCreate.kvir` | DESIGN-B.md B2: the operation of each Spring aspect module (`AspectGoldenTest`; section "Spring aspects" below) |
| `aspect/{core-spring,webflux,coroutine}/ledger.kvir` | DESIGN-B.md B2: the ledger of every audited file of each aspect module |

How to read and update:

1. `@line:col` is a position in the variant's `KeyLocalLock.kt`. Nodes of the inlined helper `nextToken()` carry
   the helper's position (coroutine line 80, core line 76, reactor line 85).
2. If the lowering output changes, the test writes the new text to `build/golden-actual/<file>`. Review the new
   text against the tables below before you copy it here.
3. `./gradlew lowerCoroutine`, `lowerCore` and `lowerReactor` print the whole module and check W1-W14.
4. `./gradlew emitCoroutine`, `emitCore` and `emitReactor` write the emitted Viper programs to
   `translator/build/kv/keylocallock.{coroutine,core,reactor}.vpr`, each with a `.positions` file (the position
   table of DESIGN-v0.md 5.7). The line numbers below are target.vpr line numbers, not those of the emitted files.

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
- Reactor: `REACTOR_variant` of the same script (lines 192-212), written to `OUT/REACTOR_variant.vpr`. It is the
  `CORE_variant` edit plus three order changes (DESIGN-v0.md 8.3, "reactor gate"); the reactor tables below list them.

## Differences that hold for every action

| ID | Difference | Reason |
|---|---|---|
| D1 | The lowered KVIR has no ghost code: no `pre_tok`, no END or ISSUE block, empty `commit`. target.vpr has them (tryLock 143, 197-210; unLock 238, 258-261; sweep 283, 299-302). | DESIGN-v0.md 1.1 rule 1: the lowering never makes ghost updates; the ghost binder (M6) fills `Action.commit`, and the emitter writes `pre_tok` and the commit block from it. |
| D2 | The method header, `requires`, the entry binding `var e: Ref := has(S, k) ? S.m[k] : null` and `res := <cell>` are not KVIR statements. | They are the emitter template (DESIGN-v0.md 5.2), made from the KVIR header lines `key`, `entry`, `free`, `cell` and `result`. |
| D3 | One KVIR `ModelOp` is two or three Viper lines (compareAndSet, tryAcquire, incrementAndGet). `tokenOf(n)` adds `assert 0 <= n`. `exit remove` and `exit put(e)` become map writes; `exit keep` writes nothing. | The KVIR -> Viper table of DESIGN-v0.md 5.2. |
| D4 | An `if` statement is placed at its `if` keyword (tryLock `@156:13`); target comments give the condition (`@156:17`). The condition itself carries `@156:17`. | Statement and expression positions are kept apart; both are Kotlin positions (W1). Not a semantic difference. |
| D5 | Header comments: target writes `now <- now @150:9 = nowToEpochTime() @150:19 (clock read outside the action)`. KVIR prints `now: Int <- now (captured @150:9)`. | The clock call is computed outside the action. The ledger records it as `free-source val now = nowToEpochTime()`; the free value's origin is the captured local (2.12). |

## coroutine tryLock (`coroutine/tryLock.kvir`, target.vpr 110-211)

| # | KVIR | Kotlin | target.vpr | Note |
|---|---|---|---|---|
| H1 | `operation tryLock = fun KeyLocalLock.tryLock` | 145:5 | 110, 115 | |
| H2 | `cell acquiredToken__151: Token? := null` | 151:9 | 144 `var acquiredToken__151: Int := NULL` | `Token?` null is `NULL` (2.9 rule 4) |
| H3 | `result res: Token? := acquiredToken__151` | 195:16 | 115 `returns (res: Int)`, 196 `res := acquiredToken__151` | D2 |
| H4 | `key k: Key <- completeKey (action key @155:25)` | 149:9 | 111, 115 `k: Key` | |
| H5 | `entry existing__155: LockInfo?` | 155:43 | 142 | D2 |
| H6 | `free now`, `free timeout` | 150:9, 172:48 | 112-113, 115 `now: Int, timeout: Int` | D5 |
| H7 | `free rej`, `free limit` (nondet `LocalLockLimit.rejectsNewEntry`) | 181:21 | 114, 115 `rej: Bool, limit: Int`, 117 `requires rej ==> limit != 0` | named non-deterministic values are parameters with the trusted contract (2.12 rule 4) |
| 1 | `if existing__155 != null` | 156:13 | 145 | D4 |
| 2 | `var cond_161_21: Bool := false` | 161:21 | 147 | `&&` with a CAS on the right (2.6) |
| 3 | `if now > existing__155.expiresAt` | 161:21 | 148 | |
| 4 | `cond_161_21 := jdk.AtomicBoolean.compareAndSet(existing__155.isHeld, true, false)` | 161:49 | 149-150 | D3 |
| 5 | `if cond_161_21` | 161:17 | 152 | |
| 6 | `existing__155.token := null` | 163:21 | 153 `:= NULL` | |
| 7 | `jdk.Semaphore.release(existing__155.permits)` | 164:21 | 154 | D3 |
| 8 | `cond_168_21 := jdk.Semaphore.tryAcquire(existing__155.permits)` | 168:21 | 156-157 | D3 |
| 9 | `if cond_168_21` | 168:17 | 158 | |
| 10 | `n_80_52 := jdk.AtomicLong.incrementAndGet(seq) [ASSUME A80]` | 80:52 | 159-160 | `nextToken()` 169:33 inlined |
| 11 | `let token__169: Token := tokenOf(n_80_52)` | 169:21 | 161-162 | D3 (`assert 0 <= n_80_52`) |
| 12 | `jdk.AtomicBoolean.set(existing__155.isHeld, true)` | 170:21 | 163 | |
| 13 | `existing__155.token := token__169` | 171:21 | 164 | |
| 14 | `existing__155.expiresAt := now + timeout [ASSUME A172]` | 172:21 | 165 | |
| 15 | `acquiredToken__151 := token__169` | 173:21 | 166 | cell `set` (2.5) |
| 16 | `exit keep` | 175:17 | 168 (comment) | D3 |
| 17 | `else` | | 169 | |
| 18 | `nondet n_181_52 := jdk.ConcurrentHashMap.mappingCount` | 181:52 | 170 `var n_181_52: Int` | D6 below (resolved) |
| 19 | `nondet rej, limit := LocalLockLimit.rejectsNewEntry` | 181:21 | none at this point; 115, 117 | marks the call site of the named values H7 |
| 20 | `if rej` | 181:17 | 171 | |
| 21 | `n_80_52b := jdk.AtomicLong.incrementAndGet(seq) [ASSUME A80]` | 80:52 | 172-173 | `nextToken()` 182:39 inlined |
| 22 | `acquiredToken__151 := tokenOf(n_80_52b)` | 182:21 | 174-175 | D3 |
| 23 | `exit remove` | 183:21 | 176 `S.m := S.m[k := null]` | |
| 24 | `else` | | 177 | |
| 25 | `n_80_52c := jdk.AtomicLong.incrementAndGet(seq) [ASSUME A80]` | 80:52 | 178-179 | `nextToken()` 187:29 inlined |
| 26 | `let token__187: Token := tokenOf(n_80_52c)` | 187:17 | 180-181 | D3 |
| 27 | `new newLock__188: LockInfo {permits := 1, isHeld := false, token := token__187, expiresAt := now + timeout} [ASSUME A188]` | 188:31 | 183-188 (182 comment) | D7 below (resolved); `isHeld := false` is the constructor default @57:37 (2.11 rule 6) |
| 28 | `cond_189_17 := jdk.Semaphore.tryAcquire(newLock__188.permits)` | 189:17 | 189-190 | result unused, as in target |
| 29 | `jdk.AtomicBoolean.set(newLock__188.isHeld, true)` | 190:17 | 191 | |
| 30 | `acquiredToken__151 := token__187` | 191:17 | 192 | |
| 31 | `exit put(newLock__188)` | 192:17 | 193 `S.m := S.m[k := newLock__188]` | |
| A | `A80`, `A172`, `A188` | 80:52, 172:42, 188:54 | 159, 165, 188 (comments) | the Long sites of DESIGN-v0.md 2.8 |

Differences of this action:

| ID | Difference | Reason |
|---|---|---|
| D6 (resolved) | KVIR row 18 evaluates `lockMap.mappingCount()` into the unnamed `n_181_52`. | Main-session decision (DESIGN-v0.md 8.3, M3/M4 question 2): the emitter writes every unnamed `Nondet` as `var o: T` (5.2 table), and target.vpr now has `var n_181_52: Int` (line 170). No difference remains. |
| D7 (resolved) | `new` lists the field values in sidecar field order (`permits, isHeld, token, expiresAt`). | target.vpr now assigns them in sidecar order too (lines 185-188), the order rule of DESIGN-v0.md 5.2. No difference remains. |

## coroutine unLock (`coroutine/unLock.kvir`, target.vpr 213-262)

| # | KVIR | Kotlin | target.vpr | Note |
|---|---|---|---|---|
| H1 | `operation unLock = fun KeyLocalLock.unLock` | 198:5 | 213, 215 | |
| H2 | `cell released__204: Bool := false` | 204:9 | 239 | |
| H3 | `result res: Bool := released__204` | 227:16 | 215 `returns (res: Bool)`, 257 | D2 |
| H4 | `key k: Key <- completeKey (action key @209:25)` | 203:9 | 214, 215 | |
| H5 | `entry existing__209: LockInfo?` | 209:43 | 237 | D2 |
| H6 | `free t: Token <- token (captured @201:9)` | 201:9 | 214, 215 `t: Int`, 217 `requires t != NULL` | a non-null `String` parameter is a non-null Token; the emitter adds `t != NULL` (5.2 type facts) |
| 1 | `if existing__209 == null` | 210:13 | 240 | |
| 2 | `exit remove` | 210:35 | 241 | `return@compute null` |
| 3 | `else` | | 242 | the statements after the early return move here (2.4 rule 4) |
| 4 | `var cond_213_17: Bool := false` | 213:17 | 244 | |
| 5 | `if existing__209.token == t` | 213:17 | 245 | `Token? == Token` (2.3) |
| 6 | `cond_213_17 := jdk.AtomicBoolean.compareAndSet(existing__209.isHeld, true, false)` | 213:44 | 246-247 | D3 |
| 7 | `if cond_213_17` | 213:13 | 249 | |
| 8 | `jdk.Semaphore.release(existing__209.permits)` | 214:17 | 250 | |
| 9 | `released__204 := true` | 215:17 | 251 | cell `set` |
| 10 | `exit remove` | 219:17 | 252 | |
| 11 | `else` | | 253 | |
| 12 | `exit keep` | 221:13 | 254 (comment) | |

No difference beyond D1-D5.

## coroutine sweep (`coroutine/sweep.kvir`, target.vpr 264-303)

| # | KVIR | Kotlin | target.vpr | Note |
|---|---|---|---|---|
| H1 | `operation sweep = compute in KeyLocalLock.Companion` | 82:9 | 264, 266 | placed at the enclosing `ensureMonitorStarted` |
| H2 | `key k: Key <- key (action key @97:53)` | 96:56 | 265, 266 | the `forEach` parameter over the racy read `lockMap.keys` 96:33 |
| H3 | `entry lockInfo__97: LockInfo?` | 97:63 | 282 | D2 |
| H4 | `free now: Int <- now (captured @90:33)` | 90:33 | 265, 266 `now: Int` | D5 |
| 1 | `if lockInfo__97 == null` | 98:41 | 284 | |
| 2 | `exit remove` | 98:63 | 285 | |
| 3 | `else` | | 286 | |
| 4 | `if now > lockInfo__97.expiresAt` | 100:41 | 287 | the `if` is the lambda result (2.4 rule 2) |
| 5 | `cond_104_49 := jdk.AtomicBoolean.compareAndSet(lockInfo__97.isHeld, true, false)` | 104:49 | 288-289 | a CAS as the whole condition needs no `false` initial value |
| 6 | `if cond_104_49` | 104:45 | 290 | |
| 7 | `lockInfo__97.token := null` | 105:49 | 291 | |
| 8 | `jdk.Semaphore.release(lockInfo__97.permits)` | 106:49 | 292 | |
| 9 | `exit remove` | 108:45 | 294 | the block value `null` |
| 10 | `else` | | 295 | |
| 11 | `exit keep` | 110:45 | 296 (comment) | |

No difference beyond D1-D5.

## core tryLock (`core/tryLock.kvir`, analogue: target.vpr 110-211 with `CORE_variant`)

The rows are those of coroutine tryLock with core positions: lines 145-195 move to 163-214, `nextToken()` is at
76:52, and the names follow (`existing__173`, `acquiredToken__169`, `token__187`, `token__205`, `newLock__206`,
`cond_179_21`, `cond_186_21`, `cond_207_17`, `n_76_52`, `n_199_52`). Only the rows below differ.

| # | KVIR | Kotlin | analogue line | Note |
|---|---|---|---|---|
| H4 | `key k: Key <- completeKey (action key @173:25)` | 167:9 | 115 | `completeKey = buildLockKey(key, lockType)`: computed outside the action and not translated (2.13 rule 5) |
| 27 | `new newLock__206: LockInfo {permits := 1, isHeld := false, token := null, expiresAt := now + timeout} [ASSUME A206]` | 206:31 | 183-188, with 187 `newLock__188.token := NULL` | `LockInfo(Semaphore(1), now + lockTimeoutMillis)` passes no token, so the default `null` @62:40 is used |
| 29a | `newLock__206.token := token__205` | 209:17 | the line `CORE_variant` inserts after 191 | the core code sets the token after `newLock.isHeld.set(true)` (208:17) |
| A | `A76`, `A190`, `A206` | 76:52, 190:42, 206:54 | | the core Long sites of DESIGN-v0.md 2.8 |

Differences: D1-D5 as for coroutine tryLock; D6 and D7 are resolved (`n_199_52` is `var n_199_52: Int`).

## core unLock (`core/unLock.kvir`, analogue: target.vpr 213-262)

Rows 1-12 and H1-H6 of coroutine unLock, with core positions: function 217:5, cell `released__223` 223:9, key
222:9, entry `existing__229` 229:43, `t` 220:9, early exit 230:35, condition 232:17 with CAS 232:44, release
233:17, `released__223 := true` 234:17, `exit remove` 238:17, `exit keep` 240:13, result 246:16. No difference
beyond D1-D5.

## core sweep (`core/sweep.kvir`, analogue: target.vpr 264-303)

Rows 1-11 and H1-H4 of coroutine sweep, with core positions: operation 97:9 (`startMonitoring`, whose
`scheduleWithFixedDelay` Runnable lambda holds the loop), compute 109:25, key `key` 108:44 over the racy read
`lockMap.keys` 108:21, `now` 101:21, entry `lockInfo__109` 109:51, CAS `cond_116_37` 116:37, exits 110:51, 120:33
and 122:33. No difference beyond D1-D5.

## reactor tryLock (`reactor/tryLock.kvir`, analogue: target.vpr 110-211 with `REACTOR_variant`)

`tryLock` is `= Mono.fromCallable { ... }` (158:5, call 162:9). The model `reactor.Mono.fromCallable` (DESIGN-v0.md
8.3) makes the lambda the operation body: its locals `completeKey` 163:13, `now` 164:13 and the cell
`acquiredToken` 166:13 are read as in core, and its last expression `acquiredToken.get()` 212:13 is the result.
The rows are those of core tryLock with reactor positions: core lines 163-214 become reactor lines 158-212,
`nextToken()` is at 85:52, and the names follow (`existing__170`, `acquiredToken__166`, `token__184`, `token__202`,
`newLock__203`, `cond_176_25`, `cond_183_25`, `cond_204_21`, `n_85_52`, `n_196_56`). Only the rows below differ.

| # | KVIR | Kotlin | analogue line | Note |
|---|---|---|---|---|
| H1 | `operation tryLock = fun KeyLocalLock.tryLock` | 158:5 | 110, 115 | the function whose whole body is the `Mono.fromCallable` call |
| H3 | `result res: Token? := acquiredToken__166` | 212:13 | 115, 196 | the lambda's last expression (a fake `return@fromCallable`), not a function `return`; `null` is the empty Mono, `NULL` |
| H4 | `key k: Key <- completeKey (action key @170:29)` | 163:13 | 111, 115 | a local of the fromCallable lambda |
| H6 | `free now <- now (captured @164:13)` | 164:13 | 112, 115 | `free now = local now` binds the lambda's local (DESIGN-v0.md 8.3) |
| 6, 7 | `jdk.Semaphore.release(existing__170.permits)` then `existing__170.token := null` | 177:25, 179:25 | 153-154, swapped | `REACTOR_variant` edit 1: the source releases first |
| 13, 14 | `existing__170.expiresAt := now + timeout` then `existing__170.token := token__184` | 186:25, 187:25 | 164-165, swapped | `REACTOR_variant` edit 2 |
| A | `A85`, `A186`, `A203` | 85:52, 186:46, 203:58 | | the reactor Long sites |

Differences: D1-D5 as for coroutine tryLock; D6 and D7 are resolved (`n_196_56` is `var n_196_56: Int`).

## reactor unLock (`reactor/unLock.kvir`, analogue: target.vpr 213-262)

`unLock` is `= Mono.fromCallable { ... }` (215:5, call 220:9). Rows 1-12 and H1-H6 of coroutine unLock, with
reactor positions: function 215:5, cell `released__222` 222:13, key 221:13, entry `existing__227` 227:47, `t`
218:9, early exit 228:39, condition 230:21 with CAS 230:48, release 231:21, `released__222 := true` 232:21,
`exit remove` 236:21, `exit keep` 240:17, result 242:13 (the lambda's last expression). The `log.debug` call
239:17 is the `slf4j.Logger` no-effect model, as in coroutine 225 and core 244. No difference beyond D1-D5.

## reactor sweep (`reactor/sweep.kvir`, analogue: target.vpr 264-303)

Rows 1-11 and H1-H4 of coroutine sweep, with reactor positions: operation 87:9 (`startMonitoringOnce`; the compute
runs in `synchronized` -> `Flux.interval(...).flatMap` -> `Mono.fromRunnable` -> `forEach` lambdas, all outside the
action and not modeled), compute 105:41, key `key` 104:60 over the racy read `lockMap.keys` 104:37, `now` 98:37 (a
local of the fromRunnable lambda), entry `lockInfo__105` 105:67, CAS `cond_112_53` 112:53, exits 106:67, 116:49
and 118:49. One row differs:

| # | KVIR | Kotlin | analogue line | Note |
|---|---|---|---|---|
| 7 | `lockInfo__105.token := null`, after the `if cond_112_53` block | 115:49 | 291, moved after 293 | `REACTOR_variant` edit 3. Its comments give `@113:49` and `@115:45`; the Kotlin positions are 113:53 and 115:49 (comments only, not compared by the gate) |

No difference beyond D1-D5.

## Ledgers (`coroutine/ledger.kvir`, `core/ledger.kvir`, `reactor/ledger.kvir`)

Each line is one construct and the range of FIR node numbers it holds (`first-last kind what @position`).
Numbers missing from the list are the nodes the actions translated (their `translates FIR nodes` lines).

| Kind | Coroutine | Core | Reactor |
|---|---|---|---|
| racy-read | `lockMap.keys` 96:33 | `lockMap.size` 102:34, `lockMap.keys` 108:21, `lockMap.size` 126:33 | `lockMap.keys` 104:37 |
| free-source | `lockTimeoutMillis`, both `completeKey = lockKeyOf(...)`, `now = nowToEpochTime()`, sweep `now = System.currentTimeMillis()`, parameter `token`, `forEach` parameter `key`, the three key arguments | the same, with `buildLockKey(...)` | the same, with `completeKey(...)`; `completeKey` and `now` are locals of the fromCallable lambdas |
| cell | `acquiredToken`, `released`, and their `get()` after the action | same | same; each `get()` is the last expression of its fromCallable lambda (a fake return) |
| structure | module object `Companion`, state `lockMap` and `tokenSequence`, class `LockInfo`, its constructor and four fields | same, with `tokenCounter` | same as coroutine |
| not-modeled | `log`, `monitorJob`, `ensureMonitorStarted` (launch, while, try, delay), `stopMonitoring`, `job`, `coroutineContext`, `init`, `cancel`, and the rest of `tryLock` and `unLock` | `log`, `sharedScheduler`, `getOrCreateScheduler`, `createScheduler`, `startMonitoring` (scheduleWithFixedDelay, try, logging), both `init` blocks, `buildLockKey`, `shutdown`, and the rest of `tryLock` and `unLock` | `log`, `monitoringStarted`, `monitorDisposable`, `consecutiveFailures`, `startMonitoringOnce` (synchronized, Flux.interval, flatMap, Mono.fromRunnable, onErrorResume, subscribe, logging), `stopMonitoring`, `init`, `completeKey`, and the rest of `tryLock` and `unLock`: the function frame and its `Mono.fromCallable` call |
| unsupported | none | none | none |

The node count is 397 (coroutine: 191 in the ledger, 206 translated), 479 (core: 270 and 209) and 460 (reactor:
247 and 213). The `structure`
kind is not in the table of DESIGN-v0.md 2.14; see the M3/M4 report.

## Spring aspects (DESIGN-B.md, B2)

`AspectGoldenTest` compares the lowering of the three `ReqShieldAspect` modules with `aspect/<module>/getOrCreate.kvir`
and `aspect/<module>/ledger.kvir`. The sidecars are `specs/reqshield/aspect.{core-spring,webflux,coroutine}.kv`;
`./gradlew lowerAspect` prints the whole modules. Positions are in each module's `ReqShieldAspect.kt` (req-shield
`2e792d2`); a position in another audited file names the file. Reference model: `spike/aspect/target.vpr`, written
for core-spring; its line numbers below. The webflux and coroutine operations differ from core-spring only in
positions (function 124:5 and 231:5, call 125:9 and 232:9, key 125:38 and 232:38, createReqShield 126:13 and
233:13), so the table holds for them with those positions.

### core-spring getOrCreate (`aspect/core-spring/getOrCreate.kvir`, target.vpr 68-99)

| # | KVIR | Kotlin | target.vpr | Note |
|---|---|---|---|---|
| H1 | `operation getOrCreate = fun ReqShieldAspect.getOrCreateReqShield` | 111:5 | 68, 71 | an expression-body function |
| H2 | `result res: Shield?` | 111:5 | 71 `returns (res: Ref, exc: Bool)` | the result type is the return type made nullable: the action's value and racy reads can be null in the model; `res != null` is part of `P_same` |
| H3 | `throws` | | 71 `exc: Bool`, 83 `exc := false` | `Operation.canThrow`: a path raises (B1) |
| B1 | `run action getOrCreate#112` | 112:9 | 84-98 | `RunAction`: the action runs here (DESIGN-B.md 5.2) |
| B2 | `return out_112_9` | 112:9 (fake `FromExpressionBody` return) | 94 `res := r_113_13`, 97 `res := cur_112_9` | E1 below |
| A1 | `action getOrCreate#112 = reqShieldMap.computeIfAbsent` | 112:9 | 68 | `ActionKind.COMPUTE_IF_ABSENT` |
| A2 | `key k: MethodKey <- getTargetMethod(joinPoint) (action key @112:38)` | 112:38 | 69, 71 `k: MethodKey` | `type MethodKey = opaque java.lang.reflect.Method`; the key argument is computed outside the action (ledger free-source) |
| A3 | `entry cur_112_9: Shield?` | 112:9 | 84 `var cur_112_9: Ref := has(S, k) ? S.m[k] : null` | the current value (DESIGN-B.md 5.4 rule 4); computeIfAbsent runs its body only if it is null (85) |
| A4 | `out out_112_9: Shield?` | 112:9 | none | the call's value: `cur` if not null, else the mapped value. E1 |
| A5 | `free thrown: Bool <- ReqShieldAspect.createReqShield(...) (nondet ...)` | 113:13 | 70, 71 `thrown: Bool` | `free thrown = call createReqShield` names the throw flag of the first call (DESIGN-B.md 2.7 rule 1, 5.3 rule 6) |
| 1 | `nondet thrown := ReqShieldAspect.createReqShield` | 113:13 | none at this point; 71 | marks the call site of the named flag |
| 2 | `if thrown` / `raise` | 113:13 | 87-88 `if (thrown) { exc := true }` | `Raise` in an action: the mapping is left unestablished (W3) |
| 3 | `fresh r_113_13: Shield := createReqShield` | 113:13 | 90-92 | `Fresh`: the emitter writes `new(gFor)`, `S.created` and `gFor := k` (DESIGN-B.md 4.1) |
| 4 | `exit put(r_113_13)` | 113:13 | 93 | the lambda's non-null result is mapped |
| C | `commit (empty)` | | none | B binds no ghost rule (DESIGN-B.md 4.1) |

Differences, each a job of the B3 emitter:

| ID | Difference | Reason |
|---|---|---|
| E1 | KVIR returns the action's `out` after `run action`; target.vpr assigns `res` at the action's two ends (94 and 97) and has no out variable. | KVIR keeps the operation section apart from the action (DESIGN-B.md 5.2). `run action; return out` is the shape the emitter writes as `res :=` at each end of the action. In mutant R1 (`compute(...)!!`), `out` also feeds the `!!` check, which target R1 writes as `if (!exc && res == null) { exc := true }`. |
| E2 | No `exc := false`, `has(S, k) ? S.m[k] : null` or `if (cur_112_9 == null)` statement. | The emitter template (DESIGN-B.md 5.4) and the computeIfAbsent row of DESIGN-B.md 2.3. |
| E3 | The method header, `requires`, `ensures` and the probe are not KVIR. | Emitter template, from the sidecar (D2 above). |

B3 (DESIGN-B.md 8) closes E1-E3 in the emitter: `./gradlew emitAspect` writes `build/kv/aspect.<module>.vpr`, and
`GateTest` checks that the core-spring model is `spike/aspect/target.vpr` after `spike/vpr_normalize.py`. The action's
`out` is the result `res` when the section returns it directly (DESIGN-B.md 9.4 question 3). For the Kotlin mutants of
`scripts/mutants.py --suite aspect`, `GateTest` compares the core-spring models with `spike/aspect/mutants.py`: M2, M4
and M7 have one more raise path (9.4 question 5: `getTargetMethod(joinPoint)` runs after the fresh call and before the
action), and they are the same without it.

The mutant shapes of `spike/aspect/mutants.py` lower on the `AspectFixture` fixtures of `SectionTest` (`kv.lower`):
R1 (`compute` + elvis + `!!`), R2 (racy read + elvis), M1 (racy read + elvis + `also` + `m[k] = v`), M2 (`m[k] = v`),
M3 (`getOrPut`), M4 (`putIfAbsent` + elvis), M5 (`{ shared }`), M6 (stray `remove`), M7 (`putIfAbsent`, result
unused). Their KVIR follows the Viper edits of `mutants.py` with the same differences E1-E3: for example M3 is
`racy v := get(k) [set-once contract]` (mutants.py `racy_get`), `if v != null return v`, else the default value
(`nondet thrown`, `raise`, `fresh r`), `run action` (putIfAbsent, value r) and `return cur ?: r` (mutants.py
`res := cur != null ? cur : r`).

### Aspect ledgers (`aspect/<module>/ledger.kvir`)

`reqShieldMap` is `internal`, so every main source file of the module is audited (DESIGN-B.md 2.1 rule 6): the
aspect file first, then the others in path order.

| Kind | core-spring | webflux | coroutine |
|---|---|---|---|
| structure | module object `ReqShieldAspect`, state `reqShieldMap` | same | same |
| free-source | the key argument `getTargetMethod(joinPoint)` 112:38 | 125:38 | 232:38 |
| trusted | the body of `createReqShield` 116:5 (DESIGN-B.md 3.3 rule 5) | 129:5 | 236:5 |
| not-modeled | `computeIfAbsent` on `expressionMap` 237:9 and on `keyGeneratorMap` 246:16 (DESIGN-B.md 5.3 rule 2; B0 acceptance 2), the frame of `getOrCreateReqShield`, the other members, and the other files (annotations, cache interfaces, `LibAutoConfiguration`) | `expressionMap` 249:9, `keyGeneratorMap` 258:16 | `expressionMap` 196:34 (inside `getCacheKeyOrDefault`), `keyGeneratorMap` 282:16; also `CoroutineExtension.kt` |
| racy-read, cell, unsupported | none | none | none |

The node counts are 653 (core-spring: 640 in the ledger, 13 translated), 700 (webflux: 687 and 13) and 802
(coroutine: 789 and 13). The class `Shield` prints at the module class position: `ReqShield` is a library class
(the core jars), so it has no source position in the audited files.

## Client module: core ReqShield (DESIGN-E, E2)

`ClientGoldenTest` compares the lowering of req-shield core `ReqShield.kt` (draft sidecar
`specs/reqshield/reqshield.core.kv`, `kv lower --draft`) with `client/reqshield.core.kvir`. Reference model:
`spike/protocol2/target.vpr` (the canonical model of DESIGN-E 5); its line numbers below. `./gradlew lowerClient` prints
the module.

| KVIR | Kotlin | target.vpr | Note |
|---|---|---|---|
| `entry getAndSet`, `param k: Key`, `free mode`, `free maxAttempt`, `result res` | 38:5, 39:9, 63:31, 181:27 | 319 `getAndSetReqShieldData(k, mode, maxAttempt) returns (exc, res, tr)` | `k` is the parameter of the obligation's key type (DESIGN-E 3.1) |
| `inline executeGetCacheFunction -> currentReqShieldData__43`, its `try` / `catch Throwable` / `throw ClientException(GET_CACHE_ERROR)` | 43:36, 223-226 | 339-345 | runCatching + getOrElse is a try that catches every Throwable (DESIGN-E 3.3) |
| `if currentReqShieldData__43 != null` ... `return currentReqShieldData__43`, `else` | 44:9, 48:13, 49:14 | 347-395 | `?.let { ...; return it } ?: run { ... }`: the let lambda never returns, so the elvis is an if |
| `inline shouldUpdateCache`, `call r_55_9 := decideToUpdateCache()` | 45:17, 55:9 | 350-351 `upd__45` | |
| `inline updateReqShieldData`: `lockType__62`, `onlyCreateCache__63`, `var token__66 := null`, `if !onlyCreateCache__63 { call token__66 := KeyLock.tryLock(...) }` | 46-77 | 353-364 | the value if of 66 assigns the var in its else branch |
| `inline runInBackground` at 69: `try { spawn task_69(k, lockType__62, token__66) }`, `catch RejectedExecutionException`, nested `try { call KeyLock.unLock } catch Exception` | 69:13, 143-157 | 365-388 | `spawn` is runAsync plus the exhale of the task precondition (374) |
| `inline handleLockForCacheCreation -> out_50_20`: `lockType__85`, `onlyUpdateCache__86`, `token__89`, the recheck `try` with `return cachedData__95` and the `finally` unLock | 50:20, 80-104 | 396-437 | the return inside the try leaves the inlined helper; target encodes it with `ret_96` |
| `inline createReqShieldData -> out_107_13`: `inline executeCallable -> arg_122_17`, `inline buildReqShieldData -> reqShieldData__120` with `new ... Data {value := arg_122_17}`, `inline runInBackground` at 125 | 107:13, 113-130 | 438-490 | |
| `inline handleLockFailure -> out_109_13`: `while attempts__178 < maxAttempt [hole ReqShield.handleLockFailure@181] [measure maxAttempt - attempts__178]`, `inline sleepBetweenPolls`, the poll `try` with `return cachedData__186`, `catch Exception` with `break` | 109:13, 173-198 | 492-542 | target encodes return and break with `found_187` and `break_192` |
| fallback `inline executeCallable -> arg_197_35` with null lock type and token, `inline buildReqShieldData -> out_197_16` | 197 | 543-561 | |
| `task task_69`: `param k, lockType__62, token__66`, `requires lockType__62 == LockType.UPDATE`, `requires (mode == Mode.ONLY_CREATE_CACHE) \|\| (token__66 != null)`, `holds (token__66 != null) ==> Lease(k, lockType__62, token__66)` | 69:51 | 216-278 (`TASK69_PRE` 216-219) | DESIGN-E 3.4 parts 1-3 |
| `task task_125`: `param k, lockType__85, token__89, reqShieldData__120`, `requires lockType__85 == LockType.CREATE`, `requires (mode == Mode.ONLY_UPDATE_CACHE) \|\| (token__89 != null)`, `holds ...` | 125:47 | 281-313 (`TASK125_PRE` 281-284) | |
| ledger: `no-effect whenComplete callback: logs only` 280-294, `no-effect fun logAsyncFailure` 544-566 | 146:31, 270:5 | none | DESIGN-E 6.4 row 10 |

Differences, each a job of the E3 emitter:

| ID | Difference | Reason |
|---|---|---|
| CE1 | No trace record, event rules, guard asserts, `NO_LEASES`, ensures or assumption toggles. | They come from the sidecar (trace, guards, ensures, assume), as an object module's ghost rules do (D1). |
| CE2 | Exceptions are structured: `call ... throws K`, `try` / `catch T: kinds` / `finally`, `throw`. target.vpr uses `exc` flags, `e_` locals and `IS_EXCEPTION`. | KVIR keeps the Kotlin structure (DESIGN-E 6.3); E3 writes the flag form of DESIGN-E 3.5. |
| CE3 | A `return` inside an `inline` block or a `try`, and a `break`, are statements; target.vpr uses `ret_96`, `found_187` and `break_192`. | As CE2. |
| CE4 | Temps are `r_`, `out_`, `arg_` and `new_` with `<line>_<col>`; a helper inlined at several call sites adds `_at<line>` to its locals (DESIGN-E 5.1 item 1). target.vpr names results `e_`, `v_`, `u_` and gives `sleepBetweenPolls` (one call site) `_at183`. | E3's `vpr_normalize.py` maps these names (DESIGN-E 6.10 E3). |
| CE5 | A task precondition has no mode range and no `phase`; target.vpr's `TASK69_PRE` has `(mode == CAU \|\| mode == OCC \|\| mode == OUC)` and `phase == UPDATE`. | The range follows from the enum type and `phase` from the trace (`PHASE`); both are E3's. |
| CE6 | The fallback's `executeCallable(callable, key, null, null)` keeps its dead branch `if (null != null) && (null != null) { call KeyLock.unLock(k, null, null) }`; target.vpr (553-554) leaves it out. | The lowering does not fold constants; Viper prunes the branch. |
| CE7 | Thrown kinds are `ClientException(GET_CACHE_ERROR)` and so on; target.vpr names them `CE_GET`, `CE_SET`, `CE_SUP`. | The kind is the class and its error code (DESIGN-E 3.5); E3 picks the Viper names. |
