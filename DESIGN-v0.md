# Translator v0 설계: KVIR, sidecar 명세, ghost 갱신 바인딩

날짜: 2026-10-23. 상태: 설계안 (구현 전). 범위: `PHASE1.md`의 v0 목표.
목표: 수정하지 않은 req-shield `KeyLocalLock.kt`(coroutine, core)와 sidecar 명세에서, `spike/keylocallock/baseline.vpr`와
같은 강도의 Viper 모델을 만든다. Silicon은 같은 성질(P1-P3c)을 증명해야 한다.

이 문서의 노력 수치는 모두 **추정**이다. "측정"이라고 쓴 값만 이번 세션에서 실행해서 얻은 값이다.

## 0. 요약

| 항목 | 결정 (권고) | 근거 |
|---|---|---|
| KVIR | 타입이 있고, A-정규형(식은 순수, 부수 효과는 문장)이고, 구조적인(goto 없음) IR. 노드: `Module`, `StateVar`, `ObjClass`, `Operation`, `Action`, `FreeValue`, `Cell`, 문장 9종, 식 10종, `GhostOp` 3종. well-formedness 불변식 14개 | 1절 |
| FIR → KVIR | `ConcurrentHashMap.compute` 람다 1개 = action 1개. action 밖에서 온 값은 모두 자유 값(free value). action 안의 모든 FIR 노드는 번역하거나 unsupported로 보고한다. action 밖의 노드는 모두 분류 대장(ledger)에 남긴다 | 2절 |
| ghost 바인딩 | **(c) 효과 기반 규칙**: ghost 상태는 action의 커밋 지점에서만 바뀐다. 값은 사전 상태, 사후 상태, 연산 결과로 정한다. 코드 지점이나 라이브러리 호출(CAS 등)에 묶지 않는다 | 3절, 측정 |
| sidecar | 작은 전용 DSL. 식은 Viper 텍스트. coroutine 전체 sidecar 약 120줄(주석 포함), core 변형과의 차이 4줄 | 4절 |
| Viper 출력 | `baseline.vpr` 구조를 그대로 따르고, 커밋 블록, 소유 conjunct(`MI_OWN`), 템플릿 부수 조건, set-once 검사를 더한다 | 5절, 측정 |
| 오류 대응 | 출력한 Viper의 줄·열 범위마다 출처(Kotlin 위치 또는 sidecar 위치)를 기록한다. 계약 절은 성질마다 한 줄로 낸다 | 5.7절, 측정 |
| 구현 | 마일스톤 M0-M8 (+ 선택 M9). 사람 엔지니어 기준 15.5-25 인일 (추정) | 7절 |

ghost 바인딩 실험 (측정, 이번 세션, Silicon `v.26.08-release` + Z3 4.16.0):

| 바인딩 | 원본 | SB1 | SB2 | SB3 | SB4 | SB6 | E4 | SB5 (대조) | SB5b (대조) |
|---|---|---|---|---|---|---|---|---|---|
| (a) 코드 지점 (`//@ ghost` 주석 위치) | 증명 | 거부 | 거부 | 거부 | 거부 | 거부 | 거부 | 증명 | 증명 |
| (b) 라이브러리 사건 (CAS 성공 = 종료) | 증명 | 실행 안 함 | 실행 안 함 | 실행 안 함 | 실행 안 함 | 실행 안 함 | 실행 안 함 | **거부 (오경보)** | **거부 (오경보)** |
| (c) 효과 기반 (권고) | 증명 | 거부 | 거부 | 거부 | 거부 | 거부 | 거부 | 증명 | 증명 |

- 변이는 Kotlin 소스 변이를 v0 lowering이 낼 Viper로 손으로 옮긴 것이다 (6절).
- (c)의 목표 모델 `spike/effect_ghost/target.vpr`은 4.17 s에 검증된다. `baseline.vpr`은 같은 기계에서 3.97 s다 (측정).
- core 변형(새 entry의 token 대입 순서만 다름)은 4.24 s에 검증된다 (측정).

## 1. KVIR

### 1.1 설계 원칙

1. KVIR은 "코드가 하는 일"만 담는다. ghost 갱신은 lowering이 만들지 않는다. sidecar를 읽는 별도 단계(ghost binder)가 `Action.commit`에만 넣는다.
2. 식은 순수하다. 부수 효과가 있는 호출(CAS, `tryAcquire`, `incrementAndGet`)은 결과를 지역 변수에 묶는 문장이 된다.
3. action 본문에는 반복문, 예외, goto가 없다. 모든 경로는 정확히 한 번 `Exit`로 끝난다.
4. 모든 노드는 Kotlin 소스 위치를 가진다. 도구가 만든 노드는 원인이 된 Kotlin 노드의 위치를 가진다.
5. 지원하지 않는 구문은 `Unsupported` 노드로 남기고, 그 action 전체를 unsupported로 표시한다. 조용히 버리는 노드는 없다.
6. v0 범위 밖의 구조(여러 action을 가진 연산, 반복문 안의 action)는 노드를 만들지 않고 unsupported로 보고한다.

### 1.2 노드 타입

아래 Kotlin 코드는 설명용 스케치다. 이름과 필드는 구현에서 바뀔 수 있다.

```kotlin
// 1-based position in a Kotlin file. `fake` holds the KtFakeSourceElementKind of compiler-made nodes.
data class Pos(val file: String, val line: Int, val col: Int, val fake: String? = null)

sealed interface KType {
    data object IntT : KType                                        // Kotlin Long/Int as a mathematical integer
    data object BoolT : KType
    data class Abs(val name: String, val nullable: Boolean) : KType // abstract String types: Key, Token, Token?
    data class RefT(val cls: String, val nullable: Boolean) : KType // object class reachable from module state
}

data class Module(
    val id: String,                    // FQN of the module object, e.g. ...KeyLocalLock.Companion
    val state: List<StateVar>,
    val classes: List<ObjClass>,
    val operations: List<Operation>,
    val strayActions: List<Action>,    // compute calls that no sidecar operation names
    val ledger: List<Disposition>,     // one entry per FIR node outside actions (section 2.14)
    val diagnostics: List<Diagnostic>, // UNSUPPORTED, ASSUMPTION, INFO
)

sealed interface StateVar { val name: String; val kotlin: String; val pos: Pos }
data class MapVar(override val name: String, override val kotlin: String, override val pos: Pos,
                  val key: KType.Abs, val valueClass: String) : StateVar          // ConcurrentHashMap<String, C>
data class CounterVar(override val name: String, override val kotlin: String, override val pos: Pos,
                      val initial: Long) : StateVar                               // AtomicLong

data class ObjClass(val name: String, val kotlin: String, val fields: List<ObjField>,
                    val defaults: Map<String, Expr>)                              // constructor default values
data class ObjField(val name: String, val kotlin: String, val type: KType,
                    val model: String?,                                           // "jdk.Semaphore" when flattened
                    val volatile: Boolean, val pos: Pos)

data class Operation(val name: String, val kotlin: String, val pos: Pos,
                     val action: Action, val cells: List<Cell>, val result: Expr?)

data class Action(
    val id: String, val pos: Pos,                 // position of the compute call
    val map: MapVar, val key: FreeValue, val entry: Local,
    val free: List<FreeValue>, val body: Block,
    val commit: List<GhostOp>,                    // filled by the ghost binder only
    val status: Status,                           // Supported | Unsupported(diagnostics)
)

data class Local(val name: String, val type: KType, val pos: Pos)          // name is already mangled
data class FreeValue(val local: Local, val origin: Origin, val sidecarName: String?)
data class Cell(val local: Local, val init: Expr, val pos: Pos)            // non-escaping AtomicReference/AtomicBoolean

sealed interface Origin
data class Captured(val decl: Pos) : Origin               // local or parameter declared outside the lambda
data class InstanceVal(val property: String) : Origin     // immutable property of the receiver instance
data class ActionKey(val arg: Pos) : Origin               // compute's key argument
data class ClockRead(val call: Pos) : Origin              // trusted clock
data class NondetCall(val model: String, val call: Pos) : Origin
data class RacyRead(val at: Pos) : Origin                 // module state read outside every action

sealed interface Stmt { val pos: Pos }
data class Let(val v: Local, val e: Expr, override val pos: Pos) : Stmt
data class Assign(val v: Local, val e: Expr, override val pos: Pos) : Stmt          // local var or cell
data class FieldWrite(val obj: Expr, val field: ObjField, val e: Expr, override val pos: Pos) : Stmt
data class ModelOp(val out: Local?, val op: String, val target: Target, val args: List<Expr>,
                   override val pos: Pos) : Stmt                                    // trusted-model operation
data class New(val v: Local, val cls: ObjClass, val init: Map<ObjField, Expr>, override val pos: Pos) : Stmt
data class Nondet(val outs: List<Local>, val model: String, override val pos: Pos) : Stmt
data class If(val cond: Expr, val then: Block, val els: Block, override val pos: Pos) : Stmt
data class Exit(val result: ExitResult, override val pos: Pos) : Stmt
data class Unsupported(val what: String, val reason: String, override val pos: Pos) : Stmt

sealed interface ExitResult
data object Keep : ExitResult               // the lambda returned its value parameter
data object Remove : ExitResult             // null
data class Put(val e: Expr) : ExitResult    // a non-null object (possibly nullable expression: put or remove)

sealed interface Target
data class FieldTarget(val obj: Expr, val field: ObjField) : Target   // flattened field, e.g. existing.permits
data class StateTarget(val v: StateVar) : Target                       // module counter

sealed interface Expr { val type: KType; val pos: Pos }
// Lit(Long|Boolean|null), Var(Local), FieldRead(obj, field), Arith(op, a, b, assumptionId),
// Cmp(op, a, b), Not(a), And(a, b), Or(a, b), TokenOf(n), Carried(key, token)

sealed interface GhostOp
data class EndIfNotCarried(val witness: ObjField) : GhostOp                    // END rule
data class Issue(val token: Expr, val fields: Map<String, Expr>) : GhostOp     // ISSUE rule
data class AssertFresh(val token: Expr) : GhostOp                              // from a set-once field kind

// Omitted: Block = List<Stmt>; Status, Disposition and Diagnostic are plain records with a Pos.
```

신뢰 모델 호출은 `ModelOp`(상태를 바꾸거나 읽는 연산)과 `Nondet`(값이 임의인 호출)으로 나뉜다.
`ModelOp.op`은 신뢰 모델 표의 항목 이름이다 (예: `jdk.AtomicBoolean.compareAndSet`). 의미는 표가 정한다 (4.3절).

### 1.3 예: coroutine `tryLock` action의 KVIR

golden 테스트에 쓸 출력 형식의 예다. `@줄:열`은 `KeyLocalLock.kt`의 위치다.

```text
operation tryLock = fun KeyLocalLock.tryLock @145:5
  cell   acquiredToken__151: Token? := null                       @151:9   (AtomicReference, non-escaping)
  result acquiredToken__151                                       @195:16
  action tryLock#155 = lockMap.compute @155:9
    key   k: Key           <- completeKey @149:9 (captured)
    entry existing__155: LockInfo?                                @155:43
    free  now: Int         <- now @150:9 (captured; clock nowToEpochTime @150:19)
    free  timeout: Int     <- this.lockTimeoutMillis @172:48 (instance val)
    free  rej: Bool, limit: Int <- LocalLockLimit.rejectsNewEntry @181:21 (nondet; ensures rej ==> limit != 0)
    body
      if existing__155 != null                                    @156:17
        var cond_161_21: Bool := false
        if now > existing__155.expiresAt                          @161:21
          cond_161_21 := jdk.AtomicBoolean.compareAndSet(existing__155.isHeld, true, false)   @161:49
        if cond_161_21
          existing__155.token := null                             @163:21
          jdk.Semaphore.release(existing__155.permits)            @164:21
        cond_168_21 := jdk.Semaphore.tryAcquire(existing__155.permits)                      @168:21
        if cond_168_21
          n_80_52 := jdk.AtomicLong.incrementAndGet(seq)          @80:52 (inlined nextToken @169:33) [ASSUME Long unbounded]
          let token__169: Token := tokenOf(n_80_52)               @80:43
          jdk.AtomicBoolean.set(existing__155.isHeld, true)       @170:21
          existing__155.token := token__169                       @171:21
          existing__155.expiresAt := now + timeout                @172:21 [ASSUME Long unbounded @172:42]
          acquiredToken__151 := token__169                        @173:21
        exit keep                                                 @175:17
      else
        nondet rej, limit := LocalLockLimit.rejectsNewEntry       @181:21
        if rej
          n_80_52b := jdk.AtomicLong.incrementAndGet(seq)         @80:52 (inlined nextToken @182:39)
          acquiredToken__151 := tokenOf(n_80_52b)                 @182:21
          exit remove                                             @183:21
        else
          n_80_52c := jdk.AtomicLong.incrementAndGet(seq)         @80:52 (inlined nextToken @187:29)
          let token__187: Token := tokenOf(n_80_52c)
          new newLock__188: LockInfo {permits := 1, expiresAt := now + timeout,
                                      isHeld := false (default @57:37), token := token__187}   @188:31
          cond_189_17 := jdk.Semaphore.tryAcquire(newLock__188.permits)   @189:17 (result unused)
          jdk.AtomicBoolean.set(newLock__188.isHeld, true)        @190:17
          acquiredToken__151 := token__187                        @191:17
          exit put(newLock__188)                                  @192:17
    commit (ghost binder)
      end-if-not-carried(witness LockInfo.token)
      assert-fresh(res); issue(res) {gKey = k, gBacked = carried(S, k, res), gLease = now + timeout}
```

### 1.4 well-formedness 불변식

검사기(M1)는 아래 규칙을 모두 확인한다. 하나라도 어기면 emitter는 실행하지 않는다.

