# Round 3g 판정 — judge 1

## 0. 판정 조건

- 이 판정은 blind 판정이다. 검증기 결과, 생성기의 `report.md`, 금지 목록의 파일과 폴더는 읽지 않았다.
- 판정 근거는 두 가지다.
  1. sidecar 속성: `specs/reqshield/keylocallock.common.kv`의 issue 규칙, ensures, ends_only_if, unbacked_only_if, invariant, lemma. `keylocallock.coroutine.kv`가 이 파일을 coroutine 모듈에 묶는다. hole `MI_INV`는 쓰지 않았다.
  2. 자연어 명세: 샌드박스의 `SPEC.md`, `KeyLock.kt`(KDoc), `HELPERS.md`, `RULES.md`. 네 생성기가 받은 이 네 파일은 바이트 단위로 같다.
- 모델 가정: `Long`은 무한 정수로 본다. 그래서 `now + lockTimeoutMillis`의 overflow는 판정에 넣지 않는다(6절에 기록).
- 행 번호: 구현은 `aicode/round3g/impl/<ID>/KeyLocalLock.kt`의 행이다. sidecar는 `keylocallock.common.kv`의 행이다.

## 1. 판정표

| ID | 동작 | 판정 | 깨진 속성 | 한 줄 이유 |
|---|---|---|---|---|
| G-haiku-1 | tryLock | defect (sidecar 속성) | progress_live, P3a(tryLock) | 41행 `now < existing.expiresAt`가 `now == expiresAt`를 만료로 본다. 그래서 살아 있는 lock을 빼앗는다. |
| G-haiku-1 | unLock | correct | — | entry의 token이 인자 `token`과 같을 때만 entry를 지우고 `true`를 돌려준다(71–77행). |
| G-haiku-1 | sweep | defect (sidecar 속성) | P3a(sweep) | 103행 `now >= existing.expiresAt`가 `now == expiresAt`에서 entry를 지운다. |
| G-sonnet-2 | tryLock | defect (sidecar 속성) | progress_live, P3a(tryLock) | 44행 `existing.expiresAt > now`는 `now == expiresAt`에서 거짓이다. 그래서 46–50행이 lock을 빼앗는다. |
| G-sonnet-2 | unLock | correct | — | 64행 조건이 맞을 때만 entry를 지우고 `true`를 돌려준다. `isHeld` 검사는 map 안에서 늘 참이다. |
| G-sonnet-2 | sweep | defect (sidecar 속성) | P3a(sweep) | 112행 `existing.expiresAt <= now`가 `now == expiresAt`에서 entry를 지운다. |
| G-opus-3 | tryLock | defect (sidecar 속성) | progress_live, P3a(tryLock) | 45행 `now < existing.expiresAt`가 `now == expiresAt`를 만료로 본다. 그래서 52–61행이 lock을 빼앗는다. |
| G-opus-3 | unLock | correct | — | 76행 조건이 맞을 때만 entry를 지우고 `true`를 돌려준다. `isHeld` 검사는 map 안에서 늘 참이다. |
| G-opus-3 | sweep | defect (sidecar 속성) | P3a(sweep) | 113행 `now >= existing.expiresAt`가 `now == expiresAt`에서 entry를 지운다. |
| G-haiku-4 | tryLock | defect (sidecar 속성) | progress_live, P3a(tryLock) | 45행 `existing.expiresAt > now`는 `now == expiresAt`에서 거짓이다. 그래서 52–56행이 lock을 빼앗는다. |
| G-haiku-4 | unLock | correct | — | 68행 조건이 맞을 때만 entry를 지우고 `true`를 돌려준다. |
| G-haiku-4 | sweep | defect (sidecar 속성) | P3a(sweep) | 102행 `existing.expiresAt <= now`가 `now == expiresAt`에서 entry를 지운다. |

모듈 판정:

| ID | 모듈 판정 | 근거 |
|---|---|---|
| G-haiku-1 | defect-property | tryLock과 sweep의 lease 경계 결함(2절, 3절) |
| G-sonnet-2 | defect-property | tryLock과 sweep의 lease 경계 결함(2절, 3절) |
| G-opus-3 | defect-property | tryLock과 sweep의 lease 경계 결함(2절, 3절) |
| G-haiku-4 | defect-property | tryLock과 sweep의 lease 경계 결함(2절, 3절) |

