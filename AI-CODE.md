# AI가 쓴 코드로 시험

날짜: 2026-10-24. 상태: 결과 전에 고정한 측정 계획 (advisor 검토 반영). 결과를 본 뒤 규칙을 고치면 맨 아래 변경 기록에 날짜와 이유를 적는다.

## 1. 질문과 주장의 범위

사용자의 목표: "AI가 생성한 코드가 우리의 명세대로 작성이 된것인지 확인하고 싶은거야".

이 측정은 AI에게 자연어 명세를 주고 코드를 쓰게 한 뒤, 도구의 판정이 실제 정답과 맞는지 잰다.
1. AI 코드의 결함을 도구가 놓치는가 (놓침).
2. 정상인 AI 코드를 도구가 실패로 보는가 (오경보).
3. AI 코드가 도구의 지원 범위 안에 들어오는가 (범위).

**주장의 범위.** 본 실험(12개)은 AI에게 "검증 가능한 부분집합"의 모양 틀을 준다. 그래서 측정하는 것은 "팀이 정한 모양 안에서 AI가 논리를 채울 때"다. "AI가 자연어 문서만 보고 자유롭게 설계할 때"가 아니다. Verus 사용자가 부분집합 안에서 코드를 쓰는 것과 같은 틀이다. 자유 설계의 범위는 별도 실험(자유 구현 3개, 4.2절)으로만 본다. 보고서는 이 구분을 반복한다.

## 2. 대상

req-shield coroutine 변형의 `KeyLocalLock` (`core-kotlin-coroutine`, `2e792d2`). Aspect는 연산이 `computeIfAbsent` 한 줄이라 대상에서 뺀다.

## 3. AI에게 주는 것 (명세 묶음)

샌드박스 디렉터리 하나에 다음 파일만 둔다.
1. `SPEC.md`: 자연어 명세. 다음 원문을 그대로 옮긴다.
   - req-shield README의 "Global lock guidance"의 소유 토큰 문장 (104-105행)
   - "Local lock map size" 절 전체 (113-150행 근처)
   - CLAUDE.md의 KeyLock 문장
2. `KeyLock.kt`: coroutine 변형의 인터페이스 파일 그대로. `tryLock`, `unLock`의 KDoc, `LockType`, `lockKeyOf`가 들어 있다.
3. `HELPERS.md`: 부를 수 있는 함수와 상수의 시그니처와 KDoc.
   - `nowToEpochTime()`
   - `LocalLockLimit.rejectsNewEntry(currentSize: Long): Boolean`
   - `LOCK_MONITOR_INTERVAL_MILLIS`
   - slf4j 로거
   - kotlinx.coroutines
4. `RULES.md`: 모양 틀 (본 실험에만 준다, 4.1절 원문).
5. `compile.sh`: 그 파일을 coroutine 모듈의 classpath로 컴파일만 한다. 검증, 테스트는 하지 않는다. 최대 10회.

## 4. 생성

### 4.1 본 실험: 모양 틀이 있는 구현 12개

모델 3종(haiku, sonnet, opus) × 4회. 같은 모델의 반복은 서로 상관되므로, 실질 표본은 12보다 작다.

생성 프롬프트 (원문, `<SANDBOX>`만 바꾼다):

```text
You are implementing one Kotlin class for a team. Work only inside the directory <SANDBOX>. Do not read, list or
search any file outside it. Do not use the web, memory tools, or other agents.

Read SPEC.md (the team's specification, copied from the project documentation), KeyLock.kt (the interface you
implement), HELPERS.md (functions and constants you may call) and RULES.md (rules every implementation must follow).
Write KeyLocalLock.kt in this directory: package com.linecorp.cse.reqshield.kotlin.coroutine,
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock.

Check that it compiles with ./compile.sh. It only compiles; it does not test or verify. You may call it at most
10 times. When you are done, report: whether the final file compiles, and every design decision you made that the
specification did not settle.
```

`RULES.md` 원문:

```text
The team checks this class with a formal verifier that supports a subset of Kotlin. Follow these rules exactly.

1. Shared state lives in the class's companion object:
   private val lockMap = ConcurrentHashMap<String, LockInfo>()
   private val tokenSequence = AtomicLong(0)
   A new ownership token is "local-${tokenSequence.incrementAndGet()}".
2. One lock entry is:
   private class LockInfo(
       val semaphore: Semaphore,
       @Volatile var expiresAt: Long,
       val isHeld: AtomicBoolean = AtomicBoolean(false),
       @Volatile var token: String? = null,
   )
3. tryLock and unLock each read or change lockMap only inside exactly one call
   lockMap.compute(key) { _, existing -> ... }.
   Values that must leave the lambda go through an AtomicReference or AtomicBoolean declared before the call.
4. tryLock reads the clock into a local `val now = nowToEpochTime()` before its compute call.
5. A monitor in the companion object removes expired entries. For each key it reads `val now = System.currentTimeMillis()`
   and calls lockMap.compute(key) once.
6. Do not use other ConcurrentHashMap methods to change lockMap, and do not use synchronized, locks, or
   try/catch inside a compute lambda.
```

### 4.2 범위 실험: 자유 구현 3개 (필수)

모델 sonnet × 3. `RULES.md`를 주지 않는다. 프롬프트는 4.1에서 "and RULES.md (rules every implementation must follow)"를 빼고, 끝에 "Write production-quality code." 한 문장을 더한 것이다.

이 3개는 판정자와 hole 단계를 거치지 않는다. 다음만 기록한다.
1. 컴파일 결과
2. sidecar를 묶어 보았을 때 깨지는 것 (이름, 구조, 미지원 구문)
3. 사람이 묶기용 이름만 바꾸면 도구가 돌아가는지

### 4.3 오염 검사

req-shield는 공개 저장소다. 그래서 원래 구현이 학습 자료에 있을 수 있고, 모양 틀과 README가 생성을 원래 구현 쪽으로 끌 수 있다.
- 각 구현과 원래 `KeyLocalLock.kt`의 토큰 단위 유사도를 잰다. 주석과 공백은 빼고 비교한다.
- 분포를 보고한다. 원래 구현과 거의 같은 것(유사도 0.9 이상)은 표시한다.
- 거의 같은 구현이 많으면 결함의 다양성이 낮다는 뜻이므로 보고서에 그렇게 적는다.

## 5. 정답 (도구와 독립)

1. **req-shield 단위 테스트:** `KeyLocalLockTest`(coroutine)를 AI 코드로 돌린다 (req-shield 복사본에서). 테스트는 부분 정답이다.
2. **판정자:** 새 맥락의 deep-reasoner 3명이 각각 4개 구현을 판정한다. 판정자는 도구의 결과와 테스트 결과를 보지 않는다. 근거는 자연어 명세와 sidecar 성질의 원문이다.
   - **결함 (sidecar 성질):** 깨지는 sidecar 성질 ID와 구체적 interleaving을 댄다.
   - **결함 (sidecar 밖):** 자연어 명세는 깨지만 그것을 다루는 sidecar 성질이 없다. 명세의 빈틈이다.
   - **정상:** 성질마다 논증한다.
   - **미결:** 위 셋 중 어느 근거도 세우지 못한다.