| ID | 불변식 |
|---|---|
| W1 | 모든 노드는 `Pos`를 가진다. 도구가 만든 노드는 원인 Kotlin 노드의 위치를 가진다. |
| W2 | 식은 순수하다. `ModelOp`, `New`, `Nondet`, `Assign`은 식 안에 나오지 않는다. |
| W3 | action 본문에는 반복문, try, goto가 없다. 모든 경로는 `Exit` 하나로 끝나고, `Exit`는 자기 블록의 마지막 문장이다. |
| W4 | 모든 `Var`는 같은 경로에서 먼저 선언된 `Local`, entry, `FreeValue`, `Cell`을 가리킨다. 이름은 action 안에서 유일하다. |
| W5 | 모든 식은 `IntT`, `BoolT`, `Abs`, `RefT` 중 하나의 타입을 가진다. `String` 타입은 남지 않는다. `Token`은 `Token`과만 비교한다. |
| W6 | action 안에서 모듈 객체에 닿는 길은 entry, 그 action에서 만든 객체(`New`), 모듈 counter의 `ModelOp`뿐이다. 다른 key의 entry나 다른 map 연산은 없다 (`mappingCount`는 `Nondet`). |
| W7 | 평탄화한 필드(`permits`, `isHeld`)는 `ModelOp`의 대상으로만 나온다. 일반 필드는 `FieldRead`/`FieldWrite`로만 나온다. |
| W8 | `Cell`은 지역 변수 하나로 표현된다. 그 셀 객체가 함수 밖으로 새지 않았다는 사실은 lowering이 확인했다. |
| W9 | 모든 `FreeValue`는 `Origin`과 위치를 가진다. sidecar 이름이 붙은 값은 모델 필드, ghost 필드, 도구 macro 이름과 겹치지 않는다. |
| W10 | `Operation`은 action을 정확히 1개 가진다. `result`는 셀과 상수만 읽는다. |
| W11 | `GhostOp`는 `Action.commit`에만 있다. 그 식은 action key, 자유 값, 연산 결과, 사전 entry의 witness 값, `Carried`만 읽는다. |
| W12 | `Unsupported` 노드가 하나라도 있는 action은 status가 Unsupported다. 그런 action은 출력하지 않고, 모듈 판정은 PROVEN이 될 수 없다. |
| W13 | 무한 정수로 다루는 `Long` 연산(`Arith`, `incrementAndGet`)은 모두 ASSUMPTION 진단 ID를 가진다. |
| W14 | 모듈 파일의 FIR 노드는 모두 정확히 하나의 처리(번역, 자유 값 출처, 경쟁 읽기, 모델 밖, unsupported)를 가진다. |

### 1.5 unsupported의 표현

1. action 안의 미지원 구문은 `Unsupported(what, reason, pos)` 문장이 된다. lowering은 첫 오류에서 멈추지 않고 action 끝까지 계속해서 모든 미지원 구문을 모은다.
2. action 밖의 미지원 구문(예: action 밖에서 모듈 상태에 쓰기)은 ledger에 `unsupported`로 남는다. 그 연산과 모듈은 INCOMPLETE가 된다.
3. 보고 예: `UNSUPPORTED try expression in action tryLock at KeyLocalLock.kt:160:13: exception paths inside an action are not modeled in v0`.

## 2. FIR → KVIR lowering

### 2.1 단계

frontend는 `frontend-probe/`의 K2 checker plugin(Kotlin 2.4.21, `FirAdditionalCheckersExtension`의 file checker)을 바탕으로 한다.
visitor는 dump 텍스트를 만들지 않고 KVIR 객체를 바로 만든다.

1. sidecar의 `module`, `state`, `class` 선언을 FIR symbol에 연결한다.
2. 모듈 파일에서 `ConcurrentHashMap.compute` 호출을 모두 찾는다. 하나가 action 하나다.
3. sidecar `operation`과 action을 짝짓는다. 짝이 없는 action은 stray action이 된다.
4. 각 action의 람다 본문을 lowering한다 (2.3-2.10절).
5. 람다가 바깥에서 읽은 값을 자유 값과 셀로 분류한다 (2.5절, 2.12절).
6. `String` 값에 `Key` 또는 `Token`을 배정한다 (2.13절).
7. action 밖의 모든 FIR 노드를 ledger에 분류한다 (2.14절).
8. 검사기로 W1-W14를 확인한다.

### 2.2 action 발견과 operation 조립

1. 호출 대상의 원래 symbol(치환 override를 벗긴 것)이 `java.util.concurrent.ConcurrentHashMap.compute(K, BiFunction)`이면 compute 호출이다. probe 보고서대로 `callableId`만으로는 오버로드를 구별하지 못하므로 파라미터 타입도 비교한다.
2. dispatch receiver는 sidecar가 `state ... : Map`으로 선언한 모듈 필드여야 한다. 아니면 unsupported다.
3. 두 번째 인자는 `FirFunctionTypeConversionExpression`(kind `Sam`)이 감싼 람다 리터럴이어야 한다. 메서드 참조나 변수는 unsupported다.
4. FIR은 이 람다를 `inlineStatus=NoInline invocationKind=null`로 준다 (dump 192행). 원자성과 "정확히 한 번"은 신뢰 모델 `jdk.ConcurrentHashMap.compute`가 준다.
5. operation 선택자는 둘이다.
   - `fun KeyLocalLock.tryLock`: 그 함수 안의 compute 호출. 정확히 1개가 아니면 unsupported다.
   - `compute in KeyLocalLock.Companion`: 그 클래스 안(중첩 람다 포함)의 compute 호출. 정확히 1개가 아니면 sidecar 오류다.
6. 측정한 위치: coroutine은 sweep 97:37, tryLock 155:9, unLock 209:9. core는 sweep 109:25, tryLock 173:9, unLock 229:9.

### 2.3 구문별 규칙

| Kotlin 구문 (FIR 노드) | KVIR | 비고 |
|---|---|---|
| `if`/`else`, 주체 없는 `when` (`FirWhenExpression`) | `If` 사슬 | 주체 있는 `when`은 v0 unsupported |
| `a && b`, `a \|\| b` (`FirBooleanOperatorExpression`) | 오른쪽이 순수하면 `And`/`Or`. 아니면 2.6절 | 단락 평가 유지 |
| `x == null`, `x != null` | `Cmp(==, x, null)` | `Token?`이면 `NULL`과 비교 |
| `String == String` (`FirEqualityOperatorCall`) | `Token`끼리 `Cmp(==)` | 템플릿 단사성으로 건전 (2.9절) |
| 사용자 `equals`가 있는 클래스의 `==` | unsupported | `LockInfo`는 일반 class라 동일성 비교 |
| smart cast (`FirSmartCastExpression`) | 벗기고 원래 식 사용 | `stable=false`이면 unsupported |
| `now > e.expiresAt` (`FirComparisonExpression` + `Long.compareTo`) | `Cmp(>)` | 비교에는 가정 진단 없음 |
| `a + b`, `a - b` (`Long.plus`, `Long.minus`) | `Arith` + ASSUMPTION 진단 | 2.8절 |
| `*`, `/`, `%`, 시프트, `++` | unsupported | v0 범위 밖 |
| `"local-${...}"` (`FirStringConcatenationCall`) | `TokenOf(n)` + 부수 조건 `0 <= n` | 2.9절 |
| 지역 `val`/`var` (`FirProperty`) | `Let`/`Assign`, 이름 `<name>__<line>` | 5.5절 |
| `obj.f = e`, `obj.f` (모듈 객체의 일반 필드) | `FieldWrite`, `FieldRead` | |
| `obj.semaphore.tryAcquire()` 등 | 평탄화 필드에 대한 `ModelOp` | 2.11절 평탄화 검사 |
| 셀 `set`/`get` | `Assign`/`Var` | 2.5절 |
| 모듈의 소스 helper 호출 (`nextToken()`) | 인라인 | 재귀 금지, 같은 부분집합 규칙 적용 |
| `LockInfo(...)` 생성자 | `New` + 기본값 채움 | 2.11절 |
| 신뢰 모델의 비결정 호출 (`rejectsNewEntry`) | `Nondet` | 4.3절 |
| `log.debug(...)` 등 slf4j 호출 | 효과 없음 | 인자는 순수해야 한다 |
| `return@compute x`, 마지막 식 | `Exit` | 2.4절 |
| `try`/`catch`/`finally`, `throw` (action 안) | unsupported | SnaKt의 finally 삭제, catch 진입 결함을 피한다 |
| 반복문, 중첩 람다, `?.`, `?:`, `!!`, `is`, `as` (action 안) | unsupported | KeyLocalLock action에는 없음 |
| 그 밖의 FIR 노드 (action 안) | unsupported, FIR 클래스 이름 포함 | 건너뛰지 않음 |

action 안의 호출은 예외를 던지면 안 된다. CHM.compute는 함수가 예외를 던지면 매핑을 바꾸지 않지만, 람다가 이미 바꾼 객체 필드는 되돌리지 않는다.
v0는 이 경로를 모델링하지 않는다. 그래서 action 안의 호출은 신뢰 모델 표에서 "던지지 않음"으로 표시된 연산이거나, 같은 규칙을 통과한 인라인 helper여야 한다.
`Error`(예: `OutOfMemoryError`)는 치명적이라고 가정한다 (부록 B).

### 2.4 `return@compute`, 마지막 식, put/remove

1. `FirReturnExpression`의 대상이 compute 람다이면 `Exit`가 된다. 결과 분류:
   - `null` → `Remove`
   - 람다의 값 파라미터(smart cast를 벗긴 뒤) → `Keep`
   - 그 밖의 `LockInfo` 식 → `Put(e)` (nullable 식이면 출력 시 put 또는 remove)
2. 람다의 마지막 문장은 fake `FromLastStatement` return이 감싼다 (dump 193행: `return label=null -> lambda@compute{155:38} @156:13 fake=FromLastStatement`).
   lowering은 이 return의 식을 "결과 위치"에서 내린다.
   - 식이 `when`이면 각 분기를 결과 위치에서 내린다.
   - 블록이면 마지막 문장만 결과 위치에서 내린다.
3. `when`의 `usedAsExpression` 플래그는 쓰지 않는다. FIR은 람다 결과로 쓰인 `if`에도 `usedAsExpression=false`를 준다 (dump 194행).
4. 조기 종료 정규화: `if (c) { ...; return@compute X }` 뒤에 문장이 이어지면, 그 문장들을 `else` 블록으로 옮긴다. 그러면 `Exit`는 항상 블록의 마지막 문장이다 (W3).
5. 한 분기의 일부 경로만 종료하고 나머지가 이어지는 모양은 v0 unsupported다. KeyLocalLock에는 없다.
6. 출력 규칙: `Remove`는 항상 `S.m := S.m[k := null]`을 낸다. `Keep`은 map을 쓰지 않는다. `Put(e)`는 `S.m := S.m[k := e]`를 낸다.
   "key가 원래 없었으면 remove를 생략"하는 최적화는 넣지 않는다. 측정: 생략하지 않아도 느려지지 않았다 (생략한 목표 모델 4.15 s, 항상 쓰는 모델 4.07 s, 각각 한 번 순차 실행).
   생략 규칙은 E4처럼 분기 조건이 바뀌면 틀려지므로, 없는 편이 안전하다.

### 2.5 결과 셀 (`AtomicReference`, `AtomicBoolean`)

1. 함수의 지역 `val`이 `NEW AtomicReference(x)` 또는 `NEW AtomicBoolean(b)`로 초기화되면 셀 후보다.
2. 셀 조건: 모든 사용이 `.set(v)`/`.get()`의 receiver다. 인자로 넘기거나, 필드에 저장하거나, compute 람다가 아닌 람다가 캡처하지 않는다.
3. 셀은 `Cell(local, init)`이 된다. 람다 안의 `set`은 `Assign`, `get`은 `Var`다. action 뒤의 `get`은 연산 결과에 쓰인다.
4. Java 제네릭 생성자의 call type argument는 `kotlin.String!`이고 파라미터 타입은 `V!`다 (dump 185-186행). 셀 타입은 식 전체의 resolved type(`AtomicReference<kotlin.String?>`)에서 가져온다.
5. compute 람다는 호출한 스레드에서 같은 호출 안에 실행된다. 그래서 셀은 그 스레드의 지역 변수와 같다.
6. 람다가 바깥의 지역 `var`에 대입하는 경우는 v0 unsupported다. KeyLocalLock은 셀만 쓴다.

### 2.6 `if`/`else`와 부수 효과가 있는 `&&`

`now > existing.expiresAt && existing.isHeld.compareAndSet(true, false)`의 오른쪽은 CAS다. 블록을 복제하지 않도록 조건을 먼저 변수에 계산한다.

```text
var cond_161_21: Bool := false
if (now > existing__155.expiresAt) { cond_161_21 := compareAndSet(existing__155.isHeld, true, false) }
if (cond_161_21) { ...then 블록... } else { ...else 블록... }
```

`||`는 `var c := true; if (!a) { c := b }`로 내린다. 오른쪽이 순수하면 `And`/`Or` 식을 그대로 쓴다 (E4의 `sawEntry && existing != null`).

### 2.7 null 검사와 smart cast

1. `existing != null`은 `Cmp(!=, existing, null)`이다. Viper에서 `Ref`는 null을 가질 수 있다.
2. smart cast 노드는 사용 지점마다 따로 있다 (probe 보고서 4.2절 5번). lowering은 매번 벗긴다.
3. Viper는 null receiver의 필드 접근을 권한 검사로 거부한다. 그래서 smart cast가 보장한 non-null 사실을 Silicon도 분기 조건에서 다시 증명한다. lowering이 이 사실을 가정으로 넣지는 않는다.

### 2.8 `Long` 산술

1. `Long`은 Viper `Int`(무한 정수)로 내린다 (PHASE1 결정).
2. 무한 정수로 다루는 연산 지점마다 ASSUMPTION 진단을 하나 낸다. coroutine 변형의 지점: `incrementAndGet` 80:52, `now + lockTimeoutMillis` 172:42와 188:54. core 변형: 76:52, 190:42, 206:54.
3. 비교(`>`, `<=`)는 정확하므로 진단하지 않는다.
4. 보고서는 이 목록을 "Silicon이 확인하지 않은 가정" 절에 위치와 함께 싣는다.

### 2.9 문자열 템플릿 토큰

