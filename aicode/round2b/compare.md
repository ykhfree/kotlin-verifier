# 버그 수정 뒤 검증 실행: 2차와 2b의 대조 (AI-CODE.md 13)

이 파일은 수정 검증 실행(2b)의 결과다. 새 측정 회차가 아니다. AI-CODE.md와 HISTORY.md에 결과를 적는 일은 main session이 한다.

## 1. 무엇을 했나

1. 도구 결함 셋을 고쳤다 (AI-CODE.md 13).
   - e436d22 (수정 1): action의 입력(key, 자유 값, 셀, 연산 결과)이 ledger의 지원 밖 모듈 상태 접근을 거치면, 그 action은 UNSUPPORTED이고 출력하지 않는다. 이유는 그 구문과 위치를 댄다.
   - 43786d8 (수정 2): 인라인한 helper의 객체 파라미터는 받은 객체(entry 또는 action이 만든 객체) 자체에 묶인다.
   - 3208158 (수정 3): 지원 밖 부분 뒤에도 lowering이 나머지 부분을 내려서 action의 지원 밖 구문을 모두 보고한다. 평탄화 검사는 필드를 깨는 생성과 사용을 모두 댄다.
2. `seed.py`로 이 디렉터리를 만들었다.
   - 2차 구성을 그대로 썼다. `aicode/round2/impl/<ID>/`(KeyLocalLock.kt, bindings.txt)를 복사했다.
   - `results.jsonl`에는 도구와 무관한 2차 필드만 옮겼다 (컴파일, 유사도, 테스트, 판정자).
3. 12개 각각에 `python3 -I translator/scripts/aicode.py --results aicode/round2b tool ID`를 실행했다. 도구는 3208158의 `installDist`다.
4. FAILED인 10개에 `hole-skip`을 기록했다. 이유: "round 2b (AI-CODE.md 13) re-runs the tool only to check the fixes; no hole stage". 그 뒤 `summary`로 `summary.md`를 만들었다.
5. 아래 2절과 3절의 표는 `python3 -I aicode/round2b/compare.py <repo>`가 만든다.
   - 이 스크립트는 `aicode/round2/compare.py`의 보고서 읽기, 정답, 대조 범주를 그대로 불러 쓴다. 바뀐 것은 비교하는 두 디렉터리(`aicode/round2/logs`, `aicode/round2b/logs`)와 출력 모양뿐이다.
   - 정답은 AI-CODE.md 11의 action 표다 (`aicode/round2/compare.md`와 같다).

## 2. action 단위 변경

판정 열의 뜻은 `aicode/round2/compare.md` 2절과 같다 (detected, correct PROVEN, **miss**, **false alarm**, not judged).
UNSUPPORTED의 괄호 안은 보고서가 대는 지원 밖 구문의 이름이다.

### 2.1 판정이 바뀐 action

| ID | action | round 2 tool | round 2b tool | ground truth | round 2 | round 2b |
|---|---|---|---|---|---|---|
| T-haiku-2 | tryLock | FAILED (property) | UNSUPPORTED (input newToken) | defect | detected | not judged |
| T-sonnet-2 | tryLock | ERROR (tool defect: KVIR violates W5, W6) | FAILED (property) | defect | not judged | detected |
| T-sonnet-2 | unLock | ERROR (tool defect: KVIR violates W5, W6) | PROVEN | correct | not judged | correct PROVEN |
| T-sonnet-2 | sweep | ERROR (tool defect: KVIR violates W5, W6) | FAILED (property) | defect | not judged | detected |
| T-opus-3 | tryLock | ERROR (tool defect: KVIR violates W5, W6) | FAILED (property) | defect | not judged | detected |
| T-opus-3 | unLock | ERROR (tool defect: KVIR violates W5, W6) | PROVEN | correct | not judged | correct PROVEN |
| T-opus-3 | sweep | ERROR (tool defect: KVIR violates W5, W6) | FAILED (property) | defect | not judged | detected |
| T-opus-4 | unLock | ERROR (tool defect: KVIR violates W5, W6) | PROVEN | correct | not judged | correct PROVEN |
| T-opus-4 | sweep | ERROR (tool defect: KVIR violates W5, W6) | FAILED (property) | defect | not judged | detected |

