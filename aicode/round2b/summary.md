# AI가 쓴 코드: 집계 (AI-CODE.md 7)

`translator/scripts/aicode.py summary`가 `results.jsonl`에서 만든다. 손으로 고치지 않는다.

## 구현별

| ID | 모델 | 종류 | 컴파일 | compile.sh 호출 | 유사도 | 테스트 | 도구 (처음) | 묶기 이름 바꿈 | hole | 최종 | 판정자 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| T-haiku-1 | haiku | template | 예 | 1 | 0.230 | 14/14 (shim) | INCOMPLETE | 1 | - | out of scope | defect-property |
| T-haiku-2 | haiku | template | 예 | 2 | 0.233 | 14/14 (shim) | FAILED (property) | 0 | skipped: round 2b (AI-CODE.md 13) re-runs the tool only to check the fixes; no hole stage | FAILED (property) | defect-property |
| T-haiku-3 | haiku | template | 예 | 1 | 0.221 | 14/14 (shim) | INCOMPLETE | 1 | - | out of scope | defect-property |
| T-haiku-4 | haiku | template | 예 | 1 | 0.217 | 14/14 (shim) | FAILED (property) | 1 | skipped: round 2b (AI-CODE.md 13) re-runs the tool only to check the fixes; no hole stage | FAILED (property) | defect-property |
| T-sonnet-1 | sonnet | template | 예 | 1 | 0.317 | 14/14 (shim) | FAILED (property) | 0 | skipped: round 2b (AI-CODE.md 13) re-runs the tool only to check the fixes; no hole stage | FAILED (property) | defect-property |
| T-sonnet-2 | sonnet | template | 예 | 1 | 0.331 | 14/14 (shim) | FAILED (property) | 1 | skipped: round 2b (AI-CODE.md 13) re-runs the tool only to check the fixes; no hole stage | FAILED (property) | defect-property |
| T-sonnet-3 | sonnet | template | 예 | 1 | 0.317 | 14/14 (shim) | FAILED (property) | 0 | skipped: round 2b (AI-CODE.md 13) re-runs the tool only to check the fixes; no hole stage | FAILED (property) | defect-property |
| T-sonnet-4 | sonnet | template | 예 | 1 | 0.292 | 14/14 (shim) | FAILED (property) | 1 | skipped: round 2b (AI-CODE.md 13) re-runs the tool only to check the fixes; no hole stage | FAILED (property) | defect-property |
| T-opus-1 | opus | template | 예 | 3 | 0.569 | 14/14 (shim) | FAILED (property) | 1 | skipped: round 2b (AI-CODE.md 13) re-runs the tool only to check the fixes; no hole stage | FAILED (property) | defect-property |
| T-opus-2 | opus | template | 예 | 1 | 0.374 | 14/14 (shim) | FAILED (property) | 0 | skipped: round 2b (AI-CODE.md 13) re-runs the tool only to check the fixes; no hole stage | FAILED (property) | defect-property |
| T-opus-3 | opus | template | 예 | 3 | 0.362 | 14/14 (shim) | FAILED (property) | 1 | skipped: round 2b (AI-CODE.md 13) re-runs the tool only to check the fixes; no hole stage | FAILED (property) | defect-property |
| T-opus-4 | opus | template | 예 | 2 | 0.348 | 14/14 (shim) | FAILED (property) | 1 | skipped: round 2b (AI-CODE.md 13) re-runs the tool only to check the fixes; no hole stage | FAILED (property) | defect-property |

## 오염 검사 (4.3)

- 유사도 분포: 0.217, 0.221, 0.230, 0.233, 0.292, 0.317, 0.317, 0.331, 0.348, 0.362, 0.374, 0.569
- 0.9 이상: 0개 (없음)

## 7.1 범위

| 항목 | 본 실험 (template) | 범위 실험 (free) |
|---|---|---|
| 사산 (컴파일 실패) | 0개 (-) | 0개 (-) |
| 묶기 실패 | 없음 | 없음 |
| 범위 밖 (INCOMPLETE) | T-haiku-1: tryLock: UNSUPPORTED flattened field semaphore in action tryLock#43 at KeyLocalLock.kt:52:41: a construction of semaphore does not pass a new jdk.Semaphore object at KeyLocalLock.kt:52:41; UNSUPPORTED helper call on existing in action tryLock#43 at KeyLocalLock.kt:59:17: v0 inlines helpers of this object or class only; UNSUPPORTED input token in action tryLock#43 at KeyLocalLock.kt:41:9: its value comes from code outside the action that the model does not translate (tokenSequence operation outside an action: tokenSequence.incrementAndGet() at KeyLocalLock.kt:41:30); an unmodeled part may break what the proof relies on (DESIGN-v0.md 5.8); unLock: UNSUPPORTED flattened field semaphore in action unLock#80 at KeyLocalLock.kt:84:17: a construction of semaphore does not pass a new jdk.Semaphore object at KeyLocalLock.kt:52:41; sweep: UNSUPPORTED helper call on existing in action sweep#112 at KeyLocalLock.kt:113:45: v0 inlines helpers of this object or class only; tryLock KeyLocalLock.kt:43:9; unLock KeyLocalLock.kt:80:9; sweep KeyLocalLock.kt:112:17; probe; tokenSequence operation outside an action: tokenSequence.incrementAndGet() 41:30<br>T-haiku-3: tryLock: UNSUPPORTED flattened field semaphore in action tryLock#25 at KeyLocalLock.kt:38:33: a construction of semaphore does not pass a new jdk.Semaphore object at KeyLocalLock.kt:38:33; unLock: UNSUPPORTED flattened field semaphore in action unLock#57 at KeyLocalLock.kt:59:17: a construction of semaphore does not pass a new jdk.Semaphore object at KeyLocalLock.kt:38:33; sweep: UNSUPPORTED safe call ?. in action sweep#94 at KeyLocalLock.kt:94:55: not translated inside an action in v0; tryLock KeyLocalLock.kt:25:9; unLock KeyLocalLock.kt:57:9; sweep KeyLocalLock.kt:94:17; probe | 없음 |

### 자유 구현

| ID | 컴파일 | 묶기에서 깨지는 것 | 이름만 바꾸면 돌아가는가 |
|---|---|---|---|

## 7.2 정확성 (본 실험 중 범위 안)

칸의 ID는 그 구현이다. FAILED (invariant only)는 hole을 다시 써도 증명되지 않은 것이다. FAILED (pending)는 hole 단계가 남은 것이다.

| 정답 \ 도구 최종 | PROVEN | FAILED (property) | FAILED (invariant only) | FAILED (pending) |
|---|---|---|---|---|
| 결함 (sidecar 성질) | - | T-haiku-2, T-haiku-4, T-sonnet-1, T-sonnet-2, T-sonnet-3, T-sonnet-4, T-opus-1, T-opus-2, T-opus-3, T-opus-4 | - | - |
| 결함 (sidecar 밖) | - | - | - | - |
| 정상 | - | - | - | - |
| 미결 | - | - | - | - |
| 판정 대기 | - | - | - | - |

범위 밖이거나 대기 중인 본 실험 구현: T-haiku-1 (out of scope), T-haiku-3 (out of scope)
