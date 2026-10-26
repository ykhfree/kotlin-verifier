# 3차 측정 3a: A1-A5 지원 뒤 2차 구현 12개에 도구만 다시 실행 (AI-CODE.md 14)

이 파일은 3a 실행의 결과다. AI-CODE.md와 HISTORY.md에 결과를 적는 일은 main session이 한다.

## 1. 무엇을 했나

1. 지원 범위를 넓혔다 (AI-CODE.md 14.1). 커밋 `f3f267f`.
   - A1: 새 entry에 넘기기 전에 모델 연산으로 준비한 Semaphore (`Semaphore(1).apply { tryAcquire() }`, `.also { it.tryAcquire() }`, 지역 `val s = Semaphore(1); s.tryAcquire(); LockInfo(s, ...)`). 준비 연산은 생성 바로 뒤에 새 entry의 평탄화 필드에서 실행한다.
   - A2: 값으로 쓴 if/when. 각 분기가 값을 대입하는 지역 변수가 된다.
   - A3: entry나 이 action이 만든 객체의 지역 `val` 별칭. if/when 별칭은 그 뒤의 문장을 객체마다 한 번씩 내린다.
   - A4: action 안의 `?.`. 결과 위치의 `takeIf`/`takeUnless`.
   - A5: 모듈 클래스의 final이고 순수한 멤버 함수. `this`를 받는 객체에 묶어 인라인한다.
2. `seed.py`로 이 디렉터리와 `base/`를 만들었다. 2b와 같은 단계다.
   - 2차 구성을 그대로 썼다. `aicode/round2/impl/<ID>/`(KeyLocalLock.kt, bindings.txt)를 복사했다.
   - `results.jsonl`에는 도구와 무관한 2차 필드만 옮겼다.
3. `f3f267f`의 `installDist`로 12개 각각에 `python3 -I translator/scripts/aicode.py --results aicode/round3a tool ID`를 실행했다.
4. 12개 모두 FAILED다. 12개에 `hole-skip`을 기록했다. 이유: "round 3a (AI-CODE.md 14) re-runs the tool only to measure A1-A5; no hole stage". 그 뒤 `summary`로 `summary.md`를 만들었다.
5. **sidecar가 2b와 다르다.** 도구는 `specs/reqshield`를 그대로 읽는다. 2b 뒤에 sidecar에 절이 더해졌다: `refusal_keeps_entry` (tryLock), `no_entry_created` (unLock, sweep), 불변식 `entry_lease`, `entry_held` (커밋 `bdf08d9`, `b8bddb3`, `6138a8b`, `2137e85`).
   - 그래서 수정 전 도구로 같은 sidecar를 한 번 더 돌렸다: `base/`. 도구는 `2d479ee`(git worktree)의 `installDist`다. 명령은 같고 `--env`만 그 도구를 가리킨다.
   - base의 action 행(판정과 첫 실패 문장)은 2b와 모두 같다 (sidecar 줄 번호만 다르다). 그래서 2b → 3a의 변화는 모두 lowering의 변화다.
6. 아래 표는 `python3 -I aicode/round3a/compare.py <repo>`가 만든다. 보고서 읽기, 정답, 대조 범주는 `aicode/round2/compare.py`의 것을 그대로 불러 쓴다 (2b와 같다).

## 2. action 단위 변경 (2b → 3a)

판정 열의 뜻은 `aicode/round2/compare.md` 2절과 같다 (detected, correct PROVEN, **miss**, **false alarm**, not judged).

### 2.1 판정이 바뀐 action

| ID | action | round 2b tool | round 3a tool | ground truth | round 2b | round 3a |
|---|---|---|---|---|---|---|
| T-haiku-1 | unLock | UNSUPPORTED (flattened field semaphore) | PROVEN | correct | not judged | correct PROVEN |
| T-haiku-1 | sweep | UNSUPPORTED (helper call on existing) | FAILED (property) | defect | not judged | detected |
| T-haiku-3 | tryLock | UNSUPPORTED (flattened field semaphore) | FAILED (property) | defect | not judged | detected |
| T-haiku-3 | unLock | UNSUPPORTED (flattened field semaphore) | FAILED (property) | defect | not judged | detected |
| T-haiku-3 | sweep | UNSUPPORTED (safe call ?.) | FAILED (property) | defect | not judged | detected |
| T-sonnet-3 | tryLock | UNSUPPORTED (value of type java.util.concurrent.Semaphore; new Semaphore; tryAcquire on semaphore; flattened field semaphore) | FAILED (property) | defect | not judged | detected |
| T-sonnet-3 | unLock | UNSUPPORTED (flattened field semaphore) | FAILED (property) | defect | not judged | detected |
| T-opus-1 | tryLock | UNSUPPORTED (value of type kotlinx.coroutines.sync.Semaphore; new Semaphore; tryAcquire on semaphore; flattened field semaphore) | FAILED (property) | defect | not judged | detected |
| T-opus-1 | unLock | UNSUPPORTED (flattened field semaphore) | PROVEN | correct | not judged | correct PROVEN |
| T-opus-4 | tryLock | UNSUPPORTED (local alias entry; if/when used as a value) | FAILED (property) | defect | not judged | detected |

