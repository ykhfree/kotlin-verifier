## 결과

- 파일: `/private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/aicode/sandboxes/T-opus-3/KeyLocalLock.kt`
- **최종 파일은 컴파일됩니다.** 진단 메시지(경고 포함)는 0건입니다. `compile.sh`는 10회 중 3회 썼습니다.
  - 1회차: 첫 작성본이 컴파일됨.
  - 2회차, 3회차: 클래스 KDoc 문구만 고친 뒤 다시 확인함.
- `compile.sh`는 컴파일만 합니다. 동작 테스트와 검증은 하지 않았습니다.

## 구현 요약 (RULES.md 준수)

- §1: companion object에 `lockMap`과 `tokenSequence`를 둡니다. 토큰은 `"local-${tokenSequence.incrementAndGet()}"`입니다.
- §2: `LockInfo`를 규칙의 형태 그대로 중첩 `private class`로 선언했습니다.
- §3: `tryLock`과 `unLock`은 각각 `lockMap.compute(...) { _, existing -> ... }`를 정확히 한 번만 호출합니다. 결과는 호출 전에 선언한 `AtomicReference<String?>`나 `AtomicBoolean`으로 내보냅니다. `lockMap.size` 읽기도 람다 안에서 합니다.
- §4: `tryLock`은 compute 전에 `val now = nowToEpochTime()`를 읽습니다.
- §5: 모니터는 키마다 `val now = System.currentTimeMillis()`를 읽고 `compute`를 한 번 호출합니다.
- §6: 다른 CHM 변경 메서드는 쓰지 않았습니다. compute 람다 안에는 synchronized와 try/catch가 없습니다.

## 명세가 정하지 않아 내가 결정한 사항

1. **맵 키:** `val lockKey = lockKeyOf(key, lockType)`를 만들어 `lockMap.compute(lockKey)`를 호출합니다. RULES §3에는 `compute(key)`라고 적혀 있습니다. 하지만 원시 `key`를 쓰면 CREATE와 UPDATE가 한 엔트리로 합쳐집니다. 이는 SPEC의 "(cache key, lock type)마다 엔트리 하나"와 어긋납니다. 그래서 이름과 다르게 했습니다. 이 맵에는 캐시 엔트리가 없지만, 키 형식을 하나로 유지하려고 `LOCK_KEY_PREFIX`가 붙은 `lockKeyOf`를 그대로 썼습니다.
2. **만료된 엔트리 인수:** 만료됐지만 아직 모니터가 지우지 않은 엔트리가 있으면, `tryLock`은 그 엔트리를 비어 있는 것으로 봅니다. 그 자리에 새 엔트리와 새 토큰을 넣습니다. 그래서 락의 생존성이 1초 주기 모니터에 의존하지 않습니다. 이전 보유자의 `unLock`은 토큰 불일치로 `false`를 받습니다. 모니터가 지울 때까지 기다리는 방식도 명세와 충돌하지 않지만 택하지 않았습니다.
3. **만료 경계:** `expiresAt <= now`이면 만료로 봅니다. `tryLock`과 모니터는 같은 헬퍼 `isExpired(lockInfo, now)`를 씁니다.
4. **만료 후 본인 해제:** 만료됐지만 아직 지워지지 않은 엔트리라도 토큰이 맞으면 `unLock`이 성공합니다(`true`를 반환하고 엔트리를 지움). 만료 여부는 검사하지 않습니다.
5. **맵이 가득 찬 경우:** 새 키(`existing == null`)일 때만 `LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())`를 호출합니다. 거부되면 토큰을 발급하지만 엔트리는 넣지 않습니다. 기존 키와 만료 인수는 cap 검사를 하지 않습니다. 그래서 거부 카운터(WARN 로그 주기)가 부풀지 않습니다.
6. **엔트리 없는 토큰의 `unLock`:** `false`를 반환합니다. 토큰은 단조 증가 시퀀스로 만들므로 뒤에 오는 보유자의 토큰과 겹칠 수 없습니다.
7. **크기 읽기:** `mappingCount()`가 아니라 `lockMap.size.toLong()`을 썼습니다.
8. **Semaphore 종류:** `kotlinx.coroutines.sync.Semaphore`를 골랐습니다. 이 모듈이 코루틴 모듈이기 때문입니다. 엔트리는 `Semaphore(permits = 1, acquiredPermits = 1)`, `isHeld = AtomicBoolean(true)`, 그리고 토큰으로 처음부터 "보유됨" 상태로 만듭니다. kotlinx의 `release()`는 permit을 초과해 반환하면 예외를 던집니다. 그래서 `unLock`은 `isHeld.compareAndSet(true, false)`가 성공할 때만 `release()`를 호출합니다.
9. **인수 방식:** 기존 엔트리의 `expiresAt`과 `token`을 바꾸지 않고, 새 `LockInfo`로 교체합니다. 그래서 `var` 필드는 생성 후 바뀌지 않습니다.
10. **모니터:** companion `init`에서 `CoroutineScope(SupervisorJob() + Dispatchers.Default).launch`로 시작합니다. 루프는 `while (isActive) { delay(LOCK_MONITOR_INTERVAL_MILLIS); removeExpiredEntries() }`입니다. 클래스를 초기화할 때 시작하고 멈추지 않습니다. 순회 본문에는 예외를 던질 경로가 없으므로 try/catch를 두지 않았습니다. 따라서 만약 예외가 나면 모니터는 조용히 멈춥니다. 모니터는 만료 엔트리를 버리기만 합니다. 이때 semaphore 해제나 `isHeld` 변경은 하지 않습니다. 버려진 객체를 참조하는 곳이 없기 때문입니다.
11. **입력 검증 없음 (알려진 한계):**
    - `lockTimeoutMillis <= 0`을 검증하지 않습니다. 이 값이면 락이 즉시 만료되어 상호 배제가 깨집니다.
    - `now + lockTimeoutMillis`의 오버플로도 처리하지 않습니다. `Long.MAX_VALUE` 근처 값이면 `expiresAt`이 음수가 됩니다. 그러면 락이 즉시 만료되어 상호 배제가 깨집니다.
    - `require(lockTimeoutMillis > 0)`는 SPEC의 fail-fast 선례(`LocalLockLimit.maxEntries = -1`)를 근거로 넣을 수 있습니다. 요청되지 않아서 넣지 않았습니다. 팀이 결정할 사항입니다.
12. **재진입 불가:** 같은 보유자가 `tryLock`을 두 번 호출하면 두 번째 호출은 `null`을 받습니다.

## 열린 질문 (메인 세션 결정 필요)

- RULES §3의 문자 그대로인 `compute(key)`와 `lockKeyOf`로 만든 키 중 무엇이 맞는지 정해야 합니다. 검증기가 문자 그대로의 패턴을 요구하면 1번 결정이 문제가 될 수 있습니다.
- 11번의 `require(lockTimeoutMillis > 0)`를 추가할지 정해야 합니다.
