# Translator C 설계: 자연어 명세에서 sidecar로

날짜: 2026-10-25. 상태: 설계안 (도구 코드 변경 전). 범위: Confluence 페이지나 Markdown 문서(자연어 명세)를 kv sidecar의 형식 명세로 바꾸는 단계.
기준: `DESIGN-v0.md` 4절(sidecar 문법과 뜻), `DESIGN-B.md` 5.1절(B 추가분). 이 문서는 그 위에 더하는 것만 정한다.

수치 표시 규칙: "측정"은 이번 작업에서 실행해서 얻은 값이다. "추정"은 실행하지 않은 값이다.
파일럿 도구: `translator/build/install/kv`를 scratch에 복사한 고정본(translator.jar sha256 `722e63e8…`, 2026-10-25 01:16 빌드)이다. Silicon `v.26.08-release`.
파일럿 파일: `/private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/spec-design/` (부록 A). 저장소의 `specs/`와 `translator/`는 바꾸지 않았다.

## 0. 요약

| 항목 | 결정 (권고) | 근거 |
|---|---|---|
| 층 나누기 | sidecar를 다섯 층으로 나눈다: 묶기, 신뢰, 어휘, 의도, hole. 자연어에서 초안을 쓰는 것은 의도 층뿐이다 | 2.1절. 파일럿: 묶기·어휘를 고정하면 서명된 의도 절 15개 중 9개를 세 초안이 모두 같은 뜻으로 썼다 (판단, 기계 확인은 부록 B) |
| 초안 | 서로 다른 모델로 독립 초안 3개. 초안마다 절, 출처 인용, 범위표, 질문 목록, 시나리오 답을 낸다 | 3절, 측정 |
| 모호성 | 중심 장치는 **시나리오 칸**이다. 도구가 칸(연산 × 상태 × 경계값)을 만들고, 각 초안의 형식 절이 칸을 정하는지 Silicon으로 잰다 (결정성 검사). 질문은 칸 단위로 만든다 | 3.2-3.4절. 파일럿: 질문 규칙이 칸 8개, 결정 4개(D1, D2, D4, D5)를 골랐다. 그 밖의 칸은 고르지 않았다 (측정) |
| 경계 | 시계와 lease가 같은 칸은 초안이 모두 같아도 사람에게 묻는다 | 초안 3/3과 AI 구현 12/12가 모두 `>=`를 골랐다. 서명된 선택은 `>`다 (측정) |
| 결정 기록 | 사람의 답은 이름 있는 probe(시나리오)로 sidecar에 들어가고 `reviewed`로 서명된다 | 3.5절. 파일럿: hole을 다시 쓰면 PROVEN으로 숨는 결함(D14b)을, 결정 probe 하나가 두 hole에서 모두 FAILED로 잡았다 (측정) |
| 기계 검사 | 문법·타입, 인용, 범위, 참조 구현과의 일관성(절 하나씩), 결정성, 필드 고정, 판별력(변이, 결정 변이, AI 코드) | 4절. 파일럿: 결정을 맞춘 초안 3개가 원본에서 PROVEN이고, 뿌리 89개에서 서명된 sidecar가 잡은 65개를 모두 잡고 2개를 더 잡았다 (측정) |
| 추적성 | 의도 절마다 `source "<anchor>" quote "<인용>"`. 인용이 사라지면 `stale-source` 오류, 블록만 바뀌면 경고 | 5절. 지금의 `reviewed ... sha256`은 절 텍스트만 묶는다 |
| Confluence | v1은 Markdown 파일과 Confluence storage 형식(로컬 스크립트, 토큰은 macOS Keychain)이다. 페이지 텍스트는 Anthropic 밖으로 보내지 않는다 | 6절 |
| 도구 | `kv spec snapshot`, `kv spec check`, `kv spec stale`(Kotlin)과 `scripts/spec.py`(초안 과제와 질문 목록). 문법 변화 3개와 emitter 계약 사실 4개 | 7절 |
| 구현 | 마일스톤 C0-C6 (+ 선택 C7). 사람 엔지니어 기준 12-20.5 인일 (추정) | 8절 |

파일럿에서 나온 서명된 sidecar의 빈틈 (측정, 9.1절에서 사용자가 정함):
1. **D9 monitor 의무가 없다.** monitor가 만료 entry를 절대 지우지 않는 변이가 서명된 sidecar에서 PROVEN이다. 초안 3개는 모두 이 의무를 썼다.
2. **D14 lease가 hole 안에만 있다.** 만료 시각을 `now + lockTimeoutMillis + 1000`으로 늦춘 변이가 지금은 "invariant only"로 실패한다. 그런데 hole의 conjunct 하나를 `==`에서 `<=`로 바꾸면 PROVEN이 된다. 결정을 맞춘 초안 3개는 모두 이 변이를 성질 위반으로 잡는다.

## 1. 문제

### 1.1 목표와 지금의 흐름

사용자의 목표: "AI가 생성한 코드가 우리의 명세대로 작성이 된것인지 확인하고 싶은거야". 명세는 Confluence에 있고, Markdown일 때도 있다 (HISTORY.md, 2026-10-22 조건 1).

지금은 사람과 설계 agent가 sidecar(`specs/reqshield/*.kv`)를 손으로 쓴다. 사용자는 의도를 담는 절에 서명한다: `reviewed "<메모>" sha256 "<지문>"`. 서명이 필요한 절은 발급 규칙, 허용 조건(`ends_only_if`, `unbacked_only_if`), trusted fun이다 (DESIGN-v0.md 8.3 U7과 "reviewed 지문").

### 1.2 왜 이 단계가 필요한가

AI-CODE.md 10절의 결과: AI 구현 12개가 모두 README의 "once `lockTimeoutMillis` has passed"를 `>=`로 읽었다. 서명된 sidecar는 `now > lease`다. 자연어 명세는 경계를 정하지 않았고, 형식 명세가 정했다. 사용자는 그 뒤 엄격한 `>`를 골랐다 (AI-CODE.md 11절).

그래서 이 단계의 일은 두 가지다.
1. 자연어에서 형식 절의 초안을 만든다.
2. 자연어가 정하지 않은 결정을 찾아 사람에게 묻는다. 초안이 조용히 정해 버리면 안 된다.

파일럿은 2번이 실제로 일어남을 보였다. 초안 3개는 경계를 모두 `>=`로 정했다. 그런데 세 작성자 모두 시나리오 답에서는 그 칸을 `open`이라고 표시했다 (3.4절, 측정).

### 1.3 이 문서가 정하지 않는 것

1. 어휘 층(ghost record와 witness)을 자연어에서 자동으로 만드는 방법. 파일럿은 어휘를 고정해서 주었다. 새 프로토콜의 어휘는 지금처럼 설계 agent와 사람이 정한다 (2.1절).
2. `kv scaffold`(DESIGN-v0.md 8.3 J4)의 상세. 이 문서는 묶기 층이 코드나 팀의 모양 틀에서 온다는 것만 정한다.
3. Aspect 모듈의 파일럿. 파일럿은 KeyLocalLock 하나다.

## 2. 흐름과 역할

### 2.1 sidecar의 다섯 층

| 층 | 내용 | 출처 | 쓰는 쪽 | 기계 검사 | 파일럿 |
|---|---|---|---|---|---|
| 1 묶기 | `module`, `source`, `state`, `class`의 필드 묶기, `use`, `free ... = local/param/this/call`, 연산 선택자 | 코드, 또는 팀의 모양 틀(AI-CODE.md 4.1의 RULES.md) | `kv scaffold` 또는 사람. 자연어에서 쓰지 않는다 | `kv resolve` | SKELETON.kv로 고정해서 주었다. 초안 3개 모두 고정 줄을 바꾸지 않았다. haiku만 `// DRAFT` 표시 주석 5줄을 지웠다 (diff, 측정) |
| 2 신뢰 | `trusted fun`의 계약과 `reviewed` | 함수 본문 | 사람 (본문 지문) | `stale-review` | 고정 |
| 3 어휘 | `type`, `ghost record`와 필드 종류, `witness` | 프로토콜 틀 (예: 토큰이 있는 lease lock) | 설계 agent와 사람, 프로토콜마다 한 번 | resolver | 고정. 초안이 더 필요하다고 보고한 것은 4가지다: sweep을 부르는 probe, unLock의 시계, map 크기, liveness |
| 4 의도 | 발급 규칙, `ensures`(안전과 진행), 허용 조건, `invariant`, `lemma`, 결정된 시나리오(probe) | 자연어 명세 | LLM 초안, 사람이 결정하고 서명 | 4절 전체 | 이 문서의 대상 |
| 5 hole | `MI_INV` | 코드와 의도 | LLM (E3 흐름), 도구가 검사 | `kv hole check`, `kv verify` | 서명된 hole을 그대로 썼다. 결정을 맞춘 초안 3개가 모두 그 hole로 PROVEN이다 (측정) |

층에 대한 관찰 (파일럿):
1. **묶기 층의 이름과 주석이 의도를 끈다.** 경계 질문(Q2)에서 opus는 필드 이름 `expiresAt`을, sonnet은 SKELETON.kv의 gLease 주석("the time the acquisition expires")을 `>=`의 근거로 들었다. 그 주석은 이 파일럿에서 내가 쓴 것이다.
2. **묶기 층이 의도를 미리 정한다.** 골격의 unLock에는 시계 입력이 없다. 그래서 "만료된 뒤 unLock은 실패한다"(D2의 다른 선택지)는 쓸 수 없다. 세 작성자가 모두 이 점을 질문에 적었다 (haiku Q9, sonnet Q6, opus Q11).
3. **P2(permit 수 0..1)는 코드에서 온 성질이다.** 초안 3개 중 0개가 썼다. 자연어 명세에는 semaphore가 없다.

### 2.2 단계

1. **스냅숏.** 도구가 페이지를 블록으로 나누고, 블록마다 anchor와 지문을 단다 (`kv spec snapshot`, 5절, 6절). 이 단계는 로컬에서 돈다.
2. **골격.** 묶기·신뢰·어휘 층을 준비한다. 사람이 프로토콜마다 한 번 승인한다.
3. **초안 (1단계).** 독립 LLM 3개가 서로 다른 모델로 초안을 쓴다. 각자 `DRAFT.kv`(절과 출처 인용), `COVERAGE.md`(블록마다 어느 절이 담는지), `QUESTIONS.md`(정하지 않은 결정)를 낸다. 시나리오 표는 아직 주지 않는다. 그래야 질문 목록이 유도되지 않는다.
4. **문법 교정.** 도구가 문법·타입 오류 줄만 돌려준다 (최대 2회). 검증 결과는 돌려주지 않는다.
5. **시나리오 답 (2단계).** 같은 작성자에게 시나리오 표(3.2절)를 준다. 작성자는 칸마다 결과, 상태(`settled`, `assumed`, `open`), 인용을 쓴다 (`ANSWERS.md`). 초안은 고치지 않는다.
6. **기계 검사.** 도구가 4절의 검사를 돈다.
7. **질문 목록.** 도구가 3.3절의 규칙으로 칸을 고르고, 결정별로 묶는다. 질문마다 선택지, 인용, 초안별 선택, 선택지별 결과(어느 구현이 통과하고 어느 구현이 실패하는가)를 싣는다.
8. **결정.** 사람이 질문마다 답한다. LLM(교정 agent)이 고른 초안을 답에 맞춘다. 도구는 맞춘 초안이 답한 칸을 모두 그대로 정하는지 다시 잰다.
9. **서명.** 사람이 의도 절과 결정 probe에 서명한다 (`reviewed` + `source`).
10. **이후.** 페이지가 바뀌면 `kv spec stale`이 인용이 사라진 절, 블록이 바뀐 절, 새 블록을 보고한다.

### 2.3 역할

| 쪽 | 하는 일 | 하지 않는 일 |
|---|---|---|
| LLM | 초안, 범위표, 질문 목록, 시나리오 답, 결정에 맞춘 교정, hole | 서명, 결정 |
| 도구 | 스냅숏, 4절의 모든 검사, 질문 고르기, stale 판정 | 결정, 서명 |
| 사람 | 골격 승인(프로토콜마다), 질문에 답, 절·probe·제외 항목에 서명 | 절을 처음부터 쓰기 |

## 3. 모호성 드러내기

### 3.1 후보 방법

| ID | 방법 | 기대한 강점 | 파일럿에서 본 약점 (측정) |
|---|---|---|---|
| M1 | 독립 초안 여러 개를 절마다 비교 | 해석이 갈리면 보인다 | 같은 오독을 공유하면 보이지 않는다. D1에서 sweep 칸 C15는 세 초안이 모두 같은 답(dropped)이다 |
| M2 | LLM에게 정하지 않은 결정을 적게 함 (`QUESTIONS.md`) | 재현율이 높다 | 질문이 16-17개로 많다. D1의 확신도는 low, low, medium이었고, 세 초안 모두 서명되지 않은 쪽을 골랐다 |
| M3 | 구현 변형을 만들고 어느 성질이 그것을 가르는지 봄 | 결정의 결과가 구체적이다 | 구현끼리 같은 오독을 하면 가르지 못한다. AI 구현 12개는 D1에서 모두 같았다 (AI-CODE.md 10.1) |
| M4 | 시나리오 칸과 결정성 검사 (이 문서의 권고) | 칸은 사람이 읽기 쉽다. 형식 절이 칸을 정하는지 기계로 잰다 | 칸 목록이 담지 않는 결정은 보지 못한다 (예: D12 overflow) |
| M5 | 참조 구현과의 일관성 (코드가 있을 때) | 충돌이 곧 결정 지점이다 | 코드가 없는 명세 우선 흐름에서는 쓸 수 없다. 참조 구현의 선택이 의도라는 보장이 없다 |
| M6 | 결정 변이: 결정의 다른 선택지를 참조 구현에 넣은 변이 | 선택지별 결과를 보인다 | 결정을 먼저 알아야 만든다 |

### 3.2 시나리오 칸과 결정성 검사

**칸.** 칸 하나는 빈 모듈에서 시작하는 짧은 시나리오와, 그 끝에서 관찰하는 결과의 선택지다. 예: C04 "시각 0에 잡음(lockTimeoutMillis = 10), 풀지 않음, 시각 10에 tryLock → null / 새 토큰".

**생성 규칙 (파일럿에서 손으로 따른 규칙, C4에서 자동화).**
1. 준비: 발급 연산을 시각 0, timeout 10, 기본 입력으로 한 번 부른다. 결과는 entry가 받치는 토큰 `t1`이라고 둔다.
2. 연산마다, 연산 앞의 key 상태마다 칸을 만든다.
   - 상태: entry 없음; `t1`을 가진 entry. 시계를 받는 연산이면 시각 5(lease 전), 10(lease와 같음), 11(lease 뒤).
   - Bool 입력(`rej`): 두 값.
   - Token 입력: entry가 가진 토큰, 넘겨받힌 옛 토큰, unbacked 토큰, 발급된 적 없는 토큰.
3. 두 단계 시나리오: 주인을 바꾸는 단계(넘겨받기, 해제, monitor 삭제) 하나 뒤에 각 연산 한 번.
4. 결과 선택지: 연산 결과의 종류(null, backed 토큰, unbacked 토큰; true, false)와 key 상태(entry가 `t1`을 가짐, entry 없음).

KeyLocalLock에서 이 규칙으로 칸 20개를 만들었다 (`packet/SCENARIOS.md`, 초안이 나오기 전에 등록). C09(만료 뒤 unLock)는 unLock에 시계가 없어서 형식상 C08과 같다. 그래서 형식 칸은 19개다.

**결정성 검사.** 도구는 초안의 형식 절만으로 칸이 정해지는지 잰다.
1. 초안으로 Viper 모델을 만든다. 연산 메서드의 본문을 지운다. 그러면 연산 호출은 계약만 가정한다.
2. 칸마다 메서드 셋 이상을 붙인다: 결과 선택지마다 `assert 결과`, 그리고 `refute false`.
3. Silicon을 한 번 돌린다 (서명된 sidecar에서 칸 메서드 62개, 16.6 s, 측정). `assert o`가 검증되면 DETERMINED(o)다. 어느 것도 검증되지 않고 끝에 닿을 수 있으면 OPEN이다. 끝에 닿을 수 없으면 INFEASIBLE이다 (계약이 시나리오의 준비와 모순).
4. 두 방식으로 잰다. 방식 a는 hole을 넣는다(구현의 귀납 불변식까지). 방식 b는 hole을 `true`로 둔다(절만).

파일럿에서 고친 점 (측정, 초안 점수를 매기기 전): 처음 하네스는 도구가 계약에 적지 않는 사실 세 가지를 몰랐다. 그래서 C20(모든 sidecar)과 C02, C13(초안 3개)이 거짓으로 OPEN이 나왔다. 서명된 sidecar는 cap_unbacked와 P1a에 gBacked를 직접 적었기 때문에 C02, C13이 처음부터 정해졌다.
- FRAME: action은 자기 key의 map 항목만 바꾼다.
- END: key의 entry가 더 이상 가지지 않는 토큰의 획득은 끝난다.
- ISSUE: 발급 규칙의 값.
세 사실을 계약에 더한 모델을 본문과 함께 검증했다 (Silicon 9.65 s, 검증됨). 그래서 이 사실들은 하네스의 가정이 아니라 증명된 사실이다. 도구에서는 emitter가 이 사실을 계약으로 낸다 (7.3절, C2).

**파일럿 결과 (측정, 방식 a).**

| 칸 | 내용 | 서명된 sidecar | haiku | sonnet | opus | 결정을 맞춘 초안 3개 |
|---|---|---|---|---|---|---|
| C04 | 시각 10(= lease)에 tryLock | null | 토큰 | null | 토큰 | null |
| C05 | 시각 11에 tryLock | 토큰 | 토큰 | null | 토큰 | 토큰 |
| C06, C07 | C05 뒤 옛 주인/새 주인의 unLock | false, true | false, true | INFEASIBLE | false, true | false, true |
| C15 | 시각 10에 monitor | 남음 | 지움 | 지움 | 지움 | 남음 |
| C16 | 시각 11에 monitor | **OPEN** | 지움 | 지움 | 지움 | 지움 |
| C19 | map이 꽉 찬 상태, 시각 11에 tryLock | backed 토큰 | backed | null | backed | backed |
| 나머지 12칸 | | DETERMINED | 서명과 같음 | 서명과 같음 | 서명과 같음 | 서명과 같음 |

방식 b(hole 없음): 서명된 sidecar는 C05, C16, C19가 OPEN이다. 그 진행 조건이 entry 필드 `expiresAt`로 쓰여 있고, `expiresAt`와 lease의 연결은 hole에만 있기 때문이다. 결정을 맞춘 초안 3개는 방식 b에서도 19칸을 모두 정한다. 초안은 그 연결을 의도 절로 썼다 (3.5절).

### 3.3 질문 규칙

