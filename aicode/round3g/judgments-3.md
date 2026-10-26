# 판정 3 — round3g KeyLocalLock (블라인드 판정)

- 판정자는 judge 3이다. 판정자는 검증기 결과를 보지 않았다.
- 판정 근거는 두 가지다.
  - sidecar 속성: `specs/reqshield/keylocallock.common.kv`의 issue 규칙, ensures, ends_only_if, unbacked_only_if,
    invariant, lemma. 바인딩은 `keylocallock.coroutine.kv`가 정한다.
  - 자연어 명세: 생성기가 받은 `SPEC.md`, `KeyLock.kt`(KDoc), `HELPERS.md`, `RULES.md`.
- `hole MI_INV`는 판정에 쓰지 않았다. 그 hole은 다른 구현을 설명한다.
- 대상 파일은 `aicode/round3g/impl/<ID>/KeyLocalLock.kt`이다. ID는 G-haiku-3, G-sonnet-4, G-opus-1, G-opus-2이다.
- 아래에서 "common.kv N행"은 `keylocallock.common.kv`의 행 번호이다. 다른 행 번호는 해당 구현 파일의 행 번호이다.

## 1. 판정표

| ID | 동작 | 판정 | 깨진 속성 | 한 줄 이유 |
|---|---|---|---|---|
| G-haiku-3 | tryLock | defect (sidecar) | progress_live, P3a (tryLock) | 거절 조건이 `now < expiresAt`(47행)뿐이다. 그래서 `now == expiresAt`에서 살아 있는 항목을 새 LockInfo로 바꾼다(54-62행). |
| G-haiku-3 | unLock | defect (sidecar) | progress_owner | 항목이 호출자의 토큰을 가져도 `now >= expiresAt`이면 false를 반환한다(74행, 78행). |
| G-haiku-3 | sweep | defect (sidecar) | P3a (sweep) | `now >= existing.expiresAt`이면 항목을 지운다(112행). |
| G-sonnet-4 | tryLock | defect (sidecar) | progress_live, P3a (tryLock) | `existing.expiresAt <= now`이면 같은 LockInfo의 token과 expiresAt을 덮어쓴다(47-53행). |
| G-sonnet-4 | unLock | correct | 없음 | 맵 안 항목은 항상 held이다. 그래서 토큰이 맞으면 항상 해제하고 항목을 지운다(71-77행). |
| G-sonnet-4 | sweep | defect (sidecar) | P3a (sweep) | `existing.expiresAt <= now`이면 항목을 지운다(111행). |
| G-opus-1 | tryLock | defect (sidecar) | progress_live, P3a (tryLock) | 거절 조건이 `isHeld && now < expiresAt`(49행)이다. 그래서 `now == expiresAt`에서 새 LockInfo로 인수한다(58-66행). |
| G-opus-1 | unLock | correct | 없음 | 토큰이 맞으면 항상 해제하고 항목을 지운다(82-86행). |
| G-opus-1 | sweep | defect (sidecar) | P3a (sweep) | `now >= existing.expiresAt`이면 항목을 지운다(122행). |
| G-opus-2 | tryLock | defect (sidecar) | progress_live, P3a (tryLock) | `existing.expiresAt <= now`이면 새 항목으로 인수한다(56행). |
| G-opus-2 | unLock | correct | 없음 | 토큰이 맞으면 항상 해제하고 항목을 지운다(81-87행). |
| G-opus-2 | sweep | defect (sidecar) | P3a (sweep) | `existing.expiresAt <= now`이면 항목을 지운다(138행). |

모듈 판정은 네 구현 모두 `defect-property`이다.

자연어 명세만 깨고 sidecar 속성은 깨지 않는 결함(defect outside)은 찾지 못했다. 근거는 4.8절에 있다.

## 2. 경계에 대한 sidecar의 규정

네 구현의 공통 결함은 `now == expiresAt`인 순간의 처리이다. sidecar는 이 순간을 다음과 같이 정한다.