1. sidecar가 `type Token = template "local-${Long}"`을 선언한다.
2. `FirStringConcatenationCall`의 부분이 [문자열 상수 `local-`, `Long` 식 하나]이면 `TokenOf(n)`이 된다. 그 밖의 모양이 `Token`으로 흐르면 unsupported다.
3. 근거: 고정 접두어 + 10진 `Long` 표기는 `n`에 대해 단사다. 그래서 `String == String` 비교를 `Int` 비교로 바꿔도 결과가 같다.
4. `String?`의 null은 `NULL`(-1)이다. 템플릿이 만든 값이 -1이면 null과 섞인다. emitter는 템플릿마다 `assert 0 <= n`을 낸다.
   이 검사는 spike에서 `0 <= S.seq`가 빠진 불변식이 init과 probe를 통과한 문제를, 불변식이 아니라 증명 의무로 드러낸다.
5. 호출자가 넘긴 임의 문자열(`unLock`의 `token`)은 "-1이 아닌 임의 `Int`"다. 이것은 실제 문자열 집합을 덮는 건전한 과대근사다.
   템플릿 모양이 아닌 문자열은 발급된 어떤 토큰과도 같지 않다. 모델은 그 값도 허용하므로 모델이 더 넓다.

### 2.10 `@Volatile`

1. `@Volatile`은 property가 아니라 backing field의 annotation에 있다 (dump 24행: `[VOLATILE annotations(field)=[kotlin/jvm/Volatile]]`). frontend는 `backingFieldSymbol`의 annotation을 읽어 `ObjField.volatile`에 기록한다.
2. v0 모델에서는 의미가 바뀌지 않는다.
   - action 안의 접근은 같은 key의 compute가 직렬화한다.
   - action 밖의 읽기는 volatile 여부와 관계없이 임의 값으로 둔다 (2.12절). 이것은 둘 다에 건전하다.
   - action 밖의 쓰기는 unsupported다.
3. 보고서는 volatile 필드를 정보로만 표시한다.

### 2.11 companion object = 모듈, 객체 평탄화, 생성자 기본값

1. sidecar `module`이 companion object를 가리킨다. 그 안에서 sidecar `state`로 선언한 필드만 모듈 상태다.
2. 모듈 상태 접근은 implicit receiver가 companion에 묶인 노드로 알아본다 (dump 189행: `bound=class ...KeyLocalLock.Companion`).
   인스턴스 property(`lockTimeoutMillis`)는 `bound=class ...KeyLocalLock`이므로 모듈 상태가 아니다.
3. 초기값은 FIR 초기화식에서 읽는다. coroutine `AtomicLong()`은 0, core `AtomicLong(0)`은 `const Long 0`이다 (core dump에서 확인). `ConcurrentHashMap()`은 빈 map이다.
4. sidecar에 없는 companion 필드(`monitorJob`, `sharedScheduler`)는 모델 밖이다. action이 그 필드를 읽거나 쓰면 unsupported다.
5. 평탄화: `LockInfo.semaphore`와 `LockInfo.isHeld`를 `permits: Int`, `isHeld: Bool` 필드로 접는다. 조건:
   - 필드가 `val`이다.
   - 모든 생성 지점이 새 객체를 넘긴다 (`NEW Semaphore(1)`, 또는 기본값 `NEW AtomicBoolean(false)`).
   - 모든 사용이 신뢰 모델 연산의 receiver다.
   하나라도 어기면 그 필드를 쓰는 action은 unsupported다.
6. 생성자 기본값: 호출에서 빠진 인자는 FIR 생성자 파라미터의 기본값 식으로 채운다 (dump 15행: `default isHeld: NEW java/util/concurrent/atomic/AtomicBoolean(kotlin.Boolean)`). core는 `token`도 기본값 `null`을 쓴다.
   라이브러리 함수의 기본값은 FIR에 식이 없다. 그런 호출은 신뢰 모델 표가 기본값을 명시해야 한다.
7. Java 파라미터 이름은 `p0`, `p1`이다. 신뢰 모델은 이름이 아니라 위치와 타입으로 인자를 맞춘다.

### 2.12 action 밖에서 읽은 값 = 자유 값

람다가 람다 밖에서 선언된 값을 읽으면, 셀이 아닌 한 자유 값이다. 자유 값은 그 타입의 임의 값이다.

| 출처 | 예 | `Origin` |
|---|---|---|
| 람다 밖의 지역 변수, 파라미터 | `now`, unLock의 `token` | `Captured` |
| 수신 인스턴스의 불변 property | `lockTimeoutMillis` | `InstanceVal` |
| compute의 key 인자 | `completeKey`, sweep의 `key` | `ActionKey` |
| action 안의 비결정 신뢰 호출 | `rejectsNewEntry(...)` | `NondetCall` |
| action 밖에서 모듈 상태를 읽은 결과 | SB2의 `lockMap[completeKey]?.token`, E4의 `containsKey`, sweep의 `lockMap.keys` | `RacyRead` (해당 지역 변수의 출처로 기록) |

규칙:
1. 자유 값의 실제 계산식은 번역하지 않는다. 임의 값으로 두는 것이 모든 시점·경쟁을 덮는 과대근사다. spike 가정 2는 이것을 시계(`now`)에 대해서만 말했다. v0는 같은 논리를 모든 자유 값에 적용한다.
2. 계산식은 ledger로 감사한다. 모듈 상태 쓰기가 있으면 unsupported다.
3. sidecar가 이름을 붙인 자유 값은 Viper 메서드 파라미터가 된다. 이름이 없는 자유 값은 메서드 안의 초기화하지 않은 지역 변수(임의 값)가 된다.
   그래서 코드에 새 경쟁 읽기가 생겨도 sidecar와 probe를 고칠 필요가 없다 (SB2, E4).
4. 이름이 붙은 `Nondet` 값은 계약이 상태와 무관할 때만 파라미터로 올린다 (`rej ==> limit != 0`). 이름이 없으면 호출 위치에서 havoc 후 계약을 inhale한다.
5. action 밖에서 얻은 모듈 객체 참조를 action 안에서 역참조하면 unsupported다. 그 값이 어떤 객체인지 모델이 모르기 때문이다.

### 2.13 추상 타입 추론 (`Key`, `Token`)

`String`은 두 추상 타입으로 갈린다. key는 불투명한 `Key`, 토큰은 `Int`로 표현하는 `Token`이다.

1. 제약: 대입(좌 ~ 우), 비교(a ~ b), 인라인 helper의 인자 ~ 파라미터, 셀 `set` 값 ~ 셀 원소, 반환 ~ 결과.
2. 고정점: compute key 인자 → `Key`, `TokenOf` → `Token`, sidecar `class` 필드 타입 (`token: Token?`).
3. union-find로 묶는다. 각 묶음은 고정점이 정확히 하나여야 한다.
   - 고정점이 없고 무시하는 곳(로그 인자)에만 쓰이면 번역하지 않는다.
   - 고정점이 없는데 action이 쓰면 unsupported ("String value with unknown abstraction")다.
   - 고정점이 둘이면 unsupported ("String used as both Key and Token")다.
4. KeyLocalLock 결과: `completeKey`, sweep `key` → `Key`. `LockInfo.token`, 두 `val token`, 셀 `acquiredToken`, `tryLock` 결과, `unLock`의 `token` → `Token`.
5. `lockKeyOf`(coroutine)와 `buildLockKey`(core)는 action 밖에서 계산되므로 번역하지 않는다. 두 변형의 차이가 여기서 사라진다.

### 2.14 모듈 감사와 ledger

action 밖의 FIR 노드는 번역하지 않지만, 모두 다음 중 하나로 ledger에 남는다.

| 처리 | 뜻 | KeyLocalLock 예 |
|---|---|---|
| `free-source` | 자유 값을 계산하는 식 | `lockKeyOf(...)`, `nowToEpochTime()` |
| `racy-read` | action 밖의 모듈 상태 읽기 (임의 값) | coroutine `lockMap.keys` 96:33; core `lockMap.size` 102:34, `lockMap.keys` 108:21, `lockMap.size` 126:33 |
| `structure` | 모델이 쓰는 선언: 모듈 객체, `state` 필드, sidecar 클래스와 그 생성자·필드 | `Companion`, `lockMap`, `tokenSequence`, `LockInfo` |
| `cell` | 셀 생성과 action 뒤의 읽기 | `AtomicReference(null)` 151:29, `released.get()` 227:16 |
| `not-modeled` | 모듈 상태를 건드리지 않는 코드 | `launch`, `while (isActive)`, `delay`, `synchronized`, `scheduleWithFixedDelay`, shutdown hook, 로그 |
| `unsupported` | action 밖의 모듈 상태 쓰기, action 밖 객체를 action 안에서 역참조 | 원본에는 없음 |

1. 테스트는 "모듈 파일의 FIR 노드 수 = ledger 항목 수 + action 안에서 번역한 노드 수"를 확인한다 (W14).
2. 보고서는 `unsupported`를 항상 싣는다. `not-modeled`는 범주별 위치 목록으로 싣는다.
3. FIR 함정 처리: monitor의 `catch (e: CancellationException)`은 `java.util.concurrent.CancellationException`으로 펼쳐져 나온다 (dump 137행). action 밖이므로 `not-modeled`이고, 이름 대응이 필요 없다.

## 3. ghost 갱신 바인딩

### 3.1 무엇을 묶어야 하나

`baseline.vpr`의 ghost 갱신은 6곳이다. 할당 기록(`S.created := S.created union Set(e2)`)은 명세가 아니라 권한 장부이므로 도구가 `New`마다 낸다.

| # | baseline.vpr | 의미 | Kotlin 지점 |
|---|---|---|---|
| 1 | `ISSUE(tok1, k, true, now + timeout)` | 발급, entry 있음 | `val token = nextToken()` 169:21 |
| 2 | `ISSUE(acquired, k, false, now + timeout)` | 발급, 상한 (unbacked) | `acquiredToken.set(nextToken())` 182:21 |
| 3 | `ISSUE(tok2, k, true, now + timeout)` | 발급, 새 entry | `val token = nextToken()` 187:17 |
| 4 | `tok(old_tok).gHeld := false` (tryLock) | 만료 회수 | CAS 161:49의 then 블록 |
| 5 | `tok(e.token).gHeld := false` (unLock) | 해제 | CAS 213:44의 then 블록 + `return@compute null` 219:17 |
| 6 | `tok(old_tok).gHeld := false` (sweep) | 만료 회수 | CAS 104:49의 then 블록 |

spike `PLAN.md`의 판단은 그대로 유지한다: ghost 갱신은 `tHeld`/`tLease`의 뜻을 정하므로, LLM이 쓰게 하면 성질을 공허하게 만들 수 있다.

### 3.2 후보

**(a) 소스의 `//@ ghost` 주석 (VerifiedSCION 방식).** 사람이 위 6곳에 주석을 넣는다.

```kotlin
if (now > existing.expiresAt && existing.isHeld.compareAndSet(true, false)) {
    //@ ghost end(existing.token)
    existing.token = null
```

**(b) sidecar의 라이브러리 사건 규칙.** 신뢰 모델의 사건에 ghost 갱신을 붙인다.

```text
on jdk.AtomicBoolean.compareAndSet(true, false) on LockInfo.isHeld succeeds: end tok(entry.token)
on call nextToken() in action A: issue tok(result) { gKey = k; gLease = now + timeout; gBacked = ? }
```

KVIR 패턴 규칙("`existing.token`으로 흐르는 `nextToken()` 호출 뒤")도 이 부류다. 패턴은 (b)보다 더 코드 모양에 묶이므로 따로 다루지 않는다.

**(c) 효과 기반 규칙 (권고).** ghost 상태는 action의 커밋 지점(compute 본문의 끝 = 선형화 지점)에서만 바뀐다. 갱신 값은 action의 사전 상태, 사후 상태, 연산 결과로 정한다.

```text
witness LockInfo.token                         // END: 사전 상태에서 key의 entry가 가진 토큰을, 사후 entry가 더 이상 갖지 않으면 종료
operation tryLock { issue res { gKey = k; gBacked = carried(S, k, res); gLease = now + timeout } }
                                               // ISSUE: tryLock이 null이 아닌 토큰을 돌려주면 발급
```

action은 원자적이다. 그래서 같은 action 안의 어느 지점에 ghost 갱신을 두어도 다른 스레드가 보는 결과는 같다. 커밋 지점에 두면 코드 지점을 고를 필요가 없다.

### 3.3 실험 (측정)

1. (c)의 목표 출력을 손으로 썼다: `spike/effect_ghost/target.vpr`. KeyLocalLock.kt를 v0 규칙(2절, 5절)대로 내린 모양이다.
2. (a)와 (b)는 같은 파일에서 END 갱신 위치만 바꿔 만들었다. (a)는 then 블록 첫 문장, (b)는 CAS 성공 직후다.
3. Kotlin 변이를 v0 lowering 결과로 옮겨 적용했다: `spike/effect_ghost/mutants.py` (편집마다 메서드 안에서 정확히 한 번 맞아야 한다).
4. 각 파일을 Silicon으로 검증했다 (`--numberOfErrorsToReport 0`). 결과: 0절 표. 원 출력은 scratchpad `effect_mutants.report`에 있다.

관찰:
1. (b)는 대조군 SB5와 SB5b를 거부했다. CAS를 지우면 묶인 사건도 사라진다. 그런데 토큰은 여전히 덮어써지거나 entry가 지워진다. 그래서 MI의 "held backed 토큰 → entry" 연결이 깨진다. 이것은 오경보다. 두 변이는 MI 아래에서 동치이기 때문이다.
2. 동치의 근거도 측정했다. 목표 모델의 분기 10곳에 `refute false`를 넣었다. MI 아래에서 도달할 수 없는 분기는 세 CAS 실패 분기(161:49, 213:44, 104:49)뿐이었다. 나머지 7곳은 도달 가능했다.
3. (c)는 SB6와 E4를 P3a(`ends_only_if`)로 잡는다. (a)는 같은 변이를 MI 연결 conjunct로 잡는다. 둘 다 거부한다.
4. (c)에서 SB4는 커밋 블록의 set-once 검사(`assert !tok(res).gIssued`)로 잡힌다. 진단이 "새 토큰이 이미 발급됨"으로 바로 나온다.
5. (메인 세션 추가 실험) 동등한 리팩터링 대조군 2개도 (c)에서 증명된다. R1: `unLock`이 entry를 지우기 전에 `existing.token = null`을 한다. R2: 만료 인수 때 기존 `LockInfo`를 고치지 않고 새 `LockInfo`를 넣는다. 두 변형은 `effect_ghost/mutants.py`의 `R1_*`, `R2_*`이다.
6. 주의: 이 실험의 Viper는 모두 손으로 옮긴 "예상 emitter 출력"이다. 그래서 M5 수락 테스트 2(생성 모델 = `target.vpr`, 정규화 후)를 M6 시작 조건으로 둔다. ghost 바인딩 결정은 도구 출력에서 한 번 더 확인된 뒤에 그 위에 binder를 만든다.

