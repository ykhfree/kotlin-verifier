# 기계적 변이 측정

날짜: 2026-10-24. 상태: 결과 전에 쓴 측정 규칙. 이 문서는 결과를 본 뒤에 고치지 않는다. 고쳐야 하면 고친 날짜와 이유를 맨 아래에 적는다.

## 1. 목적

사람이 고른 변이 13종(DESIGN-v0.md 6절, DESIGN-B.md 7절)은 도구를 만든 쪽이 골랐다. 이 측정은 규칙으로 만든 많은 변이에서 도구의 판정을 잰다.
답할 질문은 두 가지다.
1. 도구가 놓치는 결함이 있는가 (건전성과 명세의 빈틈).
2. 도구가 정상 코드를 결함으로 보는가 (정밀도).

## 2. 대상

모듈 6개: KeyLocalLock 3종(coroutine, core, reactor), Spring Aspect 3종(core-spring, webflux, coroutine). 소스는 req-shield `2e792d2`, sidecar는 `specs/reqshield/`.

변이 위치:
1. sidecar 연산의 함수 본문과 그 안의 action 람다
2. `compute in` 연산(sweep)의 action 람다
3. 연산이 부르는 모듈 파일 안의 helper (예: `nextToken`)
4. 서명된 trusted fun의 본문 (`nowToEpochTime`, `rejectsNewEntry`, `createReqShield`)

모듈 파일의 그 밖의 코드(monitor 루프, 설정, 로그 helper)는 대상이 아니다.

위치는 FIR 원소의 소스 범위로 찾는다. 정규식으로 찾지 않는다. 컴파일되지 않는 변이는 "사산"으로 세고 판정에서 뺀다.

## 3. 변이 규칙

| ID | 규칙 | 적용 위치 |
|---|---|---|
| ROR | 관계 연산자 바꾸기: `>`↔`>=`, `<`↔`<=`, `==`↔`!=` | 모든 비교 |
| COR | `&&`↔`||` | 모든 논리 결합 |
| NEG | `if`/`when` 조건 부정: `c` → `!(c)` | 모든 조건 |
| BOOL | boolean 상수 뒤집기 (`compareAndSet(true, false)`의 인자 포함) | 모든 boolean 상수 |
| SDL | 식 문장 삭제 (호출, 대입). 선언, `return`, 람다의 마지막 식은 지우지 않는다 | 모든 식 문장 |
| RET | 경계 반환: `return@compute X`와 람다의 마지막 값 → `null`, `null` → 람다의 값 파라미터 (`existing`, `lockInfo`, `old`); 연산 함수의 결과 → 그 타입의 상수 (`null`, `true`, `false`) | 람다 결과, 함수 결과 |
| AOR | `+`↔`-`, 그리고 피연산자 하나 버리기 (`now + timeout` → `now`) | `Long` 산술 위치(가정 진단이 붙는 곳)만 |
| MOP | 모델 연산 바꾸기: `incrementAndGet`→`get`, `compareAndSet(a, b)`의 두 인자 교환, `tryAcquire()`→`true` | 신뢰 모델 호출 |
| RACY | 경쟁 읽기 삽입: action 바로 앞에 `val racy_N = <state map>.containsKey(<key>)`를 넣고, action 안이나 연산 구간의 조건 `c`를 `racy_N && c`로 바꾼다 | 조건마다 1개 |

RACY는 도구의 고유한 주장("action 밖의 읽기는 임의 값")을 겨냥한다. AI의 리팩터링이 실제로 만드는 모양이다 (SB2, E4).

## 4. 실행

