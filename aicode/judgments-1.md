# KeyLocalLock AI 구현 판정 (judge 1)

- 대상: `aicode/impl/<ID>/KeyLocalLock.kt` (ID = T-haiku-1, T-sonnet-2, T-opus-3, T-haiku-4)
- 기준: `specs/reqshield/keylocallock.common.kv`의 성질 절, SPEC.md, `KeyLock` KDoc
- 증거: 코드 읽기, 그리고 재현 harness `aicode/judge-work/1/` 실행 결과 (9절)
- 작성일: 2026-10-24

## 1. 판정 표

| ID | 판정 | 깨진 성질 | 한 줄 이유 |
|---|---|---|---|
| T-haiku-1 | defect (sidecar property) | progress_live, P3a(tryLock), P3a(sweep) | 만료 검사가 `now >= expiresAt`(127행)이다. 그래서 `now == expiresAt`일 때 tryLock이 살아 있는 lock을 넘겨받고, monitor가 entry를 지운다 |
| T-sonnet-2 | defect (sidecar property) | progress_live, P3a(tryLock), P3a(sweep) | `isExpired`가 `now >= info.expiresAt`(119행)이다. 결과는 T-haiku-1과 같다 |
| T-opus-3 | defect (sidecar property) | progress_live, P3a(tryLock), P3a(sweep) | `isExpired`가 `lockInfo.expiresAt <= now`(117행)이다. 결과는 T-haiku-1과 같다 |
| T-haiku-4 | defect (sidecar property) | progress_live, P3a(tryLock), P3a(sweep) | tryLock의 live 조건 `now < existing.expiresAt`(42행)과 monitor 조건 `existing.expiresAt <= now`(93행)이 경계값을 만료로 본다 |

- 4개 구현은 같은 주 결함을 가진다. sidecar는 만료를 엄격한 `now > expiresAt`로 정한다 (progress_live 70행, expired_takeover 72행, P3a 65행·87행). 4개 구현은 모두 비엄격한 `now >= expiresAt`를 쓴다.
- 부 결함도 4개 공통이다. `now + lockTimeoutMillis`가 `Long` 범위를 넘으면 음수로 wrap된다. ghost `gLease`를 Viper의 무한 정수로 읽으면 P3a가 깨진다. verifier가 이것을 보고하는지는 Kotlin `Long` 덧셈의 모델링 방식에 달려 있다 (2.3절).
- 아래 성질은 4개 구현 모두에서 성립한다: P1, P1a, P1a_entry, P3a(unLock), P3b, P3c, P2, progress_free, progress_owner, cap_unbacked, expired_takeover.
- 이 문서에서 P3a(tryLock), P3a(unLock), P3a(sweep)은 각 operation의 `ends_only_if P3a` 절을 뜻한다 (sidecar 65행, 80행, 87행).

## 2. 공통 근거

4개 구현은 같은 시나리오로 깨진다. 그래서 시나리오와 공통 논증을 여기에 한 번만 적는다. 3~6절은 구현별 코드 행과 관찰 값을 적는다.

### 2.1 재현 시나리오 R1: tryLock 경계 (progress_live, P3a(tryLock))

스레드 하나의 순서로 충분하다. 끼어들기는 필요 없다.

1. 초기 상태: `lockMap`이 비어 있다. `LocalLockLimit.maxEntries = 0`(무제한)이다. `val lock = KeyLocalLock(lockTimeoutMillis = 1000)`.
2. A가 `lock.tryLock("k", CREATE)`를 호출한다. 이때 `nowToEpochTime()`은 5000을 돌려준다.
   - 결과는 `"local-1"`이다. entry는 `{token = "local-1", expiresAt = 6000, isHeld = true}`이다.
   - ghost: `tok(local-1).gLease = 5000 + 1000 = 6000`.
3. B가 같은 key로 `tryLock`을 호출한다. 이때 `nowToEpochTime()`은 6000을 돌려준다.
   - 4개 구현 모두 null이 아닌 토큰을 돌려준다. entry는 B의 토큰을 가진다.
4. A가 `lock.unLock("k", CREATE, "local-1")`을 호출한다. 결과는 false다. A의 획득은 3단계에서 이미 끝났다.

