# KeyLocalLock AI 구현 판정 (judge 3)

- 날짜: 2026-10-24
- 대상: `/Users/ykhfree/AI/kotlin-verifier/aicode/impl/<ID>/KeyLocalLock.kt` (ID = T-opus-1, T-haiku-2, T-sonnet-3, T-opus-4)
- 기준: `specs/reqshield/keylocallock.common.kv`의 성질(sidecar), SPEC.md, KeyLock.kt의 KDoc
- 증거: 코드 읽기와 실행 재현. 재현 코드와 출력은 `/Users/ykhfree/AI/kotlin-verifier/aicode/judge-work/3/`에 있다 (8절).

## 1. 판정 표

| ID | 판정 | 깨진 성질 | 한 줄 이유 |
|---|---|---|---|
| T-opus-1 | defect (sidecar property) | P3a (tryLock, sweep), progress_live | 만료 판정이 `now >= expiresAt`이다. 그래서 `now == expiresAt`인 1 ms에 tryLock이 아직 유효한 lease를 빼앗고, monitor도 그 entry를 지운다. |
| T-haiku-2 | defect (sidecar property) | P3a (tryLock, sweep), progress_live | T-opus-1과 같은 경계 결함이다. tryLock은 `existing.expiresAt > now`일 때만 살아 있다고 보고, sweep은 `expiresAt <= now`이면 지운다. |
| T-sonnet-3 | defect (sidecar property) | P3a (tryLock, unLock, sweep), progress_live, progress_owner | 같은 경계 결함이 있다. 또 unLock은 만료됐지만 아직 map에 있는 자기 entry를 지우면서 `false`를 돌려준다. 이 창은 만료 시각부터 다음 monitor 실행이나 다음 takeover까지(최대 약 1 s) 열려 있다. |
| T-opus-4 | defect (sidecar property) | P3a (tryLock, sweep), progress_live | 같은 경계 결함이다. `isExpired`가 `info.expiresAt <= now`이다. |

- 가장 심각한 결함은 T-sonnet-3의 unLock 결함이다. 이 결함은 만료 뒤 최대 약 1 s 길이의 창에서 생긴다.
- 나머지 결함은 모두 `now == expiresAt`인 1 ms 경계에서만 생긴다.
- `now < expiresAt`와 `now > expiresAt`에서는 네 구현 모두 sidecar대로 동작한다 (재현 S2의 `-1` 대조, S3, S4).

## 2. 성질별 결과

✓ = 모든 실행에서 성립한다. ✗ = 깨진다 (근거는 4절). "경계" = `now == expiresAt`인 경우에만 깨진다.

| 성질 | T-opus-1 | T-haiku-2 | T-sonnet-3 | T-opus-4 |
|---|---|---|---|---|
| P1 (unLock) | ✓ | ✓ | ✓ | ✓ |
| P1a | ✓ | ✓ (주 1) | ✓ | ✓ |
| P1a_entry | ✓ | ✓ | ✓ | ✓ |
| P2 | ✓ | ✓ | ✓ | ✓ |
| P3a (tryLock) | ✗ 경계 | ✗ 경계 | ✗ 경계 | ✗ 경계 |
| P3a (unLock) | ✓ | ✓ | ✗ | ✓ |
| P3a (sweep) | ✗ 경계 | ✗ 경계 | ✗ 경계 | ✗ 경계 |
| P3b | ✓ | ✓ | ✓ | ✓ |
| P3c | ✓ | ✓ | ✓ | ✓ |
| progress_free | ✓ | ✓ | ✓ | ✓ |
| progress_live | ✗ 경계 | ✗ 경계 | ✗ 경계 | ✗ 경계 |
| progress_owner | ✓ | ✓ | ✗ | ✓ |
| cap_unbacked | ✓ | ✓ | ✓ | ✓ |
| expired_takeover | ✓ | ✓ | ✓ | ✓ |

주 1: T-haiku-2는 토큰 번호를 compute 밖에서 받는다. 번호는 호출마다 하나뿐이므로 실행에서는 P1a가 성립한다. 검증기는 이것을 증명하지 못할 수 있다 (7절).

## 3. 판정에 쓴 의미