칸은 다음 중 하나라도 맞으면 질문이 된다.
1. 한 작성자라도 `open`이라고 답했거나, 두 작성자 이상이 `assumed`라고 답했다.
2. 두 초안이 칸을 다르게 정한다 (다른 DETERMINED, 또는 한쪽이 INFEASIBLE).
3. 초안이 칸을 정하는데, 그 작성자의 답이 다르거나 `open`이다.
4. 참조 구현이 있고, 칸을 정하는 초안의 절이 참조 구현에서 실패한다 (4.1절의 절 분리 검사).
5. 칸이 경계 칸이다 (시계 = lease).

그다음 칸을 결정별로 묶는다. v1에서는 LLM이 묶고 이름을 붙인다. 도구는 질문 칸이 모두 정확히 한 묶음에 들어갔는지 검사한다.

파일럿에 적용한 결과 (측정, `tools/questions.py`가 규칙 1-5를 기계로 적용): 질문 칸은 C04, C05, C06, C07, C09, C12, C15, C19의 8개다. 묶음은 4개다 (묶기는 손으로 했다).
규칙 4는 파일럿에서 대리 지표로 쟀다: "초안이 칸을 서명된 sidecar(원본에서 증명됨)와 다르게 정한다". C16처럼 서명된 sidecar가 정하지 않은 칸에는 규칙 4를 적용하지 않았다.

| 묶음 | 칸 | 걸린 규칙 | 서명된 선택 |
|---|---|---|---|
| D1 만료 경계 | C04, C15 | C04: 1-5. C15: 1, 3, 4, 5 (세 초안이 같은 답이라 2는 걸리지 않음) | 엄격: lease 시각에는 아직 잡혀 있다 |
| D4 만료 lock 넘겨받기 | C05, C06, C07, C19 | C05, C19: 1-4. C06, C07: 2, 4 (sonnet이 INFEASIBLE) | tryLock이 바로 넘겨받는다 |
| D2 만료 뒤 주인의 unLock | C09 | 1 (assumed 3/3) | true |
| D5 unbacked 토큰의 unLock | C12 | 1 (assumed 3/3) | false |

규칙 1의 문턱 때문에 haiku 혼자 `assumed`라고 한 C03, C14, C16은 질문이 되지 않았다. D9(monitor 의무)는 질문이 아니다. 세 초안과 세 답이 모두 텍스트대로 "지운다"이기 때문이다. D9는 서명된 sidecar와 비교할 때만 드러나는 빈틈이다.

### 3.4 방법별 측정

결정 목록(D1-D12)은 초안이 나오기 전에 `PREREG.md`에 등록했다. 표의 "3/3"은 초안 세 개 중 몇 개인지다.

| 결정 | 서명된 선택 | 초안의 선택 (haiku / sonnet / opus) | M2 질문 목록 | 시나리오 답 | M1 초안끼리 칸이 다름 | 초안과 자기 답이 다름 | M5 참조 구현과 충돌 |
|---|---|---|---|---|---|---|---|
| D1 만료 경계 | `>` | `>=` / `>=` / `>=` | 3/3 (확신도 low, low, medium) | C04 open 3/3; C15 open 2, assumed 1 | C04 예 (D4 때문), C15 **아니오** | 3/3 | 3/3 (절 6개) |
| D2 만료 뒤 unLock | true | true / true / true | 3/3 | C09 assumed 3/3 | 아니오 | 아니오 | 아니오 |
| D4 만료 lock 넘겨받기 | 넘겨받음 | 넘겨받음 / **안 받음** / 넘겨받음 | 3/3 | C05, C19 open 3/3 | 예 (C05, C06, C07, C19) | 3/3 | sonnet (절 3개) |
| D5 unbacked 토큰의 unLock | false | false ×3 | 3/3 | C12 assumed 3/3 | 아니오 | 아니오 | 아니오 |
| D3 상한 경로 | unbacked 토큰 | 같음 ×3 | 2/3 (sonnet Q8, opus Q7, 확신도 high) | C02 settled 3/3 | 아니오 | 아니오 | 아니오 |
| D9 monitor가 지움 | 정하지 않음 | 지움 ×3 | 3/3 | C16 settled 2, assumed 1 | 아니오 | 아니오 | 아니오 |
| D12 overflow | 모델 밖 | - | 0/3 | - | - | - | - |

관찰:
1. D1에서 초안끼리의 비교(M1)는 sweep 칸을 놓쳤다. 세 초안이 같은 오독을 했기 때문이다. 이것은 AI 구현 12개가 모두 `>=`를 고른 것과 같은 현상이다.
2. 세 작성자는 C04를 `open`으로 답하면서, 자기 초안에서는 C04를 정했다. 형식 초안은 열린 결정을 조용히 닫는다. 결정성 검사가 이것을 드러낸다 (규칙 3).
3. D12는 아무 방법도 드러내지 못했다. 파일럿의 LANGUAGE.md가 "정수는 넘치지 않는다"고 미리 말했기 때문이다. 도구의 가정(보고서의 Assumptions)도 질문 목록에 실어야 한다 (9.2 JC8).
4. 자기 보고(M2)의 합집합은 결정 목록 12개 중 11개(D1-D11)를 담았다. 초안 하나는 9-11개를 담았다 (haiku 9: D3, D11 없음; sonnet 11; opus 10: D8 없음). 그러나 질문 수가 16-17개이고, 목록 밖의 질문도 많다 (재진입, 0 이하 timeout, 거절된 호출의 lease 연장 등). 그중 lease 연장(haiku Q16, sonnet Q4, opus Q3)은 3.5절의 D13으로 이어졌다.

### 3.5 결정을 sidecar에 남기기

사람의 답은 이름 있는 probe로 sidecar에 들어간다. 예 (7.2절의 문법):

```text
probe expiry_boundary(k: Key, a: Int, d: Int) requires d > 0 {
  t1 := tryLock(k, now = a, timeout = d, rej = false, limit = 0)
  t2 := tryLock(k, now = a + d, timeout = d, rej = false, limit = 0)
  assert t2 == NULL
}
  source "README.md#local-lock-map-size/2" quote "once `lockTimeoutMillis` has passed"
  reviewed "<사람> <날짜>: strict: at exactly the lease end the lock is still held" sha256 "<fp>"   // 예시이고 서명이 아니다
```

probe는 연산의 계약과 hole로만 증명된다. hole은 그 코드에서 귀납성이 증명된다. 그래서 probe가 PROVEN이면, 검증한 코드는 그 시나리오에서 답한 대로 동작한다.

**측정: 결정 probe가 hole 뒤의 의도를 지킨다.** 변이 D14b는 만료 시각을 `now + lockTimeoutMillis + 1000L`로 둔다 (두 곳). 풀지 않은 lock이 lockTimeoutMillis보다 1 s 더 남는다. README의 "dropped by the cleanup monitor once `lockTimeoutMillis` has passed"를 어긴다.

| sidecar \ 코드 | 원본 | D14b |
|---|---|---|
| 서명된 sidecar | PROVEN | FAILED (invariant only). 보고서가 "Next: re-derive the hole for this code"라고 권한다 |
| 서명된 sidecar, hole conjunct 하나를 `gLease == expiresAt`에서 `gLease <= expiresAt`로 바꿈 | PROVEN | **PROVEN** (놓침) |
| 서명된 sidecar + 결정 probe C05 (lease + 1에 tryLock이 토큰을 받음), 원래 hole | PROVEN | FAILED (invariant only) |
| 서명된 sidecar + 결정 probe C05, 바꾼 hole | FAILED (probe: hole이 약함) | FAILED (probe) |
| 결정을 맞춘 초안 3개, 원래 hole | PROVEN | FAILED (property): haiku `grant_lease`, sonnet `inv_entry_expiry_is_lease`, opus `inv_held_backed_token_carried_with_lease` |

결과:
1. 지금 sidecar에서는 "만료 시각 = lease"가 hole(MI_INV_4)에만 있다. AI-CODE.md 6.3의 흐름처럼 hole을 다시 쓰면 이 의도가 사라질 수 있다.
2. 결정 probe가 있으면 어느 hole로도 D14b가 PROVEN이 되지 않는다. 원래 hole이면 action이 실패하고, 바꾼 hole이면 probe가 실패한다.
3. 초안 3개는 이 연결을 의도 절로 썼다. 그래서 hole과 무관하게 성질 위반이다.

같은 종류의 변이 D13(거절된 tryLock이 lease를 연장)도 서명된 sidecar에서는 "invariant only"이고, 결정을 맞춘 haiku에서는 성질 위반(`tryLock_null_keeps_owner`)이다 (측정).

### 3.6 질문의 모양

질문마다 다음을 싣는다. 예는 D1이다.

```text
Q-D1 만료 시각과 정확히 같은 1 ms에, 풀지 않은 lock은 아직 잡혀 있는가? (칸 C04, C15)
근거: [README.md#local-lock-map-size/2] "once `lockTimeoutMillis` has passed"
A. 아직 잡혀 있다: C04 null, C15 entry가 남음.     지금 서명, 참조 구현.
B. 이미 만료다:    C04 새 토큰, C15 entry를 지움.  초안 3/3, AI 구현 12/12 (AI-CODE.md 10.1).
B를 고를 때의 결과 (측정): 참조 구현이 FAILED가 된다. AI 구현 T-sonnet-1과 T-sonnet-4는 모듈 전체가 PROVEN이 된다.
A를 고를 때의 결과 (측정): 위 두 AI 구현은 sweep의 P3a로 FAILED다.
답의 기록: probe expiry_boundary (3.5절)
```

선택지별 결과는 결정 변이와 AI 구현을 두 초안(선택지 A, B)으로 돌려서 얻는다 (4.1절 판별력).

### 3.7 한계

1. 파일럿은 모듈 하나, 초안 셋, 칸 20개다. 비율은 일반화하지 않는다.
2. 칸 목록이 담지 않는 결정은 질문이 되지 않는다 (D12). 그래서 자기 보고(M2)와 도구의 가정 목록도 질문 후보로 함께 보인다.
3. 묶기 층과 언어 설명이 답을 끌었다 (2.1절 관찰 1, 2; D12). 실제 도입에서는 모양 틀의 이름과 주석도 검토 대상이다.
4. 결정을 맞춘 초안은 내가 문자열 편집으로 만들었다 (부록 A). sonnet은 sonnet 자신이 Q1에 적은 선택지 B의 절을 그대로 썼다. 교정 agent는 아직 시험하지 않았다.
5. 2단계 답은 1단계와 같은 agent가 썼다. 작성자들은 1단계 파일을 다시 열지 않았다고 보고했다. 그러나 맥락에는 남아 있다.

### 3.8 권고와 대안

| 안 | 내용 | 파일럿 근거 (측정) | 판단 |
|---|---|---|---|
| 권고 | 초안 3개 + 시나리오 칸 + 결정성 검사 + 질문 규칙 5개 + 결정 probe | 질문 칸 8개, 결정 4개. 모두 실제 결정이다. D1을 경계 규칙과 open 답으로 잡았다 | 채택 |
| 대안 A | 초안 1개 + 자기 보고만 | 결정 9-11/12를 담는다. 질문 16-17개. D1은 질문에 있으나 기본값이 서명과 반대다 | 싸지만 사람이 걸러야 할 질문이 많다 |
| 대안 B | 초안 여러 개의 절 비교만 (M1) | D4는 잡고, D1의 sweep 칸은 놓친다 (같은 오독) | 단독으로는 부족하다 |
| 대안 C | 자연어 요구 목록(구조화한 영어)을 사람이 먼저 검토하고, 그다음 형식화 | 시험하지 않았다. 칸이 같은 역할(구체적인 중간 표현)을 한다 | 칸으로 대신한다 |
| 대안 D | 구현 변형 생성 (M3) | AI 구현 12개가 D1에서 같았다 (AI-CODE.md 10.1) | 결정 변이(M6)로 선택지별 결과를 보이는 데만 쓴다 |

위험:
1. 칸 생성 규칙은 lease lock에 맞춘 것이다. 다른 프로토콜에서는 칸이 결정을 덜 담을 수 있다. C4의 Aspect 시험이 이것을 잰다.
2. 명세 우선 흐름(코드 없음)에서는 방식 b만 된다. 서명된 sidecar처럼 진행 조건이 필드로 쓰여 있으면 방식 b가 약하다 (서명: OPEN 3칸). 필드 고정 경고(4.1절)가 이것을 줄인다.
3. 비용: 페이지 하나에 초안 3개, 각 약 20만 토큰과 22-28분 (측정).
4. 교정 agent(결정에 맞춰 초안을 고치는 LLM)는 시험하지 않았다. 도구가 맞춘 초안을 칸으로 다시 재므로, 잘못 고치면 드러난다.

## 4. 사람에게 보이기 전의 기계 검사

### 4.1 검사 목록

| 검사 | 지금 도구가 주는 것 | 새로 필요한 것 | 파일럿 (측정) |
|---|---|---|---|
| 문법·타입 | `kv verify` 종료 코드 4와 sidecar 위치. 식 타입은 Silicon이 검사 | 코드 없이 문법만 보는 방식. 서명 전 초안을 받는 `--draft` 방식 (지금은 서명 없는 허용 조건과 발급 규칙이 오류다) | sonnet, opus는 첫 시도에 통과. haiku는 2회 교정: probe에서 sweep을 부를 문법이 없다 (`expected ':='`, 다음에 `operation sweep returns nothing`) |
| 인용 | 없음 | 인용이 anchor 블록에 글자 그대로 있는지 (공백 정규화) | DRAFT.kv 인용 114개 모두 일치. 작성자가 스스로 검사한 결과다. 인용이 절을 뒷받침하는지는 검사하지 않는다 (예: haiku는 넘겨받기 절에 "Keys whose entry is already in"을 인용) |
| 범위 | 없음 | 범위 안 블록마다 절이 있거나, 이유와 함께 제외됨 | 세 초안이 규범 블록으로 고른 것: 24, 20, 21개. 셋 모두가 고른 것 20개, 합집합 24개 |
| 일관성 (참조 구현) | `kv verify` | 절 하나씩 검사: Silicon은 경로마다 처음 실패한 절만 보고한다 | 초안 3개 모두 FAILED. 실패한 절 9개(48개 중)가 모두 D1(6개) 또는 D4(3개)다. 결정을 맞추면(6-7곳 편집) 3개 모두 PROVEN |
| 공허하지 않음 | probe, `progress_*`가 없으면 경고, M9 분기 도달성 | 결정성 검사의 INFEASIBLE 칸 (계약끼리 모순) | INFEASIBLE은 sonnet의 C06, C07뿐이다. 넘겨받기가 없으므로 "넘겨받은 뒤"가 불가능하다. 결정의 결과이고 모순이 아니다 |
| 결정성 | 없음 | 3.2절 | 3.2절 표 |
| 필드 고정 | 없음 | `ensures`의 전제가 entry 필드(witness 말고)를 읽으면, 그 필드를 ghost 값이나 발급 규칙의 값에 묶는 의도 절이 있어야 한다. 없으면 경고한다. 그 필드의 뜻이 hole에만 있다는 뜻이기 때문이다. 필드 자체의 성질(P2)은 대상이 아니다 | 서명된 sidecar: `progress_live`, `expired_takeover`가 `expiresAt`를 읽고, `expiresAt`와 `gLease`의 연결은 hole에만 있다 (경고 대상, D14). 초안 3개는 연결 절이 있다 |
| 판별력 | `mutants.py`(원본과 손으로 고른 변이 10개), `mechanical.py`(규칙 변이) | 결정 변이, AI 구현을 같은 표로. 서명된 sidecar의 결과와 비교 | 아래 |
| hole | `kv hole skeleton`, `kv hole check` | 그대로 | 서명된 hole이 결정을 맞춘 초안 3개에서 그대로 통했다 |

**판별력 (측정).** 대상은 coroutine 변형의 뿌리 91개다: 원본과 손으로 고른 변이 10개, 기계적 변이 66개(rerun 결과에서 PROVEN 또는 FAILED였던 것), 결정 변이 7개, AI 구현 7개(round 2에서 sweep이 판정된 것).
- 서명된 sidecar (기준, 89개): FAILED 65, PROVEN 24. 기계적 변이 66개의 판정이 `mutation/rerun/results.jsonl`과 모두 같다 (불일치 0). 743 s.
- 결정을 맞춘 haiku (91개): 서명된 sidecar가 잡은 65개를 모두 잡는다. PROVEN 24개 중 22개는 PROVEN 그대로다. 나머지 2개(RACY-09, D9-sweep-never)는 monitor 의무(D9)로 새로 잡힌다. D13, D14는 성질 위반이다. 564 s.
- 결정을 맞춘 sonnet (91개): haiku와 같다. 65개를 모두 잡고, 22개는 PROVEN 그대로, RACY-09와 D9-sweep-never를 새로 잡는다. D13, D14는 성질 위반(`inv_entry_expiry_is_lease`)이다. 1499 s.
- 결정을 맞춘 opus (91개): haiku, sonnet과 같다 (부록 B.4).
- 결정 전 초안 (haiku, 89개): 원본이 FAILED다. 그래서 서명된 sidecar에서 PROVEN이던 24개(대조군 포함)가 모두 FAILED다. 모두 원본과 같은 경계를 가졌기 때문이다. 판별력 검사는 결정 뒤에 돌려야 뜻이 있다 (4.2절).

결정 변이와 AI 구현 (측정, 모듈 판정):

| 뿌리 | 서명된 sidecar | haiku (결정 전) | sonnet (결정 전) | opus (결정 전) |
|---|---|---|---|---|
| D1-both (tryLock, sweep 모두 `>=`) | FAILED (P3a) | **PROVEN** | FAILED (tryLock은 넘겨받기 금지) | **PROVEN** |
| D4-no-takeover | FAILED (expired_takeover) | FAILED (경계) | tryLock PROVEN, sweep FAILED (경계) | FAILED (경계) |
| D9-sweep-never | **PROVEN** | FAILED | FAILED | FAILED |
| ai:T-sonnet-1, ai:T-sonnet-4 | FAILED (P3a) | **PROVEN** | FAILED (넘겨받기) | **PROVEN** |

### 4.2 순서와 실패 시 동작

1. 문법·타입이 실패하면 오류 줄만 작성자에게 돌려준다. 2회 안에 통과하지 못하면 그 초안을 버리고 보고한다.
2. 인용 검사가 실패하면 그 절을 "출처 없음"으로 표시한다. 절은 버리지 않는다.
3. 일관성 검사의 실패는 오류가 아니라 질문의 근거다 (규칙 4).
4. 판별력 검사는 사람이 결정을 마친 뒤 돈다. 결정 전에는 결정 변이와 AI 구현만 돌린다. 그 결과는 질문의 "선택지별 결과" 칸에 들어간다.

## 5. 추적성

### 5.1 지금의 지문

`reviewed ... sha256`은 검토한 텍스트를 묶는다. trusted fun이면 Kotlin 선언이고, 발급 규칙과 허용 조건이면 sidecar의 절 텍스트다 (DESIGN-v0.md 8.3 "reviewed 지문"). 정규화는 공백 묶음을 공백 하나로 바꾸고 SHA-256 앞 16자리를 쓴다 (`Fingerprint.kt`).