판정:
- progress_live: 전제는 `old(has(S,k))`, `old(S.m[k].isHeld) = true`, `now(6000) <= old(expiresAt)(6000)`이다. 전제가 모두 참이므로 `res == NULL`이어야 한다. 실제 결과는 null이 아니다.
- P3a(tryLock): 3단계는 `local-1`을 끝낸다. 조건 `now(6000) > old(tok(local-1).gLease)(6000)`는 거짓이다.

실제 clock에서도 이 경계에 도달할 수 있다. `nowToEpochTime()`은 `System.currentTimeMillis()`이다 (TimeUtils.kt 23행). lease 하나마다 1 ms 창이 있다.

### 2.2 재현 시나리오 R2: monitor 경계 (P3a(sweep))

1. 초기 상태: R1의 2단계 직후다. entry는 `{token = "local-1", expiresAt = 6000}`이다.
2. monitor 패스가 key `k`에 대해 `System.currentTimeMillis()`를 읽는다. 값은 6000이다. 이어서 `lockMap.compute(k)`를 호출한다.
3. 4개 구현의 조건은 모두 `6000 >= 6000`으로 참이다. 람다가 null을 돌려준다. entry가 지워지고 `local-1`이 끝난다.

판정: P3a(sweep)의 조건 `now(6000) > old(tok(local-1).gLease)(6000)`는 거짓이다.

### 2.3 재현 시나리오 R3: Long overflow (부 결함, 모델 의존)

1. 초기 상태: `lockMap`이 비어 있다. `val lock = KeyLocalLock(lockTimeoutMillis = Long.MAX_VALUE)`.
2. A가 `now = 1_760_000_000_000`에서 `tryLock`을 호출한다. 결과는 토큰이다.
   - 코드의 `expiresAt = now + Long.MAX_VALUE`는 wrap되어 `-9_223_370_276_854_775_809`가 된다.
   - ghost `gLease = now + timeout`은 무한 정수로 `9_223_373_796_854_775_807`이다.
3. B가 `now = 1_760_000_000_001`에서 `tryLock`을 호출한다. 4개 구현 모두 entry를 만료로 보고 넘겨받는다. B는 토큰을 받는다.
4. B가 없어도 monitor가 다음 패스(1 s 이내)에서 entry를 지운다.

판정:
- ghost가 Viper `Int`(무한 정수)이면 P3a(tryLock)과 P3a(sweep)이 깨진다. 조건 `now > gLease`가 거짓이기 때문이다.
- verifier가 `Long` 덧셈을 wrap 없는 정수로 모델링하면 이 경로를 보지 못한다. 그 경우 이 결함은 sidecar 밖 결함이다. 깨지는 SPEC 문장은 "an entry whose holder never released it is dropped by the cleanup monitor once `lockTimeoutMillis` has passed"이다.
- 나는 DESIGN-v0.md 2.8절(`Long` 산술)을 읽지 않았다 (허용 목록 밖이다). 그래서 verifier가 어느 쪽인지 모른다.
- 도달 조건: `lockTimeoutMillis > Long.MAX_VALUE - now`. "만료 없음"의 뜻으로 `Long.MAX_VALUE`를 설정하면 도달한다. 결과는 상호 배제가 전혀 없는 lock이다.
- 대조군(엄격한 `>` 버전)도 이 시나리오에서 실패한다. 이 결함은 비교 방향과 무관하다.

### 2.4 성립하는 성질의 공통 논증

4개 구현 모두 다음 불변식 I를 지킨다. I는 sidecar의 `MI_INV`와 같은 내용이다.

> I: `lockMap`의 모든 entry는 null 아닌 토큰 `t`를 가진다. `t`는 그 key로 발급되었고, backed이고, held이다. `tok(t).gLease == expiresAt`이다. entry의 `isHeld`는 true이고 semaphore의 남은 permit은 0이다. 그리고 held인 backed 토큰은 모두 자기 key entry의 토큰이다.

