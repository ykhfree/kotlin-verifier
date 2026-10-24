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
