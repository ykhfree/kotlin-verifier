# round3g 판정 2 (judge 2, blind)

- 대상: `aicode/round3g/impl/<ID>/KeyLocalLock.kt`, ID = G-haiku-2, G-sonnet-3, G-opus-4, G-sonnet-1.
- 근거 1: `specs/reqshield/keylocallock.common.kv`의 속성 절(issue, ensures, ends_only_if, unbacked_only_if, invariant, lemma). hole MI_INV는 판정에 쓰지 않았다.
- 근거 2: 생성기가 받은 자연어 명세(`SPEC.md`, `KeyLock.kt` KDoc, `HELPERS.md`, `RULES.md`).
- 검증기 결과, 로그, 다른 판정은 보지 않았다.
- 보조 증거: `aicode/round3g/judge-work/2/`의 harness(4절).

## 1. 판정 표

| ID | 동작 | 판정 | 깨진 속성 | 한 줄 이유 |
|---|---|---|---|---|
| G-haiku-2 | tryLock | defect (sidecar 속성) | progress_live, P3a | 42행이 `now == expiresAt`인 보유 항목을 만료로 보고 takeover한다. |
| G-haiku-2 | unLock | correct | 없음 | 토큰이 맞을 때만 항목을 지우고, 다른 경우에는 상태를 바꾸지 않는다. |
| G-haiku-2 | sweep | defect (sidecar 속성) | P3a | 94행이 `expiresAt <= now`로 지워서 `now == gLease`에서 획득을 끝낸다. |
| G-sonnet-3 | tryLock | defect (sidecar 속성) | progress_live, P3a | 38행이 `now == expiresAt`인 보유 항목을 만료로 보고 제자리 takeover한다. |
| G-sonnet-3 | unLock | correct | 없음 | 토큰이 맞을 때만 항목을 지우고, 다른 경우에는 상태를 바꾸지 않는다. |
| G-sonnet-3 | sweep | defect (sidecar 속성) | P3a | 110행이 `expiresAt <= now`로 지워서 `now == gLease`에서 획득을 끝낸다. |
| G-opus-4 | tryLock | defect (sidecar 속성) | progress_live, P3a | 73행이 `now == expiresAt`인 보유 항목을 만료로 보고 새 항목으로 바꾼다. |
| G-opus-4 | unLock | correct | 없음 | 토큰이 맞을 때만 permit을 돌려주고 항목을 지운다. |
| G-opus-4 | sweep | defect (sidecar 속성) | P3a | 50행이 `expiresAt <= now`로 지워서 `now == gLease`에서 획득을 끝낸다. |
| G-sonnet-1 | tryLock | defect (sidecar 속성) | progress_live, P3a | 44행이 `now == expiresAt`인 보유 항목을 만료로 보고 제자리 takeover한다. |
| G-sonnet-1 | unLock | correct | 없음 | 토큰이 맞을 때만 permit을 돌려주고 항목을 지운다. |
| G-sonnet-1 | sweep | defect (sidecar 속성) | P3a | 106행이 `expiresAt <= now`로 지워서 `now == gLease`에서 획득을 끝낸다. |

### 모듈 판정

| ID | 모듈 판정 | 근거 |
|---|---|---|
| G-haiku-2 | defect-property | tryLock(progress_live, P3a)과 sweep(P3a)이 경계 시점에서 깨진다. |
| G-sonnet-3 | defect-property | 같은 결함이다. |
| G-opus-4 | defect-property | 같은 결함이다. |
| G-sonnet-1 | defect-property | 같은 결함이다. |

- 네 구현의 결함은 모두 한 가지 원인에서 나온다: 만료 경계를 `now >= expiresAt`로 잡았다.
- 이 경계 결함 말고는 sidecar 속성 위반을 찾지 못했다.
- 자연어 명세만 깨는 결함(defect-outside)도 찾지 못했다.

## 2. sidecar가 정한 만료 경계

