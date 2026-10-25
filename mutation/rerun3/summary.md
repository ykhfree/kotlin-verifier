# 기계적 변이 측정 결과

MUTATION.md 8절 형식이다. 판정자가 정할 범주는 "판정 대기"로 둔다.

- 측정: 2026-10-25, wall clock 1387 s, 병렬 3.
- 원본 판정: keylocallock.coroutine PROVEN, keylocallock.core PROVEN, keylocallock.reactor PROVEN.
- 변이 264개, 사산 24개, 모델이 있는 변이 204개. Silicon 실행 195회 (원본 3회, 새 해시 192회). 중복 제거로 아낀 Silicon 실행: 12회. 그 가운데 성질 확인(pass 2)을 더 돌린 실행: 32회.
- 중복 제거 검사: 판정을 함께 쓴 변이 12개를 각자 `kv lower`로 다시 낮췄다 (Silicon 없음). 완결성이 함께 쓴 판정과 어긋나는 변이: 0개.

## 1. 모듈별·규칙별 변이 수, 사산 수, 판정 분포

| 모듈 | 규칙 | 변이 | 사산 | PROVEN (모델 동일) | PROVEN (모델 바뀜) | FAILED | ERROR | INCOMPLETE |
|---|---|---|---|---|---|---|---|---|
| keylocallock.coroutine | ROR | 10 | 3 | 0 | 0 | 3 | 4 | 0 |
| keylocallock.coroutine | COR | 4 | 0 | 0 | 0 | 2 | 2 | 0 |
| keylocallock.coroutine | NEG | 12 | 3 | 1 | 1 | 5 | 2 | 0 |
| keylocallock.coroutine | BOOL | 12 | 0 | 0 | 4 | 6 | 2 | 0 |
| keylocallock.coroutine | SDL | 18 | 0 | 1 | 4 | 10 | 1 | 2 |
| keylocallock.coroutine | RET | 12 | 0 | 0 | 3 | 8 | 0 | 1 |
| keylocallock.coroutine | AOR | 4 | 0 | 0 | 0 | 4 | 0 | 0 |
| keylocallock.coroutine | MOP | 7 | 0 | 0 | 1 | 5 | 1 | 0 |
| keylocallock.coroutine | RACY | 10 | 2 | 1 | 2 | 5 | 0 | 0 |
| keylocallock.core | ROR | 10 | 3 | 0 | 0 | 3 | 4 | 0 |
| keylocallock.core | COR | 4 | 0 | 0 | 0 | 2 | 2 | 0 |
| keylocallock.core | NEG | 12 | 3 | 1 | 1 | 5 | 2 | 0 |
| keylocallock.core | BOOL | 12 | 0 | 0 | 4 | 6 | 2 | 0 |
| keylocallock.core | SDL | 19 | 0 | 1 | 4 | 11 | 1 | 2 |
| keylocallock.core | RET | 12 | 0 | 0 | 3 | 8 | 0 | 1 |
| keylocallock.core | AOR | 4 | 0 | 0 | 0 | 4 | 0 | 0 |
| keylocallock.core | MOP | 7 | 0 | 0 | 1 | 5 | 1 | 0 |
| keylocallock.core | RACY | 10 | 2 | 1 | 2 | 5 | 0 | 0 |
| keylocallock.reactor | ROR | 10 | 3 | 0 | 0 | 3 | 4 | 0 |
| keylocallock.reactor | COR | 4 | 0 | 0 | 0 | 2 | 2 | 0 |
| keylocallock.reactor | NEG | 11 | 3 | 0 | 1 | 5 | 2 | 0 |
| keylocallock.reactor | BOOL | 12 | 0 | 0 | 4 | 6 | 2 | 0 |
| keylocallock.reactor | SDL | 19 | 0 | 1 | 4 | 11 | 1 | 2 |
| keylocallock.reactor | RET | 9 | 0 | 0 | 3 | 6 | 0 | 0 |
| keylocallock.reactor | AOR | 4 | 0 | 0 | 0 | 4 | 0 | 0 |
| keylocallock.reactor | MOP | 7 | 0 | 0 | 1 | 5 | 1 | 0 |
| keylocallock.reactor | RACY | 9 | 2 | 0 | 2 | 5 | 0 | 0 |

