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
| 결정 | 사용자가 넷을 정했다 (2026-10-26): UE1 호출 하나의 규율, UE2 Error는 치명적(A1), UE3 "호출자는 이미 얻은 결과를 잃지 않는다", UE4 받은 과제는 실행됨(A3). 남은 것: UE5(서명 차례), UE6(E5 전) | 2.3절 |
| 어휘 | 새 층 L3: 선형 의무 `Lease(k, lt, t)`(Viper 추상 predicate), `forperm` 누수 검사, 사건 기록(trace), 과제(task)와 의무 넘기기, 예외 종류. 지금의 L1 "연산당 atomic action 하나" 모델 밖이다 | 3절 |
| 고침 | 갱신 `tryLock`의 try/catch와 해제 실패를 삼키는 helper `releaseQuietly` (+32줄, -9줄). req-shield 사본에서 기존 테스트 57개 통과 (측정). req-shield에는 적용하지 않았다 | 5.6절 |
| 서명 | `review intent` 아래 16개: 의도 절 12개와 가정 4개(A1, A3, A4, A5). 그 밖에 신뢰 선언 6개와 어휘 승인 1번 | 2.5절 |
| 모델 | 기준 모델 `target.vpr`(A1, A3, A4, A5 켬, A2 끔)에서 원본 core는 **FAILED**: RET_hit, RET_recheck, RET_supplier_error 셋뿐이고 LD_*는 모두 PROVEN (측정). 고친 코드(`fixed-core.patch`)의 모델 `fixed.vpr`은 PROVEN (측정). 결함 변이 12개는 두 모델 모두에서 원본에 없는 의무를 깨뜨린다. 동치 대조 3개는 두 모델 모두에서 원본과 같은 결과다 | 5절 |
| 시간 | 파일마다 Silicon 약 2.2 s (측정: 고친 모델 2.15-2.20 s, 결정 전 원본 2.13-2.14 s). 공개 함수 메서드 약 0.5 s | 5.3절 |
| 지금 도구 | `ReqShield`의 연산을 하나도 판정하지 못한다: `kv discover`가 `ReqShield.kt`에서 action 0개, `kv lower`가 UNSUPPORTED와 INCOMPLETE를 낸다 (측정) | 8절 |
| 도구 변화 | 새 모듈 종류(client), 함수 본문 lowering(예외, `runCatching`, private helper 인라인, 람다 → 과제, 반복문), 신뢰 모델 약 10개, 문법 구성 약 12개, KVIR 노드 8개 | 6절 |
| 변형 | core 먼저. coroutine은 신뢰 모델 4개와 취소 예외를 더하면 닿는다. reactor는 Reactor 연산자 22종의 신호 의미론이 막는다 (별도 설계 필요) | 6.11절 |
| 비용 | E0-E4(core) 12.5-19.5 인일, coroutine +3-5, reactor +10-20 (모두 추정). B(aspect)는 B0-B4 7-12 인일이었다 (추정) | 6.10절, 7절 |
| 예측 | `PROTOCOL2.md`의 예측 3개가 모두 맞았다 (1은 측정, 2는 판단, 3은 질문 6개) | 8절 |

측정한 판정 (`spike/protocol2/run.sh`, 2026-10-26 사용자 결정 뒤). 두 모델에서 각 21개 파일의 "깨지는 의무 전체"를 쟀다 (`isolate.py`: 성질 하나씩만 켠 복사본, 630회).
판정 규칙 (`verdicts.py`): 변이와 토글은 그 모델의 원본에 없는 의무를 깨뜨리면 DETECTED다. 대조는 원본과 같은 의무 집합이면 ACCEPTED다.
표의 R은 기준 모델의 원본이 깨뜨리는 셋이다: RET_hit, RET_recheck, RET_supplier_error.

| 항목 | Kotlin 편집 (요약) | 기준 모델 (A2 끔): 깨지는 의무 | 고친 모델 (`fixed-core.patch`): 깨지는 의무 | 판정 (기준, 고친) |
|---|---|---|---|---|
| 원본 | — | **R**. LD_*는 모두 PROVEN | 없음 (**PROVEN**) | FAILED, PROVEN (둘 다 기대대로) |
| M1 | supplier 실패 때 해제 안 함 (`executeCallable` 264-266 삭제) | LD_noleak, RET_hit, RET_recheck | LD_noleak | DETECTED, DETECTED |
| M2 | executor 거부 때 해제 안 함 (`runInBackground` 149-156 삭제) | LD_noleak + R | LD_noleak | DETECTED, DETECTED |
| M3 | 재확인의 finally가 늘 해제 (100행 조건 삭제): 이중 해제 | LD_own, LD_supplier, LD_transfer, RET_hit, RET_recheck | LD_own, LD_supplier, LD_transfer | DETECTED, DETECTED |
| M4 | fallback 뒤 캐시 쓰기 (`.also { setCacheFunction(...) }`) | LD_write, RET_supplier + R | LD_write, RET_supplier | DETECTED, DETECTED |
| M5 | 낡은 토큰으로 해제 (`lastToken ?: token`) | LD_own + R | LD_own | DETECTED, DETECTED |
| M6 | 잘못된 lock 종류로 해제 (`LockType.CREATE` 고정) | LD_own + R | LD_own (갱신 경로에서만) | DETECTED, DETECTED |
| M7 | lock 없이 갱신 (68행 `if (true)`) | LD_supplier, LD_write + R | LD_supplier, LD_write | DETECTED, DETECTED |
| M8 | ONLY_UPDATE_CACHE에서도 생성 lock (89행 모드 검사 삭제) | MODE_create + R | MODE_create | DETECTED, DETECTED |
| M9 | supplier 실패를 삼킴 (해제 뒤 `null` 반환) | LD_own, LD_transfer, LD_write + R | LD_own, LD_transfer, LD_write, RET_supplier_error | DETECTED, DETECTED |
| M10 | fallback supplier 재시도 | SUP_once + R | SUP_once | DETECTED, DETECTED |
| M11 | 실패한 읽기만 시도로 셈 (182행 `attempts++`를 catch로) | TERM, HOLE + R | TERM, HOLE | DETECTED, DETECTED |
| M12 | 쓰기 전에 해제 (`createReqShieldData`) | LD_own, LD_transfer + R | LD_own, LD_transfer | DETECTED, DETECTED |
| C1 | 쓰기의 finally를 catch + 정상 경로 해제로 | R | 없음 | ACCEPTED, ACCEPTED |
| C2 | 재확인의 flag 없이 경로마다 해제 | R | 없음 | ACCEPTED, ACCEPTED |
| C3 | 갱신 과제를 finally 하나로 해제 | R | 없음 | ACCEPTED, ACCEPTED |
| A1 끔 | `java.lang.Error`가 치명적이지 않음 | LD_noleak, RET_supplier + R | LD_noleak, RET_hit, RET_recheck, RET_supplier, RET_supplier_error | DETECTED, DETECTED |
| A2 켬 | lock 호출은 던지지 않음 (결정 전의 기준 모델) | 없음 (PROVEN) | 없음 (PROVEN) | PROVEN, PROVEN |
| A3 끔 | executor가 받은 과제를 버릴 수 있음 | LD_noleak + R | LD_noleak | DETECTED, DETECTED |
| A4 끔 | 던진 `unLock` 뒤에도 의무가 남음 | LD_noleak + R | LD_noleak | DETECTED, DETECTED |
| A5 끔 | 던진 `tryLock`도 lock을 잡았을 수 있음 | LD_noleak + R | LD_noleak | DETECTED, DETECTED |

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
2. `ReqShield.kt:150`은 "A failed release must not cost the caller its data"라고 쓴다. 이 보호는 executor 거부 경로에만 있다. 재확인의 finally(101행)와 supplier 실패(265행)의 `unLock`이 던지면, 그 예외가 호출자의 data나 `SUPPLIER_ERROR`를 대신한다. 측정: 기준 모델(A2 끔)에서 RET_recheck와 RET_supplier_error가 깨진다 (5.2절).
3. 갱신 경로의 `tryLock`(66행)이 던지면 호출자는 이미 읽은 hit을 잃는다. 측정: 기준 모델(A2 끔)에서 RET_hit이 깨진다 (5.2절).

이 셋은 결함인지 의도인지 문서가 정하지 않았다. 그래서 UE3의 질문이었다. 사용자는 (b)를 골랐다 (2.3절). 그래서 셋은 결함이다. 고침은 5.6절이다.

### 2.3 사용자 결정 (2026-10-26)

| ID | 질문 | 선택지와 측정 (설계 때) | 결정 |
|---|---|---|---|
| UE1 | 성질의 범위: 호출 하나의 규율(LD_*, MODE, SUP_once, RET_*)인가, "key마다 한 번에 supplier 하나"(상호 배제)인가? | 상호 배제는 설계상 성립하지 않는다. fallback이 holder와 함께 supplier를 부른다 (`ReqShield.kt:165-167`). 느린 supplier 중에 lease가 끝날 수 있다. local lock은 상한에서 entry 없는 permit을 준다 (`LocalLockLimit.kt:33-37`). 상호 배제는 `KeyLock` 계약과 lease 시간에 기대는 결과로 보고서에 적는다 (권고였던 것: 호출 하나의 규율) | **결정: 호출 하나의 규율.** 상호 배제는 보고서의 설명으로만 둔다 |
| UE2 | `java.lang.Error`가 지나가는 경로도 lock을 풀어야 하는가? | (a) Error는 치명적이라고 가정한다 (보고서에 싣는다). (b) 성질에 넣는다. 측정(A1): core는 `runAsync` 자체가 Error를 던질 때만 lock이 남는다 (`runInBackground`는 `RejectedExecutionException`만 잡는다). supplier의 Error는 `runCatching`이 잡아 풀어 준다. coroutine은 supplier의 Error에서 풀지 않는다 (`catch (e: Exception)`, 243행; Phase 0 측정 A_F4) (권고였던 것: (a)) | **결정: (a) 가정한다 (A1).** 서명한다 |
| UE3 | lock 저장소가 실패할 때(`tryLock`·`unLock`이 던짐) 호출자는 무엇을 받는가? 던진 `unLock`은 해제로 치는가? | (a) lock 호출은 던지지 않는다고 가정한다 (설계 때의 기준선). (b) RET_*를 그대로 요구한다. 측정(A2): 원본이 RET_hit, RET_recheck, RET_supplier_error에서 FAILED다. LD_*는 그대로 PROVEN이다 (던진 `unLock`은 의무를 소비한다: `ReqShield.kt:150` "the lock then expires on its own"). (b)를 고르면 req-shield 코드를 고쳐야 한다 (2.2절) (권고였던 것: 사용자가 정한다. (a)이면 global lock을 쓰는 사용자에게 가정이 보이게 한다) | **결정: (b) "호출자는 이미 얻은 결과를 잃지 않는다".** RET_hit, RET_recheck, RET_supplier_error를 그대로 요구한다. 그래서 원본 core는 FAILED다 (측정, 5.2절). 고친 코드는 5.6절. 던진 `unLock`이 의무를 끝낸다는 것은 이름 있는 가정 A4로 서명한다 |
| UE4 | executor가 받은 과제는 반드시 실행된다고 가정하는가? | (a) 가정한다. (b) 성질에 넣는다. 측정(A3): 받은 과제를 버릴 수 있으면 원본이 LD_noleak에서 FAILED다. `ThreadPoolExecutor.shutdownNow()`는 "halts the processing of waiting tasks" (JDK `ThreadPoolExecutor.java:1405-1406`). 라이브러리는 사용자의 pool을 끄지 않는다 (`ReqShieldConfiguration.kt:51-52`). core-spring adapter는 자기 pool(`Executors.newScheduledThreadPool`, daemon thread)을 `shutdown()`으로 끈다 (`ReqShieldAspect.kt:263`, 271). `shutdown`은 "previously submitted tasks are executed" (JDK `ScheduledThreadPoolExecutor.java:824-825`). JVM이 끝날 때 daemon thread의 남은 과제는 실행되지 않는다. 그때 local lock은 JVM과 함께 사라지고, global lock은 lease가 끝낸다. 남는 경우의 비용은 lease 하나다 (권고였던 것: (a)) | **결정: (a) 가정한다 (A3).** 서명한다 |
| UE5 | 모드별 쓰기 규칙을 확인한다. 그리고 "polling 뒤 fallback은 캐시를 쓰지 않는다"를 의도 절로 넣는가? | 모드 규칙은 `CLAUDE.md:103-105`가 정한다. fallback의 "쓰지 않음"은 코드(197행)에만 있다. 넣으면 M4 같은 "fallback 결과도 캐시에 쓰자" 변경이 FAILED가 된다 (측정: M4 → LD_write) (권고였던 것: 넣는다 (`source none`, 사용자 서명)) | 미정. 서명 차례에 정한다. 답이 LD_write 절의 모양을 정한다 (2.5절) |
| UE6 | coroutine 변형: `scope.launch(start = ATOMIC)`의 dispatcher가 던지면? | kotlinx.coroutines 1.8.1에서 ATOMIC은 `startCoroutine`을 쓴다 (`CoroutineStart.kt:89`). `resumeWith`가 `dispatcher.dispatch`를 바로 부른다 (`DispatchedContinuation.kt:193-196`). 그래서 dispatch가 던지면 `launch`가 던지고 과제는 실행되지 않는다. coroutine 코드에는 core의 거부 처리(147-157행)에 해당하는 것이 없다: lock이 lease까지 남고, 갱신 경로의 호출자는 hit을 잃는다. Executor 기반 dispatcher는 던지지 않는다 (`Executors.kt:129-136`: 거부되면 job을 취소하고 `Dispatchers.IO`로 보낸다). (a) dispatch는 던지지 않는다고 가정 (Phase 0 F6), (b) 성질에 넣음 → coroutine FAILED (권고였던 것: E5 전에 정한다) | 미정. E5 전에 정한다 |

