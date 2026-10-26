# Translator E 설계: 두 번째 실제 프로토콜, req-shield `ReqShield` (요청 합치기)

날짜: 2026-10-26. 상태: 설계안 (도구 코드 변경 전). 범위: `PROTOCOL2.md` 2절의 1-3단계 (프로토콜 분석과 어휘, 손 Viper 모델, 도구 변화의 목록과 크기).
기준: `DESIGN-v0.md`(L1 전이 시스템, KVIR, 5.2절 emitter 템플릿), `DESIGN-B.md`(연산 구간, `fresh`/`throws` 신뢰 호출), `DESIGN-D.md`(action 밖의 단계), `DESIGN-C.md` 2.1절과 10절(sidecar 다섯 층, 서명 정책).
대상: req-shield 브랜치 `feature/NOISSUE/upgrade-kotlin2-and-deps`, HEAD `2e792d2` (읽기만 함).

수치 표시 규칙: "측정"은 이번 작업에서 실행해서 얻은 값이다. "추정"은 실행하지 않은 값이다. "판단"은 논증이고 실행하지 않았다.
실험 환경: Silicon `v.26.08-release`, Z3 4.16.0 (`spike/silicon.sh`), Apple M4 Pro (12 코어).
실험 모델은 손으로 쓴 "예상 emitter 출력"이다 (`DESIGN-v0.md` 3.3절과 같은 방식). 도구 코드는 바꾸지 않았다.
저장소에서 만든 파일은 이 문서와 `spike/protocol2/`뿐이다 (커밋하지 않음).

## 0. 요약

| 항목 | 결정 (권고) | 근거 |
|---|---|---|
| 프로토콜 | 같은 key의 요청이 몰리면 lock을 잡은 요청만 supplier를 부르고 캐시를 쓴다. 나머지는 캐시를 polling한다. 갱신(refresh)은 따로 lock을 잡고 background에서 한다 | 1절 |
| 성질 | 호출 하나의 **lock 사용 규율** 7개(LD_noleak, LD_own, LD_transfer, LD_write, LD_supplier, LD_wait, MODE)와 기다림 2개(POLL_bound, TERM), supplier 횟수 1개(SUP_once), 호출자가 받는 결과 6개(RET_*). 자연어 문서가 대부분을 정한다 | 2절 |
| 열린 결정 | 사용자에게 물을 것 6개 (UE1-UE6). 셋은 측정한 결과가 있다: 가정을 끄면 원본이 FAILED가 된다 (A1-A3) | 2.3절, 4절 |
| 어휘 | 새 층 L3: 선형 의무 `Lease(k, lt, t)`(Viper 추상 predicate), `forperm` 누수 검사, 사건 기록(trace), 과제(task)와 의무 넘기기, 예외 종류. 지금의 L1 "연산당 atomic action 하나" 모델 밖이다 | 3절 |
| 모델 | `spike/protocol2/target.vpr` (555줄): core 변형. 원본 PROVEN, 결함 변이 12개 모두 FAILED, 동치 대조 3개 PROVEN, 가정 토글 3개 FAILED (측정) | 5절 |
| 시간 | 원본 Silicon 2.13-2.14 s (3회), 변이마다 2.10-2.26 s (측정). 공개 함수 메서드 0.48 s | 5.3절 |
| 지금 도구 | `ReqShield`의 연산을 하나도 판정하지 못한다: `kv discover`가 `ReqShield.kt`에서 action 0개, `kv lower`가 UNSUPPORTED와 INCOMPLETE를 낸다 (측정) | 8절 |
| 도구 변화 | 새 모듈 종류(client), 함수 본문 lowering(예외, `runCatching`, private helper 인라인, 람다 → 과제, 반복문), 신뢰 모델 약 10개, 문법 구성 약 11개, KVIR 노드 8개 | 6절 |
| 변형 | core 먼저. coroutine은 신뢰 모델 4개와 취소 예외를 더하면 닿는다. reactor는 Reactor 연산자 22종의 신호 의미론이 막는다 (별도 설계 필요) | 6.11절 |
| 비용 | E0-E4(core) 12.5-19.5 인일, coroutine +3-5, reactor +10-20 (모두 추정). B(aspect)는 B0-B4 7-12 인일이었다 (추정) | 6.10절, 7절 |
| 예측 | `PROTOCOL2.md`의 예측 3개가 모두 맞았다 (1은 측정, 2는 판단, 3은 질문 6개) | 8절 |

측정한 판정 (`spike/protocol2/run.sh`, `isolate.py`). "처음 보고"는 Silicon 한 번 실행의 오류다. "전체"는 성질 하나씩만 켠 복사본 15개씩의 결과다 (5.2절).

| 항목 | Kotlin 편집 (요약) | 기대 | 판정 | 깨지는 의무 전체 |
|---|---|---|---|---|
| 원본 | — | PROVEN | PROVEN | 없음 |
| M1 | supplier 실패 때 해제 안 함 (`executeCallable` 264-266 삭제) | FAILED | FAILED | LD_noleak |
| M2 | executor 거부 때 해제 안 함 (`runInBackground` 149-156 삭제) | FAILED | FAILED | LD_noleak |
| M3 | 재확인의 finally가 늘 해제 (100행 조건 삭제): 이중 해제 | FAILED | FAILED | LD_own, LD_supplier, LD_transfer |
| M4 | fallback 뒤 캐시 쓰기 (`.also { setCacheFunction(...) }`) | FAILED | FAILED | LD_write, RET_supplier |
| M5 | 낡은 토큰으로 해제 (`lastToken ?: token`) | FAILED | FAILED | LD_own |
| M6 | 잘못된 lock 종류로 해제 (`LockType.CREATE` 고정) | FAILED | FAILED | LD_own (갱신 경로에서만) |
| M7 | lock 없이 갱신 (68행 `if (true)`) | FAILED | FAILED | LD_supplier, LD_write |
| M8 | ONLY_UPDATE_CACHE에서도 생성 lock (89행 모드 검사 삭제) | FAILED | FAILED | MODE_create |
| M9 | supplier 실패를 삼킴 (해제 뒤 `null` 반환) | FAILED | FAILED | LD_own, LD_transfer, LD_write, RET_supplier_error |
| M10 | fallback supplier 재시도 | FAILED | FAILED | SUP_once |
| M11 | 실패한 읽기만 시도로 셈 (182행 `attempts++`를 catch로) | FAILED | FAILED | TERM, HOLE |
| M12 | 쓰기 전에 해제 (`createReqShieldData`) | FAILED | FAILED | LD_own, LD_transfer |
| C1 | 쓰기의 finally를 catch + 정상 경로 해제로 | PROVEN | PROVEN | 없음 |
| C2 | 재확인의 flag 없이 경로마다 해제 | PROVEN | PROVEN | 없음 |
| C3 | 갱신 과제를 finally 하나로 해제 | PROVEN | PROVEN | 없음 |
| A1 | 가정 끔: `java.lang.Error`가 치명적이지 않음 | FAILED | FAILED | LD_noleak, RET_hit, RET_recheck, RET_supplier, RET_supplier_error |
| A2 | 가정 끔: `tryLock`·`unLock`이 던질 수 있음 | FAILED | FAILED | RET_hit, RET_recheck, RET_supplier_error |
| A3 | 가정 끔: executor가 받은 과제를 버릴 수 있음 | FAILED | FAILED | LD_noleak |

## 1. 프로토콜

### 1.1 대상 코드

| 변형 | 파일 | 줄 | 비동기 수단 |
|---|---|---|---|
| core | `core/src/main/kotlin/com/linecorp/cse/reqshield/ReqShield.kt` | 277 | `CompletableFuture.runAsync(task, executor)` |
| coroutine | `core-kotlin-coroutine/.../kotlin/coroutine/ReqShield.kt` | 253 | `scope.launch(start = CoroutineStart.ATOMIC)` |
| reactor | `core-reactor/.../reactor/ReqShield.kt` | 374 | `Mono` 연산자 사슬과 `subscribe()` |

1. 공개 함수는 `getAndSetReqShieldData(key, callable, timeToLiveMillis)` 하나다. 나머지 함수는 모두 private helper다.
2. 공유 상태는 이 클래스 안에 없다. 캐시는 사용자 함수(`getCacheFunction`, `setCacheFunction`) 뒤에 있다. lock은 `KeyLock` 인터페이스 뒤에 있다.
3. `KeyLock`의 구현은 둘이다: `KeyLocalLock`(L1으로 이미 검증함)과 `KeyGlobalLock`(사용자의 Redis 함수).
4. 측정: `kv discover`는 core 모듈 전체에서 action 후보 3개를 찾는다. 셋 다 `KeyLocalLock.kt`(109:25, 173:9, 229:9)다. `ReqShield.kt`에는 0개다.

### 1.2 한 요청의 흐름 (core)

1. 캐시를 읽는다 (43행). 읽기가 던지면 `ClientException(GET_CACHE_ERROR)`로 끝난다.
2. hit이면 그 객체를 돌려준다 (48행). 그 전에 TTL의 `decisionForUpdate`%가 지났으면 갱신을 시작한다 (45-46행).
   - 갱신: ONLY_CREATE_CACHE가 아니면 `tryLock(key, UPDATE)`를 부른다 (66행).
   - 토큰을 얻었거나 ONLY_CREATE_CACHE이면, 과제(supplier 호출, 캐시 쓰기, 해제)를 executor에 넘긴다 (68-76행).
3. miss이면 ONLY_UPDATE_CACHE가 아닌 한 `tryLock(key, CREATE)`를 부른다 (89행).
4. 토큰을 얻으면 캐시를 다시 읽는다 (재확인, 95행).
   - hit이면 lock을 풀고 그 객체를 돌려준다 (96행, 100-102행의 finally).
   - miss이면 lock을 쥔 채로 5단계로 간다 (99행).
