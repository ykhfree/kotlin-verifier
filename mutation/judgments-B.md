# 판정 B: KeyLocalLock FAILED 변이 19개

판정자: B (독립 판정). 날짜: 2026-10-24.
대상: `mutation/judge/index.md`의 "FAILED, 예측 7.2" 9개와 "FAILED, 무작위 표본" 10개.
읽지 않은 문서: MUTATION.md 7절, HISTORY.md, mutation/summary.md, results.jsonl의 `tags`. cross-memory도 조회하지 않았다. 다른 사람의 예측이 섞이지 않게 하기 위해서다.

## 0. 판정 기준과 증거 종류

### 0.1 성질의 뜻

1. 이 판정은 sidecar 성질을 글자 그대로 읽는다. 성질은 action(compute 람다 1회 실행) 하나의 사전·사후 상태에 대한 진술이다. `old(e)`는 그 action의 사전 상태다 (DESIGN-v0.md 4.6).
2. 자유 값(`now`, `t`, 경쟁 읽기)은 실제 실행에서 그 값이 가진 값을 쓴다.
3. 성질은 sidecar의 `issue`, `ensures`(P1a, P1a_entry, progress_free, progress_live, P1, progress_owner), `ends_only_if`(P3a 세 개), `unbacked_only_if`(P3b), `invariant P2`, `lemma P3c`다.
4. hole `MI_INV`(MI_INV_1..5)는 귀납 불변식이다. 이것은 증명 도구이고 성질이 아니다. 그래서 변이가 MI_INV_i만 깨고 어떤 성질도 깨지 않으면 오경보다.
5. 판정 셋의 뜻은 과제의 정의를 따른다.
   - detected: 변이된 Kotlin 코드의 실제 실행 하나가 성질 하나를 깬다.
   - false alarm: 변이된 코드가 모든 실행에서 모든 성질을 지킨다.
   - unresolved: 둘 다 세우지 못했다.

### 0.2 증거 세 종류

모든 증거 파일은 `/Users/ykhfree/AI/kotlin-verifier/mutation/judge-work/B/` 아래에 있다. 아래에서 이 경로를 `B/`로 줄인다.

1. **K (실제 코드 실행).** 검증기가 쓴 변이 소스 트리(`translator/build/kv/mechanical/keylocallock.<변형>/roots/<id>`)를 고치지 않고 컴파일해서 실행했다.
   - 원본 트리(`/Users/ykhfree/IdeaProjects/req-shield`)에서 같은 시나리오를 돌려 대조했다.
   - 스레드 순서는 `lockMap` 객체만 바꿔서 강제했다. 클래스 초기화 뒤에 `Unsafe`로 정적 필드를 `HookedMap`(ConcurrentHashMap 하위 클래스, 동작은 같고 `containsKey`·`compute` 앞뒤에 스레드별 hook만 있음)으로 바꿨다. JIT가 정적 final 값을 상수로 접지 않게 `-Xint`로 실행했다.
   - hook은 스레드를 `containsKey` 직전이나 compute 직전에 멈추게 한다. 그동안 다른 스레드가 진행한다. 이것은 "스레드가 그 지점에서 잠시 밀려난다"는 합법적인 스케줄이다.
   - 각 실행은 원본과 변이에 서로 다른 기대값을 검사한다. 예: 변이에서는 B가 token을 받아야 하고, 원본에서는 null을 받아야 한다. 그래서 원본 출력의 `PASS ...: false`는 원본이 원래대로 동작했다는 뜻이다.
   - 다시 실행: `B/kotlin/runall.sh`. 결과: `B/kotlin/out/<변형>__<시나리오>__<빌드>.out`. 23개 시나리오 × (원본, 변이) = 46회 실행이 모두 기대대로 나왔다.
2. **S (Silicon 결정적 시나리오).** 변이 모델(`translator/build/kv/mechanical/.../verify/<id>.vpr`)의 init 본문과 action 본문을 차례로 인라인하고 자유 값을 모두 고정했다 (`B/gen/scenario.py`, `B/gen/specs.py`).
   - "성질이 이 실행에서 거짓이다"를 `assert`로 적었다. 실행이 결정적이므로 assert가 검증되면 그 실행에서 성질이 실제로 거짓이다.
   - 끝의 `refute false`가 경로가 실현 가능함을 확인한다.
   - 음성 대조 3개(`B/scenarios/controls/`)는 기대대로 실패했다. 그래서 assert가 공허하게 통과한 것이 아니다.
   - 다시 실행: `B/gen/run_scenarios.sh`. 결과: `B/out/<이름>.out`. 34개가 모두 검증되었다.
3. **P (Silicon 탐침).** 모델 사본을 조금 고쳐 실행했다 (`B/gen/weaken.py`, `B/gen/holder_rely.py`). 결과: `B/out/<이름>.out`.
   - `order`: action 메서드에서 sidecar 성질 `ensures`를 MI_INV_i `ensures`보다 앞으로 옮겼다. 다른 것은 바꾸지 않았다.
   - `dead-MI`, `free-MI`: MI_INV_1이 죽은 entry(permits 0, 잡히지 않음, token 없음) 또는 빈 entry(permits 1, 잡히지 않음, token 없음)도 허용하게 넓혔다. MI_INV_4는 token이 있는 entry에만 적용했다.
   - `live-held`, `holder-rely`: 성질을 좁게 읽으면 판정이 바뀌는지 확인하는 탐침이다 (0.4절).

### 0.3 Silicon 보고에 대한 관찰 (탐침으로 확인)

Silicon은 한 경로에서 처음 실패한 사후조건만 보고한다. 생성된 `ensures` 순서는 MI_INV_1..5가 sidecar 성질보다 앞이다 (DESIGN-v0.md 5.2).
그래서 같은 경로에서 성질도 깨져도 MI_INV_i만 보고될 수 있다.

- core/RET-06: 원래 순서에서는 MI_INV_1만 보고되었다. `order` 탐침에서는 unLock P1이 보고되었다 (`B/out/core__RET-06__order.out`).
- core/SDL-12: 원래 순서에서는 MI_INV_4만 보고되었다. `order` 탐침에서는 progress_free가 보고되었다 (`B/out/core__SDL-12__order.out`).
- AOR-03(core, reactor)과 SDL-06: `order` 탐침에서도 MI_INV_4만 남았다. 같은 action 안에서는 성질이 깨지지 않는다는 뜻이다. 위반은 다음 action에서 생긴다 (각 항목 참고).
- reactor/SDL-02: `order` 탐침에서도 MI_INV_1만 남았다.

