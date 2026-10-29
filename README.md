# kotlin-verifier

AI가 생성한 Kotlin/JVM 코드가 우리의 명세대로 작성되었는지 **증명**하는 도구다. 테스트가 아니라 모든 경우를 다루는 증명이다.

Microsoft Research의 PDD Kit("의도 → 형식 명세 → 코드가 명세를 만족함을 증명")와 같은 일을 Kotlin/JVM에서 한다.
대상은 AI가 쓴 코드와 기존 코드(req-shield) 모두다. 동시성이 있는 코드를 다룬다.

상태 (2026-10-27): **잠시 멈춤.**

## 동작 방식

```
Kotlin 소스 (수정 없음) ─┐
                          ├─> K2 컴파일러 플러그인 (FIR) ─> KVIR ─> Viper 프로그램 ─> Silicon (Z3) ─> 보고서
sidecar 명세 (.kv) ───────┘                                                                       (성질마다 PROVEN / FAILED)
```

1. 사람이 명세를 sidecar 파일(`specs/**/*.kv`)에 쓴다. 각 절에는 원문 근거(`source`)와 서명(`reviewed ... sha256`)이 붙는다.
2. 도구가 Kotlin 소스를 K2 FIR로 읽고, sidecar의 이름을 코드의 심볼에 묶는다 (`kv resolve`).
3. 도구가 코드를 중간 표현 KVIR로 낮추고, 지원하지 않는 구문을 거부한다 (`kv lower`).
4. 도구가 Viper 프로그램을 만들고 Silicon으로 증명한다 (`kv emit`, `kv verify`).
5. 보고서는 성질마다 PROVEN 또는 FAILED를 낸다. 서명된 절이나 신뢰한 함수 본문이 바뀌면 `stale-review` 오류가 난다.

다루는 모듈 종류는 세 가지다.

| 종류 | 예 | 설계 문서 |
|---|---|---|
| L1 상태 모듈: 연산 하나가 `ConcurrentHashMap.compute` 하나 | req-shield `KeyLocalLock` (core, coroutine, reactor) | `DESIGN-v0.md` |
| aspect 캐시: `getOrCreate`와 생성 함수 | req-shield `ReqShieldAspect` (3개 모듈) | `DESIGN-B.md` |
| L3 client 프로토콜: lock 의무, 사건 기록, 백그라운드 과제 | req-shield core `ReqShield` | `DESIGN-E.md` |

## 측정 결과 (요약)

모든 수치는 측정값이다. 상세는 괄호 안의 문서에 있다.

- 변이 검사: KeyLocalLock 33회, aspect 30회, client 42회. 기대 판정과의 불일치는 0이다 (`MUTATION.md`, `PROTOCOL2.md`).
- AI가 쓴 구현 24개 (action 72개): 70개를 판정했다. 블라인드 판정자와 비교한 결과, 놓친 결함 0, 잘못된 경보 0이다 (`AI-CODE.md`).
- req-shield core `ReqShield`: 도구가 결함 3개(RET_hit, RET_recheck, RET_supplier_error)를 보고했다. 고친 코드는 PROVEN이다. 이 수정은 req-shield에 커밋했다 (`PROTOCOL2.md` 6-7절).
- 테스트 687개가 통과한다.

## 필요한 것

1. JDK 21.
2. Viper 릴리스 v.26.08의 `tools` 디렉터리 (Silicon과 Z3). 환경 변수 `VIPER_HOME`으로 지정한다.
   지정하지 않으면 `~/.local/opt/viper/v.26.08-release/tools`를 쓴다.
3. req-shield의 `2e792d2` 커밋 checkout과 그 jar (`*/build/libs/*-1.0.0.jar`, req-shield에서 `./gradlew build`로 만든다).
   기본 경로는 worktree `/Users/ykhfree/IdeaProjects/req-shield-verifier-pin`이다.
   다른 경로는 Gradle 속성으로 준다: `./gradlew -PreqShieldDir=/path/to/req-shield test`.
   테스트와 변이 기대값은 이 커밋에 맞춰져 있다. 다른 커밋을 쓰면 테스트가 실패할 수 있다.

## 빌드와 실행

모든 명령은 `translator/`에서 실행한다.

```bash
cd translator
./gradlew test            # 테스트 전체 (Silicon 실행 포함, 측정 약 21분)
./gradlew installDist     # CLI: build/install/kv/bin/kv
```

자주 쓰는 Gradle 작업:

| 작업 | 하는 일 |
|---|---|
| `verifyReqShield` | KeyLocalLock 3개 변형을 증명하고 보고서를 출력한다 |
| `verifyAspect` | aspect 캐시 모듈을 증명한다 |
| `resolveClient`, `lowerClient` | ReqShield core sidecar를 묶고, KVIR를 출력한다 |
| `mutantsReqShield`, `mutantsAspect`, `mutantsClient` | 변이 검사를 돌리고 기대 판정과 비교한다 |

CLI 명령 (`kv`를 인자 없이 실행하면 전체 사용법이 나온다):