1. 결정된 넷(UE1-UE4)으로 모델의 기준(canonical)을 다시 정했다: A1, A3, A4, A5는 켜고, A2(lock 호출은 던지지 않음)는 끈다 (4절).
2. 설계 때의 기준 모델(A2 켬)은 토글 `A2_on_lock_calls_return`으로 남는다. 그 모델에서 원본은 PROVEN이다 (측정).

### 2.4 범위 밖 (제안)

1. 상호 배제 (UE1의 다른 선택지). lease 시간과 `KeyLock` 구현에 기대므로 이 모델로는 증명할 수 없다 (판단).
2. 돌려준 `ReqShieldData`의 공유: 호출자와 쓰기 과제가 같은 객체를 쥔다. `ReqShieldData`는 `var value`, `var status`를 가진다. Phase 0의 L3M3가 이 검사(쓰기 권한)를 보였다 (`spike/reqshield/mutants.report`). 라이브러리 코드는 그 필드를 쓰지 않는다.
3. L3 의무 `Lease(k, lt, t)`와 L1의 `tok(t).gHeld`의 연결 (`spike/RESULTS.md` "Not modeled"). `KeyLocalLock`의 L1 증명이 `KeyLock` 신뢰 계약의 일부를 내보내는 길이다. 뒤로 미룬다.

### 2.5 서명 수 (`review intent`)

`DESIGN-C.md` 10절 UC4의 정책: 의도 절마다 `reviewed`와 `source`가 있어야 한다. 아래는 core sidecar에서 사용자가 서명할 것이다. 출처 줄은 `source "<anchor>" quote "..."` 또는 `source none "<결정>"`이다.

| # | 서명할 것 | 종류 | 출처 줄 |
|---|---|---|---|
| 1 | LD_write (`guard setCacheFunction`) | 의도 절 | `CLAUDE.md:104` (ONLY_CREATE_CACHE), `CLAUDE.md:105` (ONLY_UPDATE_CACHE); fallback 부분은 `source none` UE5 (미정) |
| 2 | LD_supplier (`guard Callable.call`) | 의도 절 | `ReqShield.kt:165-167`, `CLAUDE.md:7` |
| 3 | MODE_create | 의도 절 | `ReqShield.kt:88` |
| 4 | MODE_update | 의도 절 | `ReqShield.kt:65` |
| 5 | SUP_once (호출과 갱신 과제) | 의도 절 | `ReqShield.kt:165-167`, `README.md:162` |
| 6 | POLL_bound | 의도 절 | `ReqShield.kt:169-171`, `README.md:159-161` |
| 7 | RET_hit | 의도 절 | `ReqShield.kt:135`, `ReqShieldConfiguration.kt:52-53`, `source none` UE3 (2026-10-26) |
| 8 | RET_get_error | 의도 절 | `ClientException.kt:22` |
| 9 | RET_recheck | 의도 절 | `ReqShield.kt:94`, `source none` UE3 |
| 10 | RET_poll | 의도 절 | `ReqShield.kt:161`, `README.md:159` |
| 11 | RET_supplier | 의도 절 | `ReqShield.kt:135`, `ReqShieldConfiguration.kt:52-53` |
| 12 | RET_supplier_error | 의도 절 | `README.md:162-163`, `source none` UE3 |
| 13 | A1 Error는 치명적이다 | 가정 | `source none` UE2 (2026-10-26) |
| 14 | A3 executor가 받은 과제는 실행된다 | 가정 | `ReqShieldConfiguration.kt:50-52`, `source none` UE4 (2026-10-26) |
| 15 | A4 던진 `unLock`은 의무를 끝낸다 | 가정 | `ReqShield.kt:150` |
| 16 | A5 던진 `tryLock`은 의무를 남기지 않는다 | 가정 | `ReqShieldConfiguration.kt:37-38` |

1. 합계: `review intent` 아래 16개 (의도 절 12개, 가정 4개).
2. UE5는 1번 절의 모양을 정한다. "fallback은 캐시를 쓰지 않는다"이면 절은 지금 모양이다. 아니면 CREATE 경로의 조건에 `|| GAVE_UP`이 붙는다. 어느 쪽이든 절은 하나다. 그래서 개수는 그대로 16이다. 1번의 서명만 UE5를 기다린다.
3. `review intent` 밖에서도 서명할 것이 있다 (`DESIGN-v0.md` 8.3 "reviewed 지문"):
   - 신뢰 선언 6개: `KeyLock.tryLock`, `KeyLock.unLock`, `decideToUpdateCache`, `getCacheFunction`, `setCacheFunction`, `Callable.call`. 각각 `reviewed`와 지문을 단다.
   - 어휘 층 승인 1번: `obligation Lease`, 사건 기록 규칙, `PHASE`, `GAVE_UP` (`DESIGN-C.md` 2.1절 층 3).
4. 도구 사실(LD_noleak, LD_own, LD_transfer, LD_wait, TERM)은 서명하지 않는다. `obligation`과 `consumes`에서 도구가 낸다.
5. 서명 결과 (2026-10-27, 사용자가 묶음 4개를 승인): UE5는 "fallback은 캐시를 쓰지 않는다"로 정했다. 그래서 1번 절의 모양은 그대로다. 의도 절 12개, 가정 4개, 신뢰 선언 6개에 `reviewed`와 지문이 붙었다. 인용 근거 20줄에는 블록 지문이 붙었다 (스냅숏 `specdraft/reqshield-core/snapshot`). 지문의 단위:
   - 함수 본문이 있는 신뢰 선언(`KeyLock.tryLock`, `KeyLock.unLock`, `decideToUpdateCache`): 지금처럼 Kotlin 선언 (B6).
   - 신뢰 값(`getCacheFunction`, `setCacheFunction`): 속성 또는 생성자 `val` 매개변수의 선언. 첫 수식어(없으면 `val`/`var`)부터 끝까지이고, KDoc과 annotation은 밖이다.
   - 소스가 없는 JDK·Kotlin 라이브러리 함수(`Callable.call`): 시그니처 글자 `java.util.concurrent.Callable.call(): V`.
   - 어휘 승인: 문법에 칸이 없다. 승인은 sidecar 머리 주석에만 있고, 도구는 검사하지 않는다 (열린 과제).

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
| `KeyLock.tryLock(k, lt)` | null, 또는 토큰 `t`와 `Lease(k, lt, t)`. 던질 수 있다 (UE3). 던지면 호출자에게 의무가 없다 (A5) | `KeyLock.kt:28-35` | 사람 (인터페이스, 본문 없음) |
| `KeyLock.unLock(k, lt, t)` | `Lease(k, lt, t)`를 요구하고 소비한다 (LD_own). 던질 수 있다 (UE3). 던져도 소비한다 (A4) | `KeyLock.kt:37-46`, `ReqShield.kt:150` | 사람 |
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

기준(canonical) 모델의 가정이다. 사용자 결정(2.3절)을 따른다. 각 가정은 `target.vpr`의 토글(`define`) 하나다.

| ID | 가정 | 기준 모델 | 끄면 (측정, 5.2절) | 근거 |
|---|---|---|---|---|
| A1 | `java.lang.Error`는 프로세스를 끝낸다. 그래서 어떤 호출도 Error를 던지지 않는다 | 켬 (UE2, 서명) | 기준: LD_noleak, RET_supplier가 더 깨진다. 고친 모델: LD_noleak과 RET 넷(RET_hit, RET_recheck, RET_supplier, RET_supplier_error) | `source none` UE2 |
| A2 | `KeyLock.tryLock`·`unLock`은 정상 반환한다 | **끔** (UE3) | 켜면(설계 때의 모델) 원본이 PROVEN이다 | — |
| A3 | executor가 받은 과제는 실행된다 | 켬 (UE4, 서명) | 두 모델 모두 LD_noleak이 더 깨진다 | `ReqShieldConfiguration.kt:50-52`, UE4 |
| A4 | 던진 `unLock`은 의무를 끝낸다. 해제를 시도했고, 남은 lock은 lease가 끝낸다 | 켬 (서명 대상) | 두 모델 모두 LD_noleak이 더 깨진다 (공개 함수와 과제 둘) | `ReqShield.kt:150` "the lock then expires on its own" |
| A5 | 던진 `tryLock`은 호출자에게 의무를 남기지 않는다. 저장소가 그래도 잡은 lock은 lease가 끝낸다 | 켬 (서명 대상) | 두 모델 모두 LD_noleak이 더 깨진다 | `ReqShieldConfiguration.kt:37-38` "must let the lock expire on its own" |

1. A4와 A5는 A2를 끈 결과로 생긴 가정이다. lock 호출이 던질 수 있으면, 던진 호출 뒤의 의무를 정해야 한다.
2. A4를 끄면 던진 `unLock` 뒤에도 의무가 남는다. 코드는 해제를 다시 시도하지 않는다. 그래서 누수가 된다. 다시 시도해도 저장소가 계속 실패하면 끝나지 않는다. 그래서 lease가 유일한 끝이다 (판단).
3. A5를 끄면 호출자가 모르는 토큰의 의무가 남는다. 어떤 코드도 그것을 풀 수 없다 (판단). 모델은 `lostToken(k, lt)`라는 ghost 토큰으로 이것을 나타낸다.
4. 가정은 보고서의 "Assumptions"와 "Spec to review"에 늘 싣는다. 값은 사용자가 서명한다 (2.5절).

## 5. 모델 (spike)

### 5.1 구조

모델은 둘이다. 둘 다 `spike/protocol2/`에 있다.
- 기준(canonical) 모델 `target.vpr` (566줄): req-shield 코드 그대로, 4절의 기준 가정.
- 고친(fixed) 모델 `fixed.vpr`: `fixed-core.patch`를 적용한 코드 (5.6절). `target.vpr`과 다른 줄은 다섯 줄이다 (머리 주석 1줄, 고친 `if` 조건 4줄). `mutants.py`의 `FIX`가 `target.vpr`에서 이 파일을 만든다. `run.sh`는 둘이 같은지 확인한다.

`target.vpr`의 내용:

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

0절의 표가 전체 결과다. 파일: `spike/protocol2/expected-verdicts.txt`(판정 줄 44개), `spike/protocol2/isolation.txt`(파일 42개의 깨지는 의무 전체).

기준 모델의 원본 (UE3 결정 뒤):
1. 깨지는 의무는 정확히 셋이다: RET_hit, RET_recheck, RET_supplier_error.
2. 셋의 원인은 2.2절의 세 곳이다. 갱신 `tryLock`(66행)이 던지면 hit을 잃는다. 재확인 finally의 `unLock`(101행)이 던지면 재확인 결과를 잃는다. supplier 실패 뒤 `unLock`(265행)이 던지면 `SUPPLIER_ERROR`를 잃는다.
3. 그 밖의 의무는 모두 PROVEN이다: LD_noleak, LD_own, LD_transfer, LD_write, LD_supplier, LD_wait, MODE_create, MODE_update, SUP_once, POLL_bound, TERM, hole 불변식, RET_get_error, RET_poll, RET_supplier.

처음 보고된 오류 (Silicon 한 번 실행, `--numberOfErrorsToReport 0`, `first-reports.txt`). 고친 모델의 것이다. 기준 모델에서는 여기에 원본의 R 셋이 더해진다 (M1, M3에서는 RET_supplier_error 없이).

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
| A1 끔 | `NO_LEASES`와 RET 넷 |
| A3 끔, A5 끔 | 공개 함수의 `NO_LEASES` |
| A4 끔 | 공개 함수와 두 과제의 `NO_LEASES` |