### 0.4 읽는 방법에 따라 판정이 바뀌는 항목

글자 그대로의 읽기(0.1절)에서는 18개가 detected다. 아래 두 무리는 성질을 "의도"대로 좁게 읽으면 false alarm이 된다. 탐침으로 확인했다.

| 무리 | 좁은 읽기 | 그 읽기에서의 결과 | 탐침 |
|---|---|---|---|
| RACY-03 ×3, coroutine/RET-11 | progress_live를 "잡힌(held) entry가 막는다"로 읽음 | progress_live를 그렇게 좁히고 MI_INV_1을 빈 entry까지 넓히면 모델 전체가 증명된다 | `B/out/*__free-MI__live-held.out` (4개 모두 성공) |
| RACY-06 ×3 | progress_owner를 "자기 tryLock에서 받은 token을 낸 호출자"로 읽음 | 그 rely를 unLock에 넣으면 모델 전체가 증명된다 | `B/out/*__RACY-06__holder-rely.out` (3개 모두 성공) |

이 판정은 sidecar 원문을 기준으로 한다. 그래서 이 7개는 detected로 둔다. 그러나 이 7개의 위반은 성질의 글자에서만 생긴다. 사용자 관점의 잠금 보장(상호 배제, 소유자만 해제)은 깨지지 않는다.

## 1. 판정 표

| id | 판정 | 위반 성질 (detected) | 책임 규칙 (false alarm) | 한 줄 이유 |
|---|---|---|---|---|
| keylocallock.coroutine/RACY-01 | detected | tryLock P3a, P3b (+ progress_live) | - | 낡은 `containsKey == false`로 새 entry 경로를 타서 살아 있는 B의 entry를 바꾼다(상한에서는 지운다). 두 holder가 생긴다. 보고된 의무가 맞다 |
| keylocallock.core/RACY-01 | detected | tryLock P3a, P3b (+ progress_live) | - | 위와 같다 |
| keylocallock.reactor/RACY-01 | detected | tryLock P3a, P3b (+ progress_live) | - | 위와 같다 |
| keylocallock.coroutine/RACY-03 | detected | 다음 tryLock의 progress_live (글자) | - | 낡은 false면 만료 entry를 풀기만 하고 잡지 않아 빈 entry가 남는다. 더 이른 시계의 tryLock이 그 entry를 잡는다. 보고된 MI_INV_1은 성질이 아니다 |
| keylocallock.core/RACY-03 | detected | 다음 tryLock의 progress_live (글자) | - | 위와 같다 |
| keylocallock.reactor/RACY-03 | detected | 다음 tryLock의 progress_live (글자) | - | 위와 같다 |
| keylocallock.coroutine/RACY-06 | detected | unLock progress_owner (글자) | - | unLock 시작 뒤에 발급된 token("local-1")을 낸 호출자는, compute 때 entry가 그 token을 가져도 false를 받는다. 자기 token만 쓰는 호출자에게는 차이가 없다 |
| keylocallock.core/RACY-06 | detected | unLock progress_owner (글자) | - | 위와 같다 |
| keylocallock.reactor/RACY-06 | detected | unLock progress_owner (글자) | - | 위와 같다 |
| keylocallock.core/AOR-03 | detected | 다음 tryLock(또는 sweep)의 P3a | - | 새 entry가 `now`에 만료된다. 1 ms 뒤의 tryLock이 lock을 가져간다. 보고된 MI_INV_4는 성질이 아니다 |
| keylocallock.core/COR-01 | detected | tryLock P3a (+ progress_live) | - | `&&`가 `\|\|`가 되어 만료되지 않은 lock에서도 CAS가 돌고 lock을 뺏는다. 보고된 의무가 맞다 |
| keylocallock.core/RET-06 | detected | unLock P1 (`!has(S, k)`) | - | 해제 뒤 entry가 남는다. ensures 순서 때문에 MI_INV_1이 먼저 보고되었다 |
| keylocallock.core/RET-08 | detected | unLock P1 | - | 빈 map에서도 true를 돌려준다. 보고된 의무가 맞다 |
| keylocallock.core/SDL-12 | detected | tryLock progress_free | - | 빈 key의 첫 tryLock이 null을 받고 주인 없는 entry가 남는다. ensures 순서 때문에 MI_INV_4가 먼저 보고되었다 |
| keylocallock.coroutine/RET-11 | detected | 다음 tryLock의 progress_live (글자) | - | 모니터가 만료 entry를 풀지만 지우지 않는다(누수). 성질 위반은 progress_live의 글자에서만 생긴다. 보고된 MI_INV_1은 성질이 아니다 |
| keylocallock.coroutine/SDL-06 | detected | 다음 tryLock(또는 sweep)의 P3a | - | takeover가 expiresAt을 갱신하지 않아 새 holder의 lock이 바로 뺏긴다. 보고된 MI_INV_4는 성질이 아니다 |
| keylocallock.reactor/AOR-03 | detected | 다음 tryLock(또는 sweep)의 P3a | - | core/AOR-03과 같다 |
| keylocallock.reactor/MOP-02 | detected | tryLock P3a (+ progress_live, 이후 P2) | - | `tryAcquire()`가 `true`가 되어 entry가 있으면 항상 잡는다. permit이 2까지 는다. 보고된 P3a가 맞다 |
| keylocallock.reactor/SDL-02 | false alarm | - | DESIGN-v0.md 5.2: 모든 action이 MI_INV_1..5를 다시 세워야 한다. MI_INV는 원본에 맞춰 쓴 고정 hole이다(4.4). 그 INV-E(MI_INV_1)가 이 변이가 닿는 안전한 상태(죽은 entry)를 배제한다 | 만료 takeover가 lock을 잡지 못하고 죽은 entry를 남긴다. 죽은 entry는 어떤 성질도 깨지 않는다. MI_INV_1을 넓히면 모델 전체가 증명된다 |

## 2. 항목별 근거

### 2.A RACY-01 무리 (공통 분석)

변이: `val racy_1 = lockMap.containsKey(completeKey)`를 compute 앞에 넣고, 람다 첫 조건을 `if (racy_1 && existing != null)`로 바꿨다.