- progress_live: `old(has(S, k)) && old(S.m[k].isHeld) && now <= old(S.m[k].expiresAt) ==> res == NULL` (common.kv 70행).
- expired_takeover: `old(has(S, k)) && now > old(S.m[k].expiresAt) ==> res != NULL && tok(res).gBacked` (72행).
- tryLock P3a: `ends_only_if now > old(tok(qt).gLease)` (65행).
- sweep P3a: `ends_only_if now > old(tok(qt).gLease)` (96행).
- 불변식 entry_lease(108-109행)에 따라 항목의 `expiresAt`은 그 항목 토큰의 `gLease`와 같다.
- 그래서 sidecar에서 항목은 `now <= expiresAt` 동안 살아 있다. 항목은 `now > expiresAt`일 때만 만료된다.
- `now == expiresAt`인 시점을 이 문서에서 "경계 시점"이라고 부른다.
- 네 구현은 경계 시점을 만료로 본다.

## 3. 시나리오

### S1. tryLock이 경계 시점에 takeover한다 (progress_live, P3a)

1. 한 KeyLocalLock을 `lockTimeoutMillis = 100`으로 만든다.
2. 스레드 A가 `now = 1000`에서 `tryLock(key, CREATE)`를 부른다.
3. A는 `local-1`을 받는다. 항목은 `token = local-1`, `expiresAt = 1100`, `isHeld = true`이다. issue 규칙에 따라 `gLease(local-1) = 1100`이다.
4. 스레드 B가 `now = 1100`에서 같은 키로 `tryLock`을 부른다.
5. progress_live의 전제가 참이다: 항목이 있고, `isHeld`가 참이고, `1100 <= 1100`이다. 그래서 B는 `null`을 받아야 한다.
6. 구현의 "살아 있음" 검사는 `now < expiresAt` 꼴이다. `1100 < 1100`은 거짓이다.
7. 그래서 구현은 takeover 가지로 가서 B에게 `local-2`를 준다. progress_live가 깨진다.
8. 같은 동작에서 항목은 더 이상 `local-1`을 싣지 않는다. witness 규칙에 따라 `local-1`의 획득(gHeld)이 끝난다.
9. tryLock P3a는 `1100 > gLease(local-1) = 1100`을 요구한다. 이 식은 거짓이다. P3a가 깨진다.
10. 관찰 결과: A가 뒤에 `unLock(key, CREATE, local-1)`을 부르면 `false`를 받는다.

| ID | 살아 있음 검사 | 경계 시점에 가는 가지 | 동작 |
|---|---|---|---|
| G-haiku-2 | 42행 `existing.isHeld.get() && existing.expiresAt > now` | 49-58행 `else` | 새 `LockInfo`로 항목을 바꾼다(52-57행). |
| G-sonnet-3 | 38행 `existing.isHeld.get() && existing.expiresAt > now` | 41-48행 `else` | 같은 객체에 `token`, `expiresAt`, `isHeld`를 다시 쓴다(45-47행). |
| G-opus-4 | 73행 `existing.isHeld.get() && now < existing.expiresAt` | 80-89행 `else` | 새 `LockInfo`로 항목을 바꾼다(83-88행). |
| G-sonnet-1 | 44행 `existing.isHeld.get() && now < existing.expiresAt` | 47-54행 `else` | 같은 객체에 `expiresAt`, `token`, `isHeld`를 다시 쓴다(49-51행). |

- 변형: `lockTimeoutMillis = 0`이면 `expiresAt = now`이다. 이 경우 같은 밀리초 안의 두 tryLock이 둘 다 토큰을 받는다. sidecar는 두 번째 호출에 `null`을 요구한다.
- 실제 시계는 밀리초 단위이다. 그래서 경계 시점은 실제 실행에서도 생긴다. 예: 기본 timeout 3000 ms에서 정확히 3000 ms 뒤의 요청.

### S2. sweep이 경계 시점에 항목을 지운다 (sweep P3a)

1. `lockTimeoutMillis = 100`으로 `now = 2000`에서 `tryLock`을 부른다.
2. 호출자는 `local-n`을 받는다. 항목은 `expiresAt = 2100`이고 `gLease(local-n) = 2100`이다.
3. 소유자는 아직 `unLock`을 부르지 않는다.
4. 모니터가 이 키에 대해 `now = System.currentTimeMillis() = 2100`을 읽는다.
5. compute 람다가 `existing.expiresAt <= now`, 곧 `2100 <= 2100`을 참으로 보고 `null`을 돌려준다. 항목이 지워진다.
6. 항목이 `local-n`을 더 이상 싣지 않으므로 `local-n`의 획득이 끝난다.
7. sweep P3a는 `2100 > gLease(local-n) = 2100`을 요구한다. 이 식은 거짓이다. P3a가 깨진다.
8. 관찰 결과: 소유자가 뒤에 `unLock(local-n)`을 부르면 `false`를 받는다. sidecar에서 이 소유자는 아직 lease 안에 있다.