- 네 구현의 결함은 같은 종류다. 네 구현 모두 `now == expiresAt`인 순간을 만료로 본다. sidecar는 그 순간을 아직 살아 있는 것으로 본다.
- sidecar 밖 결함(defect-outside)은 네 구현 모두에서 찾지 못했다(5절).
- undecided 항목은 없다. 해석에 기대는 지점 하나를 7절에 적었다.

## 2. 결함 A — tryLock이 lease 끝 시각에 살아 있는 lock을 빼앗는다 (네 구현 공통)

### 2.1 sidecar가 정한 경계

- `progress_live`(70행): `old(has(S, k)) && old(S.m[k].isHeld) && now <= old(S.m[k].expiresAt) ==> res == NULL`.
  - 즉 `now == expiresAt`이면 tryLock은 `null`을 돌려주어야 한다.
- `ends_only_if P3a`(65행, tryLock): `now > old(tok(qt).gLease)`.
  - tryLock이 어떤 token의 획득을 끝내려면, `now`가 그 token의 lease보다 커야 한다.
- `witness LockInfo.token`(52행): backed token은 그 key의 entry가 그 token을 담는 동안 held 상태다.
  - 그래서 tryLock이 entry를 바꾸면 이전 token의 `gHeld`가 끝난다.
- `entry_lease`(108–109행): entry의 `expiresAt`는 그 entry가 담은 token의 `gLease`와 같다.
- `expired_takeover`(72행)는 `now > expiresAt`일 때만 takeover를 요구한다.
- 결론: sidecar에서 `now == expiresAt`인 lock은 아직 살아 있다. 만료는 `now > expiresAt`부터다.

### 2.2 시나리오 (네 구현 모두 같은 결과)

조건: `lockTimeoutMillis = 100`, key `"k"`, lock type `CREATE`. `T`는 임의의 epoch 밀리초 값이다.

1. 스레드 A가 `tryLock("k", CREATE)`를 부른다. `nowToEpochTime()`이 `T`를 돌려준다.
2. 구현이 새 entry를 넣는다. entry는 token `local-n`, `expiresAt = T+100`, `isHeld = true`를 가진다.
3. issue 규칙(60행)에 따라 `tok(local-n).gLease = T+100`이다.
4. 스레드 B가 같은 key로 `tryLock`을 부른다. `nowToEpochTime()`이 `T+100`을 돌려준다.
5. 구현의 "살아 있음" 검사는 `T+100 < T+100`과 같다. 이 검사는 거짓이다.
6. 구현이 takeover 분기로 간다. 새 entry(token `local-(n+1)`, `expiresAt = T+200`)를 넣고 `local-(n+1)`을 돌려준다.
7. `progress_live`가 깨진다. `old(has) = true`, `old(isHeld) = true`이고 `T+100 <= T+100`이 참이다. 그래서 `res == NULL`이어야 한다. 실제 `res`는 `local-(n+1)`이다.
8. `P3a`(tryLock)가 깨진다. 6단계 뒤 entry는 `local-n`을 담지 않는다. 그래서 `local-n`의 `gHeld`가 끝난다. P3a는 이때 `T+100 > T+100`을 요구한다. 이 식은 거짓이다.
9. 결과: A와 B가 같은 밀리초에 둘 다 lock을 가졌다고 믿고 supplier를 부른다. A가 나중에 `unLock("k", CREATE, local-n)`을 부르면 `false`를 받는다.

- `now = T+99`이면 네 구현 모두 `null`을 돌려준다.
- `now = T+101`이면 네 구현 모두 takeover한다. 이것은 `expired_takeover`와 맞다.
- 그래서 결함은 정확히 `now == expiresAt`인 1 ms에만 있다.

### 2.3 구현별 행 번호

| ID | `now` 읽기 | 살아 있음 검사 (결함 위치) | takeover 분기 | `expiresAt` 계산 | `isHeld = true` |
|---|---|---|---|---|---|
| G-haiku-1 | 36 | 41: `existing != null && existing.isHeld.get() && now < existing.expiresAt -> existing` | 48–57 | 53: `now + lockTimeoutMillis` | 54 |
| G-sonnet-2 | 29 | 44: `existing.isHeld.get() && existing.expiresAt > now` | 46–50 | 31 (overflow clamp 포함, 6절) | 101 |
| G-opus-3 | 41 | 45: `existing != null && existing.isHeld.get() && now < existing.expiresAt` | 52–61 | 55: `now + lockTimeoutMillis` | 57 |
| G-haiku-4 | 40 | 45: `existing != null && existing.expiresAt > now` | 52–56 | 55: `now + lockTimeoutMillis` | 114 |