1. 변이마다 소스 복사본을 만들고 `kv verify`를 돌린다. 판정은 종료 코드로 받는다 (0 PROVEN, 1 FAILED, 3 INCOMPLETE, 4 ERROR).
2. 생성한 Viper 모델을 `spike/vpr_normalize.py`의 정규화 형태로 해시한다. 해시가 같은 변이는 Silicon을 한 번만 돌리고 판정을 함께 쓴다. 해시가 원본 모델과 같은 변이는 "모델 동일"이다.
3. 결과는 변이마다 한 줄이다: id, 모듈, 규칙, Kotlin 위치, 바꾼 텍스트, 컴파일 여부, 판정, 실패 의무, 모델 해시, 모델 동일 여부, 그 줄의 ledger 종류.

## 5. 분류

| 판정 | 하위 분류 | 정답을 정하는 방법 |
|---|---|---|
| PROVEN, 모델 동일 | 규칙으로 추상화됨 | 기계적: 바뀐 줄의 ledger 종류(`free-source`, `not-modeled`, `cell` 등)와 모델 규칙. 예: `free-source` 줄의 변이는 자유 값이 임의 값이므로 설계상 보이지 않는다. 종류별로 묶어 보고한다 |
| PROVEN, 모델 바뀜 | 동등 / 놓침 / 미결 | 6절의 판정 규칙 |
| FAILED | 검출 / 예측된 오경보 / 설명 안 된 오경보 | 7절의 예측 목록. 목록에 없고 동등이 의심되는 FAILED는 6절의 판정자가 본다 |
| ERROR (`stale-review`) | 서명이 막음 | 기계적: trusted fun 본문 변이는 이것이 정답이다 |
| ERROR (그 밖) | 도구 결함 후보 | 모두 원인을 적는다 |
| INCOMPLETE | 지원 범위 밖 | 기계적: 개수와 구문 종류 |

변이 점수 = 검출 / (검출 + 놓침). 모듈별, 규칙별로 낸다. 미결은 점수에 넣지 않고 따로 센다.

## 6. 생존 변이의 판정 규칙

대상: "PROVEN, 모델 바뀜" 변이, 그리고 동등이 의심되는 FAILED 변이.

판정자: 새 맥락의 deep-reasoner. 구현 agent도 메인 세션도 판정하지 않는다. 판정자가 받는 것:
1. 문서화된 성질의 원문 (sidecar의 `ensures`, `invariant`, `lemma`, 권한 절과 그 출처 인용)
2. 원본과 변이의 Kotlin 소스
3. 정규화한 Viper diff

판정은 셋 중 하나이고, 근거가 있어야 한다.
1. **놓침**: 구체적 반례. 입력과 스레드 interleaving을 적고, 깨지는 성질의 ID를 댄다. 가능하면 그 반례를 Viper probe로 써서 Silicon이 그 성질을 반박함을 보인다.
2. **동등**: 그 변이가 깰 수 있는 성질을 하나씩 대고, 왜 깰 수 없는지 논증한다.
3. **미결**: 위 둘 중 어느 근거도 세우지 못함. 표에 미결로 남긴다.

상한: 판정 대상이 30개를 넘으면 ROR 경계와 RET 변이를 먼저 모두 보고, 나머지는 무작위로 30개까지 본다. 보고서는 표본임을 적는다.

## 7. 미리 등록한 예측 (결과 전)

도구 규칙이나 명세의 선택 때문에 나올 것으로 예측하는 판정이다. 이 목록에 맞는 결과는 "예측된" 것으로 세고, 정밀도 결함으로 세지 않는다.