5. 생성: supplier를 호출 스레드에서 부른다 (122행). 실패하면 lock을 풀고 `SUPPLIER_ERROR`로 끝난다 (264-267행).
6. 성공하면 data를 만들고, 쓰기 과제를 executor에 넘기고, data를 돌려준다 (125-129행). 쓰기 과제가 캐시를 쓰고 lock을 푼다 (240-248행).
7. executor가 과제를 거부하면 호출자가 lock을 푼다. 호출자는 그래도 data를 받는다 (147-157행).
8. lock을 얻지 못했으면 캐시를 polling한다 (173-198행). 50 ms마다, 최대 `maxAttemptGetCache`번, 연속 실패 3번이면 일찍 멈춘다.
9. polling이 data를 찾으면 그것을 돌려준다. 찾지 못하면 supplier를 한 번 부르고 그 결과를 돌려준다. 이 경로는 캐시를 쓰지 않는다 (197행).

### 1.3 앞의 두 프로토콜과 다른 점

| | KeyLocalLock, aspect 캐시 (L1) | ReqShield (L3) |
|---|---|---|
| 공유 상태 | 모듈의 `ConcurrentHashMap` | 없음. 캐시와 lock은 인터페이스 뒤에 있다 |
| 검증 단위 | action(compute 람다 하나)과 모듈 불변식 MI | 공개 함수 한 번의 실행(스레드 하나)과 그것이 넘긴 과제 |
| 다른 스레드 | 모든 action이 MI를 보존한다 | 보지 않는다 (thread-modular). 다른 요청과의 관계는 `KeyLock` 계약 뒤에 있다 |
| ghost | 토큰 기록 `tok(t)` (전역) | 선형 의무 `Lease` (스레드가 소유하고 넘긴다)와 사건 기록 |
| 성질 | 상태 전이의 안전 (P1-P3, set-once) | 호출 순서의 규율 (해제 의무, 소유, 보호된 쓰기, 모드)과 결과 |
| 예외 | action 안에는 없음 (v0), B의 `Raise` | 중심이다. `try`/`catch`(종류별)/`finally`, `runCatching` |
| 비동기 | 없음 | executor 과제 (소유 넘기기) |
| 반복문 | 없음 | polling (종료와 상한) |

판단: 이것은 새 종류의 프로토콜이다. 처음 설계 보고서의 3층 중 L3("thread-local linear release tokens")에 해당한다. Phase 0 spike는 L3를 coroutine 변형에서 한 번 손으로 보였다 (`spike/reqshield/l3_client.vpr`, `spike/RESULTS.md` "L3"). 이 문서는 그것을 core 변형, 더 많은 성질, 도구 설계로 넓힌다.

## 2. 성질

### 2.1 성질 표

"자연어"는 KDoc, README, CLAUDE.md, 테스트가 그 성질을 정하는지다. 줄 번호는 `2e792d2`의 파일이다. 모든 인용은 원문 그대로다.

| ID | 성질 | 출처 (인용) | 자연어 |
|---|---|---|---|
| LD_noleak | 호출과 그 과제가 끝날 때 남은 lock 의무가 없다. 경로: 성공, 재확인 hit, 재확인 실패, supplier 예외, 캐시 쓰기 실패, executor 거부 | `ReqShield.kt:133-134` "The task releases the lock identified by [token] itself, so when the executor rejects it - a saturated bounded pool, or one already shut down - the lock is released here instead."; `ReqShield.kt:252` "Runs the client supplier, releasing the lock identified by [token] when it fails."; 테스트 `shouldReleaseLockWhenCacheRecheckFails` (245행), `ReqShieldTest.kt:776` "The lock must still be released even though the write failed" | 정함. 단 `Error`, 해제 자체의 실패, 버려진 과제는 열림 (UE2-UE4) |
| LD_own | `unLock`은 그 호출이 `tryLock`으로 얻은 토큰으로만, 같은 key와 lock 종류로만, 한 번만 부른다. 남의 토큰, 낡은 토큰, 다른 종류는 없다 | `KeyLock.kt:22-24` "Ownership is represented by an opaque token: only the holder that acquired the lock can release it, so a holder whose lock already expired and was taken over by someone else cannot release the new owner's lock."; `README.md:104` "Every lock acquisition carries an ownership token. Only the holder that acquired the lock can release it"; `ReqShield.kt:253` "A null [token] means this request holds no lock, so nothing is released." | 정함 ("한 번만"은 넘기기 문구에서 나온 판단) |
| LD_transfer | 생성 경로의 재확인이 miss이면 lock을 쓰기 과제에 넘긴다. 호출 쪽은 그 lock을 풀지 않는다 | `ReqShield.kt:99` "On a miss, the existing creation path keeps the lock until its asynchronous write finishes."; `ReqShieldTest.kt:278` (쓰기가 끝나기 전) `verify(exactly = 0) { keyLock.unLock(key, LockType.CREATE, createToken) }` | 정함 |
| LD_write | 캐시 쓰기는 그 경로의 lock을 쥐고 한다. 모드가 lock을 뺀 경로만 예외다. polling 뒤 fallback은 캐시를 쓰지 않는다 | `CLAUDE.md:104` (ONLY_CREATE_CACHE) "Request collapsing (lock) applies only when a cache entry is being created; refreshes of existing entries still happen but without a lock"; `CLAUDE.md:105` (ONLY_UPDATE_CACHE) "on a cache miss every request calls the supplier and writes the cache without a lock" | 모드 규칙은 정함. "fallback은 쓰지 않음"은 문장이 없다 (UE5) |
| LD_supplier | supplier도 그 경로의 lock을 쥐고 부른다. 예외는 lock을 뺀 모드와, 기다림이 끝난 뒤의 fallback이다 | `ReqShield.kt:165-167` "The supplier is never called from the polling loop - it is called on this thread only after the wait gave up, so at most one extra supplier call per waiting request happens."; `CLAUDE.md:7` "ensuring only one request for the same cache key is processed at a time" | 정함 (판단: "processed"는 supplier와 쓰기) |
| LD_wait | 기다리는(polling) 동안 lock 의무가 없다 | `ReqShield.kt:196` "No lock was acquired by this request, so there is nothing to release on failure" | 정함 (판단) |
| MODE | 모드가 뺀 lock 종류는 `tryLock`하지 않는다 | `ReqShield.kt:65` "ONLY_CREATE_CACHE collapses requests on cache creation only, so the update runs without a lock"; `ReqShield.kt:88` (생성 쪽 같은 문장); `ReqShieldTest.kt:405` `verify(inverse = true) { keyLock.tryLock(key, LockType.CREATE) }` | 정함 |
| SUP_once | 호출 스레드의 supplier 호출은 요청당 1번 이하다. 갱신 과제도 1번 이하다 | `ReqShield.kt:166-167` (위 인용); `README.md:162` "When the attempts are exhausted the request calls the supplier once."; `ReqShieldTest.kt:841` "The supplier is called on the caller thread, exactly once - never from the polling task" | 정함 |
| POLL_bound, TERM | 기다림은 `maxAttemptGetCache`번 읽기 안에 끝난다. 연속 실패 3번이면 더 일찍 끝난다 | `ReqShield.kt:169-171` "The wait ends after [ReqShieldConfiguration.maxAttemptGetCache] polls, or earlier when [MAX_CONSECUTIVE_GET_CACHE_FAILURES] reads failed in a row (the cache looks unavailable). A poll whose cache read fails still counts as an attempt, so the wait stays bounded."; `README.md:159-161` | 정함 |
| RET_hit | 첫 읽기가 hit이면 그 객체를 돌려준다. 갱신의 거부나 lock 실패와 무관하다 | `ReqShield.kt:135` "Only that background cache write or refresh is dropped: the caller still gets its data."; `ReqShieldConfiguration.kt:52-53` "A task the pool rejects (bounded queue full, or pool shut down) only skips that cache write or refresh; the request still gets its data and the lock is released." | 정함. lock 저장소 실패는 열림 (UE3) |
| RET_get_error | 첫 읽기가 던지면 `GET_CACHE_ERROR`로 끝난다 | 테스트 `testSetMethodCacheNotExistsAndLocalLockAcquiredAndThrowGetCacheClientException` (539행) | 정함 (테스트) |
| RET_recheck | lock 뒤 재확인이 hit이면 그 객체를 돌려준다. supplier와 쓰기는 없다 | `ReqShield.kt:94` "Another request may have filled the cache between our initial miss and lock acquisition."; `ReqShieldTest.kt:223-224` `verify(exactly = 0) { callable.call() }`, `verify(exactly = 0) { cacheSetter(any(), any(), any()) }` | 정함. 해제 실패는 열림 (UE3) |
| RET_poll | polling이 data를 찾으면 그것을 돌려준다. supplier는 없다 | 테스트 `` `should return the data another request wrote into the cache without calling the supplier` `` (811행) | 정함 (테스트) |
| RET_supplier | 호출 스레드의 supplier가 값을 주면, 그 값으로 만든 data를 돌려준다. 쓰기의 실패와 executor 거부와 무관하다 | `ReqShield.kt:135` (위 인용); `ReqShieldTest.kt:770` "The cache write is fire-and-forget, so its failure must not reach the caller" | 정함 |
| RET_supplier_error | 호출 스레드의 supplier가 던지면 `ClientException(SUPPLIER_ERROR)`로 끝난다. 캐시된 `null`이 되지 않는다 | `README.md:162-163` "A supplier failure is propagated as a `ClientException(SUPPLIER_ERROR)` with the original exception as `cause`; it is never turned into a cached `null`." | 정함. 해제 실패는 열림 (UE3) |

### 2.2 문서와 코드가 어긋나는 곳 (관찰)

