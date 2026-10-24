# KeyLocalLock "PROVEN, 모델 바뀜" 변이 판정 — 판정자 A

- 날짜: 2026-10-24.
- 판정자: A (새 맥락의 deep-reasoner). advisor는 쓰지 않았다.
- 대상: `mutation/judge/index.md`에서 범주가 "PROVEN, 모델 바뀜"인 30개.
  index.md에 따르면 이 범주는 60개이고, 이 30개는 표본이다 (RET 9개 전부 + 나머지 51개 중 무작위 21개, seed 20261025).
- 독립성: MUTATION.md 7절, HISTORY.md, `mutation/summary.md`, `results.jsonl`의 `tags`를 읽지 않았다.
  작업 중에 생긴 `mutation/judge-work/B/`도 열지 않았다.
- 읽은 것: 판정 파일 30개, req-shield `2e792d2`의 세 `KeyLocalLock.kt`와 `KeyLock.kt`, sidecar `specs/reqshield/keylocallock.{common,core,coroutine,reactor}.kv`,
  생성 모델 `translator/build/kv/mechanical/keylocallock.*/models/`, 변이 소스 `.../roots/<id>/`, 도구 보고서 `.../verify/<id>.report`,
  DESIGN-v0.md (2.12, 3.5, 4.3, 4.4, 4.6, 5.2), MUTATION.md (7절 제외), req-shield README.md와 CLAUDE.md.

## 0. 방법

1. 변이 소스(`roots/<id>/`)를 원본과 diff 했다. 30개 모두 판정 파일이 적은 한 곳만 바뀌었다. support 소스(`rejectsNewEntry`, `nowToEpochTime`)는 30개 모두 원본과 같다.
2. 변이 모델을 원본 모델과 diff 했다. 30개 모두 바뀐 Kotlin 문장을 DESIGN-v0.md 4.3, 5.2 규칙대로 그대로 옮겼다. 번역 오류는 찾지 못했다.
3. Silicon v.26.08-release (Z3 4.16.0)로 변이 모델 30개를 다시 돌렸다. 옵션은 kv와 같다: `--reportReasonUnknown --numberOfErrorsToReport 0 --assertTimeout 20000 --checkTimeout 20000`. 30개 모두 PROVEN이 재현됐다 (각 4.8-7.7 s).
4. 관측 동등 probe `E_op`를 돌렸다. 모델 사본의 action 메서드 3개에, 그 action의 관측 효과 전체를 정하는 사후조건을 더했다.
   - 관측 효과 = `has()`로 본 map, 살아 있는 entry의 객체 동일성과 네 필드(`permits`, `isHeld`, `token`, `expiresAt`), counter `S.seq`, 연산 결과 `res`.
   - 다른 key의 entry는 `JA_FRAME`이 고정한다 (같은 객체, 같은 네 필드).
   - ghost 갱신은 이 값들과 자유 값만으로 정해진다 (DESIGN-v0.md 3.5의 END, ISSUE). 그래서 ghost는 따로 비교하지 않는다.
   - E_op는 원본 모델 3개에서 PROVEN이다. 따라서 변이 모델이 E_op를 증명하면, 그 변이는 MI를 만족하는 모든 상태와 모든 자유 값에서 원본과 같은 관측 효과를 낸다.
5. 빈틈 probe `G_*`를 돌렸다. sidecar에 없는 성질 3개를 하나씩 더한 모델 사본이다. 세 성질은 원본 3개에서 모두 PROVEN이다.
   - `G_expired` (tryLock): `old(has(S, k)) && now > old(S.m[k].expiresAt) ==> res != NULL && tok(res).gBacked`
   - `G_cap` (tryLock): `!old(has(S, k)) && rej ==> res != NULL && !tok(res).gBacked && !has(S, k)`
   - `G_sweep` (sweep): `old(has(S, k)) && now > old(S.m[k].expiresAt) ==> !has(S, k)`
6. 대조군 2개로 E_op 매크로가 공허하지 않음을 확인했다. 원본 사본에 거짓 사후조건을 넣었고, 둘 다 FAILED다.
   - NC1: tryLock의 살아 있는 entry 경로에서 `expiresAt`이 1 늘어난다고 주장 (JA_SAME 영역).
   - NC2: sweep 뒤 다른 key의 살아 있는 entry `expiresAt`이 1 늘어난다고 주장 (JA_FRAME 영역).
7. 30개 변이와 원본 3개에 req-shield 자체 단위 테스트 `KeyLocalLockTest`를 소스 사본에서 돌렸다 (4절). 판정의 근거가 아니라 행동 차이와 spec gap의 보조 근거다.

probe 파일 (모두 `mutation/judge-work/A/` 아래):

| 경로 | 내용 |
|---|---|
| `mkprobes.py` | probe 모델 생성 (생성 모델은 읽기만 한다) |
| `run.sh` | Silicon 실행, 4개씩 병렬 (인자 `rerun`, `equiv`, `gap` 중 하나) |
| `equiv/<변형>__<id>.vpr` | E_op probe 33개 (원본 3 + 변이 30) |
| `gap/<변형>__<id>__G_*.vpr` | G_* probe 99개 |
| `controls/NC1.vpr`, `controls/NC2.vpr` | 대조군 |
| `out/{rerun,equiv,gap}/*.out`, `out/NC1.out`, `out/NC2.out` | Silicon 출력과 종료 코드 |
| `unittest/run_tests.sh`, `unittest/results/*.xml`, `unittest/test-summary.txt` | req-shield 단위 테스트 실행기와 결과 (4절) |

재현:

```sh
cd /Users/ykhfree/AI/kotlin-verifier/mutation/judge-work/A
python3 -I mkprobes.py
sh run.sh rerun && sh run.sh equiv && sh run.sh gap
```

## 1. 판정표

`[행동 동일]`: E_op가 PROVEN이다. 변이가 원본과 같은 관측 효과를 낸다.
`[행동 바뀜]`: E_op가 FAILED다. 행동은 바뀌지만 sidecar 성질은 모두 지킨다 (재실행 PROVEN).

