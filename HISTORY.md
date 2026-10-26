# 작업 이력

이 문서는 kotlin-verifier 프로젝트의 작업 이력이다. 장기 프로젝트이므로 작업 단계마다 항목을 더한다.

갱신 규칙:
1. 단계 하나를 마치면(또는 중요한 결정을 하면) 맨 아래에 항목을 더한다.
2. 항목에는 목표, 한 일, 결정, 측정 결과, 커밋, 남은 일을 적는다.
3. 수치에는 측정인지 추정인지 적는다.
4. 결정의 근거와 상세는 설계 문서(PHASE1.md, DESIGN-v0.md, DESIGN-B.md)에 두고, 여기에는 요약과 위치만 적는다.

## 목표

사용자의 말: "나는 AI가 생성한 코드가 우리의 명세대로 작성이 된것인지 확인하고 싶은거야".
Microsoft Research의 PDD Kit("의도 → 형식 명세 → 생성한 코드가 명세를 만족함을 증명")와 같은 일을 Kotlin/JVM에서 한다.

사용자가 정한 조건 (2026-10-22):
1. 명세는 Confluence에 있고, Markdown일 때도 있다.
2. 코드는 평범한 Kotlin이다. Verus처럼 annotation을 쓴 검증 가능한 부분집합은 받아들인다.
3. 증명은 모든 경우를 다뤄야 한다 (테스트가 아니라 증명).
4. 대상은 AI가 쓴 코드와 req-shield 같은 기존 코드 모두다.
5. 실무 팀용 도구다. 결과가 JVM에서 돌면 된다. 동시성과 IO가 있는 코드를 다룬다.

## 2026-10-22: 조사와 계획

- PDD Kit을 조사하고, Kotlin/Java용 검증 도구의 계획을 세웠다.
- 검증 백엔드로 Viper(Silicon, Z3)를 골랐다. 동시성은 모듈 전이 시스템(L1)으로 다룬다: `ConcurrentHashMap.compute` 람다 하나가 원자 action 하나다.
- 첫 대상: req-shield의 `KeyLocalLock`.

## 2026-10-23 오전: Phase 0 spike

- 커밋: `73986fc` ~ `3267698`.
- KeyLocalLock을 손으로 Viper로 옮겨 P1-P3c를 증명했다. Map 기반 ghost 상태는 300 s 넘게 멈췄고, 토큰마다 객체 하나(`tok(t)`) 인코딩은 약 1 s에 검증됐다 (측정).
- 결함 변이 SB1-SB4, SB6, E4는 실패하고, CAS를 지운 대조군 SB5, SB5b는 증명됐다 (측정).
- E3: LLM이 귀납 불변식(hole)을 5번 중 5번 채웠다 (측정). 교훈: init과 probe는 귀납성을 확인하지 않는다.
- 결과: `spike/RESULTS.md`, `spike/PLAN.md`.

## 2026-10-23: req-shield 정비 (부수 작업)

- req-shield 브랜치 `feature/NOISSUE/upgrade-kotlin2-and-deps`: Kotlin 2.4.21, Spring Boot 2 제거, 최소 버전 상향(Java 17, Boot 3.5, Spring 6.2, Reactor 3.7, coroutines 1.8, 사용자 Kotlin 1.9), Boot 3.5와 Boot 4.1 예제, OSV 보안 점검과 의존성 갱신.
- 커밋 하나(`2e792d2`)로 합쳐 ykhfree 포크에 force push했다 (사용자 승인). 테스트 359개 통과 (측정).
- 이 커밋이 이후 모든 검증의 대상 소스다.

## 2026-10-23 오후: Phase 1 계획과 설계

- SnaKt(JetBrains)를 평가하고 기반으로 쓰지 않기로 했다: KeyLocalLock 14개 함수 중 0개 검증, 건전성 결함 4건 재현 (측정). 결함은 모두 upstream에 이미 있어 코멘트 초안만 썼다 (`upstream/snakt-comment-drafts.md`, 게시하지 않음).
- 자체 K2 FIR frontend를 쓰기로 했다 (`frontend-probe/`, `2a2de76`). 계획: `PHASE1.md` (`7c09cbf`).
- 번역기 v0 설계: `DESIGN-v0.md` (`60d69ab`). KVIR, sidecar 명세 DSL, 효과 기반 ghost 바인딩 (c).
- 사용자 결정: U1 (c) 채택, U2 (연산당 compute 1개), U7 (LLM 초안의 권한 절과 발급 규칙은 사람이 `reviewed`를 붙여야 유효).
- 사용자 서명: 발급 규칙 1개, 허용 조건 4개 (`26dc408`), trusted fun 2개 (`30d4144`).

## 2026-10-23: 번역기 v0 (M0-M9)