3. 판정이 끝난 뒤 테스트 결과와 비교한다. 어긋나는 것은 이유를 적는다.

## 6. 도구 흐름 (실제 사용 흐름)

1. **묶기:** coroutine 변형 sidecar의 `source`만 AI 코드로 바꿔 `kv verify`를 돌린다. 묶기가 실패하면 사람이 **묶기용 이름만** 바꿀 수 있다 (`free ... = local <이름>`, `source` 경로). 성질, 권한 절, 발급 규칙, hole은 바꾸지 않는다. 바꾼 횟수와 내용이 결과다.
2. **범위 밖:** INCOMPLETE이면 미지원 구문을 기록한다.
3. **불변식 다시 쓰기:** FAILED이면, 하위 상태가 무엇이든 불변식을 다시 쓴다.
   - 원래 hole은 원래 코드에 맞춘 것이다. 그래서 정상인 AI 코드도 실패할 수 있다.
   - 성질 위반 보고도 원래 hole의 상태(새 코드가 닿지 않는 상태)에서 나온 것일 수 있다.
   - 새 맥락의 LLM(hole 작성자)이 sidecar의 hole 문자열만 고친다. 쓸 수 있는 것은 `verify.sh`뿐이다. 이 스크립트는 `kv hole check` 다음에 `kv verify`를 부르고, 최대 20회 부를 수 있다 (spike E3과 같은 규칙).
   - hole 작성자는 AI 코드, sidecar, `verify.sh`만 본다. 판정과 테스트 결과는 보지 않는다.
4. **hole이 결함 코드를 구하지 못하는 이유 (건전성):**
   - 성질은 hole과 따로 고정된 `ensures`다.
   - `init`이 hole을 세워야 한다 (빈 map에서 참이어야 함).
   - 모든 action은 hole을 보존하면서 성질을 증명해야 한다.
   - 그래서 hole을 바꿔서 얻는 PROVEN은 "도달 가능한 모든 상태에서 성질이 성립한다"는 증명이다. hole은 귀납적이기만 하면 된다.
5. **최종 판정:**
   - PROVEN: 원래 hole이나 새 hole로 증명됨.
   - FAILED: 모든 시도가 실패. 마지막 시도의 하위 상태(property 또는 invariant only)를 함께 적는다.
   - 범위 밖.

## 7. 분류와 보고

### 7.1 범위 (본 실험과 범위 실험)

| 항목 | 기록 |
|---|---|
| 사산 (컴파일 실패) | 개수 |
| 묶기 실패 | 원인, 사람이 바꾼 이름 수 |
| 범위 밖 (INCOMPLETE) | 미지원 구문 |
| 자유 구현 3개 | 컴파일, 묶기에서 깨지는 것, 이름만 바꾸면 돌아가는지 |

### 7.2 정확성 (본 실험 중 범위 안의 구현)

| 정답 \ 도구 최종 | PROVEN | FAILED (property) | FAILED (invariant only, hole N회 실패) |
|---|---|---|---|
| 결함 (sidecar 성질) | **놓침** (가장 중요) | 검출 | 검출 (원인 미확정) |
| 결함 (sidecar 밖) | 명세의 빈틈 | 명세의 빈틈 | 명세의 빈틈 |
| 정상 | 맞음 | 오경보 | 도구+LLM이 풀지 못함 (오경보로 확정하지 않음) |
| 미결 | 미결 | 미결 | 미결 |

## 8. 미리 등록한 예측 (결과 전)

1. 본 실험 12개 중 컴파일되는 것이 8개 이상이다.
2. 적어도 1개 구현에 sidecar 성질을 깨는 실제 결함이 있다. 후보: 상한 처리, 만료 takeover의 CAS, unLock의 토큰 확인.
3. 정답이 "결함 (sidecar 성질)"로 판정한 구현을 도구가 PROVEN으로 놓치는 경우는 0이다.
4. 정상 구현 가운데 일부는 hole을 다시 써도 증명되지 않을 수 있다. 비율은 모른다.
5. 예측되는 오경보 종류 (발견으로 세지 않는다):
   - compute 앞의 빠른 경로(`lockMap[key]?.let { ... }` 뒤에 compute)는 정상이어도 FAILED일 수 있다 (v0 2.12, ARB_R2와 같음).
   - compute 안의 `try/catch`, 반복문, 주제가 있는 `when`, 연산 안의 두 번째 CHM 호출은 INCOMPLETE다.
6. 자유 구현 3개는 모두 그대로는 묶이지 않는다 (이름이나 구조가 다름). 범위 안에 들어오는 것은 1개 이하다.
7. 오염: 본 실험 구현 중 일부는 원래 구현과 유사도가 높다 (모양 틀 때문).

## 9. 진행 순서 (단계마다 결과를 커밋)

1. 측정 도구 (feature-developer):
   - 샌드박스 생성기와 `compile.sh`
   - 테스트 실행기 (req-shield 복사본에서 `KeyLocalLockTest`)
   - 도구 실행기 (변형 sidecar의 `source`를 AI 코드로)
   - hole 과제 생성기와 `verify.sh` (20회 제한)
   - 유사도 측정
   - 결과 JSONL
2. 생성: 3-4개씩 나눠 실행하고 도착할 때마다 커밋한다. 사용량 제한으로 멈추면 해제 시각까지 기다렸다가 이어서 한다.
3. 테스트와 도구 실행: 스크립트로 돌린다.
4. 판정자 3명: 블라인드, 4개씩.
5. hole 작성자: FAILED마다 1명.
6. 비교와 보고.

## 변경 기록

- 2026-10-24, 결과 전: advisor 검토를 반영해 초안을 고쳤다. 고친 것: 주장의 범위(모양 틀의 뜻), 자유 구현 3개를 필수로, 오염 검사, 분류(sidecar 밖 결함, 묶기 실패, invariant only의 뜻), 세 쪽 블라인드, 프롬프트 원문, 예측되는 오경보.
- 2026-10-24, 생성 전 (측정 도구 시험 뒤):
  1. HELPERS.md에 원래 `KeyLocalLock.kt`의 import 줄(22-29행)을 넣지 않는다. 그 줄이 원래 monitor 설계(`Job`, `isActive`, `CancellationException`)를 드러내기 때문이다. 대신 classpath의 라이브러리 목록을 준다.
  2. 4.2 자유 프롬프트: RULES.md 언급을 뺀 뒤 "KeyLock.kt (...) and HELPERS.md (...)"로 문법을 맞춘다.
  3. 단위 테스트는 `--shim`으로 돌린다. 이 옵션은 테스트 소스에 아무 일도 하지 않는 확장 함수 `stopMonitoring()`, `cancel()`을 넣는다. `KeyLocalLockTest`가 이 두 멤버를 부르는데 명세 묶음에는 없기 때문이다 (측정: 두 멤버가 없으면 14개 모두 컴파일 실패). 위험: AI의 monitor가 테스트 사이에 멈추지 않아 드물게 결과가 흔들릴 수 있다. 자유 구현은 리플렉션으로 `lockMap`을 읽는 테스트(405행)에서 실패할 수 있다.
  4. hole 작성자용 TASK.md: "FAILED (property)"는 현재 hole이 허용하는 상태에서의 실패이고, 그 상태를 배제하는 hole이면 사라질 수 있다고 적는다.
