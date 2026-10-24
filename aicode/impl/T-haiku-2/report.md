## 최종 상태

- 파일: /private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/aicode/sandboxes/T-haiku-2/KeyLocalLock.kt
- 컴파일: 통과 (`./compile.sh`, 2/10회 사용). 컴파일만 확인했습니다. 테스트와 동작 검증은 하지 않았습니다.
- 작업 지시가 메모리 도구 사용을 금지했습니다. 그래서 CLAUDE.md의 cross-memory query와 record를 실행하지 않았습니다.

## 명세가 정하지 않은 설계 결정

1. **unLock 일치 기준**: token만 비교합니다. lease가 끝났지만 다른 holder가 아직 가져가지 않은 entry는 원래 holder의 token을 그대로 갖습니다. 이 경우 unLock은 true를 반환하고 entry를 제거합니다.
2. **tryLock의 만료 처리**: `expiresAt <= now`인 entry는 빈 lock으로 봅니다. tryLock은 monitor를 기다리지 않고 같은 compute 안에서 새 LockInfo로 교체합니다. 이전 holder의 token은 더 이상 맞지 않습니다.
3. **만료 경계**: `expiresAt == now`이면 만료로 봅니다.
4. **cap 초과 시 permit**: entry 없이 token만 반환합니다. 이 token으로 unLock하면 false를 반환합니다.
5. **cap 검사 위치**: `LocalLockLimit.rejectsNewEntry(lockMap.size)`는 compute 람다 안에서, entry가 없는 키에 대해서만 호출합니다. 이미 entry가 있는 키(만료된 entry 포함)는 cap을 받지 않습니다.
6. **map key**: `lockKeyOf(key, lockType)`를 씁니다. KeyLock.kt에 있는 helper이며, (key, lockType)마다 entry가 하나씩 생깁니다. HELPERS.md 목록에는 없습니다.
7. **token 번호**: tryLock 시작 시 token을 만듭니다. 실패한 tryLock도 번호를 쓰므로 번호에 빈 자리가 생길 수 있습니다.
8. **semaphore와 isHeld**: RULES 2의 LockInfo 형식을 그대로 따릅니다. entry는 해제 시 제거되므로 map에 있는 entry는 항상 held 상태입니다. 두 필드는 읽는 곳이 없습니다. semaphore는 `Semaphore(0)`으로 만들어 "permit을 이미 가짐"을 나타냅니다.
9. **monitor 구현**: companion object의 init이 `CoroutineScope(SupervisorJob() + Dispatchers.Default)`에서 `while (true) { delay(LOCK_MONITOR_INTERVAL_MILLIS); 만료 entry 제거 }` 코루틴을 시작합니다. 명세는 구현 방식을 정하지 않았습니다. 제거 함수가 예외를 던지면 monitor가 멈추고 entry가 쌓입니다. 예외 처리는 요청되지 않아 넣지 않았습니다.
10. **LockInfo 위치**: RULES 1은 companion에 공유 상태만 두라고 합니다. 그래서 LockInfo는 KeyLocalLock 안의 private 중첩 클래스로 두었습니다.
11. **lockTimeoutMillis 검증 없음**: 0 이하 값이나 매우 큰 값을 검사하지 않습니다.
12. **라이선스 헤더**: KeyLock.kt와 같은 "Copyright 2024 LY Corporation" 헤더를 복사했습니다. 팀 파일의 관례를 따른 것입니다.