- 커밋: `4632dda` ~ `2cb16a0`.
- M0-M4: frontend, KVIR와 W1-W14, sidecar 파서·resolver, lowering, 감사와 ledger.
- M5-M6: emitter, ghost binder, Silicon 실행기, 오류 대응, 보고서. 메인 세션이 만든 gate `spike/vpr_normalize.py`로 생성 모델 = 손으로 쓴 목표 모델(`spike/effect_ghost/target.vpr`)을 확인한다.
- 메인 세션의 Kotlin 원본 변이 점검에서 SB1, SB2가 FAILED가 아니라 ERROR였다. 원인: 토큰 비교가 사라지면 추상 타입이 정해지지 않음, W14 이중 집계. 수정: `free t: Token` 표기 (`77eebbd`).
- M7 (hole 흐름), M8 (Kotlin 변이 스크립트), M9 (분기 도달성 INFO).
- 결과: PHASE1 완료 조건 1-5 충족. 테스트 246개, 변이 22회 불일치 0 (측정).

## 2026-10-23 밤: 적용 A, core-reactor KeyLocalLock

- 사용자 결정: 다른 모듈에 적용한다, A 다음 B.
- 커밋: `e1c2ae8` ~ `24580a5`.
- 같은 명세(`keylocallock.common.kv`)로 세 번째 구현(`Mono.fromCallable` 안의 compute)을 검증했다. 새 신뢰 모델 `reactor.Mono.fromCallable`.
- 발견: 함수 하나가 action을 여러 번 돌리는 코드(`forEach { compute }`)를 전에는 조용히 받았다. 이제 UNSUPPORTED다.
- 결과: 세 변형 PROVEN, 변이 33회 불일치 0, 테스트 272개 (측정).

## 2026-10-23 ~ 10-24: 적용 B, Spring Aspect의 ReqShield 캐시

- 설계: `DESIGN-B.md` (`fce23c7`). 손으로 쓴 목표 모델 `spike/aspect/target.vpr`과 변이 13개로 설계를 먼저 측정했다.
- 사용자 결정: P_once(메서드당 한 번만 생성)를 증명한다. `reviewed` 메모를 검토한 텍스트의 SHA-256 지문에 묶는다 (모든 모듈).
- 1차 B0-B2 (`185844f` ~ `ca97744`): instance 모듈, `computeIfAbsent` 등, 연산 구간 번역, 예외 경로.
- 메인 세션 결정 (`ff5cf7a`): fresh 호출과 action 사이의 모델 밖 호출은 던질 수 있다 (버려진 객체를 P_once가 잡게 함).
- 2차 B3, B4, B6 (`53520ba` ~ `24cea74`), 메인 세션의 기대 모델 갱신 (`d6cdcd9`), 지문 범위에 modifier 포함 (`c561637`).
- 사용자 서명: `createReqShield` 메모 3개 (`b5dc35b`).
- 결과: Aspect 세 모듈 PROVEN, 변이 30회 불일치 0, KeyLocalLock 33회 불일치 0, 테스트 377개, gate 4개 exit 0 (측정).

## 2026-10-24: 품질 평가

사용자 질문: "현재까지 이 도구의 품질이 좋다는거야?"

메인 세션의 답: 좁은 범위 안에서는 근거가 강하지만, 범용 도구로서의 품질은 입증되지 않았다.
- 근거가 있는 것: 모듈 6개 PROVEN, 손으로 고른 결함 변이 13종 검출, 대조군 6종 PROVEN, 회귀 체계.
- 약한 것:
  1. 실제 AI 코드로 시험하지 않았다.
  2. 변이와 도구를 같은 쪽이 만들었다.
  3. 작업 중 건전성 결함이 여러 번 나왔다.
  4. 지원 범위가 좁다.
  5. 번역기 코드를 사람이 검토하지 않았다.
  6. sidecar 작성 비용이 크다.
- 제안한 측정:
  1. 실제 AI 코드로 시험
  2. Lincheck 같은 독립 판정과 비교
  3. 기계적 변이
  4. 핵심 코드 검토

사용자 결정: 3번(기계적 변이)을 한다. 작업 이력을 이 문서에 계속 남긴다.

## 진행 중: 기계적 변이 (2026-10-24 ~)

목표: 사람이 고른 변이 13종의 편향 없이, 규칙으로 만든 많은 변이에서 도구의 판정을 잰다.

측정 규칙: `MUTATION.md` (결과 전에 고정). advisor 검토를 반영했다.
- 결과를 믿을 수 있게 하는 장치: 생존 변이의 판정 규칙(반례 또는 동등 논증, 없으면 미결)을 미리 정한다.
- 판정자는 새 맥락의 deep-reasoner다. 도구의 규칙으로 예측되는 판정 7가지를 결과 전에 등록했다.
- 변이 규칙 9종: ROR, COR, NEG, BOOL, SDL, RET, AOR(Long 위치만), MOP, RACY(경쟁 읽기 삽입).
- 순서:
  1. feature-developer가 측정 도구를 만들고 6개 모듈에 돌린다.
  2. 메인 세션이 결과를 다시 돌려 확인한다.
  3. deep-reasoner가 생존 변이를 판정한다.
  4. 결과를 이 문서에 기록한다.

### 측정 결과 (2026-10-24, 판정 전)

- 커밋: `7462f2a` ~ `17e1b42` (측정 도구 `kv mutation-sites`, `translator/scripts/mechanical.py`, 결과 `mutation/`).
- 변이 278개, 사산 27개 (smart cast가 깨진 null 검사 변이 24, non-null 결과를 `null`로 3). Silicon 198회 (해시 공유로 12회 절약), 898 s (측정).
- 판정 분포 (측정):