- `progress_live`(common.kv 70행)는 항목이 있고 isHeld이고 `now <= expiresAt`이면 tryLock이 NULL을 반환하도록 요구한다.
- `expired_takeover`(common.kv 72행)는 `now > expiresAt`일 때만 인수를 요구한다.
- tryLock의 `P3a`(common.kv 65행)는 `now > old(tok(qt).gLease)`일 때만 획득 qt가 끝나도록 허용한다.
- sweep의 `P3a`(common.kv 96행)도 같은 조건을 요구한다.
- `entry_lease`(common.kv 108-109행)는 항목의 `expiresAt`이 그 토큰의 `gLease`와 같다고 정한다.
- `witness LockInfo.token`(common.kv 52행)은 "a backed token is held while its key's entry carries it"이라고 정한다.
  그러므로 항목이 토큰을 잃으면 그 획득이 끝난다. 항목의 교체, 토큰 덮어쓰기, 항목 삭제가 모두 여기에 해당한다.

결론: sidecar에서 `now == expiresAt`인 락은 아직 살아 있다. 네 구현은 모두 이 순간을 만료로 취급한다.

## 3. 시나리오

### 3.1 tryLock: 임대 경계에서의 인수 (네 구현 공통)

설정: `lockTimeoutMillis = 100`이다. 시각은 `nowToEpochTime()`이 반환하는 밀리초 값이다.

1. 스레드 A가 T=1000에 `tryLock(k, CREATE)`를 호출한다.
2. A는 `local-1`을 받는다. 항목은 `token=local-1, expiresAt=1100, isHeld=true`이다. `gLease(local-1)`은 1100이다.
3. 스레드 B가 T=1100에 `tryLock(k, CREATE)`를 호출한다. B가 읽은 `now`는 1100이고 `expiresAt`과 같다.
4. 구현은 B에게 새 토큰 `local-2`를 반환한다. 항목은 이제 `local-2`를 가진다.
5. A가 같은 시각에 `unLock(k, CREATE, local-1)`을 호출한다. 결과는 false이다.

깨진 속성:

- `progress_live`: `old(has(S,k))`, `old(S.m[k].isHeld)`, `1100 <= 1100`이 모두 참이다. 그런데 `res != NULL`이다.
- `P3a` (tryLock의 ends_only_if): 4단계에서 local-1의 획득이 끝났다. 그런데 `now(1100) > gLease(1100)`은 거짓이다.
- 결과적으로 A는 임대 안에서 락을 잃는다. 두 holder가 같은 키에서 겹쳐 실행된다.

구현별 원인 행:

- G-haiku-3: 47행의 거절 조건은 `existing != null && now < existing.expiresAt`이다. `now == expiresAt`이면 이 조건이 거짓이다.
  `existing != null`이므로 49행 cap 가지도 거짓이다. 54-62행 else 가지가 새 LockInfo를 만든다.
- G-sonnet-4: 47행의 조건은 `existing.expiresAt <= now`이다. 48-53행이 같은 LockInfo의 token과 expiresAt을 덮어쓴다.
- G-opus-1: 49행의 거절 조건은 `existing.isHeld.get() && now < existing.expiresAt`이다. `now == expiresAt`이면 58-66행 else 가지가 새 LockInfo를 만든다.
- G-opus-2: 56행의 조건은 `existing.expiresAt <= now`이다. `newHeldEntry`(97-108행)가 새 LockInfo를 만든다.

하네스 증거(S1): 고정 시계로 T, T+99, T+100에 tryLock을 호출했다.

- 네 구현 모두 T+99에서 null을 반환했다.
- 네 구현 모두 T+100에서 새 토큰을 반환했다. 항목은 새 토큰을 가졌다.
- T+100 뒤 원래 토큰의 unLock은 네 구현 모두 false였다.
- 대조군: T+101에서는 네 구현 모두 백킹된 새 토큰을 반환했다. `expired_takeover`는 성립한다.

### 3.2 sweep: 임대 경계에서의 삭제 (네 구현 공통)

설정: `lockTimeoutMillis = 100`이다.

1. 스레드 A가 T=1000에 `tryLock(k, CREATE)`를 호출한다. A는 `local-1`을 받는다. `expiresAt`과 `gLease`는 1100이다.
2. A는 아직 unLock을 호출하지 않았다.
3. 모니터 패스가 키 k에 대해 `val now = System.currentTimeMillis()`로 1100을 읽는다.
4. 패스의 compute가 항목을 지운다.