1. `ClientException.kt:22`는 "Wraps failures raised by client-provided functions (supplier, cache getter/setter, lock functions)."라고 쓴다. core는 lock 함수의 실패를 감싸지 않는다. `tryLock`(66행, 89행)과 `unLock`(101행, 265행)의 예외가 그대로 호출자에게 간다 (판단: 코드를 읽음; `ErrorCode`에 lock 오류 코드가 없다).
2. `ReqShield.kt:150`은 "A failed release must not cost the caller its data"라고 쓴다. 이 보호는 executor 거부 경로에만 있다. 재확인의 finally(101행)와 supplier 실패(265행)의 `unLock`이 던지면, 그 예외가 호출자의 data나 `SUPPLIER_ERROR`를 대신한다. 측정: A2 토글에서 RET_recheck와 RET_supplier_error가 깨진다.
3. 갱신 경로의 `tryLock`(66행)이 던지면 호출자는 이미 읽은 hit을 잃는다. 측정: A2 토글에서 RET_hit이 깨진다.

이 셋은 결함인지 의도인지 문서가 정하지 않는다. 그래서 UE3의 질문이다.

### 2.3 사용자에게 물을 결정

| ID | 질문 | 선택지와 측정 | 권고 |
|---|---|---|---|
| UE1 | 성질의 범위: 호출 하나의 규율(LD_*, MODE, SUP_once, RET_*)인가, "key마다 한 번에 supplier 하나"(상호 배제)인가? | 상호 배제는 설계상 성립하지 않는다. fallback이 holder와 함께 supplier를 부른다 (`ReqShield.kt:165-167`). 느린 supplier 중에 lease가 끝날 수 있다. local lock은 상한에서 entry 없는 permit을 준다 (`LocalLockLimit.kt:33-37`). 상호 배제는 `KeyLock` 계약과 lease 시간에 기대는 결과로 보고서에 적는다 | 호출 하나의 규율 |
| UE2 | `java.lang.Error`가 지나가는 경로도 lock을 풀어야 하는가? | (a) Error는 치명적이라고 가정한다 (보고서에 싣는다). (b) 성질에 넣는다. 측정(A1): core는 `runAsync` 자체가 Error를 던질 때만 lock이 남는다 (`runInBackground`는 `RejectedExecutionException`만 잡는다). supplier의 Error는 `runCatching`이 잡아 풀어 준다. coroutine은 supplier의 Error에서 풀지 않는다 (`catch (e: Exception)`, 243행; Phase 0 측정 A_F4) | (a) |
| UE3 | lock 저장소가 실패할 때(`tryLock`·`unLock`이 던짐) 호출자는 무엇을 받는가? 던진 `unLock`은 해제로 치는가? | (a) lock 호출은 던지지 않는다고 가정한다 (지금 기준선). (b) RET_*를 그대로 요구한다. 측정(A2): 원본이 RET_hit, RET_recheck, RET_supplier_error에서 FAILED다. LD_*는 그대로 PROVEN이다 (던진 `unLock`은 의무를 소비한다: `ReqShield.kt:150` "the lock then expires on its own"). (b)를 고르면 req-shield 코드를 고쳐야 한다 (2.2절) | 사용자가 정한다. (a)이면 global lock을 쓰는 사용자에게 가정이 보이게 한다 |
| UE4 | executor가 받은 과제는 반드시 실행된다고 가정하는가? | (a) 가정한다. (b) 성질에 넣는다. 측정(A3): 받은 과제를 버릴 수 있으면 원본이 LD_noleak에서 FAILED다. `ThreadPoolExecutor.shutdownNow()`는 "halts the processing of waiting tasks" (JDK `ThreadPoolExecutor.java:1405-1406`). 라이브러리는 사용자의 pool을 끄지 않는다 (`ReqShieldConfiguration.kt:51-52`). core-spring adapter는 자기 pool(`Executors.newScheduledThreadPool`, daemon thread)을 `shutdown()`으로 끈다 (`ReqShieldAspect.kt:263`, 271). `shutdown`은 "previously submitted tasks are executed" (JDK `ScheduledThreadPoolExecutor.java:824-825`). JVM이 끝날 때 daemon thread의 남은 과제는 실행되지 않는다. 그때 local lock은 JVM과 함께 사라지고, global lock은 lease가 끝낸다. 남는 경우의 비용은 lease 하나다 | (a) |
| UE5 | 모드별 쓰기 규칙을 확인한다. 그리고 "polling 뒤 fallback은 캐시를 쓰지 않는다"를 의도 절로 넣는가? | 모드 규칙은 `CLAUDE.md:103-105`가 정한다. fallback의 "쓰지 않음"은 코드(197행)에만 있다. 넣으면 M4 같은 "fallback 결과도 캐시에 쓰자" 변경이 FAILED가 된다 (측정: M4 → LD_write) | 넣는다 (`source none`, 사용자 서명) |
| UE6 | coroutine 변형: `scope.launch(start = ATOMIC)`의 dispatcher가 던지면? | kotlinx.coroutines 1.8.1에서 ATOMIC은 `startCoroutine`을 쓴다 (`CoroutineStart.kt:89`). `resumeWith`가 `dispatcher.dispatch`를 바로 부른다 (`DispatchedContinuation.kt:193-196`). 그래서 dispatch가 던지면 `launch`가 던지고 과제는 실행되지 않는다. coroutine 코드에는 core의 거부 처리(147-157행)에 해당하는 것이 없다: lock이 lease까지 남고, 갱신 경로의 호출자는 hit을 잃는다. Executor 기반 dispatcher는 던지지 않는다 (`Executors.kt:129-136`: 거부되면 job을 취소하고 `Dispatchers.IO`로 보낸다). (a) dispatch는 던지지 않는다고 가정 (Phase 0 F6), (b) 성질에 넣음 → coroutine FAILED | E5 전에 정한다 |

### 2.4 범위 밖 (제안)

1. 상호 배제 (UE1의 다른 선택지). lease 시간과 `KeyLock` 구현에 기대므로 이 모델로는 증명할 수 없다 (판단).
2. 돌려준 `ReqShieldData`의 공유: 호출자와 쓰기 과제가 같은 객체를 쥔다. `ReqShieldData`는 `var value`, `var status`를 가진다. Phase 0의 L3M3가 이 검사(쓰기 권한)를 보였다 (`spike/reqshield/mutants.report`). 라이브러리 코드는 그 필드를 쓰지 않는다.
3. L3 의무 `Lease(k, lt, t)`와 L1의 `tok(t).gHeld`의 연결 (`spike/RESULTS.md` "Not modeled"). `KeyLocalLock`의 L1 증명이 `KeyLock` 신뢰 계약의 일부를 내보내는 길이다. 뒤로 미룬다.

## 3. 어휘

### 3.1 의무 `Lease(k, lt, t)`

1. 성공한 획득 하나가 의무 하나다: key `k`, lock 종류 `lt`(CREATE 또는 UPDATE), 소유 토큰 `t`.
2. Viper에서 몸체 없는 predicate다. `tryLock`의 null 아닌 결과가 만들고, `unLock`이 소비한다.
3. Viper 권한은 affine이다. 그래서 남은 의무는 그 자체로 오류가 아니다. 도구는 스레드가 끝나는 곳마다 누수 검사를 낸다:
   `define NO_LEASES [true, forperm qk: Key, qlt: Int, qt: Int [Lease(qk, qlt, qt)] :: false]`.
   - 측정: Silicon v.26.08에서 predicate의 `forperm`이 동작한다. 사전·사후조건에서는 inhale-exhale 형식 `[true, ...]`이어야 한다 (그 밖에는 "may only contain `perm` and `forperm` expressions inside of an inhale-exhale" 오류).
   - 측정: 토큰 변수를 덮어써서 첫 의무를 잃는 코드도 이 검사가 잡는다. 토큰 이름을 몰라도 된다. Phase 0 spike는 토큰 하나씩(`perm(Lease(k, t)) == none`) 검사했다.
4. "이 스레드가 key `k`, 종류 `lt`의 의무를 쥐었다"는 부정한 `forperm`이다:
   `define HOLDS(k, lt) (!(forperm qk: Key, qlt: Int, qt: Int [Lease(qk, qlt, qt)] :: !(qk == (k) && qlt == (lt))))`.
   - 측정: `exists`로 쓴 형태도 검증되지만 trigger 경고가 난다. `forperm` 형태는 경고가 없다.
5. 의무는 넘길 수 있다. 과제를 executor에 넘기는 곳에서 호출자는 과제의 전제(그 의무 포함)를 `exhale`한다 (3.4절).

### 3.2 사건 기록 (trace)

성질 대부분은 "무엇을 몇 번, 어떤 결과로 불렀나"다. 도구는 스레드 실행마다 ghost 객체 `tr` 하나를 만든다. 신뢰 호출 뒤에 사건 규칙이 `tr`을 갱신한다.

| 필드 | 사건 규칙 | 쓰는 성질 |
|---|---|---|
| `first`, `firstObj` | 이 실행의 첫 `getCacheFunction` 결과 (HIT, MISS, THREW)와 객체 | RET_hit, RET_get_error, 경로(`PHASE`) |
| `recheck`, `recheckObj` | CREATE 의무를 쥔 동안의 `getCacheFunction` 결과 | RET_recheck |
| `npoll`, `consec`, `pollHit`, `pollObj` | 그 밖의 `getCacheFunction`: 횟수, 끝의 연속 실패 수, 찾은 객체 | POLL_bound, `GAVE_UP`, RET_poll |
| `nsup`, `sup`, `supVal` | `Callable.call`: 횟수, 마지막 결과와 값 | SUP_once, RET_supplier, RET_supplier_error |
| `ntryC`, `ntryU` | 종류별 `tryLock` 횟수 | MODE |
| `nspawn` | executor가 받은 과제 수 | RET_recheck |

1. 규칙은 효과 기반이다 (`DESIGN-v0.md` 3.5절의 (c)와 같은 생각). 읽기의 종류는 코드 위치가 아니라 실행의 이력과 쥔 의무로 정한다. 그래서 C1-C3 같은 리팩터링은 판정을 바꾸지 않는다 (측정).
2. 파생 값: `PHASE(tr)` = 첫 읽기가 HIT이면 UPDATE 경로, 아니면 CREATE 경로. `GAVE_UP(tr, maxAttempt)` = `npoll >= maxAttempt || consec >= 3`.
3. 과제는 자기 `tr`을 가진다. 호출자의 경로(`PHASE`)는 넘기는 곳에서 ghost 인자로 받는다.