| ID | 모니터 위치 | `now` 읽기 | 지우는 조건 |
|---|---|---|---|
| G-haiku-2 | 86-103행 (companion `init`의 데몬 스레드) | 92행 | 94행 `existing != null && existing.expiresAt <= now` |
| G-sonnet-3 | 105-116행 `removeExpiredEntries()` (94-101행에서 1초 간격 예약) | 108행 | 110행 같은 식 |
| G-opus-4 | 42-55행 (companion `init`의 코루틴) | 48행 | 50행 같은 식 |
| G-sonnet-1 | 102-109행 `removeExpired()` (89-100행의 데몬 스레드) | 104행 | 106행 같은 식 |

## 4. harness 증거

- 위치: `aicode/round3g/judge-work/2/` (`run.sh`, `Harness.kt`, `stubs/`, `<ID>/KeyLocalLock.kt`).
- stub이 `nowToEpochTime()`과 `LocalLockLimit.rejectsNewEntry()`를 대신한다. 그래서 harness가 tryLock의 시계와 상한 답을 정한다.
- 각 `<ID>/KeyLocalLock.kt`는 원본에서 한 줄만 바꾼 사본이다. 모니터의 `System.currentTimeMillis()`를 `FakeClock.sweepNow()`로 바꿨다. `run.sh`가 이 diff를 출력한다.
- 네 구현 모두 같은 결과를 냈다.

| 단계 | 내용 | 네 구현의 결과 | sidecar 요구 |
|---|---|---|---|
| S1 | `now=1000` 획득 뒤 `now=1100`에서 tryLock | `local-2` (토큰) | `null` (progress_live) |
| S1 | 그 뒤 `unLock(local-1)` | `false` | P3a가 `local-1`의 종료를 금지한다 |
| 대조 | `now=1099`에서 tryLock | `null` | `null` |
| 대조 | `now=1101`에서 tryLock | 토큰 | 토큰 (expired_takeover) |
| S3 | 틀린 토큰, 맞는 토큰, 같은 토큰 다시 | `false`, `true`, `false`, 항목 없음 | P1, progress_owner와 일치 |
| S4 | 항목이 없는 키에 unLock | `false`, 항목 없음 | no_entry_created와 일치 |
| S5 | 상한 거부(`rej = true`) 두 번 | 토큰 두 개, 항목 없음, `unLock` `false` | cap_unbacked, P3b와 일치 |
| S6 | 모니터 `now=2100`, lease 2099 | 지움 | 지워도 된다 |
| S6 | 모니터 `now=2100`, lease 2100 | 지움 | 지우면 안 된다 (P3a) |
| S6 | 모니터 `now=2100`, lease 2101 | 남김 | 남겨야 한다 |

- 보유 중 permit 수: G-haiku-2와 G-sonnet-3은 1, G-opus-4와 G-sonnet-1은 0이었다. 둘 다 P2의 범위 [0, 1] 안이다.
- harness는 시나리오를 보여 줄 뿐이다. 4절의 결과는 5절의 논증을 대신하지 않는다.

## 5. 성립하는 속성의 논증

### 5.1 네 구현에 공통인 사실

- 항목은 tryLock의 compute 안에서만 생기거나 바뀐다.
- 맵 안의 항목은 언제나 `isHeld = true`이고 `token != null`이다.
  - G-haiku-2: 52-57행만 `LockInfo`를 만든다. 어떤 코드도 그 필드를 다시 쓰지 않는다.
  - G-sonnet-3: 36행이 만들고 45-47행이 참과 새 토큰을 다시 쓴다. 66-67행은 거짓과 `null`을 쓰지만, 그 객체는 69행의 `null` 반환으로 맵에서 빠진다.
  - G-opus-4: 83-86행이 만든다. 103-104행은 거짓과 `null`을 쓰지만, 그 객체는 108행에서 맵에서 빠진다.
  - G-sonnet-1: 37-40행이 만들고 49-51행이 다시 쓴다. 70-71행은 거짓과 `null`을 쓰지만, 그 객체는 74행에서 맵에서 빠진다.