관찰:
1. 변이 12개가 깨뜨리는 자기 의무는 두 모델에서 같다. 결정 전(A2 켬)의 측정과도 같다. 고침은 변이의 판별력을 바꾸지 않는다.
2. 기준 모델의 M1에는 RET_supplier_error가 없다. 해제 호출을 지웠으니 그 호출이 던져 `SUPPLIER_ERROR`를 가릴 일도 없다. 대신 lock이 남는다 (LD_noleak). 의무 집합이 이 교환을 보여 준다.
3. 기준 모델의 M3에도 RET_supplier_error가 없다. 이유는 다르다: 항상 켜진 LD_own 검사가 그 경로를 먼저 멈춘다.
4. 과제에 넣은 결함은 과제 메서드에서 보고된다 (M1의 `task_69`, M6, M7). 호출자 쪽 결함은 공개 함수에서 보고된다.
5. M6는 갱신 경로에서만 잘못이다. 모델은 `task_69`의 사본만 거부했다.
6. 항상 켜진 권한 검사(LD_own, LD_transfer)가 실패하면 Silicon은 그 경로를 멈춘다. 그래서 M5에서는 같은 경로의 누수(LD_noleak)가 보고되지 않는다.
7. 대조 C1-C3은 두 모델에서 모두 원본과 같은 의무 집합이다. 해제 위치를 옮긴 동치 변경은 판정을 바꾸지 않는다.
8. 고친 모델에서 A1을 끄면 RET 넷이 깨진다. `releaseQuietly`와 갱신 `tryLock`의 catch는 `Exception`만 잡는다. 그래서 Error는 결과를 가린다. A1(서명)이 이 경우를 뺀다.

### 5.3 시간 (측정)

| 실행 | 시간 |
|---|---|
| 결정 전 원본 (A2 켬), 혼자 3회 | Silicon 2.13-2.14 s, 벽시계 2.34-2.37 s |
| 기준 원본 (A2 끔), 오류를 모두 보고 | Silicon 2.25 s |
| 고친 원본, 혼자 3회 | Silicon 2.15-2.20 s |
| 고친 원본의 메서드별 (`TIMES=1`) | `getAndSetReqShieldData` 0.51 s, `task_69` 0.20 s, `task_125` 0.13 s, 신뢰 모델 0.02-0.07 s |
| 결정 전 변이·대조·토글 18개, 하나씩 | Silicon 2.10-2.26 s |
| `run.sh` 전체: 두 모델의 파일 42개와 도달성 검사 2개를 한 번씩(10개 병렬), 의무 전체 630회(작업자 8) | 벽시계 10분 19초. 한 번씩 돌 때 파일마다 4-10 s (경합) |
| 결정 전 의무 전체 (285회, 작업자 8) | 벽시계 4분 14초 |

비교: aspect 목표 모델 1.94 s (`DESIGN-B.md` 0절), KeyLocalLock 목표 모델 3.93 s (`DESIGN-v0.md` 8.3).

### 5.4 공허하지 않다는 확인 (측정)

1. `spike/protocol2/reach.py`가 분기 24곳에 `refute false`를 넣는다. 두 모델에서 모두 도달 가능하다 (측정: "Refute holds" 오류 0개).
2. 그 분기: 첫 읽기 실패, 갱신 없는 hit, lock을 쥔 갱신 과제, ONLY_CREATE_CACHE의 lock 없는 갱신, 갱신 lock 실패, 갱신 `tryLock`이 던짐, 갱신 거부 뒤 해제, 재확인 hit, 재확인 실패, 재확인 뒤 `unLock`이 던짐, supplier 실패 뒤 해제, supplier 실패 뒤 `unLock`이 던짐, lock을 쥔 쓰기 과제, ONLY_UPDATE_CACHE의 lock 없는 쓰기 과제, 쓰기 거부 뒤 해제, polling hit, 연속 실패로 멈춤, 기다림 중 interrupt, 시도를 다 쓴 fallback, 연속 실패 뒤 fallback, fallback supplier 실패, 갱신 과제의 supplier 실패 뒤 해제, 갱신 과제의 쓰기 뒤 해제, 쓰기 과제의 쓰기 실패 뒤 해제.
3. "던짐" 분기 셋은 A2를 끈 기준 모델에서 새로 생긴 경로다. RET 실패 셋이 이 경로에서 나온다.
3. 판정이 공허하지 않다는 증거는 이것과 변이 12개다. `init`에 해당하는 것은 없다 (모듈 상태가 없다).

### 5.5 처음 쓴 모델에서 고친 것 (측정)

1. hole 불변식 `exc == NONE ==> tr.consec == consecutiveFailures__179`가 보존되지 않았다. polling hit은 사건 규칙이 `consec`을 0으로 하지만 코드는 그 counter를 바꾸지 않고 돌아간다. 조건에 `found_187 == null`을 더했다.
2. RET_poll이 실패했다. 불변식이 "찾았으면 예외가 없다"를 말하지 않았다. `found_187 != null ==> exc == NONE`을 더했다.
3. 둘 다 hole 몫이다. 반복문 불변식을 쓰는 데 Silicon 실행 3회가 들었다. `DESIGN-v0.md`의 E3(LLM이 MI를 씀)와 같은 종류의 일이다.

### 5.6 고친 core (UE3 결정의 결과)

UE3 결정("호출자는 이미 얻은 결과를 잃지 않는다") 아래에서 원본은 세 곳에서 결과를 잃는다 (2.2절). 가장 작은 고침은 다음이다. req-shield에는 적용하지 않았다. 파일: `spike/protocol2/fixed-core.patch`.

1. 갱신 경로의 `tryLock`(66행)을 `try`/`catch (e: Exception)`로 감싼다. 던지면 로그를 남기고 갱신만 건너뛴다. 호출자는 hit을 그대로 받는다.
2. 해제 실패를 삼키는 helper `releaseQuietly`를 둔다. 152행이 이미 쓰는 모양(`catch (unlockError: Exception)`과 로그)을 옮긴 것이다. KDoc은 150행의 문장을 옮긴다.
3. 재확인의 finally(101행)와 supplier 실패(265행)의 `unLock`을 `releaseQuietly`로 바꾼다. 152행도 같은 helper를 쓴다 (같은 코드 셋을 하나로).
4. 바꾸지 않은 것: 생성 경로의 `tryLock`(89행)과 쓰기 과제의 finally `unLock`(243행). 89행이 던질 때 호출자는 아직 결과가 없다 (miss). 243행은 호출자가 이미 돌아간 뒤의 과제다. 그래서 UE3 규칙과 관계가 없다.

```diff
--- a/core/src/main/kotlin/com/linecorp/cse/reqshield/ReqShield.kt
+++ b/core/src/main/kotlin/com/linecorp/cse/reqshield/ReqShield.kt
@@ -63,7 +63,18 @@
         val onlyCreateCache = reqShieldConfig.reqShieldWorkMode == ReqShieldWorkMode.ONLY_CREATE_CACHE
 
         // ONLY_CREATE_CACHE collapses requests on cache creation only, so the update runs without a lock
-        val token = if (onlyCreateCache) null else reqShieldConfig.keyLock.tryLock(key, lockType)
+        val token =
+            if (onlyCreateCache) {
+                null
+            } else {
+                try {
+                    reqShieldConfig.keyLock.tryLock(key, lockType)
+                } catch (e: Exception) {
+                    // The caller already has its data: a failing lock store only skips this refresh
+                    log.warn("Failed to lock key '{}' for a cache refresh, so the refresh is skipped", key, e)
+                    return
+                }
+            }
 
         if (onlyCreateCache || token != null) {
             runInBackground(key, lockType, token) {
@@ -98,7 +109,7 @@
             } finally {
                 // On a miss, the existing creation path keeps the lock until its asynchronous write finishes.
                 if (!cacheCreationRequired) {
-                    reqShieldConfig.keyLock.unLock(key, lockType, token)
+                    releaseQuietly(key, lockType, token, "after the cache recheck")
                 }
             }
         }
@@ -147,12 +158,7 @@
         } catch (e: RejectedExecutionException) {
             log.warn("Executor rejected the background cache task for key '{}', so it is skipped", key, e)
             if (token != null) {
-                // A failed release must not cost the caller its data; the lock then expires on its own
-                try {
-                    reqShieldConfig.keyLock.unLock(key, lockType, token)
-                } catch (unlockError: Exception) {
-                    log.error("Failed to unlock key '{}' after the executor rejected its task", key, unlockError)
-                }
+                releaseQuietly(key, lockType, token, "after the executor rejected its task")
             }
         }
     }
@@ -262,11 +268,28 @@
             callable.call()
         }.getOrElse {
             if (token != null && lockType != null) {
-                reqShieldConfig.keyLock.unLock(key, lockType, token)
+                releaseQuietly(key, lockType, token, "after the supplier failed")
             }
             throw ClientException(ErrorCode.SUPPLIER_ERROR, cause = it)
         }
 
+    /**
+     * Releases the lock identified by [token]. A failed release must not cost the caller a result it already has
+     * (its data, or the error it is about to get), so it is only logged; the lock then expires on its own.
+     */
+    private fun releaseQuietly(
+        key: String,
+        lockType: LockType,
+        token: String,
+        situation: String,
+    ) {
+        try {
+            reqShieldConfig.keyLock.unLock(key, lockType, token)
+        } catch (unlockError: Exception) {
+            log.error("Failed to unlock key '{}' {}", key, situation, unlockError)
+        }
+    }
+
     private fun logAsyncFailure(
         key: String,
         throwable: Throwable,
```

측정:
1. 고친 `ReqShield.kt`는 K2(2.4.21)로 컴파일된다 (`kv discover`, core 소스 사본, 종료 코드 0).
2. req-shield 사본(scratchpad)에 패치를 적용하고 `./gradlew :core:test --offline --tests "com.linecorp.cse.reqshield.ReqShieldTest" --tests "com.linecorp.cse.reqshield.KeyLocalLockTest"`를 돌렸다. 57개 테스트가 모두 통과했다 (44 s). 기존 테스트에서 lock 호출이 던지는 것은 거부 경로의 둘뿐이다 (`ReqShieldTest.kt:321`, `335`). 그래서 이 통과는 회귀가 없다는 뜻이고, 고침의 증거는 아니다. 고침의 증거는 3번의 모델이다.
5. req-shield에 넣을 때 더할 테스트 (판단): (a) 갱신 `tryLock`이 던지면 호출자는 hit을 받고 supplier는 불리지 않는다, (b) 재확인 hit 뒤 `unLock`이 던지면 호출자는 재확인 data를 받는다, (c) supplier와 그 뒤 `unLock`이 모두 던지면 호출자는 `SUPPLIER_ERROR`를 받는다.
3. 고친 모델 `fixed.vpr`은 PROVEN이다. 기준 모델과 같은 의무 전체(LD_* 7개, MODE 2개, SUP_once, POLL_bound, TERM, RET_* 6개)를 검사한다. Silicon 2.15-2.20 s (3회).
4. 고친 모델 위의 결과 (0절 표의 "고친 모델" 열): 변이 12개 모두 DETECTED이고, 깨지는 의무는 결정 전 측정과 같다. 대조 3개는 PROVEN이다. A2를 켜도 PROVEN이다. A3, A4, A5를 끄면 LD_noleak이 깨진다. A1을 끄면 LD_noleak과 RET 넷이 깨진다.

남는 문서와 코드의 차이 (2.2절 1): `ClientException.kt:22`는 lock 함수의 실패를 감싼다고 쓴다. 고친 코드도 89행과 243행의 실패를 감싸지 않는다. 이것은 lock 규율과 무관한 문서 문제다. KDoc을 고칠지 코드를 고칠지는 req-shield 쪽에서 정한다.

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
           | "hole" "loop" KNAME "@" LINE TRIPLE_STRING           // E: the invariant of one loop (replaced by KNAME [#N], 12.5)
           | "assume" ID ":" ANAME                                // E: a named assumption of a built-in model (DESIGN-E 4)
tclause   := ... v0, B ... | "creates" NAME "(" ARGS ")" [ "if" VEXPR ] | "consumes" NAME "(" ARGS ")"
           | "throws" KIND { "," KIND }                           // E: exception kinds (B's bare `throws` = Exception)
event     := "first" CALL | CALL "while" "holds" "(" NAME "(" PATTERN ")" ")" | "count" CALL [ "where" VEXPR ]
           | "last" CALL | "trailing" "failures" "of" NAME
opitem    := ... v0, B ... | "guard" ID ":" CALL "requires" VEXPR // E: an assert before each call of CALL
VEXPR     := ... | "holds" "(" NAME "(" PATTERN ")" ")"            // E: HOLDS; `_` matches any value
```

core `ReqShield`의 sidecar 스케치 (주석과 `reviewed` 제외 약 50줄, 추정):

```text
module com.linecorp.cse.reqshield.ReqShield client
source "core/src/main/kotlin"
type Key = opaque
type Token = opaque
enum LockType = com.linecorp.cse.reqshield.LockType
enum Mode = com.linecorp.cse.reqshield.config.ReqShieldWorkMode
obligation Lease(Key, LockType, Token)