- 한 스레드만 있으면 `racy_1 == (existing != null)`이므로 원본과 같다.
- 두 tryLock이 같은 key에서 경쟁하면 다르다. A의 `containsKey`가 B의 compute 전에 돌고(false), A의 compute가 B의 compute 뒤에 돌면, A는 B의 살아 있는 entry를 보고도 "새 entry" 경로를 탄다.
  - 상한이 아니면(`rej == false`) A는 새 `LockInfo`를 넣어 B의 entry를 바꾼다. B의 token은 entry에서 사라진다(witness END). 그때 B의 lease는 남아 있다.
  - 상한이면(`rej == true`) A는 unbacked token을 받고 `return@compute null`로 B의 entry를 지운다.
- 이 경쟁은 req-shield가 막으려는 바로 그 상황(같은 key에 요청이 몰림)에서 생긴다.

반례 (실제 코드, `lockTimeoutMillis = 100`):
1. 스레드 A가 `tryLock("k", CREATE)`를 시작한다. `now_A`를 읽고 `containsKey`가 false를 돌려준다.
2. A가 compute 직전에 멈춘다. 그동안 스레드 B가 `tryLock` 전체를 실행한다. B는 entry를 만들고 `local-1`을 받는다.
3. A의 compute가 돈다. `racy_1 == false`이므로 새 entry 경로로 가서 `local-2`를 받는다.
4. B의 `unLock(local-1)`은 false를 받는다.

깨지는 성질:
- tryLock P3a(`ends_only_if now > old(tok(qt).gLease)`): A의 action이 B의 token을 끝낸다. 이때 `now_A <= lease(B)`다.
- tryLock P3b(`unbacked_only_if !old(has(S, k)) && rej && limit != 0`): 상한 경로에서 A가 unbacked token을 받는데 `old(has(S, k))`가 참이다.
- tryLock progress_live: A의 사전 상태에 B의 entry가 있고 `now_A <= expiresAt`인데 A가 token을 받는다. 이 위반은 ensures 순서 때문에 보고되지 않았다.

보고된 의무(P3a, P3b)는 둘 다 실제로 깨진다. 실제 영향: 같은 key에 두 holder가 동시에 생긴다. 첫 holder는 자기 lock을 풀 수 없다. 상한에서는 첫 holder의 entry가 지워진다.

#### keylocallock.coroutine/RACY-01 — detected
- K: `B/kotlin/out/coroutine__RACY-01__RACY-01.out`
  ```
  [+52 ms] A containsKey(reqshield:lock:k_CREATE) = false
  [+67 ms] B tryLock -> local-1; entry{permits=0, isHeld=true, token=local-1, expiresAt=+154 ms}
  [+67 ms] A tryLock -> local-2; entry{permits=0, isHeld=true, token=local-2, expiresAt=+148 ms}
  [+81 ms] B unLock(local-1) -> false; ...
  ```
  원본(`coroutine__RACY-01__original.out`)에서는 A가 null을 받고 B의 unLock이 true다.
  상한(`coroutine__RACY-01-cap__RACY-01.out`, `LocalLockLimit.maxEntries = 1`): A가 `local-2`를 받고 entry가 사라진다 (`A tryLock -> local-2; no entry`).
- S: `B/scenarios/coroutine__RACY-01__live.vpr`(P3a 위반 사례와 progress_live 위반 사례가 검증됨), `coroutine__RACY-01__cap.vpr`(P3b·P3a 위반 사례 검증), `coroutine__RACY-01__orig.vpr`(같은 입력에서 원본은 두 성질을 지킴).

#### keylocallock.core/RACY-01 — detected
- K: `B/kotlin/out/core__RACY-01__RACY-01.out`: `B tryLock -> local-1` 다음에 `A tryLock -> local-2`, `B unLock(local-1) -> false`. 상한: `core__RACY-01-cap__RACY-01.out`에서 `A tryLock -> local-2; no entry`. 원본은 A가 null.
- S: `B/scenarios/core__RACY-01__live.vpr`, `core__RACY-01__cap.vpr`, `core__RACY-01__orig.vpr` 모두 검증됨.
- P: `B/out/core__RACY-01__order.out`에서도 P3a와 P3b가 보고된다.

#### keylocallock.reactor/RACY-01 — detected
- 변이가 `Mono.fromCallable` 람다 안, compute 밖에 있다. 분석은 같다.
- K: `B/kotlin/out/reactor__RACY-01__RACY-01.out`: `A tryLock -> local-2`, `B unLock(local-1) -> false`. 상한: `reactor__RACY-01-cap__RACY-01.out`에서 `A tryLock -> local-2; no entry`.
- S: `B/scenarios/reactor__RACY-01__live.vpr`, `reactor__RACY-01__cap.vpr`, `reactor__RACY-01__orig.vpr` 모두 검증됨.

### 2.B RACY-03 무리 (공통 분석)

변이: `val racy_3 = lockMap.containsKey(completeKey)`를 넣고, 기존 entry의 획득 조건을 `if (racy_3 && existing.semaphore.tryAcquire())`로 바꿨다.

1. `racy_3`가 낡은 false이고 compute가 만료된 entry를 보면, CAS가 성공해 token을 지우고 permit을 돌려준다. 그 뒤 획득을 건너뛴다. 결과는 null이다.
2. entry는 "빈 entry"로 남는다: permits 1, isHeld false, token null, expiresAt은 예전 값.
3. 그 action 자체는 성질을 깨지 않는다. 끝난 token은 만료되었으므로 P3a가 성립한다. 결과가 null이므로 나머지 tryLock 성질은 공허하게 성립한다.
4. 빈 entry가 남은 뒤, `now <= expiresAt`인 시계 값을 가진 tryLock C가 그 entry를 잡는다. CAS 조건이 거짓이고 permit이 1이기 때문이다. 이것이 progress_live(`old(has(S, k)) && now <= old(S.m[k].expiresAt) ==> res == NULL`)를 깬다.
5. 원본 코드에서는 모든 entry가 잡혀 있고 permit이 0이다. 그래서 원본은 progress_live를 항상 지킨다 (원본 모델 PROVEN).

반례 (실제 코드, `lockTimeoutMillis = 50`):
1. 스레드 B가 시계를 읽고(`now_B`) `containsKey`가 false를 돌려준다. B는 compute 직전에 멈춘다.
2. 스레드 C가 곧바로 시계를 읽는다(`now_C ≈ now_B`). C는 `containsKey` 직전에 멈춘다.
3. 90 ms 뒤 스레드 A가 시계를 읽고(`now_A > now_B + 50`) `containsKey`가 false를 돌려준다 (아직 entry 없음).
4. B의 compute가 돈다. entry(expiresAt = `now_B + 50`)를 만들고 `local-1`을 받는다.
5. C의 `containsKey`가 true를 돌려준다. C는 compute 직전에 멈춘다.
6. A의 compute가 돈다. entry가 만료되었으므로 CAS로 풀고, `racy_3 == false`라서 잡지 않는다. A는 null을 받는다.
7. C의 compute가 돈다. `now_C <= expiresAt`이므로 CAS를 건너뛰고 permit 1을 잡는다. C는 `local-2`를 받는다.