sidecar에 맞추려면 살아 있음 검사가 `now <= existing.expiresAt`이어야 한다.

### 2.4 harness 증거

harness는 8절에 있다. 구현 코드는 바꾸지 않았다.

- 시나리오 A: tryLock의 시계(`nowToEpochTime`)만 고정한다. `lockTimeoutMillis = 100`이다.
  - 네 구현 모두 `now = base+99`에서 `null`을 돌려주었다.
  - 네 구현 모두 `now = base+100`(= lease)에서 새 token을 돌려주었다. 그 뒤 이전 holder의 `unLock`은 `false`였다.
  - 네 구현 모두 `now = base+101`에서 새 token을 돌려주었다.
- 시나리오 E: 실제 시계를 쓰고 `lockTimeoutMillis = 5`이다.
  - 두 번째 tryLock의 `now`는 새 entry의 `expiresAt - 5`로 확인했다. 이 값이 첫 holder의 lease와 같았다.
  - 네 구현 모두 이때 새 token을 돌려주었다.
  - 예 (G-haiku-1): holder1 `local-6`, lease `1793007093791`. 두 번째 호출 `now = 1793007093791` → `local-7`. holder1의 `unLock` → `false`.

## 3. 결함 B — monitor(sweep)가 lease 끝 시각에 entry를 지운다 (네 구현 공통)

### 3.1 sidecar가 정한 경계

- `ends_only_if P3a`(96행, sweep): `now > old(tok(qt).gLease)`.
- sweep이 entry를 지우면 그 token은 더 이상 entry에 담기지 않는다. 그래서 그 token의 `gHeld`가 끝난다(52행 witness).
- 그러므로 sweep은 `now > expiresAt`일 때만 entry를 지울 수 있다.

### 3.2 시나리오

1. 스레드 A가 시각 `T`에 tryLock으로 `"k"`를 얻는다. entry는 `expiresAt = T+100`이다.
2. monitor pass가 key `"k"`에 대해 `System.currentTimeMillis()`를 읽는다. 값은 `T+100`이다.
3. 구현의 지우는 조건(`now >= expiresAt` 또는 `expiresAt <= now`)이 참이다. compute가 `null`을 돌려주어 entry를 지운다.
4. `P3a`(sweep)가 깨진다. 끝나는 token의 lease는 `T+100`이다. `T+100 > T+100`은 거짓이다.
5. 결과: 같은 밀리초 `T+100`에 스레드 C가 tryLock을 부르면, entry가 없으므로 C는 새 backed token을 받는다(progress_free). sidecar에서 A는 아직 lock을 가진다. 그래서 A와 C가 동시에 lock을 가진다.

### 3.3 구현별 행 번호

| ID | sweep 위치 | `now` 읽기 | 지우는 조건 (결함 위치) |
|---|---|---|---|
| G-haiku-1 | `removeIfExpired` 100–105행. 92행이 key마다 부른다. | 101 | 103: `if (existing != null && now >= existing.expiresAt) null else existing` |
| G-sonnet-2 | `removeExpiredEntries` 106–118행 | 110 | 112: `if (existing != null && existing.expiresAt <= now) null else existing` |
| G-opus-3 | companion `init`의 coroutine 107–117행 | 111 | 113: `if (existing != null && now >= existing.expiresAt) null else existing` |
| G-haiku-4 | `removeExpiredEntries` 98–105행 | 100 | 102: `if (existing != null && existing.expiresAt <= now) null else existing` |

sidecar에 맞추려면 지우는 조건이 `now > existing.expiresAt`이어야 한다.

### 3.4 harness 증거 (시나리오 B)

- harness는 key `"S<e>"`마다 `expiresAt = e`인 entry를 3500개 만든다. `e`는 실제 미래 시각이고, 1 ms 간격이다.
- 이 entry를 지우는 코드는 monitor뿐이다. monitor는 자기 `now`가 `e` 이상일 때만 `"S<e>"`를 지울 수 있다.
- 실제 시계가 아직 `e`를 읽는 동안 `"S<e>"`가 없어졌다면, monitor는 `now == e`로 그 entry를 지운 것이다.
- 약 3.5초 동안의 관측 횟수: G-haiku-1 4회, G-sonnet-2 6회, G-opus-3 4회, G-haiku-4 5회. 대략 monitor pass마다 한 번이다.