| id | 판정 | 깨진 성질 | spec gap | 한 줄 이유 |
|---|---|---|---|---|
| keylocallock.coroutine/RET-02 | 동등 | — | 아니오 | [행동 동일] else 분기에서 `existing`은 null이므로 compute가 받는 값이 원본과 같다 |
| keylocallock.coroutine/RET-05 | 동등 | — | 아니오 | [행동 동일] `existing == null` 분기에서 돌려주는 값이 null로 같다 |
| keylocallock.coroutine/RET-10 | 동등 | — | 아니오 | [행동 동일] `lockInfo == null` 분기에서 돌려주는 값이 null로 같다 |
| keylocallock.core/RET-02 | 동등 | — | 아니오 | [행동 동일] else 분기에서 `existing`은 null이므로 compute가 받는 값이 원본과 같다 |
| keylocallock.core/RET-05 | 동등 | — | 아니오 | [행동 동일] `existing == null` 분기에서 돌려주는 값이 null로 같다 |
| keylocallock.core/RET-10 | 동등 | — | 아니오 | [행동 동일] `lockInfo == null` 분기에서 돌려주는 값이 null로 같다 |
| keylocallock.reactor/RET-02 | 동등 | — | 아니오 | [행동 동일] else 분기에서 `existing`은 null이므로 compute가 받는 값이 원본과 같다 |
| keylocallock.reactor/RET-04 | 동등 | — | 아니오 | [행동 동일] `existing == null` 분기에서 돌려주는 값이 null로 같다 |
| keylocallock.reactor/RET-07 | 동등 | — | 아니오 | [행동 동일] `lockInfo == null` 분기에서 돌려주는 값이 null로 같다 |
| keylocallock.core/BOOL-09 | 동등 | — | 아니오 | [행동 동일] CAS가 항상 실패하지만, 차이는 같은 compute가 map에서 지우는 LockInfo에만 남는다 |
| keylocallock.core/BOOL-10 | 동등 | — | 아니오 | [행동 동일] isHeld가 true로 남는 LockInfo를 같은 compute가 map에서 지운다 |
| keylocallock.core/MOP-01 | 동등 | — | 아니오 | [행동 바뀜] tryLock이 만료 entry를 인수하지 못한다. 인수를 요구하는 sidecar 성질이 없다 (G_expired FAILED). README·CLAUDE.md는 인수 시점을 문서화하지 않는다 |
| keylocallock.core/RACY-02 | 동등 | — | 아니오 | [행동 바뀜] 드문 경쟁에서만 만료 인수를 건너뛴다. 인수를 요구하는 sidecar 성질이 없다 (G_expired FAILED) |
| keylocallock.core/RACY-04 | 동등 | — | 예 | [행동 바뀜] 경쟁 읽기가 key를 못 보면 상한 검사를 건너뛰어 map이 상한을 넘는다 (G_cap FAILED) |
| keylocallock.core/SDL-02 | 동등 | — | 아니오 | [행동 동일] 지운 `token = null`은 같은 compute가 새 토큰으로 덮어쓴다 |
| keylocallock.core/SDL-14 | 동등 | — | 아니오 | [행동 동일] release를 빼도 그 LockInfo는 같은 compute가 map에서 지운다 |
| keylocallock.coroutine/BOOL-02 | 동등 | — | 아니오 | [행동 동일] CAS가 남긴 isHeld=true는 같은 compute의 `isHeld.set(true)`와 같은 값이다 |
| keylocallock.coroutine/BOOL-10 | 동등 | — | 아니오 | [행동 동일] isHeld가 true로 남는 LockInfo를 같은 compute가 map에서 지운다 |
| keylocallock.coroutine/MOP-01 | 동등 | — | 아니오 | [행동 바뀜] tryLock이 만료 entry를 인수하지 못한다. 인수를 요구하는 sidecar 성질이 없다 (G_expired FAILED). README·CLAUDE.md는 인수 시점을 문서화하지 않는다 |
| keylocallock.coroutine/MOP-05 | 동등 | — | 아니오 | [행동 동일] CAS가 항상 실패하지만, 차이는 같은 compute가 map에서 지우는 LockInfo에만 남는다 |
| keylocallock.coroutine/NEG-10 | 동등 | — | 아니오 | [행동 동일] then 블록이 죽지만, 차이는 같은 compute가 map에서 지우는 LockInfo에만 남는다 |
| keylocallock.coroutine/RACY-04 | 동등 | — | 예 | [행동 바뀜] 경쟁 읽기가 key를 못 보면 상한 검사를 건너뛰어 map이 상한을 넘는다 (G_cap FAILED) |
| keylocallock.coroutine/RACY-09 | 동등 | — | 아니오 | [행동 바뀜] 드문 경쟁에서 monitor가 만료 entry를 한 주기 더 남긴다. sweep에는 제거 의무 성질이 없다 (G_sweep FAILED) |
| keylocallock.coroutine/RACY-10 | 동등 | — | 아니오 | [행동 동일] 경쟁 읽기 값과 무관하게 entry는 지워지고, 차이는 지워지는 LockInfo에만 남는다 |
| keylocallock.coroutine/SDL-08 | 동등 | — | 예 | [행동 바뀜] 상한에서 토큰 대신 null을 돌려준다. `rej`일 때 토큰을 요구하는 성질이 없다 (G_cap FAILED) |
| keylocallock.reactor/BOOL-07 | 동등 | — | 아니오 | [행동 동일] isHeld가 true로 남는 LockInfo를 unLock의 같은 compute가 map에서 지운다 |
| keylocallock.reactor/RACY-04 | 동등 | — | 예 | [행동 바뀜] 경쟁 읽기가 key를 못 보면 상한 검사를 건너뛰어 map이 상한을 넘는다 (G_cap FAILED) |
| keylocallock.reactor/RACY-08 | 동등 | — | 아니오 | [행동 바뀜] 드문 경쟁에서 monitor가 만료 entry를 한 주기 더 남긴다. sweep에는 제거 의무 성질이 없다 (G_sweep FAILED) |
| keylocallock.reactor/SDL-03 | 동등 | — | 아니오 | [행동 동일] 지운 `token = null`은 같은 compute가 새 토큰으로 덮어쓴다 |
| keylocallock.reactor/SDL-17 | 동등 | — | 아니오 | [행동 동일] release를 빼도 그 LockInfo는 같은 compute가 map에서 지운다 |

## 2. 공통 근거

### 2.1 모든 변이에 쓰는 사실

1. **지워진 LockInfo는 아무도 읽지 못한다.**
   - 세 변형 모두 `LockInfo` 객체를 tryLock의 새 entry 분기에서만 만든다 (core 206행, coroutine 188행, reactor 203행).
   - `lockMap`은 companion의 private 필드다. compute 람다 밖에서는 `keys`(coroutine 96행, core 108행, reactor 104행)와 `size`(core 102, 126행)로만 읽는다. 람다 안에서는 `mappingCount()`만 부른다. RACY 변이는 람다 밖에 `containsKey`를 더한다. map 값을 꺼내는 호출(`get`, `values`, `entries`)은 없다.
   - 그래서 LockInfo 참조는 map 값과 compute 람다의 파라미터에만 있다. 같은 compute 호출이 map에서 지운 LockInfo는 그 뒤 어느 스레드도 읽지 못한다.
2. **compute 안의 중간 상태는 보이지 않는다.** `ConcurrentHashMap.compute`의 Javadoc은 "The entire method invocation is performed atomically."라고 쓴다 (Corretto 21.0.10 `ConcurrentHashMap.java` 1878행). 같은 key의 다른 compute는 기다리고, LockInfo 필드는 compute 밖에서 읽히지 않는다 (1번).
3. **map entry는 항상 잡혀 있다.** hole의 `MI_INV_1`(INV-E)은 "모든 entry가 `permits == 0 && isHeld && token != NULL`"이다. Silicon은 원본과 30개 변이 모두에서 이 불변식의 귀납성을 증명했다. 따라서 map entry에 대한 `compareAndSet(true, …)`는 항상 성공하고, `compareAndSet(false, …)`는 항상 실패한다. 도구의 분기 도달성 INFO도 같다: 원본에서 도달할 수 없는 분기는 세 CAS 실패 분기뿐이다 (예: core `tryLock 179:49`, `unLock 232:44`, `sweep 116:37` "compareAndSet fails").
4. **경쟁 읽기는 임의 값이다.** DESIGN-v0.md 2.12: "자유 값은 그 타입의 임의 값이다." RACY 변이의 `racy_N`은 모델에서 초기화하지 않은 `Bool`이다. 그래서 PROVEN과 E_op 결과는 경쟁 읽기가 돌려줄 수 있는 모든 값을 덮는다.
5. **누가 ghost를 바꾸나.** END는 "사전 entry의 토큰을 사후 entry가 더 이상 갖지 않으면 종료"이고, ISSUE는 "null 아닌 결과를 발급"이다 (DESIGN-v0.md 3.5). 둘 다 map과 결과만 읽고, 지워진 LockInfo의 필드는 읽지 않는다.

### 2.2 probe 결과 (모델 33개)

| 모델 | 재실행 | E_op | G_expired | G_cap | G_sweep |
|---|---|---|---|---|---|
| core/original | (원본) | PROVEN | PROVEN | PROVEN | PROVEN |
| core/BOOL-09 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| core/BOOL-10 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| core/MOP-01 | PROVEN | FAILED | FAILED | PROVEN | PROVEN |
| core/RACY-02 | PROVEN | FAILED | FAILED | PROVEN | PROVEN |
| core/RACY-04 | PROVEN | FAILED | PROVEN | FAILED | PROVEN |
| core/RET-02 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| core/RET-05 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| core/RET-10 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| core/SDL-02 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| core/SDL-14 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| coroutine/original | (원본) | PROVEN | PROVEN | PROVEN | PROVEN |
| coroutine/BOOL-02 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| coroutine/BOOL-10 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| coroutine/MOP-01 | PROVEN | FAILED | FAILED | PROVEN | PROVEN |
| coroutine/MOP-05 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| coroutine/NEG-10 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| coroutine/RACY-04 | PROVEN | FAILED | PROVEN | FAILED | PROVEN |
| coroutine/RACY-09 | PROVEN | FAILED | PROVEN | PROVEN | FAILED |
| coroutine/RACY-10 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| coroutine/RET-02 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| coroutine/RET-05 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| coroutine/RET-10 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| coroutine/SDL-08 | PROVEN | FAILED | PROVEN | FAILED | PROVEN |
| reactor/original | (원본) | PROVEN | PROVEN | PROVEN | PROVEN |
| reactor/BOOL-07 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| reactor/RACY-04 | PROVEN | FAILED | PROVEN | FAILED | PROVEN |
| reactor/RACY-08 | PROVEN | FAILED | PROVEN | PROVEN | FAILED |
| reactor/RET-02 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| reactor/RET-04 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| reactor/RET-07 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| reactor/SDL-03 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |
| reactor/SDL-17 | PROVEN | PROVEN | PROVEN | PROVEN | PROVEN |