- 키 k 항목의 토큰은 키 k에 대한 tryLock이 같은 compute 안에서 발급하고 실은 토큰이다. 그래서 그 토큰은 issued이고, `gKey = k`이고, backed이다.
- 토큰 번호는 `tokenSequence.incrementAndGet()`에서 나온다. 두 호출이 같은 번호를 받지 않는다.
- ConcurrentHashMap.compute는 키마다 원자적이고, 람다를 호출마다 정확히 한 번 실행한다. 람다 안의 `lockMap.size` 읽기는 맵을 바꾸지 않는다.

### 5.2 unLock: correct (네 구현)

- 경우 A: 이전 항목이 있고 그 토큰이 `t`이다. 람다가 `null`을 돌려주어 항목을 지우고 `res = true`이다.
  - P1: 5.1에 따라 `old(gIssued(t))`, `old(gKey(t)) == k`, `old(gBacked(t))`가 참이다. witness에 따라 `old(gHeld(t))`도 참이다. 항목이 지워지므로 `!has(S, k)`이고 `!gHeld(t)`이다.
  - P3a: 끝나는 획득은 `t` 하나이고 `res`는 참이다.
  - progress_owner: G-sonnet-3, G-opus-4, G-sonnet-1은 `isHeld`도 검사한다. 맵 안의 항목은 언제나 `isHeld = true`이므로 이 검사는 결과를 바꾸지 않는다.
- 경우 B: 항목이 없거나 토큰이 다르다. 람다가 `existing`을 그대로 돌려준다. 상태가 바뀌지 않고 `res = false`이다.
  - P1은 전제가 거짓이다. P3a: 아무 획득도 끝나지 않는다. progress_owner는 전제가 거짓이다.
  - no_entry_created: 항목이 없으면 람다가 `null`을 돌려주므로 항목이 생기지 않는다.
- 행 번호: G-haiku-2 70-78행, G-sonnet-3 63-73행, G-opus-4 101-112행, G-sonnet-1 68-78행.

### 5.3 tryLock: 경계 결함 말고는 성립한다

- P1a: 번호가 새것이므로 `!old(gIssued(res))`이다. `gKey`, `gLease`는 issue 규칙이 정한다. 발급 때 `gHeld`는 참이다.
- P1a_entry: 구현은 backed 토큰을 같은 compute에서 키 k 항목에 쓴다. 그래서 `S.m[k].token == res`이다.
- P3b, cap_unbacked: unbacked 토큰은 "항목 없음 그리고 `rejectsNewEntry`가 참"인 가지에서만 나온다. 그 가지는 `null`을 돌려주므로 항목이 생기지 않는다. trusted 계약에 따라 `rej ==> limit != 0`이다.
  - G-haiku-2 44-47행, G-sonnet-3 30-32행, G-opus-4 76-79행, G-sonnet-1 32-35행.
- progress_free: 항목이 없고 `rej`가 거짓이면 네 구현 모두 새 항목에 `res`를 싣는다.
- `rej`가 쓰이는 세 속성(P3b, progress_free, cap_unbacked)은 모두 `!old(has(S, k))`를 전제로 한다. 네 구현은 항목이 없을 때만 `rejectsNewEntry`를 부른다.
- expired_takeover: `now > expiresAt`이면 살아 있음 검사가 거짓이다. 그래서 takeover 가지가 backed 토큰을 준다.
- refusal_keeps_entry: `res == null`은 살아 있음 가지에서만 나온다. 그 가지는 `existing`을 바꾸지 않고 돌려준다. G-sonnet-1은 `acquired`와 `unbacked`가 둘 다 거짓일 때만 `null`을 돌려준다(57행).
- P3a: `now > expiresAt`인 takeover는 P3a를 지킨다. 경계 시점의 takeover만 P3a를 깬다(S1).

### 5.4 sweep: no_entry_created는 성립한다

- 항목이 없으면 람다가 `existing`, 곧 `null`을 돌려준다. 그래서 항목이 생기지 않는다.
- `now > expiresAt`인 삭제는 P3a를 지킨다. 경계 시점의 삭제만 P3a를 깬다(S2).

