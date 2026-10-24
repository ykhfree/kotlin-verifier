# Translator B 설계: Spring Aspect의 메서드별 `ReqShield` 캐시

날짜: 2026-10-23. 상태: 설계안 (translator 코드 변경 전). 범위: 일반성 검증 B ("새 프로토콜, 새 명세").
대상: req-shield 브랜치 `feature/NOISSUE/upgrade-kotlin2-and-deps`, HEAD `2e792d2`의 세 Spring Aspect 모듈과 그 `reqShieldMap`.
기준: `DESIGN-v0.md`. 이 문서는 v0와 다른 점만 정한다. 이 문서가 정하지 않은 것은 v0를 따른다.

수치 표시 규칙: "측정"은 이번 세션에서 실행해서 얻은 값이다. "추정"은 실행하지 않은 값이다.
Silicon은 `v.26.08-release`, Z3는 4.16.0이다 (spike 설치본, `spike/silicon.sh`).

## 0. 요약

| 항목 | 결정 (권고) | 근거 |
|---|---|---|
| 성질 | 네 가지를 증명한다: `P_stable`(`set-once`), `P_same`, `P_once`, 도구의 `MI_OWN`. 사용자가 말한 "same instance"는 `P_same` ∧ `P_stable`이다. "created once"는 `P_once`다 | 1절, 측정 |
| 모듈 | sidecar `module`이 클래스를 가리키면, 그 클래스의 인스턴스 하나가 모듈이다 (`S` = `this`). 모듈 상태는 sidecar가 선언한 인스턴스 필드다 | 2.1절 |
| key | `type MethodKey = opaque java.lang.reflect.Method`. Kotlin 타입이 추상 타입을 정하므로 v0의 String 추론(2.13)이 필요 없다 | 2.2절 |
| CHM API | `computeIfAbsent`, `compute`, `putIfAbsent`, `put`(`set` 연산자 포함), `remove`는 각각 action 1개다. `getOrPut`은 신뢰 모델이 "경쟁 읽기, 연산 구간의 기본값 호출, `putIfAbsent` action"으로 펼친다 | 2.3절, 2.5절, FIR 측정 |
| 경쟁 읽기 | `set-once`로 선언한 map의 `get`은 null이거나 map이 지금 가진 값이다. 그 밖의 map에서는 v0대로 임의 값이다 | 2.4절. 측정: v0 규칙은 대조군 R2를 오경보로 거부한다 |
| 모델 밖 호출 | `createReqShield`는 `trusted fun ... returns fresh Shield throws effects none`이다. 도구가 본문의 일부를 기계로 검사하고, 사람이 나머지를 검토한다 | 3절 |
| ghost | sidecar ghost 규칙은 새로 없다. 도구가 `fresh` 결과마다 `S.created`에 넣고 `gFor := k`를 기록한다. v0가 `New`마다 내는 할당 장부와 같은 종류다 | 4절. 측정: 효과 기반 계수는 M3, M4를 놓친다 |
| 예외 | `throws` 신뢰 함수 호출마다 비결정 flag가 있다. flag가 참이면 연산의 나머지를 건너뛴다. action 안이면 map 쓰기도 건너뛴다. 연산 결과 이름 `exc`를 예약한다 | 2.7절 |
| 실험 | 목표 모델 `spike/aspect/target.vpr`이 1.94 s에 검증된다 (측정). 실제 버그 변이 7개는 FAILED, 대조군 2개는 PROVEN이다 (측정) | 7절 |
| 공유 | 세 Aspect 모듈은 공통 sidecar 1개와 모듈별 파일 3개로 나눈다. 모듈별 파일은 `module`, `source`, `trusted fun`(5줄), `include`의 8줄이다 (주석 제외) | 6.2절 |
| 범위 | `reqShieldMap`은 필수다. `expressionMap`은 선택 마일스톤이다. `keyGeneratorMap`은 범위 밖이다 | 6.3절 |
| 구현 | 마일스톤 B0-B4, 사람 엔지니어 기준 7-12 인일 (추정) | 8절 |

측정한 변이 판정. `bash spike/run_mutants.sh aspect`가 13개를 병렬로 11.55 s에 검증한다 (측정). 표의 "처음 보고"는 Silicon이 보고한 의무다.
"전체"는 의무를 하나씩 남기고 따로 검증한 결과다 (84회 실행, 71.8 s, 측정). 이유: Silicon은 한 경로에서 처음 실패한 `ensures` 줄만 보고한다 (DESIGN-v0.md 6절). 그 줄의 conjunct가 여럿 실패하면 여럿을 보고한다 (M5).

| 변이 | Kotlin 편집 (요약) | 기대 | 판정 | 처음 보고 | 깨지는 의무 전체 |
|---|---|---|---|---|---|
| 원본 | `computeIfAbsent(getTargetMethod(jp)) { createReqShield(jp) }` | PROVEN | PROVEN | — | 없음 |
| R1 | `compute(...) { _, old -> old ?: createReqShield(jp) }!!` | PROVEN | PROVEN | — | 없음 |
| R2 | `reqShieldMap[method] ?: reqShieldMap.computeIfAbsent(method) { ... }` | PROVEN | PROVEN | — | 없음 |
| M1 | `reqShieldMap[method] ?: createReqShield(jp).also { reqShieldMap[method] = it }` | FAILED | FAILED | `P_stable` | `P_stable`, `P_once`, `MI_INV_1` |
| M2 | `val c = createReqShield(jp); reqShieldMap[...] = c; return c` | FAILED | FAILED | `P_stable` | `P_stable`, `P_once`, `MI_INV_1` |
| M3 | `reqShieldMap.getOrPut(getTargetMethod(jp)) { createReqShield(jp) }` | FAILED | FAILED | `P_once` | `P_once`, `MI_INV_1` |
| M4 | `val c = createReqShield(jp); return reqShieldMap.putIfAbsent(..., c) ?: c` | FAILED | FAILED | `P_once` | `P_once`, `MI_INV_1` |
| M5 | `computeIfAbsent(...) { sharedReqShield }` (새 인스턴스 필드 1개) | FAILED | FAILED | `MI_OWN` (두 conjunct) | `MI_OWN` |
| M6 | `aroundReqShieldCacheable`의 catch에서 `reqShieldMap.remove(...)` | FAILED | FAILED | `P_stable` (stray action) | `P_stable`, `MI_INV_1` |
| M7 | `val c = createReqShield(jp); reqShieldMap.putIfAbsent(..., c); return c` | FAILED | FAILED | `P_once` | `P_once`, `P_same`, `MI_INV_1` |
| ARB_R2 | R2 + v0 경쟁 읽기 (임의 값) | PROVEN | **FAILED (오경보)** | `P_same` | `P_same` |
| ARB_M3 | M3 + v0 경쟁 읽기 | FAILED | FAILED | `P_once`, `P_same` | `P_once`, `P_same`, `MI_INV_1` |
| EFF_M3 | M3 + 효과 기반 계수 (map에 들어간 객체만 센다) | FAILED | **PROVEN (놓침)** | — | 없음 |
| EFF_M4 | M4 + 효과 기반 계수 | FAILED | **PROVEN (놓침)** | — | 없음 |

`run_mutants.sh`는 ARB_R2와 EFF 두 줄을 `UNEXPECTED`로 보고하고 종료 코드 1을 낸다. 이 세 줄은 탈락한 설계 대안의 측정이다.
`spike/effect_ghost`의 CASB 줄과 같은 관례다. 권고 설계의 줄(원본, R1, R2, M1-M7)은 모두 기대와 같다.

## 1. 프로토콜과 성질

### 1.1 대상 코드 (FIR 측정)

세 모듈은 같은 모양이다. FIR 위치는 `frontend-probe` 플러그인으로 dump해서 얻었다 (부록 A).

| 모듈 | `reqShieldMap` 선언 | `getOrCreateReqShield` | `computeIfAbsent` | 람다 안 `createReqShield` 호출 | `createReqShield` 본문 |
|---|---|---|---|---|---|
| `core-spring` | 84행 (FIR 83:5, KDoc 시작) | 111:5 | 112:9 | 113:13 | 116-151 |
| `core-spring-webflux` | 77행 (FIR 76:5) | 124:5 | 125:9 | 126:13 | 129-163 |
| `core-spring-webflux-kotlin-coroutine` | 90행 (FIR 89:5) | 231:5 | 232:9 | 233:13 | 236-264 |

FIR이 보여 준 사실 (core-spring, 측정):
1. 호출은 `CALL java/util/concurrent/ConcurrentHashMap.computeIfAbsent(java.lang.reflect.Method, java.util.function.Function<...>) [override public origin=SubstitutionOverride(CallSite)]`이다.
2. 두 번째 인자는 `function-conversion kind=Sam`이 감싼 람다다. 람다는 `inlineStatus=NoInline invocationKind=null`이다. v0의 `compute` 람다와 같다.
3. key 인자 `getTargetMethod(joinPoint)`(112:38)는 람다 밖에서 계산된다. v0의 `ActionKey` 자유 값이다.
4. 람다 본문은 `createReqShield(joinPoint)` 호출 하나다. 그 함수는 같은 파일의 `private` 함수다.
5. 각 파일에는 `computeIfAbsent`가 3개 있다. `reqShieldMap` 1개, `expressionMap` 1개, `keyGeneratorMap` 1개다.
   core-spring은 112:9, 237:9, 246:16이다. webflux는 125:9, 249:9, 258:16이다. coroutine은 232:9, 196:34, 282:16이다.
6. 세 dump 모두 unresolved 노드가 0개다 (479, 513, 563줄).

### 1.2 문서화된 명세 (인용)

| 출처 | 원문 |
|---|---|
| req-shield `CLAUDE.md:27` | "One `ReqShield` instance per annotated method" |
| core-spring `ReqShieldAspect.kt:83` (webflux 76행에 같은 문장) | "A ReqShield is configured by the annotation alone, so one instance per annotated method is enough." |
| coroutine `ReqShieldAspect.kt:89` | "One ReqShield per annotated method: the cache key varies per call, the configuration does not." |
| 테스트 계약 `support/src/testFixtures/.../BaseReqShieldModuleSupportTest.kt:22` | `fun reqShieldObjectShouldBeCreatedOnce()` |
| core-spring 테스트 `ReqShieldAspectTest.kt:155` | `fun eachAnnotatedMethodShouldGetItsOwnReqShield()` |