```
kv discover | resolve | lower | emit | verify   # 소스 → 증명의 각 단계
kv mutation-sites                                # 변이를 넣을 위치
kv hole skeleton | check                         # LLM이 채우는 불변식 hole
kv spec snapshot | stale | restamp | check | cells   # 자연어 명세 → sidecar 흐름 (DESIGN-C)
```

Confluence Cloud 페이지를 명세로 쓰는 방법 (`DESIGN-C.md` 10.8):

1. API 토큰을 Keychain에 한 번 저장한다. 토큰은 프롬프트에서 입력한다 (명령줄에 쓰지 않는다): `security add-generic-password -s SERVICE -a EMAIL -w`
2. 페이지를 받는다: `python3 -I translator/scripts/confluence_fetch.py --site https://SITE.atlassian.net --email EMAIL --keychain-service SERVICE --out DIR PAGE_ID...`
3. 스냅숏을 만든다: `kv spec snapshot --out SNAP conf:PAGE_ID=DIR/PAGE_ID.xhtml`. 비밀 모양의 문자열이 든 블록은 가려진다. 한국어 이름(`비밀번호: ...`, `토큰 = ...`)도 잡는다. 단, `비밀번호는 ...`처럼 조사 뒤의 값은 ASCII 글자와 숫자를 모두 담을 때만 가린다 (`DESIGN-C.md` 10.13).
4. 받은 폴더(DIR)는 원문 그대로라서 커밋하지 않는다.

명세에 새 문단이 생겼을 때 빠뜨리지 않으려면, sidecar에 범위를 적는다 (`DESIGN-C.md` 10.10):

```
scope "conf:123456"                                              // 이 페이지 전체를 책임진다
exclude "conf:123456#배경/1" because "정보용 설명" sha256 "..."   // 일부러 담지 않는 블록과 그 이유
```

`kv spec stale`은 범위 안의 블록을 어느 절도 인용하지 않고 제외 목록에도 없으면 `uncovered-block` 오류를 낸다. `review intent` 아래에서는 `scope`와 `exclude`에도 서명(`reviewed`)이 필요하다.

## 폴더 구조

| 경로 | 내용 |
|---|---|
| `translator/` | 도구 본체 (Kotlin, Gradle). `src/main/kotlin/kv/`, 스크립트는 `scripts/` |
| `specs/reqshield/` | req-shield의 서명된 sidecar 명세 |
| `spike/` | 손으로 쓴 Viper 기준 모델. 도구의 출력을 이것과 비교한다 |
| `mutation/` | 변이 검사의 결과와 블라인드 판정 |
| `aicode/` | AI가 쓴 구현의 수집, 판정, 도구 결과 |
| `pilot-c/`, `specdraft/` | 자연어 명세에서 sidecar를 만드는 흐름의 파일럿과 원문 스냅숏 |
| `frontend-probe/` | 초기 탐색: KeyLocalLock의 K2 FIR을 출력하는 플러그인 |
| `upstream/` | SnaKt 저장소의 기존 이슈에 남길 댓글 초안 (게시하지 않음) |

## 문서

| 문서 | 내용 |
|---|---|
| `PHASE1.md` | 조사와 1단계 계획 |
| `DESIGN-v0.md` | KVIR, sidecar 명세, ghost 상태 (L1 모듈) |
| `DESIGN-B.md` | aspect 캐시 모듈 |
| `DESIGN-C.md` | 자연어 명세 → sidecar 흐름, 서명 규칙 |
| `DESIGN-D.md` | compute 밖의 예약 단계 (AI 코드 대응) |
| `DESIGN-E.md` | L3 client 프로토콜 (ReqShield) |
| `MUTATION.md`, `AI-CODE.md`, `PROTOCOL2.md` | 측정 계획(결과 전 예측)과 결과 |

## 열린 과제

- `free` 묶기는 서명이 덮지 않는다. `kv resolve`는 한 값을 두 번 묶는 것(`free-duplicate`)과, client에서 읽히지 않는 경로에 묶은 것(`free-unused`)을 잡는다 (`DESIGN-C.md` 10.16). 두 묶기를 맞바꾼 경우와 L1의 `param`·`local` 오묶기는 아직 잡지 못한다. 그런 sidecar는 전처럼 FAILED나 ERROR가 된다.
- 묶인 Kotlin 지역 변수의 계산식을 바꿔도(예: `val now = nowToEpochTime() + lockTimeoutMillis`) PROVEN이 그대로다. 도구가 `free` 값의 계산을 번역하지 않기 때문이다 (`DESIGN-v0.md` 2.12 규칙 1, `DESIGN-C.md` 10.16).
- Confluence 가져오기(C6)는 공식 API 스펙에 맞춰 만들었지만, 실제 Confluence 페이지로는 아직 확인하지 않았다 (`DESIGN-C.md` 10.8의 미확인 항목).
- coroutine 변형의 client 프로토콜(E5)은 결정 UE6이 필요하다.