이 지문의 성질:
1. 절을 고치면 `stale-review`가 난다. 형식 쪽의 변경은 잡는다.
2. 자연어 쪽의 변경은 잡지 않는다. README 118행이 바뀌어도 아무 절도 오래되었다고 표시되지 않는다.
3. 지문은 텍스트의 지문이다. tryLock의 P3a와 sweep의 P3a는 텍스트가 같아서 지문이 같다 (`1c0ae67dbfe9757d`, `keylocallock.common.kv`). 그래서 지문으로 출처를 구별할 수 없다.

### 5.2 제안: `source`

의도 절 뒤에 출처 줄을 하나 이상 단다.

```text
  ends_only_if P3a: now > old(tok(qt).gLease)
    source "README.md#local-lock-map-size/2" quote "once `lockTimeoutMillis` has passed" sha256 "<블록 지문>"
  reviewed "<사람> <날짜>: ..." sha256 "<절과 출처 줄의 지문>"
```

규칙:
1. anchor는 스냅숏의 블록이다: `<원본 이름>#<절 제목 slug>/<블록 번호>`, KDoc이면 `<파일>#<선언 이름>`. Confluence면 원본 이름에 페이지 id를 쓴다.
2. `quote`는 그 블록의 일부를 글자 그대로 옮긴 것이다. 공백만 정규화한다.
3. `sha256`은 서명할 때 블록 전체의 지문이다.
4. `reviewed`의 지문 단위에 출처 줄을 넣는다. 그래서 인용을 바꾸면 다시 서명해야 한다. `stale-source`를 끄려고 출처 줄을 고치면 `stale-review`가 난다.

### 5.3 stale 규칙 (`kv spec stale`)

| 새 스냅숏에서 | 판정 |
|---|---|
| anchor 블록에 인용이 그대로 있고 블록 지문도 같다 | 그대로 |
| 인용은 그대로지만 블록 지문이 다르다 (같은 블록의 다른 문장이 바뀜) | 경고 `source-context-changed`: 사람이 읽어 보기를 권한다 |
| 인용이 anchor 블록에는 없고 다른 블록에 있다 | 경고 `source-moved`: anchor를 새 블록으로 고치라고 제안한다 |
| 인용이 어디에도 없다 | 오류 `stale-source` (종료 코드 4): 사람이 다시 검토해야 한다 |
| 범위 안(`scope`)의 새 블록을 어느 절도 인용하지 않고, 제외 목록에도 없다 | 오류 `uncovered-block` (C7을 넣을 때만) |

### 5.4 범위표

범위표는 "이 sidecar가 책임지는 블록"과 "담지 않은 블록과 그 이유"다. 파일럿의 `COVERAGE.md`가 그 초안이다. 담지 않은 이유는 셋 중 하나였다: 모델 밖(로그, 설정 파싱, 다른 클래스), 언어로 쓸 수 없음(시간 간격, liveness, map 크기), 정보용 예시. 사람은 제외 항목에도 서명한다. 서명이 없으면 빠진 절과 일부러 뺀 절을 구별할 수 없다. 서명된 sidecar의 cap_unbacked, expired_takeover가 나중에 더해진 것(HISTORY.md 2026-10-24)이 그 예다.

## 6. Confluence 받아들이기

### 6.1 선택지

| 방법 | 내용 | 장점 | 약점 |
|---|---|---|---|
| Markdown 파일 | 저장소나 로컬의 `.md` | 지금 바로 된다. 파일럿의 스냅숏이 이것이다 | Confluence 원본과 어긋날 수 있다 |
| 수동 내보내기 | Confluence의 페이지 내보내기 (Word, PDF, HTML) | 네트워크 설정이 없다 | Markdown 내보내기는 기본 기능이 아니다 (확인하지 않음). 사람이 매번 해야 한다 |
| REST API (로컬 스크립트) | Cloud: `GET /wiki/api/v2/pages/{id}?body-format=storage` (커뮤니티 답변으로 확인, 공식 문서는 끝까지 읽지 못함). Data Center: `GET /rest/api/content/{id}?expand=body.storage` (공식 예). Data Center 7.9부터 personal access token | 페이지 id와 버전이 함께 온다. 자동으로 다시 받을 수 있다 | 토큰이 필요하다 |
| Atlassian 커넥터 (MCP) | Claude가 OAuth로 페이지를 읽는다 | 토큰을 다루지 않는다 | 이 환경에는 없다. 조직이 허용해야 한다 (확인하지 않음) |

### 6.2 v1

1. Markdown 파일을 그대로 받는다.
2. Confluence는 로컬 스크립트(`scripts/confluence_fetch.py`)로 받는다.
   - 입력: 페이지 id 목록, 사이트 주소, 판(Cloud 또는 Data Center).
   - 토큰은 macOS Keychain에서 실행할 때 읽는다 (`security find-generic-password`). 화면, 로그, 파일에 쓰지 않는다. Claude는 토큰 값을 읽지 않는다.
   - 출력: storage XHTML 원문, 페이지 id, 버전 번호, 제목, 받은 시각.
3. `kv spec snapshot`이 storage XHTML을 블록으로 나눈다. 제목, 문단, 목록 항목, 표 행, code 매크로, info 패널, expand 매크로를 다룬다. 모르는 매크로는 원문 그대로 블록 하나로 두고 경고한다.
4. anchor의 원본 이름은 `conf:<페이지 id>`다. 페이지 버전은 스냅숏 머리에 적는다.

### 6.3 비밀과 외부 서비스

1. 토큰은 Confluence 호스트로만 간다. 그 밖의 서비스로는 보내지 않는다.
2. 페이지 텍스트는 초안 단계에서 Anthropic API로 간다 (LLM). 그 전에 로컬 비밀 검사(키, 토큰, 비밀번호 모양의 정규식)를 돈다. 걸린 블록은 가리고, 목록을 사람에게 보인다.
3. 페이지 텍스트를 Jev 같은 다른 서비스로 보내지 않는다 (v1). 바꾸려면 사용자가 정한다 (9.1 UC7).
4. 스냅숏, 검사, Silicon은 모두 로컬에서 돈다.

## 7. 도구와 문법의 변화

### 7.1 명령

| 명령 | 언어 | 하는 일 |
|---|---|---|
| `kv spec snapshot --out DIR NAME=FILE...` | Kotlin | Markdown, KDoc, (C6부터) storage XHTML → 블록, anchor, 지문 |
| `kv spec check [--root DIR] --snapshot DIR [--cells FILE] [--draft] SIDECAR` | Kotlin | 문법·타입, 인용, 범위, 결정성(칸), 절 분리 일관성(`--root`가 있을 때). 보고서와 JSON |
| `kv spec stale --snapshot DIR SIDECAR` | Kotlin | 5.3절 |
| `scripts/spec.py` (`task`, `collect`, `questions`, `align`, `outcomes`) | Python | 초안 과제 상자(aicode.py와 같은 방식), 수집, 교정 2회, 질문 규칙과 묶음, 교정 agent 과제, 결정 변이·AI 구현 실행 |

LLM 실행은 Claude Code agent가 맡는다. 도구는 과제 상자와 검사만 만든다. hole 흐름(M7)과 같은 분담이다.

### 7.2 문법 변화

```text
decl     := ... v0, B ...
          | "probe" [ NAME ] "(" PARAMS ")" [ "requires" VEXPR ] "{" { step } "}" { srcline } [ "reviewed" ... ]
                                         // C: a probe may have a name; a sidecar may have many probes
step     := [ NAME { "," NAME } ":=" ] NAME "(" ARGS ")"     // C: a call of an operation without a result
          | "assert" VEXPR
srcline  := "source" STRING "quote" STRING [ "sha256" STRING ]   // C: after an issue rule, ensures, allowance
                                                                 // clause, invariant, lemma, probe, assume or
                                                                 // set-once (10.14)
decl     := ...
          | "scope" STRING                        // optional C7: a snapshot section this sidecar answers for
          | "exclude" STRING STRING [ "reviewed" ... ]   // optional C7: a block in scope that no clause encodes, and why
```

1. 이름 없는 `probe(...)`는 지금처럼 쓴다 (이름 `probe`).
2. `reviewed`는 probe, `ensures`, `invariant`, `lemma`에도 붙을 수 있다. 필수인지는 사용자가 정한다 (9.1 UC4).
3. 파일럿에서 문법이 막은 것은 하나다: 결과 없는 연산을 probe에서 부를 수 없다. 세 작성자가 모두 이 문제를 적었다.

### 7.3 emitter 변화: 계약 사실

action 메서드의 계약에 도구가 아는 사실을 더한다. 본문과 함께 검증한다.

| 이름 | 식 (KeyLocalLock) | 왜 |
|---|---|---|
| FRAME | `forall qk: Key :: qk != k ==> has(S, qk) == old(has(S, qk)) && (has(S, qk) ==> S.m[qk] == old(S.m[qk]))` | 다른 key의 칸(C20)을 정한다. L3 클라이언트도 쓴다 |
| END | entry가 더 이상 가지지 않는 토큰은 `!gHeld`; entry가 가지지 않던 held 토큰은 held로 남음 | ghost 관찰을 계약으로 |
| ISSUE | `res != NULL ==> !old(gIssued) && gIssued && gKey == k && gBacked == carried(S, k, res) && gHeld && gLease == now + timeout` | 발급 규칙의 값을 계약으로. 지금의 P1a, P1a_entry는 이것을 손으로 다시 쓴 것이다 |
| WITNESS | `forall t :: gIssued && gBacked && gHeld ==> carried(S, gKey, t)` (불변식) | 초안 3개가 모두 이것을 직접 썼다. ghost 규칙에서 나오므로 도구가 만든다 (`MI_OWN`과 같은 종류) |

파일럿에서 FRAME, END, ISSUE를 원본 본문과 함께 검증했다 (Silicon 9.65 s, 검증됨). WITNESS는 서명된 hole의 MI_INV_5와 같은 식이고, 원본에서 이미 증명된다.
영향: 생성 모델이 바뀌므로 gate 4개의 목표 모델(`spike/effect_ghost/target.vpr` 등)을 고쳐야 한다. 이 파일들은 메인 세션이 관리한다 (9.2 JC4).
주의 (메인 세션, 2026-10-25): Silicon은 경로마다 처음 실패한 사후조건만 보고한다. 그래서 계약 사실을 더하면 절 순서가 바뀌고, 변이의 판정은 같아도 `scripts/mutants-expected.txt`와 `scripts/aspect-mutants-expected.txt`의 실패 의무 ID가 바뀔 수 있다. C2의 수락 테스트 2는 판정만이 아니라 이 두 파일을 다시 재고 바뀐 ID를 하나씩 설명해야 끝난다.

### 7.4 바뀌지 않는 것

KVIR, lowering, ghost binder, hole 흐름, 판정 규칙, 종료 코드, 기존 sidecar 문법의 뜻. 새 문법 없이 쓴 기존 sidecar 8개(`specs/reqshield/*.kv`)는 그대로 읽힌다.

## 8. 마일스톤

feature-developer가 순서대로 구현한다. 마일스톤은 수락 테스트가 통과해야 끝난다. 추정은 FIR과 Viper를 아는 사람 엔지니어 1명 기준이다. AI agent 세션의 시간은 비교할 수 없다 (파일럿의 초안 1회는 22-28분, 측정).

| C | 내용 | 수락 테스트 | 추정 (인일) |
|---|---|---|---|
| C0 | `kv spec snapshot` (Markdown, KDoc), `source` 줄, 인용 검사, `kv spec stale` | 1. 파일럿 원문에서 블록 128개와 지문이 `tools/snapshot.py`와 같다. 2. 파일럿 초안의 인용 114개가 통과하고, anchor가 틀린 인용과 바꿔 쓴 인용은 절 위치와 함께 실패한다. 3. README 118행을 고친 복사본에서 그 블록을 인용한 절만 `stale-source`다. 같은 블록의 다른 문장만 고치면 경고다. 4. 인용을 고치면 `stale-review`다 | 1.5-2.5 |
| C1 | probe 문법: 이름, 여러 개, 결과 없는 호출, `reviewed`와 `source` | 1. haiku의 처음 probe(`sweep(key, now = a + 3000)`)가 읽히고 출력된다. 2. probe 3개인 sidecar가 메서드 3개를 낸다. 3. D1, D4의 결정 probe가 원본에서 PROVEN이고, D1-both와 D4-no-takeover에서 각각 FAILED다. 4. 3.5절 표의 다섯 줄이 재현된다 | 1-2 |
| C2 | emitter 계약 사실 (FRAME, END, ISSUE, WITNESS) | 1. 여섯 모듈의 원본에서 사실이 본문과 함께 검증된다. 2. 손으로 고른 변이 63회의 판정이 바뀌지 않는다. 3. gate 4개를 새 목표 모델로 고친 뒤 exit 0 (메인 세션) | 1.5-3 |
| C3 | `kv spec check`: 계약만의 출력(코드 없이), 칸의 결정성, 절 분리 일관성, 필드 고정 경고, `--draft` | 1. 3.2절 표와 부록 B.2(서명, 결정 전 초안 3개, 결정을 맞춘 초안 3개, 방식 a·b)가 그대로 나온다. 2. 결정 전 초안 3개에서 실패한 절 9개가 나온다. 3. `--root` 없이도 방식 b가 돈다. 4. 서명된 sidecar에 필드 고정 경고 2개(`progress_live`, `expired_takeover`)가 나오고, 결정을 맞춘 초안 3개에는 나오지 않는다 | 2.5-4 |
| C4 | 칸 생성 (3.2절 규칙), 질문 규칙과 묶음 (3.3절), 검토 묶음(Markdown) | 1. KeyLocalLock에서 파일럿 칸 20개(또는 그 상위 집합)가 나온다. 2. 파일럿 자료에서 질문 칸이 정확히 8개, 묶음이 4개다. 3. Aspect 모듈에서 칸이 나오고, 서명된 aspect sidecar가 그 칸을 모두 정한다 | 2-3 |
| C5 | `scripts/spec.py` (과제 상자 3개, 수집, 교정 2회, 교정 agent 과제, 결정 변이·AI 구현 실행) | 단계마다 명령 하나로 파일럿을 처음부터 다시 돌릴 수 있다. 교정 회차에는 오류 줄만 간다 (테스트가 보고서 문구가 없음을 확인) | 2-3 |
| C6 | Confluence 받기 (Cloud v2, Data Center), storage XHTML → 스냅숏, Keychain 토큰, 비밀 검사 | 1. storage fixture(제목, 목록, 표, code·info·expand 매크로)가 기대한 블록을 낸다. 2. 사용자가 준 시험 페이지 하나를 받아 스냅숏을 만든다. 3. fixture 토큰이 출력, 로그, 파일, 스냅숏 어디에도 나오지 않는다. 4. 키 모양의 문자열이 든 fixture 블록이 가려진다 | 1.5-3 |
| 합계 | C0-C6 | | 12-20.5 (추정) |
| C7 (선택) | `scope`, `exclude`, `uncovered-block` | 범위 안 절에 새 블록을 더한 복사본이 `uncovered-block`이다 | 0.5-1 |

위험이 가장 큰 곳은 C3(계약만의 출력)과 C4(칸 생성의 일반성)다. C4의 수락 테스트 3이 KeyLocalLock이 아닌 모듈에서 규칙을 시험한다.

## 9. 열린 질문

### 9.1 사용자가 정할 것

| ID | 질문 | 권고 |
|---|---|---|
| UC1 | D9: 서명된 sidecar에 monitor 의무("monitor가 만료 entry를 보면 지운다", 경계는 `now > lease`)를 더하는가? 측정: D9-sweep-never와 RACY-09가 지금 PROVEN이고, 초안 3개는 모두 이 의무를 썼다 | 예 |
| UC2 | D14: "만료 시각 = lease"를 hole이 아니라 의도 절로 두는가? 측정: D14b가 hole 한 줄을 바꾸면 PROVEN이다 (3.5절) | 예. 예: held backed 토큰의 entry는 `expiresAt == gLease` (opus의 불변식), 또는 결정 probe C05 |
| UC3 | D13: 거절된 tryLock은 entry를 바꾸지 않는가 (연장 없음)? 측정: 지금은 hole만 잡는다 | 예 |
| UC4 | 서명 정책: 의도 절 전부(`ensures`, `invariant`, `lemma`, 결정 probe)에 `reviewed`와 `source`를 요구하는가? 지금은 발급 규칙과 허용 조건만이다 (U7). 진행 조건(D1, D4, D9가 사는 곳)은 지금 서명 없이 바뀔 수 있다 | 새 명세에는 예. 기존 sidecar는 다음에 고칠 때 옮긴다 |
| UC5 | 결정을 이름 있는 서명 probe로 sidecar에 남기는가? | 예 (3.5절) |
| UC6 | Confluence는 Cloud인가 Data Center인가? 받는 길은 REST(토큰은 Keychain), 수동 내보내기, Atlassian 커넥터 중 무엇인가? | 판을 알려 주면 REST로 시작 |
| UC7 | 페이지 텍스트를 Anthropic 밖의 서비스(예: Jev)로 보내도 되는가? | v1에서는 아니오 |
| UC8 | 경계 칸은 초안이 모두 같아도 항상 묻는가? | 예. 초안 3/3과 AI 구현 12/12가 같은 쪽으로 틀렸다 |
| UC9 | D2(만료 뒤 주인의 unLock = true)와 D5(unbacked 토큰의 unLock = false)를 확인하는가? 서명된 sidecar가 이미 이렇게 정했고, 초안 3개도 같지만 셋 다 `assumed`로 표시했다 | 확인 |

### 9.2 메인 세션이 정할 것

| ID | 판단 | 이 문서의 의견 |
|---|---|---|
| JC1 | 초안을 3개(모델 3종) 고정으로 두는가? 측정: 질문 목록의 합집합은 결정 11/12, 초안 하나는 9-11/12. D4의 불일치(규칙 2)는 초안이 여럿일 때만 보였다. 다만 D4는 세 질문 목록에 모두 있었다 | v1은 3개 고정. 비용은 초안당 약 20만 토큰, 22-28분 (측정). **메인 세션 사후 분석 (2026-10-25, 사전 등록 아님):** 초안 하나에 규칙 1을 "그 작성자가 open 또는 assumed"로 바꿔 적용하면(규칙 2 없음), 세 초안 각각이 결정 4개(D1, D2, D4, D5)를 모두 고른다. 고른 칸: haiku 9개(결정 밖 C03, C14, C16 포함), sonnet 8개, opus 6개. open만 쓰면 D1, D4만 고른다. 그래서 파일럿은 초안 3개가 초안 1개보다 낫다는 것을 보이지 못했다. 사용자에게 비용 결정으로 묻는다 |
| JC2 | Kotlin(`kv spec`)과 Python(`scripts/spec.py`)의 분담 | 7.1절대로. 지문과 stale은 `Fingerprint.kt`를 다시 쓴다 (DRY) |
| JC3 | 칸 목록의 출처 | v1은 3.2절 규칙과 프로토콜마다의 틀. 일반 생성기는 C4의 Aspect 시험 뒤에 정한다 |
| JC4 | emitter 계약 사실이 gate의 목표 모델을 바꾼다 | 메인 세션이 목표 모델을 고친다 (C2 수락 테스트 3) |
| JC5 | C7(`scope`, `exclude`)을 v1에 넣는가 | 넣을 만하다. 새 문단이 조용히 빠지는 것을 막는다. 0.5-1 인일 (추정) |
| JC6 | `--draft`(서명 전 검사)를 두는가, 아니면 검사 전에 서명을 요구하는가 | `--draft`를 둔다. 보고서 첫 줄에 "DRAFT: not signed; the verdict counts for nothing"를 쓴다. 파일럿은 "PILOT, not signed by the user" 메모를 하네스가 넣었다 |
| JC7 | critic 검토(CLAUDE.md rule 2)와 advisor 상담 | 이 작업은 advisor를 쓰지 않았다 (지시). 문법 변화(`source`, probe)와 서명 정책은 되돌리기 비싸다. 메인 세션이 정한다 |
| JC8 | 도구의 가정(보고서의 Assumptions: `Long` 무한, init의 inhale 등)을 질문 목록에 싣는가 | 싣는다. D12(overflow)는 어떤 방법도 드러내지 못했다 |

