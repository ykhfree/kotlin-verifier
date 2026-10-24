# 2차 측정: 도구만 다시 실행한 결과와 정답의 대조 (AI-CODE.md 11)

이 파일은 2차 측정의 결과다. AI-CODE.md와 HISTORY.md에 결과를 적는 일은 main session이 한다.

## 1. 무엇을 했나

1. 도구에 결정된 범위 수정 두 가지를 더했다.
   - 7c65fd3: `Int.toLong()`은 항등 변환이다. action 안의 `lockMap.size`는 `mappingCount()`처럼 계약 없는 비결정 값이다.
   - a04d9c4: 신뢰 모델 `kotlinx.Semaphore`. sidecar의 `Semaphore` 필드는 Kotlin 타입에 따라 `jdk.Semaphore` 또는 `kotlinx.Semaphore`에 묶인다. 고른 모델을 `use`하지 않으면 sidecar 오류 `missing-use`다.
2. 86d4475: `aicode.py`에 묶기 수정 `use MODEL`을 더했다. 변형 sidecar에 `use` 한 줄을 더하고, 묶기 수정 1개로 센다.
3. `seed.py`로 이 디렉터리를 만들었다.
   - 틀 구현 12개의 `KeyLocalLock.kt`를 1차와 같은 내용으로 복사했다 (`cmp`로 확인).
   - 1차의 `class LockInfo LockInfo` 수정 4개(T-haiku-1, T-haiku-3, T-haiku-4, T-sonnet-4)를 그대로 옮겼다.
   - kotlinx `Semaphore`를 쓴 4개(T-sonnet-2, T-opus-1, T-opus-3, T-opus-4)에 `use kotlinx.Semaphore`를 더했다.
   - `results.jsonl`에는 도구와 무관한 1차 필드만 옮겼다 (컴파일, 유사도, 테스트, 판정자). 도구, hole, 최종 판정은 옮기지 않았다.
4. 12개 각각에 `python3 -I translator/scripts/aicode.py --results aicode/round2 tool ID`를 실행했다.
   - 코드를 다시 생성하지 않았다. 단위 테스트와 판정자도 다시 실행하지 않았다.
5. FAILED인 7개에 `hole-skip`을 기록했다. 이유: "round 2 (AI-CODE.md 11) re-runs the tool only; no hole stage". 그 뒤 `summary`로 `summary.md`를 만들었다.
6. 아래 표는 `python3 -I aicode/round2/compare.py <repo>`가 1차 `aicode/logs/<ID>/tool.report`와 2차 `aicode/round2/logs/<ID>/tool.report`에서 만든다. 정답은 AI-CODE.md 11의 action 표다.

## 2. action 단위 대조

판정 열의 뜻 (AI-CODE.md 7.2):
- detected: 정답이 결함이고 도구가 FAILED (property)다.
- correct PROVEN: 정답이 정상이고 도구가 PROVEN이다.
- **miss**: 정답이 결함인데 도구가 PROVEN이다.
- **false alarm**: 정답이 정상인데 도구가 FAILED (property)다.
- not judged: UNSUPPORTED, 범위 밖(묶기 실패), 또는 ERROR라서 도구가 판정하지 않았다.