- 2026-10-24, 도구 1차 결과 뒤 (판정 전): 6.1의 묶기 수정에 `class NAME KNAME`을 더했다 (sidecar의 `class LockInfo = X`의 Kotlin 이름만 바꿈). 이유: RULES.md가 `LockInfo`의 위치(클래스 안, companion 안, 파일 최상위)를 정하지 않았다. 그래서 4개 구현(T-haiku-1, T-haiku-3, T-haiku-4, T-sonnet-4)의 묶기가 실패했다. 이것은 성질이나 조건이 아니라 묶기용 이름이므로 6.1의 취지 안이다. 다만 미리 등록한 목록에는 없었으므로 결과 뒤 변경으로 적는다. 바꾼 구현은 `aicode/impl/<ID>/bindings.txt`에 있고, `binding_adaptations`로 센다.
- 2026-10-24, 판정 뒤: 6.3의 hole 다시 쓰기 단계를 생략했다. 이유: 도구가 FAILED를 낸 action은 6개 구현의 sweep이다. 판정자가 그 6개 모두에서 같은 성질(sweep의 P3a, 경계 `>=`)의 실제 위반을 실행으로 확인했다. hole을 다시 쓰면 "낡은 hole 때문에 생긴 실패"를 가려낼 수 있지만, 실제 위반은 어떤 hole로도 증명되지 않는다 (6.4). 나머지 실패한 모듈은 tryLock이 UNSUPPORTED라서 hole로 PROVEN이 될 수 없다.

## 10. 결과 (2026-10-24, 1차: 지금 도구 그대로)

결과 파일: `aicode/results.jsonl`, `aicode/summary.md`, 판정 `aicode/judgments-{1,2,3}.md`, 판정자 증거 `aicode/judge-work/`.

### 10.1 정답 (판정자 3명, 블라인드)

- 틀 구현 12개 모두 "결함 (sidecar 성질)"이다.
- **공통 결함 (12개):** 만료 판정이 `>=`(`expiresAt <= now`)다.
  - 정확히 만료 시각과 같은 1 ms에 tryLock이 아직 유효한 lock을 넘겨받는다 (P3a, progress_live).
  - monitor도 그 순간 entry를 지운다 (sweep의 P3a).
  - SPEC.md의 "once `lockTimeoutMillis` has passed"는 경계를 정하지 않는다. 경계를 정한 것은 서명된 sidecar(`now > gLease`)다.
  - 판정자들은 단일 스레드 재현, 실제 시계 재현, `>`로 고친 대조군으로 확인했다.
- **추가 결함:**
  - T-haiku-3: unLock이 만료된 자기 entry를 풀지 않는다 (progress_owner).
  - T-sonnet-3: 만료 뒤 unLock이 entry를 지우면서 `false`를 돌려준다 (progress_owner, unLock의 P3a).
- **도구의 모델 밖 결함:** 여러 구현에서 `now + lockTimeoutMillis`가 `Long` 범위를 넘으면 lock이 곧바로 만료된다. 도구는 `Long`을 넘치지 않는 정수로 가정하고(보고서의 Assumptions), 이 경로를 보지 못한다. T-opus-2는 포화로 막았다.
- **성립하는 성질:** 12개 모두 상호 배제(경쟁 부하 시험에서 겹친 보유자 0), P1, P1a, P3b, P3c, P2, cap_unbacked, expired_takeover는 성립한다.
- req-shield 단위 테스트는 12개 모두 14/14 통과다. 테스트는 이 경계 결함을 잡지 못한다.

### 10.2 도구 판정과 정답의 대조 (action 단위)

| 도구의 action 판정 | 개수 | 대조 |
|---|---|---|
| FAILED (property) | 6 (sweep: T-haiku-2, T-haiku-4, T-sonnet-1, T-sonnet-3, T-sonnet-4, T-opus-2) | 6개 모두 정답과 같다 (검출). 오경보 0 |
| PROVEN | 5 (unLock: T-haiku-2, T-haiku-4, T-sonnet-1, T-sonnet-4, T-opus-2) | 5개 모두 정답도 unLock이 성질을 지킨다고 본다. 놓침 0 |
| UNSUPPORTED | 13 | tryLock 8개 전부, T-haiku-1과 T-haiku-3의 unLock과 sweep, T-sonnet-3의 unLock |
| 범위 밖 | 4개 구현 (12 action) | `kotlinx.coroutines.sync.Semaphore` (T-sonnet-2, T-opus-1, T-opus-3, T-opus-4) |

- 도구가 판정을 낸 action 11개는 모두 정답과 맞다: 검출 6, 맞은 PROVEN 5, 놓침 0, 오경보 0.
- 그러나 정답이 찾은 결함 중 다음은 도구가 판정하지 못했다.
  - tryLock의 경계 결함 (12개 전부): UNSUPPORTED 또는 범위 밖.
  - T-sonnet-3의 unLock 결함: UNSUPPORTED.
  - overflow: 모델 밖.
- 모듈 단위로는 PROVEN 0, FAILED 6, INCOMPLETE 2, 범위 밖 4다.

### 10.3 범위 (가장 큰 결과)

지금 도구는 AI가 쓴 틀 구현 12개 중 하나도 끝까지 검증하지 못했다. 원인은 다음과 같다.

| 원인 | 구현 수 | 성격 |
|---|---|---|
| `lockMap.size.toLong()` (v0는 Int의 `+`, `-`만) | 묶인 8개 모두 (tryLock) | 도구의 부분집합이 좁음 |
| `kotlinx.coroutines.sync.Semaphore` | 4 | 신뢰 모델 없음. RULES.md가 패키지를 정하지 않음 |
| `LockInfo`의 위치 (companion 안, 파일 최상위) | 4 | RULES.md가 정하지 않음. 묶기 수정으로 해결 |
| 다른 객체의 helper 호출, `Semaphore` 필드 직접 접근, safe call `?.`, 값으로 쓴 if, compute 밖의 `tokenSequence` 증가 | 여러 개 | 도구의 부분집합이 좁음 |

자유 구현 3개는 모두 그대로는 묶이지 않는다. 상태 이름, 클래스, 구조가 다르다 (예측 6과 같음).

### 10.4 예측 대조 (8절)