trusted fun KeyLock.tryLock(key: Key, lockType: LockType) returns t: Token? creates Lease(key, lockType, t) if t != null throws Exception
trusted fun KeyLock.unLock(key: Key, lockType: LockType, token: Token) returns released: Bool consumes Lease(key, lockType, token) throws Exception
assume A1: errors-are-fatal
assume A3: accepted-tasks-run
assume A4: throwing-release-ends-obligation
assume A5: throwing-acquire-takes-nothing
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
2. 새 문법 구성은 약 12개다: `client`, `enum`, `obligation`, `creates ... if`, `consumes`, 종류 있는 `throws`, `trusted value`, `trace`(사건 형태 5개), `guard`, `holds`, `hole loop`, `assume`. B는 7개였다 (`DESIGN-B.md` 5.1).
3. `assume`은 도구가 아는 가정 이름만 받는다 (A1, A3, A4, A5). 가정마다 `reviewed`와 `source`가 있어야 한다 (2.5절). sidecar에 없는 가정은 꺼진 것으로 본다. 그러면 보고서가 그 가정에 기대는 의무의 실패를 낸다.
4. 사건 형태를 고정된 몇 개로 둔다 (판단). 일반 사건 열(`Seq[Event]`)과 그 위의 식은 더 일반적이지만, 사람이 읽기 어렵고 Viper 시간이 늘 수 있다 (측정하지 않음).

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
| E1 | sidecar 문법·resolver (6.2절) | core sidecar의 이름이 FIR symbol에 묶인다. 오류 테스트: 없는 `consumes` 대상, 모르는 예외 종류, 반복문 없는 `hole loop`, `trusted value`의 타입 불일치, 모르는 `assume` 이름 | 2-3 |
| E2 | KVIR과 함수 본문 lowering (6.3-6.4절), 과제 전제 유도 (3.4절), ledger | 6.4절 구문마다 정상 1개와 거부 1개. core의 golden KVIR | 5-8 |
| E3 | emitter와 gate (6.6절) | 생성 모델이 `spike/protocol2/target.vpr`과 정규화 후 같다. 고친 Kotlin(`fixed-core.patch`)의 생성 모델이 `fixed.vpr`과 같다. `vpr_normalize.py`의 `LOCAL`에 `e_`, `u_`, `_at` 형태를 더한다 | 3-4.5 |
| E4 | 보고서, Kotlin 변이 스크립트(두 모델 위의 M1-M12, C1-C3, 가정 토글 5개), 회귀 | 판정과 깨지는 의무가 `expected-verdicts.txt`와 같다. 기존 gate·변이 불일치 0 | 2-3 |
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
| 새 문법 구성 | 7 | 약 12 (추정) |
| 새 신뢰 모델 | CHM API 5개와 `getOrPut` | core 약 10개 (`KeyLock` 2개는 sidecar), coroutine +4, reactor +22 |
| KVIR 새 노드 | 5 (`RunAction`, `Fresh`, `RacyGet`, `Return`, `Raise`) | 8 (`Entry`, `Task`, `Call`, `Try`/`Catch`, `Throw`, `Spawn`, `While`, `Break`)와 예외 종류 |
| lowering 범위 | action 밖 연산 구간의 일부 | 공개 함수 전체, private helper 인라인, 예외, 반복문, 람다 |
| 목표 모델 | 124줄, 1.94 s, 변이 7 + 대조 2 | 566줄 두 벌(기준, 고친 코드), 각 약 2.2 s, 변이 12 + 대조 3 + 토글 5를 두 모델 위에서 |
| 구현 추정 | 7-12 인일 (B0-B4) | 12.5-19.5 인일 (E0-E4), coroutine +3-5, reactor +10-20 |
| 사용자 결정 | 6개 (UB1-UB6) | 6개 (UE1-UE6). 넷은 정했고(2.3절), 결과로 원본 core가 FAILED가 되어 고침(5.6절)이 필요해졌다 |
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
| 3. 사용자에게 물을 명세 결정이 적어도 하나 나온다 | 맞음 | 6개 (UE1-UE6). 셋(UE2-UE4)은 가정을 끄면 원본이 FAILED가 됨을 설계 때 측정했다. 사용자는 넷을 정했다 (2.3절). UE3 결정으로 원본 core가 RET 셋에서 FAILED가 되었다 (측정, 5.2절). 예측에 없던 결과다: 명세 결정 하나가 코드 고침(5.6절)을 낳았다 |

## 9. 위험

| 위험 | 완화 |
|---|---|
| E2의 lowering 범위가 넓다. AI가 쓴 변형은 지원하지 않는 모양을 자주 낼 것이다 (판단) | 구문마다 fixture. UNSUPPORTED는 구문 이름과 위치를 낸다. AI 코드 실험(AI-CODE.md)과 같은 방식으로 넓힌다 |
| 신뢰 가정이 판정을 정한다 (A1-A5, 측정). 잘못 고르면 결함이 숨는다 (예: A2를 켜면 호출자 data 손실이 보이지 않는다; 그래서 UE3에서 껐다) | 가정을 sidecar 신뢰 층에 두고 사용자가 서명한다 (2.5절). 보고서가 늘 싣는다 |
| 메서드 끝의 사후조건 실패는 경로를 말하지 않는다 | 이름 있는 신뢰 호출 결과로 경로를 설명한다 (E7) |
| 항상 켜진 권한 검사가 같은 경로의 다른 실패를 가린다 (측정: M5) | 보고서는 처음 실패를 싣는다. 전체 집합은 선택 측정이다 (285회, 4분 14초) |
| 반복문 불변식이 LLM hole이다. 예외 flag가 든 불변식은 새 종류다 (이 spike에서 2번 고침) | `kv hole` 흐름, 도구 몫과 hole 몫의 분리 |
| 사건 규칙이 프로토콜마다 다르다. 일반화가 덜 되었다 | 고정된 사건 형태 5개로 시작한다. 두 번째 client 프로토콜에서 다시 본다 |
| reactor는 모델이 없다. 취소 창은 정밀한 모델에서 FAILED일 가능성이 크다 (판단) | E6 전에 별도 설계와 사용자 결정 |
| inlining은 메서드를 키운다. coroutine은 일시 중단 지점마다 분기를 더한다 | 측정: 지금 0.48 s. 커지면 helper별 메서드(계약 추론)로 바꾼다 (JE2) |

## 10. 열린 질문

### 10.1 사용자가 정할 것

1. 정함 (2026-10-26): UE1-UE4 (2.3절).
2. 남음: UE5 (서명 차례에 정함; LD_write 절의 모양), UE6 (E5 전에 정함).
3. 서명할 것 16개 (2.5절). 그리고 `fixed-core.patch`를 req-shield에 넣을지 (5.6절).

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
| 결정 반영 (2026-10-26 사용자 결정 뒤): 기준 모델 바꿈, 가정 A4·A5, 고친 core 패치와 그 테스트, 두 모델의 변이 재측정, 이 문서 갱신 | 23:41-00:01, 약 20분 (측정; 그중 `run.sh` 10분 19초는 기다림) | 세션 계수기 약 0.12 M (측정: 14.39 M → 14.27 M) |
| 합계 | 약 80-90분 (rate limit 대기 제외; 첫 구간은 추정) | 세션 계수기 약 0.73 M (측정: 15.00 M → 14.27 M) |

1. "세션 계수기"는 이 agent의 남은 토큰 표시의 감소량이다. 그 정확한 뜻(입력·출력·캐시 포함 여부)은 모른다. 메인 세션이 실제 사용량을 더한다.
2. rate limit 때문에 21:53 시작 뒤 22:43에 다시 시작했다. 그 사이의 대기는 비용에 넣지 않았다.

## 부록 A. 재현

1. 두 모델: `spike/silicon.sh --numberOfErrorsToReport 0 spike/protocol2/target.vpr` (기준: RET 셋 FAILED), `spike/silicon.sh spike/protocol2/fixed.vpr` (PROVEN). `TIMES=1`이면 메서드별 시간.
2. 전체: `bash spike/protocol2/run.sh <OUT_DIR>`. `<OUT_DIR>`은 저장소 밖에 둔다.
   - 두 모델을 만들고, `fixed.vpr`이 `mutants.py`의 `FIX`로 만든 것과 같은지 확인한다.
   - 도달성 검사 두 벌, 파일마다 Silicon 한 번(`first-reports.txt`), 의무 전체(`isolation.txt`, `isolate.py`), 판정(`verdicts.py`).
   - 판정 줄은 `spike/protocol2/expected-verdicts.txt`, 의무 전체는 `spike/protocol2/isolation.txt`와 같아야 한다.
3. 예측 1 (req-shield checkout에서):
   - `CP` = kotlin-stdlib 2.0.21, slf4j-api 1.7.36, `support/build/libs/support-1.0.0.jar` (`translator/build.gradle.kts`의 `reqShieldClasspath`와 같은 jar)
   - `translator/build/install/kv/bin/kv discover --classpath $CP --kotlinc "-language-version 2.0 -api-version 2.0 -jvm-target 17 -Xjsr305=strict" core/src/main/kotlin`
   - `translator/build/install/kv/bin/kv lower --root <req-shield> --classpath $CP --kotlinc "..." spike/protocol2/current-tool-probe.kv`
4. 고친 core의 테스트: req-shield를 `rsync -a --exclude build --exclude .gradle --exclude .git`로 저장소 밖에 복사하고, 사본에서 `patch -p1 < spike/protocol2/fixed-core.patch`, `./gradlew :core:test --offline --tests "com.linecorp.cse.reqshield.ReqShieldTest" --tests "com.linecorp.cse.reqshield.KeyLocalLockTest"`.
5. 이번 세션의 출력 (session-local scratchpad `/private/tmp/claude-501/-Users-ykhfree-AI/cfc00473-f90e-5b9b-b11d-3741f96c0a2e/scratchpad/`): `p2/run3/`(두 모델의 파일, Silicon 출력, `first-reports.txt`, `isolation.txt`), `p2/run2/`(결정 전 실행), `rsfix/`(패치한 req-shield 사본과 `test.log`), `fix/`(패치 원본), `exp/q1-q4.vpr`(Viper 기능 실험), `kxsrc181/`, `jdk21src/`, `kstd2021/`(인용한 소스).

## 부록 B. E가 더하는 신뢰 가정 (Silicon이 확인하지 않음)

1. `KeyLock.tryLock`의 null 아닌 결과는 그 key와 종류의 획득이다.
2. A1, A3, A4, A5 (4절). A2는 가정하지 않는다 (UE3).
3. 던진 lock 호출 뒤에 남은 lock은 lease가 끝낸다 (A4, A5의 근거). lease의 길이와 시계는 이 모델 밖이다.
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

## 메인 세션 기록 (2026-10-27, 구현 뒤)

- 3.4 part 1 (과제 전제의 의무): 구현은 "해제 호출이 이름 짓는 의무"가 아니라 "넘기는 쪽(spawn한 쪽)이 만든 의무"를 넘긴다. 그러지 않으면 M5·M6가 LD_own이 아니라 LD_transfer로 실패한다 (E3-E4 agent 측정). 이 문서의 뜻은 구현을 따른다.
- 구현 결과와 예측 대조는 `PROTOCOL2.md` 6절에 있다.

## 12. 프로토콜 2의 남은 틈 (2026-10-28 설계)

날짜: 2026-10-28. 상태: 설계안. 이 작업은 도구 코드, 테스트, sidecar를 바꾸지 않았다.
범위: `PROTOCOL2.md:103`(8절 6번)의 두 틈, 그리고 이 설계 중에 찾은 같은 종류의 틈 하나.
기준: 저장소 `77e50da`(도구 소스는 `0a50f1c`와 같다), req-shield 고정 worktree `2e792d2`, 설치된 `translator/build/install/kv`(소스보다 새것).
표시 규칙은 이 문서 머리와 같다: "측정"은 이번에 실행한 값, "추정"은 실행하지 않은 값, "판단"은 논증이다.
경로 줄임: 코드는 `translator/src/main/kotlin/kv/` 아래, 테스트는 `translator/src/test/kotlin/kv/` 아래, 스크립트는 `translator/scripts/` 아래다.
측정 사본과 출력은 이 세션의 scratchpad `/private/tmp/claude-501/-Users-ykhfree-AI/819151fd-848c-443c-98b0-c7f2397d5cfe/scratchpad/`의 `gap/`, `ref/`, `mutlines/`에 있다 (저장소 밖, 커밋하지 않음). 고친 곳은 아래 표에 모두 적었으므로 다시 만들 수 있다.

### 12.1 요약

| 틈 | 권고 | 근거 (측정) | 사용자에게 물을 것 |
|---|---|---|---|
| 1. 어휘 승인 칸이 없다 | **V-C**: `review vocabulary` 줄과 메모 하나. 지문은 어휘 선언 전부를 덮는다. 주석과 공백은 지문 밖이다 | 어휘 한 단어를 고쳐도 오류가 없다. `recheck`의 lease 종류를 바꾸면 원본 core의 RET_recheck 실패가 보고서에서 사라진다 | 덮는 텍스트의 확인, `class Data`를 넣는지, 메모 문구와 날짜 |
| 1b. 신뢰 선언의 계약 텍스트에 지문이 없다 (새로 찾음) | **T-A**: `review intent` 아래에서 신뢰 선언 메모의 지문이 계약 텍스트도 덮는다 | `KeyLock` 두 줄의 `throws RTE`를 지우면 원본 core가 FAILED에서 PROVEN이 된다. 오류는 없다 | 지금 닫는지. 닫으면 서명된 지문 6개가 바뀐다 (메모 문구는 그대로) |
| 2. `hole loop KNAME@LINE`이 줄 번호로 반복문을 고른다 | **L-B**: `hole loop KNAME`(함수의 유일한 반복문), 여럿이면 `KNAME#N`. `@LINE`은 없앤다 | client 변이 42회 중 26회에서 반복문이 181행 밖에 있다. 지금은 스크립트가 실행마다 sidecar 사본의 줄을 고친다 | 없음. hole은 서명 대상이 아니다. 메인 세션이 정한다 |

