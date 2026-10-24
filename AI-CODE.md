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