`CLAUDE.md:27`의 문장은 두 가지로 읽힌다.
1. "한 메서드의 모든 호출이 객체 하나를 함께 쓴다" (`P_same` ∧ `P_stable`).
2. "한 메서드에 객체를 하나만 만든다" (`P_once`).
테스트 이름 `reqShieldObjectShouldBeCreatedOnce`는 두 번째 읽기를 지지한다. 그러나 그 테스트는 `reqShieldMap.size == 1`과 `reqShieldMap[method]`가 null이 아님만 확인한다 (core-spring 테스트 149-150행). 그래서 `getOrPut` 변이(M3)도 통과한다.
테스트 이름은 문서가 아니다. 이 문서는 테스트 이름을 보조 근거로만 쓴다.

### 1.3 중복 `ReqShield`의 비용

현재 코드 (`2e792d2`, 소스를 읽어서 확인):
1. `ReqShield`의 인스턴스 상태는 `reqShieldConfig` 하나다 (core `ReqShield.kt:35-37`. reactor, coroutine도 같다).
2. 설정 객체는 기본 인자로 `KeyLocalLock` 또는 `KeyGlobalLock`을 새로 만든다 (core `ReqShieldConfiguration.kt:58`, 98-112).
3. `KeyLocalLock`의 lock map은 companion에 있다: "Global lockMap shared by all instances - CRITICAL FIX for request collapsing" (core `KeyLocalLock.kt:66`).
   감시 스케줄러는 `getOrCreateScheduler()`가 한 번만 만든다 (core 79-86행, `init` 156-159행).
4. coroutine `KeyLocalLock`은 인스턴스마다 `private val job = Job()`을 가진다 (137행). 그 scope에서 `launch`하는 코드는 없다. `launch`는 companion의 87행뿐이다.
5. 결론: 지금 중복 하나의 비용은 쓰레기 객체뿐이다 (설정, 함수 값 2-4개, `KeyLock`, coroutine에서는 빈 `Job`). 스레드, 예약 작업, lock map 항목은 늘지 않는다.

과거 코드 (git 이력, 측정):
1. 커밋 `15e24d5`(2025-08-10) 이전의 core `KeyLocalLock.kt`는 인스턴스마다 `private val lockMap`(32행)과 `Executors.newSingleThreadExecutor()`(34행)를 가졌다.
   `init`이 그 executor에서 `while (true)` 감시 루프를 시작했다 (36-48행).
   그 코드에서는 중복 `ReqShield`(지역 lock) 하나가 끝나지 않는 스레드 하나를 더 만든다. 또 두 인스턴스는 lock map을 공유하지 않으므로, 그 둘 사이에서는 요청 병합이 일어나지 않는다.
2. 커밋 `cbabe26`(2026-09-16) 이전의 key는 `"${method.declaringClass.name}.${method.name}-${cacheName}-${cacheKey}"`였다 (`generateReqShieldKey`, 188-192행).
   그래서 cache key 값마다 `ReqShield`가 하나씩 생겼다. `cbabe26`이 key를 `Method`로 바꾸고 `CLAUDE.md:27`의 문장을 더했다.

### 1.4 성질 표

| ID | 식 (Viper, 4.6절 어휘 + 5.1절 추가 어휘) | 검사 위치 | 출처 | 지위 |
|---|---|---|---|---|
| `P_stable` | `forall qk :: old(has(S, qk)) ==> has(S, qk) && S.m[qk] == old(S.m[qk])` | 모든 action (stray 포함) | `CLAUDE.md:27` 읽기 1, `ReqShieldAspect.kt:83` | 문서화됨 |
| `P_same` | `!exc ==> res != null && has(S, k) && S.m[k] == res` | 연산 `getOrCreate` | `CLAUDE.md:27` 읽기 1 | 문서화됨 |
| `P_once` | `forall qr1, qr2 :: qr1 in S.created && qr2 in S.created && qr1.gFor == qr2.gFor ==> qr1 == qr2` | 불변식 (모든 action) | `CLAUDE.md:27` 읽기 2, 테스트 이름 | 문서화됨 (문장이 모호함, 9.1절 UB1) |
| `MI_OWN` | map 값은 이 모듈이 만든 객체이고, 서로 다른 key는 같은 객체를 갖지 않는다 | 불변식 (도구가 만듦, v0 5.3) | "per annotated method", 테스트 155행 | 문서화됨 |
| `progress_ok` | `!thrown ==> !exc` | 연산 `getOrCreate` | 없음 (probe가 공허하지 않게 하는 진행 조건, v0 5.4) | 제안 |

각 성질이 사는 것:
1. `P_stable` ∧ `P_same` ("same instance"): 한 메서드의 모든 호출이 객체 하나를 함께 쓴다. 그래서 요청 병합과 설정이 호출자가 받은 객체에 의존하지 않는다.
   지금은 lock 상태가 전역이므로 이중 안전장치다. `15e24d5` 이전 같은 인스턴스별 상태가 돌아오면 이 성질이 요청 병합을 지킨다.
   잡는 변이: M1, M2, M6 (`P_stable`), M7 (`P_same`).
2. `P_once` ("created once"): 메서드마다 `ReqShield`를 한 번만 만든다. 지금은 쓰레기 객체를 줄인다. 생성이 자원을 잡던 `15e24d5` 이전 코드에서는 스레드 누수를 막았다.
   잡는 변이: M3, M4 (이 성질만 깨진다), 그리고 M1, M2, M7.
3. `MI_OWN`: 두 메서드가 한 객체를 공유하지 않는다. 그래서 각 메서드는 자기 어노테이션으로 만든 설정을 쓴다. 잡는 변이: M5 (이 성질만 깨진다).
4. 비용: 네 성질을 모두 넣은 목표 모델이 1.94 s에 검증된다 (측정). 그래서 성질을 빼서 얻는 속도 이득이 없다.

권고: 네 성질을 모두 증명한다. `P_once`는 별도 ID로 두어 팀이 모듈마다 뺄 수 있게 한다.

### 1.5 두 성질이 갈라지는 곳 (측정)

| 변이 | same instance (`P_same` ∧ `P_stable`) | created once (`P_once`) |
|---|---|---|
| M3 `getOrPut` | 지킴 | 깨짐 |
| M4 생성 후 `putIfAbsent` | 지킴 | 깨짐 |
| M7 `putIfAbsent` 결과 무시 | 깨짐 (`P_same`) | 깨짐 |
| M1 get-then-put, M2 무조건 put | 깨짐 (`P_stable`) | 깨짐 |
| M6 실패 시 remove | 깨짐 (`P_stable`) | 지킴 (그 단계에서는) |
| M5 공유 객체 | 지킴 | 지킴 (`MI_OWN`만 깨짐) |

`getOrPut`이 갈라지는 이유는 Kotlin stdlib KDoc이 직접 말한다 (2.5절):
"This method guarantees not to put the value into the map if the key is already there," 그리고
"but the [defaultValue] function may be invoked even if the key is already in the map."

### 1.6 범위 밖 성질 (제안)

| ID | 내용 | 출처 | 범위 밖인 이유 |
|---|---|---|---|
| `P_failfast` | `isLocalLock = false`인데 lock 지원이 없으면 그 메서드의 호출은 항상 예외로 끝나고, 매핑은 생기지 않는다 | README 102-103행: "the first call to the annotated method fails with an `IllegalArgumentException` instead of silently running without request collapsing." | 생성 함수가 언제 던지는지를 key와 묶는 계약(`fails(targetMethod(jp))`)이 필요하다. 이 성질을 깨는 변이(예외를 잡고 대체 객체를 넣음)는 action 안 `try`라서 이미 UNSUPPORTED다 |
| `P_key` | key는 호출의 대상 메서드이고, key `m`의 객체는 `m`의 어노테이션으로 설정되었다 | `getTargetMethod`의 KDoc (core-spring 153-156행), `ReqShieldAspect.kt:83` | key 계산(자유 값)을 순수 신뢰 함수로 번역해야 한다. 새 개념 2개(순수 신뢰 함수, 신뢰 계약이 정하는 ghost 표시)가 든다. 이 성질은 `cbabe26`이 고친 key 단위 버그와 `signature.method` key 버그를 잡는다 (9.1절 UB4) |

알려진 빈틈: key를 `(joinPoint.signature as MethodSignature).method`로 바꾸는 변이는 B에서 PROVEN이다. key가 두 경우 모두 임의 자유 값이기 때문이다. `P_key`만 이 변이를 잡는다.

## 2. 모델

### 2.1 모듈 = Aspect 인스턴스

1. sidecar `module`이 클래스(`...aspect.ReqShieldAspect`)를 가리키면 인스턴스 모듈이다. Viper의 `S`는 그 인스턴스(`this`)다.
2. 모듈 상태는 sidecar `state`로 선언한 인스턴스 필드다. 필드는 `val`이고, 선언에서 `ConcurrentHashMap()`으로 초기화되어야 한다. `init`은 생성자의 그 초기화다 (`S.m := Map()`).
3. FIR에서 모듈 상태 접근은 implicit receiver가 그 클래스에 묶인 노드다 (`dispatch-receiver: this (implicit) bound=class .../ReqShieldAspect`, 측정). v0의 companion 규칙(2.11.2)과 같은 방법이다.
4. 선언하지 않은 인스턴스 property(`lockSupport`, `spelParser`, `beanFactory` 등)를 읽으면 자유 값이다. v0의 `InstanceVal`은 불변 property만 받았다. B는 `var`도 받는다.
   이유: action이 쓰지 않는 필드를 읽은 값은 그 타입의 임의 값으로 두어도 건전하다. action 안에서 그런 필드에 쓰면 v0대로 UNSUPPORTED다.
5. 인스턴스가 여럿이면 모듈도 여럿이다. 성질은 인스턴스마다 성립한다. Spring은 `@Import(ReqShieldAspect::class)`(`LibAutoConfiguration.kt`)로 context마다 bean 하나를 만든다.
6. 감사 범위 (v0 2.14 확장): state 필드가 `private`이면 그 클래스의 파일을 감사한다. `internal`이면 그 Gradle 모듈의 main 소스 전체를 감사한다. `protected`나 `public`이면 UNSUPPORTED다.
   `reqShieldMap`은 `internal`이므로 sidecar의 `source`가 디렉터리 `core-spring/src/main/kotlin`을 가리킨다.
   test 소스도 `internal`을 볼 수 있다. 세 모듈의 test는 `reqShieldMap`을 읽기만 한다 (`size`, `get`, `isEmpty`, 측정: grep). test 소스는 감사하지 않고, 보고서의 가정 목록에 싣는다 (9.1절 UB6).

### 2.2 key 타입과 값 클래스

