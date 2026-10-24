# KeyLocalLock AI 구현 판정 (판정자 2)

- 날짜: 2026-10-24
- 대상: `aicode/impl/<ID>/KeyLocalLock.kt` (ID = T-sonnet-1, T-opus-2, T-haiku-3, T-sonnet-4)
- 기준 1: `specs/reqshield/keylocallock.common.kv`의 성질. P1, P1a, P1a_entry, P2, P3a, P3b, P3c, progress_free, progress_live, progress_owner, cap_unbacked, expired_takeover.
- 기준 2: 모델이 받은 packet의 SPEC.md 문장과 KeyLock KDoc.
- 방법: 코드를 줄 단위로 읽었다. 그 다음 결함마다 JVM에서 재현했다. 재현 도구와 출력은 6절에 있다.
- 줄 번호는 각 구현 파일의 줄 번호다.

## 1. 요약

| ID | 판정 | 깨진 성질 | 한 줄 이유 |
|---|---|---|---|
| T-sonnet-1 | 결함 (sidecar 성질) | P3a (tryLock, sweep), progress_live. 극단 입력에서 P3a 추가 | `now == expiresAt`일 때 tryLock이 살아 있는 lock을 빼앗고, 모니터가 그 entry를 지운다 |
| T-opus-2 | 결함 (sidecar 성질) | P3a (tryLock, sweep), progress_live | 같은 경계 결함이다. Long overflow는 포화 처리로 막았다 |
| T-haiku-3 | 결함 (sidecar 성질) | progress_owner, P3a (tryLock, sweep), progress_live. 극단 입력에서 P3a 추가 | unLock이 `expiresAt <= now`인 자기 entry를 풀지 않는다. 경계 결함도 같이 있다 |
| T-sonnet-4 | 결함 (sidecar 성질) | P3a (tryLock, sweep), progress_live. 극단 입력에서 P3a 추가 | T-sonnet-1과 같은 경계 결함이다 |

- "올바름(correct)"이나 "미결(undecided)"로 판정한 구현은 없다.
- "극단 입력"은 `now + lockTimeoutMillis`가 `Long.MAX_VALUE`를 넘는 경우다. 예: `lockTimeoutMillis = Long.MAX_VALUE`.
- P1, P1a, P1a_entry, P2, P3a (unLock), P3b, P3c, progress_free, cap_unbacked, expired_takeover는 네 구현 모두에서 성립한다. progress_owner는 T-haiku-3만 깬다.

## 2. 해석과 공통 논거

### 2.1 lease 경계: sidecar는 닫힌 구간을 쓴다

sidecar는 lock이 `now <= expiresAt`이면 살아 있고 `now > expiresAt`이면 만료라고 정한다.

- tryLock `progress_live`: `old(has(S, k)) && old(S.m[k].isHeld) && now <= old(S.m[k].expiresAt) ==> res == NULL`
- tryLock `expired_takeover`: `old(has(S, k)) && now > old(S.m[k].expiresAt) ==> res != NULL && tok(res).gBacked`
- tryLock과 sweep의 `P3a`: `ends_only_if P3a: now > old(tok(qt).gLease)`
- issue 규칙: `gLease = now + timeout`. MI_INV hole은 `tok(S.m[qk].token).gLease == S.m[qk].expiresAt`를 둔다.

그래서 entry의 토큰을 끝내도 되는 조건은 `now > expiresAt`다.
네 구현은 모두 반열린 구간 `[발급 시각, 발급 시각 + lockTimeoutMillis)`를 썼다. 즉 `now >= expiresAt`이면 만료로 본다.
그 결과 `now == expiresAt`인 1 ms에서 네 구현 모두 sidecar와 어긋난다.

주의: 모델이 받은 자연어 packet은 이 경계를 정하지 않는다.
SPEC.md는 "dropped by the cleanup monitor once `lockTimeoutMillis` has passed"라고만 쓴다.
이 문장은 `>=`로도 자연스럽게 읽힌다. KeyLock KDoc, HELPERS.md, RULES.md에는 경계에 대한 말이 없다.
그래서 이 결함은 sidecar 규약 위반이지만, 자연어 packet만으로는 모델이 피할 근거가 없었다.
판정은 지시대로 sidecar 성질을 따른다.

### 2.2 동시성: 원자적 action의 순서열로 보면 된다

네 구현은 모두 다음을 지킨다.