| 판정 | 개수 |
|---|---|
| FAILED | 129 (7.1 예측 6, 7.2 예측 9, 그 밖 114) |
| PROVEN, 모델 바뀜 | 60 (판정 대기) |
| PROVEN, 모델 동일 | 7 (규칙으로 추상화됨) |
| ERROR `stale-review` | 47 (서명이 막음) |
| ERROR 그 밖 | 0 |
| INCOMPLETE | 8 |

- 예측 대조: 7.1, 7.3, 7.4, 7.5, 7.6은 예측과 같다. 7.2(9개)와 7.7(9개)은 판정 대기다.
- 한계: Aspect 세 모듈의 변이 14개는 모두 서명이 막았거나(11) 사산(3)이다. 기계적 규칙으로는 Aspect 연산 코드의 품질을 잴 수 없다. Aspect의 근거는 계속 손으로 고른 변이(DESIGN-B 7절)다.
- 메인 세션 확인:
  - 테스트 396개 통과.
  - gate 4개 exit 0.
  - 손으로 고른 변이 33회와 30회 모두 불일치 0.
  - `results.jsonl`을 집계해 요약 표와 같음을 확인했다.
  - 변이 3개(SDL-13 PROVEN, RACY-01 FAILED, BOOL-03 FAILED)는 측정 도구 없이 직접 재현했다. 판정과 실패 의무가 모두 같았다.
- 정리: 저장소가 추적하던 `__pycache__/mutants.cpython-314.pyc`를 빼고 `.gitignore`에 `__pycache__/`를 더했다.
- 다음: 판정 묶음 49개(`mutation/judge/`)를 새 맥락의 판정자에게 넘긴다.

### 판정 결과 (2026-10-24)

- 판정자 A (생존 변이 표본 30): 놓침 0, 동등 30, 미결 0.
- 판정자 B (FAILED 19): 검출 18, 오경보 1 (reactor SDL-02, 원인은 원본에 맞춘 고정 hole).
- 상세와 발견은 `MUTATION.md` 9절에 있다. 요약:
  1. 도구의 건전성 결함은 찾지 못했다.
  2. FAILED 보고의 절반이 성질 대신 hole 조각을 가리킨다.
  3. 진행 조건 두 개가 의도보다 강하다. 검출 7개가 이 해석에 달려 있다.
  4. 명세의 빈틈: 상한 동작, 만료 lock의 즉시 넘겨받기.
- 메인 세션 확인: 판정자 증거 10개를 Silicon으로 재현했다. README 인용 3개는 quote-check로 확인했다.
- 남은 결정 (사용자): 진행 조건 문구를 의도대로 고칠지, sidecar에 상한·만료 성질(`G_cap`, `G_expired`)을 더할지.
- 남은 결정 (메인 세션): hole 조각만 실패할 때 성질을 따로 확인하는 보고 개선을 할지.

### 사용자 결정 (2026-10-24, 측정 뒤)

1. 진행 조건 `progress_live`, `progress_owner`를 의도대로 좁힌다. 고친 문구는 사용자 승인을 받아 확정한다.
2. sidecar에 `G_cap`(상한 동작, README 139행)과 `G_expired`(만료 lock의 즉시 넘겨받기, KeyLock KDoc이 함축)를 성질로 더한다.
3. 보고 개선을 구현한다. 불변식 조각만 실패하면 성질을 따로 확인하고, 보고를 "성질 위반"과 "불변식만 불일치"로 나눈다.

순서: 1과 2(명세와 기대 모델)를 먼저 하고, 3(도구)을 나중에 한다. 동시에 하면 서로의 회귀 검사를 깬다.

### sidecar 변경 (2026-10-24, 사용자 승인)

- 커밋: 이 항목과 같은 날의 "Narrow progress_live and add cap_unbacked and expired_takeover".
- 바뀐 것:
  - `progress_live`: 잡혀 있고 만료되지 않은 entry만 획득을 막는다.
  - `cap_unbacked` 추가: README 139행의 상한 동작.
  - `expired_takeover` 추가: KeyLock KDoc이 함축하는 만료 lock 넘겨받기.
- `progress_owner`는 그대로 둔다. 의도("자기가 받은 토큰")가 호출자의 이력에 관한 것이라 v0 명세로 표현할 수 없다.
- 확인 (메인 세션):
  - 세 변형 PROVEN, gate 4개 exit 0, 테스트 396개.
  - 손으로 고른 변이 33회와 30회 불일치 0.
  - 기대 모델 사본에서 변이 24개의 판정이 이전과 같다.
- 다음: 보고 개선(성질 재확인)을 구현하고 재측정한다. 재측정 예측은 `MUTATION.md` 변경 기록에 결과 전에 등록했다.

### 보고 개선과 재측정 (2026-10-24)

- 커밋:
  - `5ff2b62`: pass 2 성질 확인, 하위 상태 "property" / "invariant only"
  - `10688ed`: 테스트 보강
  - `e0f5d00`: 재측정 결과 `mutation/rerun/`
