# AI가 쓴 코드로 시험

날짜: 2026-10-24. 상태: 결과 전에 쓴 측정 계획 (초안, advisor 검토 전). 결과를 본 뒤 규칙을 고치면 맨 아래 변경 기록에 날짜와 이유를 적는다.

## 1. 질문

사용자의 목표: "AI가 생성한 코드가 우리의 명세대로 작성이 된것인지 확인하고 싶은거야".
이 측정은 AI에게 자연어 명세만 주고 코드를 쓰게 한 뒤, 도구의 판정이 실제 정답과 맞는지 잰다.
1. AI 코드의 결함을 도구가 놓치는가 (놓침).
2. 정상인 AI 코드를 도구가 실패로 보는가 (오경보).
3. AI 코드가 도구의 지원 범위 안에 들어오는가 (범위).

## 2. 대상

req-shield coroutine 변형의 `KeyLocalLock` (`core-kotlin-coroutine`). Aspect는 연산이 `computeIfAbsent` 한 줄이라 대상에서 뺀다.

## 3. AI에게 주는 것 (명세 묶음)

1. 자연어 명세: req-shield README의 lock 관련 절(98-150행 근처: 소유 토큰, local lock map 크기와 상한, 정리 monitor), CLAUDE.md의 KeyLock 문장, `KeyLock.kt` 인터페이스와 KDoc.
2. 쓸 수 있는 helper: `nowToEpochTime()`, `LocalLockLimit.rejectsNewEntry(currentSize)`, `LOCK_MONITOR_INTERVAL_MILLIS`.
3. 검증 가능한 부분집합의 규칙 (모양 틀): sidecar가 코드에 묶이도록 이름과 구조를 정한다.
   - `class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock`
   - `companion object` 안에 `private val lockMap = ConcurrentHashMap<String, LockInfo>()`, `private val tokenSequence = AtomicLong(0)`. 토큰은 `"local-${tokenSequence.incrementAndGet()}"`.
   - `private class LockInfo(val semaphore: Semaphore, @Volatile var expiresAt: Long, val isHeld: AtomicBoolean = AtomicBoolean(false), @Volatile var token: String? = null)`
   - `lockMap`을 읽고 바꾸는 일은 연산마다 하나의 `lockMap.compute(key) { _, existing -> ... }` 안에서만 한다. 결과는 compute 앞에 선언한 `AtomicReference`/`AtomicBoolean`에 담는다.
   - `tryLock`은 compute 앞에서 `val now = nowToEpochTime()`을 읽는다. monitor는 key마다 `lockMap.compute`로 만료 entry를 지우고, 그 앞에서 `val now = System.currentTimeMillis()`를 읽는다.
   이 틀은 해법이 아니라 모양이다. compute 안의 논리(만료 처리, CAS, semaphore, 토큰 확인, 상한)는 AI가 정한다.
4. 컴파일 확인 스크립트 (검증은 하지 않는다).

AI는 sidecar, 도구의 검증 결과, 원래 구현, 변이와 판정 파일을 보지 않는다.

## 4. 생성

- 모델 3종(haiku, sonnet, opus) × 4회 = 구현 12개. 프롬프트는 모두 같다.
- 컴파일이 되지 않으면 "사산"으로 세고 판정에서 뺀다.
- (선택) 모양 틀 없이 3개를 더 만들어, 도구를 그대로 쓸 수 있는지만 본다.

## 5. 정답 (도구와 독립)

1. req-shield의 `KeyLocalLockTest`(coroutine)를 AI 코드로 돌린다. 테스트는 부분 정답이다.
2. 새 맥락의 판정자(deep-reasoner)가 도구의 결과를 보지 않고 각 구현을 판정한다.
   - 근거는 자연어 명세와 sidecar의 성질 원문이다.
   - 결함이 있다고 보면, 깨지는 성질과 구체적 interleaving을 댄다.
   - 정상이라고 보면, 성질마다 논증한다.
   - 둘 다 세우지 못하면 미결이다.
3. 판정이 테스트와 어긋나면 그 이유를 적는다.

## 6. 도구 흐름 (실제 사용 흐름)

1. AI 코드에 sidecar를 그대로 쓴다 (coroutine 변형 파일의 `source`만 AI 코드로 바꾼다). `kv verify`.
2. 묶기 실패나 INCOMPLETE이면 그대로 기록한다 (범위 밖).
3. FAILED이면 불변식(hole)을 다시 쓴다.
   - 원래 hole은 원래 코드에 맞춘 것이다. 그래서 정상 코드도 실패할 수 있다.
   - 성질 위반 보고도 원래 hole의 상태에서 나온 것일 수 있다.
   - 새 맥락의 LLM이 sidecar의 hole 문자열만 고친다. `kv hole check`와 `kv verify`를 부르는 스크립트를 쓰고, 최대 20회다 (spike E3과 같은 규칙).
4. 최종 판정:
   - PROVEN: 원래 hole 또는 새 hole로 증명됨.
   - FAILED: 모든 시도에서 실패. 마지막 보고의 하위 상태를 함께 적는다.
   - 범위 밖.

## 7. 분류

| 정답 \ 도구 최종 | PROVEN | FAILED | 범위 밖 |
|---|---|---|---|
| 결함 있음 | **놓침** (가장 중요) | 검출 | 범위 밖 |
| 정상 | 맞음 | 오경보 (hole을 못 찾은 경우 포함) | 범위 밖 |
| 미결 | 미결 | 미결 | 범위 밖 |

## 8. 미리 등록한 예측 (결과 전)

1. 구현 12개 중 컴파일되는 것이 8개 이상이다.
2. 적어도 1개 구현에 실제 결함이 있다. 후보: 상한 처리, 만료 takeover의 CAS, unLock의 토큰 확인.
3. 정답이 결함으로 판정한 구현을 도구가 PROVEN으로 놓치는 경우는 0이다.
4. 정상 구현 가운데 일부는 hole을 다시 써도 증명되지 않아 오경보가 될 수 있다. 비율은 모른다.
5. 일부 구현은 범위 밖이다 (예: compute 2개, `putIfAbsent`, `synchronized` 안의 compute).

## 변경 기록

(없음)