이유:
- T-haiku-2 tryLock: 수정 1. 토큰을 compute 밖에서 만든다 (`val newToken = "local-${tokenSequence.incrementAndGet()}"`, KeyLocalLock.kt:45). 보고서 원문: "UNSUPPORTED input newToken in action tryLock#47 at KeyLocalLock.kt:45:9: its value comes from code outside the action that the model does not translate (tokenSequence operation outside an action: tokenSequence.incrementAndGet() at KeyLocalLock.kt:45:33)".
- T-sonnet-2, T-opus-3, T-opus-4 (2차 ERROR): 수정 2. W5/W6 위반이 사라졌다. tryLock과 sweep의 실패 성질은 `ends_only_if P3a`다 (경계 `>=`). unLock은 PROVEN이다. T-opus-4의 tryLock은 계속 UNSUPPORTED다 (2.2).

### 2.2 판정은 같고 이유가 바뀐 action

| ID | action | round 2 tool | round 2b tool | ground truth | round 2 | round 2b |
|---|---|---|---|---|---|---|
| T-haiku-1 | tryLock | UNSUPPORTED (flattened field semaphore; constructor default of semaphore; helper call on existing) | UNSUPPORTED (flattened field semaphore; helper call on existing; input token) | defect | not judged | not judged |
| T-haiku-3 | tryLock | UNSUPPORTED (flattened field semaphore; constructor default of semaphore) | UNSUPPORTED (flattened field semaphore) | defect | not judged | not judged |
| T-haiku-4 | tryLock | UNSUPPORTED (flattened field semaphore; constructor default of semaphore; operation result) | UNSUPPORTED (flattened field semaphore; operation result; input token) | defect | not judged | not judged |
| T-sonnet-3 | tryLock | UNSUPPORTED (value of type java.util.concurrent.Semaphore; tryAcquire on semaphore; flattened field semaphore; constructor default of semaphore) | UNSUPPORTED (value of type java.util.concurrent.Semaphore; new Semaphore; tryAcquire on semaphore; flattened field semaphore) | defect | not judged | not judged |
| T-opus-1 | tryLock | UNSUPPORTED (value of type kotlinx.coroutines.sync.Semaphore; tryAcquire on semaphore; flattened field semaphore; constructor default of semaphore) | UNSUPPORTED (value of type kotlinx.coroutines.sync.Semaphore; new Semaphore; tryAcquire on semaphore; flattened field semaphore) | defect | not judged | not judged |
| T-opus-2 | tryLock | UNSUPPORTED (if/when used as a value; constructor default of expiresAt) | UNSUPPORTED (if/when used as a value) | defect | not judged | not judged |
| T-opus-4 | tryLock | UNSUPPORTED (local alias entry) | UNSUPPORTED (local alias entry; if/when used as a value) | defect | not judged | not judged |

이유:
- "constructor default of semaphore"(5개)와 "constructor default of expiresAt"(T-opus-2)가 사라졌다: 수정 3. 그 인자는 주어졌지만 지원 밖이었다. 2차 보고서는 그것을 "기본값이 없다"고도 보고했다. 이것은 거짓 이유였다.
- "input token"(T-haiku-1, T-haiku-4): 수정 1. 두 구현도 토큰을 compute 밖에서 만든다 (41:30, 37:30). action은 이미 UNSUPPORTED였으므로 판정은 같다.
- "new Semaphore"(T-sonnet-3, T-opus-1): 수정 3. 지역 `val semaphore = Semaphore(1)`의 타입이 모델 밖이어도 초기화식을 내린다.
- "if/when used as a value"(T-opus-4): 수정 3. 지역 별칭 `val entry = if (...) ... else existing`의 초기화식을 내린다.

## 3. 개수

| agreement | round 2 tryLock | round 2 unLock | round 2 sweep | round 2b tryLock | round 2b unLock | round 2b sweep | round 2 total | round 2b total |
|---|---|---|---|---|---|---|---|---|
| detected | 3 | 0 | 7 | 4 | 0 | 10 | 10 | 14 |
| correct PROVEN | 0 | 5 | 0 | 0 | 8 | 0 | 5 | 8 |
| not judged | 9 | 7 | 5 | 8 | 4 | 2 | 21 | 14 |

- miss와 false alarm은 2차와 2b 모두 0이다.
- 도구가 판정한 action: 2차 15개, 2b 22개 (36개 중).

모듈 단위:

| 모듈 판정 | 2차 | 2b |
|---|---|---|
| PROVEN | 0 | 0 |
| FAILED | 7 | 10 (T-haiku-2, T-haiku-4, T-sonnet-1, T-sonnet-2, T-sonnet-3, T-sonnet-4, T-opus-1, T-opus-2, T-opus-3, T-opus-4) |
| INCOMPLETE | 2 | 2 (T-haiku-1, T-haiku-3) |
| ERROR | 3 | 0 |