1. lockMap과 LockInfo 필드는 `lockMap.compute` lambda 안에서만 읽고 바꾼다.
2. 예외는 두 가지다. 모니터의 `lockMap.keys` 순회와 cap 추정용 `size` 또는 `mappingCount()` 읽기다. 둘 다 읽기만 한다.
3. LockInfo 객체 하나는 한 key의 entry로만 쓰인다.
4. compute 밖 코드는 `now` 읽기와 결과 cell(AtomicReference, AtomicBoolean) 읽기뿐이다. 둘 다 호출 thread 안의 값이다.
5. `ConcurrentHashMap.compute`는 key마다 원자적이다. lambda는 호출마다 한 번만 실행된다.

그래서 모든 실행은 "임의의 `now` 값을 가진 원자적 action의 순서열"과 같다.
성질은 action마다 그 action의 `now`로 정의된다. 그래서 단일 thread 순서열이 모든 interleaving을 덮는다.
compute 밖에서 읽은 `now`가 오래된 값이어도 새로운 위반은 생기지 않는다.
실제 JVM 부하 시험(S8: 8 thread × 20000회, lockTimeoutMillis = 60000)에서 네 구현 모두 겹친 보유자가 0이었다. 주인의 unLock 실패도 0, 지난 토큰의 해제 성공도 0이었다.

### 2.3 네 구현에서 모두 성립하는 성질의 논거

| 성질 | 논거 |
|---|---|
| P1a | 토큰은 compute 안에서 `"local-${tokenSequence.incrementAndGet()}"`로 만든다. 한 tryLock은 카운터를 많아야 한 번 올리고, 만든 토큰은 반드시 반환한다. 그래서 반환 토큰은 전에 발급된 적이 없다. gKey, gHeld, gLease는 issue 규칙이 넣는다. 같은 action의 END는 사전 entry의 토큰에만 적용되고, 그 토큰은 새 토큰과 다르다 |
| P1a_entry | issue 규칙이 `gBacked = carried(S, k, res)`를 사후 상태에서 정한다. 그래서 바로 성립한다 |
| P1 | unLock이 true를 내는 분기는 하나다. 그 분기는 적어도 `existing != null && existing.token == token`을 요구한다 (구현에 따라 isHeld나 만료 조건이 더 붙는다). 그리고 lambda가 null을 반환해 entry를 지운다. entry의 토큰은 tryLock 생성 분기에서만 들어가고, entry가 map에 있는 동안 바뀌지 않는다. 그래서 그 토큰은 이 key로 발급되었고, backed이고, held다. 삭제 뒤 END가 gHeld를 false로 만든다 |
| P3a (unLock) | unLock에서 entry를 바꾸는 분기는 true를 내는 분기뿐이다. 그래서 끝나는 토큰은 인자 토큰뿐이고, 그때 res는 true다 |
| P3b | unbacked 토큰은 cap 분기에서만 나온다. cap 분기는 `existing == null && LocalLockLimit.rejectsNewEntry(...)`일 때만 탄다. trusted 계약 `rej ==> limit != 0`이 남은 조건을 준다 |
| progress_free | entry가 없고 rej가 false이면 생성 분기로 간다. 생성 분기는 새 토큰을 가진 entry를 넣는다 |
| cap_unbacked | entry가 없고 rej가 true이면 새 토큰을 결과 cell에 넣고 lambda가 null을 반환한다. 그래서 entry가 생기지 않는다. 실측 S6: 두 번 연속 unbacked 토큰을 받았고 k2 entry는 없었다 |
| expired_takeover | `now > expiresAt`이면 네 구현의 live 검사가 거짓이다. existing이 null이 아니므로 cap 검사를 건너뛰고 생성 분기로 간다. 실측 S6(map이 가득 찬 상태)과 S7 |
| P3c | entry 하나는 토큰 하나만 가진다. 토큰은 생성 분기에서만 entry에 들어간다. entry가 토큰을 잃으면(삭제, 교체) END가 gHeld를 끈다. 그래서 한 key에 held backed 토큰은 많아야 하나다 |
| P2 | 구현마다 permit 변화가 다르다. 각 절에서 따로 따진다 |

구현별 근거 줄:

| 근거 | T-sonnet-1 | T-opus-2 | T-haiku-3 | T-sonnet-4 |
|---|---|---|---|---|
| tryLock 시계 읽기 | 42 | 44 | 23 | 43 |
| live 검사 (경계 결함) | 46 | 49 | 26 | 47 |
| 토큰 생성 | 50 | 52, 57 (`newToken` 108) | 31, 35 (`nextToken` 88) | 51 |
| cap 분기 | 52-54 | 51-54 | 29-32 | 53-55 |
| 생성 분기 | 57-61 | 56-60 | 33-43 | 57-60 |
| unLock 해제 조건 | 78 | 73 | 58 | 76 |
| unLock 삭제 (null 반환) | 84 | 75 | 62 | 82 |
| sweep 시계 읽기 | 119 | 113 | 93 | 119 |
| sweep 조건 (경계 결함) | 121 | 115 | 94 | 121 |