1. `type MethodKey = opaque java.lang.reflect.Method`는 Kotlin 타입이 `Method`인 모든 값을 `MethodKey`로 둔다. Viper에서는 `domain MethodKey {}`다.
2. Viper의 `==`는 `Method.equals`를 뜻한다. `ConcurrentHashMap`은 key를 `equals`로 비교한다 (`get`의 Javadoc, 928행: "such that {@code key.equals(k)}").
   `Method.equals`의 Javadoc (JDK 21 `Method.java:361-364`): "Two {@code Methods} are the same if" "they were declared by the same class and have the same name" "and formal parameter types and return type."
   이 대응은 신뢰 모델 표의 항목이다 (`jdk.reflect.Method` key 항목). 다른 key 클래스는 B에서 sidecar 오류다.
3. v0의 String 추론(2.13)은 그대로 둔다. `opaque` 뒤에 Kotlin 클래스가 있으면 추론 대상이 아니다. 고정점이 하나뿐이므로 union-find가 필요 없다.
4. 값 클래스는 `class Shield = ReqShield { }`처럼 필드 없이 선언한다. Viper에서는 `Ref`이고, 모델 필드가 없다.
5. 제네릭: `ReqShield<T>`와 `ReqShieldAspect<T>`의 타입 인자는 무시한다. 클래스는 class id로 맞춘다. 모델에 `T`의 값이 나오지 않는다.
6. KNAME 찾기 (v0 4.2 제한 3)에 "모듈 파일의 import"를 더한다. 그러면 `ReqShield`가 세 모듈에서 각자의 클래스로 풀린다 (core, reactor, coroutine 패키지). 공통 sidecar가 이것에 기댄다 (6.2절).

### 2.3 `ConcurrentHashMap` API 모델 (신뢰 모델 표 추가분)

모든 항목은 action 하나다. receiver는 sidecar `state` map이어야 한다. key 인자는 action key `k`다.
출처는 Corretto 21.0.10 `src.zip`의 `ConcurrentHashMap.java`와 `ConcurrentMap.java`다.

| 모델 | 의미 | 출처 (한 줄씩 인용) |
|---|---|---|
| `jdk.ConcurrentHashMap.computeIfAbsent` | 원자적이다. key가 있으면 함수를 부르지 않고 현재 값을 돌려준다. 없으면 함수를 한 번 부른다. 결과가 null이 아니면 넣고 그 값을 돌려준다. null이면 넣지 않고 null을 돌려준다. 함수가 던지면 매핑을 만들지 않고 예외가 나간다 | 1670행 "method invocation is performed atomically.  The supplied"; 1672행 "if the key is absent, else not at all.  Some attempted update"; 1669행 "and enters it into this map unless {@code null}.  The entire"; 1681-1682행 "@return the current (existing or computed) value associated with" "the specified key, or null if the computed value is null"; 1689행 "in which case the mapping is left unestablished"; 1677행 "The mapping function must not modify this map during computation." |
| `jdk.ConcurrentHashMap.compute` | v0 그대로. 함수가 던지면 매핑이 바뀌지 않는다 | 1895행 "in which case the mapping is unchanged" |
| `jdk.ConcurrentHashMap.putIfAbsent` | 원자적이다. key가 없으면 넣고 null을 돌려준다. 있으면 바꾸지 않고 현재 값을 돌려준다 | `ConcurrentMap.java:127-128` "If the specified key is not already associated" "with a value, associates it with the given value."; 136행 "except that the action is performed atomically." |
| `jdk.ConcurrentHashMap.put` | 넣고 이전 값을 돌려준다. `kotlin.collections.set`(`m[k] = v`)도 이 모델이다 | `ConcurrentHashMap.java:993` "Maps the specified key to the specified value in this table."; stdlib 2.0.21 `Maps.kt:266-267` "public inline operator fun <K, V> MutableMap<K, V>.set(key: K, value: V): Unit {" "put(key, value)" |
| `jdk.ConcurrentHashMap.remove` | 지우고 이전 값을 돌려준다 | 1093행 "Removes the key (and its corresponding value) from this map." Viper는 v0 `Remove`와 같다 |
| `jdk.ConcurrentHashMap.racyRead` (B 확장) | action 밖의 `get`, `containsKey`. 2.4절 | 87-88행 "Retrievals reflect the results of the most" "recently <em>completed</em> update operations holding upon their" |
| `kotlin.collections.getOrPut` (ConcurrentMap receiver) | 2.5절 | stdlib 2.0.21 `MapsJVM.kt:70-75` |

`computeIfPresent`, `merge`, `replace`, `clear`, `putAll`, `remove(k, v)`는 B에서 UNSUPPORTED다.

Viper 출력 (action 하나, `cur`는 도구 임시 변수):

| 모델 | Viper |
|---|---|
| `computeIfAbsent` | `var cur := has(S, k) ? S.m[k] : null`; `cur == null`이면 람다 본문, 결과 `e`가 null이 아니면 `S.m := S.m[k := e]`; 결과는 `cur ?: e` |
| `putIfAbsent(k, v)` | `var cur := ...`; `if (cur == null) { S.m := S.m[k := v] }`; 결과는 `cur` |
| `put(k, v)` | `var cur := ...`; `S.m := S.m[k := v]`; 결과는 `cur` |
| `remove(k)` | `var cur := ...`; `S.m := S.m[k := null]`; 결과는 `cur` |

### 2.4 경쟁 읽기: `set-once` map

v0 규칙(2.12): action 밖에서 모듈 상태를 읽은 값은 그 타입의 임의 값이다. 이 규칙은 건전하다. 그러나 B에서는 정밀하지 않다.
측정: 흔한 최적화 R2(`reqShieldMap[method] ?: reqShieldMap.computeIfAbsent(method) { ... }`)가 v0 규칙에서는 `P_same`으로 거부된다 (ARB_R2). 이것은 오경보다.
`getOrPut`도 `get`으로 시작하므로, v0 규칙에서는 `P_same`까지 깨진 것으로 나온다 (ARB_M3). 그러면 두 성질이 갈라지는 모습을 보일 수 없다.

B 규칙:
1. sidecar가 `set-once P_stable: m`을 선언하면, 도구는 `P_stable`을 모든 action 메서드의 `ensures`로 낸다 (stray action 포함).
2. 같은 선언 아래에서, 연산의 action 앞에 있는 `m.get(key)`는 "null이거나 `has(S, key) && S.m[key] == v`"인 값 `v`다. `containsKey(key) == true`는 `has(S, key)`다.
   출력: `var v_L_C: Ref` 다음에 `inhale v_L_C == null || (has(S, key) && S.m[key] == v_L_C)`. 위치는 그 읽기의 자리이고, 상태는 action의 사전 상태다.
3. 선언이 없는 map의 읽기는 v0대로 임의 값이다. action 뒤의 읽기도 임의 값이다.

건전성 논증:
1. CHM의 null 아닌 읽기는 끝난 갱신이 넣은 값을 돌려준다: "(More formally, an update operation for a given key bears a" "<em>happens-before</em> relation with any (non-null) retrieval for" (89-90행).
2. 그래서 읽기가 `v`를 돌려준 시점 `t0`에 `S.m[key] == v`였다.
3. `t0` 뒤의 모든 단계는 action이다. 모든 action은 `P_stable`을 증명한다. 그래서 그 사실은 action의 사전 상태까지 유지된다.
4. null 읽기는 아무것도 주지 않는다. 오래된 null일 수 있기 때문이다 (M1, M3의 경쟁이 바로 이것이다).
5. 이 논증은 "모든 action이 `P_stable`을 만족한다"는 귀납 가정에 기댄다. 어떤 action이 그것을 깨면 그 action의 증명이 실패하고 모듈은 FAILED가 된다. 그래서 가정이 틀린 상태에서 PROVEN이 나오지 않는다 (M6: stray `remove`가 `P_stable`로 FAILED, 측정).

이 규칙은 history ghost(키마다 매핑된 적 있는 값의 집합)보다 단순하다. 새 ghost 상태가 없고, 정밀도는 같다 (R2 PROVEN, 측정).

### 2.5 `getOrPut`과 `set` 연산자 (FIR, bytecode 측정)

`reqShieldMap.getOrPut(getTargetMethod(joinPoint)) { createReqShield(joinPoint) }`의 FIR (측정):
1. `CALL kotlin/collections/getOrPut(K, () -> V) [inline public origin=Library typeArgs=<java.lang.reflect.Method!, com.linecorp.cse.reqshield.ReqShield<T>!>]`.
2. `extension-receiver(explicit): READ property val .../ReqShieldAspect.reqShieldMap`.
3. 람다는 `inlineStatus=Inline invocationKind=UNKNOWN`이다. stdlib 2.0.21의 이 함수에는 contract가 없다.
4. dump는 호출된 오버로드의 receiver 타입을 찍지 않는다. 컴파일된 bytecode(javap)는 `ConcurrentMap.get` 다음에 `createReqShield`, 그다음에 `ConcurrentMap.putIfAbsent`를 부른다. 그래서 `ConcurrentMap` 오버로드가 골라졌다 (측정).
   frontend는 resolved symbol의 extension receiver 타입이 `java.util.concurrent.ConcurrentMap`인지 확인해야 한다. `MutableMap` 오버로드는 B에서 UNSUPPORTED다.

신뢰 모델 `kotlin.collections.getOrPut` (stdlib 2.0.21 `MapsJVM.kt:72-73`: "return this.get(key)" "?: defaultValue().let { default -> this.putIfAbsent(key, default) ?: default }"):
1. 경쟁 읽기 `v := get(key)` (2.4절).
2. `v != null`이면 결과는 `v`다.
3. 아니면 람다 본문을 연산 구간에서 실행한다 (어떤 action 밖이다). 결과를 `d`라 한다.
4. `putIfAbsent(key, d)` action을 실행한다. 결과는 `cur ?: d`다.
req-shield는 stdlib를 `kotlinApi` 2.0.21로 컴파일한다 (`libs.versions.toml:5`). 2.4.21의 본문(`MapsJVM.kt:111-116`, `return this.get(key)`는 113행)도 같다 (측정).

`reqShieldMap[method] = it`의 FIR은 `CALL kotlin/collections/set(K, V) [inline public origin=Library syntax=Operator]`다. 모델은 `put`이다.
`reqShieldMap[method]`는 `CALL java/util/concurrent/ConcurrentHashMap.get(java.lang.reflect.Method) [... syntax=Operator] : ReqShield<T>?`다.
`?:`는 `elvis`, `!!`는 `not-null-assert`, `also { }`는 `CALL kotlin/also(...)`와 `invocationKind=EXACTLY_ONCE` 람다다.

### 2.6 연산 구간 번역

v0는 action 밖 코드를 번역하지 않았다. 그 결과는 모두 자유 값이었다 (2.12). B는 연산 함수 본문 중 action 밖 부분(연산 구간)의 일부를 번역한다. M1-M4, M7, R2, `getOrPut`의 결과가 action 밖 코드에서 오기 때문이다.