## 4. 성립하는 속성 (속성별 논증)

이 절의 논증은 네 구현에 공통이다. 구현별 차이는 각 항목에 적었다. 2절과 3절의 경계 결함은 여기서 다시 세지 않는다.

네 구현이 공유하는 사실:

- F1. map을 바꾸는 코드는 `lockMap.compute`뿐이다. 같은 key의 compute는 서로 직렬화된다.
- F2. entry는 tryLock의 새 entry 분기에서만 생긴다. 이 entry는 그 호출이 발급한 새 token, `expiresAt = now + lockTimeoutMillis`, `isHeld = true`, permit 0개를 가진다.
- F3. map 안에 있는 entry의 `token`, `expiresAt`, `isHeld`는 바뀌지 않는다. unLock이 이 필드나 semaphore를 바꾸는 구현(haiku-1 74–75행, opus-3 78–80행, haiku-4 70–71행)은 같은 compute에서 그 entry를 지운다.
- F4. token은 `"local-${tokenSequence.incrementAndGet()}"`이다. 그래서 두 호출이 같은 token을 받지 않는다.

### 4.1 tryLock

| 속성 | 논증 |
|---|---|
| issue 규칙, P1a (60, 62–63행) | F4에 따라 `res`는 처음 발급된다(`!old(gIssued)`). `gKey = k`와 `gLease = now + timeout`은 issue 규칙이 정한다. backed token은 entry가 담으므로 held이다. unbacked token은 발급 때 held이다. haiku-4는 token 번호를 compute 전(42행)에 뽑는다. 그 번호는 이 호출만 가지므로 결론이 같다. |
| P1a_entry (64행) | `gBacked = carried(S, k, res)`이다. 그래서 backed이면 사후 entry가 `res`를 담는다. 새 entry 분기가 바로 그 entry를 넣는다. |
| P3b, unbacked_only_if (67행) | unbacked token은 cap 분기에서만 나온다. cap 분기는 `existing == null`(즉 `!old(has(S, k))`)과 `rejectsNewEntry == true`(rej)를 요구한다. 40행이 `rej ==> limit != 0`을 준다. cap 분기 행: haiku-1 43–46, sonnet-2 35–39, opus-3 48–51, haiku-4 48–51. |
| progress_free (69행) | key가 없고 rej가 거짓이면, 새 entry 분기로 가서 backed token을 돌려준다. |
| cap_unbacked (71행) | key가 없고 rej가 참이면, token을 돌려주고 compute는 `null`을 돌려준다. 그래서 entry가 생기지 않는다. harness 시나리오 C(max-entries = 1)에서 네 구현 모두 token을 돌려주고 entry를 만들지 않았다. |
| expired_takeover (72행) | `now > expiresAt`이면 살아 있음 검사가 거짓이다. 그래서 cap 검사가 없는 새 entry 분기로 가서 backed token을 돌려준다. harness 시나리오 C에서 cap이 차 있어도 네 구현 모두 만료 key를 takeover했다. |
| refusal_keeps_entry (74–75행) | `res == NULL`은 살아 있음 분기에서만 생긴다. 이 분기는 `existing`을 그대로 돌려준다. 그래서 entry, token, expiresAt가 그대로다. |
| progress_live, `now < expiresAt`인 경우 | 이 범위에서 네 구현 모두 `null`을 돌려준다. 깨지는 경우는 `now == expiresAt` 하나다(2절). |
| P3a, `now > gLease`인 경우 | takeover는 이전 token 하나만 끝낸다. `entry_lease`에 따라 그 lease는 `expiresAt`와 같다. 그래서 `now > expiresAt`인 takeover는 P3a를 지킨다. |

### 4.2 unLock

| 속성 | 논증 |
|---|---|
| P1 (83–84행) | `res = true`는 entry가 있고 그 token이 `t`일 때만 생긴다. F2와 F3에 따라 그 token은 이 key로 발급된 backed held token이다. 같은 compute가 entry를 지운다. 그래서 `!has(S, k)`이고, 52행 witness에 따라 `t`의 `gHeld`가 끝난다. |
| P3a, ends_only_if (85행) | unLock이 끝내는 token은 지운 entry의 token, 즉 `t` 하나다. 그때 `res = true`이다. 다른 경로는 map을 바꾸지 않는다. |
| progress_owner (87행) | entry의 token이 `t`이면 네 구현 모두 entry를 지우고 `true`를 돌려준다. sonnet-2(64행)와 opus-3(76행)은 `isHeld`도 검사한다. F2와 F3에 따라 map 안 entry의 `isHeld`는 늘 `true`이다. 그래서 이 검사는 결과를 바꾸지 않는다. |
| no_entry_created (89행) | `existing == null`이면 네 구현 모두 `existing`(즉 `null`)을 돌려준다. 그래서 entry가 생기지 않는다. |