## 3. 구현별 판정

### 3.1 T-sonnet-1: 결함 (sidecar 성질)

#### 결함 A: P3a (tryLock), progress_live

코드 46행:

```kotlin
if (existing != null && existing.isHeld.get() && now < existing.expiresAt) {
```

`now == expiresAt`이면 이 조건이 거짓이다. 그래서 50-61행의 생성 분기가 새 토큰으로 entry를 덮어쓴다.

시나리오 (단일 thread, `now`는 `nowToEpochTime()`의 값):

1. 초기 상태: lockMap은 비어 있다. `lock = KeyLocalLock(100)`. cap은 0이다.
2. now = 1000에서 `lock.tryLock("k", LockType.CREATE)`를 부른다. 결과는 `"local-1"`이다. entry는 `{token=local-1, expiresAt=1100}`이다. ghost `gLease(local-1) = 1100`이다.
3. now = 1100에서 `lock.tryLock("k", LockType.CREATE)`를 부른다. 결과는 `"local-2"`이다. entry는 `{token=local-2, expiresAt=1200}`이다.

판정:

- progress_live: 사전 entry가 있고 isHeld가 true이고 `now(1100) <= expiresAt(1100)`이다. 그래서 결과는 NULL이어야 한다. 실제 결과는 `"local-2"`다.
- P3a (tryLock): 이 action에서 local-1이 끝난다(사후 entry가 local-1을 갖지 않는다). 그런데 `now(1100) > gLease(1100)`이 거짓이다.

실측:

- S1 (가짜 시계): `now=base+100 tryLock -> local-3`, 두 성질 모두 VIOLATION.
- S1r (실제 시계만 사용): `KeyLocalLock(0)`으로 같은 밀리초 안에서 tryLock을 두 번 부르면 둘 다 토큰을 받는다 (`t1=local-4 t2=local-5`).
- S1r5 (실제 시계만 사용): `KeyLocalLock(5)`에서 정확히 5 ms 뒤에 부른 tryLock도 토큰을 받는다.

#### 결함 B: P3a (sweep)

코드 121행:

```kotlin
if (existing != null && now >= existing.expiresAt) {
```

시나리오:

1. `lock = KeyLocalLock(100)`. 시스템 시계가 R일 때, `nowToEpochTime()`이 R − 100을 돌려주는 상태에서 tryLock을 부른다. entry는 `{expiresAt=R}`이고 `gLease = R`이다.
2. 모니터가 119행에서 `now = R`을 읽는다. 121행 조건이 참이므로 entry를 지운다.

판정: sweep의 P3a는 `now(R) > gLease(R)`을 요구한다. 이 식은 거짓이다.

실측:

- S2: 위 순서대로 실행했다. sweep 전후의 시스템 시계가 모두 R이었다. sweep 뒤 entry가 없었다.
- S2r (실제 시계만 사용): `KeyLocalLock(0)`에서 tryLock 직후 같은 밀리초에 sweep을 부르면 entry가 사라진다.

#### 결함 C: P3a (극단 입력, Long overflow)

코드 57행의 `now + lockTimeoutMillis`는 Long 덧셈이다. 결과가 범위를 넘으면 음수로 감긴다.
sidecar의 gLease는 Int(무한 정수)이고 issue 규칙의 덧셈은 감기지 않는다.

시나리오:

1. `lock = KeyLocalLock(Long.MAX_VALUE)`.
2. now = 1000에서 tryLock을 부른다. 결과는 `"local-1"`이다. expiresAt는 −9223372036854774809로 감긴다. gLease는 9223372036854776807이다.
3. now = 1001에서 tryLock을 부른다. 46행의 `now < existing.expiresAt`가 거짓이므로 빼앗는다. 결과는 `"local-2"`다.

판정: P3a는 `now(1001) > gLease(local-1)`을 요구한다. 이 식은 거짓이다.
모니터도 다음 주기에 이 entry를 지운다. 그래서 sweep의 P3a도 같은 이유로 깨진다.

실측: S4에서 `now=base+1 tryLock -> local-20`. S4r(실제 시계)에서 연속 두 호출이 모두 토큰을 받았다. 즉 이 설정에서 lock은 아무도 막지 못한다.

조건: `lockTimeoutMillis > Long.MAX_VALUE − now`일 때만 생긴다. 현재 시계로는 약 9.22×10^18 ms 이상이다.