- 결과 (상세는 `MUTATION.md` 9.5):
  - 판정 변화 15개. 모두 새 성질로 PROVEN → FAILED이고, 반대 방향은 없다.
  - 예측 6개 중 5개가 맞았다.
  - 예측 4는 틀렸다. 새 성질 `expired_takeover`가 판정자 B의 "명세의 빈틈"(reactor SDL-02)을 메워 property 위반이 됐다.
  - 진짜 결함 SB3, AOR-03, SDL-06이 "invariant only"로 보고된다. 그래서 이 하위 상태를 무해하다고 읽으면 안 된다.
- 비용: pass 2 때문에 측정 시간이 898 s에서 1417 s로 늘었다. 손으로 고른 변이 33회는 120 s에서 150 s로 늘었다.
- 메인 세션 확인: 테스트 408개, gate 4개, 변이 63회 불일치 0, 재측정 집계와 판정 변화 목록, 보고서 원문.

## 진행 중: AI가 쓴 코드로 시험 (2026-10-24 ~)

- 사용자 결정: 품질 측정 1번(실제 AI 코드로 시험)을 한다.
- 계획 초안: `AI-CODE.md` (결과 전에 씀, advisor 검토 전).
  - AI에게 자연어 명세와 "검증 가능한 부분집합"의 모양 틀만 준다. 모델 3종 × 4회로 coroutine `KeyLocalLock`을 쓰게 한다.
  - 정답은 req-shield 단위 테스트와, 도구 결과를 보지 않는 판정자가 정한다.
  - 도구는 실제 사용 흐름 그대로 쓴다: 실패하면 LLM이 그 코드에 맞는 불변식(hole)을 다시 쓴다.
- 계획 확정 (`0fd17cb`, advisor 검토 반영).
  - 모양 틀의 뜻: "팀이 정한 모양 안에서 AI가 논리를 채움"을 잰다.
  - 자유 구현 3개는 필수다.
  - 오염 검사(원본과의 유사도)를 한다.
  - 분류를 세분한다: sidecar 밖 결함, 묶기 실패, invariant only.
  - 블라인드는 세 쪽(생성자, 판정자, hole 작성자)이다.
  - 프롬프트 원문과 예측 7개를 결과 전에 등록했다.
- 1단계 시작: 측정 도구(샌드박스, 컴파일, 테스트, 도구, hole 과제, 유사도) 구현을 feature-developer에게 맡겼다.
  - 원본(D1)과 SB1 결함(D2)으로 측정 도구를 먼저 시험한다.
- 1단계 완료 (`5b4c1e1` ~ `e08d3a3`): 측정 도구 `translator/scripts/aicode.py`.
  - 시험 실행:
    - D1(원본): 테스트 14/14, PROVEN, 유사도 1.0.
    - D2(SB1): 테스트 12/14, FAILED (property, unLock P3a), hole 과제를 만듦.
  - 메인 세션 확인과 결정 (생성 전, `AI-CODE.md` 변경 기록):
    1. HELPERS.md의 원래 import 줄을 빼서 오염을 줄였다.
    2. 자유 프롬프트 문법을 고쳤다.
    3. 테스트는 `--shim`으로 돌린다.
    4. hole TASK.md의 설명을 고쳤다.
    - AicodeScriptTest 6/6 통과.
- 2단계 완료 (생성, `b3e2eda` ~ `15b462c`): 15개 모두 컴파일됨 (`compile.sh` 1-3회). 예측 1 맞음.
  - 관찰 (판정 전): 틀 구현 12개 중 4개(T-sonnet-2, T-opus-1, T-opus-3, T-opus-4)가 `kotlinx.coroutines.sync.Semaphore`를 골랐다. RULES.md가 `Semaphore`의 패키지를 정하지 않았다.
  - T-sonnet-4는 `LockInfo`를 companion object 안에 두었다.
  - 프로토콜 이탈: T-opus-2는 "다른 agent 금지"를 어기고 advisor(더 강한 모델)에 한 번 물었다. 블라인드는 유지된다(advisor는 그 agent의 대화만 봄). 다만 "opus 단독"이 아니므로 결과에 따로 표시한다.
  - 생성 agent에는 사용자의 전역 CLAUDE.md(한국어 응답, 개념 줄이기 규칙)가 함께 적용된다. 실제 팀 환경과 같은 조건으로 본다.
- 3단계 (`0828853`, 측정):
  - 유사도: 0.22-0.37, T-opus-1만 0.57. 거의 같은 것(0.9 이상)은 없다. 예측 7은 틀렸다.
  - 단위 테스트: 틀 구현 12개 모두 14/14 (shim). 자유 3개는 shim이 private companion 때문에 컴파일되지 않았다.
  - 도구: 틀 구현 12개 중 끝까지 검증된 것은 0이다.
    - 묶기: 4개는 `LockInfo` 위치가 달라 실패했다. 결과 뒤 `class` 묶기 수정을 더해 묶었다 (`AI-CODE.md` 변경 기록).
    - 범위 밖: 4개가 `kotlinx.coroutines.sync.Semaphore`를 써서 범위 밖이다.
    - tryLock: 묶인 8개 모두 INCOMPLETE다. 주로 `lockMap.size.toLong()`(v0는 Int의 `+`, `-`만 모델링). 그 밖에 값으로 쓴 if, helper 호출, `Semaphore` 필드 직접 접근이 있다.
    - sweep: 6개가 FAILED (property)다. 만료 판정이 `>=`(`expiresAt <= now`)이기 때문이다. 서명된 P3a는 `now > gLease`(엄격)다.