깨진 속성:

- `P3a` (sweep의 ends_only_if): 4단계에서 local-1의 획득이 끝났다. 그런데 `now(1100) > gLease(1100)`은 거짓이다.
- 이 직후 A의 `unLock(k, CREATE, local-1)`은 false를 반환한다. 다른 스레드는 tryLock으로 새 항목을 만들 수 있다.

구현별 원인 행:

- G-haiku-3: 112행 `if (existing != null && now >= existing.expiresAt) null else existing`.
- G-sonnet-4: 111행 `if (existing != null && existing.expiresAt <= now) null else existing`.
- G-opus-1: 122행 `if (existing != null && now >= existing.expiresAt) null else existing`.
- G-opus-2: 138행 `if (existing != null && existing.expiresAt <= now) null else existing`.

하네스 증거(S3): 실제 시계에서 패스 직전과 직후의 시계 값이 모두 r이었다.

- `expiresAt = r`인 항목은 네 구현 모두에서 사라졌다.
- `expiresAt = r+1`인 대조 항목은 네 구현 모두에서 남았다.
- 하네스는 시간을 줄이려고 tryLock의 시계를 r-100으로 고정했다. 실제 운용에서는 T=r-100의 tryLock과 정확히 r에 도는 모니터 패스로 같은 상태가 생긴다.

### 3.3 G-haiku-3 unLock: 만료된 소유자의 해제 거절

설정: `lockTimeoutMillis = 100`이다.

1. 스레드 A가 T=1000에 `tryLock(k, CREATE)`를 호출한다. A는 `local-1`을 받는다. `expiresAt`은 1100이다.
2. A가 T=1100에 `unLock(k, CREATE, local-1)`을 호출한다. 다음 모니터 패스와 다른 tryLock은 아직 없다.
3. 74행이 `now = 1100`을 읽는다.
4. 78행 조건 `existing == null || existing.token != token || now >= existing.expiresAt`이 참이다. 마지막 항이 참이기 때문이다.
5. compute는 항목을 그대로 둔다. unLock은 false를 반환한다.

깨진 속성:

- `progress_owner`(common.kv 87행): `old(has(S,k))`와 `old(S.m[k].token) == local-1`이 참이다. 그런데 `res`가 false이다.
- T=1100은 sidecar에서 아직 임대 안이다. T=1150처럼 임대가 지난 시각에도 같은 결과가 나온다.
  항목이 local-1을 가지는 한 progress_owner는 true를 요구한다.
- 결과적으로 해제를 요청한 소유자의 항목이 맵에 남는다. 항목은 다음 모니터 패스(약 1 s 주기) 또는 다음 tryLock의 인수까지 남는다.
- 같은 호출의 P1, P3a, no_entry_created는 성립한다. false 경로는 맵을 바꾸지 않기 때문이다.

하네스 증거(S2): 고정 시계로 tryLock을 T에 호출하고 unLock 시각만 바꿨다.

| unLock 시각 | G-haiku-3 | G-sonnet-4 | G-opus-1 | G-opus-2 |
|---|---|---|---|---|
| T+99 | true | true | true | true |
| T+100 | **false** (항목 남음) | true | true | true |
| T+5000 | **false** (항목 남음) | true | true | true |

## 4. 속성별 논증

### 4.1 도달 가능한 상태의 공통 사실

각 구현에서 다음 사실을 따로 확인했다.

- F1: LockInfo 필드는 그 키의 `lockMap.compute` 람다 안에서만 읽고 쓴다. ConcurrentHashMap은 같은 키의 compute를 직렬화한다.
  그러므로 각 동작은 compute 한 번에서 원자적으로 일어난다.
- F2: 맵 안의 모든 LockInfo는 `isHeld == true`이고 `token != null`이다.
  - 항목을 맵에 넣는 곳은 tryLock뿐이다. tryLock은 항상 isHeld를 true로, token을 새 토큰으로 둔다.
  - unLock은 isHeld를 false로 바꾸는 바로 그 compute에서 항목을 지운다.
  - sweep은 항목을 지우기만 한다.