### 3.4 기준별 비교

| 기준 | (a) `//@ ghost` 주석 | (b) 라이브러리 사건 규칙 | (c) 효과 기반 규칙 |
|---|---|---|---|
| 기존 코드에 쓸 수 있나 (bytecode 불변) | 예. 단, req-shield 소스 파일을 고쳐야 한다. "수정하지 않은 소스"라는 v0 목표와 맞지 않는다 | 예. 소스 불변 | 예. 소스 불변 |
| 변이 견고성 | 측정: 6/6 거부, 대조 2/2 증명 | 측정: 대조 2/2 오경보. 구현 수단(CAS)을 지우는 동치 변경에 깨진다 | 측정: 6/6 거부, 대조 2/2 증명 |
| 코드 변경(리팩터링) 견고성 | 주석이 코드와 함께 움직여야 한다. 새 해제 경로에 주석을 빠뜨리면 오경보(안전). 지운 문장에 붙은 주석은 "고아 주석" 오류로 알려야 한다 | 사건이 새 호출 지점에서 조용히 발동하거나, 동치 리팩터링에서 사라진다 | 규칙이 구현 수단을 언급하지 않는다. 동치 리팩터링은 판정을 바꾸지 않는다. 새 동작은 P1-P3가 판정한다 |
| 버그를 정답으로 재계측할 위험 | 낮음. ghost가 코드를 따라가지 않는다 | 중간. 규칙이 코드 어디서나 발동한다 | ghost의 `gHeld`, `gBacked`가 코드 상태를 따라간다. 그래서 P3a/P3b가 판정을 진다. 완화: 3.6절 |
| 사람이 쓰는 양 | 주석 6개 + ghost schema | 규칙 4-6개 + schema + 라이브러리 사건 어휘 | 규칙 2개(`witness`, `issue`) + schema + 권한 절 2종 |
| LLM 초안 | 가능. 그러나 주석 위치 하나하나가 의미를 정하므로 지점마다 검토가 필요하다 | 가능. 사건 어휘를 알아야 한다 | 가능. 규칙이 프로토콜 수준이고 짧다. `KeyLock` KDoc("Returns an opaque ownership token when acquired")에서 바로 나온다. 검토는 한 번 읽기로 끝난다 |
| 구현 비용 | FIR에는 주석이 없다. 소스 텍스트나 light tree에서 주석을 찾아 KVIR 문장에 붙여야 한다 | 사건 매칭 + 상태 의존 규칙 | 커밋 블록 생성 하나 |

### 3.5 권고: (c) 효과 기반 규칙

규칙의 내용:
1. **END (witness 규칙).** sidecar가 소유 증거 필드를 하나 고른다: `witness LockInfo.token`.
   커밋 지점에서 "사전 상태의 key entry가 가진 토큰 `t0`"를 사후 entry가 더 이상 갖지 않으면 `tok(t0).gHeld := false`다. entry 삭제, 교체, 필드 덮어쓰기가 모두 해당한다.
2. **ISSUE (결과 규칙).** 연산이 돌려준 null 아닌 토큰이 새 획득이다. `KeyLock` 인터페이스의 계약이 바로 이것이다.
   필드 종류에 따라 도구가 값을 정한다.
   - `set-once` 필드(`gIssued`)는 `true`가 되고, 그 전에 `false`였는지 검사한다 (`assert !tok(res).gIssued`).
   - `ends` 필드(`gHeld`)는 `true`가 된다.
   - `frozen` 필드는 sidecar가 값을 준다.
3. **R1 (의도 값은 명세 항에서).** `issue`의 값 식은 action key, 자유 값, 연산 결과, 상수, 산술, `carried(S, k, t)`만 쓸 수 있다. 모듈 상태(사전·사후 필드)를 직접 읽으면 sidecar 오류다.
   예: `gLease = S.m[k].expiresAt`은 거부한다. 이 식을 허용하면 SB3(expiresAt 갱신 누락)를 MI가 보지 못한다.
4. **R2 (파생 값은 권한 절로 제한).** 코드 상태에서 파생하는 ghost 값(`gHeld`의 종료, `gBacked`)마다 "언제 허용되는가"를 연산별 권한 절로 쓴다.
   - `ends_only_if C`: 이 action에서 끝나는 held backed 토큰 `qt`는 `C`를 만족해야 한다 (P3a).
   - `unbacked_only_if C`: 이 연산이 발급한 토큰이 unbacked이면 `C`여야 한다 (P3b).
   - 기본값은 둘 다 `false`다. sidecar가 말하지 않은 action은 어떤 획득도 끝낼 수 없고, unbacked 토큰을 낼 수 없다. 도구가 이 기본 계약을 stray action에도 붙인다.
5. **schema 검사.** 필드 종류에서 HIST(이력 불변)와 set-once 검사를 도구가 만든다. 사람이 HIST를 쓰지 않는다.

### 3.6 (c)의 위험과 완화

| 위험 | 완화 |
|---|---|
| ghost가 코드를 따라가므로, 성질 집합이 빠지면 버그가 통과할 수 있다 | 권한 절의 기본값이 `false`다. 예외는 sidecar가 이름(P3a, P3b)과 함께 명시해야 한다. 보고서는 권한 절을 "검토할 명세" 절에 따로 싣는다 |
| 사람이 너무 넓은 권한 절(`ends_only_if true`)을 쓸 수 있다 | 권한 절은 사람 소유다. LLM은 초안만 쓰고, sidecar의 `reviewed` 표시 없이는 채택하지 않는다 (8절 U7) |
| 소유를 상태로 판정할 수 없는 프로토콜(소유자 없는 permit 수)에는 witness가 없다 | v0 범위 밖이다. `Action.commit`은 일반 `GhostOp` 목록이므로, 나중에 코드 지점 주석을 같은 자리에 넣을 수 있다 |
| 토큰이 연산 결과가 아닌 경로(공유 저장소 등)로 나갈 수 있다 | v0는 발급 출처를 연산 결과로 한정한다. 결과가 아닌 곳에 저장된 토큰은 MI 연결과 P1이 잡는다 (발급 기록 없는 토큰을 가진 entry는 unLock P1을 깬다) |

## 4. sidecar 명세 형식

### 4.1 형식 선택

권고는 작은 전용 DSL이다. 구조는 DSL이 맡고, 식은 Viper 텍스트로 둔다.

| | 전용 DSL | TOML + Viper 문자열 |
|---|---|---|
| 사람 검토 | 명세처럼 읽힌다 (`ends_only_if P3a: ...`) | 배열 표(`[[operation]]`)와 따옴표가 많다 |
| LLM 초안 | 문법이 작다. 문법 오류는 파서가 위치와 함께 알린다 | LLM이 TOML을 잘 안다 |
| 구현 | 파서 약 300줄 (추정). 식은 텍스트 구간으로 저장 | 파서 의존성 하나 |
| 위치 대응 | 식마다 줄·열을 바로 얻는다 | 문자열 안 위치를 다시 계산해야 한다 |

식을 Viper 텍스트로 두는 이유:
1. LLM이 쓰는 `MI_INV`와 같은 어휘를 쓴다.
2. Silicon의 타입 검사가 식을 검사한다. 오류 위치는 sidecar 위치로 돌려 준다 (5.7절).
3. Kotlin 비슷한 명세 언어(`lockMap[k]?.token`)는 v0 범위 밖이다.

### 4.2 문법

```text
file        := { decl }
decl        := "module" FQN
             | "source" STRING                                  // path relative to --root
             | "include" STRING                                 // textual, one level (for variants)
             | "type" NAME "=" ( "opaque" | "template" STRING )
             | "state" NAME ":" ( "Map<" NAME "," NAME ">" | "Counter" ) "=" KNAME
             | "class" NAME "=" KNAME "{" { NAME ":" FTYPE "=" KNAME } "}"
             | "use" MODEL
             | "trusted" "fun" KSIG ( "=" "clock" | { tclause } )
             | "ghost" "record" NAME "(" NAME ")" "{" { NAME ":" TYPE KIND } "}"
             | "witness" NAME "." NAME
             | "operation" NAME "=" ( "fun" KNAME | "compute" "in" KNAME ) "{" { opitem } "}"
             | "invariant" ID ":" VEXPR                         // state property, part of MI
             | "lemma" ID ":" VEXPR                             // state lemma, proven from MI
             | "hole" "MI_INV" TRIPLE_STRING                    // inductive conjuncts, LLM-written
             | "probe" "(" PARAMS ")" [ "requires" VEXPR ] "{" { NAME ":=" NAME "(" ARGS ")" | "assert" VEXPR } "}"
tclause     := "returns" NAME ":" TYPE | "ghost" NAME ":" TYPE | "ensures" VEXPR | "effects" "none" | "reviewed" STRING
opitem      := "free" NAME [ ":" NAME ] { "," NAME } "=" ( "local" KNAME | "param" KNAME | "this." KNAME | "call" NAME )
                                                                // ": NAME" = a `type` name (Key, Token)
             | "result" NAME
             | "issue" NAME "{" NAME "=" VEXPR { ";" NAME "=" VEXPR } "}"
             | "ends_only_if" ID ":" VEXPR                      // reserved name qt = the ending token
             | "unbacked_only_if" ID ":" VEXPR
             | "ensures" ID ":" VEXPR
KIND        := "set-once" | "frozen" | "ends"
FTYPE       := "Semaphore" | "AtomicBoolean" | "Long" | NAME [ "?" ]
VEXPR       := Viper expression text; continuation lines are indented deeper than the first line.
               Inside `issue { ... }` a VEXPR ends at `;` or at the closing `}`.
```

의도적인 제한:
1. 연산에 자유 형식 `requires`를 쓸 수 없다. 전제 조건은 환경을 좁히는 검사되지 않은 가정이기 때문이다. 전제는 신뢰 모델 계약(`rej ==> limit != 0`)과 타입 사실(non-null `String` 파라미터 → `t != NULL`)에서만 온다.
2. `probe`의 `requires`는 시나리오만 좁히고 모델은 좁히지 않으므로 허용한다.
3. 짧은 Kotlin 이름(KNAME)은 모듈 객체, 바깥 클래스, 패키지 순서로 찾는다. 다른 패키지의 함수는 FQN을 쓴다.
4. Kotlin 타입이 `String` 또는 `String?`인 자유 값은 추상 타입을 적어야 한다 (`free t: Token = param token`). 다른 타입의 자유 값에는 적을 수 없다. 이 표기는 2.13의 추론에 고정점으로 들어간다. 코드의 추론과 다르면 sidecar 오류다. 이유: 코드가 그 값을 sidecar 필드와 비교하지 않으면 추론이 타입을 정하지 못한다. "AI가 토큰 비교를 지운" 코드(SB1, SB2)가 바로 그 경우이고, 도구는 그 코드를 FAILED로 보고해야 한다.

### 4.3 신뢰 모델 표 (도구 내장 항목)

내장 항목은 도구 저장소의 `models/jdk.kvm`에 같은 문법으로 둔다. sidecar는 `use`로 쓸 항목을 나열한다. 그래서 검토자는 모듈마다 신뢰 기반을 한 곳에서 본다.

```text
model jdk.ConcurrentHashMap.compute
  match   java.util.concurrent.ConcurrentHashMap.compute(K, java.util.function.BiFunction)
  role    action                      // each call site is one atomic action of the module
  where   receiver is a `state Map`; arg 0 -> action key k; arg 1 is a lambda literal (SAM BiFunction)
  runs    exactly-once, atomically    // Javadoc: "The entire method invocation is performed atomically.
                                      //  The supplied function is invoked exactly once per invocation of this method."
  result  null -> remove k; value parameter -> keep; other object -> put (k, value)
  body    no other access to this map except mappingCount(); no call that may throw
                                      // Javadoc: "The remapping function must not modify this map during computation."
                                      // Javadoc: a throwing function leaves "the mapping is unchanged" (object writes stay)
  source  "Corretto 21.0.10 src.zip, java.base/java/util/concurrent/ConcurrentHashMap.java:1875-1896"

model jdk.ConcurrentHashMap.mappingCount
  inside-action  nondet Long, no contract   // Javadoc: "The value returned is an estimate"
  outside-action racy read

model jdk.ConcurrentHashMap.racyRead
  match   get, containsKey, keys, keySet, size, isEmpty, mappingCount
  outside-action an arbitrary value of the result type (racy read; recorded in the ledger)
  inside-action  unsupported, except mappingCount

model jdk.Semaphore                   // flattened into the owning field as permits: Int
  ctor    Semaphore(kotlin.Int)       : permits := arg0
  op      tryAcquire(): Boolean       = permits > 0 ? { permits := permits - 1; true } : false
  op      release()                   : permits := permits + 1      // no upper bound
  throws  none
  source  "Semaphore.java: 'There is no requirement that a thread that releases a permit must have acquired that permit'"

model jdk.AtomicBoolean               // flattened: Bool
  ctor    AtomicBoolean(kotlin.Boolean)                     : v := arg0
  op      compareAndSet(kotlin.Boolean, kotlin.Boolean): Boolean = v == arg0 ? { v := arg1; true } : false
  op      set(kotlin.Boolean)                               : v := arg0
  op      get(): Boolean                                    = v

model jdk.AtomicLong                  // a module `state ... : Counter`
  ctor    AtomicLong()                : v := 0
  ctor    AtomicLong(kotlin.Long)     : v := arg0
  op      incrementAndGet(): Long     = { v := v + 1; v }           // ASSUMPTION: Long unbounded
  op      get(): Long                 = v

model jdk.AtomicCell                  // non-escaping local AtomicReference<T> or AtomicBoolean (section 2.5)
  ctor    AtomicReference(V), AtomicBoolean(kotlin.Boolean) : cell := arg0
  op      set(T): cell := arg0;  get(): T = cell

model jdk.System.currentTimeMillis
  match   java.lang.System.currentTimeMillis()
  role    clock                       // an arbitrary Long; no monotonicity assumed

model slf4j.Logger
  match   org.slf4j.Logger.{trace, debug, info, warn, error, isTraceEnabled}
  role    no effect on modeled state; arguments must be pure
```