## 부록 A. 파일럿 재현

위치: `/private/tmp/claude-501/-Users-ykhfree-AI/9a7562f5-114a-4bd1-9a46-6cd88dafecdb/scratchpad/spec-design/`.
저장소 사본 (2026-10-25, 메인 세션): `pilot-c/`. scratch 폴더는 세션이 끝나면 사라지므로 옮겼다. 빠진 것: `kv-frozen/`(고정한 kv, 64 MB), `mutants/`(변이 뿌리, `tools/mutset.py roots`로 다시 만듦), `runs/`와 `*.vpr`(생성 모델), `drafts/`(작성자 작업 폴더; 결과는 `frozen-*`에 있음), `*.work`. `tools/mutset.py`는 `kv-frozen/bin/kv`를 부르므로, 다시 돌리려면 그 자리에 kv 설치본을 둔다.

| 파일 | 내용 |
|---|---|
| `PREREG.md` | 초안 전에 등록한 입력, 결정 목록 D1-D12, 칸의 서명된 답, 검사, 예측 11개 |
| `packet/` | `SOURCES.md`(블록 128개), `sources.json`, `LANGUAGE.md`, `SKELETON.kv`, `TASK.md`, `SCENARIOS.md` |
| `frozen-phase1/`, `frozen-final/` | 초안 3개의 1단계 파일과 최종 파일 (`DRAFT.kv`, `COVERAGE.md`, `QUESTIONS.md`, `ANSWERS.md`) |
| `aligned/` | 결정(D1 엄격, sonnet은 D4 넘겨받기)을 맞춘 초안 3개 |
| `checks/` | 조립한 sidecar와 검사 보고서 (`final-*`, `aligned-*`) |
| `results/` | 변이 판정 JSONL (`signed`, `haiku`, `sonnet`, `opus`, `aligned-*`, `x-*`), 결정성(`det/`), 절 분리(`iso/`) |
| `tools/` | `snapshot.py`, `assemble.py`, `mutset.py`, `determinacy.py`, `isolate.py`, `tracecheck.py`, `questions.py`, `compare.py`, `b4.py`, `merge_opus.py` |
| `kv-frozen/` | 고정한 kv |

절차:
1. `python3 -I tools/snapshot.py packet README.md=... CLAUDE.md=... "KeyLock.kt(core)=..." ...`
2. 초안: general-purpose agent 3개(haiku, sonnet, opus), 같은 프롬프트. 1단계 뒤 `SCENARIOS.md`를 넣고 2단계를 보냈다. haiku는 교정 2회.
3. `python3 -I tools/assemble.py build DRAFT.kv OUT` (서명된 hole, 시범 메모, coroutine 변형 파일).
4. `python3 -I tools/isolate.py OUT/check.vpr PREFIX`, `python3 -I tools/determinacy.py OUT/check.vpr PREFIX [--no-hole]`.
5. `python3 -I tools/mutset.py roots`, `python3 -I tools/mutset.py run SPEC_DIR OUT.jsonl --jobs 3`.

예측 대조 (`PREREG.md` 5절):

| 예측 | 결과 |
|---|---|
| 1. 교정 2회 안에 모두 통과 | 맞음 (sonnet, opus 0회, haiku 2회) |
| 2. D1: 2개 이상이 엄격 경계가 아님 | 맞음 (3/3이 `>=`) |
| 3. D1: 2개 이상이 질문에 올림 | 맞음 (3/3) |
| 4. D2: C09 답이 갈리거나 open이 있음 | 틀림 (3/3이 true, assumed) |
| 5. D3: 2개 이상이 의무로 씀 | 맞음 (3/3) |
| 6. P2는 0개 | 맞음 |
| 7. D4: 2개 이상이 의무로 씀 | 맞음 (2/3: haiku, opus) |
| 8. D9: 2개 이상이 의무로 쓰고, sweep-never가 서명에서 PROVEN, 초안에서 FAILED | 맞음 (3/3) |
| 9. 1개 이상이 참조 구현에서 실패하고, 실패는 결정에서 옴 | 맞음 (3/3, 절 9개 모두 D1 또는 D4) |
| 10. 서명된 sidecar는 C16만 OPEN (방식 a) | 맞음. 단, 하네스에 FRAME 등을 더한 뒤다. 처음 하네스는 C20을 거짓으로 OPEN이라 했다 (3.2절) |
| 11. 초안끼리의 차이가 질문 목록에 없는 결정을 하나 이상 드러냄 | 틀림. 초안끼리 다른 결정(D4)은 세 질문 목록에 모두 있었다 |

이탈과 주의:
1. 작성자 agent에는 사용자의 전역 CLAUDE.md가 함께 적용되었다 (AI-CODE.md와 같은 조건).
2. 작성자들은 상자 밖 파일을 읽지 않았다고 보고했다. 초안에는 서명된 sidecar의 절 이름(`P1a_entry`, `cap_unbacked`, `progress_live` 등)과 지문이 없다 (grep, 측정). haiku의 `progress_expired_takeover`만 서명된 이름 `expired_takeover`를 포함하고, 내용은 `>=`라서 다르다.
3. 결정 변이 D2의 편집은 unLock 안에서 `nowToEpochTime()`을 부른다. 도구는 이것을 이름 없는 자유 값으로 다룬다.

## 부록 B. 파일럿 원자료

### B.1 서명된 절의 재현 (뜻으로, 판단)

"같음"은 같은 뜻이다. 다른 절과 합쳐서 같은 뜻인 경우도 포함한다. "부분"은 관찰되는 부분(결과와 map)만 같은 것이다. "다름 Dn"은 결정 Dn을 다르게 골랐다는 뜻이다.

| 서명된 절 | haiku | sonnet | opus | 근거 |
|---|---|---|---|---|
| 발급 규칙 | 같음 | 같음 | 같음 | 세 초안의 `issue` 문자열이 서명된 것과 같다 |
| P1a | 같음 | 같음 | 같음 | 발급 규칙과 도구의 set-once 검사가 만든다 (7.3 ISSUE) |
| P1a_entry | 같음 | 같음 | 같음 | `gBacked = carried(S, k, res)` |
| P1 | 부분 | 부분 | 부분 | true이면 entry가 그 토큰을 가졌고 지금은 없다. ghost conjunct는 쓰지 않았다 (WITNESS와 hole이 준다) |
| P2 | 없음 | 없음 | 없음 | 문서에 semaphore가 없다 |
| P3a (tryLock) | 다름 D1 | 다름 D4 (절 없음: 끝낼 수 없음) | 다름 D1 | |
| P3a (unLock) | 같음 | 같음 | 같음 | |
| P3a (sweep) | 다름 D1 | 다름 D1 | 다름 D1 | |
| P3b | 같음 | 같음 | 같음 | `limit != 0`은 trusted 계약 `rej ==> limit != 0`에서 나온다 |
| P3c | 같음 | 같음 | 같음 | |
| progress_free | 같음 | 같음 | 같음 | |
| progress_live | 다름 D1 | 다름 D4 (entry가 있으면 항상 null) | 다름 D1 | |
| progress_owner | 같음 | 같음 | 같음 | |
| cap_unbacked | 같음 | 같음 | 같음 | |
| expired_takeover | 다름 D1 | 다름 D4 | 다름 D1 | |
| 합계 (15) | 같음 9, 부분 1, 다름 4, 없음 1 | 같음 9, 부분 1, 다름 4, 없음 1 | 같음 9, 부분 1, 다름 4, 없음 1 | |

결정을 맞춘 뒤(D1 엄격, sonnet은 D4 넘겨받기): "다름" 4줄이 모두 같음이 된다. 기계 확인: 세 초안이 원본에서 PROVEN이고, 결정성 칸 19개 중 18개가 서명된 sidecar와 같다(나머지 C16은 초안이 더 정함). 변이 판정은 B.4.

서명된 sidecar에 없는데 세 초안에 모두 있는 것: monitor 의무(D9), 만료 시각 = lease의 연결(D14), 거절된 호출이 entry를 바꾸지 않음(D13), WITNESS 불변식.

### B.2 결정성 칸 (측정)

방식 a(서명된 hole 포함)와 방식 b(hole 없음). 값은 DETERMINED의 결과, OPEN, INFEASIBLE이다. 칸 C09는 형식상 C08과 같아서 뺐다.

| 칸 | 서명 a | 서명 b | haiku a/b | sonnet a/b | opus a/b | 결정을 맞춘 3개 a/b |
|---|---|---|---|---|---|---|
| C01 | backed | backed | backed | backed | backed | backed |
| C02 | unbacked | unbacked | unbacked | unbacked | unbacked | unbacked |
| C03 | null | null | null | null | null | null |
| C04 | null | null | 토큰 | null | 토큰 | null |
| C05 | 토큰 | OPEN | 토큰 | null | 토큰 | 토큰 |
| C06 | false | false | false | INFEASIBLE | false | false |
| C07 | true | true | true | INFEASIBLE | true | true |
| C08 | true | true | true | true | true | true |
| C10 | false | false | false | false | false | false |
| C11 | false | false | false | false | false | false |
| C12 | false | false | false | false | false | false |
| C13 | unbacked | unbacked | unbacked | unbacked | unbacked | unbacked |
| C14 | 남음 | 남음 | 남음 | 남음 | 남음 | 남음 |
| C15 | 남음 | 남음 | 지움 | 지움 | 지움 | 남음 |
| C16 | OPEN | OPEN | 지움 | 지움 | 지움 | 지움 |
| C17 | false | false | false | false | false | false |
| C18 | backed | backed | backed | backed | backed | backed |
| C19 | backed | OPEN | backed | null | backed | backed |
| C20 | 토큰 | 토큰 | 토큰 | 토큰 | 토큰 | 토큰 |

초안의 a와 b는 모든 칸에서 같았다.

### B.3 참조 구현에서 실패한 절 (측정, 절 하나씩)

| 초안 | 절 수 | 실패 | 실패한 절 (결정) |
|---|---|---|---|
| haiku | 19 | 3 | `progress_expired_takeover`(D1), `tryLock_null_only_when_owned`(D1), `progress_sweep_drops_expired`(D1) |
| sonnet | 15 | 4 | tryLock의 기본 `ends_only_if false`(D4), `tryLock_held_key_returns_null`(D4), `tryLock_held_key_unchanged`(D4), `progress_sweep_drops_expired`(D1) |
| opus | 14 | 2 | `progress_tryLock_takeover`(D1), `progress_sweep_drops_expired`(D1) |

`kv verify`의 첫 보고는 sonnet의 tryLock에서 기본 허용 조건 하나만 보였다. 나머지 둘은 절 분리 검사에서만 보였다.

### B.4 변이 판정 (측정)

뿌리 89개(서명된 sidecar를 돌린 것과 같은 집합)에서 결정을 맞춘 초안과 서명된 sidecar의 모듈 판정을 비교한다. 결정 변이 D13, D14는 따로 적는다.

| 결정을 맞춘 초안 | 둘 다 FAILED | 둘 다 PROVEN | 서명 PROVEN, 초안 FAILED | 서명 FAILED, 초안 PROVEN | D13 (거절 때 lease 연장) | D14 (lease 두 배) | 시간 |
|---|---|---|---|---|---|---|---|
| haiku | 65 | 22 | 2 | 0 | FAILED (property: `tryLock_null_keeps_owner`) | FAILED (property: `grant_lease`) | 564 s |
| sonnet | 65 | 22 | 2 | 0 | FAILED (property: `inv_entry_expiry_is_lease`) | FAILED (property: 같은 절) | 1499 s |
| opus | 65 | 22 | 2 | 0 | FAILED (property: `inv_held_backed_token_carried_with_lease`) | FAILED (property: 같은 절) | 두 번에 나눠 돌림 (뒤 45개 922 s) |
| (서명된 sidecar) | | | | | FAILED (invariant only: `MI_INV_4`) | FAILED (invariant only: `MI_INV_4`) | |

1. 세 초안 모두 서명된 sidecar가 잡은 65개를 모두 잡는다. 서명된 sidecar가 잡는데 초안이 놓친 뿌리는 0개다.
2. 세 초안 모두 대조군과 동등 변이 22개를 PROVEN으로 둔다 (손으로 고른 대조군 SB5, SB5b, R1, R2와 원본 포함).
3. 세 초안이 더 잡는 2개는 같다: RACY-09(monitor 앞의 경쟁 읽기 때문에 만료 entry를 한 번 건너뜀)와 D9-sweep-never. 둘 다 `progress_sweep_drops_expired`(D9)로 잡힌다.
4. AI 구현 7개는 세 초안과 서명된 sidecar에서 모두 FAILED다 (경계 `>=`).
5. opus는 두 실행으로 나눠 돌렸다: 앞 46개는 첫 실행의 뿌리별 보고서에서, 뒤 45개는 둘째 실행에서 모았다 (`tools/merge_opus.py`).

### B.5 작성자 비용 (측정)

| 작성자 | 1단계 시간 | 1단계 토큰 | 질문 수 | 절 수 (출처 인용이 있는 것) | 2단계 답 settled / assumed / open |
|---|---|---|---|---|---|
| haiku | 28.2분 | 218k | 16 | 24 (21) | 11 / 5 / 4 |
| sonnet | 27.8분 | 191k | 17 | 21 (18) | 14 / 2 / 4 |
| opus | 22.6분 | 222k | 16 | 20 (18) | 14 / 3 / 3 |

## 부록 C. 근거

| 출처 | 원문 |
|---|---|
| req-shield `README.md:118-119` | "an entry whose holder never released it is dropped by the" / "cleanup monitor once `lockTimeoutMillis` has passed, which runs on a 1 s interval and so can lag by up to that much." |
| req-shield `README.md:139` | "Once the map is full, a request for a **new** key is handed a permit that no map entry backs." |
| req-shield `CLAUDE.md:97` | "`lockTimeoutMillis`: Lock acquisition timeout (default: 3000ms)" |
| core `KeyLock.kt:23-24` | "release it, so a holder whose lock already expired and was taken over by someone else" / "cannot release the new owner's lock." |
| reactor `KeyLock.kt:36` | "Emits false when the lock is not held or the token does not match, so an expired lock" |
| coroutine `KeyLocalLock.kt:100` | `if (now > lockInfo.expiresAt) {` |
| coroutine `KeyLocalLock.kt:161` | `if (now > existing.expiresAt && existing.isHeld.compareAndSet(true, false)) {` |
| `specs/reqshield/keylocallock.common.kv` | tryLock과 sweep의 P3a 메모가 모두 `sha256 "1c0ae67dbfe9757d"`다 |
| `translator/src/main/kotlin/kv/sidecar/Check.kt:213` | "needs a 'reviewed' note: allowance clauses count only after a person reads them (U7)" |
| `translator/src/main/kotlin/kv/sidecar/Fingerprint.kt` | "every whitespace run becomes one space, the ends are trimmed, and the fingerprint is the first 16 hex" |
| `DESIGN-v0.md` 8.3 "reviewed 지문" | "발급 규칙과 허용 조건이면 sidecar의 그 절 텍스트다" |
| `AI-CODE.md` 10.1 | "SPEC.md의 \"once `lockTimeoutMillis` has passed\"는 경계를 정하지 않는다." |
| Atlassian, Confluence Server/DC REST API examples | "curl -u admin:admin http://localhost:8080/confluence/rest/api/content/3965072?expand=body.storage" (personal access token: Confluence 7.9부터) |

## 10. 사용자 결정 (2026-10-25)

| ID | 결정 |
|---|---|
| UC1 | 더하지 않는다. monitor 의무 절은 D9-sweep-never(결함)와 함께 RACY-09도 잡는다. 1차 판정자 A는 RACY-09를 결함이 아니라고 봤다 (README가 1 s 지연을 허용, MUTATION.md 9.3). 빈틈은 기록으로 남긴다 |
| UC2 | 더한다: 만료 시각 = lease를 의도 절로 |
| UC3 | 더한다: 거절된 tryLock은 entry를 바꾸지 않는다 |
| UC4 | 새 명세는 의도 절 전부(`ensures`, `invariant`, `lemma`, 결정 probe)에 `reviewed`와 `source`를 요구한다. 기존 sidecar는 다음에 고칠 때 옮긴다. 지금 문법은 `ensures`, `invariant`의 `reviewed`를 받지 않으므로, 서명은 C1의 문법 변화 뒤에 붙인다 |
| UC5 | 결정은 이름 있는 서명 probe로 sidecar에 남긴다 |
| UC6 | Confluence Cloud. C6은 REST API v2와 Keychain의 API 토큰으로 받는다 |
| UC7, UC8, UC9 | 권고를 기본값으로 둔다 (메인 세션이 알렸고, 사용자가 다른 뜻을 말하지 않음): 페이지 텍스트를 Anthropic 밖으로 보내지 않는다; 경계 칸은 항상 묻는다; D2 = true, D5 = false를 지금 서명대로 둔다 |
| JC1 | 초안 1개로 시작한다. 규칙 1은 "그 작성자가 open 또는 assumed"다. 두 번째 모듈에서 초안 3개와 비교해서 확정한다 |

### 10.1 메인 세션 결정 (2026-10-25, C0·C1 뒤)

C0 `aba91e7`, C1 `8d8e17a`. 메인 세션 확인: 테스트 488개 통과, gate 4개 exit 0, mutantsReqShield 33회와 mutantsAspect 30회 불일치 0.