1. 틈 1과 틈 1b는 같은 종류다: 서명된 sidecar의 텍스트가 증명의 뜻을 바꾸는데, 어느 지문도 그 텍스트를 덮지 않는다.
2. 틈 2는 서명과 관계없다. 이름 풀기의 견고성 문제다.
3. 세 권고는 판정과 생성 Viper를 바꾸지 않는다 (예측 12.8).

### 12.2 지금 상태

#### 12.2.1 지문이 덮지 않는 텍스트 (측정)

`review intent` 아래에서 지문은 다음을 덮는다:
1. 의도 절과 가정: 절 텍스트와 출처 줄이다 (`sidecar/Ast.kt:309-313`, `sidecar/Check.kt:87`, `307`).
2. 신뢰 선언: Kotlin 쪽만 덮는다. 함수 선언의 텍스트, 신뢰 값의 선언, 또는 소스가 없는 JDK 함수의 시그니처다 (`frontend/Resolver.kt:638-670`, `323-339`, `647-651`).
3. 인용 출처 줄: 블록 지문이 있어야 한다 (`sidecar/Check.kt:113-118`).

다음 텍스트는 어느 지문에도 들지 않는다:
1. 어휘 선언: `type`, `enum`, `class`, `obligation`, `trace`. L1 모듈이면 `ghost record`, `witness`도 그렇다.
2. 신뢰 선언의 sidecar 쪽 텍스트: 매개변수와 결과, `ensures`, `effects`, `creates`, `consumes`, `throws` 종류. 이 절은 이것을 "계약 텍스트"라고 부른다.

측정 방법: 서명된 sidecar의 사본을 한 곳씩 고쳤다. 그리고 `kv resolve`와 `kv verify`를 `--draft` 없이 돌렸다.

| 사본 | 고친 곳 (`reqshield.core.kv`의 행) | `kv resolve` | `kv verify` (원본 core) |
|---|---|---|---|
| x0 | 없음 | exit 0 | FAILED: RET_hit, RET_recheck, RET_supplier_error (18 s) |
| x1 | 41행: `derived GAVE_UP = true` | exit 0 | 재지 않음 |
| x2 | 31행: `recheck`의 `Lease(k, CREATE, _)` → `Lease(k, UPDATE, _)` | exit 0 | FAILED: RET_hit, RET_supplier_error. RET_recheck는 PROVEN이 된다 |
| x4 | 19행: `value: Int = value` → `value: Int = timeToLiveMillis` | exit 0 | FAILED: RET_hit, RET_recheck, RET_supplier, RET_supplier_error |
| x3 | 47행과 49행: `throws RTE`를 지움 | exit 0 | **PROVEN** (4 s) |

1. x2: 어휘 한 단어가 서명된 절 RET_recheck의 뜻을 바꾼다. 그래서 도구가 찾은 실제 결함 하나가 보고서에서 사라진다.
2. x1: `GAVE_UP`이 늘 참이면 LD_supplier의 CREATE 쪽 조건(`... ==> holds(...) || GAVE_UP`, `reqshield.core.kv:98`)도 늘 참이다. 그러면 lock 없는 supplier 호출도 통과한다 (판단).
3. x4: `kv resolve`는 필드의 타입만 본다. sidecar `Int`는 Kotlin `Long`도 대신한다 (`frontend/Resolver.kt:230`). 그래서 다른 속성에 묶어도 오류가 없다.
4. x3: 계약 텍스트를 고치면, 서명하지 않은 A2("lock 호출은 던지지 않음")를 켠 것과 같다. 그래서 사용자 결정 UE3(2.3절)이 서명 없이 되돌려진다.

#### 12.2.2 반복문 이름 (측정)

1. `hole loop KNAME@LINE`은 함수 KNAME 안에서 시작 줄이 LINE인 `FirWhileLoop`이나 `FirDoWhileLoop`을 찾는다 (`frontend/Resolver.kt:398-411`). 그런 반복문이 없으면 `no-loop`이다 (`frontend/Resolver.kt:412`).
2. 찾기는 처음 맞는 반복문에서 멈춘다 (`frontend/Resolver.kt:407-408`). 그래서 한 줄에서 두 반복문이 시작하면 도구는 바깥 것을 고르고 알리지 않는다 (판단).
3. 이름표 `KNAME@LINE`은 세 곳에서 따로 만든다: KVIR의 `While.hole`(`lower/ClientLowering.kt:218`), emitter의 hole 찾기(`emit/ClientEmitter.kt:2014`), 보고서의 행(`verify/ClientReport.kt:230`). 중복 검사는 `(함수, 줄)`을 열쇠로 쓴다 (`sidecar/ClientCheck.kt:158-164`).
4. hole은 지문 대상이 아니다. 그래서 줄이 움직여도 서명은 깨지지 않는다. 대신 이름 풀기가 `no-loop`으로 깨진다. 고친 브랜치는 줄을 187로 고친 사본으로 검증했다 (`PROTOCOL2.md:92`).
5. `./gradlew mutantsClient`(`translator/build.gradle.kts:276-300`)는 줄 이동을 이렇게 견딘다:
   1. 실행마다 편집한 `ReqShield.kt`에서 반복문 머리 줄의 글자(`scripts/mutants.py:394`)를 찾는다.
   2. sidecar 사본의 `@181`을 찾은 줄로 바꾼다 (`scripts/mutants.py:528-547`).
   3. 머리 줄이 정확히 한 번 나오지 않으면 스크립트 오류다 (`scripts/mutants.py:540-541`). 그래서 머리 줄을 고치는 변이는 지금 돌 수 없다.
6. 테스트의 고친 사본도 같은 방법을 쓴다 (`client/Protocol2.kt:19-31`, 테스트 경로).
7. 측정: `mutants.py`의 `make_root`로 client 변이 42개의 사본을 만들고 머리 줄을 셌다.
   - 42회 중 26회에서 반복문이 181행 밖에 있다. 줄은 173-191행 사이의 10가지다. 고친 기준의 21회는 모두 181행 밖이다.
   - 모든 사본에서 반복문 문장은 하나다.
   - 변이 편집 글자와 `fixed-core.patch`에는 `while`, `for`, `do` 반복문 글자가 없다.

`@LINE` 형식을 쓰는 곳 (전체, grep으로 측정):

| 종류 | 곳 |
|---|---|
| sidecar | `specs/reqshield/reqshield.core.kv:150` |
| 코드 | `sidecar/Parser.kt:406-424`, `sidecar/Ast.kt:395-411`, `frontend/Resolver.kt:398-415`, `sidecar/ClientCheck.kt:158-164`, `lower/ClientLowering.kt:218`, `emit/ClientEmitter.kt:2014`, `verify/ClientReport.kt:229-230` |
| 스크립트 | `scripts/mutants.py:393-394`, `528-547` |
| 테스트 | `cli/ClientCliTest.kt:43`, `client/ClientResolveTest.kt:64`, `72`, `78`, `85-90`, `client/ClientVerifyTest.kt:32`, `client/Protocol2.kt:18-31`, `scripts/MutantScriptTest.kt:223`, `241-243`, `sidecar/ClientGrammarTest.kt:54`, `88`, `228`, `client/ClientEmitterTest.kt:203` |
| 테스트 자료 | `translator/src/test/resources/golden/client/reqshield.core.kvir:96`, `translator/src/test/resources/golden/README.md:327` |
| 문서 | `DESIGN-E.md:497`, `548`, `README.md:107`, `PROTOCOL2.md:82`, `92`, `103`, `HISTORY.md:366` |

### 12.3 틈 1: 어휘 승인 칸

#### 12.3.1 어휘 선언

| 종류 | 파서 | 어휘로 보는 근거 |
|---|---|---|
| `type` | `sidecar/Parser.kt:291-303` | `DESIGN-C.md:61` (층 3) |
| `enum` | `sidecar/Parser.kt:348-352` | 서명 때의 머리 주석이 승인한 어휘를 "types, enums, obligation, trace"로 적었다 (`reqshield.core.kv:5`) |
| `class` | `sidecar/Parser.kt:331-346` | x4가 뜻을 바꾼다. 단, `DESIGN-C.md:59`는 class의 필드 묶기를 층 1에 둔다. 그래서 사용자가 정한다 (12.3.7 질문 2) |
| `obligation` | `sidecar/Parser.kt:353-362` | DESIGN-E 2.5 3번(`DESIGN-E.md:183`), 3.6(`DESIGN-E.md:277`) |
| `trace` (사건 필드와 `derived`) | `sidecar/Parser.kt:582-620` | 같은 곳의 "사건 기록 규칙, PHASE, GAVE_UP" |
| `ghost record` | `sidecar/Parser.kt:374-389` | `DESIGN-C.md:61` (L1 모듈의 층 3) |
| `witness` | `sidecar/Parser.kt:390-395` | `DESIGN-C.md:61` |

어휘가 아닌 선언과 그 이유:
1. `module`, `source`, `include`, `use`, `state`, 연산 선택자, `free`, `result`는 층 1이다. `kv resolve`가 코드와 맞춘다 (`DESIGN-C.md:59`).
2. 의도 절과 `assume`은 자기 메모가 있다.
3. 신뢰 선언은 자기 메모가 있다. 계약 텍스트는 12.4절에서 다룬다.
4. `hole`은 층 5다. Silicon이 검사한다.
5. `set-once`(L1)는 성질 ID를 가진 절이다. 2026-10-28에 의도 절이 되었다: 출처 줄과 자기 메모를 받는다 (JG5, `DESIGN-C.md` 10.14).
6. `review intent`는 정책 표시다.

규칙은 모듈 종류가 아니라 `review intent`에 묶는다. 이유는 둘이다:
1. 층 3은 L1 모듈에도 있다 (`DESIGN-C.md:61`).
2. 지금 `review intent`를 쓰는 sidecar는 `reqshield.core.kv` 하나다 (측정: `specs/`, `pilot-c/`, `specdraft/`, `translator/scripts/`를 grep). 그래서 여섯 기존 모듈은 이 규칙의 영향을 받지 않는다. 그 모듈들은 UC4의 이동 때 따른다 (`DESIGN-C.md:628`).

#### 12.3.2 지문 단위 (세 선택지에 공통)

1. 어휘 텍스트는 불러온 sidecar의 어휘 선언을 `Sidecar.decls` 순서로 이은 것이다. `include`한 파일의 선언도 그 자리에서 든다 (`sidecar/Parser.kt:7-22`).
2. 선언 하나는 첫 키워드부터 마지막 토큰까지다. `class`, `trace`, `ghost record`는 닫는 `}`까지다.
3. 선언마다 `//` 주석을 줄 끝까지 지운다. 단, 문자열 `"..."` 안의 `//`는 지우지 않는다.
4. 선언 사이에 줄바꿈 하나를 넣는다.
5. 그 뒤 기존 지문 규칙을 쓴다: 공백 묶음은 공백 하나로, 양 끝은 자르고, UTF-8 SHA-256의 앞 16자리를 쓴다 (`sidecar/Fingerprint.kt:5-17`).

| 고침 | 지문 |
|---|---|
| 공백, 줄바꿈, 들여쓰기 | 그대로 |
| `//` 주석을 더하거나 고치거나 지움 | 그대로 |
| 토큰 하나를 바꿈 (이름, 연산자, 사건 형태, 패턴, 묶은 Kotlin 이름) | 바뀜 |
| 어휘 선언을 더하거나 지움 | 바뀜 |
| 어휘 선언끼리 순서를 바꿈 | 바뀜 |
| 어휘 선언을 다른 종류의 선언 너머로 옮김 (어휘끼리 순서는 그대로) | 그대로 |

주석을 지문 밖에 두는 이유:
1. 도구는 주석을 읽지 않는다. 그래서 주석을 고쳐도 증명의 뜻은 바뀌지 않는다.
2. 서명된 `trace` 블록 안에는 줄 끝 주석이 6개 있다 (`reqshield.core.kv:30-32`, `39-41`). 주석을 지문에 넣으면, 주석을 손볼 때마다 다시 서명해야 한다.
3. 의도 절의 단위는 여러 줄 식 안의 주석 줄을 포함한다 (`sidecar/Parser.kt:207-213`, `702`). 이 설계는 그 규칙을 바꾸지 않는다. 바꾸면 기존 지문의 뜻이 바뀐다.

참값 (측정, 이 규칙을 따른 참조 스크립트 `ref/vocab_fp.py`):
1. 지금 서명된 sidecar의 어휘 선언은 7개다 (15-19행, 22행, 29-42행). 지문은 `92a6a3bbf1f4b44b`다.
2. `class Data`를 빼면 지문은 `61a0636ba3629c85`다.
3. 줄 끝 주석 하나와 들여쓰기 하나를 고친 사본(`ref/cmt.kv`)의 지문도 `92a6a3bbf1f4b44b`다.
4. x1, x2, x4의 지문은 각각 `edea1c3703f16826`, `d28491c19f519c8a`, `7c8e6a6b957599bb`다.