규칙별 사산 수: ROR 9, COR 0, NEG 9, BOOL 0, SDL 0, RET 0, AOR 0, MOP 0, RACY 6.

## 2. 변이 점수

점수 = 검출 / (검출 + 놓침). 놓침은 판정자가 정하므로 점수는 판정 대기다. 아래는 기계적으로 정한 검출 수다 (판정자의 FAILED 표본 결과에 따라 줄 수 있다).

| 모듈 | 검출 | 놓침 | 점수 |
|---|---|---|---|
| keylocallock.coroutine | 43 | 판정 대기 | 판정 대기 |
| keylocallock.core | 44 | 판정 대기 | 판정 대기 |
| keylocallock.reactor | 42 | 판정 대기 | 판정 대기 |

| 규칙 | 검출 | 놓침 | 점수 |
|---|---|---|---|
| ROR | 9 | 판정 대기 | 판정 대기 |
| COR | 6 | 판정 대기 | 판정 대기 |
| NEG | 15 | 판정 대기 | 판정 대기 |
| BOOL | 18 | 판정 대기 | 판정 대기 |
| SDL | 32 | 판정 대기 | 판정 대기 |
| RET | 22 | 판정 대기 | 판정 대기 |
| AOR | 12 | 판정 대기 | 판정 대기 |
| MOP | 15 | 판정 대기 | 판정 대기 |
| RACY | 0 | 판정 대기 | 판정 대기 |

## 3. 생존 변이

- 규칙으로 추상화됨 (PROVEN, 모델 동일): 7개. ledger 종류별: not-modeled 6, translated 1.
- 동등 / 놓침 / 미결: 판정 대기 ("PROVEN, 모델 바뀜" 45개).

| 변이 | ledger 종류 | 위치 | 바꾼 텍스트 |
|---|---|---|---|
| keylocallock.core/NEG-07 | not-modeled | KeyLocalLock.kt:243:13-243:28 | `!released.get()` → `!(!released.get())` |
| keylocallock.core/RACY-07 | not-modeled | KeyLocalLock.kt:243:13-243:28 | `!released.get()` → `racy_7 && !released.get()` / `` → `val racy_7 = lockMap.containsKey(completeKey)⏎        ` |
| keylocallock.core/SDL-16 | not-modeled | KeyLocalLock.kt:244:13-244:114 | `log.debug("Attempted to unlock key '{}' that is not held or ` → `` |
| keylocallock.coroutine/NEG-07 | not-modeled | KeyLocalLock.kt:224:13-224:28 | `!released.get()` → `!(!released.get())` |
| keylocallock.coroutine/RACY-07 | not-modeled | KeyLocalLock.kt:224:13-224:28 | `!released.get()` → `racy_7 && !released.get()` / `` → `val racy_7 = lockMap.containsKey(completeKey)⏎        ` |
| keylocallock.coroutine/SDL-15 | not-modeled | KeyLocalLock.kt:225:13-225:105 | `log.debug("Attempted to unlock key '{}' that is not held by ` → `` |
| keylocallock.reactor/SDL-16 | translated | KeyLocalLock.kt:239:17-239:105 | `log.debug("Attempted to unlock key '{}' without holding its ` → `` |

## 4. FAILED

- 검출: 129개 (예측 7.1: 6개, 그 밖: 123개; 그 밖 중 10개는 판정자 표본).
- 예측된 오경보 / 설명 안 된 오경보: 판정 대기 (예측 7.2-7.4에 맞는 FAILED 15개, 무작위 표본 10개).
- FAILED 합계: 144개.
- FAILED action의 하위 상태 (변이×action): invariant only 21, property 123.

## 5. ERROR와 INCOMPLETE