| ID | action | round 1 tool | round 2 tool | ground truth | round 1 | round 2 |
|---|---|---|---|---|---|---|
| T-haiku-1 | tryLock | UNSUPPORTED (Int toLong; helper call on existing) | UNSUPPORTED (flattened field semaphore; constructor default of semaphore; helper call on existing) | defect | not judged | not judged |
| T-haiku-1 | unLock | UNSUPPORTED (flattened field semaphore) | UNSUPPORTED (flattened field semaphore) | correct | not judged | not judged |
| T-haiku-1 | sweep | UNSUPPORTED (helper call on existing) | UNSUPPORTED (helper call on existing) | defect | not judged | not judged |
| T-haiku-2 | tryLock | UNSUPPORTED (Int toLong) | FAILED (property) | defect | not judged | detected |
| T-haiku-2 | unLock | PROVEN | PROVEN | correct | correct PROVEN | correct PROVEN |
| T-haiku-2 | sweep | FAILED (property) | FAILED (property) | defect | detected | detected |
| T-haiku-3 | tryLock | UNSUPPORTED (Int toLong) | UNSUPPORTED (flattened field semaphore; constructor default of semaphore) | defect | not judged | not judged |
| T-haiku-3 | unLock | UNSUPPORTED (flattened field semaphore) | UNSUPPORTED (flattened field semaphore) | defect | not judged | not judged |
| T-haiku-3 | sweep | UNSUPPORTED (safe call ?.) | UNSUPPORTED (safe call ?.) | defect | not judged | not judged |
| T-haiku-4 | tryLock | UNSUPPORTED (Int toLong; operation result) | UNSUPPORTED (flattened field semaphore; constructor default of semaphore; operation result) | defect | not judged | not judged |
| T-haiku-4 | unLock | PROVEN | PROVEN | correct | correct PROVEN | correct PROVEN |
| T-haiku-4 | sweep | FAILED (property) | FAILED (property) | defect | detected | detected |
| T-sonnet-1 | tryLock | UNSUPPORTED (Int toLong) | FAILED (property) | defect | not judged | detected |
| T-sonnet-1 | unLock | PROVEN | PROVEN | correct | correct PROVEN | correct PROVEN |
| T-sonnet-1 | sweep | FAILED (property) | FAILED (property) | defect | detected | detected |
| T-sonnet-2 | tryLock | out of range (binding failure: kotlin-type) | ERROR (tool defect: KVIR violates W5, W6) | defect | not judged | not judged |
| T-sonnet-2 | unLock | out of range (binding failure: kotlin-type) | ERROR (tool defect: KVIR violates W5, W6) | correct | not judged | not judged |
| T-sonnet-2 | sweep | out of range (binding failure: kotlin-type) | ERROR (tool defect: KVIR violates W5, W6) | defect | not judged | not judged |
| T-sonnet-3 | tryLock | UNSUPPORTED (Int toLong) | UNSUPPORTED (value of type java.util.concurrent.Semaphore; tryAcquire on semaphore; flattened field semaphore; constructor default of semaphore) | defect | not judged | not judged |
| T-sonnet-3 | unLock | UNSUPPORTED (flattened field semaphore) | UNSUPPORTED (flattened field semaphore) | defect | not judged | not judged |
| T-sonnet-3 | sweep | FAILED (property) | FAILED (property) | defect | detected | detected |
| T-sonnet-4 | tryLock | UNSUPPORTED (Int toLong) | FAILED (property) | defect | not judged | detected |
| T-sonnet-4 | unLock | PROVEN | PROVEN | correct | correct PROVEN | correct PROVEN |
| T-sonnet-4 | sweep | FAILED (property) | FAILED (property) | defect | detected | detected |
| T-opus-1 | tryLock | out of range (binding failure: kotlin-type) | UNSUPPORTED (value of type kotlinx.coroutines.sync.Semaphore; tryAcquire on semaphore; flattened field semaphore; constructor default of semaphore) | defect | not judged | not judged |
| T-opus-1 | unLock | out of range (binding failure: kotlin-type) | UNSUPPORTED (flattened field semaphore) | correct | not judged | not judged |
| T-opus-1 | sweep | out of range (binding failure: kotlin-type) | FAILED (property) | defect | not judged | detected |
| T-opus-2 | tryLock | UNSUPPORTED (if/when used as a value; constructor default of expiresAt) | UNSUPPORTED (if/when used as a value; constructor default of expiresAt) | defect | not judged | not judged |
| T-opus-2 | unLock | PROVEN | PROVEN | correct | correct PROVEN | correct PROVEN |
| T-opus-2 | sweep | FAILED (property) | FAILED (property) | defect | detected | detected |
| T-opus-3 | tryLock | out of range (binding failure: kotlin-type) | ERROR (tool defect: KVIR violates W5, W6) | defect | not judged | not judged |
| T-opus-3 | unLock | out of range (binding failure: kotlin-type) | ERROR (tool defect: KVIR violates W5, W6) | correct | not judged | not judged |
| T-opus-3 | sweep | out of range (binding failure: kotlin-type) | ERROR (tool defect: KVIR violates W5, W6) | defect | not judged | not judged |
| T-opus-4 | tryLock | out of range (binding failure: kotlin-type) | UNSUPPORTED (local alias entry) | defect | not judged | not judged |
| T-opus-4 | unLock | out of range (binding failure: kotlin-type) | ERROR (tool defect: KVIR violates W5, W6) | correct | not judged | not judged |
| T-opus-4 | sweep | out of range (binding failure: kotlin-type) | ERROR (tool defect: KVIR violates W5, W6) | defect | not judged | not judged |