연산 구간에서 번역하는 것:
1. 지역 `val`, `if`/`else`, 주체 없는 `when`, `?:`, `!!`, 조기 `return` (v0 2.4.4의 정규화 재사용).
2. `let`, `also`, `run`, `apply`와 람다 리터럴. FIR이 `invocationKind=EXACTLY_ONCE`를 줄 때만 인라인한다.
3. `fresh`/`throws` 신뢰 함수 호출 (3절, 2.7절). 연산 구간의 `fresh` 호출도 `S.created`에 기록한다 (4절).
4. state map의 경쟁 읽기 (2.4절)와 state map의 action (2.3절).
5. 그 밖의 호출 결과는 v0대로 자유 값이다. 그 호출이 모듈 상태를 인자로 받으면 UNSUPPORTED다.

규칙:
1. 한 경로는 action을 최대 1번 실행한다 (v0 U2 유지). 경로마다 정확히 한 번 `Return` 또는 `Raise`로 끝난다.
2. 연산 결과는 연산 구간의 지역 변수, action 결과, 경쟁 읽기 값, 셀, 상수를 읽을 수 있다 (v0 W10 확장).
3. 연산의 key `k`는 그 action의 key다. action이 없는 연산은 UNSUPPORTED다.
4. 반복문, `try`, 비인라인 람다가 연산 구간에서 결과나 action에 닿으면 UNSUPPORTED다. 닿지 않으면 v0대로 ledger의 `not-modeled`다.

건전성: 연산 구간의 지역 계산은 다른 스레드의 간섭을 받지 않는다. 공유 상태 읽기는 경쟁 읽기 계약(2.4절)으로만 들어온다.
연산 구간의 `fresh` 호출은 실제로는 action보다 먼저 일어난다. 모델은 그것을 action과 같은 단계에 기록한다. 생성 횟수는 순서와 무관하므로 `P_once`의 판정은 바뀌지 않는다 (4.2절).

KeyLocalLock 회귀: v0의 세 연산은 결과가 셀이고, 연산 구간의 나머지는 자유 값과 로그뿐이다. 그래서 같은 KVIR과 Viper가 나와야 한다. 수락 테스트는 v0의 세 gate를 그대로 쓴다 (8절 B3).

### 2.7 예외 경로

1. 신뢰 함수가 `throws`이면 호출마다 Bool flag 하나가 있다. sidecar가 `free NAME = call F`로 이름을 붙이면 메서드 파라미터다. 아니면 초기화하지 않은 지역 변수(임의 값)다.
2. flag가 참이면 그 호출 뒤의 문장을 건너뛰고 `exc := true`로 연산을 끝낸다. lowering은 남은 문장을 `else` 블록으로 옮긴다 (v0 2.4.4와 같은 정규화).
3. 호출이 action 안이면 map 쓰기도 건너뛴다. 이것이 `computeIfAbsent`의 "the mapping is left unestablished"와 `compute`의 "the mapping is unchanged"다. action 안에서 그 전에 바뀐 객체 필드는 그대로 남는다.
4. `!!`가 null을 만나면 같은 방식으로 `exc := true`다.
5. 연산 결과에 대한 sidecar `ensures`는 `exc`를 직접 쓴다 (`!exc ==> ...`). 도구가 자동으로 감싸지 않는다. 검토자가 정상 종료 조건을 눈으로 보게 하기 위해서다.
6. 불변식과 `set-once`는 예외 경로에서도 성립해야 한다.

v0는 action 안의 호출이 던지지 않는다고 요구했다 (2.3). `createReqShield`는 설계상 던진다 (`require`, README 102-103행). "던지지 않음"이라고 검토하면 거짓 주장이 된다. 그래서 B는 예외 경로를 모델에 넣는다.
대안(예외 경로를 빼고, "던지는 호출 앞에 상태 변경과 `fresh` 호출이 없음"을 정적으로 검사)은 9.2절 JB2에 둔다.

## 3. action 안의 모델 밖 호출

### 3.1 선택지

| 선택지 | 내용 | 판단 |
|---|---|---|
| (i) 본문 인라인 | v0의 helper 인라인 규칙으로 `createReqShield` 본문을 번역한다 | 불가. 본문에 `require`(인라인 람다), 함수 값 2-4개를 받는 설정 생성자, 모르는 라이브러리 호출이 있다. action 전체가 UNSUPPORTED가 된다 |
| (ii) 비결정 호출 (`Nondet`) | 결과를 임의 값으로 둔다 | 부족. 결과가 새 객체라는 사실이 없으면 `MI_OWN`과 `P_once`를 증명할 수 없다 |
| (iii) `fresh` 신뢰 함수 (권고) | 정상 반환은 매번 새 객체다. 던질 수 있고, 던지면 아무것도 만들지 않았다. 모듈 상태를 바꾸지 않는다 | 원본, R1, R2를 증명하고 M1-M7을 거부한다 (측정) |

### 3.2 sidecar가 말할 것

```text
trusted fun ReqShieldAspect.createReqShield(org.aspectj.lang.ProceedingJoinPoint)
  returns fresh Shield      // every normal return is a new object of the value class
  throws                    // it may throw; then it has created no Shield and changed no modeled state
  effects none              // it writes no module state
  reviewed "..."            // U7: a person read the body
```

Viper 출력: `var r_L_C: Ref := new(gFor)`, `S.created := S.created union Set(r_L_C)`, `r_L_C.gFor := k`. `throws`이면 그 앞에 flag 분기가 있다 (2.7절).

### 3.3 도구가 기계로 검사하는 것

신뢰 함수가 모듈 파일 안에 있으면 도구는 본문의 FIR을 읽는다. 다음 중 하나라도 어기면 sidecar 오류다.
1. 본문은 state 필드를 읽거나 쓰지 않는다. 그래서 "The mapping function must not modify this map during computation."(1677행)과 `effects none`의 모듈 상태 부분이 기계로 확인된다.
2. 본문은 sidecar 연산 함수를 부르지 않는다 (재진입 금지).
3. 본문의 모든 `return` 식은 값 클래스의 생성자 호출이거나, 그런 호출로만 초기화한 지역 `val`이다 (core-spring 150행 `return ReqShield(reqShieldConfiguration)`).
4. 감사 범위(2.1절 6)의 파일에서 값 클래스의 생성자 호출은 `fresh` 신뢰 함수 본문에만 있다. 다른 곳에 있으면 그 노드가 ledger `unsupported`이고 모듈은 INCOMPLETE다. 그래서 감사 범위 안에는 `P_once`가 셀 수 없는 생성 경로가 남지 않는다.
5. ledger에 7번째 처리 `trusted`를 더한다: "검토한 신뢰 함수의 본문. 번역하지 않는다" (v0 2.14 확장).

### 3.4 사람이 검토하는 것 (U7)

1. 값 클래스 생성자(`ReqShield(...)`)와 본문이 부르는 함수가 모듈 상태나 이 Aspect의 연산을 부르지 않는다.
2. 던지는 모든 호출이 생성자 호출보다 먼저 실행된다. 그래서 던질 때 `ReqShield`를 만들지 않았다. core-spring에서는 `getTargetMethod`(117행), `getCacheableAnnotation`(118행), `require`(120-123행), 설정 생성자(125-148행)가 모두 150행보다 앞이다.
3. 이 검토 결과를 `reviewed`에 이름, 날짜, 줄 범위와 함께 쓴다.

제안 (9.1절 UB3): `reviewed`를 본문 내용에 묶는다. 도구가 본문 소스 텍스트의 SHA-256 앞 8자리를 계산하고, sidecar의 `reviewed ... body "<hash>"`와 다르면 ERROR로 보고한다.
이유: 신뢰 함수가 모듈 파일 안에 있으면 AI가 그 본문을 고칠 수 있다. 예를 들어 `createReqShield`가 캐시한 객체를 돌려주게 바꾸면, 옛 검토가 조용히 남는다.

## 4. ghost 바인딩

### 4.1 B에 필요한 ghost

| ghost | 누가 정하나 | 갱신 지점 | v0와의 관계 |
|---|---|---|---|
| `S.created` | 도구 | `fresh` 호출마다 (action 안이든 밖이든) | v0의 `New` 할당 장부와 같다 (DESIGN-v0.md 3.1: "권한 장부이므로 도구가 `New`마다 낸다") |
| `r.gFor` | 도구 | 같은 지점. 값은 연산의 action key `k` | 의도 값은 명세 항에서 온다는 R1(3.5)을 지킨다 (`k`는 명세 항) |

sidecar ghost record와 커밋 규칙(END, ISSUE)은 B에서 쓰지 않는다. 그래서 ghost binder는 바뀌지 않는다.
`P_stable`과 `P_same`은 코드 상태만 말한다. `P_once`와 `MI_INV_1`은 도구 장부를 말한다.

### 4.2 생성을 `fresh` 호출에 묶는 이유

v0 3절의 증거: CAS 성공에 END를 묶은 (b)는 CAS를 지운 동치 변이 SB5, SB5b를 거부했다 (DESIGN-v0.md 466행: "(b)는 대조군 SB5와 SB5b를 거부했다. CAS를 지우면 묶인 사건도 사라진다.").
원인은 사건(CAS)이 효과(획득 종료)를 만드는 수단 하나였다는 점이다. 같은 효과를 다른 수단으로 내는 코드에서 사건이 사라졌다.

B의 생성 사건은 다르다.
1. 생성 사건은 `P_once`가 세는 대상 그 자체다. 수단이 아니다.
2. 생성 횟수를 바꾸지 않는 리팩터링은 판정을 바꾸지 않는다. 측정: R1(`compute` + elvis), R2(경쟁 fast path)는 PROVEN이다.
3. 감사 범위 안에는 `ReqShield`를 만드는 다른 길이 남지 않는다. 생성자를 직접 부르면 3.3절 규칙 4로 INCOMPLETE다. 본문을 인라인하면 action이 UNSUPPORTED다.
   남는 빈틈은 하나다: 감사 범위 밖의 함수(다른 Gradle 모듈, 라이브러리)가 안에서 `ReqShield`를 만들면 셀 수 없다. 이것은 신뢰 가정이다 (부록 B 7).
4. 생성이 action 밖에서 일어나도 같은 단계에 기록한다. 같은 key의 생성 두 개는 어떤 순서로 기록되어도 나중 기록에서 `P_once`가 깨진다.

효과 기반 대안: v0 규칙 (c)처럼 커밋 지점의 사전·사후 상태와 연산 결과만 보면, map에 들어가지 못한 객체는 보이지 않는다.
`getOrPut`의 패자 객체는 상태에도 결과에도 남지 않는다. 측정: "map에 들어간 객체만 센다"로 바꾸면 EFF_M3, EFF_M4가 PROVEN이 된다 (놓침).
그래서 `P_once`는 효과 기반으로 정의할 수 없다. 생성 호출에 묶는 것이 유일한 선택지다.