#### 성질별 상태

| 성질 | 상태 | 근거 |
|---|---|---|
| P1 | 성립 | 2.3. 78행 조건, 84행 삭제. S7: 지난 토큰과 위조 토큰은 false |
| P1a | 성립 | 50행에서 한 번 증가, 66행에서 반환 |
| P1a_entry | 성립 | issue 규칙 |
| P2 | 성립 | 57행 `Semaphore(1)`, 58행 `tryAcquire`로 0. 79행 `release`로 1이 되지만 같은 compute에서 84행이 entry를 지운다. 그래서 같은 객체를 두 번 release하지 않는다. sweep과 takeover는 permit을 바꾸지 않는다 |
| P3a (tryLock) | 위반 | 결함 A, 결함 C |
| P3a (unLock) | 성립 | 2.3 |
| P3a (sweep) | 위반 | 결함 B (결함 C도 해당) |
| P3b | 성립 | 52행 |
| P3c | 성립 | 2.3. S8 겹침 0 |
| progress_free | 성립 | 2.3 |
| progress_live | 위반 | 결함 A |
| progress_owner | 성립 | 78행은 isHeld와 토큰만 본다. map 안 entry의 isHeld는 항상 true다 (59행에서 true, 80행에서 false로 바꾼 뒤 84행에서 바로 삭제). 만료 여부는 보지 않는다. S3: base+99, +100, +101, +500에서 모두 true |
| cap_unbacked | 성립 | 52-54행. S6 |
| expired_takeover | 성립 | S6, S7 |

#### 운영 메모 (성질 위반 아님)

- 모니터는 `newSingleThreadScheduledExecutor`의 데몬 thread 하나다 (105-114행). thread가 무한히 늘지 않는다.
- task가 예외를 던지면 `scheduleWithFixedDelay`는 이후 실행을 조용히 멈춘다. 로그도 남지 않는다. 지금 sweep 코드는 Error 말고는 던질 것이 없다.
- S10: 모니터 thread를 interrupt해도 thread가 살아남았고 sweep도 계속됐다.
- `lockTimeoutMillis <= 0`을 검사하지 않는다. 0이면 모든 tryLock이 바로 빼앗는다 (S1r).
- `rejectsNewEntry`의 WARN 로그가 compute 안에서, 즉 bin lock을 쥔 채 돈다.
- 50행에서 cap 판단 전에 토큰을 만든다. cap 분기도 이 토큰을 반환하므로 문제는 없다.

### 3.2 T-opus-2: 결함 (sidecar 성질)

#### 결함 A: P3a (tryLock), progress_live

코드 49행:

```kotlin
existing != null && existing.expiresAt > now -> existing
```

`now == expiresAt`이면 첫 분기가 거짓이다. 51행 분기는 `existing == null`이 거짓이라 건너뛴다. 그래서 56-60행의 else 분기가 새 entry로 덮어쓴다.

시나리오는 3.1 결함 A와 같다. now = 1000에서 tryLock은 `"local-1"`(expiresAt 1100)을 낸다. now = 1100에서 tryLock은 `"local-2"`를 낸다.
progress_live와 P3a (tryLock)가 깨진다.
실측: S1 `now=base+100 tryLock -> local-3`. S1r과 S1r5도 VIOLATION.

#### 결함 B: P3a (sweep)

코드 115행:

```kotlin
if (existing != null && existing.expiresAt <= now) null else existing
```

`now == expiresAt`이면 entry를 지운다. 시나리오는 3.1 결함 B와 같다. 실측: S2, S2r에서 VIOLATION.

#### Long overflow: 결함 아님

84-85행의 `expiryAfter`가 합을 `Long.MAX_VALUE`로 포화시킨다.

```kotlin
if (lockTimeoutMillis > Long.MAX_VALUE - now) Long.MAX_VALUE else now + lockTimeoutMillis
```

실측 S4: `KeyLocalLock(Long.MAX_VALUE)`에서 now = base+1의 tryLock은 null이다. S4r(실제 시계)에서도 두 번째 호출은 null이다.
현실 시계(0 이상, `Long.MAX_VALUE` 미만)에서는 P3a가 깨지지 않는다.

남는 점 두 가지 (나열된 성질 위반 아님):

