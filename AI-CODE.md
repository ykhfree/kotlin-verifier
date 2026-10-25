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