### 4.3 모델이 공허하지 않다는 확인 (측정)

1. `getOrCreate`의 세 분기(key 있음, 생성 함수가 던짐, 새로 만듦)에 각각 `refute false`를 넣었다. 셋 다 도달 가능했다.
2. probe가 성공한다: 첫 호출은 객체를 만들고, 같은 메서드의 두 번째 호출은 같은 객체를 받고, 다른 메서드는 다른 객체를 받는다.
3. hole을 빼면 `P_once`가 귀납적이지 않다 (실패). `MI_INV_1` 하나면 충분하다. "각 mapped 객체는 자기 key로 만들었다"(`S.m[qk].gFor == qk`)만 넣으면 실패한다.

## 5. sidecar, KVIR, lowering, emitter의 변화

### 5.1 sidecar 문법 (v0 4.2에 더하는 것만)

```text
decl        := ... v0 ...
             | "module" FQN                      // B: a class FQN declares an instance module (S = this)
             | "source" STRING                   // B: a directory means every .kt file under it
             | "type" NAME "=" "opaque" [ KCLASS ]  // B: KCLASS binds the abstract type to a Kotlin class
             | "set-once" ID ":" NAME            // B: every action keeps each mapped key's value (2.4)
tclause     := ... v0 ... | "returns" "fresh" NAME | "throws"
probestep   := NAME { "," NAME } ":=" NAME "(" ARGS ")" | "assert" VEXPR    // B: an operation with exc
```

새 예약 이름 (v0 4.6 표에 더함):

| 이름 | 뜻 |
|---|---|
| `exc` | 연산이 예외로 끝났다 (`throws` 호출이나 `!!`) |
| `null` | `Ref` 결과의 null (Viper `null`) |
| `S.created` | 도구 장부: `fresh` 신뢰 함수가 돌려준 모든 객체 (v0에서는 `New` 객체) |
| `r.gFor` | 도구 장부: 객체 `r`을 만든 연산의 action key |

새 개념과 그 근거:

| 개념 | 왜 필요한가 |
|---|---|
| 인스턴스 `module` | 상태가 companion이 아니라 인스턴스 필드다 |
| `type ... = opaque KCLASS` | key가 String이 아니다 |
| `set-once` | `P_stable`을 모든 action에 낸다. 같은 선언이 경쟁 읽기 계약을 정당화한다 (R2 오경보 제거, 측정) |
| `returns fresh`, `throws` | 매핑 함수가 모델 밖 코드를 부른다. 그 코드는 설계상 던진다 |
| `exc`, 여러 결과의 probe 호출 | 예외 경로를 성질과 probe에서 말해야 한다 |
| `source` 디렉터리 | `internal` 필드의 감사 범위 |

넣지 않은 것: 일반 2-상태 성질(`step`), 키별 history ghost, 키별 생성 counter ghost record, 코드 지점 주석. B의 성질에는 필요 없다.

### 5.2 KVIR

```kotlin
// Additions to DESIGN-v0.md 1.2 (sketch; names may change in the implementation).
enum class ModuleKind { OBJECT, INSTANCE }                      // Module.kind
// MapVar gains: val setOnce: String?   (the set-once property ID)
// AbsType gains: val kclass: String?   (opaque type bound to a Kotlin class, e.g. java/lang/reflect/Method)
enum class ActionKind { COMPUTE, COMPUTE_IF_ABSENT, PUT_IF_ABSENT, PUT, REMOVE }
// Action gains: val kind: ActionKind, val value: Expr? (PUT, PUT_IF_ABSENT), val out: Local? (the call's result)
// Operation: v0 (action, cells, result) becomes a body with the action as one statement.
data class Operation(val name: String, val kotlin: String, val pos: Pos, val body: List<Stmt>,
                     val cells: List<Cell>, val result: Local, val canThrow: Boolean)
data class RunAction(val action: Action, override val pos: Pos) : Stmt               // at most once per path
data class Fresh(val v: Local, val cls: ObjClass, val fn: String, override val pos: Pos) : Stmt
data class RacyGet(val v: Local, val map: MapVar, val key: Expr, override val pos: Pos) : Stmt
data class Return(val e: Expr, override val pos: Pos) : Stmt
data class Raise(override val pos: Pos) : Stmt                                       // exc := true, rest skipped
```

well-formedness 변화:

| ID | 변화 |
|---|---|
| W3 | action 경로는 `Exit` 또는 `Raise`로 끝난다 |
| W6 | action 안에서 모듈 객체에 닿는 길에 `Fresh` 결과를 더한다 |
| W10 | 연산 본문의 모든 경로는 `RunAction`을 최대 1번 실행하고, `Return` 또는 `Raise` 하나로 끝난다. 결과는 2.6절 규칙 2의 값만 읽는다 |
| W15 (새) | `Fresh`는 `returns fresh` 신뢰 함수 호출이다. 감사 범위에서 값 클래스 생성자 호출은 그 함수 본문에만 있다 |
| W16 (새) | `RacyGet`의 계약은 그 map이 `set-once`일 때만 붙는다 |

### 5.3 lowering

1. action 발견 (v0 2.2 확장): receiver가 state map인 2.3절의 CHM 호출, 그리고 extension receiver가 state map인 `kotlin/collections/set`, `getOrPut`. 오버로드는 파라미터 타입과 receiver 타입으로 가린다.
2. state가 아닌 map의 CHM 호출은 ledger `not-modeled`다. v0는 이것을 UNSUPPORTED로 했다 (2.2.2). 그대로 두면 `expressionMap`, `keyGeneratorMap` 때문에 세 모듈이 모두 INCOMPLETE가 된다. 그 람다 안의 노드도 ledger가 감사한다. state 쓰기가 있으면 v0대로 UNSUPPORTED다.
3. `computeIfAbsent` 람다(SAM, NoInline)가 action 본문이다. 결과 위치 규칙은 v0 2.4와 같다. null이 아닌 결과는 `Put(e)`, null은 `Keep`이다.
4. 연산 구간 (2.6절), 예외 경로 (2.7절), 인스턴스 모듈 (2.1절), 신뢰 본문 검사 (3.3절).
5. Kotlin 타입이 non-null인 `Ref` 자유 값에는 타입 사실 `inhale v != null`을 낸다 (v0의 `t != NULL`과 같은 규칙).
6. 같은 `trusted fun`을 연산에서 두 곳 이상 부르면, `free` 이름은 첫 호출에 묶고 나머지는 이름 없는 flag다. `free ... = call F`인데 `F`를 부르지 않으면 INFO다 (쓰이지 않는 파라미터). M5처럼 생성 호출을 없앤 변이가 ERROR가 아니라 FAILED로 끝나게 하기 위해서다.

### 5.4 emitter

action 메서드 템플릿 (v0 5.2에서 바뀐 줄만):

```text
method <op>(S: Ref, k: <Key>, <named free values...>) returns (<result>: <T>, exc: Bool)   // exc: only if canThrow
  requires MI(S)
  ensures MI_PERMS(S)
  ensures MI_OWN(S)
  ensures <set-once ID>(S)                 // B: one line per set-once map, right after MI_OWN
  ensures <invariants> ...                 // P_once
  ensures MI_INV_1(S) ... MI_INV_n(S)
  ensures HIST                             // only with a ghost record
  ensures <allowance clauses>              // only with a ghost record
  ensures <sidecar ensures>                // P_same, progress_ok
{
  exc := false                             // only if canThrow
  <lowered operation body: segment statements, the action body at RunAction, Return/Raise>
}
```

1. `set-once` 줄을 `MI_OWN` 바로 뒤에 둔다. 측정: 이 순서에서 M1, M2, M6은 `P_stable`로, M3, M4, M7은 `P_once`로, M5는 `MI_OWN`으로 처음 보고된다. 각 변이의 가장 구체적인 원인이다.
2. `MI_PERMS`는 `fresh` 클래스가 있으면 `created` 객체의 `gFor` 권한을 더한다.
3. `MI_OWN`은 v0처럼 state map마다 낸다. B에서는 값 클래스가 `New`나 `fresh`로만 만들어질 때 낸다. 모듈이 만들지 않는 값(예: bean 조회 결과)의 map에는 내지 않는다.
4. 새 임시 변수 이름: `cur_<line>_<col>`(action의 현재 값), `r_<line>_<col>`(`fresh` 결과), `v_<line>_<col>`(경쟁 읽기 값). `spike/vpr_normalize.py`의 `LOCAL` 패턴에 이 세 접두어를 더해야 한다.
5. stray action은 기본 계약을 받는다: `MI`와 모든 `set-once` 줄. M6가 이것으로 거부된다 (측정).

### 5.5 보고서

1. "Spec to review"에 `set-once`, `fresh`/`throws` 주장, `reviewed` 문구를 더한다.
2. "Assumptions"에 더한다: `set-once` map의 경쟁 읽기 계약, `Method.equals`를 `==`로 둠, test 소스를 감사하지 않음(`internal` 필드), 생성자의 효과(3.4절의 사람 검토).
3. `MI_OWN` 실패는 "map 값이 이 모듈이 만든 객체가 아니거나, 두 key가 한 객체를 공유한다"로 보고한다. 이 Aspect에서는 `CLAUDE.md:27`의 "per annotated method"다.

### 5.6 바뀌지 않는 것

v0의 KVIR 노드, ghost binder(END, ISSUE, 권한 기본값), hole 흐름(M7), 분기 도달성(M9), Silicon 예산, 종료 코드는 그대로다. KeyLocalLock의 세 gate와 `mutantsReqShield` 33회 결과가 바뀌면 회귀다.

## 6. sidecar

### 6.1 core-spring `reqShieldMap` 전체 sidecar

파일 제안: `specs/reqshield/aspect.core-spring.kv` (모듈 줄과 신뢰 함수)와 `specs/reqshield/aspect.common.kv` (나머지). 아래는 둘을 합친 내용이다 (주석 포함 61줄, 측정: 줄 수).