### 4.4 coroutine `KeyLocalLock`의 전체 sidecar

파일 이름 제안: `specs/reqshield/keylocallock.coroutine.kv`.

```text
// Sidecar spec for req-shield KeyLocalLock (core-kotlin-coroutine). Format: kv-sidecar/0.
// Expressions are Viper text over the vocabulary in DESIGN-v0.md section 4.6.

module com.linecorp.cse.reqshield.kotlin.coroutine.KeyLocalLock.Companion
source "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLocalLock.kt"
source "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLock.kt"

// ---- Abstract types for String values ----
type Key   = opaque                     // compute keys: lockKeyOf(key, lockType)
type Token = template "local-${Long}"   // "local-" + n -> n (injective); null -> NULL

// ---- Module state ----
state m:   Map<Key, LockInfo> = lockMap
state seq: Counter            = tokenSequence

// ---- Classes reachable from the state ----
class LockInfo = KeyLocalLock.LockInfo {
  permits:   Semaphore     = semaphore
  isHeld:    AtomicBoolean = isHeld
  token:     Token?        = token
  expiresAt: Long          = expiresAt
}

// ---- Trusted models (built-in, section 4.3) ----
use jdk.ConcurrentHashMap.compute
use jdk.ConcurrentHashMap.mappingCount
use jdk.ConcurrentHashMap.racyRead
use jdk.Semaphore
use jdk.AtomicBoolean
use jdk.AtomicLong
use jdk.AtomicCell
use jdk.System.currentTimeMillis
use slf4j.Logger

// ---- Trusted project functions (bodies read by a human) ----
trusted fun com.linecorp.cse.reqshield.support.utils.nowToEpochTime() = clock
  reviewed "support/.../utils/TimeUtils.kt:23: fun nowToEpochTime() = System.currentTimeMillis()"
trusted fun com.linecorp.cse.reqshield.support.config.LocalLockLimit.rejectsNewEntry(kotlin.Long)
  returns rej: Bool
  ghost   limit: Int                    // the cap the call read
  ensures rej ==> limit != 0
  effects none                          // rejectionCount and logging are outside the model
  reviewed "support/.../config/LocalLockLimit.kt:86-101: returns false when limit == UNLIMITED_LOCK_ENTRIES (0L) or currentSize < limit"

// ---- Ghost state ----
ghost record tok(Token) {
  gIssued: Bool set-once                // tryLock returned this token
  gKey:    Key  frozen                  // key the token was issued for
  gBacked: Bool frozen                  // a map entry carried the token when it was issued
  gHeld:   Bool ends                    // the acquisition has not ended
  gLease:  Int  frozen                  // clock reading at issue + lockTimeoutMillis
}
witness LockInfo.token                  // a backed token is held while its key's entry carries it

// ---- Operations: exactly one atomic action each ----
operation tryLock = fun KeyLocalLock.tryLock {
  free now        = local now           // nowToEpochTime(), read before compute
  free timeout    = this.lockTimeoutMillis
  free rej, limit = call rejectsNewEntry
  result res
  issue res { gKey = k; gBacked = carried(S, k, res); gLease = now + timeout }
  ensures P1a: res != NULL ==> !old(tok(res).gIssued) && tok(res).gIssued &&
      tok(res).gKey == k && tok(res).gHeld && tok(res).gLease == now + timeout
  ensures P1a_entry: res != NULL && tok(res).gBacked ==> has(S, k) && S.m[k].token == res
  ends_only_if P3a: now > old(tok(qt).gLease)
  unbacked_only_if P3b: !old(has(S, k)) && rej && limit != 0
  ensures progress_free: !old(has(S, k)) && !rej ==> res != NULL && tok(res).gBacked
  ensures progress_live: old(has(S, k)) && now <= old(S.m[k].expiresAt) ==> res == NULL
}

operation unLock = fun KeyLocalLock.unLock {
  free t: Token = param token
  result res
  ensures P1: res ==> old(has(S, k)) && old(S.m[k].token) == t && old(tok(t).gIssued) &&
      old(tok(t).gKey) == k && old(tok(t).gBacked) && old(tok(t).gHeld) && !tok(t).gHeld && !has(S, k)
  ends_only_if P3a: qt == t && res
  ensures progress_owner: old(has(S, k)) && old(S.m[k].token) == t ==> res
}

operation sweep = compute in KeyLocalLock.Companion {
  free now = local now                  // System.currentTimeMillis(), once per sweep pass
  ends_only_if P3a: now > old(tok(qt).gLease)
}

// ---- State properties ----
invariant P2: forall qr: Ref :: { qr in S.created } qr in S.created ==> 0 <= qr.permits && qr.permits <= 1
lemma P3c: forall t1: Int, t2: Int :: { tok(t1).gHeld, tok(t2).gHeld }
    tok(t1).gIssued && tok(t2).gIssued && tok(t1).gBacked && tok(t1).gHeld &&
    tok(t2).gBacked && tok(t2).gHeld && tok(t1).gKey == tok(t2).gKey ==> t1 == t2

// ---- Inductive invariant: written by an LLM (E3 workflow), checked by the tool ----
hole MI_INV """
  // INV-E: every entry is held, has no free permit, and carries a token.
  (forall qk: Key :: { S.m[qk] } has(S, qk) ==>
     S.m[qk].permits == 0 && S.m[qk].isHeld && S.m[qk].token != NULL) &&
  // The counter is never negative, and every issued token is between 0 and the counter.
  0 <= S.seq &&
  (forall qt: Int :: { tok(qt).gIssued } tok(qt).gIssued ==> 0 <= qt && qt <= S.seq) &&
  // Each entry's token is a held backed token issued for this key, with the entry's lease.
  (forall qk: Key :: { S.m[qk] } has(S, qk) ==>
     tok(S.m[qk].token).gIssued && tok(S.m[qk].token).gKey == qk &&
     tok(S.m[qk].token).gBacked && tok(S.m[qk].token).gHeld &&
     tok(S.m[qk].token).gLease == S.m[qk].expiresAt) &&
  // Each held backed token is the token of its key's entry.
  (forall qt: Int :: { tok(qt).gHeld }
     tok(qt).gIssued && tok(qt).gBacked && tok(qt).gHeld ==>
       has(S, tok(qt).gKey) && S.m[tok(qt).gKey].token == qt)
"""

// ---- Non-vacuity probe: a scenario from init ----
probe(k: Key, now: Int, timeout: Int) requires timeout > 0 {
  t1 := tryLock(k, now = now, timeout = timeout, rej = false, limit = 0)
  assert t1 != NULL && tok(t1).gBacked
  t2 := tryLock(k, now = now, timeout = timeout, rej = false, limit = 0)   // live entry
  assert t2 == NULL
  ok := unLock(k, t = t1)
  assert ok
  ok2 := unLock(k, t = t1)                                                 // stale release
  assert !ok2
}
```

참고: 이 `hole` 내용은 spike `baseline.vpr`의 `MI_INV`에서 두 conjunct(map 값은 할당된 객체, key끼리 객체를 공유하지 않음)를 뺀 것이다. 두 conjunct는 도구가 `MI_OWN`으로 만든다 (5.3절).

### 4.5 core 변형과의 차이

core sidecar는 위 파일과 4줄만 다르다. 나머지 선언(타입, `class`, `use`, 신뢰 함수, ghost, 연산, 성질, hole, probe)은 그대로 맞는다.
근거: 짧은 이름 `KeyLocalLock.LockInfo`, `KeyLocalLock.tryLock`, `KeyLocalLock.Companion`, `local now`, `param token`, `this.lockTimeoutMillis`가 두 변형에 모두 있다.
sweep 선택자 `compute in KeyLocalLock.Companion`은 coroutine에서는 `ensureMonitorStarted`의 compute를, core에서는 `startMonitoring`의 compute를 찾는다.

```diff
-module com.linecorp.cse.reqshield.kotlin.coroutine.KeyLocalLock.Companion
-source "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLocalLock.kt"
-source "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLock.kt"
-state seq: Counter            = tokenSequence
+module com.linecorp.cse.reqshield.KeyLocalLock.Companion
+source "core/src/main/kotlin/com/linecorp/cse/reqshield/KeyLocalLock.kt"
+source "core/src/main/kotlin/com/linecorp/cse/reqshield/KeyLock.kt"
+state seq: Counter            = tokenCounter
```

`include`를 쓰면 공통 부분을 `keylocallock.common.kv` 하나에 두고, 변형 파일은 `include` 1줄과 위 4줄이 된다.

도구가 두 변형에서 다르게 처리하는 점 (sidecar에는 드러나지 않음):

| | coroutine | core |
|---|---|---|
| counter 초기화식 | `AtomicLong()` → 0 | `AtomicLong(0)` (`const Long 0`) → 0 |
| 새 entry의 token | 생성자 인자 `token = token` | 기본값 `null`, 뒤에 `newLock.token = token` (209행) |
| sweep 위치 | `launch` 람다 → `while` → `try` → `forEach` 람다 | `scheduleWithFixedDelay`의 `Runnable` SAM 람다 → `try` → `forEach` 람다 |
| action 밖 경쟁 읽기 | `lockMap.keys` | `lockMap.keys`, `lockMap.size` 2곳 (값은 로그에만 쓰임) |
| key 계산 | `lockKeyOf` (KeyLock.kt) | `buildLockKey` (private method) |

### 4.6 sidecar 식의 어휘

| 이름 | 뜻 |
|---|---|
| `S` | 모듈 객체. `S.m`, `S.seq`는 sidecar `state` 이름. `S.created`는 도구가 만든 할당 집합 |
| `k` | action key (`Key`) |
| `res` | 연산 결과 (`result`로 이름을 바꿀 수 있음) |
| `free`로 선언한 이름 | 자유 값 (메서드 파라미터) |
| `qt` | `ends_only_if`에서 끝나는 토큰 |
| `NULL` | `Token?`의 null (-1) |
| `has(S, k)` | key `k`에 entry가 있다 |
| `carried(S, k, t)` | key `k`의 entry가 witness 필드에 `t`를 가진다 |
| `tok(t).<field>` | 토큰 `t`의 ghost 기록 |
| `old(e)` | action 사전 상태의 값 |

이름 충돌 규칙: Viper는 지역 변수와 필드가 같은 이름이면 거부한다 (측정: `duplicate identifier`). 그래서 sidecar 이름은 모델 필드, ghost 필드, 도구 macro, 예약 이름(`S`, `k`, `qt`, `qk`, `qr`, `NULL`)과 겹치면 안 된다. resolver가 sidecar 위치와 함께 거부한다.

## 5. KVIR → Viper 출력

### 5.1 파일 구조

목표 출력은 `spike/effect_ghost/target.vpr`이다. 순서는 `baseline.vpr`과 같다.

1. 머리 주석: 소스, sidecar, 도구 버전, 가정 요약
2. 도메인: 불투명 타입(`domain Key {}`), ghost 기록 색인(`domain TokRec`, 단사 공리)
3. `define NULL (-1)`
4. 필드: 모듈 상태, `created`, ghost 기록 필드, 평탄화한 클래스 필드
5. macro: `has`, `carried`, `GHOST_PERMS`, `MI_PERMS`, `MI_OWN`, `invariant` 성질(P2), hole 조각 `MI_INV_1..n`과 그 곱 `MI_INV`, `MI`, `HIST`. 권한 절은 macro가 아니라 `ensures` 식으로 낸다
6. `method init()`
7. 연산마다 action 메서드 1개
8. stray action마다 기본 계약 메서드 1개
9. `lemma`마다 메서드 1개
10. `method probe(...)`
11. (선택, M9) 별도 파일: 분기 도달성 검사

`baseline.vpr`과의 차이:
1. ghost 갱신이 본문 안이 아니라 메서드 끝의 커밋 블록에 있다.
2. `MI_OWN`(2개 conjunct)을 도구가 만든다.
3. 템플릿마다 `assert 0 <= n`, 발급마다 `assert !tok(res).gIssued`가 있다.
4. `return@compute null`은 항상 map을 쓴다.
5. Kotlin 지역 이름은 `<name>__<line>`이다.

### 5.2 action 메서드 템플릿

```text
// Action <op>: lockMap.compute @<pos>, operation <kotlin fun> @<pos>
//   <free value>: <origin> (one line per free value)
method <op>(S: Ref, k: Key, <named free values...>) returns (<result>: <T>)
  requires MI(S)
  requires <trusted contracts of named Nondet values>        // rej ==> limit != 0
  requires <type facts>                                       // t != NULL for a non-null String parameter
  ensures MI_PERMS(S)
  ensures MI_OWN(S)
  ensures P2(S)                                               // one clause per sidecar invariant
  ensures MI_INV_1(S)  ...  ensures MI_INV_n(S)               // hole split on top-level &&
  ensures HIST
  ensures <ends_only_if>                                      // default: false
  ensures <unbacked_only_if>                                  // issuing operations; default: false
  ensures <sidecar ensures>                                   // one clause per property ID
{
  var <entry>: Ref := has(S, k) ? S.m[k] : null
  var pre_tok: Int := <entry> != null ? <entry>.<witness> : NULL // ghost snapshot for END
  var <cell>: <T> := <init>                                       // per cell
  var <unnamed free value>: <T>                                   // arbitrary
  <lowered body>
  <result> := <operation result expression>
  // commit point: effect-bound ghost rules
  if (pre_tok != NULL && !carried(S, k, pre_tok)) {
    tok(pre_tok).gHeld := false
  }
  if (<result> != NULL) {                           // only for an operation with an `issue` rule
    assert !tok(<result>).gIssued                   // set-once field
    tok(<result>).<field> := <value>                // one line per ghost field, in declaration order:
                                                    // set-once true, frozen = issue value, ends true
  }
}
```