이유 (모두 base → 3a에서도 같은 변화다. 4절):
- T-haiku-1 unLock, T-haiku-3 unLock, T-sonnet-3 unLock, T-opus-1 unLock: A1. 모듈의 `semaphore` 생성이 모두 새 객체를 넘기므로 평탄화 검사가 통과한다.
- T-haiku-3 tryLock (`also`), T-sonnet-3 tryLock (지역 val, jdk), T-opus-1 tryLock (지역 val, kotlinx): A1.
- T-haiku-1 sweep: A5 (`existing.isExpired(now)`).
- T-haiku-3 sweep: A4 (`existing?.takeIf { it.expiresAt > now }`).
- T-opus-4 tryLock: A3과 A2 (`val entry = if (...) LockInfo(...) else existing`).
- 실패 성질: 새 tryLock 4개와 sweep 2개는 모두 `ends_only_if P3a`다 (경계 `>=`). T-haiku-3 unLock은 `progress_owner`, T-sonnet-3 unLock은 `ends_only_if P3a`다.

### 2.2 판정은 같고 이유가 바뀐 action

| ID | action | round 2b tool | round 3a tool | ground truth | round 2b | round 3a |
|---|---|---|---|---|---|---|
| T-haiku-1 | tryLock | UNSUPPORTED (flattened field semaphore; helper call on existing; input token) | UNSUPPORTED (input token) | defect | not judged | not judged |
| T-haiku-4 | tryLock | UNSUPPORTED (flattened field semaphore; operation result; input token) | UNSUPPORTED (operation result; input token) | defect | not judged | not judged |
| T-opus-2 | tryLock | UNSUPPORTED (if/when used as a value) | UNSUPPORTED (read of Companion.MAX_VALUE) | defect | not judged | not judged |

이유:
- T-haiku-1, T-haiku-4 tryLock: A1과 A5가 이유를 지웠다. 남은 이유는 Part B다 (compute 밖에서 counter로 만든 토큰, "input token"; T-haiku-4는 그 토큰을 돌려주는 "operation result"도).
- T-opus-2 tryLock: A2가 if 값을 내리자 그 안의 `Long.MAX_VALUE`(KeyLocalLock.kt:85:33, 85:55, helper `expiryAfter`)가 드러났다. 보고서 원문: "UNSUPPORTED read of Companion.MAX_VALUE in action tryLock#46 at KeyLocalLock.kt:85:33: a property outside the model; only immutable properties of the receiver instance are free values (DESIGN-v0.md 2.12)". 이 구문은 A1-A5에 없다.

## 3. 개수

| agreement | round 2b tryLock | round 2b unLock | round 2b sweep | round 3a tryLock | round 3a unLock | round 3a sweep | round 2b total | round 3a total |
|---|---|---|---|---|---|---|---|---|
| detected | 4 | 0 | 10 | 8 | 2 | 12 | 14 | 22 |
| correct PROVEN | 0 | 8 | 0 | 0 | 10 | 0 | 8 | 10 |
| not judged | 8 | 4 | 2 | 4 | 0 | 0 | 14 | 4 |

- miss와 false alarm은 2b와 3a 모두 0이다.
- 도구가 판정한 action: 2b 22개, 3a 32개 (36개 중). 판정 안 함 4개: Part B의 tryLock 3개 (T-haiku-1, T-haiku-2, T-haiku-4)와 T-opus-2 tryLock.

모듈 단위:

| 모듈 판정 | 2b | 3a |
|---|---|---|
| PROVEN | 0 | 0 |
| FAILED | 10 | 12 |
| INCOMPLETE | 2 (T-haiku-1, T-haiku-3) | 0 |
| ERROR | 0 | 0 |

T-haiku-1, T-haiku-2, T-haiku-4, T-opus-2 모듈은 FAILED이면서 tryLock이 UNSUPPORTED다.

## 4. lowering만의 효과 (base → 3a)

base는 `2d479ee` 도구에 3a와 같은 sidecar를 준 실행이다.