E_op가 FAILED인 9개에서 Silicon이 가리킨 절 (출력 `out/equiv/*.out`):

| 변이 | 실패한 E_op 절 |
|---|---|
| core/MOP-01, coroutine/MOP-01, core/RACY-02 | tryLock: `old(has(S, k)) && now > old(S.m[k].expiresAt) ==> res == old(S.seq) + 1 && …` (만료 인수) |
| core/RACY-04, coroutine/RACY-04, reactor/RACY-04, coroutine/SDL-08 | tryLock: `!old(has(S, k)) && rej ==> res == old(S.seq) + 1 && S.seq == old(S.seq) + 1 && !has(S, k)` (상한) |
| coroutine/RACY-09, reactor/RACY-08 | sweep: `old(has(S, k)) && now > old(S.m[k].expiresAt) ==> !has(S, k)` (만료 제거) |

### 2.3 부류 A — RET 9개: null인 값 파라미터를 돌려준다

대상: core·coroutine·reactor의 RET-02 (tryLock), core·coroutine RET-05와 reactor RET-04 (unLock), core·coroutine RET-10과 reactor RET-07 (sweep).

Kotlin 의미:
1. 바뀐 `return@compute`는 값 파라미터가 null인 분기에만 있다. tryLock은 `if (existing != null)`의 else 분기이고, unLock은 `if (existing == null)`, sweep은 `if (lockInfo == null)`이다.
2. 그래서 변이도 원본처럼 null을 돌려준다. compute 구현은 함수가 돌려준 값 `val`이 null인지만 본다. key가 없을 때 `val`이 null이면 아무것도 넣지 않는다 (`ConcurrentHashMap.java` 1916행 빈 bin, 1955-1956행 list bin, 1975-1976행 tree bin). 리터럴 `null`과 null인 변수를 구별하는 경로는 없다.
3. `java.util.Map.compute`의 계약: "If the remapping function returns {@code null}, the mapping is removed" "(or remains absent if initially absent)." (`Map.java` 1165-1166행). 두 프로그램 모두 key가 없는 상태로 끝난다.

모델 변화와 무해함:
1. DESIGN-v0.md 4.3: "result  null -> remove k; value parameter -> keep". 그래서 원본 모델은 `S.m := S.m[k := null]`을 쓰고, 변이 모델은 map을 쓰지 않는다.
2. 두 경우 모두 사전 entry가 null이다 (`existing := has(S, k) ? S.m[k] : null`). 그래서 사후 `has(S, k)`는 둘 다 false다.
3. 모든 성질과 MI는 map을 `has(S, ·)`로, 또는 `has` 아래에서만 읽는다. 따라서 어떤 성질도 두 모델을 구별하지 못한다. E_op PROVEN이 이것을 확인한다.

깰 수 있는 성질과 이유:
- tryLock (RET-02): 이 분기는 `rej`가 참인 상한 분기다. `res`는 새 토큰이고, ISSUE는 `gBacked := carried(S, k, res)` = false를 쓴다 (entry 없음). P3b는 `!old(has(S, k)) && rej && limit != 0`으로 원본과 같이 성립한다. P1a, P1a_entry(`gBacked`가 false라 공허), progress_free(`!rej`만 다룸), progress_live(`old(has)`만 다룸), P3a(`pre_tok == NULL`이라 END 없음)는 원본과 같은 이유로 성립한다.
- unLock (RET-05, RET-04): `res`는 false다. P1은 공허하다. progress_owner는 `old(has(S, k))`가 거짓이라 공허하다. P3a는 END가 없어 공허하다.
- sweep (RET-10, RET-07): `pre_tok == NULL`이라 END가 없다. P3a는 공허하다.
- MI_PERMS, MI_OWN, P2, MI_INV_1-5, HIST: 상태 효과가 원본과 같다 (`has`로 본 map, counter, ghost 갱신이 같다).

### 2.4 부류 B — 같은 compute가 지우는 LockInfo의 필드만 바뀐다 (9개)

대상: core BOOL-09, core BOOL-10, core SDL-14, coroutine BOOL-10, coroutine MOP-05, coroutine NEG-10, coroutine RACY-10, reactor BOOL-07, reactor SDL-17.

Kotlin 의미:
1. 바뀐 문장은 sweep의 만료 분기나 unLock의 해제 분기에만 있다. 두 분기는 모두 같은 compute 안에서 `null`을 돌려 그 entry를 지운다 (sweep의 `null // Atomic removal`, unLock의 `return@compute null`).
2. 바뀌는 것은 지워지는 LockInfo의 `isHeld`, `token`, `permits` 값뿐이다.
3. 2.1절 1번에 따라 그 LockInfo는 그 뒤 아무도 읽지 못한다. 그래서 프로그램의 관측 행동은 원본과 같다.

깰 수 있는 성질과 이유:
- P2 (`S.created`의 모든 객체에서 `0 <= permits <= 1`): 지워진 객체도 범위에 든다. 변이에서 그 객체의 `permits`는 0으로 남거나(release 생략) 1이 된다(release 유지). 둘 다 범위 안이다.
- END와 P3a: entry가 지워지므로 END가 원본과 같이 `pre_tok`을 종료한다 (2.1절 5번).
  - sweep: 지워지는 entry는 만료 분기에 있다. `MI_INV_4`에 따라 `gLease == expiresAt < now`이므로 sweep P3a가 성립한다.
  - unLock: 해제 분기는 `existing.token == token`이 참일 때만 온다. 끝나는 토큰은 `t`이고 `res`는 true다. unLock P3a, P1이 성립한다.
- progress_owner (unLock 변이만): 토큰이 맞으면 해제 분기에 들어가야 한다. core SDL-14는 CAS를 바꾸지 않았다. reactor BOOL-07의 `compareAndSet(true, true)`는 `isHeld`가 true이므로 성공한다 (2.1절 3번). 둘 다 `res = true`다.
- MI_OWN, MI_INV_1-5: map entry만 다룬다. 지워진 객체는 범위 밖이다.
- HIST: ghost 갱신이 원본과 같다.

### 2.5 부류 C — 같은 compute가 뒤에서 덮어쓰는 쓰기 (3개)

대상: core SDL-02, reactor SDL-03 (`existing.token = null` 삭제), coroutine BOOL-02 (`compareAndSet(true, false)` → `compareAndSet(true, true)`). 셋 다 tryLock의 만료 인수 블록에 있다.

Kotlin 의미:
1. 만료 인수 블록은 `now > existing.expiresAt`이고 CAS가 성공할 때 실행된다. 2.1절 3번에 따라 entry는 `permits == 0`이다.
2. 블록의 `semaphore.release()`가 `permits`를 1로 올린다. 그래서 바로 뒤 `existing.semaphore.tryAcquire()`는 항상 성공한다.
3. 성공 블록은 `existing.isHeld.set(true)`와 `existing.token = token`(새 토큰)을 쓴다. 따라서 지운 `token = null`이나 CAS가 남긴 `isHeld = true`는 같은 compute 안에서 같은 최종 값으로 덮인다.
4. compute 안의 중간 값은 다른 스레드가 보지 못한다 (2.1절 2번).

깰 수 있는 성질과 이유: tryLock의 모든 성질(P1a, P1a_entry, P3a, P3b, progress_free, progress_live)과 MI, HIST. 최종 상태와 결과가 원본과 같으므로 모두 원본과 같은 이유로 성립한다. E_op의 만료 절(`S.m[k].permits == 0 && S.m[k].isHeld && S.m[k].token == res && S.m[k].expiresAt == now + timeout`)이 세 변이에서 PROVEN이다.

### 2.6 부류 D — 행동이 바뀌지만 sidecar 성질은 모두 지킨다 (9개)

이 9개는 E_op가 FAILED다. 즉 모델에서도 행동이 원본과 다르다. 그러나 sidecar 성질은 모두 성립한다 (재실행 PROVEN).
tryLock의 진행 성질은 두 개뿐이다: progress_free (`!old(has(S, k)) && !rej`)와 progress_live (`old(has(S, k)) && now <= old(S.m[k].expiresAt)`). unLock에는 progress_owner가 있고, sweep에는 진행 성질이 없다. 아래 네 하위 부류가 바꾸는 행동은 어느 성질도 다루지 않는다.