1. **명세의 엄격한 경계 (FAILED, 검출로 셈)**: 만료 비교 `now > expiresAt`의 `>`→`>=`는 P3a(`now > old(gLease)`)를 깨므로 FAILED다. 명세가 "만료 시각이 지난 뒤"를 엄격하게 정했기 때문이다. 도구 오경보가 아니다.
2. **v0 경쟁 읽기 규칙 (FAILED, 예측된 오경보일 수 있음)**: `set-once`가 아닌 map(KeyLocalLock `lockMap`)에 대한 RACY 변이는 읽은 값이 임의 값이므로 FAILED가 될 수 있다. 실제 실행에서 그 읽기 값이 성질에 영향을 줄 수 없는 경우라면 이것은 v0 2.12 규칙의 부정확성에서 나온 예측된 오경보다. 판정자가 어느 쪽인지 정한다.
3. **질문 5 규칙 (FAILED, 예측된 오경보일 수 있음)**: fresh 호출 뒤, action 앞에 모델 밖 호출이 생기는 변이는 버려진 객체 경로 때문에 FAILED다. 그 호출이 실제로 던질 수 없다면 예측된 오경보다. 이 규칙들로는 그런 변이가 생기지 않을 것으로 예측한다.
4. **상수 전파 없음 (J6)**: 같은 식을 두 번 계산하는 변이는 자유 값 두 개가 되어 FAILED일 수 있다. 이 규칙들로는 생기지 않을 것으로 예측한다.
5. **서명된 본문 (ERROR)**: trusted fun 본문의 모든 컴파일되는 변이는 `stale-review`다.
6. **로그와 모델 밖 효과 (PROVEN, 모델 동일)**: `log.*` 호출의 SDL은 slf4j 모델 때문에 모델이 같다.
7. **제거되는 entry의 자원 (PROVEN, 모델 바뀜, 동등 예상)**: 바로 map에서 지워지는 entry에 대한 `semaphore.release()`나 `token = null`의 SDL은 명세의 어떤 성질도 깨지 않을 것으로 예상한다 (예: unLock의 release 삭제). 판정자가 확인한다.

## 8. 보고 형식

1. 모듈별·규칙별 변이 수, 사산 수, 판정 분포.
2. 변이 점수 (모듈별, 규칙별).
3. 생존 변이: {규칙으로 추상화됨, 동등, 놓침, 미결}. 놓침과 미결은 하나마다 한 문장.
4. FAILED: {검출, 예측된 오경보, 설명 안 된 오경보}. 설명 안 된 것은 하나마다 한 문장.
5. ERROR: {서명이 막음, 도구 결함 후보}. INCOMPLETE: 개수와 구문.
6. 7절 예측과 실제의 대조.

## 변경 기록

- 2026-10-24, 결과 전: 6절의 "동등이 의심되는 FAILED"를 정했다. 판정자는 다음을 본다.
  1. 7절 예측 2-4에 맞는 FAILED 전부
  2. 그 밖의 FAILED 중 무작위 10개 (오경보 비율의 추정)
  3. "PROVEN, 모델 바뀜" 전부 (6절 상한 적용)
  이유: 의심을 구현 agent나 메인 세션이 고르면 판정자의 독립성이 없어진다.
- 2026-10-24, 결과 뒤 (판정 전): 4절 2의 판정 공유에 검사 하나를 더했다. 해시가 같아 판정을 함께 쓰는 변이도 각자 `kv lower`로 미지원 구문과 완결성을 확인한다. 이유: kv는 미지원 부분을 출력하지 않으므로, 미지원 구문이 다른 두 변이가 같은 모델이 될 수 있다 (측정: RET-04와 SDL-01). 이번 실행의 충돌은 0이고, 범주별 개수는 검사 전후가 같다.
- 2026-10-24, 재측정 전 (사용자 결정 뒤): sidecar를 바꿨다. `progress_live`를 좁히고 `cap_unbacked`, `expired_takeover`를 더했다. 도구 보고도 바꾼다: 불변식 조각만 실패하면 성질을 따로 확인하고, "성질 위반"과 "불변식만 불일치"로 나눈다. 재측정 결과의 예측 (결과 전에 등록):
  1. MOP-01 ×2와 core RACY-02는 `expired_takeover`로 FAILED가 된다.
  2. RACY-04 ×3과 coroutine SDL-08은 `cap_unbacked`로 FAILED가 된다.
  3. coroutine RACY-09와 reactor RACY-08은 PROVEN으로 남는다.
  4. RACY-03 ×3, coroutine RET-11, reactor SDL-02는 "불변식만 불일치"로 FAILED다.
  5. RACY-06 ×3은 성질 위반(`progress_owner`)으로 FAILED다. 의도대로 읽으면 v0 2.12(경쟁 읽기는 임의 값) 때문에 생긴 오경보다.
  6. "불변식만 불일치"는 무해하다는 뜻이 아니다. 판정자 B의 core·reactor AOR-03과 coroutine SDL-06은 다음 단계에서 P3a를 깨는 진짜 결함인데, "불변식만 불일치"로 보고될 수 있다.