I가 유지되는 이유:
1. entry는 토큰을 tryLock에서만 얻는다 (새 entry 생성 또는 만료 entry 인수). 같은 action이 그 토큰을 발급한다. 발급 값은 `gKey = k`, `gBacked = carried = true`, `gLease = now + timeout`이다. 코드는 같은 `now`와 `lockTimeoutMillis`로 `expiresAt`을 정한다.
2. entry가 토큰을 잃는 경로는 세 가지다: unLock의 삭제, monitor의 삭제, tryLock의 인수. 세 경로 모두 END 규칙(DESIGN 3.5 규칙 1)에 따라 그 토큰의 `gHeld`를 false로 만든다. entry가 토큰 없이 map에 남는 경로는 없다.
3. 모든 `LockInfo` 필드 읽기와 쓰기는 같은 key의 `compute` 람다 안에서 일어난다. `ConcurrentHashMap.compute`는 key마다 원자적이고, 람다를 정확히 한 번 호출한다 (JDK Javadoc: "The supplied function is invoked exactly once per invocation of this method."). 같은 key의 compute가 보호하지 않는 읽기는 두 가지뿐이다: monitor의 `lockMap.keys` 순회, 그리고 람다 안에서 읽는 `lockMap.size` 추정치. `lockMap.size`는 `rejectsNewEntry`의 인자로만 쓰인다. sidecar는 `rej`를 자유 값으로 두므로 이 추정치는 성질에 영향이 없다. `lockMap.keys` 순회는 각 key마다 compute를 다시 호출하므로 역시 영향이 없다.
4. 토큰 번호는 `AtomicLong.incrementAndGet()`에서 온다. 그래서 발급되는 토큰은 서로 다르다.

I에서 다음이 나온다.
- P1: unLock이 true를 돌려주는 경우는 entry가 있고 `entry.token == t`일 때뿐이다. 그 entry는 같은 람다에서 지워진다. I에 의해 `t`는 그 key로 발급된 backed, held 토큰이다. 삭제는 END이므로 사후 상태에서 `!tok(t).gHeld`이고 `!has(S, k)`이다.
- P3a(unLock): unLock이 상태를 바꾸는 경로는 위의 삭제 하나뿐이다. 이때 끝나는 토큰은 `t`이고 `res = true`다.
- progress_owner: 토큰이 일치하면 삭제 경로가 반드시 실행된다. T-sonnet-2와 T-opus-3은 `isHeld` CAS를 추가로 요구한다. I에 의해 map의 entry는 모두 `isHeld = true`이므로 CAS는 항상 성공한다.
- P3c: entry 하나는 토큰 필드 하나를 가진다. I에 의해 held인 backed 토큰은 자기 key entry의 토큰이다. 그래서 같은 key에는 그런 토큰이 많아야 하나다.
- P1a: 토큰은 새 번호이므로 `!old(gIssued)`이다. 나머지 conjunct는 issue 규칙이 정한다.
- 오래된 토큰과 위조 토큰: 토큰은 서로 다르다. 그래서 끝난 토큰, 다른 key의 토큰, unbacked 토큰은 어떤 entry의 토큰과도 같지 않다. unLock은 false를 돌려준다. 위조 문자열도 현재 entry의 토큰과 다르면 false다 (S3.d).
- 한계: 토큰은 순번 `local-N`이므로 추측할 수 있다. 추측한 문자열이 현재 entry의 토큰과 같으면 unLock은 true를 돌려준다. P1은 토큰 값만 보므로 이것을 허용한다. 토큰 형식은 RULES.md 1이 정했으므로 구현 결함이 아니다.

## 3. T-haiku-1

판정: **defect (sidecar property)**. 깨진 성질: progress_live, P3a(tryLock), P3a(sweep). 부 결함: R3 (P3a, 모델 의존).

### 3.1 주 결함의 코드 근거

- 127행: `fun isExpired(now: Long): Boolean = now >= expiresAt`
- tryLock 59~64행: `existing.isExpired(now)`가 참이면 61행 `existing.token = token`, 62행 `existing.expiresAt = now + lockTimeoutMillis`를 실행하고 토큰을 돌려준다. entry를 바꾸지 않고 같은 객체의 witness 필드를 덮어쓴다. 이 덮어쓰기도 END다 (DESIGN 3.5 규칙 1: "필드 덮어쓰기").
- monitor 113행: `if (existing != null && existing.isExpired(now)) null else existing`

R1, R2가 그대로 적용된다.

### 3.2 harness 관찰 (`judge-work/1/run-output.txt`)