| 하위 부류 | 변이 | 바뀐 행동 | 이를 막는 sidecar 성질 | 원본은 지키고 변이는 깨는 probe |
|---|---|---|---|---|
| D1 만료 인수 | core/MOP-01, coroutine/MOP-01, core/RACY-02 | tryLock이 만료 entry를 인수하지 못한다 | 없음. tryLock P3a는 "만료된 것만 끝낸다"는 허용 조건이다 | G_expired |
| D2 상한 우회 | core/RACY-04, coroutine/RACY-04, reactor/RACY-04 | 상한에서도 새 key에 entry를 만든다 | 없음. P3b는 "unbacked는 상한에서만"이라는 허용 조건이다 | G_cap |
| D3 상한에서 null | coroutine/SDL-08 | 상한에서 토큰 대신 null을 준다 | 없음. progress_free는 `!rej`만 다룬다 | G_cap |
| D4 monitor 지연 | coroutine/RACY-09, reactor/RACY-08 | monitor가 만료 entry를 남긴다 | 없음. sweep에는 P3a(허용 조건)만 있다 | G_sweep |

DESIGN-v0.md 3.5는 P3a, P3b를 허용 조건으로 정의한다: "`unbacked_only_if C`: 이 연산이 발급한 토큰이 unbacked이면 `C`여야 한다 (P3b)." 그래서 "상한이면 unbacked여야 한다"나 "만료면 인수해야 한다"는 방향은 어느 절에도 없다.

## 3. 변이별 근거

각 절의 "Silicon" 줄은 2.2절 표의 해당 행이다. "재실행"은 생성 모델 그대로의 결과다.

### 3.1 keylocallock.coroutine/RET-02 — 동등 [행동 동일]

- 연산·위치: tryLock, `KeyLocalLock.kt:183:36-183:40` (상한 분기, `existing == null`인 else 분기 안).
- 변이: `return@compute null` → `return@compute existing`.
- 모델 변화: 상한 분기의 `S.m := S.m[k := null]` 1줄이 사라진다.
- 근거: 부류 A (2.3절). else 분기라 `existing`은 null이다. compute는 두 경우 모두 null을 받는다.
- 깰 수 있는 성질: P3b, P1a, P1a_entry, progress_free, progress_live, P3a, MI_*, HIST. 2.3절의 tryLock 항목대로 모두 성립한다.
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음 (람다가 compute에 돌려주는 값이 같다).

### 3.2 keylocallock.coroutine/RET-05 — 동등 [행동 동일]

- 연산·위치: unLock, `KeyLocalLock.kt:210:50-210:54`.
- 변이: `if (existing == null) return@compute null` → `if (existing == null) return@compute existing`.
- 모델 변화: `existing == null` 분기의 `S.m := S.m[k := null]`이 사라진다.
- 근거: 부류 A. 분기 조건이 `existing == null`이다.
- 깰 수 있는 성질: P1, P3a(unLock), progress_owner, MI_*, HIST. 이 분기에서 `res`는 false이고 `old(has(S, k))`는 거짓이므로 P1, progress_owner, P3a가 공허하다.
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.3 keylocallock.coroutine/RET-10 — 동등 [행동 동일]

- 연산·위치: sweep, `KeyLocalLock.kt:98:78-98:82`.
- 변이: `if (lockInfo == null) return@compute null` → `if (lockInfo == null) return@compute lockInfo`.
- 모델 변화: `lockInfo == null` 분기의 `S.m := S.m[k := null]`이 사라진다.
- 근거: 부류 A. 분기 조건이 `lockInfo == null`이다.
- 깰 수 있는 성질: P3a(sweep), MI_*, HIST. `pre_tok == NULL`이라 END가 없고, 상태가 바뀌지 않는다.
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.4 keylocallock.core/RET-02 — 동등 [행동 동일]

- 연산·위치: tryLock, `KeyLocalLock.kt:201:36-201:40` (상한 분기).
- 변이: `return@compute null` → `return@compute existing`.
- 모델 변화: 상한 분기의 `S.m := S.m[k := null]`이 사라진다 (원본 모델 168행).
- 근거와 성질: 3.1절과 같다 (부류 A).
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.5 keylocallock.core/RET-05 — 동등 [행동 동일]

- 연산·위치: unLock, `KeyLocalLock.kt:230:50-230:54`.
- 변이: `return@compute null` → `return@compute existing` (`existing == null` 분기).
- 모델 변화: `existing == null` 분기의 `S.m := S.m[k := null]`이 사라진다.
- 근거와 성질: 3.2절과 같다 (부류 A).
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.6 keylocallock.core/RET-10 — 동등 [행동 동일]

- 연산·위치: sweep, `KeyLocalLock.kt:110:66-110:70`.
- 변이: `return@compute null` → `return@compute lockInfo` (`lockInfo == null` 분기).
- 모델 변화: `lockInfo == null` 분기의 `S.m := S.m[k := null]`이 사라진다.
- 근거와 성질: 3.3절과 같다 (부류 A).
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.7 keylocallock.reactor/RET-02 — 동등 [행동 동일]

- 연산·위치: tryLock, `KeyLocalLock.kt:198:40-198:44` (`Mono.fromCallable` 안, 상한 분기).
- 변이: `return@compute null` → `return@compute existing`.
- 모델 변화: 상한 분기의 `S.m := S.m[k := null]`이 사라진다.
- 근거와 성질: 3.1절과 같다 (부류 A). `fromCallable`은 이 분기와 무관하다.
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.8 keylocallock.reactor/RET-04 — 동등 [행동 동일]

- 연산·위치: unLock, `KeyLocalLock.kt:228:54-228:58`.
- 변이: `return@compute null` → `return@compute existing` (`existing == null` 분기).
- 모델 변화: `existing == null` 분기의 `S.m := S.m[k := null]`이 사라진다.
- 근거와 성질: 3.2절과 같다 (부류 A).
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.9 keylocallock.reactor/RET-07 — 동등 [행동 동일]

- 연산·위치: sweep, `KeyLocalLock.kt:106:82-106:86`.
- 변이: `return@compute null` → `return@compute lockInfo` (`lockInfo == null` 분기).
- 모델 변화: `lockInfo == null` 분기의 `S.m := S.m[k := null]`이 사라진다.
- 근거와 성질: 3.3절과 같다 (부류 A).
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.10 keylocallock.core/BOOL-09 — 동등 [행동 동일]

- 연산·위치: sweep, `KeyLocalLock.kt:116:67-116:71` (만료 분기의 CAS).
- 변이: `lockInfo.isHeld.compareAndSet(true, false)` → `lockInfo.isHeld.compareAndSet(false, false)`.
- 모델 변화: `_v2 := _v1.isHeld == true` → `_v2 := _v1.isHeld == false`.
- 실행 의미: entry의 `isHeld`는 항상 true다 (2.1절 3번). 그래서 CAS는 항상 실패하고 then 블록(`token = null`, `release()`)이 죽는다. 도구 INFO도 `sweep 116:37 compareAndSet succeeds`와 `116:33 if-then`을 도달 불가로 보고한다. entry는 그래도 `null`로 지워진다.
- 근거: 부류 B (2.4절). 원본과의 차이는 지워진 LockInfo가 `isHeld = true`, 옛 `token`, `permits = 0`을 갖는다는 것뿐이다.
- 깰 수 있는 성질: P3a(sweep), P2, MI_*, HIST. 2.4절대로 성립한다 (P2: 지워진 객체의 `permits`는 0).
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.11 keylocallock.core/BOOL-10 — 동등 [행동 동일]

- 연산·위치: sweep, `KeyLocalLock.kt:116:73-116:78`.
- 변이: `compareAndSet(true, false)` → `compareAndSet(true, true)`.
- 모델 변화: CAS 성공 시 `_v1.isHeld := false` → `_v1.isHeld := true`.
- 실행 의미: CAS는 성공한다. `isHeld`는 true로 남고, `token = null`과 `release()`는 실행된다. entry는 지워진다.
- 근거: 부류 B. 지워진 객체의 `isHeld`만 다르다.
- 깰 수 있는 성질: P3a(sweep), P2(`permits`는 0에서 1), MI_*, HIST. 2.4절대로 성립한다.
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.12 keylocallock.core/MOP-01 — 동등 [행동 바뀜, D1]