- 2026-10-25, 2차 재측정 전 (DESIGN-C.md 10, 사용자 결정 뒤): sidecar에 `invariant entry_lease`(entry의 만료 시각 = 그 토큰의 lease)와 tryLock의 `ensures refusal_keeps_entry`(거절된 호출은 owner와 lease를 바꾸지 않음)를 더했다. KeyLocalLock 세 모듈만 다시 잰다 (aspect sidecar는 바뀌지 않음). 결과는 `mutation/rerun2/`. 예측 (결과 전에 등록):
  1. FAILED에서 PROVEN으로 바뀌는 변이는 0개다. 이유: `entry_lease`는 hole 조각 MI_INV_4와 같은 식이라 사전 조건에 새 가정을 더하지 않고, 새 ensures는 의무만 더한다.
  2. PROVEN에서 FAILED로 바뀌는 변이는 0개다. 이유: 두 절을 깨는 변이는 MI_INV_4나 MI_INV_5(또는 P3a)도 깨므로 이미 FAILED였다.
  3. MI_INV_4 하나로 "invariant only"였던 15개(SDL-06 ×2, reactor SDL-05, AOR-01..04 ×3)는 "property"(`entry_lease`)가 된다.
  4. MI_INV_1 하나로 "invariant only"였던 18개는 그대로 "invariant only"다. 새 절은 permit과 isHeld를 말하지 않는다.

- 2026-10-25, 2차 재측정 결과 (`mutation/rerun2/`, 결과 뒤): FAILED→PROVEN 0, PROVEN→FAILED 0 (예측 1, 2 맞음). MI_INV_4 "invariant only" 15개가 모두 `entry_lease` 성질 위반이 됐다 (예측 3 맞음). 예측 4는 3개에서 틀렸다: coroutine·core RET-11과 reactor RET-08이 `entry_lease`로 property가 됐다. 원인은 메인 세션이 쓴 절이다. 이 변이들은 sweep에서 `token = null`로 만든 entry를 지우지 않고 남긴다. 조건 없는 `entry_lease`는 "모든 entry가 토큰을 가진다"도 요구해서, 토큰 없는 entry에서 실패했다. 이것은 사용자가 정한 뜻(UC2: entry가 가진 토큰의 lease)보다 넓고, 더하지 않기로 한 monitor 의무(UC1) 쪽에 걸친다.
- 2026-10-25, 3차 재측정 전 (결과 뒤의 변경): `entry_lease`에 `S.m[qk].token != NULL` 조건을 넣었다. 이유는 바로 위 항목이다. 결과는 `mutation/rerun3/`. 예측 (결과 전):
  1. rerun2와 같다. 다만 RET-11 ×2와 reactor RET-08은 rerun과 같은 "invariant only"(MI_INV_1)로 돌아간다.
  2. MI_INV_4 "invariant only"였던 15개는 계속 `entry_lease` 성질 위반이다. 이 변이들은 토큰을 지우지 않는다.