```text
// Sidecar spec for req-shield ReqShieldAspect.reqShieldMap (core-spring). Format: kv-sidecar/0 with the B
// additions (DESIGN-B.md 5.1). Expressions are Viper text over DESIGN-v0.md 4.6 and DESIGN-B.md 5.1.
// `source` paths are relative to the req-shield checkout (kv resolve --root).

module com.linecorp.cse.reqshield.spring.aspect.ReqShieldAspect   // a class: each instance is one module
source "core-spring/src/main/kotlin"                               // reqShieldMap is internal: audit every main file

// ---- Abstract types ----
type MethodKey = opaque java.lang.reflect.Method                    // == is Method.equals

// ---- Module state ----
state m: Map<MethodKey, Shield> = reqShieldMap
set-once P_stable: m                    // a mapped method keeps its ReqShield (checked in every action)

// ---- Classes reachable from the state ----
class Shield = ReqShield { }            // ReqShield<T>: no field is modeled

// ---- Trusted models (built-in, DESIGN-B.md 2.3) ----
use jdk.ConcurrentHashMap.computeIfAbsent
use jdk.ConcurrentHashMap.compute
use jdk.ConcurrentHashMap.putIfAbsent
use jdk.ConcurrentHashMap.put           // also kotlin.collections.set on a state map
use jdk.ConcurrentHashMap.remove
use jdk.ConcurrentHashMap.racyRead
use kotlin.collections.getOrPut

// ---- Trusted project functions (bodies read by a person) ----
trusted fun ReqShieldAspect.createReqShield(org.aspectj.lang.ProceedingJoinPoint)
  returns fresh Shield                  // every normal return is a new ReqShield
  throws                                // it may throw; then it has created no ReqShield
  effects none                          // it writes no module state (the tool checks the body too)
  reviewed "<reviewer> <date>: core-spring ReqShieldAspect.kt:116-151: the only return is ReqShield(reqShieldConfiguration) (150); every call that can throw (117, 118, require 120-123, the configuration constructor 125-148) runs before it; the body reads the annotation and instance vals and calls no operation of this aspect"

// ---- Operations: exactly one atomic action each ----
operation getOrCreate = fun ReqShieldAspect.getOrCreateReqShield {
  free thrown = call createReqShield    // named, so the probe can fix it
  result res
  ensures P_same: !exc ==> res != null && has(S, k) && S.m[k] == res
  ensures progress_ok: !thrown ==> !exc
}

// ---- State properties ----
invariant P_once: forall qr1: Ref, qr2: Ref :: { qr1 in S.created, qr2 in S.created }
    qr1 in S.created && qr2 in S.created && qr1.gFor == qr2.gFor ==> qr1 == qr2

// ---- Inductive invariant: written by an LLM (E3 workflow), checked by the tool ----
hole MI_INV """
  // Every created object is the value the map holds for the method it was created for.
  (forall qr: Ref :: { qr in S.created } qr in S.created ==> has(S, qr.gFor) && S.m[qr.gFor] == qr)
"""

// ---- Non-vacuity probe: a scenario from init ----
probe(k1: MethodKey, k2: MethodKey) requires k1 != k2 {
  r1, e1 := getOrCreate(k1, thrown = false)
  assert !e1 && r1 != null
  r2, e2 := getOrCreate(k1, thrown = false)              // the same method again: the same object
  assert !e2 && r2 == r1
  r3, e3 := getOrCreate(k2, thrown = false)              // another method: its own object
  assert has(S, k1) && S.m[k1] == r1                     // trigger for P_stable (see note 3)
  assert !e3 && r3 != r1
}
```

참고:
1. `reviewed`의 `<reviewer> <date>`는 사람이 본문을 읽은 뒤 채운다. 이 문서는 검토를 대신하지 않는다 (U7).
2. `use` 목록은 원본에 필요한 것(`computeIfAbsent`)보다 넓다. 그래야 `getOrPut`, `put` 같은 재작성이 UNSUPPORTED가 아니라 FAILED로 끝난다 (9.2절 JB7).
3. probe의 `assert has(S, k1) && S.m[k1] == r1` 줄은 Silicon의 trigger를 위해 필요했다. 이 줄이 없으면 `r3 != r1`이 실패한다 (측정). 그래서 `P_stable`의 trigger를 `{ qk in domain(S.m) } { S.m[qk] }` 두 벌로 낸다.
4. `res != null`의 `null`은 Viper `null`이다. v0의 `NULL`(`Token?`의 -1)과 다르다.

### 6.2 세 모듈의 공통부

가능하다. v0의 KeyLocalLock 변형과 같은 방식이다 (`include` 한 단계).

| 파일 | 내용 |
|---|---|
| `aspect.common.kv` | `type`, `state`, `set-once`, `class`, `use`, `operation`, `invariant`, `hole`, `probe` |
| `aspect.core-spring.kv` | `module ...spring.aspect.ReqShieldAspect`, `source "core-spring/src/main/kotlin"`, `trusted fun` (116-151, 150행), `include` |
| `aspect.webflux.kv` | `module ...spring.webflux.aspect.ReqShieldAspect`, `source "core-spring-webflux/src/main/kotlin"`, `trusted fun` (129-163, 162행), `include` |
| `aspect.coroutine.kv` | `module ...spring.webflux.kotlin.coroutine.aspect.ReqShieldAspect`, `source "core-spring-webflux-kotlin-coroutine/src/main/kotlin"`, `trusted fun` (236-264, 263행), `include` |

근거:
1. 짧은 이름 `ReqShieldAspect.getOrCreateReqShield`, `ReqShieldAspect.createReqShield`, `reqShieldMap`이 세 모듈에 모두 있다 (측정: FIR dump).
2. `class Shield = ReqShield { }`는 import를 통한 KNAME 찾기(2.2.6)로 세 패키지의 `ReqShield`에 각각 풀린다. 이 규칙을 넣지 않으면 `class` 줄이 모듈 파일로 간다 (+1줄씩).
3. `trusted fun`은 모듈 파일에 둔다. 본문이 모듈마다 다르고 (`executor`, `scheduler`, `scope`), 사람이 세 본문을 따로 읽어야 하기 때문이다. 본문 지문(UB3)을 채택하면 지문도 모듈마다 다르다.
4. 컴파일 classpath는 sidecar가 아니라 CLI가 정한다. 측정: core-spring은 core 소스와 함께 Spring 6.2.19 jar 6개, aspectjweaver 1.9.25.1, slf4j 1.7.36, stdlib 2.0.21, annotations 13.0, support jar로 오류 없이 컴파일된다. webflux와 coroutine은 reactor-core 3.7.19, reactive-streams 1.0.4, kotlinx-coroutines(-core-jvm, -reactor, -reactive) 1.8.1을 더하면 된다.

### 6.3 `expressionMap`과 `keyGeneratorMap`

| map | 같은 프로토콜인가 | 문서 | 판단 |
|---|---|---|---|
| `expressionMap` (`String` → `Expression`) | 예. `computeIfAbsent { spelParser.parseExpression(it) }`. 파서는 호출마다 새 식을 만들고 `ParseException`을 던질 수 있다 | coroutine 86행만 있다: "Parsing a SpEL expression is expensive, so each annotation key is parsed only once." core-spring과 webflux에는 문장이 없다 | 선택 마일스톤 B5. 비용 0.5-1 인일 (추정) |
| `keyGeneratorMap` (`String` → `KeyGenerator`) | 부분적. 값은 `beanFactory.getBean(...)`의 bean 조회 결과다. 보통 singleton이라 "생성"이 아니다 | 없다 | 범위 밖. `P_once`는 뜻이 없다. alias 두 개가 같은 bean을 가리키면 `MI_OWN`이 거짓이 된다 |

`expressionMap`을 넣을 때의 비용:
1. `state`, `set-once`, `class`, `trusted fun` (라이브러리 함수 `SpelExpressionParser.parseExpression`) 각 1개, 연산 1개, 불변식 1개.
2. coroutine 모듈은 `computeIfAbsent`를 `getCacheKeyOrDefault` 안에 직접 쓴다 (196:34). 그 함수의 결과는 cache key 문자열이지 식이 아니다. 그래서 "action 결과" 어휘(`act`)가 하나 더 필요하다. 이것이 B5를 선택으로 두는 이유다.

## 7. 변이 실험 (측정)

### 7.1 방법

1. 목표 모델 `spike/aspect/target.vpr`을 손으로 썼다. 원본 Kotlin을 B 규칙(2-5절)대로 내린 모양이다.
2. 변이는 `spike/aspect/mutants.py`다. 각 변이는 Kotlin 편집을 B lowering이 낼 Viper 편집으로 옮긴 것이다. Kotlin 편집은 주석에 있다. 편집마다 메서드 안에서 정확히 한 번 맞아야 한다.
3. `bash spike/run_mutants.sh aspect`로 검증했다. 판정 줄은 `spike/aspect/expected-verdicts.txt`에 저장했다.
4. 깨지는 의무 전체는 scratch 스크립트로 쟀다. 변이 파일마다 `MI_PERMS`와 의무 하나만 남긴 파일 6개를 만들어 검증했다 (14개 파일 × 6 = 84회).
5. 원본은 1.94 s에 검증된다 (`init` 0.17 s, `getOrCreate` 0.31 s, `probe` 0.36 s, 측정). v0 목표 모델은 3.93 s였다 (DESIGN-v0.md 8.3).

### 7.2 결과

0절의 표가 결과다. 실패 위치는 모두 `getOrCreate`의 `ensures` 줄이다. M6만 stray 메서드 `stray_aroundReqShieldCacheable_100`의 `ensures P_stable(S)` 줄이다.

변이마다 판정을 지키는 이유:
1. M1, M2: 오래된 null 읽기 뒤나 무조건 `put`이 있는 매핑을 덮어쓴다. `P_stable`이 깨진다. 덮인 객체는 map을 잃으므로 `MI_INV_1`도 깨진다. 두 번째 생성이므로 `P_once`도 깨진다.
2. M3, M4: 패자 객체가 만들어진 뒤 버려진다. `P_once`만 깨진다. 결과는 승자이므로 `P_same`과 `P_stable`은 지켜진다.
3. M7: 패자가 자기 객체를 돌려준다. `P_same`과 `P_once`가 깨진다.
4. M5: 공유 객체는 인스턴스 `val`의 자유 값이다. 모듈이 만든 객체라는 보장이 없고, 다른 key도 같은 값을 가질 수 있다. `MI_OWN`의 두 conjunct가 모두 깨진다.
5. M6: stray `remove`가 기본 계약의 `P_stable`을 깬다. sidecar가 이 경로를 말하지 않아도 거부된다.
6. R1: `compute`의 `Keep`/`Put` 출력이 `computeIfAbsent`와 같은 상태 변화를 낸다. `!!`의 예외 분기는 도달하지 않는다 (측정: 그 분기의 `refute false`가 "could not be reached").
7. R2: `set-once` 경쟁 읽기 계약 덕분에 fast path가 map의 현재 값을 돌려준다.

### 7.3 설계 대안 (측정)

| 대안 | 변이 | 결과 | 결론 |
|---|---|---|---|
| v0 경쟁 읽기 (임의 값) | ARB_R2 | `P_same` 실패 | 정상 최적화를 거부한다 (오경보). 2.4절 규칙이 필요하다 |
| v0 경쟁 읽기 | ARB_M3 | `P_once`, `P_same` 실패 | `getOrPut`에서 두 성질이 갈라지지 않는다 |
| 효과 기반 생성 계수 | EFF_M3, EFF_M4 | PROVEN | 실제 버그를 놓친다. 생성은 `fresh` 호출에 묶는다 (4.2절) |