1. 포화하면 `expiresAt = Long.MAX_VALUE < gLease`가 된다. 그래서 MI_INV의 `gLease == expiresAt`가 깨진다. 시계가 정확히 `Long.MAX_VALUE`를 읽으면 49행과 115행의 경계 결함 때문에 P3a가 깨진다. 이는 결함 A, B의 특수한 경우다.
2. 가드 자체가 넘친다. `now < 0`이면 `Long.MAX_VALUE - now`가 음수로 감긴다. 그러면 거의 모든 timeout에서 `expiresAt = Long.MAX_VALUE`가 된다. 실측 S4n: now = −1000, timeout 100에서 expiresAt가 9223372036854775807이었다 (기대값 −900). 이 lock은 만료되지 않는다. lease가 길어질 뿐이라 나열된 성질은 깨지지 않는다. SPEC의 "dropped ... once `lockTimeoutMillis` has passed" 문장에는 어긋난다. 하지만 시스템 시계가 1970년 이전일 때만 생기므로 결함으로 세지 않았다.

#### 성질별 상태

| 성질 | 상태 | 근거 |
|---|---|---|
| P1 | 성립 | 2.3. 73행 조건, 75행 삭제. S7 |
| P1a | 성립 | 52행 또는 57행에서 `newToken()` 한 번, 63행에서 반환 |
| P1a_entry | 성립 | issue 규칙 |
| P2 | 성립 | 59행 `Semaphore(0)`. 이 semaphore의 permit을 바꾸는 코드가 없다. 항상 0이다 |
| P3a (tryLock) | 위반 | 결함 A |
| P3a (unLock) | 성립 | 2.3 |
| P3a (sweep) | 위반 | 결함 B |
| P3b | 성립 | 51행 |
| P3c | 성립 | 2.3. S8 겹침 0 |
| progress_free | 성립 | 2.3 |
| progress_live | 위반 | 결함 A |
| progress_owner | 성립 | 73행은 토큰만 본다. S3: 네 시각 모두 true |
| cap_unbacked | 성립 | 51-54행. S6 |
| expired_takeover | 성립 | S6, S7 |

#### 운영 메모 (성질 위반 아님)

- 모니터는 `CoroutineScope(SupervisorJob() + Dispatchers.Default).launch`의 coroutine 하나다 (100-105행). 공유 Default dispatcher를 쓴다. 이름 붙은 thread는 없다.
- sweep이 예외를 던지면 loop가 끝나고 다시 시작하지 않는다. 예외는 기본 handler로 출력된다.
- `lockTimeoutMillis <= 0`을 검사하지 않는다. 0이면 S1r처럼 모든 tryLock이 빼앗는다.
- `rejectsNewEntry`의 WARN 로그가 compute 안에서 돈다.
- 위의 음수 시계 가드 문제.

### 3.3 T-haiku-3: 결함 (sidecar 성질)

#### 결함 A: progress_owner

코드 55행과 58행:

```kotlin
val now = nowToEpochTime()
...
if (existing != null && existing.expiresAt > now && existing.token == token) {
```

unLock이 시계를 읽는다. `now >= expiresAt`이면 entry가 그 토큰을 가져도 풀지 않고 false를 낸다.

시나리오:

1. 초기 상태: lockMap은 비어 있다. `lock = KeyLocalLock(100)`.
2. now = 1000에서 tryLock을 부른다. 결과는 `"local-1"`이다. entry는 `{token=local-1, expiresAt=1100}`이다.
3. 모니터는 아직 이 entry를 지우지 않았고, 다른 tryLock도 없다.
4. now = 1101에서 `lock.unLock("k", LockType.CREATE, "local-1")`을 부른다. 결과는 false다. entry는 그대로 남는다.

판정: progress_owner는 `old(has(S, k)) && old(S.m[k].token) == t ==> res`다. 사전 entry가 있고 토큰이 같으므로 결과는 true여야 한다.
now = 1100에서도 같은 결과가 나온다. 이 시각은 sidecar 기준으로 아직 살아 있는 lock이다.

실측:

- S3: base+100, +101, +500에서 unLock이 false였다. 예를 들어 base+100의 호출 뒤에 entry `{token=local-14, expiresAt=base+100}`가 남았다. base+99에서는 true였다.
- S3r (실제 시계만 사용): `KeyLocalLock(0)`으로 tryLock 직후 같은 밀리초에 unLock하면 false다. entry도 남는다.

영향: 늦게 unLock한 주인은 false를 받는다. entry는 모니터가 지울 때까지(최대 약 1 s) 또는 다음 tryLock이 빼앗을 때까지 남는다. 그동안 그 entry는 cap 계산에 들어간다.
SPEC 문장 "An entry is dropped as soon as the lock is released"와도 어긋난다.

#### 결함 B: P3a (tryLock), progress_live

코드 26행:

