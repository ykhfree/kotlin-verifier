## 결과

- 최종 파일은 컴파일됩니다. 파일 경로: `/private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/aicode/sandboxes/T-opus-4/KeyLocalLock.kt`
- `./compile.sh`는 2회 사용했습니다(10회 중). 두 번 모두 진단 메시지 없이 컴파일됐습니다. 두 번째 호출은 주석만 고친 뒤 실행했습니다.
- `compile.sh`는 컴파일만 합니다. 동작은 테스트하지도 검증하지도 않았습니다.
- 작업 디렉터리 밖은 읽지 않았습니다. 웹, 메모리 도구, 다른 에이전트, advisor는 쓰지 않았습니다. 사용자 CLAUDE.md에 "subagent는 advisor를 직접 쓰지 않는다"는 규칙이 있습니다.

## RULES.md 준수
- companion에 `lockMap`과 `tokenSequence`가 있습니다. 토큰 형식은 `"local-${tokenSequence.incrementAndGet()}"`입니다.
- `LockInfo`는 규칙 2의 필드를 그대로 씁니다.
- `tryLock`과 `unLock`은 각각 `lockMap.compute` 한 번 안에서만 맵을 읽고 씁니다. 값은 `AtomicReference`와 `AtomicBoolean`으로 꺼냅니다.
- `tryLock`은 compute 전에 `val now = nowToEpochTime()`로 시각을 읽습니다.
- monitor는 키마다 `val now = System.currentTimeMillis()`를 읽고 `compute`를 한 번 호출합니다.
- compute 람다 안에서는 synchronized, lock, try/catch를 쓰지 않습니다.

## 명세가 정하지 않아서 직접 정한 설계 결정
1. **Semaphore 타입**: `java.util.concurrent.Semaphore`가 아니라 `kotlinx.coroutines.sync.Semaphore`를 썼습니다. 이유는 두 가지입니다. 이 모듈이 coroutine 모듈입니다. 또 `release()`를 너무 많이 호출하면 permit을 조용히 늘리지 않고 예외를 던집니다.
2. **맵 키**: KeyLock.kt의 `lockKeyOf(key, lockType)`를 썼습니다. 결과는 접두사가 붙은 `"<key>_<TYPE>"`입니다. 서로 다른 (key, type) 쌍은 항상 서로 다른 키가 됩니다.
3. **만료된 entry 인수**: 만료된 entry를 `tryLock`이 바로 새 `LockInfo`로 바꿉니다. monitor를 기다리지 않습니다. 그래서 lock은 `expiresAt`에 정확히 만료됩니다. monitor는 맵 크기만 정리합니다. 이전 holder의 토큰은 더 이상 일치하지 않습니다.
4. **만료 판정**: `expiresAt <= now`이면 만료입니다. 즉 획득 후 정확히 `lockTimeoutMillis`가 지난 시점부터 만료로 봅니다. `tryLock`과 monitor는 같은 헬퍼 `isExpired`를 씁니다.
5. **cap 검사 범위**: `LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())`는 해당 키의 entry가 없을 때만 호출합니다. 만료 entry를 교체할 때는 호출하지 않습니다. 교체는 맵을 키우지 않고, WARN 로그 카운터도 올리지 않습니다. 규칙 3 때문에 `size`는 compute 람다 안에서 읽습니다.
6. **맵이 가득 찼을 때 발급하는 permit**: 같은 시퀀스로 같은 `"local-N"` 형식의 토큰을 만듭니다. 맵에는 entry를 넣지 않습니다. 이 토큰으로 `unLock`을 부르면 `false`를 반환합니다. 같은 키를 다른 holder가 갖고 있어도 그 entry는 건드리지 않습니다.
7. **unLock은 시각을 읽지 않습니다**: 토큰이 일치하면 해제하고 `true`를 반환합니다. entry가 이미 만료됐어도, 아직 교체되거나 제거되지 않았으면 마찬가지입니다. 교체됐거나 제거됐으면 `false`입니다.
8. **해제 처리**: 명세는 "released 즉시 drop"이라고 합니다. 그래서 compute가 `null`을 반환해 entry를 제거합니다. 제거 전에 `isHeld=false`, `token=null`, `semaphore.release()`를 실행합니다. 버려지는 객체라서 동작에는 영향이 없습니다. 필드 간 불변식을 맞추려고 넣었습니다.
9. **tryLock의 획득 게이트**: 새 entry에도 `semaphore.tryAcquire()`를 거칩니다. 성공하면 `isHeld`, `token`, `expiresAt`을 설정합니다. 해제하면 entry가 사라지므로 만료되지 않은 기존 entry는 항상 held 상태입니다. 그래서 기존 entry에서는 `tryAcquire`가 실패하고 `tryLock`은 `null`을 반환합니다.
10. **monitor 구현**:
    - companion `init`에서 `CoroutineScope(SupervisorJob() + Dispatchers.Default).launch`로 시작합니다.
    - 루프는 `while (isActive) { delay(LOCK_MONITOR_INTERVAL_MILLIS); removeExpiredEntries() }`입니다. 즉 먼저 대기한 뒤 정리합니다.
    - 클래스를 처음 쓸 때 시작하고, 멈추는 API는 없습니다.
    - Default dispatcher 스레드는 daemon이라서 JVM 종료를 막지 않습니다.
    - 제거하는 entry의 semaphore는 release하지 않습니다.
11. **monitor 예외 처리 없음**: 이 설계에서는 정리용 람다가 예외를 던지지 않습니다. 그래서 루프에 try/catch를 넣지 않았습니다. 예상 밖 예외가 나면 monitor coroutine이 끝납니다. 그 뒤에도 `tryLock`은 만료 entry를 인수합니다. 다만 다시 lock되지 않는 키의 entry는 맵에 남습니다.
12. **lockTimeoutMillis 검증 없음**: 0이나 음수를 주면 lock이 즉시 만료됩니다. 아주 큰 값을 주면 `now + lockTimeoutMillis`가 Long overflow를 일으킵니다.
13. **배치**: `LockInfo`는 KeyLocalLock의 private nested class입니다(companion 밖). `newToken()`과 `isExpired()`는 companion의 private 함수입니다.
14. **logger 없음**: WARN 로그는 `LocalLockLimit`이 직접 남깁니다. 그래서 이 클래스에는 logger를 두지 않았습니다.

## 메인 세션이 판단할 열린 질문
- Semaphore 타입(1번)이 팀 verifier가 모델링하는 타입과 같은지 확인이 필요합니다.
- 만료 lock 처리 방식(3번)을 정해야 합니다. 하나는 tryLock이 즉시 인수하는 방식입니다. 다른 하나는 monitor가 제거할 때까지 최대 1초 막는 방식입니다.
- 만료됐지만 아직 교체되지 않은 lock을 토큰 소유자가 unLock할 때 `true`를 반환해도 되는지(7번) 결정이 필요합니다.
- monitor 루프에 방어용 try/catch와 로그가 필요한지(11번) 결정이 필요합니다.