이 반례에서 B와 C는 시계를 읽은 뒤 `lockTimeoutMillis`보다 오래 멈추어야 한다 (기본값 3000 ms). 또는 벽시계가 뒤로 가야 한다. 드물지만 가능한 실행이다.

보고된 의무와의 관계:
- 보고된 의무는 MI_INV_1(INV-E)이다. 이것은 성질이 아니다.
- `free-MI` 탐침은 MI_INV_1이 빈 entry를 허용하게 넓힌다. 그러면 Silicon은 tryLock의 progress_live 하나만 실패로 보고한다. 다른 모든 성질, unLock, sweep, lemma P3c, probe는 증명된다. 그래서 이 무리에서 걸린 성질은 progress_live뿐이다.
- 같은 넓힘을 원본 모델에 적용하면 원본 tryLock도 progress_live에서 실패한다 (`B/out/coroutine__original__free-MI.out`). 원인은 빈 entry 상태 자체이고, 그 상태에 닿는 것은 변이뿐이다.
- 0.4절: progress_live를 잡힌 entry로 좁히면 3개 모두 증명된다. 그래서 이 무리의 검출은 progress_live의 글자에 달려 있다.

실제 영향: 만료된 lock을 만난 tryLock이 드물게 lock을 잡지 못하고 null을 받는다 (불필요한 대기). 빈 entry는 다음 tryLock이 잡거나 모니터가 지울 때까지 남는다. 상호 배제와 소유권은 깨지지 않는다.

#### keylocallock.coroutine/RACY-03 — detected
- K: `B/kotlin/out/coroutine__RACY-03__RACY-03.out`
  ```
  [+54 ms] B containsKey(...) = false
  [+148 ms] A containsKey(...) = false
  [+150 ms] C containsKey(...) = true
  [+164 ms] C compute pre-state: entry{permits=1, isHeld=false, token=null, expiresAt=+100 ms}
  [+164 ms] A tryLock -> null; ...
  [+164 ms] C tryLock -> local-2; ...
    PASS C's compute saw an entry, and C's clock +50 ms <= its expiresAt +100 ms (progress_live antecedent), yet C got a token
  ```
  원본(`coroutine__RACY-03__original.out`)에서는 A가 `local-2`를 받고 C는 null을 받는다.
- S: `B/scenarios/coroutine__RACY-03__mut.vpr`: 둘째 action에서 P3a와 progress_live가 성립하고, 셋째 action에서 progress_live 위반 사례가 검증된다. `coroutine__RACY-03__orig.vpr`: 원본은 셋째 action에서 progress_live를 지킨다.
- P: `B/out/coroutine__RACY-03__free-MI.out`(progress_live 1개만 실패), `B/out/coroutine__RACY-03__free-MI__live-held.out`(성공).

#### keylocallock.core/RACY-03 — detected
- K: `B/kotlin/out/core__RACY-03__RACY-03.out`: C의 compute 사전 상태가 `entry{permits=1, isHeld=false, token=null, expiresAt=+74 ms}`이고, C의 시계는 +24 ms이며, C가 `local-2`를 받는다. A는 null을 받는다. 원본은 C가 null을 받는다.
- S: `B/scenarios/core__RACY-03__mut.vpr`, `core__RACY-03__orig.vpr` 검증됨.
- P: `B/out/core__RACY-03__free-MI.out`(progress_live 1개만 실패), `core__RACY-03__free-MI__live-held.out`(성공).

#### keylocallock.reactor/RACY-03 — detected
- K: `B/kotlin/out/reactor__RACY-03__RACY-03.out`: C의 compute 사전 상태가 `expiresAt=+154 ms`인 빈 entry이고, C의 시계는 +104 ms이며, C가 `local-2`를 받는다. 원본은 C가 null을 받는다.
- S: `B/scenarios/reactor__RACY-03__mut.vpr`, `reactor__RACY-03__orig.vpr` 검증됨.
- P: `B/out/reactor__RACY-03__free-MI.out`(progress_live 1개만 실패), `reactor__RACY-03__free-MI__live-held.out`(성공).

### 2.C RACY-06 무리 (공통 분석)

변이: `val racy_6 = lockMap.containsKey(completeKey)`를 넣고, 해제 조건을 `if (racy_6 && existing.token == token && existing.isHeld.compareAndSet(true, false))`로 바꿨다.

1. `racy_6`가 낡은 false이면 해제하지 않고 entry를 그대로 둔다. 결과는 false다.
2. `racy_6`가 false인데 compute가 token `t`를 가진 entry를 보려면, 그 entry가 `containsKey` 뒤에 생겨야 한다. token은 다시 쓰이지 않는다. 그래서 `t`는 이 unLock이 시작된 뒤 발급된 token이다.
3. 자기 tryLock에서 `t`를 받은 호출자에게는 이 일이 생기지 않는다. 그 entry는 `containsKey` 때 이미 있었기 때문이다. `holder-rely` 탐침이 이 rely를 넣으면 세 모델 모두 증명된다.
4. sidecar는 `free t: Token = param token`으로 임의 token을 받는다 (DESIGN-v0.md 2.9 5번도 호출자의 문자열을 임의 값으로 둔다). progress_owner(`old(has(S, k)) && old(S.m[k].token) == t ==> res`)는 모든 `t`에 대한 진술이다. token은 `local-N` 꼴이라 예측할 수 있다.

반례 (실제 코드, 새 JVM):
1. 스레드 X가 `unLock("k", CREATE, "local-1")`을 시작한다. `containsKey`가 false를 돌려준다.
2. X가 compute 직전에 멈춘다. 그동안 스레드 Y가 `tryLock`을 실행해 `local-1`을 받는다.
3. X의 compute가 돈다. entry가 `local-1`을 가지고 있지만 `racy_6 == false`라서 X는 false를 받는다.
4. 원본 코드는 같은 순서에서 X에게 true를 준다 (compute 안에서 token을 비교하므로).