| 예측 | 대조 |
|---|---|
| 1. 12개 중 8개 이상 컴파일 | 맞음 (12/12, 자유 3/3) |
| 2. 적어도 1개에 sidecar 성질 결함 | 맞음 (12/12) |
| 3. 놓침 0 | 맞음 (판정이 난 action 기준). 다만 도구가 판정하지 못한 결함이 많다 |
| 4. 정상 구현의 일부는 hole을 다시 써도 실패 | 해당 없음 (정상 구현 0) |
| 5. 예측된 오경보 종류 | 빠른 경로는 틀 구현에 없었다. INCOMPLETE 원인은 예측과 다르다: 주로 `Int.toLong`, helper 호출 |
| 6. 자유 구현은 그대로 묶이지 않음 | 맞음 (3/3) |
| 7. 일부는 원본과 유사도가 높음 | 틀림 (0.22-0.57, 0.9 이상 0개) |

### 10.5 기록할 이탈

- T-opus-2의 생성자는 advisor를 한 번 썼다 (블라인드는 유지).
- 판정자 3은 허용 범위보다 넓게 DESIGN-v0.md를 읽었다. 그 범위에 원래 코드의 조건문 한 줄이 있었다. 판정의 근거는 sidecar 원문이었다.
- 생성 agent에는 사용자의 전역 CLAUDE.md가 함께 적용되었다.

## 11. 2차 측정 계획 (2026-10-24, 결과 전)

사용자 결정 (1차 결과 뒤):
1. 만료 경계는 엄격(`>`)을 유지한다. 서명된 P3a, progress_live, expired_takeover는 바꾸지 않는다. 그래서 AI 구현 12개의 경계 결함은 결함으로 남는다.
2. 도구의 지원 범위를 두 가지 넓힌 뒤, 같은 12개로 도구만 다시 돌린다. 생성과 판정은 다시 하지 않는다. 정답은 10.1절이고, 도구와 독립이다.
   - `Int.toLong()`: 수학적 정수에서 항등 변환으로 모델링한다. action 안의 `lockMap.size`는 `mappingCount()`처럼 비결정 값으로 다룬다.
   - `kotlinx.coroutines.sync.Semaphore`: 신뢰 모델을 더한다. 사용 가능한 permit 수로 평탄화한다.
     - `tryAcquire()`는 jdk 모델과 같다.
     - `release()`는 "permit 수가 최대보다 작다"를 증명할 의무(assert)로 낸다. 초과 해제는 `IllegalStateException`을 던지기 때문이다.
     - 생성자는 `Semaphore(permits, acquiredPermits = 0)`이다.
     - 근거는 kotlinx-coroutines 1.8.1 소스에서 인용한다.

action 단위의 정답 (10.1절):

| action | 결함 | 정상 |
|---|---|---|
| tryLock | 12개 모두 (P3a, progress_live: 경계 `>=`) | - |
| sweep | 12개 모두 (P3a: 경계 `>=`) | - |
| unLock | T-haiku-3, T-sonnet-3 (progress_owner; T-sonnet-3은 unLock의 P3a도) | 나머지 10개 |

### 11.1 미리 등록한 예측 (결과 전)

1. 묶인 8개 중 tryLock이 범위에 들어오는 것은 정확히 T-haiku-2, T-haiku-3, T-sonnet-1, T-sonnet-3, T-sonnet-4다. 1차의 tryLock UNSUPPORTED 원인이 `Int toLong`뿐이었기 때문이다. 이 5개의 tryLock은 FAILED (property)다 (P3a 또는 progress_live).
2. 나머지 3개는 다른 구문 때문에 tryLock이 계속 UNSUPPORTED다: T-haiku-1 (helper 호출), T-haiku-4 (결과 반환 모양), T-opus-2 (생성자 기본값, 값으로 쓴 if).
3. T-haiku-1, T-haiku-3, T-sonnet-3의 unLock은 계속 UNSUPPORTED다 (`Semaphore` 필드 직접 접근). 그래서 T-haiku-3과 T-sonnet-3의 unLock 결함은 계속 판정되지 않는다.
4. kotlinx `Semaphore`를 쓴 4개(T-sonnet-2, T-opus-1, T-opus-3, T-opus-4)는 이제 묶인다. 그중 적어도 1개 action이 판정을 받고, 지원되는 sweep은 FAILED (property)다.
5. 판정이 정답과 어긋나는 action은 0이다. tryLock이나 sweep의 PROVEN은 놓침이고, 정상 unLock의 FAILED는 오경보다.
6. 끝까지 PROVEN인 모듈은 0이다 (12개 모두 결함이 있으므로 정답과 맞는 결과다).

## 12. 2차 결과 (2026-10-25, `Int.toLong`과 kotlinx `Semaphore` 모델을 더한 뒤 도구만)

결과 파일: `aicode/round2/` (results.jsonl, summary.md, compare.md, logs/). 커밋 `7c65fd3` ~ `03ebe93`.

### 12.1 action 단위 대조 (메인 세션이 보고서 원문에서 다시 집계함)

| 대조 | 1차 | 2차 |
|---|---|---|
| 검출 (도구 FAILED, 정답 결함) | 6 | 10 (tryLock 3: T-haiku-2, T-sonnet-1, T-sonnet-4; sweep 7) |
| 맞은 PROVEN (도구 PROVEN, 정답 정상) | 5 | 5 (unLock) |
| 놓침 | 0 | 0 |
| 오경보 (action 단위) | 0 | 0 |
| 판정 없음 | 25 | 21 |

- 모듈 단위 (1차 → 2차): PROVEN 0 → 0, FAILED 6 → 7, INCOMPLETE 2 → 2, ERROR 0 → 3, 묶기 실패 4 → 0.
- **의무 단위의 거짓 이유 2개 (메인 세션 확인):** T-haiku-2의 tryLock은 실제 위반인 P3a와 함께 `cap_unbacked`와 발급 set-once `gIssued`로도 실패한다. 판정자는 두 성질이 성립한다고 판정했다 (판정자 3은 "도구가 P1a의 freshness를 증명하지 못할 수 있다"고 미리 적었다). 이 구현은 토큰을 compute 밖에서 만든다. 도구는 그 연산을 모듈의 UNSUPPORTED로 적으면서도 action을 검증했다. 그래서 그 action의 실패 이유 일부가 지원 밖 구문에 기댄다. action 판정은 맞지만 보고된 이유는 일부 거짓이다.
- **ERROR 3개 (T-sonnet-2, T-opus-3, T-opus-4):** 기존 도구 결함이다. smart cast된 entry로 companion helper(`isExpired(lockInfo, now)`)를 부르면, inlining이 nullable `let`을 만들고(W5) entry가 아닌 객체를 읽는다(W6). 수정 전 도구로 jdk 버전을 돌려도 같은 W5 ERROR가 났다 (구현 agent 측정). W 검사가 이것을 잡았으므로 거짓 PROVEN은 나오지 않았다.
- kotlinx `release`의 증명 의무는 AI 구현에서는 한 번도 실행되지 않았다 (해당 action이 UNSUPPORTED이거나 ERROR). 고정 테스트에서만 확인했다.