## 8. 구현 계획

feature-developer가 순서대로 구현한다. 마일스톤은 수락 테스트가 통과해야 끝난다. 추정은 FIR과 Viper를 아는 사람 엔지니어 1명 기준이다. AI 에이전트 세션의 시간은 측정하지 않았고, 비교할 수 없다.

| B | 내용 | 수락 테스트 | 추정 (인일) |
|---|---|---|---|
| B0 | 세 Aspect 모듈의 컴파일 설정 (`translator/build.gradle.kts`의 classpath와 core 소스), 새 FIR 모양의 fixture | 1. `kv discover`가 파일마다 CHM 호출 3개를 1.1절의 위치에서 찾는다. 2. `reqShieldMap` 호출만 action이고 나머지 둘은 `not-modeled`다. 3. `getOrPut` fixture에서 frontend가 `ConcurrentMap` 오버로드와 `MutableMap` 오버로드를 가린다 | 0.5-1 |
| B1 | sidecar 문법·resolver (5.1절) | 1. 세 Aspect sidecar와 `include`를 읽고 이름을 FIR symbol에 묶는다. 2. 오류 테스트: map이 아닌 state의 `set-once`, 알 수 없는 key 클래스의 `opaque`, `reviewed` 없는 `fresh` 함수, `protected` state 필드, import로 풀리지 않는 KNAME. 모두 sidecar 줄·열을 보고한다 | 1-1.5 |
| B2 | KVIR과 lowering (2.1-2.7절, 3.3절, 5.2-5.3절) | 1. 세 모듈 원본의 golden KVIR. 2. fixture(정상 1 + 거부 1): elvis, `!!`, `also`/`let`, 조기 `return`, `getOrPut`, `set`, `putIfAbsent`, `remove`, action 안·밖의 `fresh`, action 안·밖의 `throws`, `set-once`와 일반 map의 경쟁 읽기, 신뢰 본문 검사(본문의 state 쓰기, 생성자가 아닌 `return`), 신뢰 함수 밖의 값 클래스 생성자, 경로에 action 2개. 3. W3, W6, W10, W15, W16마다 맞는 것 1개와 틀린 것 1개 | 3-5 |
| B3 | emitter와 gate (5.4절) | 1. 생성한 core-spring 모델이 `spike/aspect/target.vpr`과 정규화 후 같다 (`vpr_normalize.py`에 새 임시 이름 접두어 추가). 2. M1-M7, R1, R2의 Kotlin 변이에서 생성한 모델이 `mutants.py`의 출력과 같다. 3. webflux와 coroutine 모델이 검증된다. 4. KeyLocalLock의 세 gate가 그대로 0으로 끝난다 | 1.5-2.5 |
| B4 | sidecar 3+1개, Kotlin 변이 스크립트, 회귀 | 1. `kv verify`가 세 모듈에서 PROVEN이다. 2. Kotlin 변이 M1-M7이 7.2절의 의무로 FAILED, R1과 R2는 PROVEN이다 (세 모듈 × 9). 3. `mutantsReqShield` 33회가 바뀌지 않는다 | 1-2 |
| 합계 | B0-B4 | | 7-12 (추정) |
| B5 (선택) | `expressionMap` (6.3절) | coroutine 86행의 "parsed only once"를 `P_once`로 증명한다. `act` 어휘 fixture | 0.5-1 |
| B6 (선택) | `reviewed` 본문 지문 (3.4절) | `createReqShield` 본문을 바꾼 fixture가 ERROR로 끝난다 | 0.5 |
| B7 (선택) | `P_key` (1.6절) | key를 `signature.method`로 바꾼 변이가 FAILED다 | 1.5-2.5 |

위험이 가장 큰 곳은 B2의 연산 구간 번역이다. v0가 번역하지 않던 영역이므로 KeyLocalLock 회귀(B3 수락 테스트 4)를 매 단계 돌린다.

## 9. 열린 질문

### 9.1 사용자가 정할 것

| ID | 질문 | 권고 |
|---|---|---|
| UB1 | `P_once`를 증명하는가? `CLAUDE.md:27`은 "한 객체를 공유"로도, "한 번만 생성"으로도 읽힌다. 지금 중복 생성의 비용은 쓰레기 객체뿐이다. `15e24d5` 이전에는 스레드 누수였다 | 예. 별도 ID로 두어 모듈마다 뺄 수 있게 한다 |
| UB2 | `set-once` map의 경쟁 읽기 계약(2.4절)을 신뢰 모델 규칙으로 받아들이는가? 건전성은 "모든 action이 `P_stable`을 증명한다"에 기댄다 | 예. 없으면 R2 같은 fast path가 오경보다 |
| UB3 | `reviewed`를 본문 지문에 묶는가 (3.4절)? 본문을 고칠 때마다 다시 검토해야 한다. v0의 `support` 신뢰 함수에도 적용된다 | 예 (B6) |
| UB4 | `P_key`(key = 대상 메서드, 설정은 자기 메서드의 것)를 다음 범위에 넣는가? `cbabe26`이 고친 버그 부류를 잡는다 | B 뒤에 B7로 |
| UB5 | `expressionMap`을 범위에 넣는가? 문서는 coroutine 모듈에만 있다 | 선택 B5 |
| UB6 | `internal` state 필드의 test 소스를 감사하지 않는 가정을 받아들이는가? 지금 test는 읽기만 한다 | 예. 보고서 가정 목록에 싣는다 |

### 9.2 메인 세션이 정할 것

| ID | 판단 | 이 문서의 의견 |
|---|---|---|
| JB1 | 템플릿에서 `set-once` 줄의 위치 | `MI_OWN` 바로 뒤. 측정한 처음 보고 의무가 각 변이의 원인과 맞는다 |
| JB2 | 예외 경로를 모델에 넣는가 (2.7절), 아니면 빼고 정적 검사를 하는가 | 넣는다. 정적 규칙은 "던지는 호출 앞에 상태 변경과 `fresh` 호출이 없음"이어야 하고, `fresh` 뒤에 던지는 경우(생성 후 버림)를 놓치기 쉽다. 목표 모델은 넣은 형태로 1.94 s다 |
| JB3 | `free ... = call F`인데 `F` 호출이 없을 때 | INFO (5.3절 6) |
| JB4 | 새 임시 변수 이름과 `vpr_normalize.py`의 `LOCAL` 패턴 | `cur_`, `r_`, `v_` 접두어 (5.4절 4) |
| JB5 | KNAME을 모듈 파일 import로 찾는가 | 예 (6.2절 근거 2) |
| JB6 | `source`가 디렉터리를 받는가 | 예. 아니면 core-spring 파일 6개를 나열한다 |
| JB7 | sidecar `use`에 원본이 쓰지 않는 CHM 모델을 나열하는가 | 예. 쓰이지 않은 `use`는 INFO다 |
| JB8 | key 식을 두 번 계산하는 코드 (`getTargetMethod(jp)` 두 번)는 자유 값 두 개다. 그런 fast path는 `P_same` 오경보가 된다 | B에서는 받아들인다 (v0 J6: 상수·순수 호출 전파 없음). `P_key`(B7)의 순수 신뢰 함수가 이것도 고친다 |
| JB9 | 2.4절 계약과 4.2절 생성 바인딩에 critic 검토(CLAUDE.md rule 2)를 두는가 | 둘 다 되돌리기 비싼 규칙이다. 이 작업은 advisor를 쓰지 않았다 (지시). 메인 세션이 정한다 |

### 9.3 결정 (2026-10-23)

| ID | 결정 |
|---|---|
| UB1 | 사용자 결정: B를 구현하고 `P_once`를 증명한다. `P_once`만이 `getOrPut`처럼 조용한 중복 생성을 잡는다 (M3, M4: 측정) |
| UB2 | 받아들인다. 2.4절 규칙은 가정이 아니라 유도다: CHM의 null 아닌 읽기 보장(신뢰)과, 모든 action이 증명하는 `P_stable`에서 나온다. `set-once`가 없는 map의 읽기는 v0대로 임의 값이므로 KeyLocalLock의 SB2, E4 검출은 그대로다 |
| UB3 | 사용자 결정: `reviewed`를 검토한 텍스트의 지문에 묶는다. 전체 정책이며 v0의 메모 7개에도 적용한다. B6은 선택이 아니라 필수다. 자세한 규칙은 DESIGN-v0.md 8.3 "reviewed 지문" |
| UB4, UB5 | 뒤로 미룬다: `P_key`(B7), `expressionMap`(B5). `keyGeneratorMap`은 범위 밖이다 |
| UB6 | 받아들인다: test 소스의 `internal` 필드 쓰기는 감사하지 않는다. 보고서의 가정 목록에 싣는다 |
| JB1-JB8 | 9.2절 의견대로 정한다. JB4: 메인 세션이 `spike/vpr_normalize.py`의 `LOCAL`에 `cur_`, `r_`, `v_`를 더했다 (기존 세 gate exit 0, aspect 목표 모델로 이름 바꾸기·합치기 확인) |
| JB9 | critic을 두지 않는다. 두 대안 모두 측정으로 탈락했다 (ARB_R2 오경보, EFF_M3/M4 버그 놓침). 그래서 CLAUDE.md rule 2의 "분명한 승자 없음" 조건이 아니다. advisor 검토를 거쳤다 |
| 구현 순서 | 두 번에 나눈다. 1차: B0, B1, B2 (KeyLocalLock 회귀 유지). 2차: B3, B4, B6 |
| 메인 세션 확인 | `spike/aspect/target.vpr` Silicon 1.89 s, 변이 13개 판정이 `expected-verdicts.txt`와 같다. `15e24d5` 이전의 core `KeyLocalLock`은 인스턴스마다 `lockMap`과 `newSingleThreadExecutor`, `while (true)` 감시를 가졌다 (req-shield 기록) |

### 9.4 1차 구현 뒤 결정 (2026-10-24)

메인 세션 확인: 테스트 345개, KeyLocalLock gate 3개 exit 0, `mutantsReqShield` 33회 불일치 0 (120.5 s). `lowerAspectCoreSpring`의 KVIR은 `spike/aspect/target.vpr`의 `getOrCreate`와 구조가 같다.

