## 결과

- 최종 파일: /private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/aicode/sandboxes/T-haiku-3/KeyLocalLock.kt
- 컴파일: 성공. `./compile.sh` 1회 호출에서 진단 메시지 없이 통과했다. 예산 10회 중 1회 사용.
- 테스트와 검증은 하지 않았다. brief가 컴파일만 요구했다.
- RULES.md의 1~6번 형태와 호출 규칙(compute 1회, 람다 밖 값은 AtomicReference/AtomicBoolean으로 전달)을 따랐다.

## 명세가 정하지 않은 설계 결정

1. **만료 엔트리는 빈 것으로 본다.** `tryLock`은 `expiresAt <= now`인 엔트리를 새 토큰의 엔트리로 교체한다. 그래서 락은 `lockTimeoutMillis`에 정확히 풀리고, 모니터의 최대 1초 지연을 기다리지 않는다. 이전 보유자의 `unLock`은 토큰이 달라 false를 받는다.
2. **`unLock`도 만료 엔트리를 보유 중으로 보지 않는다.** 토큰이 맞아도 `expiresAt <= now`이면 false를 반환한다. 엔트리는 모니터가 지울 때까지 둔다. 이 때문에 `unLock`에서도 `nowToEpochTime()`을 읽는다. RULES는 `tryLock`의 시계 읽기만 정했다.
3. **맵이 가득 차면 토큰만 발급한다.** 새 키이고 `LocalLockLimit.rejectsNewEntry`가 true이면, 엔트리 없이 토큰을 반환한다. 그 토큰으로 `unLock`하면 엔트리가 없어 false가 된다. 명세대로 호출자는 락 없이 실행된다.
4. **캡 검사는 compute 람다 안에서 `lockMap.size`를 읽는다.** RULES 3번이 맵 읽기를 compute 안으로 제한하기 때문이다. 명세가 말한 추정치 성격과 맞는다.
5. **모니터는 컴패니언 `init`에서 데몬 스레드 하나를 시작한다.** 클래스 로드 때 한 번 실행된다. 주기는 `LOCK_MONITOR_INTERVAL_MILLIS`이다. 모니터는 RULES 5번에 따라 `System.currentTimeMillis()`를 쓴다. `nowToEpochTime()`과 값은 같다.
6. **`lockTimeoutMillis`를 검증하지 않는다.** 0 이하이면 모든 락이 잡히자마자 만료된다. 명세에 허용 범위가 없다.
7. **`semaphore`와 `isHeld`는 동작을 바꾸지 않는다.** 소유 상태는 맵 엔트리와 토큰이 이미 나타낸다. RULES가 `LockInfo` 형태를 고정하므로 그대로 두었다. 검증기가 허용하면 제거해도 된다.
8. **맵 키는 `lockKeyOf(key, lockType)`이다.** 같은 패키지의 기존 `internal` 헬퍼를 썼다.
9. **해제하면 엔트리를 지운다.** compute 람다가 null을 반환한다.
10. **라이선스 헤더를 넣지 않았다.** `KeyLock.kt`에는 있다. brief가 요구하지 않았다.
11. **ktlint는 적용하지 않았다.** 이 디렉터리에 ktlint 설정 파일이 없다.
12. **cross-memory를 쓰지 않았다.** brief가 memory 도구 사용을 금지했다. 전역 CLAUDE.md는 시작 전 `cross-memory query`를 요구한다. 두 지시가 충돌하므로 사용자가 확인해야 한다.