- 2026-10-25, 4차 재측정 전 (사용자 결정 뒤, DESIGN-C.md 10.3): sidecar에 `invariant entry_held`(토큰을 가진 entry의 isHeld = 그 토큰의 gHeld)를 더했다. KeyLocalLock 세 모듈을 다시 잰다. 결과는 `mutation/rerun4/`, 비교 대상은 `rerun-c2/`. 예측 (결과 전):
  1. 판정(PROVEN, FAILED 등)이 바뀌는 변이는 0개다. 이유: hole의 MI_INV_1(entry는 held)과 MI_INV_3(entry의 토큰은 held)이 `entry_held`를 이미 함축한다. 그래서 사전 조건에 새 가정이 없고, 이 절을 깨는 변이는 그 hole 조각도 깬다.
  2. MI_INV_1 "invariant only" 18개 중 isHeld를 false로 남기는 12개(SDL-04, BOOL-03, SDL-10, BOOL-04; 각 세 모듈)는 `entry_held` 성질 위반이 된다.
  3. 나머지 6개(SDL-09 ×3: permit; coroutine·core RET-11과 reactor RET-08: 토큰이 null인 entry)는 "invariant only"로 남는다.

## 9. 결과 (2026-10-24, 판정 뒤)

이 절은 결과다. 1-8절의 규칙은 바꾸지 않았다.

### 9.1 기계적 집계 (측정)

변이 278개 중 사산 27개다. 판정 분포는 다음과 같다.
- FAILED 129개
- PROVEN, 모델 바뀜 60개
- PROVEN, 모델 동일 7개 (not-modeled 6, translated 1)
- ERROR `stale-review` 47개
- 그 밖의 ERROR 0개
- INCOMPLETE 8개

상세: `mutation/summary.md`, `mutation/results.jsonl`.

### 9.2 판정 (판정자 A, B: 새 맥락의 deep-reasoner 2개)

| 대상 | 판정 | 근거 파일 |
|---|---|---|
| PROVEN, 모델 바뀜: 60개 중 표본 30 | 놓침 0, 동등 30 (행동 같음 21, 행동은 바뀌나 sidecar 성질은 지킴 9), 미결 0 | `mutation/judgments-A.md`, `mutation/judge-work/A/` |
| FAILED, 7.2 (경쟁 읽기) 9 | 검출 9, 오경보 0 | `mutation/judgments-B.md`, `mutation/judge-work/B/` |
| FAILED, 무작위 10 | 검출 9, 오경보 1 (reactor SDL-02) | 같음 |

1. **놓침:** 표본 30개에서 0개다. 표본이 30개이므로 "모델 바뀜 PROVEN" 가운데 놓침 비율의 95% 상한은 약 10%다 (3/30 규칙, 추정).
2. **오경보 (FAILED 무작위 표본):** 1/10이다. 95% 구간은 0.25-44.5%다 (판정자 B 계산, 표본이 작다).
3. **글자대로 읽기 대 의도대로 읽기:** 검출 7개(RACY-03 ×3, RACY-06 ×3, RET-11)는 진행 조건 `progress_live`, `progress_owner`를 sidecar 원문 그대로 읽을 때만 검출이다. 의도대로 좁혀 읽으면 오경보가 된다. 그때 무작위 표본의 오경보는 2/10이고, 7.2 범주의 검출은 3/9(RACY-01 ×3)다.
4. **7절 예측 대조:**
   - 7.1, 7.3-7.6은 9.1의 집계와 같다.
   - 7.2의 FAILED 9개는 글자대로 읽으면 모두 검출이다. 의도대로 읽으면 6개가 오경보다.
   - 7.7의 9개는 판정자 A가 모두 동등(행동 같음)으로 판정했다.

### 9.3 발견