1. tryLock, unLock, sweep은 각각 `lockMap.compute(key)` 한 번이다. 이 compute 한 번이 원자 action 하나다. `ConcurrentHashMap.compute`는 key마다 원자적이고, 함수를 호출당 정확히 한 번 실행한다.
2. 네 구현 모두 `LockInfo` 필드를 그 key의 compute 안에서만 읽고 쓴다. compute 밖에서 `lockMap`을 건드리는 코드는 monitor의 `lockMap.keys` 순회뿐이다. T-haiku-2는 토큰 번호도 compute 밖에서 올린다.
3. ISSUE: tryLock이 null이 아닌 토큰 `res`를 돌려주면 `tok(res)`가 발급된다. 이때 `gLease = now + timeout`이다. `gBacked`는 사후 상태에서 key의 entry가 `res`를 가지는지다 (sidecar 60행, DESIGN 3.5).
4. END (witness `LockInfo.token`): 사전 상태의 entry가 가진 토큰 `t0`를 사후 entry가 더 이상 갖지 않으면, `t0`의 acquisition이 끝난다. entry 삭제, 교체, 토큰 덮어쓰기가 모두 해당한다.
5. `ends_only_if`는 그 action에서 끝나는 held backed 토큰 `qt`마다 조건을 요구한다.
   - tryLock(65행)과 sweep(87행): `now > old(tok(qt).gLease)`. 엄격한 `>`이다.
   - unLock(80행): `qt == t && res`.
6. 네 구현 모두 entry를 만들 때 `expiresAt = now + lockTimeoutMillis`로 둔다. 그 뒤 `expiresAt`를 다른 값으로 바꾸지 않는다. 그래서 entry의 `expiresAt`는 그 토큰의 `gLease`와 같다. 따라서 "`now > gLease`"는 "`now > expiresAt`"과 같다.
7. sidecar는 경계를 한 가지로 정한다.
   - `now <= expiresAt`이고 entry가 held이면 tryLock은 `null`이어야 한다 (progress_live, 70행).
   - `now > expiresAt`이면 tryLock은 backed 토큰을 받아야 한다 (expired_takeover, 72행).
   - `now == expiresAt`에서 entry를 끝내면 P3a가 깨진다.

## 4. 구현별 근거

### 4.1 T-opus-1

코드 위치:
- tryLock 73행: `if (existing != null && existing.isHeld.get() && now < existing.expiresAt) {`. 참이면 75행이 entry를 그대로 둔다.
- 거짓이면 77행이 새 토큰을 만든다. 79행 `existing == null && LocalLockLimit.rejectsNewEntry(lockMap.mappingCount())`가 참이면 81행이 `null`을 돌려준다 (entry 없음).
- 79행이 거짓이면 84-86행이 `LockInfo(semaphore, now + lockTimeoutMillis, AtomicBoolean(true), token)`를 넣는다.
- unLock 102행: `existing != null && existing.token == token && existing.isHeld.compareAndSet(true, false)`. 참이면 103-106행이 release, `token = null`, `released = true`, entry 삭제를 한다.
- monitor 46행: `existing != null && existing.expiresAt <= now`이면 entry를 지운다.

결함 A: P3a (tryLock), progress_live
1. 초기 상태: map이 비어 있다. `LocalLockLimit.maxEntries = 0`이다. `lock = KeyLocalLock(100)`.
2. 스레드 A가 `tryLock("k", CREATE)`를 부른다. `nowToEpochTime() = 1000`이다. 결과는 `"local-1"`이다. entry는 `{token=local-1, expiresAt=1100, isHeld=true, permits=0}`이다. `gLease(local-1) = 1100`이다.
3. 스레드 B가 `tryLock("k", CREATE)`를 부른다. `nowToEpochTime() = 1100`이다. 73행의 `1100 < 1100`이 거짓이다. 86행이 entry를 `{token=local-2, expiresAt=1200}`으로 바꾼다. 결과는 `"local-2"`이다.
4. 3단계에서 `local-1`이 끝난다 (END). P3a는 `1100 > gLease(local-1) = 1100`을 요구한다. 이 식은 거짓이다.
5. progress_live의 전제 `old(has) && old(isHeld) && 1100 <= 1100`은 참이다. 그런데 `res = "local-2"`이다.
6. 그 뒤 A의 `unLock("k", CREATE, "local-1")`은 `false`다. A는 lease의 마지막 1 ms에 lock을 잃었다.

재현 S2: 시계가 `expiresAt = 1792851218087`인 ms에 두 번째 tryLock을 불렀다. 결과는 `local-10`이었다. 대조로 `expiresAt - 1`인 ms에서는 `null`이었다.