| 시나리오 | sidecar 요구 | 관찰 |
|---|---|---|
| S1.b B.tryLock, now = t0+999 | null | null |
| S1.c B.tryLock, now = t0+1000 = expiresAt | null (progress_live) | `local-3`, entry 토큰도 `local-3` |
| S1.d 이어서 A.unLock(local-1) | true (P3a: A의 획득은 아직 끝나면 안 됨) | false |
| S6 monitor, 실제 clock, 2600 entry | 경계에서 삭제 없음 | 경계 삭제 1건: expiresAt = 1792851206242인 entry가 clock이 1792851206242일 때 이미 없었다 |
| S7 timeout = Long.MAX_VALUE | null (무한 정수 gLease 기준) | B가 토큰을 받음. expiresAt = -9223370244003571476 |
| S8 timeout = 0, 같은 ms의 B | null (progress_live) | B가 토큰을 받음 |

그 밖의 23개 검사는 모두 통과했다 (9.1절 목록).

### 3.3 성질별 판정

| 성질 | 결과 | 근거 |
|---|---|---|
| P1a | 성립 | 41행에서 새 번호로 토큰을 만든다. 토큰은 46행 또는 63행을 거쳐 70행에서만 나간다 |
| P1a_entry | 성립 | backed 토큰은 55행(새 entry) 또는 61행(인수)에서 entry에 들어간다 |
| P3a(tryLock) | **위반** | R1. 59행 + 127행의 `>=` |
| P3b | 성립 | unbacked 경로는 49행뿐이다. 이 경로는 `existing == null`(45행)이고 `rejectsNewEntry`가 참(48행)일 때만 실행된다. 신뢰 계약 `rej ==> limit != 0`이 남은 conjunct를 준다 |
| progress_free | 성립 | `existing == null`이고 rej가 거짓이면 51~56행이 토큰을 가진 entry를 넣는다 |
| progress_live | **위반** | R1. `now == expiresAt`에서 59행이 참이 된다 |
| cap_unbacked | 성립 | 46행이 토큰을 정하고 49행이 null을 돌려준다. compute는 아무것도 넣지 않는다 |
| expired_takeover | 성립 | `now > expiresAt`이면 `now >= expiresAt`도 참이다. 61~64행이 entry에 새 토큰을 넣는다 |
| P1 | 성립 | 81행이 `existing.token != token`이면 그대로 둔다. 일치하면 87행이 entry를 지운다. 나머지는 2.4절 |
| P3a(unLock) | 성립 | 상태를 바꾸는 경로는 84~87행뿐이다 |
| progress_owner | 성립 | 81행의 조건은 null 검사와 토큰 비교뿐이다 |
| P3a(sweep) | **위반** | R2. 113행 + 127행 |
| P2 | 성립 | 52행이 permit을 0으로 만든다. `release`는 84행에서만 일어나고 같은 람다가 87행에서 entry를 지운다. 인수(61~64행)는 같은 객체를 release 없이 다시 쓴다. 그래서 객체마다 release는 많아야 한 번이고 permit은 0 또는 1이다 |
| P3c | 성립 | 2.4절. S4.a/S4.b(16 스레드 × 300회)에서 매회 승자가 정확히 1명이었다 |

## 4. T-sonnet-2

판정: **defect (sidecar property)**. 깨진 성질: progress_live, P3a(tryLock), P3a(sweep). 부 결함: R3 (P3a, 모델 의존).

### 4.1 주 결함의 코드 근거

- 116~119행: `private fun isExpired(info: LockInfo, now: Long): Boolean = now >= info.expiresAt`. 115행 KDoc도 이 선택을 적는다: "A lock is expired from the moment [now] reaches its `expiresAt`."
- tryLock 47행: `if (existing != null && !isExpired(existing, now))`이면 entry를 유지한다. `now == expiresAt`이면 이 조건이 거짓이다. 50행 조건도 거짓이다 (`existing != null`). 그래서 54~60행이 새 `LockInfo`로 entry를 바꾸고 새 토큰을 돌려준다. entry 교체는 END다.
- monitor 126행: `if (existing != null && isExpired(existing, now)) null else existing`

### 4.2 harness 관찰

| 시나리오 | sidecar 요구 | 관찰 |
|---|---|---|
| S1.c now = expiresAt | null | `local-2` |
| S1.d A.unLock(local-1) | true | false |
| S6 monitor, 실제 clock | 경계에서 삭제 없음 | 경계 삭제 2건. 예: expiresAt = 1792851210534, clock = 1792851210534 |
| S7 timeout = Long.MAX_VALUE | null | B가 토큰을 받음 |
| S8 timeout = 0 | null | B가 토큰을 받음 |