1. **도구의 건전성 결함은 찾지 못했다.** 놓침이 0이고, 도구 결함 후보 ERROR도 0이다. 판정자 A는 표본 30개 모두에서 변이 모델이 변이 Kotlin을 그대로 옮겼음을 diff로 확인했다.
2. **오경보 1개의 원인은 고정된 hole이다.** reactor SDL-02(만료 takeover의 `release()` 삭제)는 permits 0인 죽은 entry를 남긴다. 그 entry는 어떤 성질도 깨지 않는다. 원본 코드에 맞춘 불변식 `MI_INV_1`(INV-E)이 이 상태를 배제했을 뿐이다. 불변식을 넓힌 사본에서는 변이도 원본도 증명된다 (메인 세션 재현). 실제 사용에서는 코드가 바뀌면 hole을 다시 쓴다 (M7 흐름).
3. **FAILED가 성질이 아니라 hole 조각만 가리키는 경우가 많다.** 검출 18개 중 9개는 `MI_INV_*`만 보고됐다. Silicon은 경로마다 처음 실패한 사후조건만 보고하고, 템플릿이 hole 조각을 성질보다 앞에 두기 때문이다. 이런 보고만으로는 사용자가 "성질이 깨졌는지, 불변식만 안 맞는지"를 알 수 없다.
4. **진행 조건이 의도보다 강하다.**
   - `progress_owner`는 발급받지 않은 token을 내는 호출자에게도 해제 성공을 요구한다.
   - `progress_live`는 잡히지 않은 죽은 entry에도 획득 거부를 요구한다.
   - 이 두 절은 설계 단계에서 probe용으로 썼고, 사람이 서명하지 않았다.
5. **명세의 빈틈.** 아래 변이는 sidecar 성질은 모두 지키지만, 문서화된 동작을 깬다.
   - 상한 동작: RACY-04 ×3, coroutine SDL-08. README 122, 139, 141행: "To bound it, set `req-shield.lock.max-entries`", "Once the map is full, a request for a **new** key is handed a permit that no map entry backs.", "no added latency, and the cache still gets populated". 판정자 B의 SDL-02(key가 최대 약 1 s 막힘)도 지연에 관한 같은 종류다.
   - 만료된 lock의 즉시 넘겨받기: MOP-01 ×2, core RACY-02. `KeyLock.kt` KDoc은 만료된 lock을 다른 holder가 넘겨받는다고 전제하지만, "즉시"라고 쓰지는 않았다. 그래서 논쟁의 여지가 있다. req-shield 단위 테스트는 MOP-01에서 실패한다 (판정자 A 측정).
   - monitor의 만료 entry 제거: coroutine RACY-09, reactor RACY-08. 드문 경쟁에서 한 주기 늦어질 뿐이라, 판정자 A는 빈틈이 아니라고 봤다.
   - 판정자 A의 probe `G_cap`, `G_expired`, `G_sweep`은 원본에서 증명되고 해당 변이에서만 실패한다. 이 probe들은 sidecar에 더할 후보다.
6. **기계적 규칙의 한계.** Aspect 세 모듈은 판정받은 변이가 0개다. 연산 코드가 `computeIfAbsent` 한 줄이라, 기계적 규칙의 변이가 서명된 함수에 걸리거나 사산이 된다.

### 9.4 메인 세션 확인

판정자의 증거 10개를 Silicon으로 다시 돌렸고, 모두 판정자의 설명과 같았다.
- B 쪽 4개:
  - SDL-02: 불변식을 넓힌 사본이 증명된다.
  - 원본: 불변식을 넓힌 사본이 증명된다.
  - RET-06: 순서를 바꾼 사본에서 P1이 보고된다.
  - RACY-01: 두 holder 시나리오가 검증된다.
- A 쪽 6개:
  - 대조군 NC1은 실패한다.
  - 동등성 probe에서 원본과 BOOL-09는 증명되고, MOP-01은 실패한다.
  - `G_cap` probe에서 원본은 증명되고, RACY-04는 실패한다.

README 인용 3개는 quote-check로 원문을 확인했다.

### 9.5 재측정 (2026-10-24, sidecar 변경과 pass 2 뒤)

바뀐 것은 두 가지다.
- sidecar (`57c36eb`): `progress_live`를 좁히고 `cap_unbacked`와 `expired_takeover`를 더했다.
- 도구 (`5ff2b62`): 불변식 조각만 실패한 action은 그 조각을 뺀 두 번째 Silicon 실행(pass 2)으로 성질을 확인한다. 보고는 "FAILED (property)"와 "FAILED (invariant only)"로 나눈다.