### 5.5 불변식과 lemma (네 구현 모두 성립)

- P2 (permit이 0 이상 1 이하):
  - G-haiku-2: `java.util.concurrent.Semaphore(1)`(53행)를 한 번도 쓰지 않는다. permit은 언제나 1이다.
  - G-sonnet-3: `kotlinx.coroutines.sync.Semaphore(1)`(36행)를 한 번도 쓰지 않는다. permit은 언제나 1이다.
  - G-opus-4: 새 kotlinx Semaphore에 `tryAcquire()`를 하므로 0이 된다(83-84행). unLock이 `release()`를 한 번 하고(105행) 같은 compute에서 객체를 맵에서 뺀다. 그래서 한 객체는 많아야 한 번 release되고 1을 넘지 않는다. takeover는 새 객체를 만들므로 옛 객체는 0으로 남는다.
  - G-sonnet-1: 새 jdk Semaphore에 `tryAcquire()`를 하므로 0이 된다(37-38행). 제자리 takeover는 permit을 건드리지 않는다. unLock이 `release()`를 한 번 하고(72행) 객체를 맵에서 뺀다(74행).
- entry_lease: 구현은 토큰과 `expiresAt = now + lockTimeoutMillis`를 같은 compute에서 쓴다. issue 규칙의 `gLease`도 `now + timeout`이다.
- entry_held: 맵 안의 항목은 `isHeld = true`이다. 그 토큰은 backed이고 항목에 실려 있으므로 witness에 따라 `gHeld = true`이다.
- P3c: 키마다 항목은 많아야 하나이고, 항목은 토큰을 하나만 싣는다. witness에 따라 backed 토큰은 실려 있는 동안만 held이다. 그래서 한 키에 backed이고 held인 토큰은 많아야 하나이다.

## 6. 모델 밖이거나 결함으로 세지 않은 관찰

- Long overflow (네 구현): `now + lockTimeoutMillis`가 넘치면 `expiresAt`이 음수가 된다. 그러면 다음 tryLock이 바로 takeover하고 모니터가 항목을 지운다. 모델은 Long을 무한 정수로 보므로 이것을 sidecar 결함으로 세지 않았다. 네 구현 모두 overflow를 막지 않는다.
- 자연어 명세와 sidecar의 차이: SPEC.md는 "dropped by the cleanup monitor once `lockTimeoutMillis` has passed"라고만 쓴다. 이 문장은 경계 시점을 정하지 않는다. "has passed"를 `>=`로 읽을 수도 있다. 네 구현은 모두 `>=`로 읽었다. sidecar는 `>`를 요구한다. 생성기는 자연어만으로 이 경계를 알 수 없었다.
- G-sonnet-3과 G-opus-4는 `kotlinx.coroutines.sync.Semaphore`를 쓴다. sidecar는 `use jdk.Semaphore`를 선언한다. 의미로는 P2가 성립한다(5.5). 검증기 모델이 kotlinx Semaphore를 다루는지는 이 판정에서 확인하지 않았다.
- G-sonnet-1:
  - `LockInfo`를 파일 최상위 private class로 둔다(12-17행). sidecar의 바인딩은 `KeyLocalLock.LockInfo`이다. 동작에는 영향이 없다.
  - 토큰 번호를 compute 전에 받는다(26행). 거절된 호출도 번호를 하나 쓴다. 어떤 속성도 번호의 순서나 연속을 요구하지 않는다. P1a는 성립한다.
- 모니터의 견고성: G-haiku-2, G-sonnet-1의 `while (true)` 스레드와 G-opus-4의 코루틴은 예외가 새면 멈춘다. G-sonnet-3은 예외를 잡는다(106-115행, 람다 밖이라 RULES 6에 맞다). 루프 안에 예외를 낼 코드가 없으므로 결함으로 세지 않았다.

## 7. 정하지 못한 것

- 동작별 판정은 모두 정했다. undecided는 없다.
- 남는 질문 하나: 팀이 경계 시점을 `>=`로 받아들인다면 sidecar(progress_live, expired_takeover, 두 P3a)를 고쳐야 한다. 그 경우 이 판정의 결함 근거는 사라지고, 네 구현은 내 논증에서 결함이 없다. 이 결정은 판정자의 몫이 아니다.