| 질문 (구현 agent) | 결정 |
|---|---|
| 결정 probe의 판정 | probe는 연산의 계약과 hole로만 증명된다. 그래서 코드 변이는 probe가 아니라 action 행에서 FAILED가 된다. 결정 probe의 일은 **명세나 hole이 약해져도 결정이 남게 지키는 것**이다 (3.5절). 8절 C1 수락 3을 이렇게 읽는다: 원본 PROVEN, 결정 변이는 모듈 FAILED, 결정을 담은 절을 약하게 한 sidecar에서는 probe가 FAILED. agent의 측정이 이것을 보였다 |
| `source none "<reason>"` | 7.2 문법에 넣는다. 자연어에 문장이 없는 의도 절(사용자 결정, 코드에서 온 성질)이 쓴다 |
| 인용 안의 `"` | 지금은 두지 않는다. C6(Confluence)에서 필요해지면 `\"` escape를 더한다 |
| 유니코드 공백 정규화 | C6에서 넓힌다 (Confluence의 NBSP). 기존 텍스트는 ASCII라 기존 지문은 바뀌지 않는다 |
| "Spec to review" 줄 | `review intent` 아래에서는 모든 의도 절을 나열한다. C3에서 한다 |
| 블록 지문(sha256) 필수 | `review intent` 아래에서는 필수로 한다 (문맥 변경을 보려면 필요). C3에서 한다 |
| `review intent`의 위치 | 공통 파일에 한 번 둔다. include된 변형 파일에도 적용된다 |

### 10.2 C2 결과와 메인 세션 결정 (2026-10-25)

C2 `9ea254b`(emitter), `c7b54e1`(기계적 재측정 기록). emitter는 FRAME(모든 action과 stray), END 두 줄(witness가 있는 object 모듈), ISSUE(발급 규칙이 있는 연산)를 계약에 낸다. 보고서 이름은 `tool fact FRAME|END|ISSUE`다.

메인 세션 확인:
- gate 목표 모델은 메인 세션이 7.3의 식에서 손으로 고쳤다 (`spike/effect_ghost/target.vpr`, `spike/aspect/target.vpr`, `spike/aspect/mutants.py`의 stray). 그 뒤 GateTest의 다섯 비교를 포함한 테스트 490개가 통과한다.
- mutantsReqShield 33회, mutantsAspect 30회 불일치 0. 실패 의무 ID도 바뀌지 않았다 (세 사실은 구조상 참이라 처음 실패하는 사후조건이 될 수 없다).
- 기계적 변이 264개 (`mutation/rerun-c2/`): `rerun3`과 판정, 하위 상태, 실패 의무가 모두 같다 (다시 셈).

| 질문 (구현 agent) | 결정 |
|---|---|
| O1 WITNESS | C3에서 정한다. 지금 sidecar의 MI_INV_5와 같은 식이고, 도구 사실만으로 귀납적임을 agent가 손 모델로 보였다 (E2). 넣으면 파일럿 방식 b의 재현이 바뀔 수 있다 |
| O2 다른 key 항목의 필드 프레임 | C3의 결정성 검사에서 C20 같은 칸이 필드 프레임을 요구하는지 잰 뒤 정한다 |
| O3 다섯 번째 gate | 메인 세션이 `spike/aspect/mutants.py`의 stray 계약에 FRAME을 넣어 고쳤다 |
| O4 END 두 줄의 이름 | 하나(`tool fact END`)로 둔다 |
| O5 `mutation/rerun-c2/` | 기록으로 남긴다 |

### 10.3 C3 결과와 메인 세션 결정 (2026-10-25)

C3 `b5191e4` (`kv spec check`). 대조 스크립트: `pilot-c/c3/` (`run_pilot.sh`, `compare.py`, 측정용 두 key 칸 `two-keys.cells`).

결과 (agent 측정, 메인 세션이 지금 sidecar와 결정 전 sonnet 초안을 다시 돌려 확인):
- 결정성: 7개 sidecar × 방식 2 × 칸 19 = 266개 판정이 파일럿과 모두 같다.
- 지금 sidecar: C05와 C19가 방식 b에서도 정해진다 (등록한 예측 맞음, `entry_lease` 덕분). C16만 OPEN이다 (monitor 의무를 더하지 않음, UC1).
- 절 분리: 결정 전 초안의 실패 절 9개를 모두 찾는다. 지금 sidecar는 13절 모두 PROVEN이다.
- 필드 고정 경고: 파일럿 서명 2개, 맞춘 초안 0개. 지금 sidecar는 1개다: `progress_live`가 `isHeld`를 읽고, 그 뜻은 hole(MI_INV_1)에만 있다. 수락 기준의 "0개"는 `isHeld`를 빠뜨린 메인 세션의 예상이었다.
- WITNESS(O1)와 필드 프레임(O2)은 19칸의 어느 것도 바꾸지 않는다. 다른 key의 단계가 들어간 칸(측정용 X1-X5)에서는 둘 다 필요하고, FRAME에 domain 트리거도 필요하다.
- 테스트 519개 통과, 변이 63회 불일치 0 (메인 세션 확인).

| 질문 (구현 agent) | 결정 |
|---|---|
| `isHeld` 경고 | 사용자에게 묻는다. 후보 절 `entry_held`(토큰을 가진 entry의 isHeld = 그 토큰의 gHeld)는 세 변형에서 PROVEN이고 경고를 없앤다 (메인 세션 측정; Silicon 시간은 약 5 s에서 8-10 s로 늘었다) |
| spec 우선 sidecar의 타입 표기 | 지금은 정하지 않는다. 코드 없는 흐름을 실제로 쓰는 C5에서 정한다 |
| 칸 없이 방식 b로 타입 검사 | 유지한다 |
| 종료 코드 | 지금처럼 발견은 exit 0이다. CI 연결 때 옵션을 정한다 |
| FRAME domain 트리거, WITNESS, 필드 프레임, init의 "발급된 record 없음" | C4에서 칸 생성이 다른 key 단계를 넣을 때 함께 도구 사실로 올린다 (gate와 변이 재측정 포함) |
| 칸 fixture 위치 | C4에서 정한다 |

### 10.4 C4 결과와 메인 세션 결정 (2026-10-26)

C4 `ea1ffcc`(`kv spec cells`, 도구 사실 WITNESS·ISSUE_ONLY), `83de51d`(`scripts/spec.py questions`, 묶음 검사, 검토 묶음).

결과 (agent 측정; 메인 세션은 테스트, 변이, 재측정을 다시 돌림):
- KeyLocalLock 생성 칸 65개가 파일럿 형식 칸 19개를 모두 덮는다. 대응한 19칸의 판정이 손 칸과 a·b 모두 같다.
- 질문 규칙: 초안 3개는 파일럿과 같은 8칸, 초안 1개는 haiku 9, sonnet 8, opus 6칸이고 셋 다 D1, D2, D4, D5를 덮는다. 묶음 검사는 빠진 칸, 두 묶음의 칸, 질문 아닌 칸을 거부한다.
- Aspect core-spring: 칸 12개. 정해짐 7, OPEN 3(생성 함수가 throw할 때: exc와 map을 정하는 절이 없음), INFEASIBLE 2(set-once 때문에 "주인 변경" 단계가 불가능; 규칙의 결과).
- KeyLocalLock 방식 b의 OPEN 10개: 절만으로는 "발급하지 않는 연산(unLock, sweep)이 빈 key에 entry를 만들지 않는다"가 없다. hole(INV-E)만 그것을 말한다.
- 도구 사실: WITNESS(다른 key 칸 4개가 방식 b에서 필요)와 ISSUE_ONLY(결과 말고는 발급하지 않음; 구조상 참)를 올렸다. FRAME domain 트리거, 필드 프레임, init 사실은 필요한 칸이 없어 측정 옵션으로만 둔다.
- 메인 세션 확인: gate 목표 모델에 두 사실을 손으로 넣었다 (`spike/effect_ghost/target.vpr`). 테스트 536개 통과, 변이 63회 불일치 0, 기계적 변이 264개 (`mutation/rerun-c4/`)가 `rerun4`와 모두 같다.

| 질문 (구현 agent) | 결정 |
|---|---|
| 칸 생성이 발급 규칙(의도 층)을 읽는다 | C5에서 1단계 초안의 발급 규칙으로 칸을 만든다 (2.2의 순서와 같다: 초안 → 칸 → 2단계 답) |
| 칸 수 65개 (파일럿 20개) | C5의 파일럿 재실행에서 답 비용을 잰 뒤 정한다 |
| KeyLocalLock 방식 b OPEN 10개, Aspect OPEN 3개 | 사용자에게 묻는다 (sidecar에 절을 더할지) |
| WITNESS를 MI 밖에 둔 것 | 그대로 둔다 |
| 규칙 2 (OPEN 차이는 세지 않음), 규칙 4 (대리 지표) | 그대로 둔다. 진짜 규칙 4는 칸과 절의 연결이 필요하다 (뒤로 미룸) |
| 칸 생성 상수의 CLI 옵션 | 두 번째 프로토콜에서 필요해질 때 연다 |

### 10.5 C5 결과와 메인 세션 결정 (2026-10-26)

C5 `8007476`(`scripts/spec.py` task, collect, cells, questions --run, align, outcomes; `kv verify --draft`), `1eb2df5`(재현 결과 `specdraft/pilot-replay/results/`).

결과 (agent 측정; 메인 세션은 테스트 546개와 변이 63회를 다시 돌리고 재현 결과 파일을 읽음):
- 파일럿을 opus 초안 하나로 명령마다 다시 돌렸다 (8분 21초): 교정 0회, 칸 19개 판정, 2단계 답(settled 14, assumed 3, open 3), 질문 칸 6개(D1, D2, D4, D5), 맞춘 초안이 원본에서 PROVEN. 모두 파일럿과 같다.
- 교정 회차에는 오류 줄만 간다 (검증 문구를 막는 테스트).
- Q-D1 선택지별 결과가 3.6과 같다: 선택지 B(`>=`)에서 참조 구현 FAILED, T-sonnet-1과 T-sonnet-4 PROVEN; 선택지 A(`>`)에서는 그 둘이 FAILED. 26개 판정 중 25개가 파일럿과 같고, T-haiku-2 하나는 AI-CODE.md 13의 수정 1 때문에 FAILED에서 INCOMPLETE가 됐다.
- 비용 자료: 생성 칸 65개의 2단계 상자는 65,562자로, 파일럿 칸(43,023자)의 약 1.5배다. 칸 설명은 21,009자 대 3,016자다.

| 질문 (구현 agent) | 결정 |
|---|---|
| `kv verify --draft` | 승인한다. 보고서 첫 줄이 "DRAFT"이고 판정은 서명 전 참고용이다 |
| probe 안의 타입 오류가 교정을 지나감 | 알려진 한계로 기록한다. 코드가 있는 실행에서는 잡힌다. 뒤에 문법 검사 모델에 probe를 넣는다 |
| 묶기 상자, 선택지가 하나뿐인 묶음, 선택지 초안 만들기 | 실제 실행에서 메인 세션이 손으로 하고, 필요한 상자를 그 결과로 정한다 |
| 2단계 작성자 | 같은 agent를 이어 쓴다 (파일럿과 같은 조건) |
| 생성 칸 65개 대 파일럿 칸 | 실제 실행은 생성 칸 65개로 한다. 비용을 재는 것이 목적이다 |

### 10.6 실제 실행 (2026-10-26, 생성 칸 65개, 초안 1개)

산출물: `specdraft/e2e-2026-10-26/`. 작성자 agent 하나(opus)와 교정 agent 하나(opus). 메인 세션이 `scripts/spec.py` 명령을 돌렸다.

| 단계 | 결과 (측정) | 비용 (측정) |
|---|---|---|
| 1단계 초안 | 교정 0회, 절 25개, 인용 30개. 만료 경계와 만료 lock 넘겨받기를 정하지 않고 열어 두었고, 둘 다 질문 목록에 올렸다 | 약 9.6만 토큰, 5.1분 |
| 칸과 검사 | 65칸 중 13칸 OPEN (방식 a, b), 원본 코드에서 실패한 절 0개 | 도구 3분 7초 |
| 2단계 답 | settled 50, assumed 2, open 13 | 약 4.1만 토큰, 2.5분 |
| 질문 | 15칸, 결정 4개 (D1 경계, D4 넘겨받기, D7 넘겨받은 뒤 lease 시작, D5 unbacked 해제) | 도구 수 초 |
| 결정 | 사용자의 기존 결정으로 정했다 (다시 묻지 않음) | |
| 교정 | 결정 칸 15개가 방식 a, b에서 모두 결정대로다. 다른 칸 변화 0, 실패한 절 0, 결정 probe 4개가 있다 | 약 6.9만 토큰, 1.7분 |
| 원본 검증 | **init FAILED**: 작성자의 invariant와 lemma에 `tok(x).gIssued` 조건이 없다. 발급되지 않은 ghost 기록의 필드는 init에서 정해지지 않으므로 빈 map에서 깨질 수 있다 (진짜 초안 결함) | |
| 메인 세션 수정 뒤 | 두 곳에 `gIssued`를 넣으니 원본 PROVEN, 결정 probe 4개 PROVEN | |
| 선택지별 결과 (A = 수정한 초안) | 참조 구현 PROVEN. 결정 변이 5개 모두 FAILED. AI 구현 10개 모두 FAILED (판정 가능한 5개는 경계 `>=`로) | |

LLM 비용 합계는 약 21만 토큰, 9분이다 (파일럿 1단계 하나가 22만 토큰, 22.6분이었다).

발견:
1. 흐름은 처음부터 끝까지 돈다. AI 구현 12개가 모두 틀린 결정(D1)과 파일럿 초안끼리 갈린 결정(D4)이 질문으로 올라왔다.
2. **빠진 검사:** 절 분리와 `align --check`의 칸 검사는 action만 본다. init과 lemma의 실패는 마지막 `kv verify --draft`에서만 드러났다. 그리고 그 실패를 교정 agent에게 돌려보내는 단계가 없다. 이번에는 메인 세션이 손으로 고쳤다.
3. **묻지 않은 결정:** 초안은 monitor 의무(D9)를 스스로 정했다 (`progress_sw_drops_expired`). 사용자는 UC1에서 이 의무를 더하지 않기로 했다. 참조 sidecar가 그 칸을 OPEN으로 두었으므로 규칙 4(대리 지표)가 걸리지 않았다. 참조가 OPEN이고 초안이 정한 칸도 질문 후보로 올려야 한다.
4. 교정 agent가 상자 규칙을 한 번 어겼다 (check.sh 말고 cat, grep, sed, python3를 썼다). 결과에는 영향이 없다.
5. **되돌림 시험 (advisor 권고):** 메인 세션의 손 수정은 블라인드 원칙을 어긴다. 그래서 교정 agent에게 오류 줄 하나(`init: Postcondition of init might not hold: generated MI`)만 보냈다. agent는 같은 수정(invariant와 lemma의 `gIssued` 조건)을 스스로 찾았고 (약 4천 토큰, 37초), `align --check`가 원본 PROVEN을 보고했다. 그래서 "검증 실패의 오류 줄을 교정 agent에게 돌려준다"를 흐름의 단계로 넣을 근거가 있다 (n = 1).
6. **상자 설명의 빈틈:** LANGUAGE.md는 "발급되지 않은 ghost 기록의 필드는 정해지지 않는다, 그래서 tok(x)에 대한 invariant와 lemma에는 gIssued 조건이 필요하다"를 말하지 않는다. 파일럿 opus 초안은 조건을 썼고 이번 초안은 쓰지 않았다. 한 문장을 더하면 된다.
7. **참조 sidecar:** 이번 실행의 규칙 4 대리 지표는 `pilot-c/specs-signed`(파일럿 날의 서명 sidecar)였다. 질문 칸 15개는 모두 규칙 1과 5에서 나왔으므로, 지금 sidecar를 참조로 써도 질문은 같을 것으로 본다 (추정). 발견 3은 어느 참조에서나 성립한다 (H14s는 둘 다 OPEN).
8. D7(넘겨받은 뒤 lease의 시작)은 사용자에게 따로 묻지 않았다. 서명된 발급 규칙(`gLease = now + timeout`)에 포함된 결정이다.

### 10.7 실제 실행 빈틈 메우기 (2026-10-26)

`2158745`. 메인 세션 확인: 테스트 551개 통과, 변이 63회 불일치 0, 재현 스크립트 `specdraft/keylocallock/e2e-open-decision.sh` exit 0.

1. **init·lemma·probe 검사와 되돌림:** `kv spec check --root`가 전체 프로그램을 한 번 더 돌려 init, lemma, probe를 행으로 보고한다. `align --check`는 `kv verify --draft`가 PROVEN이 아니면 실패하고, 교정 상자에 `FEEDBACK-n.md`(결정 칸의 시나리오와 결정된 결과, 그리고 `kv verify`의 오류 줄만)를 쓴다. 최대 2회. 1단계 초안의 init 결함도 이제 cells 단계에서 보인다.
2. **일부러 열어 둔 결정:** 결정 파일의 칸이나 묶음을 `"open"`으로 정할 수 있다. `align --check`는 그 칸이 모든 방식에서 OPEN이기를 요구한다. 규칙 6: 참조가 OPEN이거나 이전 결정이 open인 칸을 초안이 정하면 질문이다. 실제 실행 자료에서 규칙 6은 H14s(monitor 의무) 하나를 더한다 (질문 16칸 = 실행 때의 15칸 + H14s; 10.6 항목 7의 추정이 측정으로 맞음). H14s를 open으로 정하면 의무가 든 초안은 실패하고 FEEDBACK 1줄이 나오며, 의무를 뺀 초안은 통과한다.
3. **LANGUAGE.md:** 발급되지 않은 기록의 필드는 정해지지 않으니 tok(x)에 대한 invariant와 lemma에는 `gIssued` 조건이 필요하다는 문단을 더했다.
4. 파일럿 자료에서 규칙 6은 C16(D9)을 질문으로 더한다. DESIGN-C 3.3이 "서명된 sidecar와 비교할 때만 드러나는 빈틈"이라 부른 칸이 이제 질문이 된다.

메인 세션 결정: 방식 b에서는 규칙 6을 이전 결정의 open 칸으로만 제한한다 (hole 없는 참조의 OPEN은 대개 "hole이 정함"이라는 뜻이다; 실제 실행에서 잡음 4칸). 파일럿 재현 기록을 다시 만든다. UC1은 프로토콜 설정에 넣지 않는다. FEEDBACK은 `kv verify`의 첫 실패 줄만 쓴다.

### 10.8 C6 상세 설계 (2026-10-28, 실제 연결 없이 API 스펙 기준)

사용자 결정 (2026-10-28): 지금 연결된 Confluence가 없다. 그래서 공식 API 스펙에 맞춰 구현한다. 실제 페이지로 하는 확인(8절 C6 수락 2)은 연결이 생길 때 한다.
스펙 근거: 공식 문서 조사 (Cloud REST v2 "Get page by id", basic auth, Data Center 11.0 storage format, 5.9 매크로 문서). 요약과 인용 44개는 이 세션 scratchpad의 `c6/SPEC.md`에 있다 (저장소 밖). 아래 "미확인"은 공식 문서로 확인하지 못한 것이다.

범위:
1. Cloud만 한다 (UC6). Data Center 받기는 미룬다.
2. 받기는 Python 스크립트, 블록 나누기는 Kotlin `kv spec snapshot`이다 (6.2).

**받기: `translator/scripts/confluence_fetch.py` (표준 라이브러리만)**