결과 파일: `mutation/rerun/` (results.jsonl, summary.md, compare.md; `e0f5d00`).

**판정 변화 (측정):**
- 판정이 바뀐 변이는 15개다. 모두 PROVEN에서 FAILED로 바뀌었고, 반대 방향은 없다.
  - `expired_takeover`: RACY-02, BOOL-01, MOP-01 (각 세 변형)
  - `cap_unbacked`: RACY-04, SDL-08 (각 세 변형)
- 집계:
  - FAILED 129 → 144, PROVEN 67 → 52 ("모델 바뀜" 60 → 45).
  - 하위 상태 (action 단위): property 108, invariant only 36, property check ERROR 0.
- 1차에서 hole 조각만 보고된 56개는 invariant only 36과 property 20으로 나뉘었다. 이것은 판정 변화가 아니라 보고의 세분화다. compare.md의 "바뀐 변이 74개"는 판정 변화 15개와 이 세분화를 함께 센 수다.
- pass 2 Silicon 실행은 53회다. wall clock은 898 s에서 1417 s로 늘었다. pass 2 한 번은 최대 63 s 걸렸다.

**예측 대조 (변경 기록의 예측 1-6):**

| 예측 | 대조 | 설명 |
|---|---|---|
| 1 | 맞음 | MOP-01 ×2, core RACY-02가 `expired_takeover`로 FAILED다. 예측 밖의 reactor MOP-01, 세 변형의 BOOL-01도 같다 |
| 2 | 맞음 | RACY-04 ×3, coroutine SDL-08이 `cap_unbacked`로 FAILED다. core·reactor SDL-08도 같다 |
| 3 | 맞음 | coroutine RACY-09, reactor RACY-08은 PROVEN이다 |
| 4 | 안 맞음 | coroutine RET-11만 invariant only다. RACY-03 ×3과 reactor SDL-02는 pass 2에서 `expired_takeover` 위반으로 property가 됐다 |
| 5 | 맞음 | RACY-06 ×3은 `progress_owner` 위반이다 |
| 6 | 맞음 | core·reactor AOR-03, coroutine SDL-06이 invariant only로 보고됐다. 손으로 고른 SB3(진짜 결함)도 세 변형 모두 invariant only다 |

**예측 4가 틀린 이유:**
- 예측을 등록할 때 메인 세션은 새 성질 `expired_takeover`가 이 변이들의 동작을 덮는다는 점을 보지 못했다.
- reactor SDL-02는 만료 takeover에서 `release()`를 지운다. 그래서 permit이 0으로 남아 획득에 실패하고, 만료된 lock을 넘겨받지 못한다.
  - 판정자 B는 이 동작을 옛 sidecar 기준으로 "오경보이자 명세의 빈틈(key가 최대 약 1 s 막힘)"으로 판정했다.
  - 새 성질이 그 빈틈을 메웠고, 이제는 위반으로 검출된다.
- RACY-03은 takeover 뒤의 `tryAcquire()` 앞에 경쟁 읽기 조건을 넣는다. 다음 interleaving에서 만료된 lock을 넘겨받지 못한다 (메인 세션 분석, 판정자가 확인하지는 않음):
  1. 우리 스레드의 읽기가 아직 없는 entry를 본다.
  2. 다른 스레드가 오래 멈춘 뒤, 이미 지난 만료 시각으로 entry를 만든다.
  3. 우리 스레드의 compute가 그 entry를 해제하지만 획득을 건너뛴다.

**주의:** "invariant only"는 무해하다는 뜻이 아니다. SB3, AOR-03, SDL-06은 다음 단계에서 P3a를 깨는 진짜 결함인데 이 하위 상태로 보고된다. 보고 문구는 이 점을 직접 말한다 ("A property may still break in a later step").