#### 12.3.3 선택지

**V-A: 어휘 블록**

```text
decl  := ... | "vocabulary" "{" { vdecl } "}" [ "reviewed" STRING [ "sha256" STRING ] ]
vdecl := type | enum | class | obligation | trace | ghost-record | witness   // the declarations of 12.3.1
```

1. 지문 단위: 블록 안의 선언이다 (12.3.2 규칙).
2. `review intent` 아래에서 블록 밖의 어휘 선언은 `unreviewed`다. 이 규칙이 없으면, 선언을 블록 밖으로 옮겨 지문을 피할 수 있다.
3. 서명된 sidecar: 15-42행을 블록 안으로 옮긴다.

**V-B: 선언마다 메모**

```text
type Key = opaque
  reviewed "<note>" sha256 "<hex>"
obligation Lease(Key, LockType, Token)
  reviewed "<note>" sha256 "<hex>"
trace { ... }
  reviewed "<note>" sha256 "<hex>"
```

1. 지문 단위: 그 선언 하나다 (12.3.2 규칙).
2. `review intent` 아래에서 메모 없는 어휘 선언은 `unreviewed`다.
3. 서명된 sidecar: 메모 7개가 생긴다.
4. 지금 문법은 `type` 뒤의 `reviewed`를 문법 오류로 본다 (`sidecar/Parser.kt:430`, 테스트 `sidecar/ProbeGrammarTest.kt:98`). V-B는 이 규칙을 바꾼다.

**V-C: `review vocabulary` 줄 하나 (권고)**

```text
decl := ... | "review" "intent"
           | "review" "vocabulary" [ "reviewed" STRING [ "sha256" STRING ] ]   // DESIGN-E 12: the vocabulary note
```

1. 지문 단위: 불러온 sidecar의 어휘 선언 전부다 (12.3.2). 도구는 위치가 아니라 종류로 고른다.
2. 파서: `review intent`의 가지(`sidecar/Parser.kt:426-429`)에 `vocabulary`를 더한다. 메모는 기존 `topReview()`(`sidecar/Parser.kt:828-833`)로 읽는다.
3. 서명된 sidecar: `trace` 블록 바로 뒤에 3줄을 더한다 (관례).

```text
// The vocabulary note covers every type, enum, class, obligation and trace declaration of this sidecar (DESIGN-E 12.3).
review vocabulary
  reviewed "<note the user approves>" sha256 "92a6a3bbf1f4b44b"
```

#### 12.3.4 검사 규칙 (V-C)

검사는 `checkSidecar`(FIR 전, `sidecar/Check.kt:65-91`)에서 돈다. 그래서 서명 흐름의 첫 단계에서 의도 절 지문과 함께 나온다.

| 조건 | 오류 코드 | 위치 | `--draft`이면 |
|---|---|---|---|
| `review intent`가 있고, 어휘 선언이 있고, `review vocabulary`가 없다 | `unreviewed` | 첫 어휘 선언 | 오류 아님 |
| `review intent`가 있고, `review vocabulary`에 메모가 없다 | `unreviewed` | `review vocabulary` | 오류 아님 |
| 메모에 지문이 없다 | `missing-fingerprint` (메시지에 지금 지문) | `reviewed` | 오류 아님 |
| 지문이 다르다 | `stale-review` (메시지에 적힌 값, 지금 값, 덮는 선언의 이름 전부) | `reviewed` | 오류 |
| `review vocabulary`가 둘 이상 | `duplicate-decl` | 둘째 것 | 오류 |

1. `review intent`가 없으면 도구는 어휘 메모를 요구하지 않는다. 메모가 있으면 지문은 검사한다. 이것은 의도 절의 규칙과 같다 (`sidecar/ProbeGrammarTest.kt:139-149`가 고정한 동작).
2. `--draft`의 뜻은 지금과 같다: 메모와 지문이 없어도 되지만, 적힌 지문이 틀리면 오류다 (`sidecar/Check.kt:14-16`).
3. 어휘 종류는 sealed `Decl`(`sidecar/Ast.kt:54-56`)에 대한 `when` 하나로 고른다. 그 `when`에는 `else` 가지를 두지 않는다. 그러면 새 선언 종류를 더할 때 컴파일러가 분류를 요구한다. 그래서 새 종류가 조용히 지문 밖에 남지 않는다.
4. 지문 비교는 `sidecar/Check.kt:316-329`의 `fingerprint`를 다시 쓴다. 지금 그 함수는 `IntentClause`를 받는다. 메모, 지문, 위치, 단위 텍스트를 받도록 넓힌다.
5. `stale-review` 메시지의 예: `the vocabulary changed after its review: the note records sha256 "…", the vocabulary (type Key, type Token, enum LockType, enum Mode, class Data, obligation Lease, trace) now has sha256 "…"; a person must review it again`.

#### 12.3.5 기존 sidecar와 테스트에 미치는 영향

1. `reqshield.core.kv`: 규칙이 들어가면, 메모가 생길 때까지 `unreviewed` 하나로 깨진다. 그동안 `kv resolve`는 exit 1, `kv verify`는 ERROR, `mutantsClient`는 실패다. 그래서 규칙과 메모는 한 커밋에 넣는다. 메모는 사용자 승인 뒤에 쓴다.
2. 여섯 기존 모듈의 sidecar 파일 8개에는 `review intent`가 없다 (측정). 그래서 새 오류가 없고, 출력도 그대로다 (예측 12.8의 6).
3. 기대값이 바뀌는 테스트 (회귀가 아니다):
   - `cli/ClientCliTest.kt:26`, `51`: 메모 수가 22에서 23이 된다.
   - `sidecar/ClientGrammarTest.kt:113-117`: `unreviewed`가 22개에서 23개가 된다 (신뢰 선언 6, 어휘 1).
   - `sidecar/ProbeGrammarTest.kt:125`, `151`, `161`의 `--draft` 없는 검사: 고정 자료 `intent()`(`sidecar/ProbeGrammarTest.kt:105-122`)에 `type`과 `class`가 있다. 그래서 `unreviewed`가 하나 더 나온다. 고정 자료에 서명된 어휘 메모를 넣는다.
   - `sidecar/ProbeGrammarTest.kt:99`(`review intents`의 문법 오류)는 그대로 통과한다. 새 메시지도 `'intent'`를 담는다.
   - `sidecar/ClientGrammarTest.kt:163`(가정의 출처 줄)은 그대로 통과한다. `--draft`이면 어휘 오류가 없고, `--draft` 없는 단언은 오류 코드의 집합(`unsourced`, `unreviewed`)을 비교한다.

#### 12.3.6 권고: V-C

1. V-C와 V-A는 가깝다. 두 선택지의 보장은 같다. V-A도 블록 밖의 어휘를 막는 규칙이 필요하고, 그 규칙은 V-C의 "종류로 고르기"와 같다.
2. V-A가 더하는 것은 눈에 보이는 블록 하나다. 그 값으로 V-A는 중첩 파싱, 서명된 sidecar 28줄의 이동, `include` 파일마다의 블록을 치른다. 블록을 빼도 동작이 같다. 그래서 블록을 뺀다.
3. V-B는 승인 1번(`DESIGN-E.md:183`)을 메모 7개로 나눈다. 메모마다 세션이 승인을 풀어 쓴다. 그래서 사용자가 보지 않은 메모 문구(`PROTOCOL2.md:100`)가 1개가 아니라 7개 생긴다. V-B는 기존 문법 규칙 하나도 바꾼다.
4. V-B의 장점은 `stale-review`가 고친 선언을 바로 가리킨다는 것이다. 메인 세션은 무엇을 고쳤는지 알기 때문에 이 장점은 작다 (판단).
5. V-C의 약점은 메모가 덮는 텍스트가 파일에서 바로 보이지 않는다는 것이다. 완화: 오류 메시지가 덮는 선언의 이름을 모두 싣는다. sidecar에는 주석 한 줄을 둔다.

#### 12.3.7 메인 세션이 사용자에게 물을 것

사용자는 2026-10-27에 묶음 4("신뢰 선언 6개와 어휘")를 승인했다 (`PROTOCOL2.md:98`). 그러나 어휘 메모의 문구와 지문은 새 서명 내용이다. 그래서 메인 세션은 다음을 한 질문으로 묻는다:
1. 덮는 텍스트: 12.3.2의 7개 선언(주석 뺌)을 보여 준다. 사용자가 10-27에 본 것은 한국어 설명과 절 미리보기였다 (`PROTOCOL2.md:100`). 그래서 텍스트나 그 한국어 설명을 보여 주고 "10-27에 승인한 어휘가 이것인가"를 묻는다. 메인 세션은 묻기 전에 `git log -p specs/reqshield/reqshield.core.kv`로 이 텍스트가 서명 커밋 `0a50f1c` 뒤에 바뀌지 않았음을 확인한다.
2. `class Data`(필드 묶기)를 어휘에 넣는가. 권고는 넣는 것이다 (x4). 넣지 않으면 지문은 `61a0636ba3629c85`이고, 필드 묶기는 지금처럼 지문 밖에 남는다.
3. 메모 문구와 날짜. 승인은 10-27이고 기록은 10-28이다. 예: `"ykhfree 2026-10-27 (recorded 2026-10-28): the vocabulary of the lock-discipline protocol: types, enums, the Data binding, obligation Lease, the trace rules with PHASE and GAVE_UP (DESIGN-E 3.1, 3.2)"`.
4. 틈 1b(12.4절)를 같은 라운드에 닫는가. 닫으면(T-A) 신뢰 선언 6개의 계약 텍스트(`reqshield.core.kv:47`, `49`, `51`, `53`, `55`, `65`)도 보여 주고 승인을 받는다. 메모 문구는 그대로이고 지문만 바뀐다.

### 12.4 틈 1b (새로 찾음): 신뢰 선언의 계약 텍스트

1. 신뢰 선언 메모의 지문은 Kotlin 쪽만 덮는다 (12.2.1의 2). 정책의 글도 그렇다: "대상은 trusted fun이면 그 Kotlin 선언" (`DESIGN-v0.md:1127`).
2. 그래서 서명 뒤에 계약 텍스트가 바뀌어도 도구는 알리지 않는다. x3이 그 예다: 원본 core가 PROVEN이 된다.
3. 이 길은 가정의 서명 정책을 비켜 간다. `review intent`는 `assume`마다 메모를 요구한다 (`sidecar/Check.kt:105-120`). 그러나 `throws RTE`를 지우면 A2를 서명 없이 켠 것과 같다.

| 선택지 | 내용 | 좋은 점 | 나쁜 점 |
|---|---|---|---|
| T-A (권고) | `review intent` 아래에서 신뢰 선언 메모의 지문 단위 = 계약 텍스트(주석 뺌, `reviewed` 절 뺌) + 줄바꿈 + 지금의 Kotlin 단위 | `stale-review`가 그 선언을 가리킨다. 메모 수는 그대로다. 메모 하나가 선언 하나를 지킨다 | 서명된 지문 6개가 바뀐다 (메모 문구는 그대로). 단위가 `review intent`에 따라 다르다 |
| T-B | 계약 텍스트를 어휘 텍스트(12.3.2)에 넣는다 | 기존 지문 6개가 그대로다 | 신뢰 선언 하나를 메모 둘이 지킨다. 계약을 고쳐도 `stale-review`는 어휘 메모를 가리킨다. "어휘"라는 이름이 맞지 않는다 |
| T-C | 지금 닫지 않고 기록만 한다 | 비용이 없다 | x3의 길이 열려 있다 |

1. T-A를 `review intent` 밖에도 쓰면 L1 sidecar의 신뢰 메모 5개가 `stale-review`가 된다 (`keylocallock.common.kv:36`, `42`, `aspect.core-spring.kv:13`, `aspect.coroutine.kv:13`, `aspect.webflux.kv:13`). 그러면 여섯 모듈의 판정이 ERROR가 된다. 그래서 T-A는 `review intent` 아래로 한정한다.
2. T-A의 검사 위치: resolver가 FIR 뒤에 신뢰 선언 지문을 검사한다 (`frontend/Resolver.kt:672-685`). 그래서 서명 흐름의 두 번째 단계에서 새 지문 6개가 나온다.
3. 단위의 경계: `trusted()`는 절의 순서를 고정하지 않는다 (`sidecar/Parser.kt:488-560`). 그래서 단위는 "선언 전체에서 `reviewed` 절을 뺀 것"으로 정한다.

### 12.5 틈 2: 반복문 이름

#### 12.5.1 선택지

세 선택지에서 "함수의 반복문"은 함수 KNAME의 FIR 안에 있는 `FirWhileLoop`과 `FirDoWhileLoop`이다. 함수 안의 람다도 지금처럼 포함한다 (`frontend/Resolver.kt:404-411`). 순서는 전위 순서다: 바깥 반복문이 그 안의 반복문보다 앞이다. Kotlin `for`는 FIR에서 `while`로 바뀐다고 본다 (판단, 12.7 D8에서 확인).

**L-A: 순번만**

