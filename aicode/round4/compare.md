# 4차 측정: Part B (DESIGN-D (a)) 뒤 AI 구현 24개에 도구만 다시 실행 (AI-CODE.md 15)

이 파일은 4차 실행의 결과다. AI-CODE.md와 HISTORY.md에 결과를 적는 일은 main session이 한다.

## 1. 무엇을 했나

1. 도구를 바꿨다. 커밋 `8422673`(D0: lowering)과 `f794827`(D1: emitter와 보고서).
   - 예약 단계 R: compute 앞의 지역 `val`이 counter의 다음 값을 받는다 (`C.incrementAndGet()`, 그것을 담은 토큰 템플릿, 또는 그것을 돌려주는 helper 호출). R은 action 밖의 atomic 단계 하나로 모델링한다.
   - 도구 ghost `gPend`, 도구 사실 MI_PEND와 PEND, 발급마다 `assert !tok(res).gPend`.
   - W10 완화: object 모듈의 결과는 셀, 상수, 예약 `val`을 읽는다. if-else, `&&`, `||`, `!`로 묶어도 된다.
2. `aicode/round4/seed.py`로 결과 디렉터리 두 개를 새로 만들었다. `aicode/round2*/`와 `aicode/round3*/`에는 아무것도 쓰지 않았다.
   - `r2/`: `aicode/round2/impl`의 12개와 bindings.txt (`aicode/round3a/seed.py`와 같은 단계).
   - `g/`: `aicode/round3g/impl`의 12개와 bindings.txt.
3. `f794827`의 `installDist`로 24개 각각에 `python3 -I translator/scripts/aicode.py --results aicode/round4/{r2,g} tool ID`를 실행했다. sidecar는 `specs/reqshield` 그대로다 (3a, 3g와 같은 sidecar).
4. 24개 모두 FAILED다. 24개에 `hole-skip`을 기록했다. 이유: "round 4 (AI-CODE.md 15, Part B) re-runs the tool only to measure DESIGN-D (a); no hole stage". 그 뒤 `summary`로 `summary.md`를 만들었다.
5. 아래 2절의 표는 `python3 -I aicode/round4/compare.py <repo>`가 만든다.
   - 보고서 읽기와 대조 범주는 `aicode/round2/compare.py`의 것을 그대로 불러 쓴다.
   - 2차 12개의 정답은 AI-CODE.md 11절이다 (`aicode/round2/compare.py`의 `truth`).
   - 새 12개의 정답은 AI-CODE.md 14.7절의 요약이다: tryLock과 sweep은 12개 모두 결함, unLock은 G-haiku-3만 결함. 판정자 파일(`judgments-*.md`)은 읽지 않았다.

## 2. action 단위 비교

판정 열의 뜻은 `aicode/round2/compare.md` 2절과 같다 (detected, correct PROVEN, **miss**, **false alarm**, not judged).

### 2.1 2차 12개 (Set r2): aicode/round3a/logs -> aicode/round4/r2/logs

#### Verdict changes

| ID | action | round 3a tool | round 4 tool | ground truth | round 3a | round 4 |
|---|---|---|---|---|---|---|
| T-haiku-1 | tryLock | UNSUPPORTED (input token) | FAILED (property) | defect | not judged | detected |
| T-haiku-2 | tryLock | UNSUPPORTED (input newToken) | FAILED (property) | defect | not judged | detected |
| T-haiku-4 | tryLock | UNSUPPORTED (operation result; input token) | FAILED (property) | defect | not judged | detected |

#### Failing obligations of the newly judged actions (round 4 report; pass 1, pass 2)

| ID | action | round 4 tool | pass 1 | pass 2 |
|---|---|---|---|---|
| T-haiku-1 | tryLock | FAILED (property) | ends_only_if P3a | - |
| T-haiku-2 | tryLock | FAILED (property) | ends_only_if P3a | - |
| T-haiku-4 | tryLock | FAILED (property) | ends_only_if P3a | - |

#### Reason changes, same verdict

| ID | action | round 3a tool | round 4 tool | ground truth | round 3a | round 4 |
|---|---|---|---|---|---|---|

#### Counts

| agreement | round 3a tryLock | round 3a unLock | round 3a sweep | round 4 tryLock | round 4 unLock | round 4 sweep | round 3a total | round 4 total |
|---|---|---|---|---|---|---|---|---|
| detected | 8 | 2 | 12 | 11 | 2 | 12 | 22 | 25 |
| correct PROVEN | 0 | 10 | 0 | 0 | 10 | 0 | 10 | 10 |
| not judged | 4 | 0 | 0 | 1 | 0 | 0 | 4 | 1 |

#### Reserve steps (round 4)

| ID | step | increment | verdict |
|---|---|---|---|
| T-haiku-1 | reserve tryLock | KeyLocalLock.kt:41:30 | PROVEN |
| T-haiku-2 | reserve tryLock | KeyLocalLock.kt:45:33 | PROVEN |
| T-haiku-4 | reserve tryLock | KeyLocalLock.kt:37:30 | PROVEN |

#### 2.2 새 12개 (Set g): aicode/round3g/logs -> aicode/round4/g/logs

#### Verdict changes

| ID | action | round 3g tool | round 4 tool | ground truth | round 3g | round 4 |
|---|---|---|---|---|---|---|
| G-haiku-3 | tryLock | UNSUPPORTED (input token) | FAILED (property) | defect | not judged | detected |
| G-haiku-4 | tryLock | UNSUPPORTED (input token) | FAILED (property) | defect | not judged | detected |
| G-sonnet-1 | tryLock | UNSUPPORTED (operation result; input newToken) | FAILED (property) | defect | not judged | detected |
| G-opus-1 | tryLock | UNSUPPORTED (input newToken) | FAILED (property) | defect | not judged | detected |