### 12.2 예측 대조 (11.1)

| 예측 | 대조 |
|---|---|
| 1. tryLock이 범위에 들어오는 것은 5개 | 틀림: 3개(T-haiku-2, T-sonnet-1, T-sonnet-4)다. T-haiku-3(`Semaphore(1).also {...}`)과 T-sonnet-3(지역 `val semaphore = Semaphore(1)`)은 Semaphore를 만드는 모양 때문에 계속 UNSUPPORTED다. 1차 보고서에는 이 원인이 `Int toLong`에 가려 보이지 않았다 |
| 2. 3개는 계속 UNSUPPORTED | 맞음 |
| 3. T-haiku-3, T-sonnet-3의 unLock 결함은 계속 판정되지 않음 | 맞음 (원인은 Semaphore를 만드는 모양) |
| 4. kotlinx 4개가 묶이고 일부가 판정됨 | 맞음 (T-opus-1의 sweep FAILED; 나머지 3개는 ERROR) |
| 5. 어긋나는 action 0 | 맞음 (action 단위). 의무 단위의 거짓 이유 2개는 12.1 |
| 6. PROVEN 모듈 0 | 맞음 |

### 12.3 남은 범위 밖 구문 (2차)

| 구문 | 구현 |
|---|---|
| Semaphore를 만드는 모양 (`Semaphore(1).also/apply {...}`, 지역 변수) | T-haiku-1, T-haiku-3, T-haiku-4, T-sonnet-3, T-opus-1 |
| helper inlining의 W5/W6 결함 (도구 버그) | T-sonnet-2, T-opus-3, T-opus-4 |
| 다른 객체의 helper 호출, `?.`, 값으로 쓴 if, 생성자 기본값, entry의 지역 별칭 | T-haiku-1, T-haiku-3, T-opus-2, T-opus-4 |

## 13. 버그 수정 뒤 검증 실행 (2026-10-25, 결과 전)

사용자 결정: 측정은 2차로 마치고 Confluence/Markdown → sidecar 방향으로 전환한다. 그 전에 2차에서 드러난 도구 결함 셋을 고친다. 그리고 같은 12개로 도구만 다시 돌려 수정을 확인한다 (측정 3차가 아니라 수정 검증이다).
1. 지원 밖 구문(action 밖의 counter 연산 등)에 입력이나 결과가 기대는 action은 검증하지 않고 UNSUPPORTED로 보고한다 (12.1의 거짓 이유).
2. smart cast된 entry를 helper에 넘길 때의 inlining 결함(W5/W6)을 고친다.
3. 보고서는 action마다 지원 밖 구문을 모두 나열한다 (12.2 예측 1의 원인).

예측 (결과 전):
1. T-haiku-2의 tryLock은 FAILED에서 UNSUPPORTED가 된다. 이유는 compute 밖의 토큰 생성이다. 그래서 검출 1개가 줄어든다.
2. ERROR였던 3개(T-sonnet-2, T-opus-3, T-opus-4)는 더 이상 ERROR가 아니다. 지원되는 sweep은 FAILED (property)다 (경계).
3. 놓침 0, action 단위 오경보 0이 유지된다.
4. 1차·2차에서 UNSUPPORTED였던 action의 보고에 원인이 더 많이 나열된다 (예: T-haiku-3, T-sonnet-3의 tryLock에 Semaphore 생성 모양).


### 13.1 결과 (2026-10-25, 메인 세션 확인)

수정 커밋: `e436d22`(수정 1), `43786d8`(수정 2), `3208158`(수정 3). 검증 실행: `df8db76` (`aicode/round2b/`, 대조표는 `aicode/round2b/compare.md`).

36개 action의 대조 (메인 세션이 보고서 원문 12개에서 다시 셌다):

| 대조 | 2차 | 2b |
|---|---|---|
| 검출 | 10 | 14 |
| 맞은 PROVEN | 5 | 8 |
| 놓침 | 0 | 0 |
| 오경보 | 0 | 0 |
| 판정 안 함 | 21 | 14 |

- 모듈 판정 (2차 → 2b): FAILED 7 → 10, INCOMPLETE 2 → 2, ERROR 3 → 0, PROVEN 0 → 0.
- 판정이 바뀐 action은 9개다. T-haiku-2의 tryLock은 FAILED에서 UNSUPPORTED가 되었다. 2차 ERROR 3개의 action 8개는 FAILED 5, PROVEN 3이 되었다 (T-opus-4의 tryLock은 UNSUPPORTED 그대로).
- 새 FAILED 5개의 실패 성질은 모두 `ends_only_if P3a`다 (경계 `>=`).
- kotlinx `release()` 의무가 AI 코드에서 처음 실행되었다. T-sonnet-2, T-opus-3, T-opus-4의 unLock은 PROVEN이다.

예측 대조:

| 예측 | 결과 | 근거 |
|---|---|---|
| 1. T-haiku-2 tryLock이 UNSUPPORTED, 검출 1 감소 | 맞음 | 보고서: "UNSUPPORTED input newToken in action tryLock#47 at KeyLocalLock.kt:45:9 ... tokenSequence.incrementAndGet() at KeyLocalLock.kt:45:33". 2차의 거짓 이유 5개(MI_INV_1, MI_INV_3, P3a, cap_unbacked, set-once)가 사라졌다. 전체 검출 수는 예측 2 때문에 늘었다 |
| 2. ERROR 3개가 ERROR가 아니고, sweep은 FAILED (property) | 맞음 | 세 sweep 모두 `ends_only_if P3a` |
| 3. 놓침 0, 오경보 0 | 맞음 | 판정한 22개: 검출 14, 맞은 PROVEN 8 |
| 4. UNSUPPORTED action의 원인이 더 많이 나열됨 (예: T-haiku-3, T-sonnet-3 tryLock의 Semaphore 생성 모양) | 일부 맞음 | 참인 이유가 tryLock 5개에서 늘었다 (T-haiku-1, T-haiku-4, T-sonnet-3, T-opus-1, T-opus-4). 예로 든 T-haiku-3은 늘지 않았다: 그 Semaphore 모양(38:33)은 2차에 이미 보고되었다. 1차에서 이 이유를 가린 `Int toLong`은 2차부터 지원되어 이번 실행으로 다시 만들 수 없다. 그 경우는 `ReasonTest`가 확인한다 |

예측 밖의 결과:
1. 수정 3에서 거짓 이유 하나를 지웠다. 주어졌지만 지원 밖인 생성자 인자를 "constructor default ... gives no argument"로도 보고했다 (2차 tryLock 6개).
2. 수정 1의 범위는 문구보다 좁다. 입력을 오염시키는 것은 ledger의 **모듈 상태 접근**뿐이다. 문구대로 "모든 지원 밖 구문"으로 하면 aspect 변이 M5(공유 `ReqShield`를 fresh 함수 밖 생성자로 만듦)가 기대값 FAILED(`MI_OWN`)에서 INCOMPLETE가 된다 (agent 측정). 그 생성자는 모듈 상태를 바꾸지 않고, 모듈은 계속 INCOMPLETE다. 12개 구현에서는 두 해석의 결과가 같다. 메인 세션은 이 해석을 받아들인다.
3. 수정 agent가 기존 결함 둘을 임시 probe로 찾았다 (고치지 않음). 13.2절.