그 밖의 23개 검사는 모두 통과했다.

### 4.3 성질별 판정

| 성질 | 결과 | 근거 |
|---|---|---|
| P1a | 성립 | 토큰은 람다 안에서 `nextToken()`(113행)으로 만든다 (52행, 56행) |
| P1a_entry | 성립 | backed 토큰은 59행의 새 entry에만 들어간다 |
| P3a(tryLock) | **위반** | R1. 47행 + 119행 |
| P3b | 성립 | unbacked 경로는 52~53행뿐이고, 50행 조건 `existing == null && rejectsNewEntry(...)` 아래에 있다 |
| progress_free | 성립 | `existing == null`이고 rej가 거짓이면 54~60행이 실행된다 |
| progress_live | **위반** | R1 |
| cap_unbacked | 성립 | 53행이 null을 돌려주므로 아무것도 넣지 않는다 |
| expired_takeover | 성립 | `now > expiresAt`이면 47행과 50행이 모두 거짓이다. 54~60행이 backed 토큰을 낸다 |
| P1 | 성립 | 76행이 토큰 일치와 CAS 성공을 요구한다. 79행이 entry를 지운다 |
| P3a(unLock) | 성립 | 상태를 바꾸는 경로는 77~79행뿐이다 |
| progress_owner | 성립 | 59행이 `isHeld = true`로 entry를 만든다. `isHeld`를 false로 바꾸는 곳은 76행 CAS뿐이고, 같은 람다가 79행에서 entry를 지운다. 그래서 map의 entry에서 CAS는 항상 성공한다 |
| P3a(sweep) | **위반** | R2. 126행 + 119행 |
| P2 | 성립 | `kotlinx.coroutines.sync.Semaphore(1, 1)`의 남은 permit은 0이다. `release`(77행)는 CAS가 성공할 때만 실행되므로 객체마다 많아야 한 번이다. 관찰 값은 release 뒤 1이었다 |
| P3c | 성립 | 2.4절. S4 경쟁 검사 통과 |

## 5. T-opus-3

판정: **defect (sidecar property)**. 깨진 성질: progress_live, P3a(tryLock), P3a(sweep). 부 결함: R3 (P3a, 모델 의존).

### 5.1 주 결함의 코드 근거

- 114~117행: `private fun isExpired(lockInfo: LockInfo, now: Long): Boolean = lockInfo.expiresAt <= now`. 113행 주석은 tryLock과 monitor가 이 규칙을 같이 쓴다고 적는다. 그래서 두 곳이 같이 틀린다.
- tryLock 49행: `existing != null && !isExpired(existing, now) -> existing`. `now == expiresAt`이면 51행도 거짓이다. 56~65행이 새 entry와 새 토큰을 만든다.
- monitor 124행: `if (existing != null && isExpired(existing, now)) null else existing`

### 5.2 harness 관찰

| 시나리오 | sidecar 요구 | 관찰 |
|---|---|---|
| S1.c now = expiresAt | null | `local-2` |
| S1.d A.unLock(local-1) | true | false |
| S6 monitor, 실제 clock | 경계에서 삭제 없음 | 경계 삭제 2건. 예: expiresAt = 1792851215869, clock = 1792851215869 |
| S7 timeout = Long.MAX_VALUE | null | B가 토큰을 받음 |
| S8 timeout = 0 | null | B가 토큰을 받음 |

그 밖의 23개 검사는 모두 통과했다.

### 5.3 성질별 판정

| 성질 | 결과 | 근거 |
|---|---|---|
| P1a | 성립 | 토큰은 람다 안에서 `nextToken()`(111행)으로 만든다 (52행, 57행) |
| P1a_entry | 성립 | backed 토큰은 59~64행의 새 entry에만 들어간다 |
| P3a(tryLock) | **위반** | R1. 49행 + 117행 |
| P3b | 성립 | unbacked 경로는 51~54행뿐이다 |
| progress_free | 성립 | `existing == null`이고 rej가 거짓이면 56~65행이 실행된다 |
| progress_live | **위반** | R1 |
| cap_unbacked | 성립 | 53행이 null을 돌려준다 |
| expired_takeover | 성립 | `now > expiresAt`이면 56~65행이 backed 토큰을 낸다 |
| P1 | 성립 | 80행이 토큰 일치와 CAS 성공을 요구한다. 83행이 entry를 지운다 |
| P3a(unLock) | 성립 | 상태를 바꾸는 경로는 81~83행뿐이다 |
| progress_owner | 성립 | T-sonnet-2와 같은 이유다. 62행 `isHeld = AtomicBoolean(true)`, CAS는 80행에만 있다 |
| P3a(sweep) | **위반** | R2. 124행 + 117행 |
| P2 | 성립 | `Semaphore(permits = 1, acquiredPermits = 1)`(60행). release는 CAS 뒤에서만 한 번 일어난다 |
| P3c | 성립 | 2.4절. S4 경쟁 검사 통과 |