### 3.3 신뢰 계약

| 모델 | 계약 | 출처 | 검토 |
|---|---|---|---|
| `KeyLock.tryLock(k, lt)` | null, 또는 토큰 `t`와 `Lease(k, lt, t)`. 던지면 의무 없음 (A2) | `KeyLock.kt:28-35` | 사람 (인터페이스, 본문 없음) |
| `KeyLock.unLock(k, lt, t)` | `Lease(k, lt, t)`를 요구하고 소비한다 (LD_own). 던져도 소비한다 | `KeyLock.kt:37-46`, `ReqShield.kt:150` | 사람 |
| `getCacheFunction(key)` | data, null, 또는 던짐 (`Exception`) | `ReqShieldConfiguration.kt:34` | 사용자 함수 |
| `setCacheFunction(key, data, ttl)` | Boolean, 또는 던짐 | `ReqShieldConfiguration.kt:33` | 사용자 함수 |
| `Callable.call()` | 값, 또는 던짐 (`Exception`, `InterruptedException` 포함) | JDK `Callable` | 사용자 함수 |
| `CompletableFuture.runAsync(task, executor)` | executor가 받거나(과제는 나중에 한 번 실행되고, 과제의 `Throwable`은 future가 잡는다), `RejectedExecutionException`을 던진다(과제는 실행되지 않음). 받은 과제는 실행된다 (A3) | JDK `CompletableFuture.java:2031-2034`, `1815-1820`, `1798-1809`; `Executor.java:126-136` | 도구 내장 |
| `CompletableFuture.whenComplete(cb)` | `cb`는 로그만 한다. 의무와 사건이 없다 | — | ledger가 확인 |
| `Thread.sleep(ms)` | 정상, 또는 `InterruptedException` | JDK | 도구 내장 |
| `decideToUpdateCache(...)` | 임의 Boolean (시계를 읽는다) | `CommonUtils.kt:19-28` | 사람 |
| `ReqShieldData(value, ttl)` | 새 객체, `value` 필드 | `ReqShieldData.kt:27-32` | 도구 (`fresh`, DESIGN-B 3절과 같음) |
| `runCatching { }.getOrElse { }` | `try { } catch (e: Throwable) { }` | kotlin-stdlib 2.0.21 `Result.kt:141-147` (계약 없음), `188-196` (`AT_MOST_ONCE`) | 도구 내장 |
| `slf4j.Logger` | 효과 없음 | 기존 모델 | 기존 |

1. `runCatching`에는 Kotlin 계약이 없다 (측정: stdlib 2.0.21 소스). 그래서 B의 규칙("`EXACTLY_ONCE`일 때만 인라인")으로는 람다를 받지 못한다. 전용 신뢰 모델이 필요하다.
2. `runAsync`가 돌려준 future는 `whenComplete`의 수신자로만 쓰인다 (146행). 그래서 누구도 과제 전에 그 future를 완료하지 못한다. 이것은 `AsyncRun.run`의 `if (d.result == null)`(`CompletableFuture.java:1802`)이 과제를 건너뛰지 않는다는 조건이다. 도구는 이 쓰임새를 구문으로 확인한다 (판단).

### 3.4 과제와 의무 넘기기

1. executor에 넘긴 람다는 과제다. 과제는 자기 Viper 메서드를 가진다. 끝에서 자기 `NO_LEASES`를 검사한다.
2. 넘기는 곳의 Viper는 `exhale <과제 전제>`다 (fork). 받은 뒤 버려진 과제(A3를 끈 경우)는 `exhale`하지 않는다. 그러면 의무가 호출자에게 남아 호출자의 누수 검사가 실패한다.
3. 과제 전제는 도구가 코드에서 만든다. 세 부분이다.
   - 과제 본문의 해제 호출이 이름 짓는 의무: `t != NULL ==> Lease(k, lt, t)` (캡처한 값으로).
   - 상수로 초기화한 캡처 `val`의 값: `lt == UPDATE` (`val lockType = LockType.UPDATE`, 62행).
   - 넘기는 곳을 둘러싼 `if` 조건 중 캡처 값만 쓰는 것: `mode == OCC || t != NULL` (68행).
   - 세 부분 모두 불변 값(`val`, 파라미터, 인스턴스의 불변 설정)만 쓴다. 캡처한 `var`는 과제가 나중에 다른 값을 볼 수 있으므로 과제 안에서 임의 값이다 (판단). core의 두 람다는 불변 값만 캡처한다 (판단: 코드를 읽음).
4. 건전성 (판단): 넘기는 곳의 `exhale`이 전제 전체를 확인한다. 그래서 도구가 전제를 잘못 만들면 거짓 PROVEN이 아니라 FAILED(오경보)가 된다.
   - 전제에 없는 의무는 호출자에게 남는다. 그러면 호출자의 누수 검사가 실패한다.
   - 전제에 있는데 호출자가 쥐지 않은 의무는 `exhale`이 실패한다.
5. 과제가 언제 실행되는지는 이 성질들에 영향이 없다. 넘긴 의무는 호출자가 더 이상 쥐지 않기 때문이다 (권한 분리).

### 3.5 예외 종류

| 종류 | 뜻 | 잡는 절 |
|---|---|---|
| `CE_GET`, `CE_SET`, `CE_SUP` | 코드가 던지는 `ClientException`(오류 코드별) | `catch (e: Exception)`, `runCatching` |
| `REE` | `RejectedExecutionException` | `catch (e: RejectedExecutionException)`, 위 둘 |
| `RTE`, `CHK` | 그 밖의 `RuntimeException`, 검사 예외 | `Exception`, `Throwable` |
| `INTR` | `InterruptedException` | `catch (e: InterruptedException)`, 위 둘 |
| `ERR` | `java.lang.Error` | `runCatching`, `finally`만 (A1) |

1. 던질 수 있는 호출은 결과와 함께 예외 종류를 돌려준다. `exc != NONE`이면 try나 함수의 나머지를 건너뛴다.
2. `finally`는 모든 경로에서 실행된다. 그 안에서 던지면 대기 중인 예외나 `return`을 대신한다 (Kotlin 의미).
3. coroutine에는 `CANCEL`(`CancellationException`, `IllegalStateException`의 하위)을 더한다. 일시 중단 지점마다 나올 수 있다 (6.11절).

### 3.6 다섯 층에서의 위치 (`DESIGN-C.md` 2.1절)

| 층 | ReqShield에서 |
|---|---|
| 1 묶기 | `module ... client`, `source`, 공개 함수 선택, `free mode = this.reqShieldConfig.reqShieldWorkMode` |
| 2 신뢰 | 3.3절 표. `KeyLock` 두 함수와 `decideToUpdateCache`에 `reviewed` |
| 3 어휘 | `obligation Lease`, 사건 기록 필드와 규칙, `PHASE`, `GAVE_UP`. 프로토콜마다 한 번 사람이 승인한다 |
| 4 의도 | 2.1절의 성질 절 (guard, ensures). 자연어에서 초안을 쓸 수 있다 |
| 5 hole | polling 반복문의 불변식 (6.8절) |

## 4. 신뢰 가정과 토글

| ID | 가정 (기준선) | 끄면 (측정) | 연결된 질문 |
|---|---|---|---|
| A1 | `java.lang.Error`는 프로세스를 끝낸다. 그래서 어떤 호출도 Error를 던지지 않는다 | FAILED: LD_noleak, RET_hit, RET_recheck, RET_supplier, RET_supplier_error. 누수는 `runAsync`의 Error 하나뿐이다 (측정: 그 계약에서만 Error를 빼면 LD_noleak이 PROVEN) | UE2 |
| A2 | `KeyLock.tryLock`·`unLock`은 정상 반환한다 | FAILED: RET_hit, RET_recheck, RET_supplier_error. LD_*는 PROVEN이다 | UE3 |
| A3 | executor가 받은 과제는 실행된다 | FAILED: LD_noleak | UE4 |

1. 세 가정은 판정을 정한다. 그래서 보고서의 "Assumptions"와 "Spec to review"에 모두 싣는다.
2. 가정의 값은 사용자가 서명한다 (`DESIGN-v0.md` 8.3 U7, `DESIGN-C.md` 10절 UC4와 같은 정책).

## 5. 모델 (spike)

### 5.1 구조

`spike/protocol2/target.vpr` (555줄)의 내용:

1. 공개 함수 하나가 메서드 `getAndSetReqShieldData`다. private helper는 모두 인라인했다. 지역 이름은 `<이름>__<줄>`이다. 여러 곳에 인라인한 helper의 지역 이름은 호출 줄을 접미사로 단다 (`e_262_at122`).
2. 과제 둘이 메서드다: `task_69`(갱신, 69-76행)와 `task_125`(생성 쓰기, 125-127행).
3. 신뢰 모델은 몸체 없는 메서드다. 예외 종류를 돌려준다.
4. polling 반복문(181-194행)은 Viper `while`이다.
   - 도구 몫의 불변식: 읽기만 하는 필드는 분수 권한과 값(`acc(tr.first, 1/2) && tr.first == MISS`), 쓰는 필드는 전체 권한, `NO_LEASES`(LD_wait: 기다리는 동안 lock이 없다).
   - hole 몫의 불변식: 코드의 counter와 사건 기록의 관계 (5줄).
   - 종료: `decreases maxAttempt - attempts__178`.
5. 성질은 사후조건(공개 함수 11개, 과제 3개)과 사용자 호출 앞의 `assert`(LD_write, LD_supplier)다. LD_own은 `unLock`의 전제, LD_transfer는 넘기는 곳의 `exhale`이다.
6. 모드(`mode`)와 `maxAttempt`는 메서드 파라미터다. 인스턴스의 불변 설정 값이기 때문이다 (`DESIGN-v0.md` 2.12의 `InstanceVal`).