1. 명령: `confluence_fetch.py --site https://SITE.atlassian.net --email EMAIL --keychain-service NAME --out DIR PAGE_ID...`
2. 토큰: 실행할 때 `security find-generic-password -s NAME -a EMAIL -w`로 읽는다. 토큰은 화면, 로그, 파일, 예외 메시지에 쓰지 않는다.
3. 요청: `GET {site}/wiki/api/v2/pages/{id}?body-format=storage`. `Authorization: Basic base64(email:token)`을 처음 요청부터 보낸다 (Confluence는 인증 challenge를 보내지 않는다).
4. `--site`는 `https://`여야 한다. 예외는 `localhost`와 `127.0.0.1`의 `http://`뿐이다 (시험용 가짜 서버). 토큰이 평문 HTTP로 다른 호스트에 가지 않게 하기 위해서다.
5. 출력: 페이지마다 두 파일.
   - `DIR/<id>.xhtml`: `body.storage.value` 원문 (UTF-8).
   - `DIR/<id>.page.json`: `id`, `title`, `status`, `version`(번호), `createdAt`(버전 시각), `site`, `fetchedAt`.
6. 오류와 종료 코드:
   - 401: 인증 실패 (email 또는 토큰 확인). 종료 코드 2.
   - 404: "페이지가 없거나 권한이 없다" (이 엔드포인트는 403을 문서화하지 않는다). 종료 코드 2.
   - 429와 503: `Retry-After`(초, 정수)만큼 기다린 뒤 다시 한다. 없으면 1, 2, 4초를 기다린다. 최대 3번 다시 한다. 기다림 한 번의 상한은 60초다.
   - `body.storage.value`가 없음: 종료 코드 2.
   - Keychain 읽기 실패: 종료 코드 3.

**블록 나누기: `kv spec snapshot`의 `.xhtml` 입력**

1. `NAME=FILE`에서 FILE이 `.xhtml`이면 storage format이다. NAME은 `conf:<페이지 id>`를 쓴다 (6.2 4번).
2. 읽기:
   - HTML 4 named entity 252개를 숫자 참조로 바꾼 뒤 XML로 읽는다. `<![CDATA[ ... ]]>` 안은 바꾸지 않는다 (code 매크로 본문은 원문 그대로다). 표에 없는 entity는 그 이름을 담은 오류다. DTD는 읽지 않는다 (외부 entity 차단).
   - namespace를 보지 않고 읽는다 (`IS_NAMESPACE_AWARE = false`). 그래서 `ac:structured-macro`는 콜론이 든 요소 이름이다. namespace URI(미확인)가 필요 없고, 처음 보는 prefix도 오류가 아니다.
   - 값은 최상위 요소가 여럿인 조각이다. 그래서 합성 root 하나로 감싼다. root는 첫 줄 앞에 줄바꿈 없이 붙인다. 그래서 블록의 줄 번호는 파일의 줄 번호와 같다.
3. 절: `h1`-`h6`이 절을 시작한다. 제목 자체는 블록이 아니다. 첫 제목 앞은 절 `top`이다. anchor는 Markdown과 같다: `NAME#<제목 slug>/<번호>`.
   - slug 규칙을 넓힌다 (Markdown에도 쓴다): 소문자로 바꾼 뒤 글자(`\p{L}`)와 숫자(`\p{N}`) 밖의 묶음을 `-`로 바꾼다. 지금 규칙(`[^a-z0-9]`)은 한국어 제목을 빈 slug로 만들어 anchor가 겹친다. 그래도 비면 `h<제목 번호>`를 쓴다. ASCII 제목의 slug는 그대로다.
4. 블록:

| 요소 | 블록 | 텍스트 |
|---|---|---|
| `p`, `pre`, `blockquote` | 하나 | 안의 글자. `br`은 줄바꿈 |
| `ul`, `ol`의 최상위 `li` | 항목마다 하나 | `- 글자` 또는 `1. 글자`. 안의 목록은 다음 줄에 두 칸 들여쓰기 |
| `table`의 `tr` | 행마다 하나 | 칸의 글자를 ` | `로 잇는다 |
| `code` 매크로 | 하나 | `ac:plain-text-body`의 원문 그대로 |
| `info`, `tip`, `note`, `warning` 매크로 | 하나 | 첫 줄 `NAME: 제목`(제목이 없으면 `NAME:`), 다음 줄부터 본문 |
| `expand` 매크로 | 하나 | 첫 줄 `expand: 제목`, 다음 줄부터 본문 |
| `ac:task` | 하나 | `[ ] 글자` 또는 `[x] 글자` (완료 값 `complete`는 미확인) |
| 그 밖의 매크로 | 하나 | 안의 글자. 표준 오류에 경고 한 줄 |
| `ac:layout`, `ac:layout-section`, `ac:layout-cell`, `div` | 없음 (투명) | 안의 요소를 차례로 나눈다 |
| `ac:adf-extension` | `ac:adf-content`를 투명하게 나눈다. 없으면 `ac:adf-fallback` | 구조는 미확인 (제3자 자료) |
| `hr`, `ac:image`, `ac:emoticon` | 없음 | |

5. 글자: 줄 안의 공백 묶음은 공백 하나로 바꾼다. NBSP(U+00A0)는 공백이다 (10.1 "유니코드 공백 정규화"를 읽는 단계에서 한다; 지문 규칙은 그대로). 줄의 양 끝 공백은 자른다. 링크는 링크 본문, 없으면 `ri:page`의 `ri:content-title`이다. 빈 블록은 만들지 않는다.
6. 줄 번호: 블록 요소의 시작 줄과 끝 줄이다.
7. SOURCES.md: 같은 폴더의 `<id>.page.json`이 있으면 원본 제목 줄에 페이지 제목과 버전을 적는다.

**비밀 검사 (6.3 2번): `kv spec snapshot`의 모든 입력**

1. 블록 텍스트가 비밀 모양의 정규식에 걸리면, 블록 텍스트를 `[redacted by kv spec snapshot: KIND]`로 바꾼다. 지문도 바뀐 텍스트로 만든다. 원문은 어느 파일에도 쓰지 않는다.
2. 표준 오류에 가린 블록의 anchor와 KIND를 적는다. 값은 적지 않는다.
3. KIND: 비밀 키 머리(`-----BEGIN ... PRIVATE KEY-----`), AWS 접근 키, Atlassian API 토큰, GitHub 토큰, Slack 토큰, Anthropic 키, OpenAI 형식 키, JWT, `password`/`secret`/`api key`/`token` 뒤의 `:` 또는 `=`와 8자 이상의 값.
4. 기존 스냅숏(파일럿 128블록, ReqShield core)이 바이트 단위로 같아야 한다. 걸리는 블록이 생기면 규칙을 좁힌다.

**시험**

1. 수락 1: storage fixture(제목, 문단, 중첩 목록, 표, code·info·expand 매크로, task, layout, entity, NBSP, 모르는 매크로)가 기대한 블록을 낸다.
2. 수락 3: JDK `HttpServer` 가짜 서버와 PATH의 가짜 `security`로 스크립트를 끝까지 돌린다. fixture 토큰과 base64(`email:token`)(Authorization 헤더의 값)이 표준 출력, 표준 오류, 출력 파일, 스냅숏 어디에도 없다. 401, 404, 429 경로도 같다. 429 경로의 가짜 서버는 `Retry-After: 0`을 보낸다.
3. 수락 4: 키 모양의 문자열이 든 fixture 블록이 가려진다. 가린 목록에 값이 없다.
4. 수락 2 (실제 페이지)는 미룬다. 연결이 생기면 미확인 항목(namespace URI, entity 집합, 중첩 목록, task 완료 값, `ac:adf-extension`, Cloud 매크로 속성)을 그 페이지로 확인한다.

**미루는 것**

1. 인용 안의 `"` (10.1 "C6에서 필요해지면"): 실제 페이지가 필요를 보일 때까지 미룬다.
2. 받은 `.xhtml`과 `.page.json`은 원문 그대로다. 비밀 검사는 LLM으로 가는 길(스냅숏)만 지킨다. 그래서 받은 폴더는 저장소에 커밋하지 않는다.

**예측 (구현 전 등록)**

1. 파일럿 스냅숏(128블록)과 `specdraft/reqshield-core/snapshot`은 비밀 검사와 slug 규칙을 넣은 뒤에도 바이트 단위로 같다.
2. 기존 테스트는 모두 통과한다.
3. fixture의 블록 수는 요소 표대로다 (fixture와 함께 등록한다).
4. 가짜 서버 시험에서 토큰과 base64(`email:token`)의 출현은 0번이다.
5. 비밀 fixture의 KIND마다 블록 하나가 가려지고, 가린 목록에 값이 나오지 않는다.

### 10.9 C6 결과 (2026-10-28, 메인 세션 기록)

구현: 받기 스크립트와 그 시험은 feature-developer(별도 worktree), 블록 나누기와 비밀 검사는 메인 세션이 했다.
새 파일: `translator/scripts/confluence_fetch.py`, `kv/spec/Storage.kt`, `kv/spec/Secrets.kt`, 리소스 `kv/spec/html4-entities.txt`, 테스트 `StorageSnapshotTest`(8개), `ConfluenceFetchTest`(12개), fixture `confluence/page.xhtml`.

| 예측 (10.8) | 결과 (측정) |
|---|---|
| 1. 파일럿 128블록과 ReqShield core 스냅숏이 바이트 단위로 같다 | 맞음. core 132블록을 새 도구로 다시 만들어 `diff -r`이 비었다. 가려진 블록은 없다 |
| 2. 기존 테스트는 모두 통과 | 맞음. 전체 732개 통과 |
| 3. fixture 블록 수가 요소 표대로 | 맞음. 결과 전에 적은 18블록, 줄 번호 3곳, 경고 2개가 모두 같았다 |
| 4. 토큰과 base64(`email:token`)의 출현 0번 | 맞음. 받기 12가지 경우와 받은 페이지의 스냅숏에서 0번 |
| 5. 비밀 KIND마다 블록 하나가 가려지고 값은 나오지 않음 | 맞음. 9종 모두 가려졌고, sources.json, SOURCES.md, 경고에 값이 없다 |

1. 기대값을 적다가 고친 것: 글자가 없는 모르는 매크로(예: `jira`)는 블록이 없어서 경고도 사라졌다. 이제 블록이 없어도 경고한다.
2. 받기 스크립트의 판단: 첫 실패 페이지에서 멈추고, 앞 페이지의 파일은 남긴다. 리다이렉트는 따라가지 않는다 (urllib는 리다이렉트할 때 Authorization 헤더를 새 주소로 옮긴다). `--site`는 scheme과 host만 받는다. `Retry-After`가 정수가 아니면 없는 것으로 본다. SPEC.md의 "jitter"는 10.8을 따라 넣지 않았다.
3. 고의 고장: 스냅숏 쪽 5개(CDATA 보호, slug, 가리기, 숫자 조건, NBSP)와 받기 쪽 11개를 테스트가 모두 잡았다.
4. 함정: Gradle 테스트 작업은 `translator/scripts/`를 입력으로 보지 않는다. 스크립트만 고쳤으면 `--rerun`이 필요하다.
5. 한계: `assigned-credential`의 키워드는 영어뿐이다 (`password`, `secret`, `api key`, `token` 등). 그래서 `비밀번호: hunter22x`처럼 한국어로 쓴 값은 가려지지 않는다. 모양으로 찾는 종류(비밀 키, AWS, Atlassian, GitHub, Slack, Anthropic, OpenAI, JWT)는 언어와 관계없다.
6. 남은 일: 수락 2 (실제 페이지 하나로 받기와 스냅숏, 그리고 10.8의 미확인 항목 확인). 연결이 생기면 한다.

### 10.10 C7 범위 검사 상세 설계 (2026-10-28)

사용자 결정 (2026-10-28): C7을 한다. 목적: 명세에 새 문단이 생겼는데 어느 절도 그것을 인용하지 않으면 알린다 (JC5). Confluence 페이지는 계속 바뀌므로 C6과 함께 쓴다.

**문법 (7.2 스케치를 구체화)**

```text
decl := ...
      | "scope" STRING { "reviewed" STRING [ "sha256" STRING ] }
      | "exclude" STRING "because" STRING [ "sha256" STRING ] { "reviewed" STRING [ "sha256" STRING ] }
```

1. `scope "S"`: 이 sidecar가 책임지는 스냅숏 범위다. 블록 B가 범위 안에 있는 조건은 셋 중 하나다: B의 anchor가 S와 같다; B의 anchor가 `S/`로 시작한다 (S는 절, 예: `README.md#waiting`); B의 원본 이름이 S와 같다 (S는 원본 전체, 예: `conf:123`).
2. `exclude "ANCHOR" because "REASON"`: 범위 안의 블록 하나를 일부러 담지 않는다. REASON은 5.4의 이유(모델 밖, 언어로 쓸 수 없음, 정보용 예시 등)를 글로 쓴다. `sha256`은 서명할 때 그 블록의 지문이다 (출처 줄의 블록 지문과 같은 뜻). 7.2 스케치의 두 문자열 사이에 `because`를 넣어, 어느 문자열이 이유인지 읽을 수 있게 했다.
3. `reviewed`: 서명 메모다. `scope`의 지문은 `scope "S"`를 덮는다. `exclude`의 지문은 `exclude "ANCHOR" because "REASON"`만 덮는다. 블록 지문 `sha256`은 서명 단위 밖이다: 출처 줄의 블록 지문처럼 상태이기 때문이다. 그래서 `exclude-context-changed` 뒤에 블록을 다시 읽고 블록 지문만 고치면, 다시 서명할 필요가 없다.

**검사 (`checkSidecar`, 스냅숏 없이)**

| 조건 | 오류 | `--draft`이면 |
|---|---|---|
| 같은 `scope` 문자열이 둘 | `duplicate-decl` | 오류 |
| 같은 `exclude` anchor가 둘 | `duplicate-decl` | 오류 |
| `review intent` 아래에서 메모 없는 `scope`, `exclude` | `unreviewed` | 오류 아님 |
| 메모에 지문이 없음 / 지문이 다름 | `missing-fingerprint` / `stale-review` | 오류 아님 / 오류 |
| `review intent` 아래에서 블록 지문 없는 `exclude` | `missing-block-fingerprint` | 오류 아님 |

`scope`를 서명하는 이유: `scope` 줄을 지우면 책임 범위가 조용히 줄어든다. 서명이 없으면 그것을 알 수 없다. 반대로, 서명이 지키지 못하는 것이 있다. 서명된 `scope` 줄을 통째로 지우면 메모도 함께 사라진다. 이 위험은 의도 절을 지우는 경우와 같고, 이 설계는 다루지 않는다.

**검사 (`kv spec stale`, 스냅숏과 함께; 5.3 표의 마지막 줄)**

| 조건 | 판정 |
|---|---|
| 범위 안의 블록을 어느 출처 줄의 anchor도 가리키지 않고, `exclude`도 없다 | 오류 `uncovered-block` (종료 코드 4) |
| `scope`에 맞는 블록이 하나도 없다 (오타, 원본 이름 바뀜) | 오류 `empty-scope` |
| `exclude`의 anchor가 스냅숏에 없다 | 오류 `stale-exclude` |
| `exclude`의 블록 지문이 다르다 | 경고 `exclude-context-changed` (사람이 그 블록을 다시 읽고 여전히 뺄지 정한다) |
| `exclude`의 블록이 어느 `scope`에도 없다 | 경고 `exclude-out-of-scope` |
| `exclude`의 블록을 출처 줄도 가리킨다 | 경고 `exclude-cited` |

1. "가리킨다"는 `source "ANCHOR" quote "..."` 줄의 anchor가 블록 anchor와 같다는 뜻이다. 인용이 다른 블록으로 옮겨 간 경우(`source-moved`)에는 새 블록이 `uncovered-block`이 된다. anchor를 고치면 둘 다 사라진다.
2. `source none` 줄은 어느 블록도 덮지 않는다. 범위 안의 블록은 인용하거나 `exclude`해야 한다. 어떤 절이 블록의 뜻을 담는다면, 그 절은 그 블록을 인용해야 한다.
3. `uncovered-block`과 `empty-scope`는 `scope`가 있을 때만 검사한다. `exclude`의 검사(`stale-exclude`와 경고 3종)는 `exclude`가 있으면 늘 한다: 사용자가 직접 쓴 선언이기 때문이다. 둘 다 없으면 C7 검사는 돌지 않는다. 그래서 기존 sidecar의 판정은 그대로다.
4. 서명된 `reqshield.core.kv`에는 이번에 `scope`를 넣지 않는다. 넣으려면 범위와 제외 목록에 사용자 서명이 필요하다. 대신 사본으로 측정한다 (예측 3).

**예측 (구현 전 등록)**

1. 기존 테스트는 모두 통과한다. `scope`가 없는 sidecar의 `kv spec stale` 출력은 요약 줄 밖에서 그대로다.
2. 수락 기준 (8절 C7): 범위 안 절에 새 블록을 더한 스냅숏 사본에서 `uncovered-block`이 정확히 1개 나온다.
3. 서명된 `reqshield.core.kv` 사본에 `scope "README.md"` 한 줄을 더하고 core 스냅숏으로 돌리면, `uncovered-block`이 정확히 46개 나온다. 근거: core 스냅숏의 README 블록은 48개이고, sidecar가 인용한 README anchor는 2개다 (도구 없이 Python으로 셈). 절별: top 1, req-shield 1, requirements 4, usage 2, release-versions 2, dev-snapshot-versions 3, integration-tests-with-redis-testcontainers 3, kotlin-with-reactor-3-8-spring-boot-4 2, webflux-null-handling 1, cache-key-layout 3, global-lock-guidance 4, local-lock-map-size 7, cache-eviction-semantics 2, waiting-for-another-request-lock-not-acquired 1, thread-pools-and-schedulers 4, webflux-adapter 2, kotlin-coroutine-adapter 1, contributing 1, license 1, how-to-reach-us 1. 이 값은 사용자가 범위를 정할 때의 자료다.

### 10.11 C7 결과 (2026-10-28, 메인 세션 기록)

구현 (메인 세션): `ScopeDecl`·`ExcludeDecl`(`Ast.kt`), 파서, `checkSidecar`의 중복·서명 검사(`Check.kt`), `kv spec stale`의 범위 규칙(`Stale.kt`의 `coverage`), CLI 요약 줄, 테스트 도우미 `rereviewed`. 테스트 `CoverageTest` 10개.

| 예측 (10.10) | 결과 (측정) |
|---|---|
| 1. 기존 테스트 통과, `scope` 없는 sidecar의 출력은 그대로 | 맞음. 전체 742개 통과. `scope`와 `exclude`가 없으면 C7 요약 줄도 나오지 않는다 (요약 줄도 그대로) |
| 2. 범위 안 절에 새 블록을 더한 사본에서 `uncovered-block` 정확히 1개 | 맞음 |
| 3. 서명된 core sidecar 사본에 `scope "README.md"`를 더하면 `uncovered-block` 46개 | 맞음. 절별 분포 20개 절도 미리 센 값과 같다 |