### 13.2 수정 중에 찾은 기존 결함 (2026-10-25)

| ID | 결함 | 영향 | 상태 |
|---|---|---|---|
| S1 | aspect section의 `relevance()`는 relevant 지역 변수에 대입만 하는 문장을 relevant로 보지 않는다. 그래서 `var r = shieldMap.computeIfAbsent(...) { ... }; if (prefix.length > 3) r = shared; return r`에서 section이 그 `if`를 버린다 | **거짓 PROVEN.** 이 코드는 `shared`를 돌려줄 수 있어 `P_same`이 깨질 수 있는데, AspectFixture가 PROVEN이다 | 고침: relevant 지역 변수에 대입하는 문장도 section에 넣는다. 같은 코드가 이제 FAILED (`P_same`)다 |
| S2 | object 모듈의 stray `boxMap.putIfAbsent(key, Box(counter.incrementAndGet()))`에서 `actionValueOutside`가 `markAll(arg)`로 값 전체를 번역한 것으로 표시한다 | action 밖의 counter 쓰기가 ledger에 없어 모듈이 INCOMPLETE가 아니다. DESIGN-v0.md 2.12 rule 2와 맞지 않는다. 원인은 audit이다: put의 값 인자가 action 호출 안에 있어 audit이 action 영역으로 보고 건너뛴다. 같은 원인으로 stray putIfAbsent의 값 `Shield(prefix)`(fresh 함수 밖 생성자, DESIGN-B.md 3.3 rule 4)도 ledger에 없었다. 그러면 `P_once`가 그 생성을 세지 못한다 | 고침: audit은 put의 값 인자를 action 밖(action 전에 계산됨)으로 본다. lowering은 계산된 값을 번역한 것으로 표시하지 않는다 |

S1, S2 수정의 확인 (메인 세션): 새 테스트 5개 중 결함 테스트 3개는 수정 전 코드에서 실패하고 수정 뒤 통과한다. 대조 테스트 2개는 두 경우 모두 통과한다. `cleanTest test` 452개 통과, gate 4개 exit 0, mutantsReqShield 33회와 mutantsAspect 30회 불일치 0. gate가 보는 실제 모듈 4개의 생성 모델은 그대로다. AI 구현 12개는 다시 돌리지 않았다. 근거: 12개는 object 모듈이라 section(S1)이 없고, put과 putIfAbsent(S2)를 쓰지 않는다 (grep).

## 14. 3차: 지원 범위 넓히기와 일반화 측정 (2026-10-26, 결과 전)

사용자 결정 (2026-10-26): AI 코드 지원 범위를 넓힌다. 2b에서 판정하지 못한 action은 14개다 (`aicode/round2b/logs`를 메인 세션이 다시 셈).

### 14.1 지원할 구문 (2b의 증거)

| ID | 구문 | 막힌 action (2b) | 조건 |
|---|---|---|---|
| A1 | 새 entry에 넘기기 전에 만든 Semaphore: `Semaphore(1).apply { tryAcquire() }`, `Semaphore(1).also { it.tryAcquire() }`, 지역 `val s = Semaphore(1); s.tryAcquire(); LockInfo(s, ...)` (jdk와 kotlinx) | T-haiku-1 tryLock·unLock, T-haiku-3 tryLock·unLock, T-haiku-4 tryLock, T-opus-1 tryLock·unLock, T-sonnet-3 tryLock·unLock | "action 안의 새 평탄화 객체": 이 action에서 모델 생성자로 만들고, 모델 연산으로만 쓰고, 새 entry의 생성자에 정확히 한 번 넘기고, 넘긴 뒤에는 읽지 않는다. `tryAcquire()`의 결과를 버려도 permit은 1에서 0이 된다. 조건을 어기는 경우(두 생성자에 넘김, 기존 entry의 필드에 저장, 넘긴 뒤 사용, `existing.semaphore`의 별칭)는 UNSUPPORTED로 남는다 |
| A2 | 값으로 쓴 if/when | T-opus-2 tryLock, T-opus-4 tryLock | |
| A3 | entry의 지역 별칭 (`val e = existing`, `val e = if (...) X else existing`) | T-opus-4 tryLock | 별칭은 entry나 이 action이 만든 객체만 가리킨다 |
| A4 | action 안의 `?.` | T-haiku-3 sweep | |
| A5 | entry 클래스의 멤버 helper (`existing.isExpired(now)`) | T-haiku-1 tryLock·sweep | 순수한 멤버 함수, `this` = entry |

빼는 것 (Part B, 설계 필요): compute 밖에서 counter로 만든 토큰 (T-haiku-1, T-haiku-2, T-haiku-4의 tryLock). 연산 하나에 atomic 단계가 둘 생기므로 L1 모델의 변경과 건전성 논증이 필요하다 (deep-reasoner). T-haiku-4의 "operation result (W10)"도 같은 원인이다 (`return if (granted.get()) token else null`의 token이 compute 밖에서 만든 값).

### 14.2 2차 구현 12개에 대한 예측

정답은 11절의 action 표다 (tryLock, sweep은 12개 모두 결함; unLock은 T-haiku-3, T-sonnet-3만 결함).
1. 새로 판정되는 action은 정확히 11개다: T-haiku-1 unLock·sweep, T-haiku-3 tryLock·unLock·sweep, T-opus-1 tryLock·unLock, T-opus-2 tryLock, T-opus-4 tryLock, T-sonnet-3 tryLock·unLock. T-haiku-1 tryLock과 T-haiku-4 tryLock, T-haiku-2 tryLock은 Part B로 남는다.
2. **판별 예측:** T-haiku-3 unLock과 T-sonnet-3 unLock은 FAILED다 (정답의 unLock 결함). PROVEN이면 A1의 lowering이 만든 놓침이다.
3. 새로 판정되는 tryLock과 sweep은 모두 FAILED다 (경계 `>=`). 새로 판정되는 unLock 중 T-haiku-1, T-opus-1은 PROVEN이다.
4. 놓침 0, 오경보 0이 유지된다. 그러면 36개 중 판정 33개 (2b의 22 + 11), 판정 안 함 3개 (Part B의 tryLock 3개)다.
5. 이 구문들은 AI 코드에만 있으므로 gate 4개와 손으로 고른 변이 63회는 바뀌지 않는다. 바뀌면 변경이 req-shield lowering에 샌 것이다.

### 14.3 일반화 측정 (과적합 검사)