### 5.2 결과 (측정)

0절의 표가 전체 결과다. 파일: `spike/protocol2/expected-verdicts.txt`(판정 줄), `spike/protocol2/isolation.txt`(깨지는 의무 전체).

처음 보고된 오류 (Silicon 한 번 실행, `--numberOfErrorsToReport 0`):

| 항목 | 처음 보고 |
|---|---|
| M1 | `task_69`와 공개 함수의 `NO_LEASES` 사후조건 |
| M2 | 공개 함수의 `NO_LEASES` |
| M3 | LD_supplier `assert`, `unLock`의 전제 (265행과 152행의 사본), 넘기는 곳의 `exhale` |
| M4 | LD_write `assert` (추가한 쓰기) |
| M5 | `unLock`의 전제 (두 과제의 243행 사본): `Lease(k, lt, lastToken != -1 ? lastToken : t)` |
| M6 | `unLock`의 전제 (`task_69`의 265행 사본만) |
| M7 | `task_69`의 LD_supplier `assert` |
| M8 | MODE_create 사후조건 |
| M9 | `task_69`의 LD_write `assert`, `unLock`의 전제 (`task_69`의 243행 사본, 공개 함수의 152행 사본), RET_supplier_error, 넘기는 곳의 `exhale` |
| M10 | SUP_once 사후조건 |
| M11 | hole 불변식(`tr.npoll <= attempts__178`)이 보존되지 않음, 종료 측도 |
| M12 | 넘기는 곳의 `exhale`, `unLock` 전제 (거부 경로) |
| A1 | `NO_LEASES`, RET_hit, RET_recheck, RET_supplier, RET_supplier_error |
| A2 | RET_hit, RET_recheck, RET_supplier_error |
| A3 | `NO_LEASES` |

관찰:
1. 과제에 넣은 결함은 과제 메서드에서 보고된다 (M1의 `task_69`, M6, M7). 호출자 쪽 결함은 공개 함수에서 보고된다. 위치가 원인과 맞는다.
2. M6는 갱신 경로에서만 잘못이다 (생성 경로에서는 `CREATE`가 맞는 값이다). 모델은 `task_69`의 사본만 거부했다.
3. 항상 켜진 권한 검사(LD_own, LD_transfer)가 실패하면 Silicon은 그 경로를 멈춘다. 그래서 M5에서는 같은 경로의 누수(LD_noleak)가 보고되지 않는다. 보고서는 "처음 실패"를 싣고, 전체 집합은 선택 측정으로 둔다.
4. 동치 대조 C1-C3은 해제 위치를 옮긴다(finally ↔ catch와 정상 경로). 사건 규칙과 의무가 코드 위치에 묶이지 않으므로 PROVEN이다.
5. M4는 LD_write 말고 RET_supplier도 깨뜨린다. 더한 쓰기가 던지면 호출자가 data를 잃기 때문이다. 이것은 진짜 결과다.

### 5.3 시간 (측정)

| 실행 | 시간 |
|---|---|
| 원본, 혼자 3회 | Silicon 2.13-2.14 s, 벽시계 2.34-2.37 s |
| 원본의 메서드별 (`TIMES=1`) | `getAndSetReqShieldData` 0.48 s, `task_69` 0.18 s, `task_125` 0.13 s, 신뢰 모델 0.02-0.07 s |
| 변이·대조·토글 18개, 하나씩 | Silicon 2.10-2.26 s |
| 도달성 검사 (`refute false` 21개) | 2.20-2.23 s |
| 20개 파일 병렬 (`run.sh`) | 벽시계 22.3 s (파일마다 17.7-20.9 s, 12 코어 경합) |
| 깨지는 의무 전체 (285회, 작업자 8) | 벽시계 4분 14초 |

비교: aspect 목표 모델 1.94 s (`DESIGN-B.md` 0절), KeyLocalLock 목표 모델 3.93 s (`DESIGN-v0.md` 8.3).

### 5.4 공허하지 않다는 확인 (측정)

1. `spike/protocol2/reach.py`가 분기 21곳에 `refute false`를 넣는다. 모두 도달 가능하다 (오류 0개).
2. 그 분기: 첫 읽기 실패, 갱신 없는 hit, lock을 쥔 갱신 과제, ONLY_CREATE_CACHE의 lock 없는 갱신, 갱신 lock 실패, 갱신 거부 뒤 해제, 재확인 hit, 재확인 실패, supplier 실패 뒤 해제, lock을 쥔 쓰기 과제, ONLY_UPDATE_CACHE의 lock 없는 쓰기 과제, 쓰기 거부 뒤 해제, polling hit, 연속 실패로 멈춤, 기다림 중 interrupt, 시도를 다 쓴 fallback, 연속 실패 뒤 fallback, fallback supplier 실패, 갱신 과제의 supplier 실패 뒤 해제, 갱신 과제의 쓰기 뒤 해제, 쓰기 과제의 쓰기 실패 뒤 해제.
3. 판정이 공허하지 않다는 증거는 이것과 변이 12개다. `init`에 해당하는 것은 없다 (모듈 상태가 없다).

### 5.5 처음 쓴 모델에서 고친 것 (측정)

1. hole 불변식 `exc == NONE ==> tr.consec == consecutiveFailures__179`가 보존되지 않았다. polling hit은 사건 규칙이 `consec`을 0으로 하지만 코드는 그 counter를 바꾸지 않고 돌아간다. 조건에 `found_187 == null`을 더했다.
2. RET_poll이 실패했다. 불변식이 "찾았으면 예외가 없다"를 말하지 않았다. `found_187 != null ==> exc == NONE`을 더했다.
3. 둘 다 hole 몫이다. 반복문 불변식을 쓰는 데 Silicon 실행 3회가 들었다. `DESIGN-v0.md`의 E3(LLM이 MI를 씀)와 같은 종류의 일이다.

## 6. 도구 변화

### 6.1 새 모듈 종류: client

1. sidecar `module <클래스> client`: 모듈 상태가 없다. 공개 함수마다 실행 하나를 검증한다 (thread-modular).
2. 다른 스레드와의 간섭은 신뢰 계약(`KeyLock`)만 통한다. 그래서 MI, `MI_OWN`, FRAME, hole `MI_INV`가 없다.
3. 이 모듈의 판정 단위는 "공개 함수"와 "과제"다. 모듈 판정 규칙은 `DESIGN-v0.md` 5.8과 같다.

### 6.2 sidecar 문법 (더하는 것만, 스케치)

```text
decl      := ... v0, B, C ...
           | "module" FQN "client"                                // E: no state; public funs are checked thread-modularly
           | "enum" NAME "=" KCLASS                               // E: a Kotlin enum as Int constants
           | "obligation" NAME "(" TYPE { "," TYPE } ")"          // E: a linear obligation (abstract predicate)
           | "trusted" "value" KNAME tclause*                     // E: a function-typed property the code invokes
           | "trace" "{" { NAME "=" event } "}"                   // E: the event record
           | "hole" "loop" KNAME "@" LINE TRIPLE_STRING           // E: the invariant of one loop
tclause   := ... v0, B ... | "creates" NAME "(" ARGS ")" [ "if" VEXPR ] | "consumes" NAME "(" ARGS ")"
           | "throws" KIND { "," KIND }                           // E: exception kinds (B's bare `throws` = Exception)
event     := "first" CALL | CALL "while" "holds" "(" NAME "(" PATTERN ")" ")" | "count" CALL [ "where" VEXPR ]
           | "last" CALL | "trailing" "failures" "of" NAME
opitem    := ... v0, B ... | "guard" ID ":" CALL "requires" VEXPR // E: an assert before each call of CALL
VEXPR     := ... | "holds" "(" NAME "(" PATTERN ")" ")"            // E: HOLDS; `_` matches any value
```

core `ReqShield`의 sidecar 스케치 (주석과 `reviewed` 제외 약 45줄, 추정):

```text
module com.linecorp.cse.reqshield.ReqShield client
source "core/src/main/kotlin"
type Key = opaque
type Token = opaque
enum LockType = com.linecorp.cse.reqshield.LockType
enum Mode = com.linecorp.cse.reqshield.config.ReqShieldWorkMode
obligation Lease(Key, LockType, Token)

trusted fun KeyLock.tryLock(key: Key, lockType: LockType) returns t: Token? creates Lease(key, lockType, t) if t != null
trusted fun KeyLock.unLock(key: Key, lockType: LockType, token: Token) returns released: Bool consumes Lease(key, lockType, token)
trusted value ReqShieldConfiguration.getCacheFunction returns d: Ref throws Exception
trusted value ReqShieldConfiguration.setCacheFunction returns b: Bool throws Exception
trusted fun java.util.concurrent.Callable.call() returns v: Int throws Exception
use jdk.CompletableFuture.runAsync
use kotlin.runCatching

trace {
  first   = first getCacheFunction
  recheck = getCacheFunction while holds(Lease(k, CREATE, _))
  npoll   = count getCacheFunction where !first && !holds(Lease(k, CREATE, _))
  consec  = trailing failures of npoll
  nsup    = count Callable.call
  ntryC   = count KeyLock.tryLock where lockType == CREATE
}

operation getAndSet = fun ReqShield.getAndSetReqShieldData {
  free mode = this.reqShieldConfig.reqShieldWorkMode
  free maxAttempt = this.reqShieldConfig.maxAttemptGetCache
  guard LD_write: setCacheFunction requires
      (PHASE == CREATE && mode != ONLY_UPDATE_CACHE ==> holds(Lease(k, CREATE, _))) &&
      (PHASE == UPDATE && mode != ONLY_CREATE_CACHE ==> holds(Lease(k, UPDATE, _)))
  ensures MODE_create: mode == ONLY_UPDATE_CACHE ==> ntryC == 0
  ensures RET_hit: first == HIT ==> !exc && res == first.obj
}
hole loop ReqShield.handleLockFailure@181 """ ... """
```