결함 B: P3a (sweep)
1. 결함 A의 1-2단계와 같다.
2. monitor가 key "k"에서 `System.currentTimeMillis() = 1100`을 읽는다. 46행의 `1100 <= 1100`이 참이다. compute가 entry를 지운다.
3. `local-1`이 `now = 1100 = gLease`에서 끝난다. P3a (sweep)는 `now > gLease`를 요구한다.

재현 (블랙박스, MonitorBoundary): T-opus-1의 monitor 본문은 `init` 안의 람다라서 직접 부를 수 없다. 그래서 배경 monitor의 실행 시각 근처에 `expiresAt`이 1 ms씩 다른 entry 29개를 놓았다. 세 번 모두 `expiresAt = P`인 entry가 시계가 `P`일 때 이미 사라져 있었다 (`removedBy - expiresAt = 0`). 그 entry는 다른 호출이 건드리지 않았다. 그래서 monitor가 `now <= gLease`에서 지운 것이다. `expiresAt = P + 1`인 entry는 다음 실행(약 1 s 뒤)까지 남았다.

성립하는 성질:
- P1a: 토큰은 compute 안 77행의 `incrementAndGet()`에서 나온다. 번호마다 한 호출만 받는다. 토큰은 78행과 90행으로만 나간다.
- P1a_entry: backed 토큰은 86행의 entry가 가진다. 이것은 gBacked의 정의(사후 상태에서 carried)와 같다.
- P3b, cap_unbacked: 토큰을 저장하지 않고 돌려주는 길은 79-81행뿐이다. 이 길은 `existing == null`이고 `rej`가 참일 때만 열린다. compute가 없는 key에 `null`을 돌려주므로 entry가 생기지 않는다. 재현 S0: `tryLock#1=local-3 entryAfter=absent`, 두 번째 호출도 `local-4`를 받았다.
- progress_free: `existing == null`이면 73행이 거짓이다. `rej`가 거짓이면 86행이 entry를 넣는다.
- expired_takeover: `now > expiresAt`이면 73행이 거짓이다. 79행은 `existing != null`이라 cap을 묻지 않는다. 86행이 새 토큰의 entry를 넣는다. 재현 S0(cap 상태에서 만료된 key C)와 S3.
- P1: 102행은 entry의 토큰이 `t`와 같을 때만 `true`다. 같은 compute가 entry를 지운다 (106행). map의 entry 토큰은 항상 이 key로 발급된 held backed 토큰이다.
- P3a (unLock): unLock이 entry를 바꾸는 길은 102-106행뿐이다. 이 길은 `t`를 가진 entry를 지우고 `res = true`를 만든다.
- progress_owner: map에 있는 entry의 `isHeld`는 항상 `true`다. 86행이 `true`로 만들고, `false`로 바꾸는 102행의 CAS는 같은 compute에서 entry를 지운다. 그래서 토큰이 같으면 CAS가 성공한다. 재현 S4: 만료 뒤에도 `result=true`.
- P2: 84-85행이 `Semaphore(1)`을 만들고 바로 `tryAcquire()`한다 (permits 0). release는 102행 CAS 성공 뒤 한 번뿐이다 (permits 1). kotlinx `Semaphore.release()`는 초과 release에서 `IllegalStateException`을 던진다 (`judge-work/3/check/SemCheck.kt`로 확인). 그러나 CAS가 두 번째 release를 막는다.
- P3c: key마다 entry가 하나이고, entry는 토큰 하나를 가진다. entry가 토큰을 잃으면 END가 그 토큰을 끝낸다. 그래서 한 key에 held backed 토큰은 최대 하나다. 재현 S6: 8 스레드 × 20000회 tryLock에서 `maxConcurrentHolders=1`, `staleTokenReleases=0`.

### 4.2 T-haiku-2

코드 위치:
- tryLock 45행: `val newToken = "local-${tokenSequence.incrementAndGet()}"`. compute 밖이다.
- 49행: `existing != null && existing.expiresAt > now -> existing`.
- 50-54행: `existing == null && LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())`이면 `grantedToken.set(newToken)` 뒤 `null`.
- 55-65행: 그 밖이면 57행이 `grantedToken.set(newToken)`을 하고, 58-64행이 새 `LockInfo`를 돌려준다. 인자는 `semaphore = Semaphore(0)`, `expiresAt = now + lockTimeoutMillis`, `isHeld = AtomicBoolean(true)`, `token = newToken`이다.
- unLock 78-80행: `existing != null && existing.token == token`이면 `released.set(true)` 뒤 `null`.
- sweep 107행: `if (existing != null && existing.expiresAt <= now) null else existing`.