## 6. T-haiku-4

판정: **defect (sidecar property)**. 깨진 성질: progress_live, P3a(tryLock), P3a(sweep). 부 결함: R3 (P3a, 모델 의존).

### 6.1 주 결함의 코드 근거

- tryLock 42행: `existing != null && existing.isHeld.get() && now < existing.expiresAt -> existing`. `now == expiresAt`이면 이 조건이 거짓이다. 44행도 거짓이다 (`existing != null`). 그래서 49~57행이 새 entry와 새 토큰을 만든다.
- monitor 93행: `if (existing != null && existing.expiresAt <= now) null else existing`
- 42행의 `isHeld.get()` 검사는 결과를 바꾸지 않는다. 54행이 entry를 `isHeld = true`로 만들고, `isHeld`를 바꾸는 코드는 없다.

### 6.2 harness 관찰

| 시나리오 | sidecar 요구 | 관찰 |
|---|---|---|
| S1.c now = expiresAt | null | `local-3` |
| S1.d A.unLock(local-1) | true | false |
| S6 monitor, 실제 clock | 경계에서 삭제 없음 | 경계 삭제 1건: expiresAt = 1792851222326, clock = 1792851222326 |
| S7 timeout = Long.MAX_VALUE | null | B가 토큰을 받음 |
| S8 timeout = 0 | null | B가 토큰을 받음 |

그 밖의 23개 검사는 모두 통과했다.

### 6.3 성질별 판정

| 성질 | 결과 | 근거 |
|---|---|---|
| P1a | 성립 | 37행에서 새 번호로 토큰을 만든다. 60행은 `granted`가 참일 때만 토큰을 돌려준다 |
| P1a_entry | 성립 | backed 토큰은 51~56행의 새 entry에만 들어간다 |
| P3a(tryLock) | **위반** | R1. 42행의 `now < existing.expiresAt` |
| P3b | 성립 | unbacked 경로는 44~47행뿐이다 |
| progress_free | 성립 | `existing == null`이고 rej가 거짓이면 49~57행이 실행된다 |
| progress_live | **위반** | R1 |
| cap_unbacked | 성립 | 46행이 null을 돌려준다 |
| expired_takeover | 성립 | `now > expiresAt`이면 42행과 44행이 모두 거짓이다. 49~57행이 backed 토큰을 낸다 |
| P1 | 성립 | 70행이 토큰 일치를 요구한다. 72행이 entry를 지운다 |
| P3a(unLock) | 성립 | 상태를 바꾸는 경로는 71~72행뿐이다 |
| progress_owner | 성립 | 70행의 조건은 null 검사와 토큰 비교뿐이다 |
| P3a(sweep) | **위반** | R2. 93행 |
| P2 | 성립 | 52행이 permit을 0으로 만든다. 이 구현은 semaphore를 release하지 않는다. permit은 항상 0이다 |
| P3c | 성립 | 2.4절. S4 경쟁 검사 통과 |

## 7. 여러 구현이 공유하는 결함

1. **비엄격 만료 경계 (4개 모두, 주 결함).** 만료를 `now >= expiresAt`로 판정한다. sidecar는 `now > expiresAt`를 요구한다. 깨지는 성질은 progress_live, P3a(tryLock), P3a(sweep)이다.
   - T-sonnet-2와 T-opus-3은 판정 함수 하나를 tryLock과 monitor가 같이 쓴다. T-haiku-1은 `LockInfo.isExpired` 하나를 같이 쓴다. T-haiku-4는 두 곳에 조건을 따로 적었지만 둘 다 비엄격이다.
   - 수정: tryLock은 `now > expiresAt`일 때만 인수하고, monitor는 `expiresAt < now`일 때만 지운다. 대조군 CTRL-strict는 T-haiku-1에서 이 한 줄만 바꿨다. 대조군은 S1, S6, S8을 통과했다.
   - SPEC.md의 "once `lockTimeoutMillis` has passed"는 경계를 정하지 않는다. sidecar가 경계를 엄격하게 정했다. 그래서 이 결함은 sidecar 성질 위반으로 분류한다.