```text
| "hole" "loop" KNAME "#" N TRIPLE_STRING          // the N-th loop of fun KNAME, from 1, in source pre-order
```

**L-B: 유일한 반복문, 여럿이면 순번 (권고)**

```text
| "hole" "loop" KNAME [ "#" N ] TRIPLE_STRING      // without #N: the only loop of fun KNAME
```

**L-C: 조건 글자**

```text
| "hole" "loop" KNAME "while" STRING TRIPLE_STRING // the loop whose condition text, spaces normalized, is STRING
```

일찍 뺀 것:
1. Kotlin label(`poll@ while ...`): req-shield 코드를 고쳐야 한다. AI가 쓴 구현에는 label이 없다.
2. 함수 첫 줄부터 센 줄 번호: 함수 안에서 반복문 위를 고치면 깨진다.

#### 12.5.2 무엇이 그대로인가

| 고침 | 지금 `@LINE` | L-A | L-B | L-C |
|---|---|---|---|---|
| 함수 밖, 반복문 위 (`fixed-core.patch`, M2, M5, M12, C2, C3) | 깨짐 (`no-loop`) | 그대로 | 그대로 | 그대로 |
| 함수 안, 반복문 위의 문장 | 깨짐 | 그대로 | 그대로 | 그대로 |
| 반복문 몸체 (M11) | 그대로 | 그대로 | 그대로 | 그대로 |
| 반복문 조건 (예: 기계적 변이 ROR의 `<` → `<=`) | 그대로 | 그대로 | 그대로 | 깨짐 (`no-loop`) |
| 같은 함수에 반복문 하나를 대상 앞에 더함 | 깨지거나 다른 반복문에 묶임 | 다른 반복문에 묶임 (알림 없음) | `ambiguous` | 그대로 (조건이 다르면) |
| 같은 함수에 반복문 하나를 대상 뒤에 더함 | 그대로 | 그대로 | `ambiguous` | 그대로 (조건이 다르면) |
| client 변이 42회 (12.2.2의 7) | 26회 깨짐. 스크립트가 줄을 고친다 | 42회 그대로 | 42회 그대로 | 42회 그대로 |
| 다른 구현(AI 코드)의 같은 함수 | 줄이 다르면 깨짐 | 반복문이 하나면 그대로 | 반복문이 하나면 그대로 | 조건 글자가 같을 때만 그대로 |

1. 잘못 묶여도 거짓 PROVEN은 나오지 않는다 (판단). hole은 반복문 불변식이고, Silicon이 처음과 유지를 검사한다. 그래서 잘못 묶이면 HOLE FAILED나 이름 오류(ERROR)가 난다.
2. 그래도 잘못 묶이면 원인을 찾기 어렵다. 그래서 L-B는 그 경우를 `ambiguous`로 바로 알린다.

#### 12.5.3 오류와 옛 형식 (L-B)

| 경우 | 오류 코드 | 메시지 (예) |
|---|---|---|
| 함수가 없거나 overload가 여럿 | `unresolved` (지금과 같음, `frontend/Resolver.kt:401`) | `no single function 'C.f'` |
| `KNAME`, 반복문 0개 | `no-loop` | `hole loop C.f: fun f has no loop` |
| `KNAME`, 반복문 2개 이상 | `ambiguous` (기존 코드, `sidecar/Check.kt:342`, `frontend/Resolver.kt:349`) | `hole loop C.f: fun f has 2 loops (lines 14, 20); name one: C.f#1 or C.f#2` |
| `KNAME#N`, N이 반복문 수보다 큼 | `no-loop` | `hole loop C.f#3: fun f has 2 loop(s), so #3 names none` |
| 두 hole이 한 반복문에 묶임 (`C.f`와 `C.f#1`) | `duplicate-decl` (resolver) | `hole loop C.f#1 names the loop of hole loop C.f at <pos>` |
| 같은 글자의 hole 둘 (`C.f#1` 두 번) | `duplicate-decl` (`sidecar/ClientCheck.kt:158-164`, 열쇠만 바꿈) | 지금 메시지와 같은 모양 |
| `KNAME@LINE` | `syntax` | `hole loop names a loop by its function: write 'hole loop KNAME' (its only loop) or 'hole loop KNAME#N' (its N-th loop); a line number moves with every edit above the loop` |
| `#0`, `#` 뒤에 숫자 없음, `#`와 숫자 사이 공백 | `syntax` | `expected a loop number from 1 after '#'` |

옛 `@LINE` 형식은 받지 않는다 (권고). 이유:
1. 이 형식을 쓰는 sidecar는 하나다 (`reqshield.core.kv:150`). hole은 지문 대상이 아니다. 그래서 고쳐도 서명 비용이 없다.
2. 두 형식을 두면 이름 풀기 길이 둘이 되고, 테스트도 두 벌이 된다.
3. 줄 정보는 `kv resolve`의 묶음 줄과 보고서의 위치 열에 남는다 (예: `@ReqShield.kt:181:9`).

#### 12.5.4 권고: L-B

1. L-A와 L-B는 가깝다. 차이는 함수에 반복문이 하나 더 생길 때뿐이다. 새 반복문이 대상 앞이면 L-A는 알리지 않고 다른 반복문에 묶는다. L-B는 새 반복문이 앞이든 뒤든 `ambiguous`로 멈춘다.
2. L-C는 반복문 조건을 고치면 깨진다. 기계적 변이(ROR)를 client 모듈에 쓰면 조건 변이마다 ERROR가 된다 (판단; 지금 `scripts/mechanical.py:52-59`는 client 모듈을 돌리지 않는다). L-C는 다른 구현에도 약하다.
3. `PROTOCOL2.md:82`는 "함수 안 순서로 묶는 방식"을 뒤로 미룬 안으로 적었다. L-B는 그 안에 "유일하면 순번 생략"을 더한 것이다.
4. 이름표: `LoopRef.label` 하나가 `KNAME` 또는 `KNAME#N`을 낸다. 12.2.2 3번의 세 문자열 틀을 이것으로 바꾼다.
5. 지금 sidecar의 새 머리 줄은 `hole loop ReqShield.handleLockFailure """`이다.
6. 생성 Viper는 바뀌지 않는다. 이름표는 Viper에 나오지 않는다 (측정: x0의 Viper에서 hole 줄의 주석은 `// hole`뿐이다). 변수 이름 `brk_181_9`는 Kotlin 위치에서 온다.

#### 12.5.5 바꿀 곳 (L-B)

1. `sidecar/Parser.kt:406-424`: `@LINE` 대신 `[#N]`을 읽는다. 옛 형식에는 안내 오류를 낸다.
2. `sidecar/Ast.kt:395-399`: `LoopRef(fn, ordinal: Int?)`와 `label`.
3. `frontend/Resolver.kt:398-415`: 함수의 반복문을 전위 순서로 모두 모은 뒤 고른다. 같은 반복문에 묶인 hole 둘은 `duplicate-decl`이다.
4. `sidecar/ClientCheck.kt:158-164`: 열쇠를 `(함수, 순번)`으로 바꾼다.
5. `lower/ClientLowering.kt:218`, `emit/ClientEmitter.kt:2014`, `verify/ClientReport.kt:230`: `label`을 쓴다.
6. `scripts/mutants.py:393-394`, `528-547`: 줄 바꿔 쓰기를 지운다. 가정 토글의 편집은 남긴다.
7. `client/Protocol2.kt:18-31`(테스트): `sidecarFor`를 지운다. 고친 사본도 서명된 sidecar를 그대로 쓴다.
8. 테스트, golden, 문서: 12.2.2의 표.

### 12.6 구현 순서

1. 메인 세션이 12.3.7의 질문을 사용자에게 묻는다 (틈 1b 포함). 답 전에는 틈 1과 틈 1b의 규칙을 커밋하지 않는다.
2. 고치기 전의 출력을 저장한다: 여섯 모듈의 `kv discover`, `resolve`, `lower`, `emit` 출력과 두 core 사본의 생성 Viper.
3. 틈 2(L-B)를 구현한다. 서명은 필요 없다. 12.7의 D 묶음과 R 묶음을 돌린다.
4. 틈 1(V-C)의 문법, 지문 단위, 검사를 구현한다.
5. 사용자가 고르면 틈 1b(T-A)를 구현한다.
6. 서명된 sidecar에 `review vocabulary`와 메모를 넣는다. 지문은 `0000000000000000`으로 둔다.
7. `kv resolve`를 `--draft` 없이 돌린다. 첫 단계에서 어휘 지문이 `stale-review`로 나온다. 그 값을 메모에 옮긴다.
8. 다시 돌린다. 두 번째 단계에서 신뢰 선언 지문 6개가 나온다 (T-A일 때). 그 값을 옮긴다.
9. 어휘 지문이 12.3.2의 참값과 같은지 본다. 다르면 구현이 12.3.2 규칙과 다르다. 원인을 찾은 뒤에 다음으로 간다.
10. 테스트 전체, `mutantsReqShield`, `mutantsAspect`, `mutantsClient`를 돌린다.
11. 12.7 R6의 고의 고장을 하나씩 넣고, 지정한 테스트가 실패하는지 본다.
12. `HISTORY.md`, `PROTOCOL2.md` 8절, 이 절에 결과를 적는다.

### 12.7 테스트 목록

"**실패**"는 오류가 나야 하는 경우다.

**A. 어휘 문법과 검사 (FIR 없음)**

| # | 입력 | 기대 |
|---|---|---|
| A1 | `review vocabulary` 다음 줄 `reviewed "n" sha256 "h"` | 선언 1개, 메모 `n`, 지문 `h` |
| A2 | 메모 없는 `review vocabulary` | 메모 없는 선언 1개 |
| A3 | `review vocab` | **실패**: 문법 오류, "expected 'intent' or 'vocabulary'" |
| A4 | `type Key = opaque` 다음 줄 `reviewed "a"` | **실패**: 지금과 같은 문법 오류 "'reviewed' must follow" |
| A5 | `review intent`, 어휘 있음, `review vocabulary` 없음, `--draft` 없음 | **실패**: `unreviewed` 1개. 위치는 첫 어휘 선언. 메시지에 덮는 선언의 이름 전부 |
| A6 | A5와 같고 `--draft` | 오류 없음 |
| A7 | 메모 없는 `review vocabulary` | `--draft` 없이 **실패** (`unreviewed`). `--draft`이면 오류 없음 |
| A8 | 지문 없는 메모 | `--draft` 없이 **실패** (`missing-fingerprint`, 메시지의 값 = 참조 스크립트의 값). `--draft`이면 오류 없음 |
| A9 | 맞는 지문 | 오류 없음 |
| A10 | 틀린 지문 | **실패**: `stale-review`. `--draft`에서도 **실패** |
| A11 | 종류마다 토큰 하나를 고침: `type` 이름, `enum` 클래스, `class` 필드 묶기, `obligation` 인자 타입, 사건 형태(`count` → `last`), `where` 식, `derived` 식 | 7개 모두 **실패**: `stale-review` |
| A12 | 주석만 고침(`trace` 줄 끝 주석, `type` 뒤의 새 주석), 들여쓰기만 고침 | 오류 없음 |
| A13 | 어휘 선언 둘의 순서를 바꿈 | **실패**: `stale-review` |
| A14 | 어휘 선언 하나를 `use` 줄 아래로 옮김 (어휘끼리 순서는 그대로) | 오류 없음 |
| A15 | `trace` 필드 하나를 더함 | **실패**: `stale-review` |
| A16 | `type T = template "a//${Long}"`에서 `//` 뒤의 글자를 고침 | **실패**: `stale-review` (문자열 안의 `//`는 주석이 아니다) |
| A17 | `review intent` 없음, 어휘 있음, 메모 없음 | 오류 없음 (기존 L1 sidecar의 동작) |
| A18 | `review intent` 없음, `review vocabulary`에 틀린 지문 | **실패**: `stale-review` |
| A19 | `review vocabulary` 두 번 | **실패**: `duplicate-decl` |
| A20 | 어휘는 `include`한 파일에, 메모는 루트 파일에 있다. 포함 파일의 어휘를 고침 | **실패**: `stale-review` |
| A21 | `review intent` 아래의 L1 고정 자료: `ghost record` 필드 종류 `ends` → `frozen`, `witness` 대상 바꿈 | 둘 다 **실패**: `stale-review` |

**B. 서명된 sidecar**

| # | 입력 | 기대 |
|---|---|---|
| B1 | 서명된 `reqshield.core.kv`(어휘 메모 포함), `checkSidecar`, `--draft` 없음 | 오류 없음. 의도 절은 16개 그대로 |
| B2 | B1에서 `reviewed "`로 시작하는 줄을 지운 사본 | `--draft`이면 오류 없음. `--draft` 없으면 `unreviewed` 23개 (신뢰 선언 6, 어휘 1) |
| B3 | CLI `kv resolve`, B2의 사본 | exit 1, `error[unreviewed]` 23줄. `--draft`이면 exit 0 |
| B4 | 측정 사본 x1, x2, x4에 B1의 메모를 더함 | 셋 다 **실패**: exit 1, `stale-review` 1개, 위치는 어휘 메모. 지금은 셋 다 exit 0 (측정) |
| B5 | 주석과 들여쓰기만 고친 사본 (`ref/cmt.kv`의 고침) | exit 0 |

