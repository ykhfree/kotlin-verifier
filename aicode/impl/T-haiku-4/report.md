컴파일 결과: 통과. `./compile.sh`를 1회 실행했다(예산 10회 중 1회 사용). 진단 메시지는 없었다. 지정 디렉터리 밖의 파일은 읽지 않았다.

최종 파일: /private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/aicode/sandboxes/T-haiku-4/KeyLocalLock.kt

구현 요약:
- tryLock: compute 한 번으로 entry를 만들거나 거부한다. 키는 lockKeyOf(key, lockType)를 쓴다.
- unLock: compute 한 번으로 token이 일치하면 entry를 제거한다.
- monitor: companion object의 init에서 데몬 스레드를 시작한다. LOCK_MONITOR_INTERVAL_MILLIS(1000 ms)마다 만료된 entry를 제거한다.
- RULES.md의 1~6번 규칙을 따랐다.

SPEC이 정하지 않은 설계 결정:
1. 만료된 entry는 빈 상태로 본다. tryLock은 monitor가 제거할 때까지 기다리지 않고 만료된 entry를 바로 교체한다. lockTimeoutMillis가 정확히 지켜지게 하려는 결정이다.
2. unLock은 만료 여부를 확인하지 않는다. 만료됐지만 다른 holder가 교체하지 않은 entry라도 token이 맞으면 제거하고 true를 반환한다. KeyLock KDoc의 "current owner" 문구를 따랐다. 만료된 holder에게 false를 주려면 이 부분을 바꿔야 한다.
3. 용량 상한(LocalLockLimit.rejectsNewEntry)은 새 key에만 적용한다. 상한에 걸리면 entry 없이 token만 발급한다. 이 token으로 unLock하면 false가 나온다. 따라서 false는 "lock을 잃음"과 "entry 없이 받은 permit"을 둘 다 뜻한다.
4. 만료된 기존 entry를 교체하는 경우는 상한 검사를 건너뛴다. SPEC의 "이미 map에 있는 key는 상한 대상이 아니다" 규칙을 따랐다.
5. 상한 검사에 쓰는 lockMap.size는 compute 람다 안에서 읽는다(RULES 3번). 이 때문에 LocalLockLimit의 WARN 로그가 compute 람다 안에서 나온다.
6. monitor는 코루틴이 아니라 데몬 Thread다. companion object의 init은 클래스가 처음 로드될 때 실행되므로, JVM당 monitor 스레드가 하나 생긴다. 중지하는 방법은 없다. monitor는 entry마다 저장된 expiresAt만 보므로 lockTimeoutMillis가 필요 없다.
7. monitor 루프에는 try/catch가 없다. RULES 6번은 compute 람다 안의 try/catch만 금지한다. 하지만 루프에서 예외가 나면 스레드가 죽고 만료 정리가 멈춘다. compute 람다는 예외를 던지지 않는다고 보고 이렇게 두었다.
8. semaphore와 isHeld 필드: RULES.md가 정한 LockInfo 형태를 그대로 따랐다. 두 필드는 동작을 바꾸지 않는다. semaphore는 허가 1개를 이미 가진 상태로 만들고 그 뒤로 읽지 않는다. isHeld는 map에 있는 entry에서 항상 true이므로, tryLock의 확인 조건은 사실상 now < expiresAt와 같다. 팀이 이 두 필드를 남길지 확인이 필요하다.
9. token은 tryLock을 호출할 때마다 발급한다. 실패한 호출도 번호를 쓴다. 번호는 JVM 안에서 유일하다.
10. lockTimeoutMillis 값을 검사하지 않는다. 0 이하이면 entry가 만들어지자마자 만료되어, lock이 다른 호출을 막지 못한다.
11. 파일 상단 라이선스 헤더는 KeyLock.kt와 같은 문구(Copyright 2024 LY Corporation)를 복사했다.