- 다음: 판정자 3명의 결과를 기다린다 (블라인드, 실행 중).
- 4단계 판정 (판정자 3명, 블라인드): 틀 구현 12개 모두 "결함 (sidecar 성질)"이다.
  - 공통 결함은 만료 경계 `>=`다. 서명된 P3a는 `now > gLease`다.
  - T-haiku-3과 T-sonnet-3에는 unLock 결함도 있다.
  - overflow는 도구의 `Long` 가정 밖이다.
- 대조 (`AI-CODE.md` 10절):
  - 도구가 판정을 낸 action 11개는 모두 정답과 맞다 (검출 6, 맞은 PROVEN 5, 놓침 0, 오경보 0).
  - 그러나 tryLock은 모두 UNSUPPORTED이고, 4개 구현은 범위 밖이다.
  - 그래서 정답이 찾은 결함 중 다수를 도구가 판정하지 못했다.
- hole 단계는 생략했다. 도구의 FAILED 6개가 모두 실제 위반으로 확인되었기 때문이다 (변경 기록).
- 가장 큰 결과는 범위다. 지금 도구는 AI 코드 12개 중 하나도 끝까지 검증하지 못했다. 원인은 `Int.toLong`, kotlinx `Semaphore`, helper 호출 등이다.

### 사용자 결정 (2026-10-24, 1차 결과 뒤)

1. 만료 경계는 엄격(`>`)을 유지한다.
2. 2차 측정: 도구에 `Int.toLong`과 kotlinx `Semaphore` 모델을 더하고, 같은 12개로 도구만 다시 돌린다.
- 계획과 예측 6개: `AI-CODE.md` 11절 (결과 전에 등록).
- 2차 결과 (`7c65fd3` ~ `03ebe93`, `AI-CODE.md` 12절):
  - 판정한 action이 11개에서 15개로 늘었다: 검출 10, 맞은 PROVEN 5, 놓침 0, action 단위 오경보 0.
  - 의무 단위의 거짓 이유가 2개 있다 (T-haiku-2: compute 밖 토큰 생성 때문).
  - ERROR 3개는 기존 helper inlining 결함이다 (W5/W6가 잡음).
  - 예측 1은 틀렸다 (Semaphore를 만드는 모양이 숨은 원인). 예측 2-6은 맞았다.
  - 메인 세션 확인: 테스트 429개, gate 4개, 변이 63회 불일치 0, 보고서 원문 집계.

### 사용자 결정 (2026-10-25, 2차 결과 뒤)

- AI 코드 측정은 2차로 마친다.
- 도구 결함 셋(지원 밖 구문에 기대는 action, helper inlining W5/W6, 지원 밖 구문 전부 나열)을 고친다. 수정 검증 실행의 예측은 `AI-CODE.md` 13절이다.
- 그다음 Confluence/Markdown → sidecar 방향으로 전환한다. 설계는 deep-reasoner가 맡고, 버그 수정과 동시에 진행한다.
- 버그 수정과 검증 실행 (`e436d22`, `43786d8`, `3208158`, `df8db76`; `AI-CODE.md` 13.1):
  - 수정 1: 입력이 action 밖의 지원 밖 모듈 상태 접근에서 오는 action은 UNSUPPORTED다. 수정 2: helper의 객체 파라미터는 받은 entry나 새 객체 자체다. 수정 3: action의 지원 밖 구문을 모두 보고한다.
  - 2b 대조 (36개 action): 검출 14, 맞은 PROVEN 8, 놓침 0, 오경보 0, 판정 안 함 14. 모듈: FAILED 10, INCOMPLETE 2, ERROR 0.
  - 예측 1-3은 맞았다. 예측 4는 일부 맞았다 (예로 든 T-haiku-3은 늘지 않음).
  - 수정 agent가 기존 결함 둘을 찾았다 (`AI-CODE.md` 13.2): S1 aspect section의 거짓 PROVEN, S2 stray 호출 인자 안의 counter 쓰기가 ledger에 없음.
  - 메인 세션 확인: `cleanTest test` 447개 통과, gate 4개 exit 0 (751, 2729, 2734, 2734 tokens), mutantsReqShield 33회와 mutantsAspect 30회 불일치 0, 2b 보고서 12개 원문 집계.