```kotlin
if (existing != null && existing.expiresAt > now) {
```

`now == expiresAt`이면 거짓이다. 29행 분기는 `existing == null`이 거짓이라 건너뛴다. 그래서 33-43행이 새 entry로 덮어쓴다.
시나리오는 3.1 결함 A와 같다. 실측: S1, S1r, S1r5에서 VIOLATION.

#### 결함 C: P3a (sweep)

코드 94행:

```kotlin
lockMap.compute(key) { _, existing -> existing?.takeIf { it.expiresAt > now } }
```

`expiresAt <= now`이면 null을 반환해 entry를 지운다. `now == expiresAt`에서 지우므로 P3a가 깨진다.
시나리오는 3.1 결함 B와 같다. 실측: S2, S2r에서 VIOLATION.

#### 결함 D: P3a, progress_owner (극단 입력, Long overflow)

39행 `expiresAt = now + lockTimeoutMillis`가 감긴다. 시나리오는 3.1 결함 C와 같다. 실측 S4: now = base+1의 tryLock이 `local-19`를 받았다.
이 경우 감긴 expiresAt(약 −9.22×10^18)는 현실 시계 값보다 언제나 작다. 그래서 58행 조건이 항상 거짓이고, 주인도 풀지 못한다. 실측 S4: `unLock(current holder local-19) -> false`.

#### 성질별 상태

| 성질 | 상태 | 근거 |
|---|---|---|
| P1 | 성립 | 58행 조건은 다른 구현보다 엄격하다. true일 때 62행이 지운다. S7 |
| P1a | 성립 | 31행 또는 35행에서 `nextToken()` 한 번, 45행에서 반환 |
| P1a_entry | 성립 | issue 규칙 |
| P2 | 성립 | 38행 `Semaphore(1).also { it.tryAcquire() }`로 0. 59행 `release`로 1이 되지만 62행이 같은 compute에서 entry를 지운다. sweep과 takeover는 permit을 바꾸지 않는다 |
| P3a (tryLock) | 위반 | 결함 B, 결함 D |
| P3a (unLock) | 성립 | 해제 분기만 entry를 바꾼다 |
| P3a (sweep) | 위반 | 결함 C (결함 D도 해당) |
| P3b | 성립 | 29행 |
| P3c | 성립 | 2.3. S8 겹침 0 |
| progress_free | 성립 | 2.3 |
| progress_live | 위반 | 결함 B |
| progress_owner | 위반 | 결함 A, 결함 D |
| cap_unbacked | 성립 | 29-32행. S6 |
| expired_takeover | 성립 | S6, S7 |

#### 운영 메모 (성질 위반 아님)

- 모니터는 raw `Thread` 하나다 (76-85행). 78행 `Thread.sleep`은 interrupt되면 `InterruptedException`을 던진다. 잡는 곳이 없어서 thread가 죽는다.
  - S10에서 재현했다. interrupt 뒤 thread가 죽었고, 만료된 entry가 2.6 s 뒤에도 남았다.
  - 그 뒤로는 같은 key의 tryLock만 만료 entry를 치운다. 다시 쓰이지 않는 key의 entry는 계속 남고 cap을 채운다.
  - 이는 SPEC 문장 "an entry whose holder never released it is dropped by the cleanup monitor"를 깬다. 다만 외부에서 라이브러리 thread를 interrupt해야 생긴다.
- sweep이 예외를 던져도 thread가 죽는다.
- `LockInfo`가 파일 최상위 private class다 (101-106행). RULES 2와 sidecar의 `KeyLocalLock.LockInfo`와 위치가 다르다.
- unLock이 시계를 읽는다 (55행). sidecar의 unLock 연산에는 이 값이 선언되어 있지 않다.
- `lockTimeoutMillis <= 0`을 검사하지 않는다.
- `rejectsNewEntry`의 WARN 로그가 compute 안에서 돈다.

### 3.4 T-sonnet-4: 결함 (sidecar 성질)

#### 결함 A: P3a (tryLock), progress_live

코드 47행:

```kotlin
if (existing != null && now < existing.expiresAt) {
```

`now == expiresAt`이면 거짓이다. 그래서 51-60행이 새 토큰으로 entry를 덮어쓴다.
시나리오는 3.1 결함 A와 같다. 실측: S1 `now=base+100 tryLock -> local-3`. S1r과 S1r5도 VIOLATION.

#### 결함 B: P3a (sweep)

코드 121행:

```kotlin
if (existing != null && now >= existing.expiresAt) null else existing
```

시나리오는 3.1 결함 B와 같다. 실측: S2, S2r에서 VIOLATION.