권한 절의 컴파일 (`C`는 괄호로 감싼다):
- `ends_only_if C` → `ensures forall qt: Int :: { tok(qt).gHeld } old(tok(qt).gIssued) && old(tok(qt).gBacked) && old(tok(qt).gHeld) && !tok(qt).gHeld ==> (C)`
- `unbacked_only_if C` → `ensures <result> != NULL && !tok(<result>).gBacked ==> (C)`

순서 규칙: `ensures`는 위 템플릿 순서이고, 같은 종류 안에서는 sidecar 선언 순서다. ghost 필드(`GHOST_PERMS`, `HIST`, 발급 대입)와 `new`의 필드 초기화는 sidecar 선언 순서다. `init`은 `state` 필드마다 초기값 `ensures`를 하나씩 낸다. `carried(S, qk, qt)`는 `has(S, qk) && S.m[qk].<witness> == qt`인 macro다.

이 두 식은 `baseline.vpr`의 P3a(`P3A_EXPIRY`, unLock의 P3a), P3b와 같은 식이다 (괄호만 다르다).

KVIR 문장의 출력:

| KVIR | Viper |
|---|---|
| `Let(v, e)` | `var v: T := e` |
| `Assign(v, e)` | `v := e` |
| `FieldWrite(o, f, e)` | `o.f := e` |
| `ModelOp(compareAndSet, o.isHeld, [a, b], out)` | `out := o.isHeld == a` 그리고 `if (out) { o.isHeld := b }` |
| `ModelOp(tryAcquire, o.permits, [], out)` | `out := o.permits > 0` 그리고 `if (out) { o.permits := o.permits - 1 }` |
| `ModelOp(release, o.permits)` | `o.permits := o.permits + 1` |
| `ModelOp(set, o.isHeld, [e])` | `o.isHeld := e` |
| `ModelOp(incrementAndGet, seq, [], out)` | `S.seq := S.seq + 1` 그리고 `var out: Int := S.seq` |
| `ModelOp(get, seq, [], out)` | `var out: Int := S.seq` |
| `New(v, LockInfo, init)` | `var v: Ref := new(fields)`, `S.created := S.created union Set(v)`, 필드마다 초기값 |
| `Nondet` (이름 있음) | 메서드 파라미터 + `requires` 계약 |
| `Nondet` (이름 없음) | `var o: T` 다음에 `inhale <계약>` |
| `If` | `if (c) { ... } else { ... }` |
| `Exit(Keep)` | 출력 없음 |
| `Exit(Remove)` | `S.m := S.m[k := null]` |
| `Exit(Put(e))` | `S.m := S.m[k := e]` |
| `TokenOf(n)` | `assert 0 <= n` 다음에 `n` |
| `Arith(+)` | `a + b` (가정 진단은 보고서에) |

### 5.3 도구가 만드는 권한·소유 conjunct

1. `GHOST_PERMS`: ghost record의 모든 필드에 대한 `Int` 전체의 양화 권한. 측정된 spike 교훈대로 ghost는 `Map` 값이 아니라 토큰 값마다 객체 하나(`tok(t)`)로 낸다.
2. `MI_PERMS(S)`: 모듈 상태 필드, `created`, `GHOST_PERMS`, `created`의 모든 객체의 평탄화 필드 권한.
3. `MI_OWN(S)`: map 값은 `created`에 있다. 서로 다른 key는 같은 객체를 갖지 않는다.
   - 첫 conjunct가 없으면 action 본문이 entry 필드를 읽을 권한을 얻지 못한다. 그래서 이것은 도구의 일이다.
   - 둘째 conjunct는 cross-key 축소(PHASE1 신뢰 모델 3)가 기대는 "key마다 객체가 따로" 사실이다. 도구가 만들면 이 부분이 논증이 아니라 증명이 된다.
   - spike E3에서 5번 중 4번의 LLM 실행이 둘째 conjunct를 생략했다 (다른 conjunct에서 따라 나오기 때문). 도구가 만들어도 손해가 없다.
4. `HIST`: `frozen` 필드는 발급 뒤 불변, `set-once`는 true 유지, `ends`는 false가 되면 false 유지.

### 5.4 init과 probe 생성

1. `init`: 모듈 객체를 할당하고 `state` 초기값(빈 map, counter 초기화식 값), 빈 `created`를 대입한다. ghost 기록은 `inhale GHOST_PERMS && (forall qt :: !tok(qt).gIssued)`로 할당한다 (spike 가정 7, 신뢰). `ensures MI(S)`.
2. `lemma`: `requires MI(S) ensures MI(S) ensures <식>`, 본문 없음.
3. `probe`: sidecar 시나리오를 Viper로 옮긴다. 이름 붙은 인자를 연산의 파라미터 순서로 바꾼다. 이름 없는 자유 값은 시나리오에 나오지 않는다.
   - probe의 단언은 연산의 진행 조건(`ensures progress_*`)으로만 증명된다. 진행 조건은 사람이 sidecar에 쓴다. 도구가 만들지 않는다. 진행 조건이 없으면 probe는 공허해질 수 있으므로, 도구는 probe가 쓰는 연산에 `progress_*`가 하나도 없으면 경고한다.
4. 선택(M9): 분기 도달성. action 메서드를 복제하고 분기마다 `refute false`를 넣은 별도 파일을 만든다.
   - 결과는 INFO다. 판정에 영향을 주지 않는다.
   - 측정: KeyLocalLock coroutine에서 도달 불가로 나온 분기는 세 CAS 실패 분기뿐이다. 이 보고는 SB5, SB5b가 왜 동치인지 사용자에게 바로 보여 준다.
5. spike 교훈: init과 probe는 귀납성을 확인하지 않는다. 귀납성은 action 메서드의 증명이 확인한다. 보고서는 이 사실을 판정 옆에 적는다.

### 5.5 이름 규칙

| 대상 | 이름 |
|---|---|
| 모듈 상태, 클래스 필드, ghost 필드 | sidecar 이름 (`m`, `seq`, `permits`, `gHeld`) |
| Kotlin 지역 변수, 람다 파라미터 | `<name>__<line>` (같은 줄에 둘이면 `<name>__<line>_<col>`). 예: `token__169`, `token__187` |
| 도구 임시 변수 | `cond_<line>_<col>`, `n_<line>_<col>`, `pre_tok` |
| 이름 있는 자유 값 | sidecar 이름 |
| 이름 없는 자유 값 | `<name>__<line>` (예: `sawMatch__205`) |
| macro의 묶인 변수 | `qk`, `qt`, `qr` (sidecar 이름으로 쓸 수 없음) |
| stray action 메서드 | `stray_<둘러싼 함수>_<compute 줄>` |

이유: FIR에서 지역 이름은 한 함수 안에서 반복된다 (tryLock의 `val token` 두 개). Viper는 지역 변수와 필드의 이름 충돌을 거부한다 (unLock 파라미터 `token`과 필드 `token`).

### 5.6 Silicon 실행

1. 실행: `java -Xss128m -cp viperserver.jar viper.silicon.SiliconRunner --z3Exe <z3> --numberOfErrorsToReport 0 <file>` (spike `silicon.sh`와 같음).
2. 시간 제한: 메서드당 60 s 기준(E1). 기본은 파일 전체 1회 실행이다 (목표 모델 4.17 s, 측정). 시간 측정이 필요하면 `--includeMethods`로 메서드별 병렬 실행을 한다.
3. 성공 조건: 종료 코드 0, 그리고 출력에 `Silicon finished verification successfully`가 있다.
4. 실패 조건: 종료 코드 1, 그리고 `Silicon found N error(s)` 요약과 오류 줄이 있다.
5. 그 밖은 모두 ERROR다: 다른 종료 코드, 시간 초과, 요약 줄 없음, `aborted`, Java 예외 출력. ERROR이면 어떤 action도 PROVEN으로 보고하지 않는다. SnaKt는 Silicon 중단을 종료 코드 0으로 넘겼다. 이 규칙은 그 결함을 막는다.

### 5.7 Silicon 오류 → Kotlin 위치

emitter는 Viper 텍스트를 만들면서 위치 표를 쓴다: `(vprLine, vprColStart, vprLineEnd, vprColEnd) → 출처`.
출처는 `kotlin(file, line, col)`, `sidecar(file, line, col, id)`, `generated(what)` 중 하나다.

대응 절차:
1. 오류 줄을 정규식으로 읽는다: 메시지, 파일, `@L.C--L2.C2`.
2. 오류 시작 위치를 포함하는 가장 안쪽 범위를 찾는다.
3. 출처 종류에 따라 보고한다.

| Silicon 메시지 | 범위 | 보고 |
|---|---|---|
| `Postcondition of <m> might not hold` | `ensures` 절 한 줄 | sidecar 성질 ID와 위치, 또는 `MI_INV_i`의 앞 주석, 또는 `HIST`. action의 Kotlin 위치를 함께 적는다 |
| `Assert might fail` | 도구가 낸 `assert` | 템플릿 부수 조건이면 Kotlin 템플릿 위치. set-once이면 sidecar `issue` 위치와 action 위치. probe이면 sidecar probe 줄 |
| `Assignment might fail`, `insufficient permission` | 본문 문장 | Kotlin 문장 위치. 원인은 보통 `MI_OWN`이나 frontend 결함이다 |
| `Refute holds in all cases` (M9) | `refute` | INFO: 도달할 수 없는 Kotlin 분기 |
| 타입·파싱·일관성 오류 (`duplicate identifier` 등) | sidecar 식 또는 출력 | sidecar 위치. 출력 쪽이면 도구 결함. 판정은 ERROR |

측정한 근거:
1. `define` macro로 감싼 사후조건이 실패하면, Silicon은 macro 안 conjunct가 아니라 `ensures` 줄 전체의 위치를 준다. spike SB3 출력이 `ensures MI(S) && HIST` 줄(129.11--129.24)을 가리켰다.
2. 그래서 emitter는 성질마다 `ensures` 줄을 따로 낸다. hole은 최상위 `&&`에서 나눠 `MI_INV_1..n`으로 낸다.
3. 나눈 파일에서 SB3 오류는 `ensures MI_INV_4(S)`(entry → 토큰 기록 연결, lease 포함)를 가리켰다. 검증 시간은 4.07 s로 같았다 (측정).

### 5.8 보고 형식과 판정 규칙

```text
KeyLocalLock (core-kotlin-coroutine)                   module: PROVEN
  action tryLock  KeyLocalLock.kt:155:9   PROVEN
  action unLock   KeyLocalLock.kt:209:9   PROVEN
  action sweep    KeyLocalLock.kt:97:37   PROVEN
  init PROVEN · lemma P3c PROVEN · probe PROVEN
Spec to review: ends_only_if tryLock P3a, unLock P3a, sweep P3a; unbacked_only_if tryLock P3b; issue tryLock
Assumptions (not checked by Silicon):
  Long unbounded: KeyLocalLock.kt:80:52, 172:42, 188:54
  Free values: k <- completeKey 149:9, now <- nowToEpochTime 150:19, timeout <- lockTimeoutMillis, rej/limit <- rejectsNewEntry 181:21 (trusted contract) ...
  Trusted models: jdk.ConcurrentHashMap.compute, ... ; project: nowToEpochTime, rejectsNewEntry (reviewed ...)
Racy reads (modeled as arbitrary values): lockMap.keys 96:33
Not modeled (no module-state access): ensureMonitorStarted 82:9 (launch, while, try, delay), stopMonitoring 128:9, cancel 230:5, ...
Unsupported: none
Branch reachability (INFO, not part of any verdict): 25 branch(es) probed, 3 unreachable
  INFO unreachable branch: tryLock KeyLocalLock.kt:161:49 compareAndSet fails  (also unLock 213:44, sweep 104:49)
```

| 대상 | 판정 |
|---|---|
| action | PROVEN (그 메서드에 오류 없음, 실행 정상 종료), FAILED (오류 1개 이상), UNSUPPORTED (출력 안 함), ERROR (실행 비정상) |
| 모듈 | PROVEN: 모든 action, init, lemma, probe가 PROVEN. FAILED: 하나라도 FAILED. INCOMPLETE: FAILED는 없고 UNSUPPORTED가 있음. ERROR: 실행 비정상 |

INCOMPLETE 규칙의 이유: 모델링하지 않은 action이 MI를 깰 수 있다. 그래서 다른 action의 증명은 "모델링한 action만 있을 때"의 증명일 뿐이다.

## 6. 변이의 흐름

Kotlin 편집은 coroutine 변형 기준이다. core는 같은 문자열을 쓴다 (SB4만 `tokenCounter`). 결과는 3.3절 실험의 (c) 열이다 (측정).

| 변이 | Kotlin 편집 | KVIR 변화 | Viper 변화 | 결과 | 실패한 의무 |
|---|---|---|---|---|---|
| SB1 | 213행 조건에서 `existing.token == token &&` 삭제 | `cond_213_17`이 CAS 결과만 받음 | `if (existing.token == t)` 감싸기 사라짐 | FAILED | unLock P3a (`ends_only_if`) |
| SB2 | 204행 뒤에 `val sawMatch = lockMap[completeKey]?.token == token` 추가, 조건을 `sawMatch && ...`로 | 이름 없는 자유 값 `sawMatch` (출처 `RacyRead`) | `var sawMatch__205: Bool` (임의 값), 조건이 이것을 씀 | FAILED | unLock P3a (`ends_only_if`), progress_owner |
| SB3 | 172행 `existing.expiresAt = now + lockTimeoutMillis` 삭제 | `FieldWrite(expiresAt)` 사라짐 | 대입 1줄 사라짐 | FAILED | MI (hole conjunct 4: entry ↔ 토큰 기록 lease 연결) |
| SB4 | 80행 `incrementAndGet()` → `get()` | 인라인한 helper 3곳에서 `ModelOp(incrementAndGet)` → `ModelOp(get)` | `S.seq := S.seq + 1` 3줄 사라짐 | FAILED | 커밋 블록 set-once 검사 `assert !tok(res).gIssued` |
| SB6 | 168행 `existing.semaphore.tryAcquire()` → `true` | `ModelOp(tryAcquire)` 사라짐, `If(true)` | `if (true)` | FAILED | MI (INV-E), P3a |
| E4 | 151행 뒤에 `val sawEntry = lockMap.containsKey(completeKey)` 추가, 156행 `if (sawEntry && existing != null)` | 이름 없는 자유 값 `sawEntry`, 조건 `And` | `var sawEntry__152: Bool`, 조건 변경 | FAILED | P3a, P3b |
| SB5 (대조) | 161행 `&& existing.isHeld.compareAndSet(true, false)` 삭제 | CAS `ModelOp` 사라짐 | `if (now > existing.expiresAt)` | PROVEN | — |
| SB5b (대조) | 104행 `lockInfo.isHeld.compareAndSet(true, false)` → `true` | CAS `ModelOp` 사라짐 | `if (true)` | PROVEN | — |