결함 A: P3a (tryLock), progress_live
1. 초기 상태와 스레드 A는 4.1 결함 A의 1-2단계와 같다 (`"local-1"`, `expiresAt = 1100`).
2. 스레드 B가 `nowToEpochTime() = 1100`으로 tryLock을 부른다. 49행 `1100 > 1100`이 거짓이다. 50행은 `existing != null`이라 거짓이다. 55-65행이 entry를 `local-2`로 바꾼다. 결과는 `"local-2"`이다.
3. P3a와 progress_live가 4.1과 같은 이유로 깨진다.

재현 S2: `now == expiresAt == 1792851221587`에서 두 번째 tryLock이 `local-13`을 받았다. `expiresAt - 1`에서는 `null`이었다.

결함 B: P3a (sweep)
- 107행은 `now == expiresAt`에서 entry를 지운다.
- 재현 S5 (화이트박스): reflection으로 `removeExpiredEntries()`를 한 번 불렀다. 시계가 `expiresAt`이면 entry가 사라졌다 (`entry after=absent`). `expiresAt - 1`이면 entry가 남았다.
- 재현 (블랙박스): 세 번 모두 `removedBy - expiresAt = 0`인 entry가 하나씩 있었다.

성립하는 성질:
- P1a: 45행의 번호는 호출마다 고유하다. 토큰은 52행이나 57행에서 넣은 값만 68행으로 나간다. 그래서 돌려준 토큰은 그 전에 발급된 적이 없다.
- P2: 60행 `Semaphore(0)`이다. 아무 곳에서도 release하지 않는다. permits는 항상 0이다.
- P1, P3a (unLock), progress_owner: 78-80행은 토큰이 같을 때만 entry를 지우고 `true`를 돌려준다. 만료 여부를 보지 않는다. 재현 S4: 만료 뒤에도 `result=true`.
- P3b, cap_unbacked: 50-54행만 unbacked 토큰을 낸다. 재현 S0.
- progress_free, expired_takeover: `existing == null`이고 `rej`가 거짓이면 55-65행이 entry를 넣는다. `now > expiresAt`이면 49행과 50행이 모두 거짓이므로 55-65행이 takeover한다. 재현 S0, S3.
- P1a_entry, P3c: 4.1과 같은 이유다. 재현 S6: `maxConcurrentHolders=1`, `staleTokenReleases=0`.

### 4.3 T-sonnet-3

코드 위치:
- tryLock 45행: `val heldByOther = existing != null && existing.isHeld.get() && now < existing.expiresAt`.
- 49행이 compute 안에서 새 토큰을 만든다. 51-53행이 cap 길이다 (`existing == null && LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())`이면 `null`).
- 55-57행: java `Semaphore(1)`, `tryAcquire()`, `LockInfo(semaphore, now + lockTimeoutMillis, AtomicBoolean(true), newToken)`.
- unLock 70행: `val now = nowToEpochTime()`.
- unLock 73행: `if (existing != null && existing.token == token) {`.
- unLock 75행: `released.set(existing.isHeld.get() && now < existing.expiresAt)`.
- unLock 76-78행: `isHeld.set(false)`, `semaphore.release()`, `null` (entry 삭제).
- sweep 112행: `if (existing != null && now >= existing.expiresAt) null else existing`.

결함 C (가장 심각): P3a (unLock), progress_owner
1. 초기 상태: map이 비어 있다. `LocalLockLimit.maxEntries = 0`이다. `lock = KeyLocalLock(100)`.
2. 스레드 A가 `tryLock("k", CREATE)`를 부른다. 시계는 1000이다. 결과는 `"local-1"`이다. entry는 `{token=local-1, expiresAt=1100}`이다.
3. 다른 tryLock과 monitor는 아직 이 key를 건드리지 않았다. monitor는 1 s마다 돌기 때문에, 만료된 entry가 최대 약 1 s 동안 남을 수 있다.
4. 스레드 A가 `unLock("k", CREATE, "local-1")`을 부른다. 시계는 1150이다 (1100이어도 같다).
5. 73행이 참이다. 75행이 `released = (true && 1150 < 1100) = false`로 둔다. 76-78행이 entry를 지운다. 결과는 `false`다.
6. progress_owner: `old(has(S, k)) && old(S.m[k].token) == "local-1"`이 참이다. 그래서 `res`가 참이어야 한다. 결과는 `false`다.
7. P3a (unLock): `local-1`이 끝났다 (entry 삭제). 그런데 `res = false`이다. 조건 `qt == t && res`가 거짓이다.