#### 결함 C: P3a (극단 입력, Long overflow)

58행 `LockInfo(Semaphore(1), now + lockTimeoutMillis, AtomicBoolean(true), token)`의 덧셈이 감긴다.
시나리오는 3.1 결함 C와 같다. 실측: S4에서 now = base+1의 tryLock이 `local-21`을 받았다. S4r에서 연속 두 호출이 모두 토큰을 받았다.

#### 성질별 상태

| 성질 | 상태 | 근거 |
|---|---|---|
| P1 | 성립 | 2.3. 76행 조건, 82행 삭제. S7 |
| P1a | 성립 | 51행에서 한 번 증가, 65행에서 반환 |
| P1a_entry | 성립 | issue 규칙 |
| P2 | 성립 | 58행 `Semaphore(1)`, 59행 `tryAcquire`로 0. 79행 `release`로 1이 되지만 같은 compute에서 82행이 entry를 지운다 |
| P3a (tryLock) | 위반 | 결함 A, 결함 C |
| P3a (unLock) | 성립 | 2.3 |
| P3a (sweep) | 위반 | 결함 B (결함 C도 해당) |
| P3b | 성립 | 53행 |
| P3c | 성립 | 2.3. S8 겹침 0 |
| progress_free | 성립 | 2.3 |
| progress_live | 위반 | 결함 A |
| progress_owner | 성립 | 76행은 isHeld와 토큰만 본다. map 안 entry의 isHeld는 항상 true다 (58행에서 true, 77행에서 false로 바꾼 뒤 82행에서 바로 삭제). S3: 네 시각 모두 true |
| cap_unbacked | 성립 | 53-55행. S6 |
| expired_takeover | 성립 | S6, S7 |

#### 운영 메모 (성질 위반 아님)

- 모니터는 companion의 `newSingleThreadScheduledExecutor` 데몬 thread 하나다 (102-114행).
- task가 예외를 던지면 이후 실행이 조용히 멈춘다. S10: interrupt에는 살아남았다.
- `LockInfo`가 companion 안에 있다 (95-100행). sidecar의 `KeyLocalLock.LockInfo`와 위치가 다르다.
- `lockTimeoutMillis <= 0`을 검사하지 않는다.
- `rejectsNewEntry`의 WARN 로그가 compute 안에서 돈다.

## 4. 여러 구현이 공유하는 결함

1. 만료 경계 (4개 모두): tryLock과 모니터가 `now == expiresAt`을 만료로 본다. P3a (tryLock, sweep)와 progress_live를 깬다. 자연어 packet이 경계를 정하지 않은 것이 공통 원인으로 보인다 (2.1).
2. Long overflow (T-sonnet-1, T-haiku-3, T-sonnet-4): `now + lockTimeoutMillis`가 감긴다. `lockTimeoutMillis`가 `Long.MAX_VALUE` 근처이면 lock이 아무도 막지 못한다. P3a를 깨고, T-haiku-3에서는 progress_owner도 깬다. T-opus-2만 포화 처리를 했다.
3. 운영: `lockTimeoutMillis <= 0`을 검사하지 않는다 (4개 모두). 값이 0이면 경계 결함 때문에 같은 밀리초의 두 번째 tryLock도 lock을 받는다. 값이 음수이면 어느 경계 규약으로도 lock이 아무도 막지 못한다. 음수 값 자체는 sidecar 성질을 깨지 않는다 (P3a의 `now > gLease`가 성립한다).
4. 운영: 모니터가 한 번 실패하면 다시 시작하지 않는다 (4개 모두, 방식만 다름). T-haiku-3은 interrupt로도 죽는다.
5. 운영: `rejectsNewEntry`의 WARN 로그가 compute 안에서 돈다 (4개 모두).

## 5. 검증기 결과와 비교할 때 주의할 점