| 질문 | 결정 |
|---|---|
| 1 | 연산 구간 번역은 instance 모듈에만 둔다. object 모듈(KeyLocalLock)은 v0 규칙 그대로다. 합칠 필요가 생기면 그때 정한다 |
| 2 | 연산 하나의 action 호출은 정확히 1개다. 서로 다른 경로의 2개도 UNSUPPORTED다 (v0 U2와 같은 방향, 보수적) |
| 3 | emitter 이름 규칙: 연산이 그대로 돌려주는 action 값(`return out`)은 결과 변수 `res`로 쓴다. `out_` 지역은 만들지 않는다. 목표 모델과 `mutants.py`의 형태가 이 규칙이다. 다른 경우에 `out_`이 필요하면 열린 질문으로 보고한다 (보호 파일 `vpr_normalize.py`는 메인 세션이 고친다) |
| 4 | B4에서 측정한다. 모듈 판정 규칙은 v0 5.8 그대로다 (FAILED가 하나라도 있으면 FAILED) |
| 5 | 건전성 규칙: 같은 경로에서 `fresh` 호출 뒤, action 앞에 실행되는 모델 밖 호출과 `!!`는 던질 수 있다 (비결정 raise). 그런 raise는 만든 객체를 버리므로 `MI_INV_1`/`P_once`가 실패해야 한다. 그 밖의 위치(생성 전, action 뒤)의 raise는 생성 수에 영향이 없으므로 v0처럼 자유 값으로 둔다. 이유: 이 규칙이 없으면 "생성 → 던질 수 있는 호출 → putIfAbsent" 코드에서 실제로 가능한 중복 생성을 PROVEN으로 놓친다 |
| 6 | 신뢰 본문 규칙 2(연산 호출 금지)의 기계 검사는 직접 호출만 본다. 헬퍼를 거친 호출은 사람 검토(`reviewed`)가 맡는다. 보고서의 가정 목록에 싣는다 |
| 7 | state 가시성 규칙(public/protected state는 sidecar 오류)을 모든 모듈에 적용한다. 감사 밖의 코드가 state에 쓸 수 있으면 건전하지 않기 때문이다 |
| 8 | `use kotlin.collections.getOrPut` 하나로 충분하다. 보고서의 신뢰 모델 목록에 getOrPut의 inline 본문 가정(부록 B 6)을 싣는다 |
| 9 | `createReqShield` 초안 메모 3개는 B 끝에 사용자 서명을 받는다. 지문(B6)은 함수 본문에 묶이므로 메모 문장과 무관하다 |

### 9.5 2차 구현 뒤 결정 (2026-10-24)

메인 세션 확인: 테스트 375개, gate 4개 exit 0 (aspect core-spring, KeyLocalLock 3종), `mutantsReqShield` 33회 불일치 0, `mutantsAspect` 30회 불일치 0 (세 모듈의 판정과 의무가 같다). specs/의 변경은 `sha256` 접미사 10개뿐이고 메모 문장은 그대로다. 지문 3개(`nowToEpochTime`, tryLock `issue`, unLock P3a)를 따로 계산해 같음을 확인했다. `createReqShield` 세 본문과 세 `ReqShield` 클래스(초기화식, `init` 없음)를 원문으로 읽었다.

| 질문 | 결정 |
|---|---|
| 1 | 메인 세션이 `spike/aspect/mutants.py`의 M2, M4, M7에 질문 5의 raise 경로를 넣었다 (`d6cdcd9`). M2의 key 인자도 fresh 뒤에 실행되므로 M2가 셋째다. 판정 줄은 그대로이고, 손 모델의 실패 의무가 도구와 같다. `GateTest`는 이제 아홉 변이를 그대로 비교한다 (옛 `mutants.py`로는 M2에서 실패함을 확인) |
| 2 | 측정한 처음 보고 의무를 기대값으로 받아들인다. 7.2절 "깨지는 의무 전체"의 부분집합이다 |
| 3 | 그대로 둔다. 원본에는 질문 5 raise가 없어 `progress_ok`가 성립한다. fresh와 action 사이에 모델 밖 호출이 있는 코드에서는 `MI_INV_1` 실패가 진짜 결함(버려진 객체)이다 |
| 4 | 보수적 해석을 유지한다. slf4j 호출은 sidecar가 `use slf4j.Logger`를 선언할 때만 모델 안이다 |
| 5 | 라이브러리 trusted fun의 소스를 `--root` 아래 `src/main/kotlin/<package>`에서 찾는 규칙을 받아들인다. 문법은 바꾸지 않는다 |
| 6 | 바꾼다: trusted fun의 지문 단위는 첫 modifier부터 선언 끝까지다 (KDoc과 annotation 제외). 이유: `private`를 넓히면 감사 밖의 코드가 이 함수로 객체를 만들 수 있고, 그 생성은 `P_once`가 세지 못한다 (부록 B 7). 공개 범위 변경은 재검토를 요구해야 한다 |
| 7 | 받아들인다. 지문은 텍스트의 지문이고, 메모는 절마다 따로 붙는다 |
| 8 | 지금은 필요 없다. 필요해지면 메인 세션이 `vpr_normalize.py`의 `LOCAL`에 `out`을 더한다 |
| 9 | 사용자 서명을 요청한다 |
| 다른 점 1-10 | 받아들인다: sidecar가 `exc`를 쓰면 raise가 없어도 `exc`를 둔다, 읽지 않는 put/remove 결과는 `var cur`를 만들지 않는다, raise 뒤 구간은 `if (!exc)`로 감싼다, instance 모듈의 M9는 KVIR `if` 분기만 probe한다, 보고서 추가 줄 |

### 9.6 B 완료 (2026-10-24)

- 사용자 결정: `createReqShield` 세 메모에 서명했다 (`ykhfree 2026-10-24:`). 메인 세션이 세 본문과 세 `ReqShield` 클래스를 원문으로 읽고 근거를 제시했다. 지문은 첫 modifier부터의 Kotlin 선언에 묶인다 (`c561637`; core-spring `6e2380555c453306`을 메인 세션이 따로 계산해 같음을 확인).
- 최종 확인 (메인 세션): 테스트 377개, gate 4개 exit 0, `mutantsReqShield` 33회와 `mutantsAspect` 30회 불일치 0, `verifyAspect` 세 모듈 PROVEN (Silicon 약 2.3 s).
- 미룬 것: `P_key`(B7), `expressionMap`(B5), 헬퍼를 거친 연산 호출의 기계 검사(9.4 질문 6), object 모듈의 연산 구간 번역(9.4 질문 1).

## 부록 A. 재현

1. 목표 모델: `spike/silicon.sh spike/aspect/target.vpr` (`TIMES=1`이면 메서드별 시간).
2. 변이: `bash spike/run_mutants.sh aspect`. 결과는 `spike/aspect/mutants/`와 `spike/aspect/mutants.list`에 생긴다 (`.gitignore`에 있음). 판정 줄은 `spike/aspect/expected-verdicts.txt`와 같아야 한다.
3. FIR dump: `frontend-probe/build/libs/frontend-probe.jar`를 `-Xplugin`으로 넘겨 K2JVMCompiler(2.4.21)로 컴파일했다. 옵션은 `-language-version 2.0 -api-version 2.0 -jvm-target 17 -Xjsr305=strict -no-stdlib -no-reflect`이다.
   core-spring은 `core/src/main/kotlin`과 `core-spring/src/main/kotlin`을 함께 컴파일했다. 프로젝트 파일은 바꾸지 않았다.
4. 이번 세션 출력 (session-local scratchpad, `/private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/aspect-design/`):
   - `fir/ReqShieldAspect.{core-spring,webflux,coroutine}.fir.txt`, `fir/variants.fir.txt` (변이 모양의 FIR), `fir/dumpfir.sh`
   - `isolate/isolate.py`, `isolate/run1.sh`, `isofinal/table.txt` (의무별 격리 결과)
   - `checks/reach.vpr` (분기 도달성), `checks/r1_bang.vpr` (R1의 `!!` 분기), `checks/nohole.vpr`, `checks/only_inv1.vpr`, `checks/only_inv2.vpr` (hole 필요성)
   - `jdk21src/`, `kstdlib2021/` (인용한 JDK 21, Kotlin stdlib 2.0.21 소스)

## 부록 B. B가 더하는 신뢰 가정 (Silicon이 확인하지 않음)

1. `computeIfAbsent`, `putIfAbsent`, `put`, `remove`는 key마다 원자적이다 (JDK 21 Javadoc, 2.3절).
2. `set-once` map의 null 아닌 경쟁 읽기는 끝난 갱신이 넣은 값이다 (`ConcurrentHashMap.java:87-91`). 나머지 논증은 Silicon이 확인한다 (2.4절).
3. `Method.equals`는 동치 관계이고, map 안의 `Method`는 바뀌지 않는다 (`Method.java:361-364`).
4. `createReqShield`의 정상 반환은 새 객체이고, 던질 때는 아무것도 만들지 않았고, 생성자와 호출된 함수는 이 Aspect의 상태와 연산에 닿지 않는다 (3.4절, 사람 검토). 본문의 모듈 상태 접근은 도구가 확인한다 (3.3절).
5. test 소스는 `internal` state 필드에 쓰지 않는다 (감사하지 않음).
6. `getOrPut`의 inline 본문은 stdlib 2.0.21의 본문이다 (`MapsJVM.kt:70-75`, bytecode로 교차 확인).
7. 감사 범위 밖의 함수는 이 Aspect를 위해 `ReqShield`를 만들지 않는다. 만든다면 `P_once`가 그 생성을 세지 못한다 (4.2절 3).

## 부록 C. 근거

| 출처 | 쓴 곳 |
|---|---|
| req-shield `CLAUDE.md:27`, `README.md:100-105`, 세 `ReqShieldAspect.kt`, `ReqShield.kt`, `ReqShieldConfiguration.kt`, `KeyLocalLock.kt` (`2e792d2`) | 1절, 3절, 6절 |
| req-shield git: `15e24d5`와 그 부모의 core `KeyLocalLock.kt`, `cbabe26`과 그 부모의 core-spring `ReqShieldAspect.kt` | 1.3절 |
| req-shield test: `BaseReqShieldModuleSupportTest.kt:22`, core-spring `ReqShieldAspectTest.kt:130-170` | 1.2절 |
| Corretto 21.0.10 `src.zip`: `ConcurrentHashMap.java`, `ConcurrentMap.java`, `Method.java` | 2.2-2.4절 |
| kotlin-stdlib 2.0.21, 2.4.21 sources jar (Gradle 캐시): `MapsJVM.kt`, `Maps.kt` | 2.3절, 2.5절 |
| FIR dump와 javap (이번 세션) | 1.1절, 2.5절 |
| `spike/aspect/target.vpr`, `mutants.py`, `expected-verdicts.txt` (이번 세션) | 7절 |
| `DESIGN-v0.md` 2.2-2.14, 3, 4, 5, 8.3 | 전체 |