보고된 의무(progress_owner)가 실제로 깨진다. 단, 위반은 글자 그대로의 읽기에서만 생긴다 (0.4절). 좁은 읽기("자기 token")에서는 DESIGN-v0.md 2.12(경쟁 읽기를 임의 값으로 둠)가 오경보의 원인이 된다. 모델은 경쟁 읽기와 token 발급 시점의 관계를 알 수 없다.

실제 영향: req-shield의 ReqShield는 자기 tryLock에서 받은 token만 unLock에 넘긴다. 그래서 운영 영향은 없다. 아직 발급되지 않은 token을 미리 내는 호출자만 차이를 본다.

#### keylocallock.coroutine/RACY-06 — detected
- K: `B/kotlin/out/coroutine__RACY-06__RACY-06.out`
  ```
  [+54 ms] X containsKey(...) = false
  [+70 ms] Y tryLock -> local-1; entry{permits=0, isHeld=true, token=local-1, ...}
  [+82 ms] X unLock(local-1) -> false; X's compute pre-state: entry{permits=0, isHeld=true, token=local-1, ...}
  [+84 ms] Y unLock(local-1) -> true; no entry
  ```
  원본(`coroutine__RACY-06__original.out`): `X unLock(local-1) -> true`.
- S: `B/scenarios/coroutine__RACY-06__forged.vpr`: `old[L2](has(S, k)) && old[L2](S.m[k].token) == 1 && !rX`가 검증된다.
- P: `B/out/coroutine__RACY-06__holder-rely.out`(성공).

#### keylocallock.core/RACY-06 — detected
- K: `B/kotlin/out/core__RACY-06__RACY-06.out`: X의 compute 사전 상태가 `token=local-1`인 entry이고 X는 false를 받는다. Y는 그 뒤 자기 lock을 푼다. 원본은 X가 true를 받는다.
- S: `B/scenarios/core__RACY-06__forged.vpr` 검증됨.
- P: `B/out/core__RACY-06__order.out`(progress_owner 그대로), `B/out/core__RACY-06__holder-rely.out`(성공).

#### keylocallock.reactor/RACY-06 — detected
- K: `B/kotlin/out/reactor__RACY-06__RACY-06.out`: `X unLock(local-1) -> false`, 사전 상태 `token=local-1`. 원본은 true.
- S: `B/scenarios/reactor__RACY-06__forged.vpr` 검증됨.
- P: `B/out/reactor__RACY-06__holder-rely.out`(성공).

### 2.D keylocallock.core/AOR-03 — detected

- 변이: 새 entry를 `LockInfo(Semaphore(1), now)`로 만든다. 원래는 `now + lockTimeoutMillis`다.
- 보고된 의무: tryLock MI_INV_4(entry의 expiresAt = token의 lease). 이것은 성질이 아니다. `order` 탐침에서도 MI_INV_4만 남는다. 같은 action 안에서는 성질이 깨지지 않는다. `issue` 규칙이 lease를 `now + timeout`으로 정하기 때문이다.
- 반례 (한 스레드, `lockTimeoutMillis = 100`):
  1. A가 tryLock을 한다. A는 `local-1`을 받고 entry의 expiresAt은 A의 `now`다.
  2. 5 ms 뒤 B가 tryLock을 한다. `now_B > expiresAt`이므로 CAS가 A의 lock을 풀고 B가 `local-2`를 받는다.
  3. 깨지는 성질: B의 action의 P3a. A의 token이 끝나는데 `now_B <= now_A + 100 = lease(A)`다. 모니터가 먼저 돌면 sweep P3a가 같은 이유로 깨진다.
- K: `B/kotlin/out/core__AOR-03__AOR-03.out`: `A tryLock -> local-1; ... expiresAt=+21 ms`, `B tryLock -> local-2`, `A unLock(local-1) -> false`. 원본은 B가 null, A의 unLock이 true.
- S: `B/scenarios/core__AOR-03__take.vpr`(tryLock P3a 위반 사례), `core__AOR-03__sweep.vpr`(sweep P3a 위반 사례), `core__AOR-03__orig.vpr`(원본은 P3a를 지킴) 검증됨.
- 실제 영향: 새 lock이 만들어지자마자 만료된다. 1 ms 뒤의 요청이 lock을 가져가므로 request collapsing이 거의 사라진다. 첫 holder는 자기 lock을 풀 수 없다.

### 2.E keylocallock.core/COR-01 — detected

- 변이: `now > existing.expiresAt && CAS`가 `now > existing.expiresAt || CAS`가 되었다.
- 보고된 의무: tryLock P3a. 맞는 의무다. progress_live도 같은 경로에서 깨진다. ensures 순서 때문에 보고되지 않았다.
- 반례 (한 스레드, `lockTimeoutMillis = 100`):
  1. A가 tryLock을 해서 `local-1`을 받는다.
  2. 곧바로 B가 tryLock을 한다. 만료되지 않았으므로 `||`의 오른쪽 CAS가 돈다. CAS가 성공해 A의 lock을 풀고, B가 `local-2`를 받는다.
  3. P3a(A의 token이 lease 전에 끝남)와 progress_live(살아 있는 entry인데 B가 token을 받음)가 깨진다.
- K: `B/kotlin/out/core__COR-01__COR-01.out`: `B tryLock -> local-2`, `A unLock(local-1) -> false`. 원본은 B가 null.
- S: `B/scenarios/core__COR-01__take.vpr`: P3a 위반 사례와 progress_live 위반 사례가 검증된다.
- P: `B/out/core__COR-01__order.out`도 P3a를 보고한다.
- 실제 영향: 잡혀 있는 key에 대한 모든 tryLock이 lock을 뺏는다. 상호 배제가 없다.

### 2.F keylocallock.core/RET-06 — detected

- 변이: unLock이 해제한 뒤 `return@compute null` 대신 `return@compute existing`을 한다. entry가 map에 남는다 (permits 1, isHeld false, token은 그대로).
- 보고된 의무: unLock MI_INV_1. 맞는 의무가 아니다. 같은 action에서 unLock P1이 깨진다. P1은 `res ==> ... && !tok(t).gHeld && !has(S, k)`를 요구한다.
  - `!has(S, k)`가 거짓이다. entry가 남기 때문이다.
  - `!tok(t).gHeld`도 거짓이다. entry가 아직 `t`를 가지므로 witness END 규칙이 발동하지 않기 때문이다.
  - `order` 탐침(`B/out/core__RET-06__order.out`)에서 P1이 보고된다. 원래 보고에서는 ensures 순서 때문에 MI_INV_1에 가려졌다.