1. 경계 결함은 sidecar의 닫힌 lease 규약 위반이다. 네 구현 모두에서 tryLock의 P3a와 progress_live, sweep의 P3a가 실패해야 정답이다. T-haiku-3에서는 unLock의 progress_owner도 실패해야 한다. 검증기가 operation마다 첫 실패만 보고하면 그중 일부만 나올 수 있다. 어느 구현이든 이 성질들을 모두 통과하면 검증기 쪽을 먼저 의심해야 한다.
2. overflow 결함은 Long 모델링에 달렸다. 검증기가 Kotlin Long을 무한 정수로 다루면 이 결함을 보지 못한다. overflow를 assert로 검사하면 성질 실패가 아니라 산술 오류로 보고할 수 있다. 어느 쪽인지는 확인하지 않았다 (번역기는 읽지 않았다).
3. T-opus-2의 포화는 MI_INV의 `gLease == expiresAt`를 깬다. 그래서 검증기가 MI_INV를 귀납적으로 증명하지 못할 수 있다. 이것만으로는 나열된 성질 위반이 아니다.
4. 구조 차이가 있다. 이 차이 때문에 검증기가 성질 판정이 아닌 "unsupported"나 해석 오류를 낼 수 있다. 그런 결과는 성질 판정과 따로 봐야 한다.
   - T-haiku-3의 `LockInfo`는 파일 최상위, T-sonnet-4의 `LockInfo`는 companion 안에 있다. sidecar는 `KeyLocalLock.LockInfo`를 가리킨다.
   - T-sonnet-1, T-haiku-3, T-sonnet-4는 `lockMap.size`를 쓴다. sidecar의 `use` 목록에는 `jdk.ConcurrentHashMap.mappingCount`가 있다.
   - T-haiku-3은 unLock에서 시계를 읽는다.
   - T-opus-2는 `when`과 `expiryAfter` 메서드 호출을 쓴다.

## 6. 재현 방법

작업 폴더: `/Users/ykhfree/AI/kotlin-verifier/aicode/judge-work/2/`

| 파일 | 내용 |
|---|---|
| `build-and-run.sh` | Kotlin 2.0.21 컴파일러(Gradle 캐시)로 빌드하고 네 구현에 driver를 실행한다 |
| `src/driver/Driver.kt` | 시나리오 S1-S10과 변형(S1r, S1r5, S2r, S2c, S3r, S4r, S4n). 각 줄에 관찰 결과와 성질이 요구하는 결과(OK/VIOLATION)를 찍는다 |
| `src/support-fake/TimeUtils.kt` | `nowToEpochTime()` 대역. `FakeClock.fixed`가 null이면 실제 helper처럼 `System.currentTimeMillis()`를 돌려준다 |
| `src/support-common/LocalLockLimit.kt` | req-shield의 실제 파일 복사본 |
| `src/support-common/ConfigValues.kt` | 컴파일에 필요한 상수만 둔 대역 (값은 HELPERS.md, SPEC.md, sidecar에서 가져옴) |
| `impl/<ID>/KeyLocalLock.kt` | 판정 대상의 복사본 (수정 없음) |
| `run-output.txt` | 마지막 실행 출력 |

재현 순서:

1. `/Users/ykhfree/AI/kotlin-verifier/aicode/judge-work/2/build-and-run.sh build`를 실행한다.
2. `/Users/ykhfree/AI/kotlin-verifier/aicode/judge-work/2/build-and-run.sh run`을 실행한다. 한 구현만 보려면 두 번째 인자로 ID를 준다.

시나리오 설계:

- 가짜 시계 시나리오(S1, S3, S4, S6, S7)는 실제 시계보다 1000만 ms 뒤의 시각을 쓴다. 그래서 실제 모니터가 끼어들지 않는다.
- sweep 시나리오(S2, S2r)는 sweep 앞뒤의 시스템 시계가 같은 값일 때만 결과를 쓴다. 그래서 sweep이 읽은 `now`가 그 값으로 확정된다.
- 실제 시계 시나리오(S1r, S1r5, S2r, S3r, S4r)는 helper 대역 없이도 같은 결함을 보인다.
- S8은 실제 동시성 부하, S9는 실제 모니터 동작, S10은 모니터 thread interrupt 시험이다.

## 7. 블라인드 준수

- 읽은 것: 네 구현의 `KeyLocalLock.kt`, sandbox T-sonnet-1의 SPEC.md, KeyLock.kt, HELPERS.md, RULES.md, `keylocallock.common.kv`, DESIGN-v0.md의 제목 목록과 3.5, 4.4, 4.6절 (3.5절을 출력할 때 바로 뒤의 3.6절 위험 표도 같이 읽었다), req-shield의 TimeUtils.kt와 LocalLockLimit.kt.
- 읽지 않은 것: impl 폴더의 report.md와 prompt.txt, results.jsonl, logs, 다른 ID, dryrun, sandbox의 compile.sh와 다른 폴더, 원본 KeyLocalLock.kt, mutation, HISTORY.md, AI-CODE.md, MUTATION.md, 번역기.
- 실행하지 않은 것: `kv`, req-shield 테스트, translator.
- ConfigValues.kt는 읽지 않고 대역을 새로 썼다.
- cross-memory의 query와 record는 하지 않았다. 과거 검증기 결과가 섞여 블라인드가 깨질 수 있어서다.