2. **`now + lockTimeoutMillis`의 Long overflow (4개 모두, 부 결함, 모델 의존).** R3 참조. `lockTimeoutMillis` 검증이 없는 것과 같은 원인이다.

## 8. 성질 위반이 아니지만 운영에 중요한 점

| 항목 | 해당 구현 | 내용 |
|---|---|---|
| monitor가 예외 한 번에 영구 정지 | T-haiku-1, T-sonnet-2 | `scheduleAtFixedRate`(T-haiku-1 100행)와 `scheduleWithFixedDelay`(T-sonnet-2 105행)는 한 실행이 예외를 던지면 이후 실행을 모두 멈춘다 (JDK Javadoc: "Subsequent executions are suppressed."). 예외는 반환된 `ScheduledFuture`에만 남는다. 두 구현은 이 future를 저장하지 않는다. 그래서 로그 없이 멈춘다. 현재 람다는 long 비교만 하므로 예외가 날 가능성은 낮다 |
| monitor가 예외 한 번에 영구 정지 | T-opus-3 | `launch` 루프(103~108행)는 예외 한 번에 끝난다. 예외는 uncaught-exception handler로 가고, 재시작은 없다 |
| monitor가 예외 한 번에 영구 정지 | T-haiku-4 | 직접 만든 `Thread`의 `while (true)` 루프(87~100행)는 예외 한 번에 끝난다. `Thread.sleep`의 `InterruptedException`도 포함된다 |
| monitor가 멈춘 뒤의 영향 | 위 4개 | 해제되지 않은 entry가 map에 남는다. 그 key는 다음 tryLock이 인수할 때까지 남는다. 다시 쓰이지 않는 key는 영원히 남고, cap이 있으면 cap 자리를 차지한다 |
| `lockTimeoutMillis` 검증 없음 | 4개 모두 | 0이면 같은 ms의 두 번째 호출도 lock을 넘겨받는다 (S8). 음수이면 모든 lock이 즉시 만료된다. 매우 큰 값이면 overflow로 즉시 만료된다 (R3). 세 경우 모두 상호 배제가 없다 |
| bin lock 안의 로그 | 4개 모두 | `rejectsNewEntry`는 compute 람다 안에서 호출된다. 이 함수의 WARN 로그(첫 거부와 1000번째마다)는 `ConcurrentHashMap` bin lock을 잡은 채 실행된다. 빈도가 낮아서 영향은 작다 |
| semaphore를 release하지 않음 | T-haiku-4 | unLock이 semaphore를 release하지 않고 `isHeld`도 바꾸지 않는다. 이 semaphore는 아무도 기다리지 않으므로 동작 차이는 없다 |
| 실패한 tryLock도 토큰 번호를 씀 | T-haiku-1, T-haiku-4 | 토큰 번호를 compute 전에 만든다 (41행, 37행). 번호에 빈칸이 생길 뿐 성질에는 영향이 없다 |

verifier 결과와 대조할 때 참고할 사실 (성질 위반이 아님):
- sidecar는 `class LockInfo = KeyLocalLock.LockInfo`를 가리킨다.
  - T-haiku-1은 `LockInfo`를 파일 최상위 private class로 선언했다 (121행, JVM 이름 `com.linecorp.cse.reqshield.kotlin.coroutine.LockInfo`). 여기에 `isExpired` 메서드가 더 있다.
  - T-haiku-4는 `LockInfo`를 companion object 안에 선언했다 (103행, `KeyLocalLock.Companion.LockInfo`).
  - T-sonnet-2와 T-opus-3은 `KeyLocalLock` 안에 선언했다.
- sidecar는 `use jdk.Semaphore`를 쓴다. T-sonnet-2와 T-opus-3은 `kotlinx.coroutines.sync.Semaphore`를 쓴다.
- 4개 모두 compute 람다 안에서 `lockMap.size`를 읽는다. sidecar의 신뢰 모델 목록에는 `jdk.ConcurrentHashMap.mappingCount`가 있다.
- 위의 차이 때문에 verifier가 코드를 다르게 묶으면, 판정이 이 문서와 다르게 나올 수 있다.