재현:
- S4 (`now > expiresAt`, entry 있음): `result=false, entry after=absent`.
- S4b (`now == expiresAt`): `result=false, entry after=absent`.
- 다른 세 구현은 같은 시나리오에서 `result=true`를 돌려준다.

자연어 관점: KeyLock.kt 28행은 "Releases only if [token] matches the current owner; false when not held or token mismatch."이다.
- 만료된 lock을 "not held"로 읽으면 `false`는 맞다. 그러나 그때는 "current owner"가 없으므로 entry를 지우는 것도 맞지 않다.
- 만료된 lock을 아직 "held"로 읽으면 `true`를 돌려줘야 한다.
- 어느 읽기에서도 "지우고 `false`"는 KDoc과 잘 맞지 않는다. 다만 KDoc만으로는 확정하기 어렵다. 판정 근거는 sidecar의 progress_owner(82행)와 P3a(80행)이다.

결함 A: P3a (tryLock), progress_live
- 45행의 `now < existing.expiresAt` 때문에 `now == expiresAt`에서 takeover한다. 시나리오는 4.1 결함 A와 같다.
- 재현 S2: `now == expiresAt == 1792851224938`에서 두 번째 tryLock이 `local-10`을 받았다. `expiresAt - 1`에서는 `null`이었다.

결함 B: P3a (sweep)
- 112행 `now >= existing.expiresAt` 때문에 `now == expiresAt`에서 entry를 지운다.
- 재현 S5 (화이트박스 `removeExpiredLocks()`): 시계가 `expiresAt`이면 `absent`, `expiresAt - 1`이면 entry가 남았다.
- 재현 (블랙박스): 세 번 모두 `removedBy - expiresAt = 0`인 entry가 하나씩 있었다.

성립하는 성질:
- P1: `true`는 75행에서만 나온다. 그때 토큰이 같고 같은 compute가 entry를 지운다 (78행).
- P1a, P1a_entry: 토큰은 compute 안 49행에서 나온다. backed 토큰은 57행의 entry가 가진다.
- P2: 55-56행이 java `Semaphore(1)`을 만들고 `tryAcquire()`한다 (permits 0). 77행 release는 같은 compute가 entry를 지우는 길에서만 실행된다. 그래서 `LockInfo`마다 최대 한 번이다 (permits ≤ 1).
- P3b, cap_unbacked: 51-53행만 unbacked 토큰을 낸다. 재현 S0.
- progress_free, expired_takeover: `existing == null`이면 45행이 거짓이다. `now > expiresAt`이면 45행이 거짓이고 51행은 `existing != null`이라 거짓이다. 두 경우 모두 57행이 entry를 넣는다. 재현 S0, S3.
- P3c: 4.1과 같은 이유다. 재현 S6: `maxConcurrentHolders=1`, `staleTokenReleases=0`.

### 4.4 T-opus-4

코드 위치:
- tryLock 49-52행: `existing == null && LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())`이면 `acquiredToken.set(newToken())` 뒤 `null`.
- 55-60행: 56행 `existing == null || isExpired(existing, now)`이 참이면 57행의 새 `LockInfo(Semaphore(1), now + lockTimeoutMillis)`를, 거짓이면 59행의 `existing`을 `entry`로 둔다.
- 61-67행: `if (entry.semaphore.tryAcquire())`이면 새 토큰을 만들고 `isHeld = true`, `token`, `expiresAt = now + lockTimeoutMillis`를 넣는다. 68행이 `entry`를 돌려준다.
- 117-120행: `isExpired`는 `info.expiresAt <= now`이다.
- unLock 81-86행: T-opus-1과 같은 CAS 길이다.
- sweep 126행: `if (info != null && isExpired(info, now)) null else info`.

결함 A: P3a (tryLock), progress_live
1. 초기 상태와 스레드 A는 4.1 결함 A의 1-2단계와 같다.
2. 스레드 B가 시계 1100으로 tryLock을 부른다. 120행의 `1100 <= 1100`이 참이다. 57행이 새 `LockInfo`를 만든다. 61행의 `tryAcquire()`가 새 semaphore에서 성공한다. 결과는 `"local-2"`이다.
3. P3a와 progress_live가 4.1과 같은 이유로 깨진다.