변이마다 (c)가 판정을 지키는 이유:
1. SB1, SB2: END 규칙은 "entry가 사라졌다"는 효과로 발동한다. 편집은 이 효과를 바꾸지 않는다. 그래서 다른 보유자의 토큰이 끝나고, unLock의 `ends_only_if P3a: qt == t && res`가 거부한다. P1(`old(S.m[k].token) == t`)도 거짓이지만 보고되지 않는다: Silicon은 경로마다 처음 실패한 사후조건만 보고하고, 5.2 템플릿은 권한 절을 sidecar `ensures`보다 앞에 둔다 (구현 agent의 측정, 메인 세션은 재현하지 않음: 순서를 바꾸면 SB1은 P1로 보고된다). SB2의 새 값은 이름이 없어 sidecar와 probe를 고치지 않아도 된다.
2. SB3: `gLease`는 명세 항 `now + timeout`에서 온다 (R1). 코드가 쓰는 `expiresAt`에서 오지 않는다. 그래서 MI의 lease 연결이 깨진다.
3. SB4: 발급 규칙은 돌려준 값에 묶인다. 같은 값이 다시 나오면 set-once 검사가 깨진다.
4. SB6, E4: END 규칙이 밀려난 보유자의 종료를 기록한다. `ends_only_if` P3a가 "만료 전 종료"를 거부한다. E4는 상한 분기가 entry를 지우며 unbacked 토큰을 내므로 P3b도 깨진다.
5. SB5, SB5b: END 규칙은 CAS가 아니라 witness 필드의 변화에 묶인다. 토큰은 그대로 바뀌거나 entry가 지워지므로, ghost 전이는 원본과 같다.

변이 스크립트(M8)의 규칙:
1. 편집은 `.kt` 파일에 대한 정확한 문자열 치환이다. 각 치환은 정확히 한 번 맞아야 한다 (`spike/keylocallock/mutants.py`와 같은 방식).
2. 변이 소스는 임시 디렉터리의 복사본에 만든다. req-shield 작업 트리는 건드리지 않는다.
3. 변이가 컴파일되지 않으면 스크립트 오류다. 판정으로 세지 않는다.
4. 기대 판정: SB1, SB2, SB3, SB4, SB6, E4는 FAILED. SB5, SB5b와 두 원본은 PROVEN. UNSUPPORTED나 ERROR가 하나라도 나오면 스크립트가 실패한다.
5. SB2와 E4는 줄을 하나 더하므로, 그 뒤의 Kotlin 위치가 한 줄씩 밀린다.

## 7. 구현 계획

feature-developer가 순서대로 구현한다. 각 마일스톤은 수락 테스트가 통과해야 끝난다. 추정은 FIR과 Viper를 아는 사람 엔지니어 1명 기준이다.

| M | 내용 | 수락 테스트 | 추정 (인일) |
|---|---|---|---|
| M0 | `translator/` Gradle 프로젝트 (Kotlin 2.4.21, `frontend-probe` 등록 방식 재사용), CLI `kv`, in-process 컴파일 fixture 하네스 | 1. `kv discover`가 coroutine에서 compute 3개(97:37, 155:9, 209:9), core에서 3개(109:25, 173:9, 229:9)를 찾는다. 2. fixture 하네스가 Kotlin 조각을 컴파일해 KVIR을 돌려준다 | 0.5-1 |
| M1 | KVIR 데이터 모델, 검사기(W1-W14), 출력기 | 규칙마다 올바른 KVIR 1개와 틀린 KVIR 1개. 검사기가 틀린 것을 규칙 ID와 함께 거부한다. 손으로 만든 tryLock KVIR의 출력이 golden과 같다 | 1-2 |
| M2 | sidecar 파서·resolver | 두 KeyLocalLock sidecar를 읽고 모든 이름을 FIR symbol에 연결한다. 오류 테스트: 없는 Kotlin 필드, 이름 충돌(`free token`), 선택자 모호(compute 2개), 없는 신뢰 모델, `hole` 누락, R1 위반(`gLease = S.m[k].expiresAt`). 모두 sidecar 줄·열을 보고한다 | 2-3 |
| M3 | action lowering (2.2-2.11절), 신뢰 모델 표 | 1. 두 변형 × 3 action의 golden KVIR. 2. 구문별 fixture(정상 1 + unsupported 1): if/else, 결과 위치의 `when`, 조기 `return@compute`, 부수 효과 있는 `&&`, smart cast(stable/unstable), Ref·Token null 검사, `Long +`(가정 진단), `Long *`(unsupported), 템플릿(맞음·접두어 다름·부분 2개), 셀(정상·탈출), 생성자 기본값, helper 인라인(정상·재귀), action 안 `try`, action 안 반복문, 모르는 호출, 캡처한 `var`에 대입. 3. unsupported는 구문 이름과 Kotlin 위치를 내고 action을 unsupported로 만든다 | 4-6 |
| M4 | 모듈 감사, ledger, 추상 타입 추론 | 1. KeyLocalLock ledger의 경쟁 읽기 목록이 2.14절 표와 같다. 2. fixture: action 밖 쓰기(`lockMap[k] = x`) → unsupported, 모듈 INCOMPLETE. 3. ledger 완전성: FIR 노드 수가 맞는다. 4. `String`을 `Key`와 `Token`으로 함께 쓰는 fixture → unsupported | 2-3 |
| M5 | emitter, Silicon 실행기, 오류 대응, 보고서 | 1. 생성한 coroutine 모델이 검증되고 메서드당 60 s 안이다. 2. 생성 모델이 `spike/effect_ghost/target.vpr`과 이름·주석을 정규화하면 같다. 3. 틀린 성질을 넣은 sidecar → FAILED, sidecar ID와 Kotlin action 위치 보고. 4. 틀린 hole conjunct → 그 conjunct의 주석 이름 보고. 5. sidecar 식 타입 오류 → ERROR + sidecar 위치. 6. 강제 시간 초과 → ERROR, PROVEN 없음. 7. unsupported action fixture → 모듈 INCOMPLETE | 3-5 |
| M6 | ghost binder (END, ISSUE, 권한 기본값, set-once, HIST) | 1. 이름 없는 compute를 하나 더한 fixture(조건 없이 entry 삭제) → 기본 `ends_only_if false`로 FAILED. 2. 같은 토큰을 다시 발급하는 fixture → set-once 검사 실패. 3. 생성한 HIST가 목표 모델의 HIST와 같다 | 1-2 |
| M7 | `MI_INV` hole 흐름 (skeleton 출력, hole 검사, 채택, 진단용 분할) | 1. `MI_INV = true` skeleton은 검증에 실패한다. 2. hole 검사기가 hole 밖 편집과 금지어(`assume`, `inhale` 등)를 거부한다 (spike `e3_harness.py` 테스트 이식). 3. 4.4절 hole을 채택하면 검증된다 | 1 |
| M8 | Kotlin 변이 스크립트, 두 변형 end-to-end | PHASE1 완료 조건 1-5. 6절 표의 판정이 모두 나온다 | 1-2 |
| M9 | 분기 도달성 보고 (`kv verify`가 항상 실행, INFO) | coroutine에서 도달 불가 분기가 정확히 161:49, 213:44, 104:49의 CAS 실패 분기다 | 0.5-1 |
| 합계 | M0-M8 | | 15.5-25 (추정) |

참고:
1. probe 보고서는 FIR → KVIR 부분을 8-15 인일로 추정했다. 위 표에서 대응하는 M1, M3, M4는 7-11 인일이다. 같은 범위 안이다.
2. AI 에이전트 세션의 시간은 비교할 수 없다. frontend probe는 약 22분, spike는 약 80분이었다. 통합과 디버깅 비용은 측정한 적이 없다.
3. 위험이 가장 큰 곳은 M3(lowering 규칙의 빈틈)와 M5(오류 대응)다. 두 곳의 수락 테스트에 unsupported 사례와 Silicon 비정상 사례를 꼭 넣는다.

L3 연결 자리 (v0에서는 구현하지 않음):
1. `Operation`은 action 앞뒤의 스레드 지역 문장을 따로 갖는다. v1은 이것으로 함수 단위 메서드와 action 사이의 간섭(`exhale MI; inhale MI`)을 낼 수 있다.
2. 연산의 `ensures`(P1a 등)는 L3 클라이언트가 쓰는 L2 원자 명세가 된다. `Lease(k, t)`와 `tok(t).gHeld`의 연결은 RESULTS.md대로 아직 모델링하지 않았다.

## 8. 열린 질문과 판단

### 8.1 사용자만 정할 수 있는 것

| ID | 질문 | 권고 |
|---|---|---|
| U1 | ghost 바인딩으로 (c) 효과 기반 규칙을 채택하는가? (c)에서는 `gHeld`/`gBacked`가 코드 상태를 따라가고, 판정은 기본값이 `false`인 권한 절(P3a, P3b)이 진다. 대안 (a)는 req-shield 소스에 주석을 넣어야 한다 | (c) |
| U2 | SB2와 E4의 Kotlin 형태를 "action 밖 경쟁 읽기 + compute 1개"로 정하는가? compute 2개로 쓴 E4는 v0에서 UNSUPPORTED가 된다 (연산당 action 1개 제한) | 예 |
| U3 | action 하나라도 unsupported이면 모듈 판정을 INCOMPLETE로 하는가 (부분 PROVEN 없음)? | 예 |
| U4 | 도구가 `MI_OWN`(map 값 할당, key별 객체 분리)을 만들고 LLM hole에서 뺀다. 그러면 spike E3 결과는 v0보다 큰 hole(두 conjunct 포함)에 대한 결과가 된다. 받아들이는가? | 예 |
| U5 | sidecar 형식: 전용 DSL인가, TOML인가? | 전용 DSL |
| U6 | 연산에 자유 형식 `requires`(환경 가정)를 금지하는가? | v0에서는 금지 |
| U7 | 권한 절과 `issue` 규칙은 사람 소유다. LLM 초안은 사람이 `reviewed`를 붙일 때만 채택하는 정책에 동의하는가? | 예 |

### 8.2 메인 세션이 판단할 것

| ID | 판단 | 이 문서의 의견 |
|---|---|---|
| J1 | 실험 파일 `spike/effect_ghost/target.vpr`, `mutants.py`를 커밋해 emitter의 golden 목표로 쓸지 | 커밋 권장. `spike/run_mutants.sh effect_ghost`로 돌리면 `spike/effect_ghost/mutants/`와 `.list`가 생기므로 `.gitignore`에 두 줄을 더해야 한다 |
| J2 | M9(분기 도달성)를 v0에 넣을지 | 넣을 만하다. 비용 0.5-1 인일 (추정), SB5 동치를 설명한다 |
| J3 | Silicon 1회 실행인가, 메서드별 실행인가 | 1회 실행 기본, 메서드별은 옵션 |
| J4 | `kv scaffold`(FIR에서 sidecar 골격 생성: state 후보, compute 목록, 자유 값, 모르는 라이브러리 호출의 `TODO`)를 넣을지 | v0 뒤. 사람의 작성량을 줄이지만 완료 조건에는 필요 없다 |
| J5 | 발급 규칙을 `nextToken()` 사건이 아니라 연산 결과에 묶는 것 | 결과에 묶는다. `KeyLock` 계약과 같고, helper 인라인이나 이름 변경에 영향받지 않는다 |
| J6 | 리터럴로 초기화한 캡처 `val`을 상수로 전파할지 | v0는 전파하지 않는다 (임의 값). 정밀도가 필요해지면 추가 |
| J7 | 이 설계에 대한 critic 검토(rule 2)와 advisor 상담 | 이 작업은 advisor를 쓰지 않았다. ghost 바인딩은 되돌리기 비싼 결정이므로 메인 세션이 판단한다 |

### 8.3 메인 세션 결정 (2026-10-23)