## 9. 판정 방법과 한계

### 9.1 harness

- 위치: `aicode/judge-work/1/`. 실행: `perl -e 'alarm shift; exec @ARGV' 600 /Users/ykhfree/AI/kotlin-verifier/aicode/judge-work/1/run.sh T-haiku-1 T-sonnet-2 T-opus-3 T-haiku-4 CTRL-strict`
- 구현 파일은 바꾸지 않고 컴파일했다 (Kotlin 2.0.21, kotlinx-coroutines 1.8.1, JDK 21).
- 도우미는 stub으로 바꿨다.
  - `nowToEpochTime()`은 harness가 정하는 `TestClock.nowMs`를 돌려준다. 실제 함수는 `System.currentTimeMillis()`를 돌려준다.
  - `LocalLockLimit.rejectsNewEntry`는 LocalLockLimit.kt 86~101행의 판단 규칙을 그대로 따른다 (limit이 0이거나 `currentSize < limit`이면 false). 로그만 뺐다.
  - `ConfigValues`: `LOCK_MONITOR_INTERVAL_MILLIS = 1000`(HELPERS.md). `LOCK_KEY_PREFIX`는 임의의 값이다.
- harness는 reflection으로 `lockMap`을 읽는다. map에 쓰는 것은 각 시나리오 시작의 `clear()`뿐이다.
- tryLock 시나리오(S1~S5, S8)는 실제 clock보다 1억 ms 뒤의 clock 값을 쓴다. 그래서 실제 clock으로 도는 monitor가 시나리오 entry를 지우지 않는다.
- S6(monitor 경계)은 실제 clock을 쓰고 코드를 바꾸지 않는다.
  - 방법: expiresAt이 연속한 ms인 entry 2600개를 만든다. map을 계속 스냅샷하고, 스냅샷 직후 clock `c2`를 읽는다.
  - 증명: expiresAt `E >= c2`인 entry가 이미 없으면, monitor가 그 entry를 지울 때 읽은 `now`는 `c2` 이하이고 `E` 이상이다. 그래서 `now == E`이다. 엄격한 `>` 구현에서는 이런 증거가 나올 수 없다.
  - 전제: 실행 중(약 4초) `System.currentTimeMillis()`가 뒤로 가지 않는다.
- 대조군 CTRL-strict(T-haiku-1에서 127행의 `>=`만 `>`로 바꿈)는 S1, S6(경계 삭제 0건, 최소 간격 1 ms), S8을 통과했다. 그래서 harness는 두 규칙을 구별한다.
- 통과한 공통 검사 23개 (4개 구현 모두): S1.a, S1.b, S2.a~d(만료 인수, 오래된 토큰 해제 거부, 소유자 해제, P2), S3.a~d(sidecar probe, P2, 다른 key의 토큰, 위조 토큰), S4.a~c(16 스레드 × 300회 동시 획득, 동시 인수, 오래된 unLock과 인수의 경쟁), S5.a~e(cap 경로, unbacked 토큰의 unLock, 기존 key는 cap 미적용), 각 시나리오 뒤의 INV-E/P2 검사.
- 전체 출력: `aicode/judge-work/1/run-output.txt` (4개 구현), `aicode/judge-work/1/run-output-control.txt` (대조군. 두 번 실행했고, 두 번 모두 S7만 실패했다)

### 9.2 한계

- 동시성 검사(S4)는 예시 실행이다. 모든 끼어들기를 증명하지 않는다. 성립 판정은 2.4절의 논증에 기댄다. S4는 그 논증을 보조한다.
- S4.c에서 B가 먼저 인수한 경우는 구현마다 1~9회였다. 나머지는 A가 먼저 해제했다. 두 순서가 모두 한 번 이상 실행되었다.
- R3의 분류는 verifier의 `Long` 모델링에 달려 있다 (2.3절).
- blindness: 허용 목록 밖의 파일은 읽지 않았다. verifier, req-shield 테스트, translator는 실행하지 않았다. cross-memory는 조회하지도 기록하지도 않았다. 과거 verifier 결과가 섞이거나, 이 판정이 다른 judge에게 새는 것을 막기 위해서다.