- 연산·위치: tryLock, `KeyLocalLock.kt:179:49-179:91` (만료 인수 조건).
- 변이: `existing.isHeld.compareAndSet(true, false)` → `existing.isHeld.compareAndSet(false, true)`.
- 모델 변화: `_v3 := _v1.isHeld == true; if (_v3) { _v1.isHeld := false }` → `_v3 := _v1.isHeld == false; if (_v3) { _v1.isHeld := true }`.
- 실행 의미:
  1. entry의 `isHeld`는 항상 true다. 그래서 CAS는 항상 실패하고 만료 인수 블록이 죽는다.
  2. `permits`가 0으로 남으므로 `tryAcquire()`도 항상 실패한다.
  3. 결과: entry가 있으면 만료 여부와 관계없이 tryLock은 아무것도 바꾸지 않고 `null`을 돌려준다.
  4. 도구 INFO가 이것을 직접 보여 준다: `tryLock 179:49 compareAndSet succeeds`, `179:17 if-then`, `186:21 tryAcquire succeeds`, `186:17 if-then`이 도달 불가다 (`verify/MOP-01.report`).
- 깰 수 있는 성질과 이유 (entry가 있는 경로만 바뀐다):
  - MI_PERMS, MI_OWN, P2, MI_INV_1-5: 그 경로는 상태를 바꾸지 않는다.
  - HIST: `pre_tok`이 계속 실려 있어 END가 없고, `res`가 NULL이라 ISSUE가 없다.
  - P3a(tryLock): 끝나는 토큰이 없다.
  - P1a, P1a_entry, P3b: `res`가 NULL이라 공허하다.
  - progress_live: `res == NULL`이다.
  - progress_free, set-once·템플릿 assert: entry가 없는 경로만 다루고, 그 경로는 바뀌지 않았다.
- 행동 차이 (단일 스레드 실행):
  1. `val lock = KeyLocalLock(200)`.
  2. 0 ms: `lock.tryLock("k", CREATE)` → `"local-1"`. entry의 `expiresAt`은 200이다. 보유자는 unLock을 부르지 않는다.
  3. 300 ms, monitor의 다음 sweep 전: `lock.tryLock("k", CREATE)`.
     - 원본: CAS 성공 → release → tryAcquire 성공 → `"local-2"`.
     - 변이: CAS 실패 → tryAcquire 실패 → `null`.
  4. 변이에서는 monitor가 그 entry를 지운 뒤(만료 뒤 최대 약 1 s)에야 다음 tryLock이 새 entry로 lock을 얻는다.
- Silicon: 재실행 PROVEN. E_op FAILED (만료 인수 절). G_expired FAILED. 원본의 G_expired는 PROVEN이다.
- spec gap: 아니오. README·CLAUDE.md는 만료 lock을 tryLock이 언제 넘겨받는지 적지 않는다.
  - README 105행 "holder whose lock already expired can no longer release the lock of the next holder."는 변이에서도 성립한다. 다음 보유자는 monitor가 entry를 지운 뒤 생긴다.
  - README 118-119행은 monitor가 만료 entry를 지운다고만 쓴다. 변이에서도 성립한다.
  - CLAUDE.md 97행 "`lockTimeoutMillis`: Lock acquisition timeout (default: 3000ms)"은 만료 뒤 언제 다시 획득되는지 말하지 않는다. README와 CLAUDE.md에서 만료를 다루는 문장은 이 세 곳뿐이다 (grep `expire`, `take over`, `timeout`).
  - 참고 (README·CLAUDE.md 밖): core `KeyLock.kt` 23행 KDoc "a holder whose lock already expired and was taken over by someone else"와 core 단위 테스트 `KeyLocalLockTest.kt` 151행 `"B should take over the expired lock"`은 즉시 인수를 문서화한다. 4절의 단위 테스트에서 이 테스트가 실패했다.
- 실제 영향: 보유자가 해제하지 않은 lock이 만료된 뒤 다음 monitor sweep까지(최대 약 1 s) 새 요청이 lock을 얻지 못한다. 그 요청은 cache를 최대 3 s(60 × 50 ms) polling한 뒤 supplier를 직접 부른다.

### 3.13 keylocallock.core/RACY-02 — 동등 [행동 바뀜, D1]

- 연산·위치: tryLock, `KeyLocalLock.kt:179:21-179:91`.
- 변이: compute 앞에 `val racy_2 = lockMap.containsKey(completeKey)`를 넣고, 조건을 `racy_2 && now > existing.expiresAt && existing.isHeld.compareAndSet(true, false)`로 바꾼다.
- 모델 변화: 이름 없는 자유 값 `var _v3: Bool`이 생기고, 만료 인수 조건이 `_v3 && now > _v1.expiresAt`가 된다.
- 실행 의미: `racy_2`가 참이면 원본과 같다. 거짓이면 MOP-01처럼 만료 인수를 건너뛰고 `null`을 돌려준다.
- 깰 수 있는 성질: 3.12절과 같은 성질. `racy_2`가 참인 경로는 원본 경로이고, 거짓인 경로는 상태를 바꾸지 않는 MOP-01 경로다. 둘 다 모든 성질을 지킨다.
- 행동 차이 (두 스레드, 드문 경쟁):
  1. `lockTimeoutMillis = 3000`. 스레드 B가 `tryLock("k")`에서 `nowB = 0`을 읽고 compute 전에 멈춘다 (GC나 스케줄링 지연).
  2. 5000 ms에 스레드 A가 `tryLock("k")`를 시작한다. `nowA = 5000`, `racy_2 = containsKey = false`.
  3. B가 깨어나 compute로 새 entry(`expiresAt = 3000`)를 넣고 토큰을 받는다.
  4. A가 compute를 실행한다. entry가 있고 `5000 > 3000`이다.
     - 원본: 만료 인수 → A가 새 토큰을 받는다 (B의 토큰은 끝난다. P3a는 `5000 > 3000`으로 성립).
     - 변이: `racy_2`가 거짓 → 인수 없음 → A는 `null`.
  - 이 차이에는 B의 멈춤이 `lockTimeoutMillis`보다 길거나 시계가 뛰는 경우가 필요하다. entry가 막 생겼다면 보통 만료가 아니므로 조건이 어차피 거짓이다.
- Silicon: 재실행 PROVEN. E_op FAILED (만료 인수 절). G_expired FAILED.
- spec gap: 아니오 (3.12절과 같은 이유. 게다가 드문 경쟁에서만 나타난다).
- 실제 영향: 사실상 없음. 위 경쟁에서만 A가 만료 lock을 즉시 넘겨받지 못한다.

### 3.14 keylocallock.core/RACY-04 — 동등 [행동 바뀜, D2]

- 연산·위치: tryLock, `KeyLocalLock.kt:199:21-199:75` (상한 검사).
- 변이: compute 앞에 `val racy_4 = lockMap.containsKey(completeKey)`를 넣고, 조건을 `racy_4 && LocalLockLimit.rejectsNewEntry(lockMap.mappingCount())`로 바꾼다.
- 모델 변화: 자유 값 `_v3: Bool`이 생기고, 상한 분기 조건이 `_v8 := false; if (_v3) { _v8 := rej }`가 된다.
- 실행 의미:
  1. 이 분기는 compute 시점에 key가 없을 때만 온다. 보통 그 직전의 `containsKey`도 key를 못 본다. 그래서 `racy_4`는 거의 항상 거짓이다.
  2. `racy_4`가 거짓이면 `rejectsNewEntry`를 부르지 않고 새 entry를 만든다. 상한이 꺼진다.
  3. `racy_4`가 참인 경우(읽은 뒤 compute 전에 다른 스레드가 entry를 지운 경우)에만 원본처럼 상한을 검사한다.
- 깰 수 있는 성질과 이유:
  - P3b: unbacked 토큰은 `racy_4 && rej` 경로에서만 나온다. 그러면 `rej`가 참이고, 신뢰 계약 `rej ==> limit != 0`이 `limit != 0`을 준다. `!old(has(S, k)) && rej && limit != 0`이 성립한다.
  - progress_free: `!rej`이면 `_v8`이 거짓이라 새 entry를 만든다. `res != NULL && tok(res).gBacked`가 성립한다.
  - P1a, P1a_entry, set-once·템플릿 assert, MI_*, HIST: 두 분기의 효과가 원본의 두 분기와 같다.
  - P3a: entry가 없는 경로라 `pre_tok == NULL`, END가 없다.