- DESIGN-C (자연어 명세 → sidecar) 설계와 파일럿 (deep-reasoner, `DESIGN-C.md`):
  - 권고: 독립 초안 3개(서로 다른 모델) + 시나리오 칸 + 결정성 검사(Silicon) + 질문 규칙 5개 + 결정을 서명 probe로 남기기 + `source` 출처 줄.
  - 파일럿 (KeyLocalLock, 입력은 README·KDoc만): 세 초안 모두 만료 경계를 `>=`로 썼다. 세 작성자 모두 시나리오 답에서는 그 칸을 open이라 했다. 질문 규칙이 칸 8개, 결정 4개(D1, D2, D4, D5)를 골랐다.
  - 결정을 맞춘 초안 3개: 원본 PROVEN. 뿌리 89개에서 서명된 sidecar가 잡은 65개를 모두 잡고 2개(RACY-09, D9-sweep-never)를 더 잡았다.
  - 서명된 sidecar의 빈틈 둘: D9(monitor 의무 없음, D9-sweep-never가 PROVEN), D14(만료 시각 = lease가 hole 안에만 있음, hole 한 줄을 `<=`로 바꾸면 D14b가 PROVEN).
  - 메인 세션 확인:
    - 다시 돌림: D9-sweep-never(서명된 sidecar에서 PROVEN), D14b의 세 경우(서명 FAILED, hole 완화 PROVEN, 결정 probe 추가 FAILED).
    - 다시 셈: 89개 비교표 (파일럿 JSONL).
    - quote-check: 핵심 인용 9개가 원문에 있고 주장을 뒷받침한다.
- S1, S2 수정 (메인 세션, `AI-CODE.md` 13.2):
  - S1: section의 relevance가 relevant 지역 변수에 대입하는 문장도 넣는다. 거짓 PROVEN이던 fixture가 이제 FAILED (`P_same`)다.
  - S2: audit이 put의 값 인자를 action 밖으로 본다. action 밖 counter 쓰기와 fresh 함수 밖 `Shield` 생성자가 이제 ledger에 UNSUPPORTED로 남는다.
  - 확인: 452개 통과, gate 4개 exit 0, 변이 63회 불일치 0. 결함 테스트 3개는 수정 전 코드에서 실패한다.

### 사용자 결정 (2026-10-25, DESIGN-C 뒤)

- sidecar에 더함: 만료 시각 = lease (UC2), 거절된 tryLock은 entry를 바꾸지 않음 (UC3). monitor 의무(UC1)는 더하지 않음 (RACY-09는 허용된 지연).
- 서명 범위: 새 명세는 의도 절 전부와 결정 probe (UC4, UC5).
- Confluence는 Cloud (UC6). 초안은 1개로 시작하고 두 번째 모듈에서 3개와 비교 (JC1).
- 기본값으로 둔 것: 페이지 텍스트는 Anthropic 밖으로 보내지 않음, 경계 칸은 항상 묻기, D2와 D5는 지금 서명대로.
- 전체 표: `DESIGN-C.md` 10절.
- sidecar 보강 (`bdf08d9`, `b2c2c5b`; `MUTATION.md` 9.6):
  - `invariant entry_lease`: 토큰을 가진 entry의 만료 시각은 그 토큰의 lease다 (UC2).
  - tryLock의 `ensures refusal_keeps_entry`: 거절된 호출은 owner와 lease를 바꾸지 않는다 (UC3).
  - 처음 쓴 `entry_lease`에는 토큰 조건이 없어서 "모든 entry는 토큰을 가진다"까지 요구했다. 2차 재측정의 예측 4가 이 때문에 틀렸고, 조건을 넣은 뒤 3차로 다시 쟀다.
  - 결과: 판정 변화 0. "invariant only" 15개가 성질 위반이 됐다 (진짜 결함 AOR-03, SDL-06 포함). D13, D14, D14b, SB3도 성질 위반이다. hole을 느슨하게 해도 그렇다.
  - 두 절의 서명은 C1(ensures와 invariant의 `reviewed` 문법) 뒤에 받는다.
- 파일럿 자료를 `pilot-c/`로 옮겼다 (`cbcfe79`). scratch 폴더는 세션과 함께 사라진다.
- C0, C1 구현 시작 (feature-developer, 2026-10-25): 스냅숏(`kv spec snapshot`), `source` 줄, `kv spec stale`, 이름 있는 probe, 결과 없는 연산 호출, ensures·invariant·lemma·probe의 `reviewed`, opt-in `review intent` 줄 (UC4).
- C0, C1 완료 (`aba91e7`, `8d8e17a`): `kv spec snapshot`(파일럿 128블록과 바이트 단위로 같음), `source` 줄, `kv spec stale`, 이름 있는 probe, 결과 없는 호출, 모든 의도 절의 `reviewed`, `review intent`.
  - 수락 테스트: 파일럿 인용 114개 통과, README 118행 수정 시 그 문구를 인용한 절 8개만 `stale-source`, 결정 probe는 결정을 담은 절이 약해질 때 FAILED.
  - 메인 세션 확인: 테스트 488개, gate 4개, 변이 63회 불일치 0. 열린 질문의 결정은 `DESIGN-C.md` 10.1.
- 서명 (사용자 승인 2026-10-25): `entry_lease`(출처 README 118-119행 블록, CLAUDE.md lockTimeoutMillis 줄)와 `refusal_keeps_entry`(`source none`: 문장 없음, 사용자 결정 UC3)에 `reviewed` 메모와 지문을 달았다. 지문은 절 텍스트와 출처 줄을 함께 묶는다.
  - 확인: 테스트 488개 통과, 세 변형 PROVEN, `kv spec stale` 오류 0, gate 3개 exit 0, mutantsReqShield 33회 불일치 0.
  - 나머지 의도 절 13개는 아직 서명하지 않았다. 그래서 `review intent` 줄은 넣지 않았다.