1. LD_noleak, LD_own, LD_transfer, LD_wait, TERM은 sidecar 절이 아니다. 도구 사실이다: `obligation`과 `consumes`가 있으면 도구가 낸다.
2. 새 문법 구성은 약 11개다: `client`, `enum`, `obligation`, `creates ... if`, `consumes`, 종류 있는 `throws`, `trusted value`, `trace`(사건 형태 5개), `guard`, `holds`, `hole loop`. B는 7개였다 (`DESIGN-B.md` 5.1).
3. 사건 형태를 고정된 몇 개로 둔다 (판단). 일반 사건 열(`Seq[Event]`)과 그 위의 식은 더 일반적이지만, 사람이 읽기 어렵고 Viper 시간이 늘 수 있다 (측정하지 않음).

### 6.3 KVIR

```kotlin
// Additions to DESIGN-v0.md 1.2 and DESIGN-B.md 5.2 (sketch; names may change in the implementation).
enum class ModuleKind { OBJECT, INSTANCE, CLIENT }                       // E: CLIENT has no state
enum class ExcKind { CE_GET, CE_SET, CE_SUP, REE, RTE, CHK, INTR, CANCEL, ERR }
data class Entry(val fn: String, val pos: Pos, val params: List<Local>, val body: List<Stmt>, val result: Local?)
data class Task(val id: String, val pos: Pos, val captures: List<FreeValue>, val body: List<Stmt>,
                val facts: List<Expr>)                                    // constant captures, enclosing conditions
data class Call(val outs: List<Local>, val model: String, val args: List<Expr>,
                val throws: Set<ExcKind>, override val pos: Pos) : Stmt    // a trusted call with an exception outcome
data class Try(val body: List<Stmt>, val catches: List<Catch>, val fin: List<Stmt>?, override val pos: Pos) : Stmt
data class Catch(val kinds: Set<ExcKind>, val body: List<Stmt>)
data class Throw(val kind: ExcKind?, override val pos: Pos) : Stmt       // null kind: rethrow the pending exception
data class Spawn(val task: Task, val model: String, override val pos: Pos) : Stmt
data class While(val cond: Expr, val body: List<Stmt>, val hole: String?, val measure: Expr?, override val pos: Pos) : Stmt
data class Break(override val pos: Pos) : Stmt
// Return (B) may now stand inside Try (the finally runs) and inside an inlined helper (it leaves the helper).
```

새 well-formedness 규칙 (추정 5개):
1. `Try`의 `catch` 종류는 FIR의 catch 타입에서 온다. 모르는 타입은 UNSUPPORTED다.
2. 인라인하는 private helper의 호출 그래프에 순환이 없다.
3. `Spawn`의 과제는 람다 리터럴이다. 그 람다는 helper의 함수 타입 파라미터를 지나도 된다 (`runInBackground(key, lockType, token) { ... }`).
4. `While`의 본문에는 의무 연산(`creates`, `consumes`)과 `Spawn`이 없다 (판단: 이것이 없으면 도구 몫의 반복문 불변식이 의무를 말해야 한다).
   - Viper의 반복문 본문은 불변식의 권한만 본다. 그래서 본문 안의 `HOLDS`는 불변식이 말하지 않은 의무를 보지 못한다. ReqShield에서는 LD_wait(`NO_LEASES` 불변식)이 반복문에 들어갈 때 의무가 없음을 확인하므로 정확하다. lock을 쥔 채 도는 반복문은 쥔 의무를 불변식에 적어야 한다 (다음 client 프로토콜에서 정함).
5. 의무를 만들고 소비하는 호출은 sidecar의 `trusted` 선언과 묶인다.

### 6.4 lowering: 함수 본문

v0는 action 람다만, B는 연산 구간의 일부만 번역했다. client 모듈은 공개 함수와 그것이 부르는 private helper 전체를 번역한다. core `ReqShield.kt`에 나오는 구문 (판단: 코드를 읽음):

| # | 구문 | 위치 (행) | 처리 |
|---|---|---|---|
| 1 | `?.let { ...; return it } ?: run { return ... }` (비지역 return) | 44-51 | B의 `let`/`run` 인라인 + Return |
| 2 | 식으로 쓴 `if` (`val token = if (c) null else tryLock(...)`) | 66, 89 | AI-CODE.md A2의 조건 식 |
| 3 | `return`이 든 `try`와 `finally` | 93-103 | `Try` + 대기 중인 return |
| 4 | `runCatching { }.getOrElse { }` | 223-227, 261-268 | 신뢰 모델 (3.3절) |
| 5 | `try`/`catch (e: Exception)`/`finally` | 236-248 | `Try` |
| 6 | `catch (e: RejectedExecutionException)`와 그 안의 `try`/`catch (e: Exception)` | 143-157 | `Try` 중첩 |
| 7 | `while`, catch 안의 `break`, try 안의 `return` | 181-194 | `While`, `Break`, Return |
| 8 | 함수 타입 값의 호출 (`getCacheFunction.invoke(key)`, `setFunction.invoke(...)`, `callable.call()`) | 186, 224, 237, 262 | `Call` (`trusted value`) |
| 9 | 함수 타입 파라미터를 가진 private helper에 넘긴 람다 → `runAsync(task, executor)` | 69, 125, 145 | `Spawn` |
| 10 | `.whenComplete { _, e -> ... }` (로그만) | 146 | ledger: 효과 없음 확인 |
| 11 | `Thread.sleep`, `Thread.currentThread().interrupt()` | 202, 204 | `Call`, 효과 없음 |
| 12 | data class 보조 생성자 (`ReqShieldData(value = ..., timeToLiveMillis = ...)`) | 214-217 | `fresh` (B) |
| 13 | enum 비교 (`reqShieldWorkMode == ReqShieldWorkMode.ONLY_CREATE_CACHE`) | 63, 86 | `enum` 상수 |
| 14 | `throw ClientException(ErrorCode.X, cause = ...)` | 206, 226, 239, 267 | `Throw(CE_*)` |
| 15 | `token != null && lockType != null` (nullable lock 종류) | 264 | 기존 null 검사 |
| 16 | slf4j 로그 | 여러 곳 | 기존 모델 |

ledger: 모듈 파일의 모든 FIR 노드는 번역, 신뢰 호출, 효과 없음, unsupported 중 하나다 (`DESIGN-v0.md` W14와 같은 규칙).

### 6.5 신뢰 모델 (도구 내장, 새 항목)

| 모델 | 변형 |
|---|---|
| `jdk.CompletableFuture.runAsync`, `jdk.CompletableFuture.whenComplete`, `jdk.Executor.execute` | core |
| `jdk.Thread.sleep`, `jdk.Thread.interrupt` | core |
| `kotlin.runCatching`, `kotlin.Result.getOrElse` | core (coroutine은 쓰지 않음) |
| `kotlinx.launch(ATOMIC)`, `kotlinx.withContext(NonCancellable)`, `kotlinx.ensureActive`, `kotlinx.delay` | coroutine |
| Reactor 연산자 22종 (6.11절) | reactor |

### 6.6 emitter

`spike/protocol2/target.vpr`이 목표 형태다. 순서:
1. 도메인, 상수(lock 종류, 모드, 예외 종류, 사건 결과), 가정 토글.
2. `predicate`, `NO_LEASES`, `HOLDS`, guard macro.
3. 사건 기록 필드, `TR`, `TR_NEW`, `PHASE`, `GAVE_UP`, 사건 규칙 macro (`EV_READ`, `EV_TRY`, `EV_SUP`).
4. 신뢰 모델 메서드.
5. 과제 전제 macro와 과제 메서드.
6. 공개 함수 메서드: 사후조건(성질마다 한 줄), 본문(예외 flag 형식), 넘기는 곳의 `exhale`, 반복문(도구 몫 불변식, hole, 측도).

측정한 Viper 사실:
1. statement macro(`define EV_READ(...) { ... }`)가 동작한다.
2. 반복문 불변식의 분수 권한(`acc(tr.first, 1/2)`)은 나머지 반이 반복문 밖에 남아 값을 지킨다. 반복문 뒤에 그 값을 안다.
3. 반복문 불변식에 inhale-exhale `forperm`을 쓸 수 있다.
4. `decreases`가 반복문에 동작한다. 진행이 없는 반복문은 "Loop might not terminate"로 실패한다.

### 6.7 보고서

```text
ReqShield (core)                                         module: PROVEN
  entry getAndSetReqShieldData  ReqShield.kt:38:5   PROVEN
  task  runInBackground lambda   ReqShield.kt:69:51  PROVEN   (refresh)
  task  runInBackground lambda   ReqShield.kt:125:47 PROVEN   (cache write)
  loop  handleLockFailure        ReqShield.kt:181:9  PROVEN   (hole: 5 conjuncts; measure maxAttempt - attempts)
Tool facts: LD_noleak (every thread end), LD_own (unLock consumes Lease), LD_transfer (spawn), TERM
Spec to review: obligation Lease; KeyLock.tryLock creates, KeyLock.unLock consumes; guard LD_write, LD_supplier; ...
Assumptions (not checked by Silicon): A1 errors fatal, A2 lock calls return, A3 accepted tasks run; ...
```

1. 실패 줄은 성질 ID와 Kotlin 위치를 싣는다. 과제의 실패는 과제의 람다 위치다.
2. 메서드 끝의 사후조건(LD_noleak, RET_*)은 경로를 말하지 않는다. 경로 설명은 이름 있는 신뢰 호출 결과(예: "supplier threw, executor accepted")로 한다. Silicon의 counterexample이나 결과를 고정한 재검증으로 얻는다 (추정 1-1.5 인일, 선택).
3. 칸 판정의 열 이름은 C의 것과 같다 (판정, 하위 상태, 실패 의무).

### 6.8 반복문 불변식 hole