#### Failing obligations of the newly judged actions (round 4 report; pass 1, pass 2)

| ID | action | round 4 tool | pass 1 | pass 2 |
|---|---|---|---|---|
| G-haiku-3 | tryLock | FAILED (property) | ends_only_if P3a | - |
| G-haiku-4 | tryLock | FAILED (property) | ends_only_if P3a | - |
| G-sonnet-1 | tryLock | FAILED (property) | ends_only_if P3a | - |
| G-opus-1 | tryLock | FAILED (property) | MI_INV_1 | ends_only_if P3a |

#### Reason changes, same verdict

| ID | action | round 3g tool | round 4 tool | ground truth | round 3g | round 4 |
|---|---|---|---|---|---|---|

#### Counts

| agreement | round 3g tryLock | round 3g unLock | round 3g sweep | round 4 tryLock | round 4 unLock | round 4 sweep | round 3g total | round 4 total |
|---|---|---|---|---|---|---|---|---|
| detected | 7 | 1 | 12 | 11 | 1 | 12 | 20 | 24 |
| correct PROVEN | 0 | 11 | 0 | 0 | 11 | 0 | 11 | 11 |
| not judged | 5 | 0 | 0 | 1 | 0 | 0 | 5 | 1 |

#### Reserve steps (round 4)

| ID | step | increment | verdict |
|---|---|---|---|
| G-haiku-3 | reserve tryLock | KeyLocalLock.kt:42:30 | PROVEN |
| G-haiku-4 | reserve tryLock | KeyLocalLock.kt:42:30 | PROVEN |
| G-sonnet-1 | reserve tryLock | KeyLocalLock.kt:87:52 | PROVEN |
| G-opus-1 | reserve tryLock | KeyLocalLock.kt:42:33 | PROVEN |


## 3. 3a, 3g와 같은지 (예측 2의 근거)

1. Part B가 아닌 구현 17개: 보고서 전체가 3a 또는 3g와 같다. Silicon 시간만 다르다 (`diff`, `(Silicon N s)`를 지우고 비교).
2. Part B 구현 7개: 바뀐 행은 `action tryLock`, 새 `step reserve tryLock`, init·lemma·probe 행(probe UNSUPPORTED → PROVEN)뿐이다. unLock과 sweep 행과 그 실패 줄은 같다. "Unsupported:" 줄은 "none"이 되었다.
3. 모듈 판정은 24개 모두 3a·3g와 같다 (FAILED).

## 4. 시간

1. Part B 7개의 Silicon 시간: 10.45-11.66 s. 3a·3g에서는 3.34-3.47 s였다 (tryLock을 내지 않았다). 모든 action을 내는 다른 구현은 5.6-5.9 s다 (예: 3a T-sonnet-1 5.85 s).
2. G-opus-1의 property check (pass 2): 86.55 s. 예약 단계가 없는 G-haiku-2의 pass 2는 3g에서 10.97 s, 4차에서 10.95 s다. 작업 중 먼저 돌린 실행에서는 126.68 s였다. 예산(300 s) 안이다. 원인은 재지 않았다 (6절 열린 질문 1).

## 5. AI-CODE.md 15.1 예측 대조

| 예측 | 결과 | 근거 |
|---|---|---|
| 1. 새로 판정되는 action은 정확히 7개이고, 7개 모두 FAILED (`ends_only_if P3a`)다 | 맞음 (단서 1개) | 2절 표. 7개 모두 FAILED (property), 정답은 7개 모두 결함이다 (detected). 6개는 pass 1의 실패가 `ends_only_if P3a` 하나다. G-opus-1은 pass 1에서 hole 조각 MI_INV_1(`Semaphore(1)`로 permits 1)에서 실패하고, property check(pass 2)에서 `ends_only_if P3a`로 실패한다 |
| 1a. G-sonnet-1 helper 모양, T-haiku-4 `return if (granted.get()) token else null` | 맞음 | 두 tryLock 모두 FAILED (`ends_only_if P3a`). 예약 단계 행은 G-sonnet-1에서 helper 안의 increment(87:52)를 가리킨다 |
| 2. 그 밖의 action 행은 모두 그대로 | 맞음 | 3절. 17개 보고서는 시간 말고 같다. Part B 7개의 unLock, sweep 행도 같다 |
| 결과: 2차 12개 32 → 35, 3g 12개 31 → 35 (36개 중) | 맞음 | 2절 Counts: r2 판정 안 함 4 → 1 (판정 32 → 35), g 판정 안 함 5 → 1 (판정 31 → 35) |
| 6. T-opus-2 tryLock(`Long.MAX_VALUE`), G-opus-2 tryLock(셀을 helper에 넘김)은 UNSUPPORTED 그대로 | 맞음 | 두 보고서가 3a, 3g와 같다 (3절 1) |

놓침 0, 오경보 0이 유지된다 (2절 Counts에 miss, false alarm 행이 없다).

예측 3-5의 근거는 이 측정 밖에 있다: 참조 모듈 6개의 바이트 비교, `mutation/rerun-partb/` (모델 해시), 손 변이 63회, 테스트 (커밋 `8422673`, `f794827`).

## 6. 열린 질문

1. G-opus-1의 pass 2가 86.55-126.68 s 걸렸다. 예약 단계가 있는 모듈의 pass 2가 왜 느린지 재지 않았다. 예산 300 s에 가까워지면 ERROR가 된다.