- 서명이 막음 (stale-review): 36개.
- 도구 결함 후보: 0개.
- INCOMPLETE: 8개.
  - keylocallock.coroutine/SDL-01: probe; operation tryLock (keylocallock.common.kv:55:25): fun KeyLocalLock.tryLock has 0 action calls []; an operation has exactly one (DESIGN-v0.md 8.3 U2) KeyLocalLock.kt:145:5; tokenSequence operation outside an action: tokenSequence.incrementAndGet() 80:52
  - keylocallock.coroutine/RET-04: value of type kotlin.Nothing in action tryLock#155 at KeyLocalLock.kt:195:16: the type is not part of the model
  - keylocallock.coroutine/SDL-12: probe; operation unLock (keylocallock.common.kv:78:24): fun KeyLocalLock.unLock has 0 action calls []; an operation has exactly one (DESIGN-v0.md 8.3 U2) KeyLocalLock.kt:198:5
  - keylocallock.core/SDL-01: probe; operation tryLock (keylocallock.common.kv:55:25): fun KeyLocalLock.tryLock has 0 action calls []; an operation has exactly one (DESIGN-v0.md 8.3 U2) KeyLocalLock.kt:163:5; tokenCounter operation outside an action: tokenCounter.incrementAndGet() 76:52
  - keylocallock.core/RET-04: value of type kotlin.Nothing in action tryLock#173 at KeyLocalLock.kt:214:16: the type is not part of the model
  - keylocallock.core/SDL-13: probe; operation unLock (keylocallock.common.kv:78:24): fun KeyLocalLock.unLock has 0 action calls []; an operation has exactly one (DESIGN-v0.md 8.3 U2) KeyLocalLock.kt:217:5
  - keylocallock.reactor/SDL-01: probe; operation tryLock (keylocallock.common.kv:55:25): fun KeyLocalLock.tryLock has 0 action calls []; an operation has exactly one (DESIGN-v0.md 8.3 U2) KeyLocalLock.kt:158:5; tokenSequence operation outside an action: tokenSequence.incrementAndGet() 85:52
  - keylocallock.reactor/SDL-13: probe; operation unLock (keylocallock.common.kv:78:24): fun KeyLocalLock.unLock has 0 action calls []; an operation has exactly one (DESIGN-v0.md 8.3 U2) KeyLocalLock.kt:215:5

## 6. 7절 예측과 실제

| 예측 | 규칙으로 고른 변이 | 판정 분포 | 대조 |
|---|---|---|---|
| 7.1 | ROR `>`→`>=`, 피연산자가 `expiresAt` | FAILED 6 | 모두 FAILED (예측과 같음) |
| 7.2 | RACY, `set-once` 없는 map | FAILED 15, PROVEN 8, 사산 6 | FAILED 15개는 판정 대기 |
| 7.3 | 모델의 `exc := true`가 원본보다 많음 | 없음 | 생기지 않음 (예측과 같음) |
| 7.4 | 같은 Kotlin 식의 자유 값이 둘 이상 | 없음 | 생기지 않음 (예측과 같음) |
| 7.5 | trusted fun 본문의 변이 | ERROR 36 | 컴파일되는 것 모두 stale-review (예측과 같음) |
| 7.6 | SDL, `log.*` 호출 | PROVEN 3 | 모두 PROVEN, 모델 동일 (예측과 같음) |
| 7.7 | SDL, 바로 지워지는 entry의 `release()`나 `token = null` | PROVEN 9 | PROVEN 모델 바뀜 9개는 판정 대기; 그 밖 0개 |

## 판정자 묶음

`judge/index.md`: 55개.
- "PROVEN, 모델 바뀜" 45개가 상한 30을 넘는다: ROR 경계와 RET 9개를 모두 넣고, 나머지 36개에서 무작위 21개를 뽑았다 (seed 20261025). 이 범주의 판정은 표본이다.
- 그 밖의 FAILED 129개에서 무작위 10개 (seed 20261024).