| ID | action | base tool | round 3a tool | ground truth | base | round 3a |
|---|---|---|---|---|---|---|
| T-haiku-1 | tryLock | UNSUPPORTED (flattened field semaphore; helper call on existing; input token) | UNSUPPORTED (input token) | defect | not judged | not judged |
| T-haiku-1 | unLock | UNSUPPORTED (flattened field semaphore) | PROVEN | correct | not judged | correct PROVEN |
| T-haiku-1 | sweep | UNSUPPORTED (helper call on existing) | FAILED (property) | defect | not judged | detected |
| T-haiku-3 | tryLock | UNSUPPORTED (flattened field semaphore) | FAILED (property) | defect | not judged | detected |
| T-haiku-3 | unLock | UNSUPPORTED (flattened field semaphore) | FAILED (property) | defect | not judged | detected |
| T-haiku-3 | sweep | UNSUPPORTED (safe call ?.) | FAILED (property) | defect | not judged | detected |
| T-haiku-4 | tryLock | UNSUPPORTED (flattened field semaphore; operation result; input token) | UNSUPPORTED (operation result; input token) | defect | not judged | not judged |
| T-sonnet-3 | tryLock | UNSUPPORTED (value of type java.util.concurrent.Semaphore; new Semaphore; tryAcquire on semaphore; flattened field semaphore) | FAILED (property) | defect | not judged | detected |
| T-sonnet-3 | unLock | UNSUPPORTED (flattened field semaphore) | FAILED (property) | defect | not judged | detected |
| T-opus-1 | tryLock | UNSUPPORTED (value of type kotlinx.coroutines.sync.Semaphore; new Semaphore; tryAcquire on semaphore; flattened field semaphore) | FAILED (property) | defect | not judged | detected |
| T-opus-1 | unLock | UNSUPPORTED (flattened field semaphore) | PROVEN | correct | not judged | correct PROVEN |
| T-opus-2 | tryLock | UNSUPPORTED (if/when used as a value) | UNSUPPORTED (read of Companion.MAX_VALUE) | defect | not judged | not judged |
| T-opus-4 | tryLock | UNSUPPORTED (local alias entry; if/when used as a value) | FAILED (property) | defect | not judged | detected |

base의 개수:

| agreement | base tryLock | base unLock | base sweep | base total |
|---|---|---|---|---|
| detected | 4 | 0 | 10 | 14 |
| correct PROVEN | 0 | 8 | 0 | 8 |
| not judged | 8 | 4 | 2 | 14 |

## 5. 예측 대조 (AI-CODE.md 14.2)

| 예측 | 대조 | 근거 |
|---|---|---|
| 1. 새로 판정되는 action은 정확히 11개다. Part B tryLock 3개는 남는다 | 틀림: 10개다 | 예측한 11개 중 T-opus-2 tryLock만 판정되지 않았다. 원인은 A2가 드러낸 `Long.MAX_VALUE`다 (2.2). Part B tryLock 3개는 예측대로 UNSUPPORTED다 (이유 "input token") |
| 2. T-haiku-3 unLock과 T-sonnet-3 unLock은 FAILED다 | 맞음 | T-haiku-3: "Postcondition of unLock might not hold: sidecar progress_owner". T-sonnet-3: "Postcondition of unLock might not hold: sidecar ends_only_if P3a". 둘 다 FAILED (property) |
| 3. 새 tryLock과 sweep은 모두 FAILED다. 새 unLock 중 T-haiku-1, T-opus-1은 PROVEN이다 | 맞음 | 새 tryLock 4개 (T-haiku-3, T-sonnet-3, T-opus-1, T-opus-4)와 새 sweep 2개 (T-haiku-1, T-haiku-3)가 FAILED (property), `ends_only_if P3a`다. T-haiku-1, T-opus-1 unLock은 PROVEN이다 |
| 4. 놓침 0, 오경보 0. 판정 33개, 판정 안 함 3개 | 일부 맞음 | 놓침 0, 오경보 0은 맞다. 판정은 32개, 판정 안 함은 4개다 (예측 1의 차이) |
| 5. gate 4개와 손으로 고른 변이 63회는 바뀌지 않는다 | 맞음 | `cleanTest test` 583개 통과 (GateTest 포함), `mutantsReqShield` 33회와 `mutantsAspect` 30회 불일치 0. 세부는 구현 보고서 |

## 6. 관찰

1. A3의 if 별칭은 뒤의 문장을 객체마다 한 번 내린다. 그래서 branch reachability (INFO)의 탐침도 복사본마다 생긴다. T-opus-4 보고서는 `tryAcquire fails`와 `tryAcquire succeeds`를 같은 위치 61:21에서 둘 다 unreachable로 적는다. 각 줄은 다른 복사본이다: 새 객체 복사본에서는 실패가, 기존 entry 복사본에서는 성공이 도달할 수 없다 (MI_INV: entry의 permit은 0). 판정에는 영향이 없다. 보고서는 복사본을 구별하지 않는다.
2. kotlinx `release()`의 증명 의무는 T-opus-1 unLock에서도 실행되었다 (PROVEN). 생성에서 `tryAcquire()`가 permit을 1에서 0으로 옮긴 결과다. 그 연산을 빠뜨리면 MI_INV(`permits == 0`)와 이 의무가 실패한다 (PreparedObjectTest).