- C2 시작 (사용자 요청 2026-10-25, feature-developer): emitter가 action 계약에 FRAME, END, ISSUE(그리고 필요하면 WITNESS) 사실을 더한다. gate 목표 모델(`spike/`)은 메인 세션이 손으로 고친 뒤, emitter 출력과 대조한다. 변이 판정이 하나라도 바뀌면 멈추고 보고한다.
- C2 완료 (`9ea254b`, `c7b54e1`; `DESIGN-C.md` 10.2): emitter가 FRAME, END, ISSUE 사실을 계약에 낸다. WITNESS는 C3에서 정한다.
  - gate 목표 모델은 메인 세션이 설계 식에서 손으로 고쳤고, emitter 출력과 같다 (테스트 490개 통과).
  - 판정 변화 0: 변이 63회, 기계적 변이 264개 (`mutation/rerun-c2/`가 `rerun3`과 같음).
- C3 시작 (사용자 요청 2026-10-25, feature-developer): `kv spec check` (계약만의 모델, 시나리오 칸 결정성 a·b, 절 하나씩 일관성, 필드 고정 경고, `--draft`). 수락: 파일럿 표 재현, 지금 sidecar의 칸 표(예측: C05, C19가 방식 b에서도 정해짐), WITNESS(O1)와 필드 프레임(O2) 측정.
- C3 완료 (`b5191e4`; `DESIGN-C.md` 10.3): `kv spec check`가 파일럿 결정성 266개 판정과 실패 절 9개를 모두 재현한다. 지금 sidecar는 C16만 OPEN이고, 필드 고정 경고 1개(`isHeld`)가 남는다. 후보 절 `entry_held`는 사용자에게 묻는다.
- `entry_held` 추가와 서명 (사용자 결정 2026-10-25, `6138a8b`; `MUTATION.md` 9.7): 토큰을 가진 entry의 isHeld = 그 토큰의 gHeld. 필드 고정 경고가 0개가 됐다. 4차 재측정의 예측 3개가 모두 맞았다 (판정 변화 0, isHeld 변이 12개가 성질 위반). 처음 측정의 "invariant only" 36개 중 30개가 이제 성질 위반으로 보고된다.
- C4 시작 (사용자 요청 2026-10-25, feature-developer): 시나리오 칸 생성(`kv spec cells`), 질문 규칙과 묶음 검사(초안 1개 규칙 포함, JC1), 검토 묶음 Markdown. 수락: 파일럿 19칸 재현, 질문 칸 8개(3초안)와 9/8/6개(1초안), Aspect 모듈에서 칸 생성과 서명 sidecar의 결정성. 다른 key 단계가 생기면 미룬 도구 사실을 잰다.
- C4 완료 (`ea1ffcc`, `83de51d`; `DESIGN-C.md` 10.4): 칸 생성(KeyLocalLock 65칸이 파일럿 19칸을 덮음, Aspect 12칸), 질문 규칙(3초안 8칸, 1초안 9/8/6칸), 묶음 검사, 검토 묶음. 도구 사실 WITNESS와 ISSUE_ONLY를 더했다.
  - 메인 세션 확인: gate 목표 모델을 손으로 고침, 테스트 536개, 변이 63회 불일치 0, 기계적 변이 264개 변화 0 (`mutation/rerun-c4/`).
  - 새 질문 2개를 사용자에게 묻는다: KeyLocalLock "unLock·sweep은 entry를 만들지 않음", Aspect "생성 함수가 throw하면".
- 서명 (사용자 승인 2026-10-26, `2137e85`): KeyLocalLock unLock·sweep의 `no_entry_created`, Aspect의 `factory_throw`. C4의 칸 생성이 찾은 빈틈이다.
  - 결과: KeyLocalLock 방식 b OPEN 11 → 5 (남은 것은 모두 UC1에서 나옴), Aspect는 모든 칸이 정해짐. 여섯 모듈 PROVEN.
  - 5차 재측정 (`MUTATION.md` 9.8): KeyLocalLock 변화 0 (예측 맞음). Aspect 변이 15회는 판정 그대로, `factory_throw`가 진짜 위반으로 더 보고됨.