- 행동 차이 (단일 스레드 실행):
  1. `LocalLockLimit.maxEntries = 1`, 빈 map.
  2. `tryLock("a", CREATE)` → 토큰, entry 1개.
  3. `tryLock("b", CREATE)`:
     - 원본: `rejectsNewEntry(1)`이 true → unbacked 토큰, entry 추가 없음 (map 크기 1).
     - 변이: `containsKey`가 false → 상한 검사 생략 → b의 entry 추가 (map 크기 2).
  4. 새 key마다 map이 계속 자란다. `rejectsNewEntry`를 부르지 않으므로 거부 WARN 로그도 없다.
- Silicon: 재실행 PROVEN. E_op FAILED (상한 절). G_cap FAILED. 원본의 G_cap은 PROVEN이다.
- spec gap: 예. 변이는 README의 상한 동작을 깬다.
  - README 122행: "The map is uncapped by default. To bound it, set `req-shield.lock.max-entries`; `0` (the default) means uncapped."
  - README 139행: "Once the map is full, a request for a **new** key is handed a permit that no map entry backs."
  - README 143-144행: "The cap is a soft one. The size check is an estimate and is not atomic with the insertion it guards, so concurrent" / "callers can push the map slightly past the configured number." 변이에서는 스레드 하나로도 map이 상한을 크게 넘는다.
  - README 145행: "The first refusal and every 1000th after it are logged at WARN." 변이에서는 거부가 거의 일어나지 않는다.
  - sidecar P3b의 검토 메모가 바로 이 README 문단을 인용한다 ("at the cap a new key is handed a permit that no map entry backs"). 그러나 P3b 식은 허용 방향("unbacked이면 상한")뿐이다.
- 실제 영향: `req-shield.lock.max-entries`가 사실상 무시된다. 새 key는 상한에서도 map entry를 만들고, 거부 WARN도 나오지 않는다.

### 3.15 keylocallock.core/SDL-02 — 동등 [행동 동일]

- 연산·위치: tryLock, `KeyLocalLock.kt:181:21-181:42` (만료 인수 블록).
- 변이: `existing.token = null` 삭제.
- 모델 변화: 만료 인수 블록의 `_v1.token := NULL`이 사라진다.
- 근거: 부류 C (2.5절). 같은 compute가 `existing.token = token`(189행)으로 덮어쓴다.
- 깰 수 있는 성질: tryLock의 모든 성질, MI, HIST. 최종 상태가 원본과 같다.
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.16 keylocallock.core/SDL-14 — 동등 [행동 동일]

- 연산·위치: unLock, `KeyLocalLock.kt:233:17-233:45` (해제 분기).
- 변이: `existing.semaphore.release()` 삭제.
- 모델 변화: 해제 분기의 `_v1.permits := _v1.permits + 1`이 사라진다.
- 근거: 부류 B (2.4절). 해제 분기는 `return@compute null`로 entry를 지운다. semaphore는 그 LockInfo의 일부이고, 아무도 그 semaphore를 다시 쓰지 않는다 (tryAcquire는 map entry에만 한다).
- 깰 수 있는 성질: P1, P3a(unLock), progress_owner, P2(`permits`는 0으로 남음), MI_*, HIST. 2.4절대로 성립한다.
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.17 keylocallock.coroutine/BOOL-02 — 동등 [행동 동일]

- 연산·위치: tryLock, `KeyLocalLock.kt:161:85-161:90` (만료 인수 CAS의 두 번째 인자).
- 변이: `compareAndSet(true, false)` → `compareAndSet(true, true)`.
- 모델 변화: CAS 성공 시 `_v1.isHeld := false` → `_v1.isHeld := true`.
- 근거: 부류 C (2.5절). CAS는 원본처럼 성공하고, 블록은 원본처럼 실행된다. 차이는 중간의 `isHeld` 값뿐이고, 같은 compute의 `existing.isHeld.set(true)`(170행)가 같은 최종 값 true를 쓴다.
- 깰 수 있는 성질: tryLock의 모든 성질, MI, HIST. 최종 상태가 원본과 같다.
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.18 keylocallock.coroutine/BOOL-10 — 동등 [행동 동일]

- 연산·위치: sweep, `KeyLocalLock.kt:104:85-104:90`.
- 변이: `compareAndSet(true, false)` → `compareAndSet(true, true)`.
- 모델 변화: CAS 성공 시 `_v1.isHeld := false` → `_v1.isHeld := true`.
- 근거와 성질: 3.11절과 같다 (부류 B).
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.19 keylocallock.coroutine/MOP-01 — 동등 [행동 바뀜, D1]

- 연산·위치: tryLock, `KeyLocalLock.kt:161:49-161:91`.
- 변이: `existing.isHeld.compareAndSet(true, false)` → `existing.isHeld.compareAndSet(false, true)`.
- 모델 변화: 3.12절과 같다.
- 실행 의미와 성질: 3.12절과 같다. 도구 INFO: `tryLock 161:49 compareAndSet succeeds`, `161:17 if-then`, `168:21 tryAcquire succeeds`, `168:17 if-then`이 도달 불가다.
- 행동 차이: 3.12절의 실행과 같다 (`suspend fun`이지만 tryLock 본문에는 중단 지점이 없다).
- Silicon: 재실행 PROVEN. E_op FAILED (만료 인수 절). G_expired FAILED.
- spec gap: 아니오 (3.12절과 같은 이유). 참고: coroutine 단위 테스트 `KeyLocalLockTest.kt` 106행의 주석 "Let the lock time out so that the next caller can force-acquire it."과 477행 테스트 `an entry already in the map is reacquired even when the map is at its cap`이 즉시 인수를 검사한다 (4절 결과 참조).
- 실제 영향: 3.12절과 같다.

### 3.20 keylocallock.coroutine/MOP-05 — 동등 [행동 동일]

- 연산·위치: sweep, `KeyLocalLock.kt:104:49-104:91`.
- 변이: `compareAndSet(true, false)` → `compareAndSet(false, true)`.
- 모델 변화: `_v2 := _v1.isHeld == true` → `== false`, 성공 시 대입 `false` → `true`.
- 실행 의미: `isHeld`가 true이므로 CAS는 항상 실패하고 then 블록이 죽는다 (도구 INFO: `sweep 104:49 compareAndSet succeeds`, `104:45 if-then` 도달 불가). entry는 그래도 지워진다.
- 근거와 성질: 3.10절과 같다 (부류 B).
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.21 keylocallock.coroutine/NEG-10 — 동등 [행동 동일]

- 연산·위치: sweep, `KeyLocalLock.kt:104:49-104:91`.
- 변이: `if (lockInfo.isHeld.compareAndSet(true, false))` → `if (!(lockInfo.isHeld.compareAndSet(true, false)))`.
- 모델 변화: then 블록의 조건 `_v2` → `! _v2`.
- 실행 의미: CAS는 항상 성공하므로(`isHeld`가 true) 지워질 객체의 `isHeld`는 false가 된다. 조건 `!true`가 거짓이라 `token = null`과 `release()`는 실행되지 않는다 (도구 INFO: `sweep 104:51 compareAndSet fails`, `104:45 if-then` 도달 불가). entry는 지워진다.
- 근거와 성질: 부류 B. 지워진 객체는 `isHeld = false`, 옛 `token`, `permits = 0`을 갖는다. P2는 `permits = 0`으로 성립한다.
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.22 keylocallock.coroutine/RACY-04 — 동등 [행동 바뀜, D2]

- 연산·위치: tryLock, `KeyLocalLock.kt:181:21-181:75`.
- 변이: compute 앞(155행)에 `val racy_4 = lockMap.containsKey(completeKey)`, 조건 `racy_4 && LocalLockLimit.rejectsNewEntry(lockMap.mappingCount())`.
- 모델 변화, 실행 의미, 성질, 행동 차이: 3.14절과 같다.
- Silicon: 재실행 PROVEN. E_op FAILED (상한 절). G_cap FAILED.
- spec gap: 예 (3.14절의 README 122, 139, 143-145행).
- 실제 영향: 3.14절과 같다.

### 3.23 keylocallock.coroutine/RACY-09 — 동등 [행동 바뀜, D4]