- 반례 (한 스레드): A가 tryLock으로 `local-1`을 받고 `unLock(local-1)`이 true를 돌려준다. 그 뒤에도 entry가 있다.
- K: `B/kotlin/out/core__RET-06__RET-06.out`: `A unLock(local-1) -> true; entry{permits=1, isHeld=false, token=local-1, ...}`. 원본은 `no entry`.
- S: `B/scenarios/core__RET-06__keep.vpr`: `ok`, `has(S, k)`, `tok(rA).gHeld`가 모두 검증된다.
- 문서 근거: README "Local lock map size": "An entry is dropped as soon as the lock is released".
- 실제 영향: 해제된 entry가 만료와 모니터 정리 때까지 map에 남는다 (최대 `lockTimeoutMillis` + 1 s). 상한이 있으면 새 key가 unbacked permit을 더 일찍 받는다. 다음 tryLock은 남은 entry를 다시 잡으므로 잠금 자체는 동작한다.

### 2.G keylocallock.core/RET-08 — detected

- 변이: unLock이 `return released.get()` 대신 `return true`를 한다.
- 보고된 의무: unLock P1. 맞는 의무다.
- 반례 (한 스레드): 빈 map에서 `unLock("k", CREATE, "local-999")`가 true를 돌려준다. P1은 `res ==> old(has(S, k)) && ...`를 요구하는데 `old(has(S, k))`가 거짓이다.
- K: `B/kotlin/out/core__RET-08__RET-08.out`: `X unLock(local-999) on an empty map -> true`. 원본은 false.
- S: `B/scenarios/core__RET-08__empty.vpr`: `ok && !old[L1](has(S, k))`가 검증된다.
- 실제 영향: lock 상태는 바뀌지 않는다. ReqShield는 이 결과를 debug 로그에만 쓴다 (`core/.../ReqShield.kt:243-246`). 그래서 운영 영향은 거의 없다. 결과를 믿는 다른 KeyLock 사용자는 잘못된 정보를 받는다.

### 2.H keylocallock.core/SDL-12 — detected

- 변이: 새 entry 경로에서 `acquiredToken.set(token)`을 지웠다. entry는 `local-1`을 가진 채 만들어지지만 tryLock은 null을 돌려준다.
- 보고된 의무: tryLock MI_INV_4. 맞는 의무가 아니다. 같은 action에서 progress_free(`!old(has(S, k)) && !rej ==> res != NULL && tok(res).gBacked`)가 깨진다. `order` 탐침(`B/out/core__SDL-12__order.out`)에서 progress_free가 보고된다.
- 반례 (한 스레드, 상한 없음): 빈 map에서 A의 tryLock이 null을 받는다. 그 뒤 B의 tryLock도 null을 받는다. `lockTimeoutMillis`가 지난 뒤의 takeover만 lock을 받는다.
- K: `B/kotlin/out/core__SDL-12__SDL-12.out`: `A tryLock on an empty map -> null; entry{..., token=local-1, ...}`, `B tryLock -> null`, `C tryLock after the timeout -> local-2`. 원본은 A가 `local-1`을 받는다.
- S: `B/scenarios/core__SDL-12__new.vpr`: `rA == NULL && !old[L1](has(S, k))`가 검증된다 (rej = false).
- 실제 영향: 모든 새 key의 첫 tryLock이 실패하고, 주인 없는 entry가 `lockTimeoutMillis`(기본 3000 ms) 동안 key를 막는다. 요청들은 cache를 polling하다가 각자 supplier를 부른다. collapsing이 깨지고 지연이 최대 3 s 늘어난다.

### 2.I keylocallock.coroutine/RET-11 — detected

- 변이: 모니터(sweep)가 만료 entry를 처리한 뒤 `null` 대신 `lockInfo`를 돌려준다. entry는 풀리지만 지워지지 않는다 (permits 1, isHeld false, token null).
- 보고된 의무: sweep MI_INV_1. 이것은 성질이 아니다. sweep action 자체는 성질을 지킨다: 끝나는 token은 만료되었으므로 sweep P3a가 성립한다.
- 깨지는 성질: 다음 tryLock의 progress_live. 2.B절과 같은 빈 entry가 생기기 때문이다.
- 반례 (실제 코드, 모니터 동작, `lockTimeoutMillis = 300`):
  1. A가 tryLock으로 `local-1`을 받는다 (expiresAt +344 ms).
  2. C가 tryLock을 시작해 시계(+61 ms)를 읽고 compute 직전에 멈춘다.
  3. 만료 뒤 모니터의 다음 pass(+1048 ms)가 entry를 풀고 남긴다.
  4. C의 compute가 돈다. 사전 상태는 빈 entry(expiresAt +344 ms)다. `now_C <= expiresAt`이므로 CAS를 건너뛰고 permit을 잡는다. C가 `local-2`를 받는다.
  - 필요한 창은 짧다. C가 만료 직전에 시계를 읽고, 모니터 pass가 만료 직후에 돌고, 그 뒤에 C의 compute가 돌면 된다. 실행에서는 모니터 주기(1 s) 때문에 C를 오래 멈추었다.
- 보고된 의무와의 관계:
  - `free-MI` 탐침(`B/out/coroutine__RET-11__free-MI.out`)에서 실패는 tryLock progress_live 하나뿐이다. sweep을 포함한 나머지는 모두 증명된다.
  - 0.4절: progress_live를 잡힌 entry로 좁히면 증명된다(`coroutine__RET-11__free-MI__live-held.out`). 그래서 이 검출은 progress_live의 글자에 달려 있다.
  - 같은 순서에서 원본은 entry를 지우고 C가 새 entry로 token을 받는다. C가 보는 결과는 같다. 차이는 map 상태(entry 있음/없음)뿐이다.
- K: `B/kotlin/out/coroutine__RET-11-clock__RET-11.out`: `C compute pre-state: entry{permits=1, isHeld=false, token=null, expiresAt=+344 ms}`, `C's clock was +61 ms`, `C tryLock -> local-2`. 원본(`..._original.out`): `C compute pre-state: no entry`.
  - 누수: `B/kotlin/out/coroutine__RET-11__RET-11.out`에서 2.6 s(모니터 pass 2번) 뒤에도 `entry{permits=1, isHeld=false, token=null, ...}`가 남는다. 원본은 `no entry`.