- C5 시작 (사용자 요청 2026-10-26, feature-developer): `scripts/spec.py`의 task, collect(교정 2회, 오류 줄만), cells(2단계 상자), questions, align, outcomes. 파일럿을 opus 초안 하나로 처음부터 다시 돌려 재현한다. 65칸 답의 비용 자료를 낸다. 실제 LLM 작성자 실행은 그 뒤 메인 세션이 한다.
- C5 완료 (`8007476`, `1eb2df5`; `DESIGN-C.md` 10.5): `scripts/spec.py`가 단계마다 명령 하나로 흐름을 돈다. 파일럿을 opus 초안 하나로 다시 돌려 같은 결과를 얻었다. 메인 세션 확인: 테스트 546개, 변이 63회 불일치 0.
- 다음: 실제 LLM 작성자 하나로 처음부터 끝까지 한 번 돌린다 (생성 칸 65개, 비용 측정).
- 실제 실행 시작 (2026-10-26): 작성자 agent 하나(opus)가 1단계 상자로 초안을 쓴다. 실행 폴더는 저장소 밖 scratch(`e2e/run`)다. 단계마다 결과를 기록한다.
- 실제 실행 완료 (2026-10-26; `DESIGN-C.md` 10.6, `specdraft/e2e-2026-10-26/`): LLM 약 21만 토큰, 9분. 질문 4개(경계, 넘겨받기 포함)가 올라왔고, 결정에 맞춘 초안은 init 결함(`gIssued` 없음)을 메인 세션이 고친 뒤 원본 PROVEN, 결정 변이 5개와 AI 구현 10개를 모두 잡았다. 발견: init·lemma를 보는 검사와 교정 되돌림 단계가 없다; 참조가 OPEN인 칸을 초안이 정하면 묻지 않는다.
- 흐름의 빈틈 3개 메우기 시작 (사용자 요청 2026-10-26, feature-developer): (1) 절 분리와 `align --check`가 init·lemma·probe를 보고, 실패하면 오류 줄만 담은 FEEDBACK을 교정 상자에 쓴다(최대 2회); (2) 결정 파일의 `open` 결정과 규칙 6(참조가 OPEN인 칸을 초안이 정하면 질문); (3) LANGUAGE.md의 `gIssued` 조건 안내.
- 빈틈 메우기 완료 (`2158745`; `DESIGN-C.md` 10.7): init·lemma·probe 검사와 FEEDBACK 되돌림, `open` 결정과 규칙 6, LANGUAGE.md의 `gIssued` 안내. 실제 실행 자료로 확인: 규칙 6이 monitor 의무 칸(H14s)을 질문으로 올리고, open으로 정하면 그 의무가 든 초안을 막는다. 후속(방식 b의 규칙 6 제한, 재현 기록 갱신)은 진행 중.
- 후속 완료 (`8d1ce1c`, `5b49821`): 방식 b의 규칙 6은 이전 결정의 open 칸만 본다 (실제 실행 방식 b의 잡음 4칸이 사라짐). 파일럿 재현 기록을 다시 만들었다: 질문 7칸(C16 포함), align --check는 C16 때문에 의도대로 실패하고 FEEDBACK 1줄. 메인 세션 확인: 테스트 551개, 변이 63회 불일치 0.

### 사용자 결정 (2026-10-26): AI 코드 지원 범위 넓히기

- 계획과 예측은 결과 전에 `AI-CODE.md` 14절에 등록했다 (`2d479ee`). 2b에서 판정하지 못한 action 14개를 원인별로 셌다: Semaphore를 먼저 만들고 넘기는 모양 9, 값으로 쓴 if/when·별칭 2, `?.`·멤버 helper 3, compute 밖 토큰 3 (겹침 있음).
- A1-A5 구현 시작 (feature-developer), Part B(compute 밖 토큰) 설계 시작 (deep-reasoner, `DESIGN-D.md`).
- 과적합 검사: 구현 뒤 새 AI 구현 12개를 만들어 수정 전·후 도구를 모두 돌린다. 기준: 수정 뒤 판정 비율 75% 이상.
- 3a 완료 (`f3f267f`, `846d4e4`; `AI-CODE.md` 14.4): 2차 12개에서 판정 22 → 32 (36개 중), 놓침 0, 오경보 0. 판별 예측(T-haiku-3, T-sonnet-3 unLock FAILED) 맞음. 예측 1은 하나 틀림(T-opus-2의 `Long.MAX_VALUE`).
- S3 (기존 결함, 메인 세션이 고침, `AI-CODE.md` 14.5): `reads()`가 대입의 값 쪽 읽기까지 빼서 셀·평탄화 필드의 escape를 놓쳤다. 테스트 585개, 변이 63회 불일치 0, 3a 결과 변화 없음.
- 과적합 검사 시작 (`AI-CODE.md` 14.3): 1차와 같은 과제·프롬프트로 새 구현 12개(G-haiku/sonnet/opus-1..4)를 생성 중이다. 결과 폴더 `aicode/round3g/`. 메인 세션은 새 코드를 도구에 돌리기 전에 읽지 않는다.
- 과적합 검사 결과 (`AI-CODE.md` 14.6): 새 구현 12개에서 판정 비율이 수정 전 72% → 수정 뒤 86%다. 기준 75%를 넘어 A1-A5는 일반화한다고 본다. 새 12개도 모두 만료 경계를 `>=`로 써서, AI 구현 24개가 모두 같은 쪽을 골랐다. 남은 미지원 5개 중 4개는 Part B다.

### 사용자 결정 (2026-10-26): 새 12개 판정자 3명, Part B 권고안 (a) 구현

- 판정자 3명(블라인드 deep-reasoner) 시작: 각자 새 구현 4개, 도구 결과와 그것을 적은 문서(AI-CODE.md, HISTORY.md, git 기록)를 보지 못한다. 결과는 `aicode/round3g/judgments-{1,2,3}.md`.
- Part B: advisor 권고로 메인 세션이 (b)·(c)의 반례가 실제 코드로 만들 수 있는 경우임을 확인했다 (E6: action 안 `counter.get()`, E5: 예약 값 비교). 예측은 `AI-CODE.md` 15절에 등록했다 (`adbe2f8`). 구현 시작 (feature-developer). 검증은 새 폴더 `aicode/round4/`에서 한다 (판정자 작업과 겹치지 않게).