1. polling 반복문의 불변식은 두 부분이다. 도구 몫(권한, 읽기만 하는 값, `NO_LEASES`)과 hole 몫(코드 counter와 사건 기록의 관계)이다.
2. hole은 `kv hole`의 기존 흐름을 쓴다: LLM이 쓰고, 도구가 검사한다 (금지어, hole 밖 편집).
3. 이 모델의 hole은 5줄이다. 처음 쓴 것에서 두 번 고쳤다 (5.5절).
4. 대안 (판단): 성질에서 `GAVE_UP`과 RET_poll을 빼면 hole이 필요 없다. 그러면 "기다림이 끝나기 전에 supplier를 부름"(LD_supplier의 fallback 부분)을 잡지 못한다. 권고: hole을 쓴다.

### 6.9 바뀌지 않는 것

1. object·instance 모듈의 lowering과 emitter는 그대로다. client 모듈일 때만 새 경로를 탄다.
2. 기존 gate 4개, `mutantsReqShield` 33회, `mutantsAspect` 30회, AI 코드 결과가 바뀌면 회귀다.

### 6.10 마일스톤과 추정

추정은 FIR과 Viper를 아는 사람 엔지니어 1명 기준이다 (앞 설계들과 같은 기준). AI 에이전트 세션의 시간과는 비교할 수 없다.

| E | 내용 | 수락 테스트 | 추정 (인일) |
|---|---|---|---|
| E0 | core 모듈 컴파일 설정, client 모듈 발견 (공개 함수, private helper, `runAsync`에 닿는 람다), 6.4절 구문의 FIR fixture | `kv discover --client`가 entry 1개와 과제 2개(69행, 125행)를 찾는다 | 0.5-1 |
| E1 | sidecar 문법·resolver (6.2절) | core sidecar의 이름이 FIR symbol에 묶인다. 오류 테스트: 없는 `consumes` 대상, 모르는 예외 종류, 반복문 없는 `hole loop`, `trusted value`의 타입 불일치 | 2-3 |
| E2 | KVIR과 함수 본문 lowering (6.3-6.4절), 과제 전제 유도 (3.4절), ledger | 6.4절 구문마다 정상 1개와 거부 1개. core의 golden KVIR | 5-8 |
| E3 | emitter와 gate (6.6절) | 생성 모델이 `spike/protocol2/target.vpr`과 정규화 후 같다. `vpr_normalize.py`의 `LOCAL`에 `e_`, `u_`, `_at` 형태를 더한다 | 3-4.5 |
| E4 | 보고서, Kotlin 변이 스크립트(M1-M12, C1-C3, A1-A3), 회귀 | 18개 판정이 `expected-verdicts.txt`와 같다. 기존 gate·변이 불일치 0 | 2-3 |
| 합계 | E0-E4 (core) | | 12.5-19.5 |
| E5 | coroutine: 신뢰 모델 4개, `CANCEL` 종류, suspend 함수 값, 변형 sidecar | UE6 결정대로 PROVEN 또는 FAILED. Phase 0 L3 모델의 A_F4, A_F6 결과를 재현 | 3-5 |
| E6 | reactor: 별도 설계 (6.11절) | — | 10-20 (불확실성 큼) |
| E7 (선택) | 경로 설명 (6.7절 2), 깨지는 의무 전체 측정의 도구화 | M5의 누수가 별도 실행에서 보인다 | 1-2 |

위험이 가장 큰 곳은 E2다. v0·B보다 훨씬 넓은 Kotlin(예외, 비지역 return, 함수 타입 값, 람다)을 번역한다.

### 6.11 세 변형의 도달 가능성

1. **core가 먼저다.** 동기 코드이고, 비동기 경계가 `runAsync` 한 곳이다. 이 문서의 모델이 그 형태다.
2. **coroutine은 E5로 닿는다 (판단).** 더할 것:
   - 일시 중단 지점(suspend 호출, `ensureActive`, `delay`)마다 `CANCEL`이 나올 수 있다.
   - `withContext(NonCancellable) { unLock }`(111, 227, 246행)은 취소되지 않고 실행된다.
   - `launch(start = ATOMIC)`(75, 138행)는 과제를 넘긴다. 취소된 scope에서도 블록이 시작된다 (`CoroutineStart.kt` ATOMIC 설명). dispatch가 던지면 `launch`가 던진다 (UE6).
   - `catch (e: Exception)`이 `CancellationException`도 잡는다. 그래서 243행 catch의 해제(246-248행)가 취소에서도 돈다. Error는 잡지 않는다 (UE2).
   - Phase 0의 L3 모델(`spike/reqshield/l3_client.vpr`)이 이 변형의 해제 의무를 이미 보였다 (측정, `spike/RESULTS.md`).
3. **reactor는 막혀 있다 (판단).** 이유:
   - 연산이 값이 아니라 게으른 `Mono` 사슬을 돌려준다. lock은 구독 때 잡힌다. 의미는 신호(onNext, onError, onComplete, cancel)의 순서다.
   - 이 파일은 Reactor API 22종을 쓴다 (측정: `defer`, `switchIfEmpty`, `flatMap`, `subscribe`, `subscribeOn`, `onErrorMap`, `justOrEmpty`, `map`, `thenReturn`, `onErrorResume`, `empty`, `doOnNext`, `doFinally`, `take`, `repeatWhenEmpty`, `just`, `fromSupplier`, `error`, `doOnSuccess`, `doOnError`, `doOnCancel`, `delayElements`). 지금 도구의 Reactor 모델은 `Mono.fromCallable` 하나다.
   - 구독자는 아무 때나 취소할 수 있다. 예: `tryLock`의 Mono가 토큰을 낸 뒤, `flatMap`이 안쪽을 구독하기 전에 취소가 오면 그 토큰을 아무도 풀지 않는다 (lease까지 남음). 이런 창을 모델이 다뤄야 한다.
   - `releaseLock`(327-342행)과 쓰기 사슬(188행, 203행)은 `subscribe()`로 따로 돈다. 해제는 "구독되면 시도된다"이다.
   - 한 가지 길(판단, 측정하지 않음): 연산자 경계마다 `CANCEL`이 나올 수 있는 순차 프로그램으로 바꾼다. `doFinally` = finally, `doOnCancel` = `CANCEL`의 catch, `doOnError` = catch 후 다시 던짐, `subscribe` = 과제. 이 번역이 Reactor 의미에 대해 건전한지 보이는 것이 E6의 일이다.

## 7. 도입 비용: aspect 캐시(B)와 비교

| 항목 | B (aspect 캐시) | E (ReqShield) |
|---|---|---|
| 층 | L1 확장 | 새 층 L3 |
| 새 어휘 | `set-once`, `fresh`/`throws` 신뢰 호출, `S.created`/`gFor` 장부 | 선형 의무, 사건 기록, 과제와 넘기기, 예외 종류, 반복문 hole |
| 새 문법 구성 | 7 | 약 11 (추정) |
| 새 신뢰 모델 | CHM API 5개와 `getOrPut` | core 약 10개 (`KeyLock` 2개는 sidecar), coroutine +4, reactor +22 |
| KVIR 새 노드 | 5 (`RunAction`, `Fresh`, `RacyGet`, `Return`, `Raise`) | 8 (`Entry`, `Task`, `Call`, `Try`/`Catch`, `Throw`, `Spawn`, `While`, `Break`)와 예외 종류 |
| lowering 범위 | action 밖 연산 구간의 일부 | 공개 함수 전체, private helper 인라인, 예외, 반복문, 람다 |
| 목표 모델 | 124줄, 1.94 s, 변이 7 + 대조 2 | 555줄, 2.13-2.14 s, 변이 12 + 대조 3 + 토글 3 |
| 구현 추정 | 7-12 인일 (B0-B4) | 12.5-19.5 인일 (E0-E4), coroutine +3-5, reactor +10-20 |
| 사용자 결정 | 6개 (UB1-UB6) | 6개 (UE1-UE6) |
| 설계·spike의 agent 비용 | 메인 세션이 기록 | 11절 (이 agent), 메인 세션이 사용량을 더한다 |

판단:
1. 도입 비용은 프로토콜이 기존 층에 맞는지에 크게 달려 있다. B는 L1에 맞았고, E는 새 층을 요구했다.
2. 같은 층의 다음 프로토콜(예: 다른 client 코드의 lock 규율)은 E의 기반을 다시 쓴다. 그때의 비용은 sidecar와 신뢰 모델 몇 개로 줄 것이다 (추정).
3. 손 모델 비용은 병목이 아니다: spike는 약 16분이었다 (11절). 비용은 lowering 범위(E2)에 있다.

## 8. `PROTOCOL2.md` 예측의 결과

| 예측 | 결과 | 근거 |
|---|---|---|
| 1. 지금 도구는 `ReqShield`의 연산을 하나도 판정하지 못한다 | 맞음 (측정) | `kv discover`: core 모듈에서 action 3개, 모두 `KeyLocalLock.kt`, `ReqShield.kt`에 0개. `kv lower`(최소 sidecar `spike/protocol2/current-tool-probe.kv`): "unsupported operation getAndSet ...: fun ReqShield.getAndSetReqShieldData has 0 action calls []; an operation has exactly one (DESIGN-v0.md 8.3 U2)", module INCOMPLETE |
| 2. 새 ghost 상태(lock 소유, 해제 의무)와 `KeyLock` 신뢰 계약이 필요하고, "연산당 atomic action 하나" 모델 밖이다 | 맞음 (판단, 3절) | 의무 `Lease`, `creates`/`consumes` 계약. 예측에 없던 것도 나왔다: 예외 경로가 중심이다, 의무 넘기기(과제), 반복문 hole, 사건 기록 |
| 3. 사용자에게 물을 명세 결정이 적어도 하나 나온다 | 맞음 | 6개 (UE1-UE6). 셋(UE2-UE4)은 가정을 끄면 원본이 FAILED가 됨을 측정했다 (A1-A3) |

## 9. 위험