- S: `B/scenarios/coroutine__RET-11__sweep.vpr`(sweep P3a 성립, 다음 tryLock의 progress_live 위반 사례 검증), `coroutine__RET-11__leak.vpr`(sweep 3번 뒤에도 빈 entry가 남음) 검증됨.
- 실제 영향: holder가 풀지 않은 key마다 만료 entry가 map에 영구히 남는다 (같은 key의 tryLock이 다시 잡기 전까지). 상한이 있으면 남은 entry가 상한을 채워 새 key가 collapsing을 잃는다. README의 "dropped by the cleanup monitor once lockTimeoutMillis has passed"가 깨진다. 이 누수 자체를 막는 sidecar 성질은 없다.

### 2.J keylocallock.coroutine/SDL-06 — detected

- 변이: 기존 entry의 획득 분기에서 `existing.expiresAt = now + lockTimeoutMillis`를 지웠다. 이 분기는 만료 takeover 때만 실행된다.
- 보고된 의무: tryLock MI_INV_4. 이것은 성질이 아니다. `order` 탐침에서도 MI_INV_4만 남는다. 위반은 다음 action에서 생긴다.
- 반례 (한 스레드, `lockTimeoutMillis = 50`):
  1. A가 `local-1`을 받는다 (expiresAt = `now_A + 50`).
  2. A의 timeout 뒤 C가 tryLock을 한다. takeover로 `local-2`를 받는다. expiresAt은 A의 값 그대로다 (이미 지남).
  3. 2 ms 뒤 D가 tryLock을 한다. `now_D > expiresAt`이므로 CAS가 C의 lock을 풀고 D가 `local-3`을 받는다.
  4. D의 action에서 P3a가 깨진다. C의 token이 끝나는데 `now_D <= now_C + 50 = lease(C)`다. 모니터가 먼저 돌면 sweep P3a가 깨진다.
- K: `B/kotlin/out/coroutine__SDL-06__SDL-06.out`: `C tryLock after A's timeout -> local-2; ... expiresAt=+98 ms`, `D tryLock 2 ms later -> local-3`, `C unLock(local-2) -> false`. 원본은 D가 null, C의 unLock이 true.
- S: `B/scenarios/coroutine__SDL-06__take.vpr`(셋째 action인 tryLock의 P3a 위반 사례), `coroutine__SDL-06__sweep.vpr`(셋째 action인 sweep의 P3a 위반 사례) 검증됨.
- 실제 영향: 한 번 만료된 key는 그 뒤 takeover할 때마다 새 holder의 lock이 이미 만료된 상태다. 다음 요청이나 모니터가 곧바로 lock을 가져간다.

### 2.K keylocallock.reactor/AOR-03 — detected

- 변이와 분석은 2.D(core/AOR-03)와 같다. 위치만 `Mono.fromCallable` 람다 안이다.
- 보고된 의무: MI_INV_4 (성질 아님). `order` 탐침에서도 MI_INV_4만 남는다.
- K: `B/kotlin/out/reactor__AOR-03__AOR-03.out`: `A tryLock -> local-1; ... expiresAt=+102 ms`, `B tryLock -> local-2`(7 ms 뒤), `A unLock(local-1) -> false`. 원본은 B가 null.
- S: `B/scenarios/reactor__AOR-03__take.vpr`, `reactor__AOR-03__sweep.vpr`, `reactor__AOR-03__orig.vpr` 검증됨.
- 실제 영향: 2.D와 같다.

### 2.L keylocallock.reactor/MOP-02 — detected

- 변이: 기존 entry 분기의 `if (existing.semaphore.tryAcquire())`가 `if (true)`가 되었다. permit을 쓰지 않고 항상 잡는다.
- 보고된 의무: tryLock MI_INV_1과 P3a. P3a가 맞는 의무다. MI_INV_1 경로(만료 takeover 뒤 permit 1이 남음)는 뒤에 P2 위반으로 이어진다.
- 반례 (한 스레드, `lockTimeoutMillis = 50`):
  1. A가 `local-1`을 받는다.
  2. 곧바로 B가 tryLock을 한다. 만료되지 않았으므로 CAS는 돌지 않지만 `if (true)`로 `local-2`를 받는다. P3a와 progress_live가 깨진다.
  3. timeout 뒤 C의 takeover가 permit을 하나 돌려주고 쓰지 않는다 (permits 1).
  4. 다음 timeout 뒤 D의 takeover가 또 돌려준다 (permits 2). P2(`permits <= 1`)가 깨진다.
- K: `B/kotlin/out/reactor__MOP-02__MOP-02.out`: `B tryLock at once -> local-2`, `C ... entry{permits=1, ...}`, `D ... entry{permits=2, ...}`. 원본은 B가 null이고 permits가 0이다.
- S: `B/scenarios/reactor__MOP-02__take.vpr`: P3a 위반 사례, progress_live 위반 사례, `S.m[k].permits == 2`가 검증된다.
- P: `B/out/reactor__MOP-02__order.out`도 P3a와 MI_INV_1을 보고한다.
- 실제 영향: entry가 있는 key의 모든 tryLock이 lock을 받는다. 상호 배제가 없다.

### 2.M keylocallock.reactor/SDL-02 — false alarm

- 변이: 만료 takeover 분기에서 `existing.semaphore.release()`를 지웠다.
- 변이 뒤의 동작:
  1. 만료된 entry를 만난 tryLock의 CAS가 성공해 isHeld를 false로, token을 null로 바꾼다. permit은 돌려주지 않는다 (0 그대로).
  2. 이어서 `tryAcquire()`가 실패한다. 결과는 empty(NULL)다.
  3. entry는 "죽은 entry"로 남는다: permits 0, isHeld false, token null, expiresAt은 지난 값.
  4. 그 뒤의 tryLock은 모두 empty다 (CAS 실패, tryAcquire 실패). unLock은 token이 null이라 항상 false다.
  5. 모니터의 다음 pass가 죽은 entry를 지운다 (CAS 실패, token null, 제거). 그 뒤 tryLock은 새 entry를 만든다.
- 보고된 의무: tryLock MI_INV_1(INV-E: "every entry is held, has no free permit, and carries a token"). 죽은 entry가 이 conjunct를 깬다. `order` 탐침에서도 MI_INV_1만 남는다.

성질별 논증 (변이가 닿는 상태 = 원본의 상태 + 죽은 entry):