- F3: 맵 안 항목의 token은 같은 키에 대한 tryLock이 반환한 토큰이다. 토큰은 `tokenSequence.incrementAndGet()`으로 만들므로 모두 다르다.
- F4: 맵 안 항목의 `expiresAt`은 그 토큰을 발급한 tryLock의 `now + lockTimeoutMillis`이다. 거절된 tryLock은 항목을 바꾸지 않는다.
- F5 (permits): G-haiku-3, G-sonnet-4, G-opus-2에서 맵 안 항목의 세마포어 permit은 0이다. G-opus-1은 세마포어를 획득하지 않으므로 permit이 항상 1이다.

F2와 F5 때문에 다음 가지는 도달할 수 없다.

- G-sonnet-4 37-46행 (`!existing.isHeld.get()` 가지).
- G-opus-2 57-64행 (`existing.semaphore.tryAcquire()` 가지). 맵 안 항목의 permit이 0이므로 tryAcquire가 항상 실패한다.

### 4.2 unLock: G-sonnet-4, G-opus-1, G-opus-2는 correct

세 구현의 해제 조건은 같다: `existing != null && existing.isHeld.get() && existing.token == token`
(G-sonnet-4 71행, G-opus-1 82행, G-opus-2 81행).

- `P1`(common.kv 83-84행): res가 true이면 해제 조건이 참이었다.
  - `old(has(S,k))`와 `old(S.m[k].token) == t`가 성립한다.
  - F3에 따라 t는 키 k에 대해 발급되었다(`gIssued`, `gKey == k`).
  - t는 발급 때 항목에 실렸다(`gBacked`). G-sonnet-4의 인수 가지(48-53행)도 토큰을 항목에 싣는다.
  - 항목이 t를 가지므로 witness에 따라 `old(gHeld(t))`가 성립한다.
  - compute가 null을 반환하므로 `!has(S,k)`가 성립한다. t를 가진 항목이 없으므로 `!gHeld(t)`가 성립한다.
- `P3a`(common.kv 85행): true 경로에서 끝나는 획득은 지운 항목의 토큰 t 하나뿐이다. false 경로는 맵을 바꾸지 않는다.
- `progress_owner`(common.kv 87행): 항목이 있고 token이 t이면 F2에 따라 isHeld도 참이다. 그러므로 해제 조건이 참이고 res는 true이다.
- `no_entry_created`(common.kv 89행): 키가 없으면 람다는 `existing`(null)을 반환한다. 키는 계속 없다.

하네스 증거:

- S2: T+99, T+100, T+5000 모두 true였고 항목이 사라졌다.
- S4 (sidecar probe): `t1`은 백킹되었고, `t2`는 null, `unLock(t1)`은 true, 두 번째 `unLock(t1)`은 false였다.
- S6: 고정 시계에서 8 스레드가 4개 키에 각 20000회 tryLock/unLock을 했다. 겹친 holder는 0, 소유자 해제 거절은 0이었다.

### 4.3 unLock: G-haiku-3

- `progress_owner`가 깨진다. 3.3절의 시나리오가 반례이다.
- `P1`은 성립한다. 해제 경로(80-85행)는 4.2절과 같은 논증을 따른다.
- `P3a`는 성립한다. 해제 경로에서만 토큰 t의 획득이 끝난다.
- `no_entry_created`는 성립한다. 키가 없으면 78행의 첫 항이 참이고 람다는 null을 반환한다.

### 4.4 tryLock: 경계 밖에서 성립하는 속성 (네 구현)

- `P1a`(common.kv 62-63행): 반환 토큰은 `incrementAndGet()`으로 만든 새 값이다. 그래서 `!old(gIssued)`가 성립한다.
  `gKey == k`, `gHeld`, `gLease == now + timeout`은 issue 규칙(common.kv 60행)과 F4에서 나온다.
- `P1a_entry`(common.kv 64행): 백킹된 반환 토큰은 compute가 남긴 항목의 token이다.
- `P3b`(common.kv 67행): 백킹되지 않은 토큰은 `existing == null`이고 `rejectsNewEntry`가 true인 가지에서만 나온다.
  `rejectsNewEntry`의 ensures가 `limit != 0`을 준다(common.kv 40행).
  해당 가지는 G-haiku-3 49-52행, G-sonnet-4 27-29행, G-opus-1 52-55행, G-opus-2 47-50행이다.