| 위험 | 완화 |
|---|---|
| E2의 lowering 범위가 넓다. AI가 쓴 변형은 지원하지 않는 모양을 자주 낼 것이다 (판단) | 구문마다 fixture. UNSUPPORTED는 구문 이름과 위치를 낸다. AI 코드 실험(AI-CODE.md)과 같은 방식으로 넓힌다 |
| 신뢰 가정이 판정을 정한다 (A1-A3, 측정). 잘못 고르면 결함이 숨는다 (예: A2를 켜면 호출자 data 손실이 보이지 않음) | 가정을 sidecar 신뢰 층에 두고 사용자가 서명한다. 보고서가 늘 싣는다 |
| 메서드 끝의 사후조건 실패는 경로를 말하지 않는다 | 이름 있는 신뢰 호출 결과로 경로를 설명한다 (E7) |
| 항상 켜진 권한 검사가 같은 경로의 다른 실패를 가린다 (측정: M5) | 보고서는 처음 실패를 싣는다. 전체 집합은 선택 측정이다 (285회, 4분 14초) |
| 반복문 불변식이 LLM hole이다. 예외 flag가 든 불변식은 새 종류다 (이 spike에서 2번 고침) | `kv hole` 흐름, 도구 몫과 hole 몫의 분리 |
| 사건 규칙이 프로토콜마다 다르다. 일반화가 덜 되었다 | 고정된 사건 형태 5개로 시작한다. 두 번째 client 프로토콜에서 다시 본다 |
| reactor는 모델이 없다. 취소 창은 정밀한 모델에서 FAILED일 가능성이 크다 (판단) | E6 전에 별도 설계와 사용자 결정 |
| inlining은 메서드를 키운다. coroutine은 일시 중단 지점마다 분기를 더한다 | 측정: 지금 0.48 s. 커지면 helper별 메서드(계약 추론)로 바꾼다 (JE2) |

## 10. 열린 질문

### 10.1 사용자가 정할 것

2.3절의 UE1-UE6.

### 10.2 메인 세션이 정할 것

| ID | 판단 | 이 문서의 의견 |
|---|---|---|
| JE1 | 의무의 인코딩: 추상 predicate와 `forperm`, 또는 ghost counter | predicate. 토큰을 덮어쓴 누수도 잡는다 (측정), 넘기기가 `exhale` 하나다 |
| JE2 | private helper: 인라인, 또는 helper마다 계약 | 인라인. helper 계약을 사람이 쓰지 않아도 된다. 시간이 커지면 다시 본다 |
| JE3 | 과제 전제의 유도 규칙 (3.4절) | 받아들인다. 넘기는 곳의 `exhale`이 확인하므로 거짓 PROVEN이 생기지 않는다 (판단) |
| JE4 | 반복문 불변식: hole, 또는 성질을 줄여 hole을 없앰 | hole (6.8절) |
| JE5 | 사건 기록: 고정 형태, 또는 일반 사건 열 | 고정 형태. 일반 사건 열의 Viper 시간은 재지 않았다 |
| JE6 | `run.sh`의 출력 위치 | 저장소 밖 (인자). `spike/run_mutants.sh protocol2`를 쓰면 `.gitignore`에 `spike/protocol2/mutants/`와 `spike/protocol2/mutants.list`를 더해야 한다 |
| JE7 | L1 `tok(t)`와 L3 `Lease`의 연결 | 뒤로 미룬다 (2.4절 3) |
| JE8 | 이 설계에 critic 검토(CLAUDE.md rule 2)와 advisor 상담 | 이 작업은 advisor를 쓰지 않았다 (지시). 새 층을 여는 결정이므로 메인 세션이 정한다 |

## 11. 비용 기록 (이 agent)

| 단계 | 벽시계 | 토큰 |
|---|---|---|
| 설계 1: 읽기와 분석 (req-shield, DESIGN-v0/B/C/D, sidecar, 신뢰 소스, Viper 기능 실험 4개) | 21:53-22:59 중 rate limit으로 멈춘 시간을 뺀 약 30-40분 (추정: 멈춘 시작 시각을 모른다; 다시 시작한 22:43부터 22:59까지 16분은 측정) | 세션 계수기 약 0.37 M (측정: 15.00 M → 14.63 M) |
| spike: 목표 모델, 도달성, 변이 18개, 실행, 의무 전체 측정, 시간 측정 | 22:59-23:15, 약 16분 (측정) | 세션 계수기 약 0.07 M (측정: 14.63 M → 14.56 M) |
| 설계 2: 인용 수집, 예측 1 측정(`kv discover`, `kv lower`), 이 문서, 인용 확인(76개), cross-memory 기록 | 23:15-23:30, 약 15분 (측정) | 세션 계수기 약 0.15 M (측정: 14.56 M → 14.41 M) |
| 합계 | 약 60-70분 (대기 제외; 첫 구간은 추정) | 세션 계수기 약 0.59 M (측정: 15.00 M → 14.41 M) |

1. "세션 계수기"는 이 agent의 남은 토큰 표시의 감소량이다. 그 정확한 뜻(입력·출력·캐시 포함 여부)은 모른다. 메인 세션이 실제 사용량을 더한다.
2. rate limit 때문에 21:53 시작 뒤 22:43에 다시 시작했다. 그 사이의 대기는 비용에 넣지 않았다.

## 부록 A. 재현

1. 목표 모델: `spike/silicon.sh spike/protocol2/target.vpr` (`TIMES=1`이면 메서드별 시간).
2. 전체 판정: `bash spike/protocol2/run.sh <OUT_DIR>`. `<OUT_DIR>`은 저장소 밖에 둔다. 판정 줄은 `spike/protocol2/expected-verdicts.txt`와 같아야 한다 (시간 표시 제외).
3. 깨지는 의무 전체: `python3 -I spike/protocol2/isolate.py <ISO_DIR> <OUT_DIR>/target.vpr <OUT_DIR>/mutants/*.vpr`. 결과는 `spike/protocol2/isolation.txt`와 같아야 한다.
4. 예측 1 (req-shield checkout에서):
   - `CP` = kotlin-stdlib 2.0.21, slf4j-api 1.7.36, `support/build/libs/support-1.0.0.jar` (`translator/build.gradle.kts`의 `reqShieldClasspath`와 같은 jar)
   - `translator/build/install/kv/bin/kv discover --classpath $CP --kotlinc "-language-version 2.0 -api-version 2.0 -jvm-target 17 -Xjsr305=strict" core/src/main/kotlin`
   - `translator/build/install/kv/bin/kv lower --root <req-shield> --classpath $CP --kotlinc "..." spike/protocol2/current-tool-probe.kv`
5. 이번 세션의 출력 (session-local scratchpad `/private/tmp/claude-501/-Users-ykhfree-AI/cfc00473-f90e-5b9b-b11d-3741f96c0a2e/scratchpad/`): `p2/run2/`(변이 파일과 Silicon 출력), `p2/iso/`(285개 복사본), `exp/q1-q4.vpr`(Viper 기능 실험), `kxsrc181/`, `jdk21src/`, `kstd2021/`(인용한 소스).

## 부록 B. E가 더하는 신뢰 가정 (Silicon이 확인하지 않음)

1. `KeyLock.tryLock`의 null 아닌 결과는 그 key와 종류의 획득이다. 던진 `tryLock`은 호출자에게 의무를 남기지 않는다. 저장소가 그래도 잡은 lock은 lease가 끝낸다.
2. `KeyLock.unLock`은 던져도 해제를 시도한 것으로 친다. 남은 것은 lease가 끝낸다 (`ReqShield.kt:150`).
3. A1, A2, A3 (4절).
4. `runAsync`의 executor는 `Executor.execute`의 계약을 지킨다: 받거나 `RejectedExecutionException`을 던진다 (`Executor.java:132-133`).
5. 사용자 함수(`getCacheFunction`, `setCacheFunction`, supplier)는 이 `ReqShield`의 lock 연산을 부르지 않는다 (재진입 없음).
6. `runCatching`과 `getOrElse`의 inline 본문은 stdlib 2.0.21의 것이다.
7. 같은 실행의 lock 연산은 모두 그 호출의 key를 쓴다 (코드가 `key`를 그대로 넘긴다; 모델의 `HOLDS(k, ...)`가 기대는 사실).

## 부록 C. 근거

| 출처 | 쓴 곳 |
|---|---|
| req-shield `2e792d2`: `core/.../ReqShield.kt`, `KeyLock.kt`, `KeyGlobalLock.kt`, `KeyLocalLock.kt`, `config/ReqShieldConfiguration.kt`; `support/.../ClientException.kt`, `ConfigValues.kt`, `CommonUtils.kt`, `ReqShieldData.kt`, `LocalLockLimit.kt`; coroutine·reactor의 `ReqShield.kt`, `KeyLock.kt`, `ReqShieldConfiguration.kt`; core-spring `ReqShieldAspect.kt`; `README.md`; `CLAUDE.md`; core `ReqShieldTest.kt` | 1-3절 |
| JDK 21 (Corretto 21.0.10 `src.zip`): `CompletableFuture.java`, `Executor.java`, `ThreadPoolExecutor.java`, `ScheduledThreadPoolExecutor.java` | 3.3절, UE4 |
| kotlinx-coroutines-core-jvm 1.8.1 sources jar (Gradle 캐시): `CoroutineStart.kt`, `DispatchedContinuation.kt`, `Executors.kt` | UE6, 6.11절 |
| kotlin-stdlib 2.0.21 sources jar: `Result.kt` | 3.3절 |
| `spike/reqshield/l3_client.vpr`, `mutants.report`, `spike/RESULTS.md` (Phase 0 L3) | 1.3절, UE2, UE6 |
| `spike/protocol2/` (이번 작업) | 5절 |
| `DESIGN-v0.md`, `DESIGN-B.md`, `DESIGN-C.md` 2.1절·10절, `DESIGN-D.md`, `HISTORY.md`, `PROTOCOL2.md` | 전체 |