| 성질 | 왜 성립하나 |
|---|---|
| tryLock `issue`, P1a, P1a_entry | token을 주는 경로는 새 entry 경로와 상한 경로뿐이다. 둘 다 바뀌지 않았다. 기존 entry 분기의 획득은 permit이 늘 0이라 일어나지 않는다 |
| tryLock P3a | tryLock이 끝내는 token은 만료 CAS 분기의 token뿐이다. 그때 `now > expiresAt = lease`다 (잡힌 entry에 대해 MI_INV_4가 계속 성립) |
| tryLock P3b | unbacked token은 바뀌지 않은 상한 경로(`!has && rej`)에서만 나온다 |
| progress_free | 새 entry 경로가 바뀌지 않았다 |
| progress_live | 기존 entry에서는 결과가 항상 NULL이다 |
| unLock P1, P3a, progress_owner | 죽은 entry의 token은 null이다. `t != NULL`이므로 비교가 항상 거짓이고 상태가 바뀌지 않는다. 잡힌 entry에서는 원본과 같다 |
| sweep P3a | 죽은 entry에는 token이 없으므로 아무 token도 끝나지 않는다. 잡힌 entry에서는 원본과 같다 |
| P2 | 이 변이는 permit을 돌려주는 곳을 하나 없앴을 뿐이다. permit은 0 또는 1이다 |
| P3c | MI_INV_5(잡힌 backed token = 그 key entry의 token)가 계속 성립하므로 따라 나온다 |

Silicon 근거:
- `B/probes/reactor__SDL-02__dead-MI.vpr`: 변이 모델에서 MI_INV_1을 "잡힌 entry 또는 죽은 entry"로 넓히고, MI_INV_4를 token이 있는 entry로 좁혔다. 결과: `Silicon finished verification successfully` (`B/out/reactor__SDL-02__dead-MI.out`). init, tryLock, unLock, sweep, lemma P3c, probe가 모두 증명된다. 그래서 모든 성질이 모든 실행에서 성립한다.
- 같은 넓힘을 원본 reactor 모델에 적용해도 증명된다 (`B/out/reactor__original__dead-MI.out`). 넓힌 불변식은 변이에만 맞춘 것이 아니다.
- 원래 판정 FAILED의 원인: DESIGN-v0.md 5.2의 규칙이다. 모든 action 메서드는 `ensures MI_INV_1(S)`…`MI_INV_5(S)`로 귀납 불변식 전체를 다시 세워야 한다. MI_INV는 원본 코드에 맞춰 쓴 고정 hole이다 (4.4절). 그 INV-E가 이 변이가 닿는 안전한 상태를 배제했다.
- 경로가 실현 가능함: `B/scenarios/reactor__SDL-02__dead.vpr`는 init부터 죽은 entry에 닿는 실행을 보인다. 그 실행에서 takeover action의 P3a와 다음 tryLock의 progress_live가 성립한다. unLock은 false이고, sweep이 entry를 지운 뒤 다음 tryLock은 backed token을 받는다. 모든 실행에 대한 증명은 위의 `dead-MI` 탐침이다.

- K: `B/kotlin/out/reactor__SDL-02__SDL-02.out`
  ```
  [+207 ms] B tryLock after A's timeout -> null; entry{permits=0, isHeld=false, token=null, expiresAt=+152 ms}
  [+218 ms] A unLock(local-1) -> false; ...
  [+1147 ms] a tryLock succeeds after 35 tries and 910 ms -> local-2; ...
  ```
  원본(`reactor__SDL-02__original.out`): `B tryLock after A's timeout -> local-2`.
- 실제 영향: holder가 timeout 안에 풀지 않은 key에서, 만료 뒤 첫 tryLock이 lock을 잡지 못한다. 그 key는 모니터의 다음 pass까지(최대 `LOCK_MONITOR_INTERVAL_MILLIS` = 1000 ms, 실행에서 910 ms) 막힌다. 안전성 문제는 없다. 이 지연을 막는 sidecar 성질(만료 lock의 takeover 진행)은 없다. 이것은 명세의 빈틈이고, 검증기는 LLM이 쓴 MI 덕분에 우연히 이 동작 변화를 표시했다.

## 3. 요약

### 3.1 판정별 개수

| 범주 | detected | false alarm | unresolved |
|---|---|---|---|
| FAILED, 예측 7.2 (9개) | 9 | 0 | 0 |
| FAILED, 무작위 표본 (10개) | 9 | 1 (reactor/SDL-02) | 0 |
| 합계 (19개) | 18 | 1 | 0 |

detected 18개 가운데 보고된 의무가 성질이 아닌 경우(MI_INV_i만 보고됨)가 9개다.
- 같은 action에서 성질이 깨지지만 ensures 순서에 가려진 것: core/RET-06(P1), core/SDL-12(progress_free).
- 다음 action에서 성질이 깨지는 것: core/AOR-03, reactor/AOR-03, coroutine/SDL-06(P3a), RACY-03 ×3, coroutine/RET-11(progress_live).

### 3.2 무작위 표본의 오경보율

- 10개 가운데 1개(reactor/SDL-02)가 오경보다. 오경보율은 10%다.
- 이것은 FAILED 120개에서 뽑은 표본 10개의 값이다. 표본이 작다. 95% Clopper-Pearson 구간은 0.25%-44.5%다.
- 0.4절의 좁은 읽기를 쓰면 coroutine/RET-11도 오경보가 된다. 그러면 2/10 = 20%(구간 2.5%-55.6%)다.
- 참고: 예측 7.2 범주 9개는 글자 그대로의 읽기에서 모두 detected다. 좁은 읽기에서는 RACY-03 ×3과 RACY-06 ×3이 오경보가 되고, 모든 읽기에서 detected인 것은 RACY-01 ×3뿐이다.

### 3.3 unresolved 항목

없음. 19개 모두 실제 코드 실행(K)과 Silicon 근거(S 또는 P)로 판정을 세웠다.

남은 가정 (판정을 바꾸지 않음):
1. K의 스레드 순서는 hook으로 강제했다. 각 순서는 "스레드가 `containsKey` 직전이나 compute 직전에 잠시 밀려난다"는 합법적인 스케줄이다. RACY-03의 순서는 `lockTimeoutMillis`보다 긴 정지(또는 벽시계 역행)가 필요하다. 그래서 운영에서는 드물다.
2. S와 P는 검증기의 Viper 번역을 그대로 쓴다. 이 번역이 쓰인 경로는 K 실행 결과와 일치했다.
3. 0.4절의 7개는 sidecar 원문을 어떻게 읽느냐에 판정이 달려 있다. 원문을 바꾸거나 좁히는 결정은 명세 소유자의 몫이다.