## 4. 예측 대조 (AI-CODE.md 13)

| 예측 | 대조 | 근거 (ID) |
|---|---|---|
| 1. T-haiku-2의 tryLock은 FAILED에서 UNSUPPORTED가 된다. 이유는 compute 밖의 토큰 생성이다. 그래서 검출 1개가 줄어든다 | 맞음 | T-haiku-2 tryLock: FAILED (property) → UNSUPPORTED (input newToken, 45:33의 `tokenSequence.incrementAndGet()`). 그 action의 검출 1개가 줄었다. 전체 검출 수는 예측 2의 효과로 10 → 14다 |
| 2. ERROR였던 3개는 더 이상 ERROR가 아니다. 지원되는 sweep은 FAILED (property)다 (경계) | 맞음 | T-sonnet-2, T-opus-3, T-opus-4: 모듈 FAILED. sweep 3개 모두 FAILED (property), `ends_only_if P3a`. 덧붙여 T-sonnet-2와 T-opus-3의 tryLock은 FAILED (property, `ends_only_if P3a`), 세 unLock은 PROVEN이다 |
| 3. 놓침 0, action 단위 오경보 0이 유지된다 | 맞음 | 판정한 22개: detected 14, correct PROVEN 8, miss 0, false alarm 0 |
| 4. 1차·2차에서 UNSUPPORTED였던 action의 보고에 원인이 더 많이 나열된다 (예: T-haiku-3, T-sonnet-3의 tryLock에 Semaphore 생성 모양) | 일부만 맞음 | 아래 |

예측 4의 자세한 대조 (2차 → 2b, 거짓 이유 "constructor default"는 따로 센다):
- 참인 이유가 늘었다: T-haiku-1 tryLock (+input token), T-haiku-4 tryLock (+input token), T-sonnet-3 tryLock (+new Semaphore). 1차에 범위 밖이었던 T-opus-1 tryLock (+new Semaphore)과 T-opus-4 tryLock (+if/when used as a value)도 늘었다.
- 같다: T-haiku-1 unLock, sweep; T-haiku-3 tryLock, unLock, sweep; T-sonnet-3 unLock; T-opus-2 tryLock (참인 이유 기준).
- 예로 든 T-haiku-3 tryLock은 2b에서 이유가 늘지 않았다. 그 Semaphore 생성 모양(38:33)은 2차에 이미 나열되었다. 1차에서 그 모양이 가려진 원인(조건의 `Int toLong`)은 2차 구성에서 지원되므로, 이 실행은 그 경우를 다시 만들지 못한다. 그 경우는 고정 테스트 `ReasonTest`의 첫 테스트가 확인한다 (조건의 지원 밖 구문 뒤의 생성 모양).
- 거짓 이유 "constructor default of ..."가 tryLock 6개에서 사라졌다 (T-haiku-1, T-haiku-3, T-haiku-4, T-sonnet-3, T-opus-1, T-opus-2).

## 5. 관찰

1. kotlinx `release()`의 증명 의무가 AI 구현에서 처음 실행되었다. T-sonnet-2, T-opus-3, T-opus-4의 unLock이 각각 `assert existing__N.permits < 1`을 내고 PROVEN이다.
2. 수정 1의 범위 (메인 세션 판단 필요): 입력을 오염시키는 것은 ledger의 지원 밖 **모듈 상태 접근**뿐이다 (action 밖의 쓰기, 변경 연산, 상태 유출, 상태를 바꾸는 helper 호출).
   - 같은 ledger의 다른 지원 밖 구문인 "fresh 함수 밖의 value-class 생성자"(DESIGN-B.md 3.3 rule 4)는 입력을 오염시키지 않는다. 그 생성자는 모듈 상태를 바꾸지 않고, 모듈은 계속 INCOMPLETE다.
   - 이유 (측정): 그것도 오염으로 치면, aspect 변이 M5(공유 인스턴스)가 세 모듈 모두 FAILED (`MI_OWN`)에서 INCOMPLETE로 바뀐다. `GateTest`(spike M5 모델과 비교)와 `mutantsAspect`(30회 중 불일치 3)가 실패한다.
   - 이 실행의 12개 구현에서는 두 해석의 결과가 같다. 12개의 지원 밖 ledger 구문은 모두 `tokenSequence` 연산이다 (T-haiku-1, T-haiku-2, T-haiku-4).