재현 S2: `now == expiresAt == 1792851228324`에서 두 번째 tryLock이 `local-10`을 받았다. `expiresAt - 1`에서는 `null`이었다.

결함 B: P3a (sweep)
- 126행이 120행의 `<=`를 쓴다.
- 재현 S5 (화이트박스 `removeExpiredEntries()`): 시계가 `expiresAt`이면 `absent`, `expiresAt - 1`이면 entry가 남았다.
- 재현 (블랙박스): 세 번 모두 `removedBy - expiresAt = 0`인 entry가 하나씩 있었다.

성립하는 성질:
- 살아 있는 entry의 길: 56행이 거짓이면 `entry = existing`이다. existing의 semaphore는 permits가 0이다. 만들 때 61행이 permit을 가져갔고, release는 83행에서 entry를 지우기 직전에만 한다. 그래서 61행이 실패하고, entry는 바뀌지 않고, 결과는 `null`이다.
- P1a: 토큰은 compute 안의 `newToken()`(115행)에서 나온다.
- P2: kotlinx `Semaphore(1)`이다. 새 객체에서 `tryAcquire()`를 한 번 하고, release는 81행 CAS 성공 뒤 83행에서 한 번만 한다.
- P3b, cap_unbacked: 49-52행만 unbacked 토큰을 낸다. 재현 S0.
- progress_free, expired_takeover: `existing == null`이고 `rej`가 거짓이면, 또는 `now > expiresAt`이면, 57행의 새 `LockInfo`에서 61행이 성공하고 토큰을 넣는다. 재현 S0, S3.
- P1, P3a (unLock), progress_owner: 4.1과 같은 이유다 (81-86행). 재현 S4: 만료 뒤에도 `result=true`.
- P1a_entry, P3c: 4.1과 같은 이유다. 재현 S6: `maxConcurrentHolders=1`, `staleTokenReleases=0`.

## 5. 여러 구현이 공유하는 결함

1. 만료 경계 (네 구현 모두): tryLock과 monitor가 `now >= expiresAt`을 만료로 본다. sidecar는 `now > expiresAt`만 만료로 본다 (65, 70, 72, 87행). 그래서 네 구현 모두 `now == expiresAt`인 1 ms에 아직 유효한 lease를 끝낸다.
   - SPEC.md 16-17행 "once `lockTimeoutMillis` has passed"는 경계를 정하지 않는다. 그래서 이 결함은 자연어 문장 위반이 아니라 sidecar 성질 위반이다.
   - 실제 영향: lease의 마지막 1 ms에 두 holder가 겹칠 수 있다. 앞 holder의 늦은 unLock은 여전히 `false`이므로, 다음 holder의 lock을 풀지는 못한다.
   - `lockTimeoutMillis = 0`이면 같은 ms 안의 두 번째 tryLock도 토큰을 받는다 (재현 S9, 네 구현 모두 `t2 != null`). 엄격한 `>`였다면 같은 ms 안에서는 `null`이다.
2. 다른 공유 결함은 찾지 못했다.
   - unLock 결함(P3a unLock, progress_owner)은 T-sonnet-3에만 있다.
   - cap 길, stale 토큰, 위조 토큰, 동시 holder는 네 구현 모두 sidecar대로 동작한다 (재현 S0, S3, S6, S7).
   - map 안에서 토큰을 잃는 entry는 없다. 토큰을 지우거나 `isHeld`를 내리는 코드는 모두 같은 compute에서 entry도 지운다.

## 6. 성질 위반은 아니지만 운영에 중요한 점

1. 네 구현 모두 `lockTimeoutMillis <= 0`을 거부하지 않는다.
   - 음수이면 모든 entry가 만들어지자마자 만료된다. 그래서 배제가 전혀 없다. sidecar는 이것을 허용한다 (`now > now + timeout`이 참).
   - 0이면 5절의 경계 결함 때문에 같은 ms 안에서도 배제가 없다.