**메인 세션 확인:**
- 테스트 408개 통과, gate 4개 exit 0, 손으로 고른 변이 33회와 30회 불일치 0.
- `rerun/results.jsonl`을 직접 집계했고, 판정 변화 목록을 1차와 비교해 뽑았다.
- reactor SDL-02와 coroutine RET-11의 보고서 원문을 읽었다.

### 9.6 2차·3차 재측정 (2026-10-25, `entry_lease`와 `refusal_keeps_entry` 뒤)

바뀐 것: sidecar에 `invariant entry_lease`와 tryLock의 `ensures refusal_keeps_entry`를 더했다 (DESIGN-C.md 10, UC2와 UC3). KeyLocalLock 세 모듈만 다시 쟀다. 예측은 변경 기록에 있다.

| 실행 | `entry_lease` | 결과 파일 |
|---|---|---|
| 2차 (`rerun2/`) | 조건 없음 | 예측 1-3 맞음, 예측 4는 3개에서 틀림 (변경 기록) |
| 3차 (`rerun3/`) | `S.m[qk].token != NULL` 조건 | 예측 1, 2 맞음 |

**3차 결과 (측정, 9.5의 `rerun/`과 비교):**
- 판정(PROVEN, FAILED 등)이 바뀐 변이는 0개다.
- 하위 상태만 바뀐 변이는 15개다. 모두 "invariant only"(MI_INV_4)에서 "property"(`entry_lease`)가 됐다: AOR-01..04 ×3, coroutine·core SDL-06, reactor SDL-05.
  - 그중 core·reactor AOR-03과 coroutine SDL-06은 9.5 "주의"의 진짜 결함이다. 이제 성질 위반으로 보고된다.
  - 손으로 고른 SB3도 세 변형 모두 `entry_lease` 성질 위반이다 (`scripts/mutants-expected.txt`).
- 집계 (KeyLocalLock 세 모듈, 264개): FAILED property 126, invariant only 18, PROVEN 52, INCOMPLETE 8, ERROR 36 (review 막힘), 사산 24.
- 남은 "invariant only" 18개는 모두 MI_INV_1이다 (permit, isHeld, token이 있는 entry). 새 절은 이것을 말하지 않는다.

**메인 세션 확인:** 테스트 452개 통과, gate 3개(KeyLocalLock) exit 0, mutantsReqShield 33회와 mutantsAspect 30회 불일치 0. `rerun`, `rerun2`, `rerun3`의 results.jsonl을 직접 비교했다.

### 9.7 4차 재측정 (2026-10-25, `entry_held` 뒤)

결과 파일 `mutation/rerun4/`, 비교 대상 `rerun-c2/` (C2 사실 포함, 판정은 `rerun3`과 같음). 예측은 변경 기록에 있다.

| 예측 | 결과 |
|---|---|
| 1. 판정 변화 0 | 맞음 |
| 2. SDL-04, BOOL-03, SDL-10, BOOL-04 (각 세 모듈) 12개가 `entry_held` 성질 위반 | 맞음 (정확히 이 12개) |
| 3. SDL-09 ×3, coroutine·core RET-11, reactor RET-08 6개는 "invariant only"로 남음 | 맞음 |

집계 (KeyLocalLock 세 모듈, 264개): FAILED property 138, invariant only 6, PROVEN 52, INCOMPLETE 8, ERROR 36 (review 막힘), 사산 24. 처음 측정(9.5)의 "invariant only" 36개 중 30개가 이제 이름 있는 성질 위반으로 보고된다.

메인 세션 확인: 테스트 519개 통과 (지금 sidecar의 모양을 고정한 테스트 5개를 고쳤다: 기대 목록에 `entry_held`, 필드 고정 경고 0개, PropertyCheckTest 2개는 hole 조각이 먼저 실패하는 경로를 계속 시험하도록 작업 사본에서 `entry_held`를 뺀다), mutantsReqShield 33회와 mutantsAspect 30회 불일치 0.