## 3. 개수

| agreement | round 1 tryLock | round 1 unLock | round 1 sweep | round 2 tryLock | round 2 unLock | round 2 sweep | round 1 total | round 2 total |
|---|---|---|---|---|---|---|---|---|
| detected | 0 | 0 | 6 | 3 | 0 | 7 | 6 | 10 |
| correct PROVEN | 0 | 5 | 0 | 0 | 5 | 0 | 5 | 5 |
| not judged | 12 | 7 | 6 | 9 | 7 | 5 | 25 | 21 |

- miss와 false alarm은 1차와 2차 모두 0이다.
- 도구가 판정한 action: 1차 11개, 2차 15개 (36개 중).
- 2차에 새로 판정된 action 4개: tryLock 3개 (T-haiku-2, T-sonnet-1, T-sonnet-4), T-opus-1의 sweep.

모듈 단위:

| 모듈 판정 | 1차 | 2차 |
|---|---|---|
| PROVEN | 0 | 0 |
| FAILED | 6 | 7 (T-haiku-2, T-haiku-4, T-sonnet-1, T-sonnet-3, T-sonnet-4, T-opus-1, T-opus-2) |
| INCOMPLETE | 2 | 2 (T-haiku-1, T-haiku-3) |
| ERROR | 0 | 3 (T-sonnet-2, T-opus-3, T-opus-4) |
| 묶기 실패 (범위 밖) | 4 | 0 |

## 4. 예측 대조 (AI-CODE.md 11.1)

| 예측 | 대조 | 근거 (ID) |
|---|---|---|
| 1. 묶인 8개 중 tryLock이 범위에 들어오는 것은 정확히 T-haiku-2, T-haiku-3, T-sonnet-1, T-sonnet-3, T-sonnet-4이고, 5개 모두 FAILED (property)다 | 틀림 | 범위에 들어온 것은 3개다: T-haiku-2, T-sonnet-1, T-sonnet-4. 3개 모두 FAILED (property)이고 실패한 성질에 `ends_only_if P3a`가 있다. T-haiku-3과 T-sonnet-3은 계속 UNSUPPORTED다. `toLong` 다음에 lowering이 새 `Semaphore`를 만드는 곳에 닿았고, 그 모양이 모델 밖이다: T-haiku-3은 `Semaphore(1).also { it.tryAcquire() }`, T-sonnet-3은 지역 변수 `val semaphore = Semaphore(1)`. |
| 2. T-haiku-1 (helper 호출), T-haiku-4 (결과 반환 모양), T-opus-2 (생성자 기본값, 값으로 쓴 if)의 tryLock은 계속 UNSUPPORTED다 | 맞음 | 3개 모두 UNSUPPORTED이고 예측한 구문이 이유에 있다. T-haiku-1과 T-haiku-4에는 이유가 하나 더 있다: `Semaphore(1).apply { tryAcquire() }` (flattened field semaphore). |
| 3. T-haiku-1, T-haiku-3, T-sonnet-3의 unLock은 계속 UNSUPPORTED다. T-haiku-3과 T-sonnet-3의 unLock 결함은 계속 판정되지 않는다 | 맞음 (결과) | 3개 모두 UNSUPPORTED다. 도구가 대는 이유는 "필드 직접 접근"이 아니라 생성 모양이다: "a construction of semaphore does not pass a new jdk.Semaphore object" (`.apply`, `.also`, 지역 변수). |
| 4. kotlinx 4개는 이제 묶인다. 적어도 1개 action이 판정을 받고, 지원되는 sweep은 FAILED (property)다 | 맞음 | 4개 모두 묶였다 (묶기 수정 `use kotlinx.Semaphore` 1개씩). T-opus-1의 sweep이 FAILED (property)다 (`ends_only_if P3a`). 나머지 3개(T-sonnet-2, T-opus-3, T-opus-4)는 모듈 ERROR다 (5절). |
| 5. 판정이 정답과 어긋나는 action은 0이다 | 맞음 | 판정한 15개: detected 10, correct PROVEN 5, miss 0, false alarm 0. 성질 단위의 주의는 5절에 있다. |
| 6. 끝까지 PROVEN인 모듈은 0이다 | 맞음 | PROVEN 0, FAILED 7, INCOMPLETE 2, ERROR 3. |