2. `now + lockTimeoutMillis`는 `Long` 덧셈이다 (네 구현 모두). `lockTimeoutMillis`가 `Long.MAX_VALUE - now`보다 크면 값이 넘쳐 음수가 된다. 그러면 lock이 즉시 만료된다. `gLease`를 수학 정수로 보면 P3a 위반이고, `Long` 값으로 보면 위반이 아니다. 이 판정에서는 결함으로 세지 않았다.
3. monitor에 예외 처리가 없다.
   - T-opus-1, T-haiku-2, T-opus-4의 coroutine은 예외 하나로 영구히 멈춘다.
   - T-sonnet-3의 `scheduleAtFixedRate`는 예외가 나면 다음 실행을 조용히 모두 건너뛴다.
   - 지금 compute 람다에는 현실적인 예외 원인이 없다. 그래서 위험은 낮다.
4. coroutine monitor(T-opus-1, T-haiku-2, T-opus-4)는 `Dispatchers.Default`에서 돈다.
   - CPU를 다 쓰는 애플리케이션에서는 monitor가 늦어질 수 있다.
   - sweep 뒤에 `delay(1000)`을 두므로 주기가 1 s보다 조금 길다 (측정: 약 1001-1011 ms).
   - T-sonnet-3는 전용 daemon thread와 고정 주기(`scheduleAtFixedRate`)를 쓴다 (측정: 1000 ms 간격).
5. T-sonnet-3: 만료 뒤 unLock이 entry를 지우면서 `false`를 돌려준다. 호출자가 `false`를 "해제 실패"로 기록하거나 다시 시도하면, 그 신호는 틀린 신호다.
6. T-haiku-2:
   - 모든 tryLock 호출이 토큰 번호 하나를 쓴다. 실패한 호출도 쓴다. stress 뒤 번호가 `local-160023`까지 갔다. 기능 문제는 아니다.
   - unLock은 `isHeld`와 semaphore를 그대로 둔다. 같은 compute에서 entry를 지우므로 영향은 없다.
7. 수정 제안 (판정과 별개): 경계 결함은 tryLock의 "살아 있음" 판정을 `now <= expiresAt`으로, takeover와 sweep 조건을 `now > expiresAt`으로 바꾸면 없어진다. T-sonnet-3의 unLock 결함은 75행을 `released.set(true)`로 바꾸면 없어진다. 73행이 이미 토큰을 확인하기 때문이다.

## 7. 검증기 결과와 비교할 때 볼 점 (판정과 별개)

1. T-opus-1과 T-opus-4는 `kotlinx.coroutines.sync.Semaphore`를 쓴다. sidecar의 신뢰 모델은 `use jdk.Semaphore`이다.
   - 여기서는 실행 의미가 같다. compute 안에서 `tryAcquire()`와 `release()`만 쓰기 때문이다.
   - 도구가 kotlinx Semaphore를 모델링하지 않으면 성질 판정 대신 unsupported가 나올 수 있다.
2. T-haiku-2는 토큰 번호를 compute 밖(45행)에서 받는다.
   - 실행에서는 번호가 호출마다 고유하므로 P1a가 성립한다.
   - 도구에 "compute 밖에서 받은 번호는 아직 발급되지 않았다"를 말하는 불변식이 없으면, P1a의 `!old(tok(res).gIssued)`를 증명하지 못할 수 있다. 그것은 구현 결함이 아니다.
3. T-sonnet-3의 unLock은 시계를 읽는다 (70행). sidecar의 unLock에는 `free now`가 없다. 도구가 이 값을 임의 값으로 다루면, probe의 `assert ok`도 실패할 수 있다. 이것도 결함 C와 같은 원인이다.
4. T-opus-4는 compute 안에서 companion 함수 `newToken()`과 `isExpired()`를 부른다. 도구가 이 호출을 펼치지 못하면 unsupported가 나올 수 있다.
5. 경계 결함은 `now` 값 하나(`now == expiresAt`)에서만 반례가 나온다. 검증기가 tryLock의 P3a와 progress_live, sweep의 P3a를 네 구현 모두에서 거부하면 이 판정과 맞는다.

## 8. 방법, 범위, 재현

읽은 자료:
- 명세 packet: `scratchpad/aicode/sandboxes/T-opus-1/`의 SPEC.md, KeyLock.kt, HELPERS.md, RULES.md. 같은 폴더의 compile.sh와 KeyLocalLock.kt는 열지 않았다.
- sidecar: `specs/reqshield/keylocallock.common.kv`, `keylocallock.core.kv`, `keylocallock.coroutine.kv`.
- DESIGN-v0.md: 3.5, 4.4, 4.6절.
- helper 원본: `support/.../utils/TimeUtils.kt`, `support/.../config/LocalLockLimit.kt`.
- 네 구현의 `KeyLocalLock.kt`. 같은 폴더의 report.md와 prompt.txt는 열지 않았다.