- 연산·위치: sweep, `KeyLocalLock.kt:100:45-100:69` (만료 검사).
- 변이: `forEach` 람다 안 compute 앞에 `val racy_9 = lockMap.containsKey(key)`, 조건 `racy_9 && now > lockInfo.expiresAt`.
- 모델 변화: 자유 값 `_v2: Bool`, 만료 분기 조건 `_v2 && now > _v1.expiresAt`.
- 실행 의미: `racy_9`가 거짓이고 entry가 만료면, monitor는 그 entry를 지우지 않고 남긴다.
- 깰 수 있는 성질과 이유: sweep에는 P3a(허용 조건)만 있다. 남기는 경로는 상태와 ghost를 바꾸지 않으므로 P3a, MI_*, P2, HIST가 성립한다. 지우는 경로는 원본 경로다.
- 행동 차이 (드문 경쟁):
  1. `lockTimeoutMillis = 3000`. monitor pass가 `now = 10000`을 읽고 key 순회에서 `k`를 꺼낸다 (보유자 H의 entry).
  2. H의 unLock이 entry를 지운다. monitor의 `containsKey(k)`가 false를 읽는다.
  3. 스레드 T의 tryLock이 compute로 새 entry를 넣는다. T는 `nowT = 6990`을 읽은 뒤 멈춰 있었다. 그래서 `expiresAt = 9990`이다.
  4. monitor의 compute가 `10000 > 9990`을 본다.
     - 원본: CAS → 해제 → entry 삭제.
     - 변이: `racy_9`가 거짓 → entry를 남긴다. 다음 pass(약 1 s 뒤)에 지운다.
  - 이 차이에는 T의 멈춤이 `lockTimeoutMillis`보다 길거나 시계가 뛰는 경우가 필요하다.
- Silicon: 재실행 PROVEN. E_op FAILED (sweep 만료 절). G_sweep FAILED. 원본의 G_sweep은 PROVEN이다.
- spec gap: 아니오. README 118-119행 "dropped by the" / "cleanup monitor once `lockTimeoutMillis` has passed, which runs on a 1 s interval and so can lag by up to that much"는 보통 경우의 지연을 말한다. 위 경쟁에서 변이는 지연을 한 monitor 주기만큼 늘린다. 같은 경쟁에서는 원본도 T의 멈춤만큼 이미 늦는다. 그래서 문서화된 동작을 깬다고 보지 않는다. sidecar에 monitor의 제거 의무가 없다는 점은 5.4절에 따로 적는다.
- 실제 영향: 사실상 없음. 드문 경쟁에서 만료 entry가 monitor 한 주기(약 1 s) 더 남는다.

### 3.24 keylocallock.coroutine/RACY-10 — 동등 [행동 동일]

- 연산·위치: sweep, `KeyLocalLock.kt:104:49-104:91`.
- 변이: compute 앞에 `val racy_10 = lockMap.containsKey(key)`, CAS 조건 `racy_10 && lockInfo.isHeld.compareAndSet(true, false)`.
- 모델 변화: 자유 값 `_v2: Bool`. CAS를 `if (_v2)` 안으로 옮긴다.
- 실행 의미: `racy_10`이 거짓이면 CAS와 then 블록을 건너뛴다. 그래도 만료 분기는 `null`로 entry를 지운다.
- 근거와 성질: 부류 B. 차이는 지워진 객체의 필드(`isHeld`, `token`, `permits`)뿐이다. E_op는 `racy_10`의 모든 값에서 PROVEN이다 (2.1절 4번).
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.25 keylocallock.coroutine/SDL-08 — 동등 [행동 바뀜, D3]

- 연산·위치: tryLock, `KeyLocalLock.kt:182:21-182:51` (상한 분기).
- 변이: `acquiredToken.set(nextToken())` 삭제.
- 모델 변화: 상한 분기의 counter 증가, 템플릿 assert, `acquiredToken__151 := n_80_52b`가 사라진다. 분기에는 `S.m := S.m[k := null]`만 남는다.
- 실행 의미: 상한(`rejectsNewEntry`가 true)에서 새 key의 tryLock은 `null`을 돌려준다. 다른 보유자가 없는데도 "lock을 얻지 못함"으로 보인다.
- 깰 수 있는 성질과 이유:
  - P3b, P1a, P1a_entry: `res`가 NULL이라 공허하다.
  - progress_free: `!rej`만 다룬다. 그 경로는 바뀌지 않았다.
  - MI_INV_3 (발급 토큰 ≤ counter): 발급이 없고 counter도 그대로다.
  - 나머지 MI, HIST, P3a: 상태 효과가 원본 상한 분기와 같다 (entry 없음).
- 행동 차이 (단일 스레드 실행):
  1. `LocalLockLimit.maxEntries = 1`, `tryLock("a", CREATE)` → 토큰, entry 1개.
  2. `tryLock("b", CREATE)`:
     - 원본: unbacked 토큰 `"local-2"`.
     - 변이: `null`.
- Silicon: 재실행 PROVEN. E_op FAILED (상한 절). G_cap FAILED.
- spec gap: 예.
  - README 139행: "Once the map is full, a request for a **new** key is handed a permit that no map entry backs."
  - README 141행: "for that key, and nothing else: no added latency, and the cache still gets populated."
  - 변이에서는 그 요청이 permit을 받지 못한다. ReqShield는 `null`을 "다른 보유자가 있음"으로 보고 cache를 polling한 뒤 supplier만 부르고 cache를 쓰지 않는다 (coroutine `ReqShield.handleLockFailure`). 지연이 늘고 cache가 채워지지 않는다.
- 실제 영향: map이 상한에 닿으면 새 key의 요청이 최대 3 s(기본 60 × 50 ms) 기다린 뒤 supplier를 부르고, cache를 채우지 않는다. `LocalLockLimit.kt` KDoc(36-37행)이 바로 이 결과를 피하려고 unbacked permit을 준다고 설명한다: "and then return without ever populating the cache."

### 3.26 keylocallock.reactor/BOOL-07 — 동등 [행동 동일]

- 연산·위치: unLock, `KeyLocalLock.kt:230:84-230:89`.
- 변이: `existing.isHeld.compareAndSet(true, false)` → `existing.isHeld.compareAndSet(true, true)`.
- 모델 변화: CAS 성공 시 `_v1.isHeld := false` → `_v1.isHeld := true`.
- 실행 의미: 토큰이 맞으면 CAS는 원본처럼 성공한다. release, `released.set(true)`, entry 삭제가 원본과 같다. 지워진 객체의 `isHeld`만 true로 남는다.
- 근거와 성질: 부류 B (2.4절). P1, P3a(unLock), progress_owner(`isHeld`가 true라 CAS 성공 → `res = true`), P2(`permits` 0에서 1), MI_*, HIST가 성립한다.
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.27 keylocallock.reactor/RACY-04 — 동등 [행동 바뀜, D2]

- 연산·위치: tryLock, `KeyLocalLock.kt:196:25-196:79` (`Mono.fromCallable` 안).
- 변이: compute 앞(170행)에 `val racy_4 = lockMap.containsKey(completeKey)`, 조건 `racy_4 && LocalLockLimit.rejectsNewEntry(lockMap.mappingCount())`.
- 모델 변화, 실행 의미, 성질, 행동 차이: 3.14절과 같다. `fromCallable` 람다 안의 경쟁 읽기도 자유 값이다.
- Silicon: 재실행 PROVEN. E_op FAILED (상한 절). G_cap FAILED.
- spec gap: 예 (3.14절의 README 122, 139, 143-145행).
- 실제 영향: 3.14절과 같다.

### 3.28 keylocallock.reactor/RACY-08 — 동등 [행동 바뀜, D4]

- 연산·위치: sweep, `KeyLocalLock.kt:108:49-108:73`.
- 변이: compute 앞(105행)에 `val racy_8 = lockMap.containsKey(key)`, 조건 `racy_8 && now > lockInfo.expiresAt`.
- 모델 변화, 실행 의미, 성질, 행동 차이: 3.23절과 같다.
- Silicon: 재실행 PROVEN. E_op FAILED (sweep 만료 절). G_sweep FAILED.
- spec gap: 아니오 (3.23절과 같은 이유).
- 실제 영향: 3.23절과 같다.

### 3.29 keylocallock.reactor/SDL-03 — 동등 [행동 동일]