## 5. 관찰

1. **ERROR 3개는 도구 결함이다. 이번 수정이 만든 결함이 아니다.**
   - 보고서 문구: "the KVIR violates W1-W14 (a tool defect)". W5와 W6이다.
     - T-sonnet-2: W5 at KeyLocalLock.kt:47:48 and 126:55 (`let info__117: RefT(cls=LockInfo, nullable=true) is not assignable to RefT(cls=LockInfo, nullable=false)`), W6 at 119:29 (`reads expiresAt of an object that is neither the entry nor created by the action`).
     - T-opus-3: W5 at 49:48 and 124:55, W6 at 117:22.
     - T-opus-4: W5 at 126:51, W6 at 120:22. tryLock은 따로 UNSUPPORTED다 (local alias entry).
   - 공통 모양: companion의 helper `isExpired(<LockInfo 매개변수>, now: Long)`를 smart cast된 entry로 부른다 (예: `existing != null && isExpired(existing, now)`). helper inlining이 매개변수를 nullable 타입의 `let`으로 묶는다 (W5). helper 본문의 `info.expiresAt`은 entry가 아닌 객체를 읽는 것이 된다 (W6).
   - 1차에서 이 3개는 묶기에서 멈춰서 이 경로에 닿지 않았다. 12개 중 이 helper 모양은 이 3개에만 있다.
   - 확인 실험 (scratchpad, 커밋 안 함): T-sonnet-2를 jdk `Semaphore(0)`과 `lockMap.mappingCount()`로 바꾸고 `use kotlinx.Semaphore`를 뺐다. 수정 전 도구(9277b7e)와 수정 후 도구가 둘 다 같은 W5 ERROR를 냈다 (47:48, `info__117`).
   - ERROR는 PROVEN이 아니므로 놓침이 아니다. 다만 이 3개 모듈의 action 9개는 판정되지 않았다.
2. **T-haiku-2의 tryLock: action은 detected지만 성질 단위로는 정답과 다른 보고가 섞여 있다.**
   - 1차 실패 항목: `MI_INV_1`, `MI_INV_3`, `cap_unbacked`, `ends_only_if P3a`, `issue tryLock: set-once gIssued`.
   - 판정자는 `cap_unbacked`가 12개 모두에서 성립한다고 보았다 (AI-CODE.md 10.1).
   - T-haiku-2는 토큰을 compute 밖에서 만든다 (`tokenSequence.incrementAndGet()` 45:33, 보고서의 INCOMPLETE 항목). 그래서 action 안의 토큰은 자유 값이고, set-once와 hole 실패가 함께 나온 것으로 보인다. 원인은 확인하지 않았다 (hole 단계를 하지 않았다).
3. **kotlinx `release()`의 증명 의무는 AI 구현에서 한 번도 실행되지 않았다.**
   - T-opus-1의 unLock은 UNSUPPORTED다 (지역 변수로 만든 Semaphore). T-sonnet-2, T-opus-3, T-opus-4는 ERROR다.
   - 의무는 고정 테스트(`KotlinxSemaphoreTest`)에서만 확인했다: 한 번 해제하면 PROVEN, 두 번 해제하면 그 위치에서 FAILED.
4. **2차에 새로 드러난 범위 밖 구문:** 새 `Semaphore`를 생성자 인자로 바로 넘기지 않는 모양이다.
   - `Semaphore(1).apply { tryAcquire() }` (T-haiku-1, T-haiku-4), `Semaphore(1).also { it.tryAcquire() }` (T-haiku-3).
   - 지역 변수 `val semaphore = Semaphore(1)` 다음에 `semaphore.tryAcquire()` (T-sonnet-3: jdk, T-opus-1: kotlinx).
   - 1차에서는 `Int toLong`에서 lowering이 먼저 멈춰서 이 구문이 tryLock의 이유로 보고되지 않았다. unLock의 이유로는 1차에도 있었다.