1. 구현 커밋 뒤에 새 구현 12개를 만든다. 1차와 같은 과제(TASK.md, HELPERS.md, RULES.md), 같은 모델 3종 × 4. 메인 세션은 새 코드를 구현 전에 보지 않는다.
2. 같은 새 12개에 수정 전 도구(`git worktree`, 이 절을 커밋한 HEAD)와 수정 뒤 도구를 모두 돌린다.
3. **결정 기준 (결과 전):** 수정 뒤 도구의 판정 action 비율이 새 12개에서 75% 이상이면 A1-A5가 일반화한다고 본다. 75% 미만이면 2차 12개에 맞춘 것으로 본다. 참고값: 2b의 비율은 22/36 = 61%다.
4. 놓침과 오경보를 말하려면 새 12개의 정답이 필요하다. 1차와 같은 블라인드 판정자 3명이 필요하다. 이 비용은 사용자가 정한다. 판정자가 없으면 일반화 결과는 판정 비율만 보고한다.

### 14.4 3a 결과 (2차 12개, A1-A5 뒤; 2026-10-26, 메인 세션 확인)

구현 `f3f267f`, 실행 `846d4e4` (`aicode/round3a/`). sidecar는 2b 뒤에 절 4개가 더해졌으므로, 같은 sidecar로 수정 전 도구를 돌린 `aicode/round3a/base/`의 action 행이 2b와 같음을 agent가 확인했다. 그래서 변화는 모두 lowering에서 왔다.

| 대조 (action 36개, 메인 세션이 보고서 원문으로 다시 셈) | 2b | 3a |
|---|---|---|
| 검출 | 14 | 22 |
| 맞은 PROVEN | 8 | 10 |
| 놓침 | 0 | 0 |
| 오경보 | 0 | 0 |
| 판정 안 함 | 14 | 4 |

예측 대조:

| 예측 | 결과 |
|---|---|
| 1. 새 판정 11개 | 틀림 (10개). T-opus-2 tryLock은 A2가 값 if를 받아들인 뒤 드러난 `Long.MAX_VALUE` 읽기 때문에 UNSUPPORTED다 ("UNSUPPORTED read of Companion.MAX_VALUE in action tryLock#46 at KeyLocalLock.kt:85:33") |
| 2. T-haiku-3 unLock, T-sonnet-3 unLock은 FAILED | 맞음 (`progress_owner`, `ends_only_if P3a`) |
| 3. 새 tryLock·sweep은 FAILED, T-haiku-1·T-opus-1 unLock은 PROVEN | 맞음 |
| 4. 놓침 0, 오경보 0, 판정 33개 | 일부 맞음: 놓침 0, 오경보 0, 판정 32개 |
| 5. gate와 변이 63회는 그대로 | 맞음 (테스트 583개 통과, 불일치 0; 메인 세션 확인) |

판정 안 함 4개: Part B tryLock 3개(T-haiku-1, T-haiku-2, T-haiku-4)와 T-opus-2 tryLock(`Long.MAX_VALUE`). `Long.MAX_VALUE`는 사전 등록한 A1-A5 밖이라 넣지 않았다. 14.3의 일반화 측정에서는 수정 뒤 도구를 바꾸지 않고 잰다.

### 14.5 수정 중에 찾은 기존 결함 S3 (2026-10-26)

`ModuleContext.reads()`는 대입문의 자식인 읽기를 모두 뺐다. 대입의 대상(왼쪽)만 빼야 하는데 값(오른쪽)까지 뺐다. 그래서 셀이나 평탄화 필드가 `x = cell`처럼 대입을 거쳐 새어도 escape 검사가 놓쳤다 (agent의 임시 probe: 그런 셀을 가진 action이 supported, 모듈이 complete). 메인 세션이 고쳤다: 대입의 대상인 읽기만 뺀다. 새 테스트 2개(셀, 평탄화 필드)는 수정 전 코드에서 실패하고 수정 뒤 통과한다.
확인: 테스트 585개 통과, 변이 63회 불일치 0, 3a의 12개 보고서는 판정 줄이 모두 같다 (다시 돌림).

### 14.6 일반화 측정 결과 (새 구현 12개, 2026-10-26)

새 구현 `aicode/round3g/impl/` (G-haiku/sonnet/opus-1..4): 1차와 같은 과제와 프롬프트, 12개 모두 컴파일 1회에 통과. 수정 전 도구(`2d479ee`, worktree)의 결과는 `aicode/round3g/pre/`, 수정 뒤 도구(`b82d47c`: A1-A5와 S3)의 결과는 `aicode/round3g/`다. 메인 세션은 도구를 돌리기 전에 새 코드를 읽지 않았다.

묶기 조정 (1·2차와 같은 규칙, 두 도구에 같게 적용): `class LockInfo LockInfo` 5개(G-haiku-1, -3, -4, G-sonnet-1, -2), `use kotlinx.Semaphore` 3개(G-opus-2, -4, G-sonnet-3).

| 도구 | 판정한 action (36개 중) | 비율 |
|---|---|---|
| 수정 전 | 26 | 72% |
| 수정 뒤 | 31 | **86%** |

**결정 기준(14.3.3, 75%)을 넘었다. A1-A5는 일반화한다고 본다.** 다만 수정 전 도구도 새 12개에서 72%를 판정했다 (2b의 2차 12개는 61%). 그래서 개선 폭(+14점)은 2차 12개의 개선 폭(61% → 89%, +28점)보다 작다.

- 수정 뒤에도 판정하지 못한 5개: Part B(compute 밖 토큰) tryLock 4개 (G-haiku-3, G-haiku-4, G-opus-1, G-sonnet-1)와 G-opus-2 tryLock(셀을 helper에 인자로 넘김: `newHeldEntry(now, acquiredToken)`, A1-A5 밖의 새 구문, 수정 전에도 미지원).
- 모듈 판정: 12개 모두 FAILED. **12개 모두 sweep이 `ends_only_if P3a` 위반이고, 12개 모두 만료를 `now >= expiresAt` (또는 `expiresAt <= now`)로 썼다** (소스 grep). 1차·2차 12개와 합쳐 AI 구현 24개가 모두 같은 경계를 골랐다.
- G-haiku-3 unLock은 FAILED (`progress_owner`): 만료된 entry의 unLock을 거부한다 (생성 보고서 4번). 서명된 결정 D2(만료 뒤 주인의 unLock = true, UC9)와 다르다.
- 정답(블라인드 판정자)은 아직 없다. 그래서 놓침과 오경보는 아직 말할 수 없다 (14.3.4). 위 두 FAILED 이유는 메인 세션이 소스로 확인한 것이다.

## 15. Part B: compute 밖 토큰 (DESIGN-D (a)), 결과 전 (2026-10-26)

사용자 결정 (2026-10-26): DESIGN-D의 권고안 (a)를 구현한다 (예약 단계 R과 action B, 예약 ghost `gPend`).