1. 고의 고장 6개(절 범위 규칙, 접두사 오인, 한 블록 두 번 보고, `exclude` 무시, 블록 지문이 서명 단위에 듦, `empty-scope` 끔)를 테스트가 모두 잡았다.
2. 측정 3의 뜻: README 전체를 범위로 잡으면, 46개 블록마다 인용 또는 제외 이유를 정해야 한다. 대부분은 이 sidecar의 모델 밖(설치, 릴리스, 다른 어댑터, 연락처)이다. 서명된 sidecar에 어느 범위를 넣을지는 사용자가 정한다.
3. 범위를 정할 자료 (측정, 서명된 core sidecar 사본): sidecar가 이미 인용하는 절 `README.md#waiting-for-another-request-lock-not-acquired`를 범위로 잡으면 `uncovered-block`은 1개다: `/2` (README 160-161행, "poll 중 캐시 읽기 실패 3번 연속이면 기다림을 끝낸다"). sidecar는 이 규칙을 어휘(`derived GAVE_UP = ... || consec >= 3`)에 담지만, 이 블록을 인용하는 절은 없다. `README.md#local-lock-map-size`(7개 모두 미인용)는 KeyLocalLock의 내용이라 이 sidecar의 범위가 아니다.
4. 메시지 개선: `exclude`에 절이나 원본 이름을 쓰면 `stale-exclude` 메시지가 "한 `exclude`는 블록 하나(SOURCE#slug/N)를 가리킨다"고 알린다.

### 10.12 서명된 core sidecar에 범위 넣기 (2026-10-28, 사용자 승인)

1. 더한 것 (`specs/reqshield/reqshield.core.kv`):
   - `scope "README.md#waiting-for-another-request-lock-not-acquired"`와 메모 (지문 `9dee5e78c92be221`, 메인 세션이 손으로 다시 계산함).
   - `guard LD_supplier`의 출처 줄: README 블록 `/2`(160-161행)의 "Three consecutive failures are treated as a cache outage and the request falls back to calling the supplier itself right away." (블록 지문 `4a446045254ea7eb`).
   - `LD_supplier`의 메모 지문 `7ab7e47c408a5ab2` → `ca26eadd855a7371` (메모 문구는 그대로).
2. 인용을 `LD_supplier`에 단 이유: 어휘(trace 블록)에는 출처 줄을 달 수 없다. `GAVE_UP`(`consec >= 3` 포함)을 쓰는 의도 절은 `LD_supplier` 하나다.
3. 결과 (측정):
   - `kv resolve` exit 0. `kv spec stale`: 오류 0, C7 줄 "1 scope(s), 0 exclude(s): 0 uncovered-block".
   - 생성 Viper 8개는 바이트 단위로 같다. core 위치표 2개는 sidecar 줄 번호만 다르다 (줄을 더했기 때문).
   - 원본 core는 정확히 RET_hit, RET_recheck, RET_supplier_error에서 FAILED다.
   - 바뀐 기대값 (회귀 아님): 메모 수 23 → 24 (`ClientCliTest`, `ClientGrammarTest`), `LD_supplier` 지문 (`FingerprintTest`), README 전체 범위 측정 46 → 45 (`CoverageTest`; 이제 `/2`를 인용하므로).
   - 전체 테스트 743개 통과 (새 테스트: 서명된 sidecar가 자기 범위를 모두 덮음). `mutantsClient` 42회 불일치 0.

### 10.13 한국어 비밀 키워드 (2026-10-28)

사용자 결정 (2026-10-28): 비밀 검사(10.9 5번의 한계)에 한국어 키워드를 더한다.

1. 키워드: 비밀번호, 비번, 암호, 패스워드, 시크릿, 비밀 키, 비밀키, 토큰, 액세스 토큰, 인증 토큰, API 키, API키. 한국어는 낱말을 붙여 쓰므로(예: `관리자비밀번호`) 왼쪽 경계를 요구하지 않는다.
2. 형태 A (`:`, `=`, 전각 `：` 뒤의 값): 영어 규칙과 같다. 값은 공백 없는 8자 이상이고 숫자를 담는다.
3. 형태 B (조사 `은`, `는`, `이`, `가`, `을`, `를` 뒤의 값): 값이 ASCII 글자와 숫자를 모두 담는 8자 이상일 때만 가린다. 이유: 한국어 명세에는 "비밀번호는 최소 8자 이상"처럼 규칙을 설명하는 문장이 많다. 그런 문장은 가리면 안 된다. 실제 비밀 값은 대개 ASCII다.
4. KIND는 영어와 같은 `assigned-credential`이다 (한 개념에 한 이름).

예측 (구현 전 등록):
1. 가려짐: `비밀번호: hunter22x`, `토큰 = abcd1234ef`, `API 키： key12345678`, `관리자비밀번호=pa55word1`, `비밀번호는 Hunter22x입니다`.
2. 가려지지 않음: `비밀번호는 최소 8자 이상이어야 한다`, `토큰은 요청마다 새로 발급한다`, `비밀번호는 12345678자리`(ASCII 글자 없음), `토큰: 필수`.
3. 기존 스냅숏(파일럿 128블록, ReqShield core 132블록)은 바이트 단위로 같고, 기존 테스트는 모두 통과한다.

결과 (2026-10-28, 측정):
1. 예측 1, 2 맞음: 가려야 할 5개는 가려지고, 규칙 문장 4개는 그대로다 (`StorageSnapshotTest`).
2. 예측 3 맞음: ReqShield core 스냅숏 132블록을 새 도구로 다시 만들어 바이트 단위로 같다. 파일럿 128블록도 같다 (`SnapshotTest`).
3. 고의 고장 3개(조사 형태의 ASCII 글자 조건 뺌, `:` 형태 끔, 조사 뒤의 아무 값)를 테스트가 모두 잡았다.
4. 남은 한계: 조사 형태는 ASCII 글자와 숫자가 든 값이면 비밀이 아니어도 가린다. 예: `암호는 AES256GCM이다`(암호화 방식을 뜻함). 가려진 블록은 경고에 anchor가 나오므로 사람이 확인할 수 있다.

### 10.14 L1 sidecar를 `review intent`로 옮기기 (2026-10-28, 사용자 승인)

사용자 결정 (2026-10-28): KeyLocalLock과 aspect의 sidecar 8개를 `review intent` 아래로 옮긴다 (UC4의 이동). orchestration-policy를 쓴다.

#### 10.14.1 설계

1. `review intent`는 `keylocallock.common.kv`와 `aspect.common.kv`에 한 줄씩 넣는다. 모듈 파일 6개가 이 두 파일을 include하므로 여섯 모듈 모두에 적용된다.
2. 출처는 ReqShield core 스냅숏(`specdraft/reqshield-core/snapshot`)의 블록이다. 인용할 README·CLAUDE.md 블록 11개의 지문은 파일럿 스냅숏(`pilot-c/packet`)과 같다 (측정).
3. 공유 파일의 출처는 세 변형에 공통인 텍스트(README, CLAUDE.md)만 인용한다. `KeyLock.kt`의 KDoc은 인용하지 않는다. 이유: 스냅숏에는 core의 KDoc만 있고, reactor의 KDoc은 문구가 다르고(`Emits ... completes EMPTY`), coroutine에는 KDoc이 없다 (측정, diff). 그래서 KDoc에만 있는 사실은 `source none`에 core KDoc의 줄을 적는다. 이것은 `reqshield.core.kv`의 `//` 주석 출처와 같은 관례다.
4. JG5 (`DESIGN-E.md` 12.10): 맨 위의 `set-once`를 의도 절로 만든다 (deep-reasoner 권고 (a)). `assume`처럼 출처 줄과 `reviewed` 메모를 받는다. 이유: `P_stable`은 어휘가 아니라 주장이다. `DESIGN-B.md:108`이 `P_same`, `P_once`와 같은 문장(`CLAUDE.md:27`)을 출처로 적었고, 모든 action이 그것을 보장하고, `kv spec check`의 칸 O1r, O1s를 정한다. 코드 변경:
   - `Ast.kt`: `SetOnceDecl`이 `IntentClause`가 된다. `intentClauses()`가 `set-once P_stable`을 내고, `else` 가지를 없애 모든 선언을 분류하게 한다 (`vocabularyItem`과 같은 방식).
   - `Parser.kt`: `set-once` 뒤에 `topSources()`와 `topReview()`. `unit`은 그 전에 잡는다.
   - `Check.kt:87`: 지문 검사가 `set-once`도 본다.
   - `Report.kt:434`: `review intent` 아래에서는 `set-once`를 두 번 싣지 않는다.
5. 범위: KeyLocalLock은 `scope "README.md#local-lock-map-size"`. 블록 `/1`, `/2`, `/4`는 인용하고, `/3`, `/5`, `/6`, `/7`은 이유와 함께 제외한다. aspect는 범위를 두지 않는다. 이유: README에 메서드별 캐시 map을 다루는 절이 없고, `CLAUDE.md#key-components`는 라이브러리 전체를 다룬다.

#### 10.14.2 서명할 항목

표시: **새 메모** = 사용자가 문구를 읽고 승인한다. **출처만** = 출처 줄만 더한다. 메모 문구는 그대로이고 지문만 다시 기록한다. **T-A** = 신뢰 선언의 지문이 계약 텍스트까지 덮게 되어 다시 기록한다. 문구는 그대로다.

`keylocallock.common.kv` (의도 절 21개 중 바뀌는 16개, 어휘, 범위):

| 절 | 표시 | 출처 | 메모 (새 메모만) |
|---|---|---|---|
| issue rule of tryLock | 출처만 | `README.md#global-lock-guidance/2` "Every lock acquisition carries an ownership token." | |
| ensures P1a | 새 메모 | `CLAUDE.md#key-components/2` "`tryLock` returns an ownership token", `CLAUDE.md#reqshieldconfiguration-parameters/4` "`lockTimeoutMillis`: Lock acquisition timeout" | a returned token is new and issued for this key, its acquisition is held, and its lease ends lockTimeoutMillis after the call's clock reading |
| ensures P1a_entry | 새 메모 | `README.md#local-lock-map-size/1` "keeps one entry per `(cache key, lock type)` it is currently locking" | a backed token is the token that its key's map entry carries |
| ends_only_if P3a of tryLock | 출처만 | `README.md#global-lock-guidance/2` "a slow holder whose lock already expired" | |
| unbacked_only_if P3b | 출처만 | `README.md#local-lock-map-size/4` "a request for a **new** key is handed a permit that no map entry backs." | |
| ensures progress_free | 새 메모 | `source none`: core KDoc `KeyLock.kt:30` (null only when another holder owns the lock); the variants differ | a call for a key with no entry that the cap does not refuse gets a token that an entry carries |
| ensures progress_live | 새 메모 | `source none`: 위와 같음 | a call for a key whose entry is held and not past its lease gets no token |
| ensures cap_unbacked | 새 메모 | `README.md#local-lock-map-size/4` (P3b와 같은 인용) | at the cap a new key gets a token that no entry carries, and no entry is made |
| ensures expired_takeover | 새 메모 | `README.md#global-lock-guidance/2` "a slow holder whose lock already expired can no longer release the lock of the next holder" | a call for a key whose entry is past its lease takes the lock over and gets a token that the entry carries |
| ensures P1 of unLock | 새 메모 | `CLAUDE.md#key-components/2` "`unLock` only releases when the token matches", `README.md#local-lock-map-size/2` "An entry is dropped as soon as the lock is released" | a successful release presented the token of the key's held entry; it ends that acquisition and drops the entry |
| ends_only_if P3a of unLock | 출처만 | `README.md#global-lock-guidance/2` "Only the holder that acquired the lock can release it" | |
| ensures progress_owner | 새 메모 | `source none`: core KDoc `KeyLock.kt:40` (false only when the lock is not held or the token does not match); the variants differ | a release with the token of the key's entry succeeds |
| ends_only_if P3a of sweep | 출처만 | `README.md#local-lock-map-size/2` "cleanup monitor once `lockTimeoutMillis` has passed" | |
| invariant P2 | 새 메모 | `source none`: a model fact; KeyLocalLock makes each entry's Semaphore with one permit (`KeyLocalLock.kt:206`, core) | an entry's semaphore holds 0 or 1 permits |
| lemma P3c | 새 메모 | `CLAUDE.md#key-components/2` "Locking mechanism (local or global) to prevent concurrent cache operations" | at most one acquisition per key is held at a time among the tokens that an entry backs |
| the probe | 새 메모 | `source none`: a non-vacuity scenario, not a requirement | a first call gets a backed token, a second call for the live key gets none, the release succeeds, and a stale release fails |
| review vocabulary | 새 메모 | (출처 없음) | the KeyLocalLock vocabulary: types Key and Token, class LockInfo with its four fields, ghost record tok with its field kinds, witness LockInfo.token |
| scope `README.md#local-lock-map-size` | 새 메모 | | this sidecar answers for every block of the README section on the local lock map |
| exclude `/3` | 새 메모 | because "configuration: the cap reaches the model only through the trusted fun rejectsNewEntry" | (같은 문구) |
| exclude `/5` | 새 메모 | because "the soft cap: the model takes the refusal as a free value, so it claims no bound on the map size" | (같은 문구) |
| exclude `/6` | 새 메모 | because "logging, outside the model" | (같은 문구) |
| exclude `/7` | 새 메모 | because "how LocalLockLimit reads the configured value, outside KeyLocalLock" | (같은 문구) |
| trusted nowToEpochTime, rejectsNewEntry | T-A | | |

그대로인 절 5개: refusal_keeps_entry, no_entry_created (unLock, sweep), entry_lease, entry_held. 이미 출처와 메모가 있다.

`aspect.common.kv`와 모듈 파일 3개:

| 절 | 표시 | 출처 | 메모 (새 메모만) |
|---|---|---|---|
| set-once P_stable | 새 메모 (JG5) | `CLAUDE.md#key-components/5` "One `ReqShield` instance per annotated method" | a mapped method keeps its ReqShield |
| ensures P_same | 새 메모 | 위와 같음 | a call that does not throw returns the ReqShield that the map holds for its method |
| ensures progress_ok | 새 메모 | `source none`: the code's computeIfAbsent; no sentence states it | a call whose factory does not throw does not throw |
| invariant P_once | 새 메모 | `CLAUDE.md#key-components/5` (같은 인용) | at most one ReqShield is made per method |
| the probe | 새 메모 | `source none`: a non-vacuity scenario, not a requirement | the same method gives the same object, and another method gives its own object |
| review vocabulary | 새 메모 | | the aspect vocabulary: type MethodKey (== is Method.equals) and class Shield with no modeled field |
| trusted createReqShield (모듈 3개) | T-A | | |

그대로인 절: factory_throw.

#### 10.14.3 예측 (결과 전 등록)

1. 생성 Viper: 여섯 L1 모듈의 Viper는 바이트 단위로 같다. 위치표는 sidecar 줄 번호만 다르다.
2. 판정: `verifyReqShield`, `verifyAspect`의 판정은 그대로다. `mutantsReqShield` 33회, `mutantsAspect` 30회, `mutantsClient` 42회에서 불일치 0.
3. `kv spec stale`: 여섯 모듈 모두 오류 0. KeyLocalLock은 범위 1개, 제외 4개, `uncovered-block` 0.
4. 서명 전 (`review intent`만 넣은 사본, 설치된 `kv`, `--root` 없음, 측정): KeyLocalLock 오류 28개 (`unreviewed` 12, `unsourced` 16), aspect 9개. JG5 코드 뒤에는 aspect에 2개(`set-once P_stable`의 `unreviewed`, `unsourced`)가 더해진다. `--root`로 묶으면 T-A의 `stale-review` 5개가 더해진다 (`DESIGN-E.md:1047`).
5. 바뀐 기대값 (회귀 아님):
   - 지문 값을 고정한 테스트: `FingerprintTest` 외. 공유하던 P3a 지문 `1c0ae67dbfe9757d`는 tryLock과 sweep에서 서로 달라진다 (출처가 다르다).
   - 보고서의 "Spec to review" 줄: `AspectVerifyTest.kt:60` 외.
   - sidecar 사본을 고치는 테스트: 새로 서명되는 절을 고치면 `stale-review`, 메모 없는 절을 더하면 `unreviewed`·`unsourced`, 어휘를 고치면 어휘 메모의 `stale-review`. 대상: `VerifyTest.kt:56`(P_false 더함), `:93`(progress_live), `ReserveVerifyTest.kt:93`(class LockInfo), `DecisionProbeTest.kt:110-111`(줄 단위 리터럴), `VerifyCliTest.kt:111`(지문 리터럴). 고치는 방법: 사람이 다시 서명하는 것처럼 `rereview`를 부르거나, 리터럴을 새 텍스트로 바꾼다.
   - JG5 메시지: `SourceLineTest.kt:94`.

#### 10.14.4 결과 (2026-10-28 ~ 29, 측정)

사용자 승인 (2026-10-28): 10.14.2의 표 전부와 JG5 (a). 메모의 날짜는 2026-10-28이다.

1. JG5 코드: 위 4번의 변경 그대로다. 새 테스트 8개(`SetOnceReviewTest`)가 통과한다. 고의 고장 3개(`intentClauses()`에서 `set-once`를 뺌, `Check.kt`의 지문 검사를 옛 필터로 되돌림, `unit`을 출처 줄 뒤에 잡음)를 각각 6개, 1개, 4개 테스트가 잡았다.
2. 서명: 지문은 도구가 낸 값을 그대로 적었다 (`missing-fingerprint`, `stale-review` 메시지). 바뀐 지문은 승인한 항목과 정확히 같다.
   - `keylocallock.common.kv`: 22개 (새 메모 17개, 출처만 더한 메모 5개).
   - `aspect.common.kv`: 새 메모 6개.
   - T-A: 5개. `nowToEpochTime` `aa569e5b9740b13b` → `a4f8bac168692c9e`, `rejectsNewEntry` `c5bffa07e14e90b1` → `d00c8149a8a320cb`, `createReqShield` core-spring `6e2380555c453306` → `7a7291901e9799d2`, webflux `8f53b3fc79cdbdbe` → `e23fb5f9beedd75e`, coroutine `cef1d3afd0eb0aec` → `3f9a5ea8a088a3f0`.