- `progress_free`(common.kv 69행): 키가 없고 거절이 없으면 새 항목을 만들고 그 토큰을 반환한다.
- `cap_unbacked`(common.kv 71행): 키가 없고 거절이 있으면 토큰을 반환하고 compute는 null을 반환한다. 항목은 생기지 않는다.
- `expired_takeover`(common.kv 72행): `now > expiresAt`이면 네 구현 모두 인수 가지로 간다. 반환 토큰은 항목에 실린다.
- `refusal_keeps_entry`(common.kv 74-75행): null 반환은 항목이 있는 거절 가지에서만 나온다. 그 가지는 항목을 그대로 반환한다.
- `progress_live`와 `P3a`는 `now == expiresAt`에서만 깨진다. `now < expiresAt`의 거절과 `now > expiresAt`의 인수는 맞다.

하네스 증거(S5, cap): `LocalLockLimit.maxEntries`를 맵 크기로 두었다.

- 새 키의 tryLock은 토큰을 받았고 항목은 생기지 않았다.
- 그 토큰의 unLock은 false였다.
- 이미 있는 살아 있는 키의 tryLock은 null이었다. cap이 기존 키에 적용되지 않았다.
- 이미 있는 만료된 키(T+101)의 tryLock은 백킹된 토큰을 받았다.

### 4.5 sweep: 경계 밖에서 성립하는 속성 (네 구현)

- `no_entry_created`(common.kv 99행): 키가 없으면 람다는 `existing`(null)을 반환한다.
- `P3a`는 `now > expiresAt`인 삭제에서는 성립한다. F4와 `entry_lease`에 따라 `expiresAt == gLease`이기 때문이다.

### 4.6 상태 속성 (네 구현에서 성립)

- `P2`(common.kv 105행): 세마포어는 `Semaphore(1)`로 만든다.
  - G-haiku-3, G-sonnet-4, G-opus-2는 생성 때 한 번 획득하고, 해제는 항목을 지우는 unLock에서 한 번만 한다. permit은 0 또는 1이다.
  - G-opus-1은 획득도 해제도 하지 않는다. permit은 항상 1이다.
- `entry_lease`(common.kv 108-109행): F4에 따라 성립한다. 인수는 token과 expiresAt을 같은 compute에서 함께 바꾼다.
- `entry_held`(common.kv 114-115행): F2에 따라 맵 안 항목은 isHeld가 참이다. witness에 따라 그 토큰도 held이다.
- `P3c`(common.kv 118-120행): 백킹되고 held인 토큰은 그 키의 항목이 가진 토큰이다. 한 키의 항목은 토큰을 하나만 가진다.

### 4.7 자연어 명세 대조

다음 요구는 네 구현 모두 만족한다.

- 모든 획득은 소유권 토큰을 가진다. 토큰 형식은 `local-${tokenSequence.incrementAndGet()}`이다.
- 해제는 토큰이 맞을 때만 일어난다. 그래서 만료된 느린 holder는 다음 holder의 락을 해제할 수 없다(S1).
- 해제된 항목은 곧바로 지워진다.
- 모니터는 1 s 간격으로 돈다.
  - G-haiku-3: `scheduleAtFixedRate`(99-104행).
  - G-sonnet-4: `scheduleWithFixedDelay`(97-102행).
  - G-opus-1: 패스 뒤 `delay`(109-114행).
  - G-opus-2: `delay` 뒤 패스(123-128행).
- cap에 걸린 새 키는 항목 없는 토큰을 받는다. 이미 맵에 있는 키는 cap의 대상이 아니다.
- `rejectsNewEntry`는 새 키 시도마다 한 번만 호출된다. 그래서 거절 로그의 간격(첫 거절과 1000번째마다)이 유지된다.
- RULES 1-6: 상태 위치, LockInfo 형태, compute 한 번, compute 앞의 `val now = nowToEpochTime()`, 키별 모니터 compute,
  compute 안의 synchronized/lock/try-catch 금지를 지킨다. 4.8절의 관찰은 규칙 위반으로 보지 않았다.

### 4.8 결함으로 세지 않은 관찰