harness 시나리오 C의 결과는 네 구현 모두 같았다.

- 틀린 token: `false`. entry는 그대로다.
- 주인 token: `true`. entry가 없어진다.
- 같은 token을 두 번째로 쓰면: `false`.
- entry가 없는 key: `false`. entry가 생기지 않는다.

### 4.3 sweep

| 속성 | 논증 |
|---|---|
| no_entry_created (99행) | `existing == null`이면 지우는 조건이 거짓이다. 그래서 `existing`(즉 `null`)을 돌려준다. key를 읽은 뒤 entry가 사라져도 결과가 같다. |
| P3a, `now > gLease`인 경우 | sweep은 지운 entry의 token 하나만 끝낸다. `now > expiresAt = gLease`이면 P3a를 지킨다. 깨지는 경우는 `now == expiresAt` 하나다(3절). |

### 4.4 invariant와 lemma

| 속성 | 논증 |
|---|---|
| P2 (105행) | 모든 semaphore는 `Semaphore(1)`로 생기고 곧바로 `tryAcquire()`로 permit 0이 된다. `release()`는 unLock이 entry를 지울 때 한 번만 부른다. 지운 entry는 다시 map에 들어가지 않는다. 그래서 permit은 0 또는 1이다. sonnet-2는 `release()`를 부르지 않는다(permit 0 유지). |
| entry_lease (108–109행) | F2에 따라 새 entry의 `expiresAt`는 `now + lockTimeoutMillis`이다. 이 값은 issue 규칙의 `gLease`와 같다. F3에 따라 이 값은 바뀌지 않는다. sonnet-2의 overflow clamp는 6절에 적었다. |
| entry_held (114–115행) | F2와 F3에 따라 map 안 entry의 `isHeld`는 늘 `true`이다. 그 token은 entry가 담으므로 held이다(52행). |
| P3c (118–120행) | backed token은 그 key의 entry가 담는 동안만 held이다. entry는 token 하나만 담는다. 그래서 한 key에서 backed held token은 하나뿐이다. harness 시나리오 D(8 스레드 × 20000회, 시계 고정)에서 겹친 holder는 네 구현 모두 0이었다. |

## 5. sidecar 밖 결함 (defect-outside)

네 구현 모두에서 찾지 못했다. 확인한 자연어 항목은 아래와 같다.

| 자연어 명세 | 확인 결과 |
|---|---|
| SPEC.md 7–8행: 획득마다 ownership token이 있다. 획득한 holder만 해제한다. | 네 구현 모두 token을 `==`(내용 비교)로 비교한다. |
| SPEC.md 16행: 해제하면 entry를 바로 지운다. | 네 구현 모두 unLock의 compute가 `null`을 돌려준다. |
| SPEC.md 16–17행: monitor가 1 s 간격으로 만료 entry를 지운다. | 네 구현 모두 companion에서 `LOCK_MONITOR_INTERVAL_MILLIS`(1000 ms) 간격의 daemon monitor를 시작한다. 경계("has passed")는 sidecar가 정하므로 3절에서 다뤘다. |
| SPEC.md 37–40행: cap이 차면 새 key는 backing 없는 permit을 받는다. 이미 map에 있는 key는 cap 대상이 아니다. | 네 구현 모두 `existing == null`일 때만 `rejectsNewEntry`를 부른다. 만료 entry의 takeover는 cap을 보지 않는다. |
| SPEC.md 41–43행: soft cap, 거부 로그 | 네 구현 모두 `rejectsNewEntry`에 맡긴다. 크기 추정은 `lockMap.size`(haiku-1, sonnet-2, haiku-4) 또는 `lockMap.mappingCount()`(opus-3)이다. |
| KeyLock.kt 22행, 28행 KDoc | tryLock은 token 또는 `null`을 돌려준다. unLock은 token이 다르거나 entry가 없으면 `false`를 돌려준다. |
| RULES.md 1–6 | 네 구현 모두 compute 한 번, compute 전 `now`, key마다 `now`와 compute 한 번, 람다 밖에 선언한 AtomicReference/AtomicBoolean을 지킨다. sonnet-2의 try/catch는 compute 람다 밖(108–117행)에 있다. |