**C. 틈 1b (T-A를 고를 때)**

| # | 입력 | 기대 |
|---|---|---|
| C1 | x3 (두 `throws RTE`를 지움) | **실패**: exit 1, `stale-review` 2개 (tryLock과 unLock의 메모). 지금은 exit 0이고 verify는 PROVEN (측정) |
| C2 | 신뢰 선언의 계약 텍스트에서 주석만 고침 | 오류 없음 |
| C3 | 신뢰 선언의 `reviewed` 절을 `throws` 앞으로 옮김 | 오류 없음 (단위에서 `reviewed` 절을 뺀다) |
| C4 | `review intent`가 없는 L1 sidecar 8개 | 신뢰 메모 5개의 지문이 그대로다. 오류 없음 |
| C5 | 신뢰 함수의 Kotlin 본문을 고친 사본 | **실패**: `stale-review` (지금 동작 그대로) |

**D. 반복문 이름 (L-B)**

| # | 입력 | 기대 |
|---|---|---|
| D1 | `hole loop C.f """…"""`, `hole loop C.f#2 """…"""` | `LoopRef(C.f, null)`, `LoopRef(C.f, 2)` |
| D2 | `hole loop C.f@14`, `C.f#0`, `C.f#x`, `C.f# 2` | 넷 다 **실패**: 문법 오류. 첫째의 메시지는 두 새 형식을 안내한다 |
| D3 | 반복문 하나인 함수, `C.f` | 묶인다. 묶음 줄은 `hole loop C.f -> loop of fun … @C.kt:L:C` |
| D4 | 반복문 없는 함수, `C.f`와 `C.f#1` | 둘 다 **실패**: `no-loop` |
| D5 | 반복문 둘인 함수: `C.f`, `C.f#2`, `C.f#3` | **실패** `ambiguous` (두 줄 번호와 `#1`, `#2` 안내), 둘째 반복문, **실패** `no-loop` |
| D6 | 중첩 반복문: `#1`, `#2` | 바깥, 안쪽 (전위 순서) |
| D7 | 함수 안 람다 속의 반복문 | 센다 (지금 `@LINE`과 같은 범위) |
| D8 | `for` 다음에 `while`이 있는 함수: `#2` | `while` 반복문 (FIR 고정 자료로 확인한다. 다르면 12.5.1의 판단을 고친다) |
| D9 | `C.f`와 `C.f#1`이 한 반복문을 가리킴. `C.f#1`이 두 번 나옴 | **실패** `duplicate-decl` (resolver). **실패** `duplicate-decl` (sidecar 검사) |
| D10 | 함수 위에 6줄을 더한 사본. 함수 안 반복문 위에 `val` 줄을 더한 사본 | 같은 반복문에 묶인다. 위치 열만 바뀐다 |
| D11 | golden KVIR | 96행이 `[hole ReqShield.handleLockFailure]`가 된다. 다른 줄은 그대로다 |
| D12 | 보고서 행: 원본, 그리고 서명된 sidecar를 그대로 쓴 고친 사본 | `loop ReqShield.handleLockFailure … ReqShield.kt:181:9 PROVEN`, `…:187:9 PROVEN` |
| D13 | 변이 스크립트 테스트 (`scripts/MutantScriptTest.kt:223`) | 토글이 아닌 실행의 sidecar 사본이 서명된 sidecar와 바이트 단위로 같다. `@187` 단언은 지운다 |

**R. 회귀 (모두 필수)**

| # | 확인 | 기대 |
|---|---|---|
| R1 | 테스트 전체 | 모두 통과 (마지막 기록 687개에 새 테스트를 더함) |
| R2 | `mutantsReqShield`, `mutantsAspect`, `mutantsClient` | 33회, 30회, 42회. 불일치 0 |
| R3 | 원본 core, 고친 사본 (`client/ClientVerifyTest.kt`) | 원본은 FAILED이고 실패 성질은 정확히 RET_hit, RET_recheck, RET_supplier_error다. 고친 사본은 sidecar 사본 없이 PROVEN이다 |
| R4 | 여섯 기존 모듈의 `kv discover`, `resolve`, `lower`, `emit` 출력 | 12.6의 2에서 저장한 출력과 바이트 단위로 같다 |
| R5 | 원본 core와 고친 사본의 생성 Viper | 저장한 출력과 바이트 단위로 같다 |
| R6 | 고의 고장 (하나씩 넣고 되돌림) | a. 어휘 규칙을 끔 → A5, A10, B4가 실패한다. b. 주석 지우기를 뺌 → A12, B5가 실패한다. c. 문자열을 무시하고 `//`를 지움 → A16이 실패한다. d. 어휘 종류에서 `trace`를 뺌 → A11의 사건 3개와 B4의 x1, x2가 실패한다. e. 반복문을 후위 순서로 셈 → D6이 실패한다. f. 반복문이 여럿일 때 `KNAME`이 첫 것을 고름 → D5가 실패한다. g. T-A를 `review intent` 밖에도 씀 → C4, R4가 실패한다. h. T-A 단위에 주석을 남김 → C2가 실패한다 |

### 12.8 구현 전 예측 (결과 전에 등록)

1. 어휘 지문: 도구가 지금 서명된 텍스트에서 계산한 값은 `92a6a3bbf1f4b44b`다 (`class Data` 포함). `class Data`를 빼면 `61a0636ba3629c85`다.
2. x1, x2, x4(어휘 메모를 더한 사본): `kv resolve`가 exit 1이다. `stale-review`는 정확히 1개이고, 위치는 어휘 메모다. 지금은 셋 다 exit 0이다 (측정).
3. x3: T-A이면 `stale-review`가 정확히 2개(tryLock, unLock)다. T-C이면 exit 0 그대로다.
4. 주석과 들여쓰기만 고친 사본: exit 0.
5. 판정 불변: 원본 core는 FAILED이고, 실패 성질은 정확히 RET_hit, RET_recheck, RET_supplier_error다. 고친 사본은 PROVEN이다. 변이 33·30·42회의 불일치는 0이다.
6. 여섯 기존 모듈의 `kv discover`, `resolve`, `lower`, `emit` 출력은 바이트 단위로 같다.
7. 두 core 사본의 생성 Viper는 바이트 단위로 같다.
8. `mutantsClient`에서 토글이 아닌 실행 32회는 서명된 sidecar와 바이트 단위로 같은 내용을 읽는다. 토글 실행 10회의 sidecar는 토글의 `assume` 편집만 다르다. 지금은 42회 중 26회가 줄을 고친 사본을 읽는다 (측정, 12.2.2의 7).
9. 사용자 서명: 새 메모 1개가 생긴다. T-A이면 지문 6개도 바뀐다 (메모 문구는 그대로). 사용자 질문은 1번이다.
10. 실제 크기는 12.9 추정의 0.5-1.5배 안이다.

### 12.9 크기 (추정)

| 부분 | 코드 | 테스트 |
|---|---|---|
| 틈 1 (V-C) | 약 100줄: Ast 약 55 (새 선언, 종류 `when`, 단위 텍스트, 주석 지우기), Parser 약 17, Check 약 30 | 약 150줄 (A, B 묶음) |
| 틈 1b (T-A) | 약 25줄 (Parser 약 10, Resolver 약 15) | 약 40줄 (C 묶음) |
| 틈 2 (L-B) | 약 +50줄, −20줄. `mutants.py` 약 −15줄. `Protocol2.kt` 약 −12줄 | 약 100줄 (D 묶음, 기존 단언 약 12개의 고침 포함) |
| 합 | 약 +175줄, −50줄 | 약 290줄 |

비교: 서명 라운드의 도구 보완은 코드와 테스트를 합쳐 약 250줄이었다 (`PROTOCOL2.md:104`).

### 12.10 열린 질문

사용자가 정할 것: 12.3.7의 1-4.

메인 세션이 정할 것:

| ID | 질문 | 이 절의 의견 |
|---|---|---|
| JG1 | 옛 `@LINE` 형식을 없애는가, 한동안 받는가 | 없앤다 (12.5.3) |
| JG2 | 어휘 메모의 위치 관례 | `trace` 블록 바로 뒤. 문법은 위치를 따지지 않는다 |
| JG3 | 보고서의 "Spec to review" 줄에 어휘 메모를 싣는가 | 싣지 않는다. 범위를 좁힌다 |
| JG4 | 층 1 묶기의 같은 위험: `free` 값을 같은 타입의 다른 속성에 묶어도 `kv resolve`는 타입만 본다 (`frontend/Resolver.kt:381-393`) | 이 설계는 다루지 않는다. 2026-10-29에 측정하고 `free-duplicate`, `free-unused`로 일부 닫았다 (`DESIGN-C.md` 10.16) |
| JG5 | L1 sidecar가 `review intent`로 옮길 때 `set-once`의 메모 | 그 이동 때 정한다. 결정 (2026-10-28, 사용자 승인): 의도 절로 만든다 (`DESIGN-C.md` 10.14) |
| JG6 | 이 설계에 critic 검토를 받는가 | 틈 1의 V-A와 V-C가 가깝다. 다만 되돌리는 비용은 작다 (sidecar 3줄과 검사 하나). 메인 세션이 정한다 |

### 12.11 이 agent의 기록

1. advisor는 쓰지 않았다 (지시).
2. 실행: `kv resolve` 5회(각 약 2 s), `kv verify` 4회(4-19 s), `make_root` 42회. 모두 scratchpad 사본에서 했다.
3. 토큰과 시간은 재지 않았다.

### 12.12 구현 결과 (2026-10-28, 메인 세션 기록)

사용자 결정 (12.3.7): 덮는 텍스트를 승인했다. `class Data`를 넣는다. 제안 메모 문구를 쓴다. 틈 1b를 이번에 닫는다.
구현: 틈 2(L-B)는 feature-developer, 틈 1(V-C)과 틈 1b(T-A)는 메인 세션이 했다.

| 예측 (12.8) | 결과 (측정) |
|---|---|
| 1. 어휘 지문 `92a6a3bbf1f4b44b` | 맞음. 도구가 서명된 텍스트에서 같은 값을 냈다 |
| 2. x1, x2, x4: exit 1, `stale-review` 1개 (어휘 메모) | 맞음 |
| 3. x3: `stale-review` 2개 (tryLock, unLock) | 맞음 |
| 4. 주석과 들여쓰기만 고친 사본: exit 0 | 맞음 |
| 5. 판정 불변, 변이 33·30·42회 불일치 0 | 맞음. 원본 core는 RET_hit, RET_recheck, RET_supplier_error에서 FAILED, 고친 사본은 sidecar 사본 없이 PROVEN |
| 6. 여섯 L1 모듈의 출력이 바이트 단위로 같음 | 맞음 |
| 7. 두 core 사본의 Viper가 바이트 단위로 같음 | 맞음. 단, 위치표(`.vpr.positions`)의 sidecar 줄 번호는 바뀌었다. sidecar에 머리 주석 1줄과 어휘 메모 4줄이 더해졌기 때문이다. 예측은 위치표를 다루지 않았다 |
| 8. `mutantsClient`의 토글 아닌 32회가 서명된 sidecar를 그대로 읽음 | 맞음 (L-B 구현 때 측정) |
| 9. 새 메모 1개, 지문 6개 바뀜, 사용자 질문 1번 | 맞음 |
| 10. 크기가 추정의 0.5-1.5배 | 일부 맞음. 코드는 +246/-63줄(추정 +175/-50의 약 1.4배)이다. 테스트는 약 +515줄(추정 290의 약 1.8배)이다 |

1. 새 지문: 어휘 `92a6a3bbf1f4b44b`; 신뢰 선언 tryLock `edbefb5d3b80e3d3`, unLock `de7d774d8fb8971e`, getCacheFunction `26acb803f63d7e83`, setCacheFunction `6a8a9f3a7bdd1a04`, Callable.call `fefb2fd846c172ba`, decideToUpdateCache `a858dc2f51d5892c`. 메인 세션이 Callable.call의 값을 손으로 다시 계산했다 (계약 텍스트, 줄바꿈, 시그니처).
2. 12.3.5 목록에 빠진 테스트: `frontend/ClientTrustedReviewTest.kt`의 3개는 옛 신뢰 지문을 글자로 적어 두었다. T-A 단위로 기대값을 다시 고정했다.
3. 테스트 도우미 `rereviewed`(`Harness.kt`)가 어휘 메모도 다시 찍는다. 어휘가 주제가 아닌 테스트가 sidecar 사본을 고칠 때 쓴다.
4. 고의 고장 R6 (하나씩 넣고 되돌림): a, b, c, e, f, g, h는 지정한 테스트가 잡았다. d(어휘 종류에서 `trace`를 뺌)는 `when`에서 가지를 지우면 컴파일 오류가 난다 (12.3.4 3번의 의도). `trace`를 null 쪽 가지로 옮긴 d2는 테스트 7개가 잡았다.
5. 판단: `#01`은 `#1`로 받는다 (L-B 구현 agent).