3. 예측 1 맞음: 여섯 모듈의 생성 Viper는 바이트 단위로 같다. 단, 머리 주석의 sidecar 절대 경로는 다르다. 기준을 다른 폴더(HEAD의 worktree)에서 만들었기 때문이다. 위치표는 sidecar 줄 번호만 다르다.
4. 예측 2 맞음: 여섯 모듈 모두 PROVEN. `mutantsReqShield` 33회, `mutantsAspect` 30회, `mutantsClient` 42회에서 불일치 0.
5. 예측 3 맞음: `kv spec stale`은 여섯 모듈 모두 exit 0이고 오류가 없다. KeyLocalLock은 인용 15개, `source none` 9개, 범위 1개, 제외 4개, `uncovered-block` 0. aspect는 인용 3개, `source none` 3개.
6. 예측 4 맞음: 서명 전 오류 수가 예측과 같다. KeyLocalLock 28개, aspect는 JG5 코드 뒤에 11개(9개와 `set-once P_stable`의 2개)다. 새 `kv`로 `review intent`만 넣은 사본을 다시 쟀다. T-A의 `stale-review`는 `--root`로 묶을 때 정확히 5개다.
7. 예측 5는 종류는 맞고, 범위는 작게 잡았다. 처음 전체 실행에서 752개 중 34개가 실패했다(14개 클래스). 모두 예측한 세 종류 중 하나였고, 로직 결함을 가리키는 실패는 없었다.
   - 고정한 값: 지문, sidecar 줄 번호, 메모 줄 리터럴 (`FingerprintTest`, `TrustedReviewTest`, `VerifyCliTest`, `CellsTest`, `SiliconTest`, `AspectVerifyTest`, `CheckTest`).
   - 새로 서명된 절이나 어휘를 고침 → `stale-review` (`VerifyTest`, `ReserveVerifyTest`, `SpecCheckPilotTest`, `SidecarResolveTest`, `AspectSidecarTest`).
   - 메모 없는 절이나 probe를 더함 → `unreviewed`·`unsourced` (`DecisionProbeTest`, `VerifyTest`, `AspectSidecarTest`).
   - 고친 방법: 사람이 다시 서명하는 것처럼 `rereview`를 부르거나, 더한 절에 시험용 출처와 메모를 달거나, 리터럴을 새 텍스트로 바꿨다. `TrustedReviewTest`의 지문 계산은 T-A 단위(계약 텍스트 다음 Kotlin 텍스트)로 바꿨다. `SiliconTest`는 줄 번호 64를 고정하는 대신 sidecar에서 `P1a_entry`의 줄을 읽는다.
   - `FingerprintTest`의 출처 줄을 지운 경우는 이제 `stale-review`와 `unsourced` 두 오류가 맞다 (`review intent`가 출처 줄을 요구한다).
8. 전체 테스트 752개 통과 (744개 + `SetOnceReviewTest` 8개).
9. 남은 한계:
   - 메모가 덮지 않는 것: `set-once`의 메모는 `set-once P_stable: m`라는 텍스트를 서명한다. 도구가 만드는 식(`Emitter.kt`)과 `m`의 층 1 묶기(JG4)는 덮지 않는다.
   - progress_free, progress_live, progress_owner는 `source none`이다. 그 사실을 KDoc만 말하고, 변형마다 KDoc이 다르기 때문이다. 변형마다 sidecar를 나누면 KDoc을 인용할 수 있다. 다른 새 `source none`은 이유가 다르다: P2는 모델 사실, progress_ok는 코드의 동작, 두 probe는 요구가 아닌 시나리오다.
   - aspect에는 범위가 없다. 새 README 절이 aspect 내용을 담아도 `uncovered-block`은 나오지 않는다.

### 10.15 AI-CODE 맞춤의 서명 회귀 고침 (2026-10-29, 사용자 승인)

회귀 (10.14가 만듦, JG4 분석 중 발견):
1. `scripts/aicode.py`는 `bindings.txt`의 맞춤을 sidecar 사본에 적용한다 (`AI-CODE.md` 6.1). `class LockInfo LockInfo` 맞춤은 `class LockInfo = ...` 줄을 바꾼다.
2. 10.14부터 이 줄은 서명된 어휘 메모가 덮는다. 그래서 사본은 `stale-review`가 되고, 실행은 "binding failure"로 멈춘다.
3. 영향 (측정, grep): `aicode/`의 `bindings.txt` 68개 중 39개가 이 맞춤을 쓴다. `local` 맞춤을 쓰는 것은 0개다.
4. 테스트가 잡지 못한 이유: `AicodeScriptTest`는 가짜 `kv`를 쓴다. 10.14의 `ReserveVerifyTest`는 같은 원인으로 실패했고, 테스트에서 `rereview`를 불러 고쳤다. 하네스는 고치지 않았다.

사용자 결정 (2026-10-29): 하네스가 선언된 맞춤을 적용한 사본에서 메모 지문을 다시 찍는다. 사본은 서명된 원본이 아니고, 맞춤 목록은 결과에 남는다.

고친 것:
1. `kv spec restamp SIDECAR.kv...` (새 CLI): 테스트의 `rereviewed`를 main 코드(`sidecar/Restamp.kt`)로 옮겼다. 의도 절, 어휘, `scope`, `exclude`의 메모를 지금 텍스트의 지문으로 다시 찍고, 바뀐 메모를 한 줄씩 출력한다. 신뢰 선언의 메모는 건드리지 않는다 (그 지문은 Kotlin 텍스트를 담아서 `kv resolve`만 계산한다). 한 파일이라도 구문 오류면 어느 파일도 쓰지 않고 exit 4다. 테스트의 `rereviewed`는 이 함수를 부른다.
2. `aicode.py`: 맞춤을 적용한 뒤, 바뀐 사본 파일에 `kv spec restamp`를 부른다. 다시 찍은 메모는 `tool.restamped`에 기록한다.

결과 (측정):
1. 실제 실행 (`aicode/round2` 사본, T-haiku-1, `class LockInfo LockInfo`): HEAD 스크립트는 "binding failure: ... error[stale-review]: the vocabulary changed"다. 고친 스크립트는 "FAILED sweep (property), tryLock (property)"이고, 두 행 모두 `ends_only_if P3a`다. 이것은 `ReserveVerifyTest` AT3의 단언과 같다. `restamped`는 어휘 메모 하나(`291a2e826122f824` → `fc02a90ab69d4fb6`)다.
2. 새 테스트: `RestampTest` 3개, `AicodeScriptTest`의 맞춤 테스트에 단언을 더했다. 가짜 `kv`는 이제 `kv spec ...`을 실제 CLI로 넘기고, 테스트는 사본에 실제 `checkSidecar`를 돌린다.
3. 고의 고장 3개를 모두 잡았다: `aicode.py`의 restamp 호출 제거, 모든 파일을 읽기 전에 쓰기, 신뢰 메모도 다시 찍기.

### 10.16 JG4: `free` 묶기 오류를 `kv resolve`에서 내기 (2026-10-29, 사용자 승인, 결과 전 등록)

배경 (deep-reasoner 측정, 사본 38회, 한 번에 한 줄을 같은 타입의 다른 값으로 묶음):
1. 거짓 PROVEN은 0이었다. FAILED 10, ERROR 20, resolve 오류 8이었다.
2. 문제: FAILED는 코드 결함처럼 보이고, ERROR는 잘못된 `free` 줄을 가리키지 않는다. 예: `free maxAttempt`를 `decisionForUpdate`에 묶으면, client lowering이 `maxAttempt`를 선언하지 않는다 (`ClientLowering.kt`는 같은 경로를 번역한 읽기에만 sidecar 이름을 준다). 그러면 Silicon이 "undeclared identifier"를 내고 모듈은 ERROR다.
3. critic 검토 뒤 advisor 권고: JG4는 B1 + B2c(좁힘)로 한다. 묶기의 서명(A안)은 이번에 하지 않는다 (사용자 결정 2026-10-29).

설계:
1. **B1** `free-duplicate`: 한 연산의 `free` 항목 둘이 같은 대상에 묶이면 `kv resolve` 오류다. 대상은 local, 매개변수, 속성(client는 속성 경로 전체), call이다. 오류는 두 번째 항목의 줄에 난다.
2. **B2c** `free-unused` (client 모듈): sidecar 텍스트(절, guard, derived 값, hole)가 쓰는 `free` 이름을 어느 지원 단위(entry와 그 task들을 합침)도 선언하지 않으면, `kv resolve`가 그 `free` 줄에 오류를 낸다. 메시지는 같은 타입의 이름 없는 읽기의 코드 위치를 말한다. 미지원 구문 안에서만 읽히는 경우는 오류를 내지 않는다 (판정은 INCOMPLETE 그대로).

예측 (구현 전 등록):
1. 서명된 sidecar 9개: 새 오류 0. 생성 Viper와 판정은 그대로다. 변이 33/30/42회 불일치 0. 서명 지문 변경 0.
2. B1이 잡는 것: L1(tryLock `now = this.lockTimeoutMillis`), L2(`timeout = local now`), C4, C5(`maxAttempt`·`mode`가 서로의 경로에 묶임).
3. B2c가 잡는 것: c1(`maxAttempt = ...decisionForUpdate`), C2, C3, C7. 모두 지금은 Silicon ERROR다.
4. 잡지 못하는 것 (그대로 FAILED 또는 ERROR): 두 묶기를 맞바꾼 경우(L8, C6), L1 모듈의 `param`·`local` 오묶기(L4, L6, L7).
5. 기존 테스트: 바뀌는 것이 없다 (deep-reasoner의 grep 근거, 실행 전).

결과 (2026-10-29, 측정):
1. 구현 (orchestration-policy): B1은 메인 세션이 했다 (`frontend/Resolver.kt`, `duplicateFree`). B2c는 feature-developer가 격리된 worktree에서 했다 (`lower/ClientLowering.kt`, `freeUnused`). 메인 세션이 diff를 검토하고 가져왔다.
2. 오류 형식:
   - `free-duplicate`: `free tryLock.timeout binds the value that free tryLock.now binds (lockTimeoutMillis); each free item of an operation names its own value`
   - `free-unused` (c1 실제 출력): `free getAndSet.maxAttempt: this.reqShieldConfig.decisionForUpdate is read by no translated code, but maxAttempt is used by POLL_bound, derived GAVE_UP, hole loop ReqShield.handleLockFailure; unnamed translated reads of a Kotlin Int: ReqShield.kt:181:27 (this.reqShieldConfig.maxAttemptGetCache) (DESIGN-C 10.16)`
3. 예측 1 맞음: 여섯 L1 모듈의 생성 Viper와 위치표가 바이트 단위로 같다 (be1b4df의 worktree와 비교). client Viper는 `ClientGateTest`가 같음을 확인한다. 변이 33/30/42회 불일치 0. 서명 지문 변경 0.
4. 예측 2 맞음: L1, L2(세 변형 모두), C4, C5(원본과 고친 core)가 `free-duplicate`로 `kv resolve` exit 1이다.
5. 예측 3은 하나가 틀렸다: c1, C2, C7은 `free-unused`로 잡힌다. C3(`free maxAttempt = param timeToLiveMillis`)는 잡히지 않고, 전처럼 Silicon ERROR다. 이유: client lowering은 매개변수에 sidecar 이름을 주지 않는다 (`name__line`으로 선언). 그래서 B2c는 `this.` 출처만 검사한다. 이 경우에 "read by no translated code"라는 메시지가 틀리기 때문이다.
6. 예측 4 맞음: L4, L6, L8, C6은 `kv resolve` exit 0이다 (FAILED 또는 ERROR 그대로). L7은 core에서만 resolve되고 (FAILED), coroutine과 reactor에서는 전처럼 `unresolved`다.
7. 예측 5 맞음: 기존 테스트는 하나도 바꾸지 않았다. 새 테스트 11개: `FreeDuplicateTest` 4, `FreeUnusedTest` 6, `ClientCliTest` 1. 전체 테스트 766개 통과.
8. 고의 고장: B1 검사를 끄면 `FreeDuplicateTest` 4개가 모두 실패한다. B2c는 4개(검사 호출 제거, 미지원 단위 건너뛰기 제거, "텍스트가 이름을 쓰는가" 조건 제거, task 무시)를 모두 잡았다. 세 번째를 깨면 기존 테스트 11개도 실패한다. 그래서 이 좁힘이 필요하다.
9. 남은 한계:
   - client `param` 출처의 `free`는 lowering이 이름을 주지 않는다. 절이 그 이름을 쓰면 지금도 Silicon "undeclared identifier"다 (C3). 고치거나 금지해야 한다.
   - 맞바꾼 두 묶기(L8, C6)와 L1의 `param`·`local` 오묶기(L4, L6, L7)는 잡지 못한다. 이것을 잡는 길은 묶기의 서명(A안)뿐이고, 그것도 서명 뒤 편집만 잡는다. 사용자 결정 (2026-10-29): 이번에는 하지 않는다.
   - 묶인 Kotlin 지역 변수의 계산식을 바꾸는 편집(K1-K3: `val now = nowToEpochTime() + lockTimeoutMillis`, sweep의 `now + 1000`, `System.nanoTime()`)은 PROVEN 그대로다 (deep-reasoner 측정). `DESIGN-v0.md` 2.12 규칙 1의 설계 결과이고, JG4 밖이다. 사용자 결정 (2026-10-29): 알려진 한계로 문서에만 남긴다.

### 10.17 client `param` 출처의 `free` 이름 (2026-10-29)

사용자 결정 (2026-10-29): 남은 일 1번을 진행한다. 방법(지원 또는 금지)은 메인 세션이 정했다: 지원한다. L1에서는 `param` 출처가 이미 동작하고, 리졸버도 client의 `param`을 받기 때문이다.

문제 (10.16 결과 9): client lowering은 매개변수를 `P__LINE`으로 선언한다 (`ClientLowering.materialize`). 그래서 `free NAME = param P`의 NAME을 쓰는 절은 Silicon "undeclared identifier"가 된다. `free-unused`는 `this.` 출처만 검사했다.

고친 것:
1. `materialize`: entry의 매개변수를 `free NAME = param P`가 이름 붙이면, 지역 변수 이름을 NAME으로 한다. 이것은 이름 붙은 `this.` 경로와 같은 방식이다. task는 부모에게서 값을 받는다(spawnValue).
2. `freeUnused`: `param` 출처도 검사한다. entry의 매개변수 이름도 선언된 이름으로 센다. 메시지: "param P is read by no translated code, or it is the obligation key, which the sidecar reads as k". 이유: obligation의 key 매개변수는 언제나 지역 변수 `k`다 (DESIGN-E 3.1). 그래서 다른 이름은 그것을 선언하지 않는다.

예측: 이번에는 구현 전에 등록하지 않았다 (절차 누락). 메인 세션은 C3(`free maxAttempt = param timeToLiveMillis`)가 이름을 얻어 resolve될 것으로 짐작했다. 이 짐작은 틀렸다.

결과 (측정):
1. C3는 이제 `free-unused`로 잡힌다. getAndSet은 `timeToLiveMillis`를 모델이 버리는 인자(`setCacheFunction`, `decideToUpdateCache`)로만 읽는다. 그래서 번역된 코드는 그것을 읽지 않는다. 10.16에서 잡지 못한 C3가 이제 resolve 단계에서 그 `free` 줄을 가리킨다.
2. 매개변수를 읽는 fixture에서는 매개변수가 sidecar 이름(`n`)의 지역 변수가 되고, 오류가 없다. `free` 줄이 없으면 이름은 전처럼 `limit__LINE`이다.
3. 서명된 core sidecar는 `param`을 쓰지 않는다. 그래서 생성 Viper는 바뀌지 않는다 (`ClientGateTest`).
4. 새 테스트 3개(`FreeParamTest`). 고의 고장 2개(이름 붙이기를 끔, `param` 검사를 뺌)를 모두 잡았다.
5. 전체 테스트 794개 통과 (10.18, `AI-CODE.md` 16의 변경과 함께 돌림). 변이 33/30/42회 불일치 0.

### 10.18 G-opus-1의 느린 pass 2 (2026-10-29, 결과 전 등록)

원인 (deep-reasoner 측정, `AI-CODE.md` 15.2의 "원인은 재지 않았다"):
1. pass 2(성질 검사)는 Silicon을 프로그램 전체에 한 번 돌린다 (`verify/Report.kt` pass 2). 그래서 sidecar probe도 검증한다. 그런데 kv는 pass 2의 probe 오류를 하나도 읽지 않는다. kv가 읽는 것은 action 메서드의 오류뿐이다.
2. pass 2에서는 hole 보장이 빠진다. 그래서 probe에서 tryLock 다음 호출들은 `requires MI(S)`를 세우지 못하고 실패한다. 실패마다 Silicon의 state consolidation이 ghost 필드의 quantified 권한 묶음을 쌍마다 비교한다. 비교 수는 호출 수의 제곱에 비례한다. reserve 단계(gPend 필드, `reserve_tryLock` 호출)가 이것을 키운다.
3. 측정 (G-opus-1): probe만 62.8 s, tryLock만 4.4 s. probe를 빼면 pass 2는 83.7 s에서 4.5 s로 줄고, action 오류 2개(tryLock P3a, sweep P3a)는 같다. G-haiku-2(reserve 없음)도 pass 2 10.8 s 중 10.1 s가 probe다.

설계: pass 2의 Silicon 실행에만 `--excludeMethods <probe 이름들>`을 준다. 이름은 pass 2 프로그램의 PROBE 메서드 이름을 `|`로 잇는다.
- `--includeMethods`는 쓰지 않는다. 맞는 메서드가 없으면 아무것도 검증하지 않고도 "finished successfully"라고 출력하기 때문이다 (deep-reasoner 측정).
- 메인 세션이 확인했다: `|`로 여러 이름을 뺄 수 있다 (`probe|tryLock`은 tryLock 오류를 없앤다). 패턴은 이름 전체와 맞춘다 (`probe|tryLoc`은 tryLock을 빼지 않는다).

예측:
1. 생성 Viper(`.vpr`, `.pass2.vpr`)는 바이트 단위로 같다. pass 1은 바뀌지 않는다.
2. 서명된 모듈 7개의 판정은 그대로다. 여섯 L1 원본은 pass 2를 돌리지 않고, client core에는 pass 2가 없다.
3. 변이 33/30/42회에서 불일치 0. pass 2를 쓰는 변이는 빨라지기만 한다.
4. G-opus-1 실행 (`aicode.py tool`, round4 사본): pass 2가 100 s 아래(측정 기대 약 5 s)이고, 판정은 전과 같다 (tryLock, sweep FAILED P3a).
5. 기존 테스트는 바뀌지 않는다.

결과 (2026-10-29, 측정):
1. 구현: `Silicon.run`에 추가 인자 `extra`를 더했고, pass 2만 `skipProbes(pass2)`를 넘긴다 (`verify/Report.kt`). 메인 세션이 했다 (orchestration-policy 규칙 1: 바꿀 곳과 내용이 정해짐).
2. 예측 4 맞음: G-opus-1(round4 사본)의 pass 2가 4.41 s다. 기록은 86.55 s였다. 판정은 같다 (tryLock: pass 1 MI_INV_1, pass 2 P3a; sweep: P3a). 도구 단계 전체는 16.4 s다.
3. 예측 1 맞음: 여섯 L1 모듈의 생성 Viper와 위치표는 HEAD(`7fadc34`)와 바이트 단위로 같다.
4. 새 테스트 `SkipProbesTest` 1개: 이름 없는 probe와 이름 있는 probe 2개가 패턴에 들어가고, action은 들어가지 않는다. 고의 고장 2개를 잡았다: probe를 빼지 않으면 이 테스트가, 모든 메서드를 빼면 `PropertyCheckTest`가 실패한다.
5. 예측 2, 3, 5 맞음: 변이 33/30/42회 불일치 0. 기존 테스트는 바꾸지 않았고, 전체 테스트 794개 통과.