- haiku-1(스레드), opus-3(coroutine), haiku-4(`scheduleAtFixedRate`)의 monitor는 예외가 나면 멈춘다.
- 그러나 세 monitor 본문에는 예외를 던지는 호출이 없다. 그래서 결함으로 세지 않았다.

## 6. 판정에 넣지 않은 관찰

- Long overflow. 지시에 따라 sidecar 결함으로 세지 않았다.
  - haiku-1 53행, opus-3 55행, haiku-4 55행의 `now + lockTimeoutMillis`는 `lockTimeoutMillis > Long.MAX_VALUE - now`이면 음수로 넘친다. 그러면 새 lock이 곧바로 만료된 것으로 보인다.
  - sonnet-2 31행은 이 경우 `expiresAt = Long.MAX_VALUE`로 고정한다("never expires"). 무한 정수 모델에서 이 분기는 `expiresAt != gLease`를 만들어 `entry_lease`와 맞지 않는다. 그러나 이 분기는 실제 합이 overflow할 때만 실행된다. 그래서 모델 밖이다.
- haiku-4 42행은 token 번호를 compute 전에 뽑는다.
  - 그래서 거부된 호출도 번호 하나를 쓴다. harness 시나리오 A에서 `local-2`가 이렇게 빠졌다.
  - token은 여전히 유일하다. 어떤 sidecar 속성도 `tokenSequence`의 값을 읽지 않는다(hole은 판정 대상 밖). RULES.md 1도 증가 위치를 정하지 않는다.
- sonnet-2 unLock(63–70행)은 지우는 entry의 `isHeld`, `token`, semaphore를 그대로 둔다. map 밖 객체를 읽는 속성은 P2뿐이다. 그 permit은 0으로 남는다.
- `LockInfo` 위치. sidecar 16행은 `KeyLocalLock.LockInfo`를 묶는다.
  - opus-3만 이 위치(92–97행)에 둔다.
  - haiku-1(110–115행)과 sonnet-2(15–20행)는 최상위에 둔다. haiku-4(118–123행)는 companion 안에 둔다.
  - RULES.md 2는 위치를 정하지 않는다. 동작 결함이 아니므로 세지 않았다. 검증기 binding에는 영향을 줄 수 있다.

## 7. 해석에 기대는 지점

- tryLock 판정은 해석에 기대지 않는다. `progress_live`(70행)는 사전 상태, `now`, 결과만 읽는다. 2.2 시나리오에서 이 식은 바로 거짓이 된다.
- sweep 판정은 52행 witness의 읽기에 기댄다.
  - 읽기 1: "backed token은 entry가 담는 동안만 held"이다. 그러면 경계에서 지울 때 token이 끝나고, `P3a`(96행)가 깨진다.
  - 읽기 2: entry를 지워도 `gHeld`가 끝나지 않는다. 그러면 올바른 sweep(`now > expiresAt`) 뒤에도 옛 token이 held로 남는다. 다음 tryLock이 같은 key에 backed token을 하나 더 만들면 lemma `P3c`가 깨진다. 즉 읽기 2에서는 어떤 구현도 sidecar를 만족할 수 없다.
  - 그래서 읽기 1이 유일하게 일관된 읽기다. 읽기 1에서 네 구현의 sweep은 결함이다.

## 8. harness

- 위치: `/Users/ykhfree/AI/kotlin-verifier/aicode/round3g/judge-work/1/`
  - `src/shadow/TimeUtils.kt`, `src/shadow/FakeClock.kt`: support jar의 `TimeUtilsKt.nowToEpochTime()`를 같은 JVM 시그니처로 가린다. 고정값이 음수이면 실제 시계를 읽는다.
  - `src/harness/Harness.kt`: 시나리오 A(경계 tryLock), E(실제 시계 경계), C(나머지 절), D(상호 배제), B(실제 monitor 경계).
  - `src/harness/KeyLock.kt`: 샌드박스 사본이다.
  - `run.sh <ID>...`: 생성기의 `compile.sh`와 같은 컴파일러, classpath, 옵션으로 컴파일하고 실행한다. 생성기의 compile 예산은 쓰지 않는다.
  - `out/<ID>.txt`: 실행 결과.
- 구현 파일은 바꾸지 않았다. monitor는 실제 `System.currentTimeMillis()`를 읽는다.
