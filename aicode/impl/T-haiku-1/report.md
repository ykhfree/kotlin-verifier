## 결과

- 파일: /private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/aicode/sandboxes/T-haiku-1/KeyLocalLock.kt
- 최종 파일은 컴파일된다. `./compile.sh`를 1회 실행했다 (10회 중 1회). 진단 메시지는 없었다.
- 테스트는 실행하지 않았다. 컴파일만 확인했다.

## 구현 요약

- `tryLock`과 `unLock`은 각각 `lockMap.compute(lockKey)`를 한 번만 호출한다. 람다 밖으로 나갈 값은 `AtomicReference`와 `AtomicBoolean`으로 전달한다.
- 맵 키는 `KeyLock.kt`의 `lockKeyOf(key, lockType)`이다.
- 토큰은 `"local-${tokenSequence.incrementAndGet()}"` 형식이다.
- 용량 검사는 `LocalLockLimit.rejectsNewEntry(lockMap.size.toLong())`로 한다.
- 모니터는 companion `init`에서 데몬 스레드 하나를 시작한다. 1000 ms(`LOCK_MONITOR_INTERVAL_MILLIS`)마다 만료된 항목을 지운다.

## 명세가 정하지 않은 설계 결정

1. 만료 시점: `now >= expiresAt`이면 만료로 본다. 경계값은 만료에 포함된다.
2. 만료된 잠금 인수: 같은 `LockInfo`를 재사용하고 `token`과 `expiresAt`만 바꾼다. 이전 보유자의 토큰은 더 이상 맞지 않는다.
3. 모니터가 지우기 전의 만료 항목: 항목이 맵에 남아 있으면, 토큰이 맞는 `unLock`이 `true`를 반환하며 해제한다. 인수자가 없을 때만 이 동작이 생긴다. 토큰 일치가 유일한 검사다.
4. 용량 초과: 새 키는 맵 항목 없이 토큰만 받는다. 이 토큰으로 `unLock`하면 `false`를 반환한다.
5. 용량 검사 시점: `compute` 람다 안에서 `lockMap.size`를 읽는다. 명세대로 근사값이다.
6. 모니터 구현:
   - `Executors.newSingleThreadScheduledExecutor`를 썼다. 데몬 스레드 이름은 `req-shield-local-lock-monitor`다.
   - companion 클래스가 로드될 때 시작한다.
   - companion에는 코루틴 scope가 없어서 코루틴 대신 Executors를 골랐다.
   - RULES대로 모니터는 `System.currentTimeMillis()`를 읽는다. `tryLock`은 `nowToEpochTime()`을 쓴다.
   - 모니터에는 예외 처리를 두지 않았다. 예외가 나면 예약 작업이 조용히 멈춘다.
7. `semaphore`와 `isHeld`: RULES가 `LockInfo`의 구조를 정한다. 그래서 두 필드를 그대로 두었다.
   - `semaphore`는 생성 시 허가 1개를 얻고, `unLock`에서 반납한다.
   - `isHeld`는 생성 시 `true`이며, 아무도 읽지 않는다.
   - 두 필드 모두 "항목이 존재한다"는 사실과 중복된다. CLAUDE.md의 "불필요한 개념 제거" 규칙과 충돌한다. 팀이 제거 여부를 정해야 한다.
8. `lockTimeoutMillis` 검증: 하지 않았다. 0 이하 값을 주면 잠금이 즉시 만료되어 상호 배제가 깨진다.
9. 토큰 번호: 잠금이 거부되어도 토큰 번호를 쓴다. 그래서 번호에 빈 자리가 생길 수 있다. 동작에는 영향이 없다.
10. `tryLock`과 `unLock`은 `suspend`이지만 중단점이 없다. 인터페이스가 `suspend`를 요구한다.
11. 라이선스 헤더는 `KeyLock.kt`에서 그대로 복사했다.