- Long overflow (모델 밖): 네 구현 모두 `now + lockTimeoutMillis`를 계산한다.
  위치는 G-haiku-3 58행, G-sonnet-4 31·43·51행, G-opus-1 62행, G-opus-2 61·102행이다.
  `lockTimeoutMillis`가 매우 크면 합이 음수로 넘친다. 그러면 새 항목이 곧바로 만료된 것처럼 보인다.
  과제 지시에 따라 sidecar 속성 결함으로 세지 않았다.
- G-haiku-3의 LockInfo는 companion 안에 있다(117-122행). 그래서 클래스 이름이 `KeyLocalLock.Companion.LockInfo`이다.
  sidecar의 바인딩은 `class LockInfo = KeyLocalLock.LockInfo`(common.kv 16행)이다. RULES 2는 위치를 정하지 않는다.
  이것은 바인딩 차이이고 동작 결함이 아니다.
- G-opus-2는 `kotlinx.coroutines.sync.Semaphore`를 쓴다(27행). sidecar는 `use jdk.Semaphore`(common.kv 27행)이다.
  RULES 2는 Semaphore의 패키지를 정하지 않는다. HELPERS.md는 두 라이브러리를 모두 허용한다.
  kotlinx Semaphore(1)의 permit도 0과 1 사이에 머문다. 그래서 P2는 의미상 성립한다. 이것은 바인딩 차이이다.
- G-haiku-3(42행)과 G-opus-1(42행)은 compute 밖에서, 거절될 호출에서도 토큰 번호를 소비한다.
  이것은 `S.seq`만 바꾼다. 판정에 쓴 속성은 `S.seq`를 읽지 않는다.
- G-opus-1은 세마포어를 쓰지 않는다. P2는 성립하고, RULES는 세마포어 사용을 요구하지 않는다.
- G-haiku-3의 unLock은 시계를 읽는다(74행). sidecar의 unLock에는 `free now`가 없다. 이 시계 읽기가 3.3절 결함의 원인이다.
- README의 "once `lockTimeoutMillis` has passed"는 경계를 홀로 정하지 못한다. 경계 판정은 sidecar의 명시 조건(2절)에 근거한다.

## 5. 하네스

- 위치: `aicode/round3g/judge-work/3/`.
  - `fakeclock/TimeUtils.kt`: 실행 클래스패스에서 support jar의 `TimeUtilsKt`를 가린다. 하네스가 `nowToEpochTime()` 값을 고정할 수 있다.
    `System.currentTimeMillis()`는 바꾸지 않는다. 그래서 모니터 패스는 실제 시계를 읽는다.
  - `harness/Harness.kt`: 시나리오 S1-S6. 맵과 LockInfo는 리플렉션으로 읽고, 모니터 패스는 `removeExpiredEntries`를 리플렉션으로 호출한다.
  - `build-and-run.sh`: 네 구현을 각각 컴파일하고 하네스를 돌린다. 생성기의 `compile.sh`는 호출하지 않는다.
  - `run.log`: 실행 출력.
- S1, S2, S4, S5, S6은 실제 시계보다 약 10^9 ms 뒤의 고정 시각을 쓴다. 그래서 배경 모니터는 이 항목들을 지우지 않는다.

| 시나리오 | G-haiku-3 | G-sonnet-4 | G-opus-1 | G-opus-2 |
|---|---|---|---|---|
| S1 T+100 tryLock (요구: null) | local-3 반환 | local-2 반환 | local-3 반환 | local-2 반환 |
| S2 T+100 소유자 unLock (요구: true) | false | true | true | true |
| S3 now == expiresAt 패스 (요구: 항목 유지) | 삭제 | 삭제 | 삭제 | 삭제 |
| S4 sidecar probe | 통과 | 통과 | 통과 | 통과 |
| S5 cap | 통과 | 통과 | 통과 | 통과 |
| S6 동시성 (겹침 / 소유자 거절) | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |

## 6. 확정하지 못한 점

- 동작 판정에서 확정하지 못한 항목은 없다.
- 검증기가 G-haiku-3의 LockInfo 위치와 G-opus-2의 kotlinx Semaphore를 바인딩할 수 있는지는 판정하지 않았다. 이것은 속성 판정 밖의 문제이다.