허용 범위를 넘은 읽기 (공개):
- DESIGN-v0.md를 출력할 때 3.1-3.6절(412-511행)과 4.4-4.6절(637-811행)을 한 번에 출력했다. 허용 범위(3.5, 4.4, 4.6)보다 넓다.
- 3.1-3.2절에는 원래 코드의 조각(`if (now > existing.expiresAt && existing.isHeld.compareAndSet(true, false)) {`)과 행 번호가 있다.
- 판정은 이 조각에 기대지 않는다. 경계 판정의 근거는 sidecar 65, 70, 72, 87행이다.

하지 않은 것:
- 검증기(`kv`), req-shield 테스트, translator를 실행하지 않았다.
- results.jsonl, logs/, 다른 판정자의 judge-work, mutation/, 원래 KeyLocalLock.kt를 읽지 않았다.
- cross-memory는 query와 record를 하지 않았다. 다른 판정자의 blindness를 지키기 위해서다.

재현 방법:
1. 화이트박스 시나리오 S0-S10을 실행한다: `perl -e 'alarm shift; exec @ARGV' 600 /Users/ykhfree/AI/kotlin-verifier/aicode/judge-work/3/run.sh [ID ...]`. 출력은 `judge-work/3/run-output.txt`에 있다.
2. 블랙박스 monitor 경계 검사를 실행한다: `MAIN=com.linecorp.cse.reqshield.kotlin.coroutine.MonitorBoundaryKt perl -e 'alarm shift; exec @ARGV' 600 /Users/ykhfree/AI/kotlin-verifier/aicode/judge-work/3/run.sh [ID ...]`. 출력은 `judge-work/3/monitor-boundary-output.txt`에 있다.

재현 환경:
- Kotlin 2.0.21 (compiler-embeddable), kotlinx-coroutines-core-jvm 1.8.1, slf4j-api 1.7.36, JDK 21 (Corretto 21.0.10).
- TimeUtils.kt와 LocalLockLimit.kt는 req-shield 원본을 그대로 복사했다. KeyLock.kt는 packet의 파일이다.
- `ConfigValues`는 stub이다: `LOCK_MONITOR_INTERVAL_MILLIS = 1000L`, `UNLIMITED_LOCK_ENTRIES = 0L`, `MAX_LOCK_ENTRIES_PROPERTY = "req-shield.lock.max-entries"`. `LOCK_KEY_PREFIX`는 packet에 값이 없어서 임의 문자열을 썼다. 이 값은 lock 동작에 영향이 없다.
- harness는 reflection으로 `lockMap`과 `LockInfo` 필드를 읽기만 한다.
- 경계 시나리오는 시계가 정확히 목표 ms일 때 호출한다. 호출 전후의 시계가 모두 그 ms일 때만 결과로 센다. 그래서 구현 안의 `now`도 그 ms다.

시나리오 목록:

| ID | 내용 |
|---|---|
| S0 | cap: 빈 map에 2개를 잠근 뒤 `maxEntries = 2`. 새 key, 살아 있는 key, 만료된 key에 tryLock |
| S1 | sidecar probe: tryLock, tryLock, unLock, unLock |
| S2 | 두 번째 tryLock을 `now == expiresAt - 1`과 `now == expiresAt`에서 부름 |
| S3 | `now > expiresAt`에서 takeover, 이어서 옛 토큰으로 unLock |
| S4, S4b | 만료 뒤(`now > expiresAt`, `now == expiresAt`) 주인 토큰으로 unLock, entry는 아직 map에 있음 |
| S5 | sweep 함수를 `now == expiresAt - 1`과 `now == expiresAt`에서 한 번 실행 (T-opus-1은 함수가 없어 블랙박스로 대신함) |
| S6 | 8 스레드 × 20000회 경쟁, 동시 holder 수와 stale 토큰 해제 횟수를 셈 |
| S7 | 위조 토큰과 다른 lock type 토큰으로 unLock |
| S9 | `lockTimeoutMillis = 0`, 같은 ms 안의 tryLock 두 번 |
| S10 | 배경 monitor가 해제되지 않은 entry를 지우는지 확인 (네 구현 모두 244-351 ms 안에 지움) |
| MonitorBoundary | 배경 monitor 실행 시각 근처에 `expiresAt`이 1 ms씩 다른 entry 29개를 두고, 사라진 시각의 상한을 잼 (3회) |
