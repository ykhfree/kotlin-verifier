## 결과

`KeyLocalLock.kt`를 작성했고, 최종 파일은 컴파일됩니다.
- 파일: /private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/aicode/sandboxes/T-opus-1/KeyLocalLock.kt
- `compile.sh`는 10회 중 3회 사용했습니다. 세 번 모두 "compiles"였고 경고나 진단 메시지는 없었습니다.
- 2회차는 주석 한 줄을 고친 뒤 실행했습니다. 3회차는 수정된 내용이 파일에 들어갔는지 grep으로 확인한 뒤 다시 실행했습니다.
- `compile.sh`는 컴파일만 합니다. 동작이 맞는지는 테스트하거나 검증하지 않았습니다.

## 규칙 준수 요약
- `companion object`에 `lockMap`과 `tokenSequence`를 두었습니다. `LockInfo` 선언은 RULES.md와 글자 그대로 같습니다.
- 토큰 형식은 `"local-${tokenSequence.incrementAndGet()}"`입니다.
- `tryLock`과 `unLock`은 각각 `lockMap.compute(...) { _, existing -> ... }`를 정확히 한 번만 호출합니다.
- 람다 밖으로 나가는 값은 `AtomicReference<String?>`와 `AtomicBoolean`으로만 전달합니다.
- `tryLock`은 compute 호출 전에 `val now = nowToEpochTime()`을 읽습니다.
- 모니터는 키마다 `val now = System.currentTimeMillis()`를 읽은 뒤 `compute(key)`를 한 번 호출합니다.
- compute 람다 안에는 `synchronized`, lock, `try/catch`가 없습니다. `compute` 말고 다른 CHM 메서드로 맵을 바꾸지 않습니다.
- `tryLock`에서 살아 있는 소유자가 있으면 `existing`을 그대로 돌려줍니다. `unLock`에서 토큰이 다르면 마찬가지로 `existing`을 그대로 돌려줍니다. 따라서 다른 소유자의 엔트리를 `null`로 지우는 경로는 없습니다.

## 사양이 정하지 않아 내가 정한 사항
1. **맵 키.** RULES 3의 `compute(key)`를 템플릿으로 해석했습니다. 실제 키는 `val lockKey = lockKeyOf(key, lockType)`입니다. SPEC은 `(cache key, lock type)`마다 엔트리 하나를 요구합니다. 원본 `key`를 그대로 쓰면 CREATE와 UPDATE가 같은 엔트리를 씁니다.
2. **Semaphore 종류.** `java.util.concurrent.Semaphore`가 아니라 `kotlinx.coroutines.sync.Semaphore`를 썼습니다. kotlinx 버전은 획득한 횟수보다 많이 `release()`하면 `IllegalStateException`을 던집니다. 그래서 `unLock`은 `isHeld.compareAndSet(true, false)`가 성공할 때만 `release()`를 호출합니다.
3. **만료된 엔트리 인계.** `tryLock`은 만료된 엔트리를 바로 새 `LockInfo`로 바꿉니다. 모니터가 지울 때까지 최대 1초 기다리지 않습니다. 이전 소유자의 토큰은 이후 `unLock`에서 실패합니다.
4. **만료 경계.** 잠금은 `now < expiresAt`일 때 유효합니다. `expiresAt <= now`이면 만료입니다. `tryLock`과 모니터가 같은 비교식을 씁니다. `expiresAt`은 `now + lockTimeoutMillis`입니다.
5. **unLock은 시계를 읽지 않습니다.** 토큰이 맞으면 만료된 뒤라도 해제에 성공하고 `true`를 돌려줍니다. 단, 아직 아무도 잠금을 인계하지 않았을 때만 그렇습니다.
6. **상한 검사 위치.** 상한 검사는 `existing == null`일 때만 compute 람다 안에서 합니다. 호출은 `LocalLockLimit.rejectsNewEntry(lockMap.mappingCount())`입니다. 맵에 이미 있는 키는 상한 검사를 받지 않습니다. 만료된 엔트리를 교체할 때도 마찬가지입니다. RULES 3 때문에 크기도 람다 안에서 읽어야 합니다. 그래서 `rejectsNewEntry`의 WARN 로그는 CHM 버킷 락을 잡은 상태에서 실행됩니다.
7. **엔트리 없는 허가.** 상한에 걸리면 맵에 엔트리를 넣지 않습니다. 그래도 정상 형식의 `local-N` 토큰을 돌려주고, 이때 시퀀스 번호를 하나 씁니다. 이 토큰으로 `unLock`을 호출하면 `false`가 나옵니다. 해제할 엔트리가 없기 때문입니다.
8. **토큰 생성 시점.** 토큰은 획득에 성공할 때만 람다 안에서 만듭니다. 이미 잠겨 있어 거절된 경우에는 시퀀스 번호를 쓰지 않습니다.
9. **모니터 구현.** 모니터는 `companion object`의 `init`에서 시작합니다. `CoroutineScope(Dispatchers.Default).launch` 코루틴이고, 구조화되지 않았으며 취소되지 않습니다. 루프는 `while (isActive)` 안에서 `delay(LOCK_MONITOR_INTERVAL_MILLIS)`를 먼저 실행한 뒤 `lockMap.keys`를 순회합니다. 이 순회는 약한 일관성을 가집니다. `init`에는 예외를 던질 수 있는 코드를 넣지 않았습니다.
10. **버리는 엔트리의 상태.** 모니터가 지우는 엔트리와 `tryLock`이 교체하는 엔트리는 상태를 바꾸지 않고 버립니다. `release()`도 하지 않고 `isHeld`도 바꾸지 않습니다. 이 객체는 버린 뒤 아무도 참조하지 않습니다. `unLock`만 맵에서 지우기 전에 `isHeld=false`, `semaphore.release()`, `token=null`을 처리합니다.
11. **사용 중 판정.** `tryLock`이 잠금을 사용 중으로 볼 조건은 `existing.isHeld.get() && now < existing.expiresAt`입니다. 현재 코드에서는 맵 안의 엔트리가 항상 `isHeld == true`입니다.
12. **새 Semaphore 획득.** 새 엔트리는 `Semaphore(1)`을 만든 뒤 `tryAcquire()`를 호출해 획득 상태로 둡니다. 새 Semaphore에서는 항상 성공하므로 반환값은 무시했습니다.
13. **`lockTimeoutMillis` 검증 없음.** 값이 0 이하이면 모든 엔트리가 즉시 만료됩니다. `now + lockTimeoutMillis`가 오버플로해도 즉시 만료됩니다. 예를 들어 `Long.MAX_VALUE`가 그렇습니다. 두 경우 모두 잠금이 요청을 모아 주지 못합니다. 토큰 소유권은 그대로 지켜집니다.

## 메인 세션이 판단할 질문
- 검증기가 RULES 3의 `compute(key)`를 문자 그대로 요구하는지 모릅니다. 그렇다면 1번 결정이 충돌합니다.
- 검증기가 `kotlinx.coroutines.sync.Semaphore`와 `java.util.concurrent.Semaphore` 중 어느 쪽을 모델링하는지 모릅니다. 2번 결정은 이 점에 걸려 있습니다.