메인 세션 확인 (advisor 권고): (b)와 (c)의 반례는 실제 Kotlin 코드로 만들 수 있다. E6은 action 안에서 `counter.get()`을 읽어 다른 스레드의 예약 값을 발급하는 코드이고, E5는 예약 값을 entry의 값과 비교하는 코드다. (b)와 (c)는 "발급 순서 = 예약 순서"를 가정하므로 이 실행을 지운다. 그래서 (a)를 고른 근거가 유지된다.

### 15.1 예측 (결과 전)

1. 새로 판정되는 action은 정확히 7개다: 2차의 T-haiku-1, T-haiku-2, T-haiku-4 tryLock, 3g의 G-haiku-3, G-haiku-4, G-opus-1, G-sonnet-1 tryLock. 7개 모두 FAILED (`ends_only_if P3a`)다. 7개 모두 만료를 `>=`로 쓴다.
   - G-sonnet-1은 helper 호출 모양이다 (`nextToken()`이 companion 함수 안에서 increment). T-haiku-4는 `return if (granted.get()) token else null` 모양이다. 두 모양 모두 수락 테스트에 있어야 한다.
2. 그 밖의 action 행(3a와 3g)은 모두 그대로다.
3. 참조 모듈 6개의 생성 Viper는 **바이트 단위로 같다** (DESIGN-D의 설계 규칙). 기계적 변이 집합은 `mechanical.py`가 기록하는 정규화 모델 해시로 비교한다.
4. 손으로 고른 변이 63회는 불일치 0이다.
5. 다음은 UNSUPPORTED로 남는다 (부정 테스트): counter 말고 다른 곳에서 온 토큰(`System.nanoTime()`, 파라미터, 난수), 모듈 상태에 저장되거나 두 번째 action에 넘겨진 예약 값, 한 연산 안의 compute 두 번. 수정 1의 오염 검사는 정확히 counter 예약 경우만 좁혀 푼다.
6. 2차의 T-opus-2 tryLock(`Long.MAX_VALUE`)과 3g의 G-opus-2 tryLock(셀을 helper에 넘김)은 Part B 밖이므로 UNSUPPORTED 그대로다.

결과: 2차 12개는 판정 32 → 35 (36개 중), 3g 12개는 31 → 35 (36개 중)로 예측한다.

### 14.7 새 12개의 정답과 대조 (2026-10-26)

판정자 3명 (블라인드 deep-reasoner, 각 4개; `aicode/round3g/judgments-{1,2,3}.md`, harness는 `judge-work/`). 판정자는 도구 결과, AI-CODE.md, HISTORY.md, git 기록을 보지 않았다.

정답 (action 단위): tryLock과 sweep은 12개 모두 결함이다 (`progress_live`·P3a, 경계 `now == expiresAt`를 만료로 봄). unLock은 G-haiku-3만 결함이다 (`progress_owner`: 만료된 entry의 주인 해제를 거부). sidecar 밖 결함과 undecided는 0이다. 세 판정자 모두 harness로 경계 시나리오를 재현했다 (lease − 1에서 null, lease에서 새 토큰).

| 대조 (action 36개, 메인 세션이 보고서 원문으로 셈) | 수정 전 도구 | 수정 뒤 도구 |
|---|---|---|
| 검출 | 17 | 20 |
| 맞은 PROVEN | 9 | 11 |
| **놓침** | **0** | **0** |
| **오경보** | **0** | **0** |
| 판정 안 함 | 10 | 5 |

결론: 새 코드에서도 도구가 판정한 action은 모두 정답과 맞았다. A1-A5는 새 코드의 판정 범위를 넓히면서 놓침과 오경보를 만들지 않았다 (n = 12개 구현, 36 action).
판정자들의 공통 지적: SPEC.md의 "once `lockTimeoutMillis` has passed"는 경계를 정하지 않는다. 경계 결함은 서명된 sidecar(`>`) 기준이다.

### 15.2 결과 (2026-10-26, 메인 세션 확인)

구현 `8422673`(D0 lowering), `f794827`(D1 emitter, 보고서), 실행 `05be47d` (`aicode/round4/`).

| 집합 (action 36개, 메인 세션이 보고서 원문으로 다시 셈) | 판정 | 검출 | 맞은 PROVEN | 놓침 | 오경보 | 판정 안 함 |
|---|---|---|---|---|---|---|
| 2차 12개 (3a → 4) | 32 → 35 | 22 → 25 | 10 → 10 | 0 | 0 | 4 → 1 (T-opus-2, `Long.MAX_VALUE`) |
| 3g 12개 (3g → 4) | 31 → 35 | 20 → 24 | 11 → 11 | 0 | 0 | 5 → 1 (G-opus-2, 셀을 helper에 넘김) |

예측 대조:

| 예측 | 결과 |
|---|---|
| 1. 새 판정 7개, 모두 FAILED (P3a) | 맞음. 6개는 pass 1에서 P3a, G-opus-1은 pass 1 MI_INV_1 뒤 pass 2에서 P3a. 정답(11절, 14.7)과 7개 모두 일치 |
| 2. 그 밖의 action 행은 그대로 | 맞음 (Part B 아닌 17개 보고서가 Silicon 시간 말고 같음) |
| 3. 참조 6개 Viper 바이트 동일, 기계적 변이 해시 비교 | 맞음. Viper 6개와 `.positions` 6개 바이트 동일. 기계적 변이 284행은 수정 전·후 도구의 해시가 같다 (`mutation/rerun-partb/`). `rerun-c4`와는 sidecar가 바뀌어(`2137e85`) 비교 기준이 될 수 없었다 |
| 4. 테스트 통과, 변이 63회 불일치 0 | 맞음 (메인 세션 재실행: 테스트 621개 통과, mutantsReqShield 33회와 mutantsAspect 30회 불일치 0) |
| 5. 부정 테스트는 UNSUPPORTED | 일부 맞음. 모듈 상태 저장, 두 번째 compute, 조건부·반복 안·두 번 예약은 UNSUPPORTED. 그러나 `System.nanoTime()`, Random, 파라미터 토큰은 원래부터 UNSUPPORTED가 아니라 임의 값 입력으로 검증되며 FAILED다 (수정 전·후 같음) |
| 6. T-opus-2, G-opus-2는 그대로 | 맞음 |
| 판정 32 → 35, 31 → 35 | 맞음 |

메인 세션 결정:
- 예측 5의 nanoTime·Random·파라미터 토큰: 그대로 둔다. 이런 토큰은 유일함이 보장되지 않는다 (같은 nanoTime, 난수 충돌, 호출자가 준 값). 그래서 set-once 위반 FAILED는 거짓 이유가 아니라 실제 가능성이다. 거짓 PROVEN 방향이 아니다.
- G-opus-1의 pass 2가 87-127 s다 (예산 300 s). 원인은 재지 않았다. 다음 측정에서 지켜본다.
- R의 `0 <= n` 실패는 설계대로 property로 센다.

**누적 (AI 구현 24개, action 72개):** 판정 70개 (97%), 놓침 0, 오경보 0. 판정하지 못한 2개는 Part B 밖의 구문이다.