- 연산·위치: tryLock, `KeyLocalLock.kt:179:25-179:46` (만료 인수 블록, release 뒤).
- 변이: `existing.token = null` 삭제.
- 모델 변화: 만료 인수 블록의 `_v1.token := NULL`이 사라진다.
- 근거: 부류 C. 같은 compute가 `existing.token = token`(187행)으로 덮어쓴다.
- 깰 수 있는 성질: tryLock의 모든 성질, MI, HIST. 최종 상태가 원본과 같다.
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

### 3.30 keylocallock.reactor/SDL-17 — 동등 [행동 동일]

- 연산·위치: sweep, `KeyLocalLock.kt:113:53-113:81` (만료 분기의 CAS 블록).
- 변이: `lockInfo.semaphore.release()` 삭제. CAS는 남고 블록이 빈다.
- 모델 변화: `_v1.permits := _v1.permits + 1`이 사라진다.
- 근거와 성질: 부류 B. reactor sweep은 CAS 블록 뒤에서 `lockInfo.token = null`을 쓰고 entry를 지운다 (115-116행). 지워진 객체의 `permits`만 0으로 남는다. P2는 0으로 성립한다.
- Silicon: 재실행 PROVEN, E_op PROVEN, G_* 모두 PROVEN.
- spec gap: 아니오.
- 실제 영향: 없음.

## 4. 보조 근거: req-shield 단위 테스트

이 절은 판정의 근거가 아니다. sidecar 성질과 별개로, 변이가 실제 Kotlin에서 행동을 바꾸는지 req-shield 자체 테스트로 확인했다.

방법:
1. `git archive 2e792d2`로 req-shield 소스를 임시 디렉터리에 풀었다. req-shield 작업 트리는 바꾸지 않았다.
2. 변이마다 사본을 만들고, `roots/<id>/`의 변이 `KeyLocalLock.kt` 하나만 바꿔 넣었다.
3. 사본에서 `./gradlew --offline -q :<모듈>:test --tests <그 모듈의 KeyLocalLockTest>`를 한 번 돌렸다.
4. 실제 실행은 한 번 푼 사본을 변이마다 복사해 썼다. 저장한 스크립트 `mutation/judge-work/A/unittest/run_tests.sh`는 같은 일을 변이마다 `git archive`로 한다. 이 스크립트로 reactor/RACY-04를 다시 돌려 같은 결과(12개 중 1개 실패, 같은 메시지)를 얻었다.
5. JUnit XML 33개와 요약은 같은 폴더의 `results/`, `test-summary.txt`에 있다.

결과:

| 대상 | 결과 | 실패한 테스트 (실패 메시지) |
|---|---|---|
| 원본 core / coroutine / reactor | 20/20, 14/14, 12/12 통과 | — |
| 부류 A·B·C 21개 (행동 동일) | 21개 모두 통과 | — |
| core/MOP-01 | 20개 중 1개 실패 | `should not let an expired holder release the lock of the new holder` ("B should take over the expired lock") |
| coroutine/MOP-01 | 14개 중 2개 실패 | `should not let a stale token release the lock of the next owner` ("actual value is null"), `an entry already in the map is reacquired even when the map is at its cap` ("an entry already in the map must not be subject to the cap") |
| core/RACY-04, coroutine/RACY-04, reactor/RACY-04 | 각 1개 실패 | `at the cap tryLock still grants a permit but stops adding map entries` ("a permit handed out past the cap must not add a map entry", 기대 false, 실제 true) |
| coroutine/SDL-08 | 14개 중 1개 실패 | 같은 테스트 ("past the cap the caller must still get a permit, so it writes the cache instead of waiting for a holder that does not exist") |
| core/RACY-02, coroutine/RACY-09, reactor/RACY-08 | 모두 통과 | — |

해석:
1. 테스트 결과는 2.2절의 E_op 결과와 맞는다. E_op가 PROVEN인 21개는 모두 통과했다. E_op가 FAILED이고 경쟁 없이 드러나는 6개(MOP-01 ×2, RACY-04 ×3, SDL-08)는 모두 실패했다.
2. 드문 경쟁에서만 드러나는 3개(core/RACY-02, coroutine/RACY-09, reactor/RACY-08)는 통과했다. 테스트가 그 경쟁을 만들지 않기 때문이다. 3.13절과 3.23절의 판단과 맞는다.
3. 통과는 동등의 증명이 아니다. 동등의 근거는 2절과 3절의 논증과 Silicon 결과다.
4. core/MOP-01이 실패한 테스트는 lock 만료 100 ms 뒤에 다시 획득한다. monitor(1 s 주기)가 그 100 ms 안에 entry를 지우면 이 테스트는 통과할 수 있다. 각 대상은 한 번만 돌렸다.

## 5. 집계

### 5.1 판정별 개수

| 판정 | 개수 | 내역 |
|---|---|---|
| 놓침 | 0 | — |
| 동등 | 30 | 행동 동일 21 (부류 A 9, B 9, C 3), 행동 바뀜 9 (D1 3, D2 3, D3 1, D4 2) |
| 미결 | 0 | — |

- spec gap "예": 4개 (core/RACY-04, coroutine/RACY-04, reactor/RACY-04, coroutine/SDL-08).
- 이 30개는 "PROVEN, 모델 바뀜" 60개의 표본이다 (index.md). 나머지 30개는 보지 않았다.

### 5.2 놓침 변이와 원인

없음. 30개 모두 sidecar의 모든 성질(P1, P1a, P1a_entry, P2, P3a, P3b, P3c, progress_*, MI_INV_*, MI_PERMS, MI_OWN, HIST)을 지킨다.
- 도구 결함(모델이나 규칙의 오류)은 찾지 못했다. 30개 모두 변이 모델이 변이 Kotlin을 그대로 옮겼고(0절 2번), Silicon 재실행이 PROVEN을 재현했다.
- 행동이 바뀐 9개는 sidecar가 다루지 않는 진행(liveness)·상한 동작을 바꾼다. 이것은 놓침이 아니라 sidecar의 성질 집합 밖이다 (5.4절).

### 5.3 미결 변이

없음.

### 5.4 판정과 별개: sidecar에 없는 성질

아래 세 성질은 원본 3개 모델에서 PROVEN이고, 부류 D 변이에서 FAILED다 (2.2절). sidecar에 진행 조건으로 넣으면 해당 변이가 FAILED가 된다. 넣을지는 sidecar 소유자가 정한다 (DESIGN-v0.md 5.4: 진행 조건은 사람이 쓴다).

| probe 성질 | 잡는 변이 | README·CLAUDE.md 근거 |
|---|---|---|
| G_cap: `!old(has(S, k)) && rej ==> res != NULL && !tok(res).gBacked && !has(S, k)` | RACY-04 ×3, SDL-08 | README 139, 141행 (spec gap 예) |
| G_expired: `old(has(S, k)) && now > old(S.m[k].expiresAt) ==> res != NULL && tok(res).gBacked` | MOP-01 ×2, core RACY-02 | README·CLAUDE.md에는 없음. core `KeyLock.kt` KDoc과 단위 테스트에 있음 |
| G_sweep: `old(has(S, k)) && now > old(S.m[k].expiresAt) ==> !has(S, k)` | RACY-09, RACY-08 | README 118-119행 (지연 상한은 보통 경우에 대한 말이라 spec gap은 아니오로 판정) |

주의: 세 성질은 action 하나의 진행만 말한다. 위 표의 "잡는 변이"는 이번 30개 안에서의 결과다.

### 5.5 이 판정의 한계

1. 동등 판정은 DESIGN-v0.md 부록 B의 신뢰 가정 위에 있다 (compute의 원자성과 1회 실행, cross-key 축소, action 안에서 예외 없음, `Long` 무한 정수 등). 30개 변이는 이 가정에 닿는 코드를 더하지 않았다. 새로 생긴 호출은 RACY 변이의 `containsKey` 하나다. 그 인자는 null이 아닌 key이고, 호출은 action 밖에 있다.
2. 모델 충실도는 바뀐 지점만 diff로 확인했다. 바뀌지 않은 부분의 번역은 원본 모델과 같다. 그 부분의 충실도는 이 판정의 범위 밖이다.
3. E_op의 관측 효과에는 로그와 `rejectsNewEntry`의 거부 횟수가 들지 않는다. 행동 동일 21개는 이 호출을 바꾸지 않았다.
4. 단위 테스트(4절)는 대상마다 한 번만 돌렸다.
