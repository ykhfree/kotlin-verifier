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
                                                                 // clause, invariant, lemma or probe
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