| ID | 결정 |
|---|---|
| U3, U4, U5, U6 | 문서 권고대로 정한다: unsupported action이 있으면 INCOMPLETE, `MI_OWN`은 도구가 만든다, 전용 DSL, v0에서 자유 형식 `requires` 금지 |
| U1 | 사용자 결정: (c)를 채택한다. 프로토콜마다 사람이 권한 절(`ends_only_if`, `unbacked_only_if`)을 검토하는 것을 제품의 모양으로 받아들인다 |
| U2 | 사용자 결정: 한 연산에 compute가 둘 이상이면 v0에서 UNSUPPORTED다. 확인 후 삽입 경쟁은 "action 밖 경쟁 읽기 + compute 1개" 형태만 다룬다 |
| U7 | 사용자 결정: LLM 초안의 권한 절과 `issue` 규칙은 사람이 `reviewed`를 붙여야 채택한다 |
| J1 | `spike/effect_ghost/`를 커밋한다 |
| J2 | M9(분기 도달성 보고)를 v0에 넣는다. SB5가 왜 증명되는지 설명할 근거다 |
| J3 | Silicon 1회 실행이 기본, 메서드별 실행은 옵션 |
| J4 | `kv scaffold`는 v1 목록에 둔다. U1의 사람 검토 비용이 이것에 달려 있다 |
| J5 | 발급 규칙은 연산 결과에 묶는다 |
| J6 | v0는 상수 전파를 하지 않는다 |
| J7 | (c)를 채택한다. 세 방식을 같은 모델에서 측정했고 (b)는 재현 가능한 대조군 실패로 탈락했으므로 critic은 두지 않는다 |
| Silicon 예산 | `--assertTimeout 20000 --checkTimeout 20000`, 실행당 벽시계 300 s. 시간 초과는 ERROR이고 PROVEN이 아니다 |
| `init` inhale | emitter가 주석으로 표시하고, 보고서의 가정 목록에 싣는다 |
| M3/M4 질문 1 | ledger의 6번째 종류 `structure`를 받아들인다 (2.14 표). 모델이 쓰는 선언은 다른 5종류 어디에도 맞지 않는다 |
| M3/M4 질문 2 (D6) | emitter는 이름 없는 `Nondet`도 5.2 표대로 `var o: T`로 낸다. 읽히지 않는 값을 버리는 규칙은 만들지 않는다. 대신 `target.vpr` tryLock에 `var n_181_52: Int`(`mappingCount()` @181:52)를 더한다 |
| M3/M4 질문 3 | 2.14 표의 core 경쟁 읽기에 `lockMap.keys` 108:21을 더한다 (4.5절과 같은 사실) |
| M3/M4 질문 4 | 호출되지 않는 private helper가 모듈 상태를 바꾸면 모듈은 INCOMPLETE다. 보수적이지만 건전하다. 호출 그래프 검사는 v1 목록에 둔다 |
| M3/M4 질문 5 | 받아들인다: unsupported action의 본문은 발견 순서의 `Unsupported` 문장만 갖는다 (그 action은 출력하지 않으므로 판정과 무관하다). ledger 항목은 구문 단위로 위치를 적는다. 두 번 인라인한 helper의 이름은 `b`, `c` 접미사, 같은 줄의 두 번째 선언은 `_<col>` 접미사를 받는다 |
| M3/M4 질문 6 | `= clock`을 포함해 모든 `trusted fun`에 `reviewed`가 필요하다. 시계 선언도 "모듈 상태를 바꾸지 않고 임의 값을 준다"는 신뢰 주장이다 |
| M5 gate의 목표 형태 | `target.vpr`을 5.2 템플릿 형태로 고쳤다: 성질마다 `ensures` 1줄, hole을 `MI_INV_1..5`로 분할, `P3A_EXPIRY` 대신 권한 절 식, `carried` macro, 선언 순서 규칙, `init`의 `S.seq == 0`. 의미는 같다: Silicon 검증 3.93 s, 변이 판정 23개 동일 (`spike/effect_ghost/expected-verdicts.txt`). 실패 위치도 6절과 같다: SB3 → `MI_INV_4`, SB6 → `MI_INV_1`과 P3a, E4 → P3a와 P3b |
| M5와 M6 | 한 작업으로 합친다. `target.vpr`은 커밋 블록과 권한 절을 가지므로, ghost binder 없이는 M5 수락 테스트 1·2가 성립하지 않는다 |
| M5 gate | `python3 -I spike/vpr_normalize.py spike/effect_ghost/target.vpr <생성한 coroutine 모델>`이 0으로 끝난다. core는 `mutants.py`의 `CORE_variant.vpr`과 비교한다. 정규화는 주석 제거, 토큰 비교, 메서드별 위치 접미사 지역 이름의 첫 등장 순 이름 바꾸기뿐이다. 문장 순서는 정규화하지 않는다 |
| Silicon 시간 | 8.3의 예산(`--assertTimeout 20000 --checkTimeout 20000`, 벽시계 300 s)이 M5 수락 테스트 1의 "메서드당 60 s"를 대신한다 |
| M5/M6 검증 | 메인 세션이 직접 확인했다: 테스트 199개, 두 gate exit 0, Silicon PROVEN. Kotlin 원본 변이 점검: SB3, SB4, SB6, E4는 6절의 의무로 FAILED, SB5, SB5b는 PROVEN. SB1, SB2는 FAILED가 아니라 ERROR였다: `unLock`의 `token`을 코드가 sidecar 필드와 비교하지 않으면 추상 타입이 정해지지 않고(UNSUPPORTED), 그 경로에서 W14가 깨진다(도구 결함). 4.2 제한 4와 다음 작업의 수정으로 다룬다 |
| M5/M6 Q1 | `--logLevel ERROR`와 `--reportReasonUnknown`을 받아들인다. 죽인 실행과 `canceled`/`timeout`/`resource` 이유만 ERROR다. 시간 초과가 `(incomplete quantifiers)`로 나오면 FAILED가 될 수 있다. 이것은 거짓 PROVEN이 아니라 거짓 경보 쪽이다. 보고서는 FAILED 의무 옆에 Silicon의 이유 문구를 싣는다. 두 실제 모델은 20 s 예산에서 PROVEN이므로 이 경우가 나오지 않았다 |
| M5/M6 Q2, Q6 | 받아들인다: backed 필드는 issue 규칙이 `carried(S, k, <result>)`로 정하는 frozen 필드다. issue 값의 산술은 v0에서 `+`, `-`뿐이다 |
| M5/M6 Q3, Q4 | stray 메서드 이름은 5.5 표에 적었다. hole 분할: 최상위에 `||`, `==>`, `<==>`, `?:`, 괄호 없는 `forall`/`exists`/`let`이 있으면 나누지 않는다. 조각의 이름은 바로 앞 주석 줄이고, 없으면 가장 가까운 앞 조각의 이름이다 |
| M5/M6 Q5 | 보고서의 "경쟁 읽기 → 자유 값" 연결은 v0에서 뺀다 (5.8 예시를 고쳤다). KVIR에 출처 사슬을 더하는 것은 보고서 한 줄을 위한 기계 장치다 |
| M5/M6 Q7 | `kv verify` 종료 코드: 0 PROVEN, 1 FAILED, 2 사용법 오류, 3 INCOMPLETE, 4 ERROR (컴파일 오류, sidecar 오류, W 위반, Silicon 비정상 포함). CI와 M8 스크립트가 보고서 문구를 읽지 않아도 된다 |
| M7 편집 면 | LLM은 sidecar의 `hole MI_INV """..."""` 문자열만 고친다. 검사기는 고치기 전후의 sidecar를 비교한다: hole 문자열 밖은 바이트 단위로 같아야 하고, 안에는 금지어가 없어야 한다 (spike `e3_harness.py`의 규칙). `.vpr`의 marker 사이를 고치는 방식은 쓰지 않는다: emitter가 hole을 조각으로 나누므로 LLM이 나눈 형태를 써야 하고, sidecar가 원본이라는 원칙과도 어긋난다 |
| 다음 작업 순서 | SB1/SB2 수정 → Q3/Q5/Q7 코드 → M8(두 변형) → M7 → M9. M8이 v0 완료 조건이고, sidecar의 hole은 이미 채워져 있으므로 M8은 M7에 기대지 않는다 |
| v0 검증 (메인 세션) | 테스트 246개, 두 gate exit 0, `./gradlew mutantsReqShield` 22회 불일치 0 (84 s). 원본·SB5·SB5b·R1·R2는 두 변형 모두 PROVEN, SB1-SB4·SB6·E4는 FAILED. M9 INFO는 정확히 161:49, 213:44, 104:49. `kv hole check`는 hole 안 `inhale`과 hole 밖 편집을 위치와 함께 거부한다 |
| SB1/SB2 수정 | 원인 (A): 코드가 `token`을 sidecar 필드와 비교하지 않으면 추상 타입이 정해지지 않았다. 4.2 제한 4의 표기로 고친다. 원인 (B): action 밖 선언을 action 안 노드로 표시해 W14가 깨졌다. lowering에서 고쳤고, 같은 경로(`Long?`, 모델 밖 클래스)에 fixture를 더했다 |
| v0 질문 1 | SB1/SB2의 실패 의무는 측정값(P3a)으로 6절 표를 고친다. 어떤 `ensures` 순서도 6절의 옛 표 전체를 재현하지 못한다 (측정). 템플릿 순서는 그대로 둔다 |
| v0 질문 2, 3 | M9는 `kv verify`에서 항상 두 번째 Silicon으로 동시에 돈다 (INFO, 판정 무관). 분기: 번역된 `if`의 then 팔과 비어 있지 않은 else 팔, 결과를 읽는 CAS/tryAcquire의 성공·실패. 결과를 읽지 않는 연산(189:17)과 빈 else는 뺀다 |
| v0 질문 4, 5 | `call` 출처의 자유 값에는 타입 표기를 쓸 수 없다 (trusted fun의 `returns`/`ghost`가 정한다). `kv hole`은 hole을 담은 파일(`keylocallock.common.kv`)을 직접 받는다 |
| reactor 변형 (적용 A) | 사용자 결정: 다른 모듈에 적용한다, A(core-reactor `KeyLocalLock`) 다음 B(Spring Aspect `computeIfAbsent`). A는 같은 sidecar 공통부(`keylocallock.common.kv`)를 쓰고, 변형 파일 `keylocallock.reactor.kv`는 module, source, state 줄과 `use reactor.Mono.fromCallable`만 다르다 |
| `reactor.Mono.fromCallable` 신뢰 모델 | 람다가 연산 몸체다. 구독마다 람다가 한 번 실행되고, 실행 하나가 연산 호출 하나다 (구독이 없으면 호출도 없다). 람다의 마지막 식이 연산 결과이고, `null`은 빈 Mono(= `NULL`)다. Mono는 그 밖의 효과를 더하지 않는다. 연산 결과 규칙은 "함수 끝의 `return` 하나, 또는 이 모델 아래에서 람다의 마지막 식 하나; 둘 다 셀 `get`이나 상수만 읽는다"가 된다 |
| reactor gate | `mutants.py`의 `REACTOR_variant`(메인 세션 작성): `CORE_variant`의 새 entry 편집에 순서 차이 3곳을 더한다. 만료 해제 뒤 토큰 지우기(177, 179), `expiresAt` 뒤 `token`(186, 187), sweep은 CAS 블록 뒤에 토큰을 지운다(113, 115). Silicon 3.93 s로 검증된다. 생성한 reactor 모델은 정규화하면 이것과 같아야 한다 |

## 부록 A. 실험 재현

1. 목표 모델: `spike/silicon.sh spike/effect_ghost/target.vpr` (`TIMES=1`이면 메서드별 시간).
2. 변이: `python3 -I spike/effect_ghost/mutants.py <OUT>` → `<OUT>/<id>.vpr`과 기대 판정 목록. 각 파일을 `spike/silicon.sh --numberOfErrorsToReport 0`으로 검증한다.
3. 이번 세션 출력 (session-local scratchpad):
   - `/private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/effect_mutants.report` (변이 판정과 오류 줄)
   - `/private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/effect_mutants/` (변이 `.vpr`과 Silicon 출력)
   - `/private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/vprtest/reach.vpr` (분기 도달성)
   - `/private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/vprtest/split_target.vpr`, `split_SB3.vpr` (conjunct 분할)
   - `/private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/coredump/KeyLocalLock.core.fir.txt` (core FIR dump, 425줄, unresolved 0)
4. core FIR dump는 `frontend-probe`의 플러그인을 Gradle init script(`.../scratchpad/coredump/dumpcore.init.gradle`)로 core 소스에 적용해 만들었다. 프로젝트 파일은 바꾸지 않았다.

## 부록 B. v0의 신뢰 가정 (Silicon이 확인하지 않음)

1. `ConcurrentHashMap.compute`는 key마다 원자적이고, 함수를 정확히 한 번 부른다 (JDK 21 Javadoc).
2. cross-key 축소: 다른 key의 compute는 token counter(원자적 `AtomicLong`)만 공유한다. key별 객체 분리는 이제 `MI_OWN`으로 증명한다.
3. action 밖에서 읽은 값(시계, 경쟁 읽기, 인스턴스 값)은 임의 값이다. 이것은 모든 지연과 경쟁을 덮는다.
4. `Long`은 무한 정수다. 지점마다 진단한다.
5. 토큰 템플릿 `"local-" + n`은 단사다. 토큰이 null 표시값과 겹치지 않는다는 사실은 이제 `assert 0 <= n`으로 증명한다.
6. 프로젝트 함수 계약(`nowToEpochTime`, `rejectsNewEntry`)은 사람이 본문을 읽고 확인했다.
7. 로그와 거부 카운터는 모델 상태에 영향이 없다.
8. `init`의 ghost 기록 할당은 `inhale`이다.
9. action 안에서는 예외가 나지 않는다. 도구는 "던지지 않음" 연산만 허용해서 이것을 확인한다. `Error`는 치명적이라고 가정한다.
10. `Semaphore`와 `AtomicBoolean` 객체는 소유한 `LockInfo`에만 속한다. 도구가 평탄화 조건으로 확인한다.

## 부록 C. 근거

| 출처 | 쓴 곳 |
|---|---|
| `PHASE1.md` | 목표, 결정, 누가 무엇을 쓰는가 |
| `spike/keylocallock/baseline.vpr`, `spike/PLAN.md`, `spike/RESULTS.md`, `spike/keylocallock/mutants.py`, `mutants.report` | 목표 모델, 성질, 변이, 인코딩 교훈 |
| `spike/e3/` (`TASK.md`, `e3_harness.py`) | hole 흐름 |
| `frontend-probe/out/KeyLocalLock.fir.txt`, scratchpad `frontend-probe.md` | FIR 사실과 함정 |
| scratchpad `coredump/KeyLocalLock.core.fir.txt` | core 변형의 FIR 사실 |
| scratchpad `snakt-eval.md` | 피해야 할 인코딩 (finally 삭제, catch 진입, var havoc, Silicon 중단 침묵) |
| req-shield `KeyLocalLock.kt` 두 변형, `KeyLock.kt` 두 변형, `LocalLockLimit.kt`, `TimeUtils.kt`, `ConfigValues.kt` (브랜치 `feature/NOISSUE/upgrade-kotlin2-and-deps`, HEAD `2e792d2`). spike 커밋 `91962c7`과 비교하면 `TimeUtils.kt`만 다르다: `nowToEpochTime()`이 `LocalDateTime` 경유에서 `System.currentTimeMillis()`로 바뀌었다. 모델은 두 구현 모두 단조성 없는 임의의 시계 값으로 다루므로 설계에 영향이 없다 | lowering 규칙, 변이 편집, 신뢰 계약 |
| Corretto 21.0.10 `src.zip`의 `ConcurrentHashMap.java`, `Semaphore.java` | compute, mappingCount, Semaphore 모델 |
| `spike/effect_ghost/target.vpr`, `mutants.py` (이번 세션 작성) | 3절과 6절의 측정 |
