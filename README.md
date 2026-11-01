# kotlin-verifier

AI가 생성한 Kotlin/JVM 코드가 우리의 명세대로 작성되었는지 **증명**하는 도구다. 테스트가 아니라 모든 경우를 다루는 증명이다.

Microsoft Research의 PDD Kit("의도 → 형식 명세 → 코드가 명세를 만족함을 증명")와 같은 일을 Kotlin/JVM에서 한다.
대상은 AI가 쓴 코드와 사람이 쓴 기존 코드 모두다. 동시성이 있는 코드를 다룬다.

## 동작 방식

```
Kotlin 소스 (수정 없음) ─┐
                          ├─> K2 컴파일러 플러그인 (FIR) ─> KVIR ─> Viper 프로그램 ─> Silicon (Z3) ─> 보고서
sidecar 명세 (.kv) ───────┘                                                                       (성질마다 PROVEN / FAILED)
```

1. 사람이 명세를 sidecar 파일(`.kv`)에 쓴다. 각 절에는 원문 근거(`source`)와 서명(`reviewed ... sha256`)이 붙는다.
2. 도구가 Kotlin 소스를 K2 FIR로 읽고, sidecar의 이름을 코드의 심볼에 묶는다 (`kv resolve`).
3. 도구가 코드를 중간 표현 KVIR로 낮추고, 지원하지 않는 구문을 거부한다 (`kv lower`).
4. 도구가 Viper 프로그램을 만들고 Silicon으로 증명한다 (`kv emit`, `kv verify`).
5. 보고서는 성질마다 PROVEN 또는 FAILED를 낸다. 서명된 절이나 신뢰한 함수 본문이 바뀌면 `stale-review` 오류가 난다.

## 지원하는 코드 모양

도구는 다음 세 가지 모양의 모듈을 다룬다. 다른 모양의 코드는 sidecar를 써도 증명할 수 없다.

| 모양 | 설명 | 설계 문서 |
|---|---|---|
| L1 상태 모듈 | 연산 하나가 `ConcurrentHashMap.compute` 하나다 | `DESIGN-v0.md` |
| aspect 캐시 | `getOrCreate`와 생성 함수로 객체를 한 번만 만든다 | `DESIGN-B.md` |
| L3 client 프로토콜 | lock 의무, 사건 기록, 백그라운드 과제(`CompletableFuture.runAsync`, kotlinx `launch`)가 있다 | `DESIGN-E.md` |

## 설치

1. JDK 21.
2. Viper 릴리스 v.26.08의 `tools` 디렉터리 (Silicon과 Z3). 환경 변수 `VIPER_HOME`이나 `--viper-home`으로 지정한다.
   지정하지 않으면 `~/.local/opt/viper/v.26.08-release/tools`를 쓴다.
3. CLI를 만든다. 결과는 `translator/build/install/kv/bin/kv`다.

```bash
cd translator
./gradlew installDist
```

## 사용법

1. 후보를 찾는다: `kv discover --classpath CP FILE.kt|DIR...`. client 모듈은 `--client`를 붙인다.
2. sidecar를 쓴다: 모듈, 소스 파일, 상태, 신뢰 모델, 성질. 예는 `specs/`에 있다.
3. 내장 신뢰 모델에 없는 호출(예: DB, HTTP client)은 `trusted fun`으로 계약을 쓰고 사람이 서명한다.
4. 이름을 묶는다: `kv resolve --root DIR --classpath CP SIDECAR.kv`.
5. 지원하지 않는 구문을 확인한다: `kv lower --root DIR --classpath CP SIDECAR.kv`.
6. 증명한다: `kv verify --root DIR --classpath CP SIDECAR.kv`.

`--root`는 sidecar의 `source` 경로가 기준으로 삼는 디렉터리다. `--classpath`는 대상 코드가 쓰는 jar다.

CLI 명령 (`kv`를 인자 없이 실행하면 전체 사용법이 나온다):

```
kv discover | resolve | lower | emit | verify        # 소스 → 증명의 각 단계
kv mutation-sites                                    # 변이를 넣을 위치
kv hole skeleton | check                             # LLM이 채우는 불변식 hole
kv spec snapshot | stale | restamp | check | cells   # 자연어 명세 → sidecar 흐름 (DESIGN-C)
```

## 판정 읽기

`kv verify`의 exit code: 0 PROVEN, 1 FAILED, 2 사용법 오류, 3 INCOMPLETE, 4 ERROR (컴파일 오류, sidecar 오류, 규칙 위반, Silicon 없음, Silicon 비정상 실행).

판정 규칙 (`DESIGN-v0.md` 5.8):

| 대상 | 판정 |
|---|---|
| action | PROVEN: 그 메서드에 오류 없음. FAILED: 오류 1개 이상. UNSUPPORTED: 지원하지 않는 구문이 있어 출력하지 않음. ERROR: 실행 비정상 |
| 모듈 | PROVEN: 모든 action, init, lemma, probe가 PROVEN. FAILED: 하나라도 FAILED. INCOMPLETE: FAILED는 없고 UNSUPPORTED가 있음. ERROR: 실행 비정상 |

INCOMPLETE는 "일부만 증명됨"이 아니다. 모델링하지 않은 action이 불변식을 깰 수 있다. 그래서 다른 action의 증명은 "모델링한 action만 있을 때"의 증명이다.

PROVEN은 모델 위의 증명이다. Silicon이 확인하지 않는 가정이 있다 (전체 목록은 `DESIGN-v0.md` 부록 B). 보고서의 `Assumptions` 줄이 해당 모듈의 가정을 싣는다.
1. `ConcurrentHashMap.compute`는 key마다 원자적이고, 함수를 정확히 한 번 부른다.
2. `Long`은 무한 정수다. 지점마다 진단만 낸다.
3. action 밖에서 읽은 값(시계, 경쟁 읽기, 인스턴스 값)은 임의 값이다.
4. `trusted fun`의 계약은 사람이 본문을 읽고 확인했다.
5. 로그와 거부 카운터는 모델 상태에 영향이 없다.

## 자연어 명세에서 sidecar 만들기

명세가 Markdown이나 Confluence 페이지에 있을 때 쓴다 (`DESIGN-C.md`).

1. 원문을 블록으로 나눈다: `kv spec snapshot --out SNAP NAME=FILE...`. 블록마다 앵커와 지문이 붙는다.
2. sidecar의 의도 절마다 원문을 인용한다: `source "<anchor>" quote "<인용>"`.
3. 원문이 바뀌었는지 확인한다: `kv spec stale --snapshot SNAP SIDECAR.kv`. 인용이 원문 어디에도 없으면 `stale-source` 오류다.
4. 사람에게 보이기 전에 검사한다: `kv spec check`.

명세에 새 문단이 생겼을 때 빠뜨리지 않으려면, sidecar에 범위를 적는다 (`DESIGN-C.md` 10.10):

```
scope "conf:123456"                                              // 이 페이지 전체를 책임진다
exclude "conf:123456#배경/1" because "정보용 설명" sha256 "..."   // 일부러 담지 않는 블록과 그 이유
```

`kv spec stale`은 범위 안의 블록을 어느 절도 인용하지 않고 제외 목록에도 없으면 `uncovered-block` 오류를 낸다. `review intent` 아래에서는 `scope`와 `exclude`에도 서명(`reviewed`)이 필요하다.

Confluence Cloud 페이지를 명세로 쓰는 방법 (`DESIGN-C.md` 10.8):

1. API 토큰을 Keychain에 한 번 저장한다. 토큰은 프롬프트에서 입력한다 (명령줄에 쓰지 않는다): `security add-generic-password -s SERVICE -a EMAIL -w`
2. 페이지를 받는다: `python3 -I translator/scripts/confluence_fetch.py --site https://SITE.atlassian.net --email EMAIL --keychain-service SERVICE --out DIR PAGE_ID...`
3. 스냅숏을 만든다: `kv spec snapshot --out SNAP conf:PAGE_ID=DIR/PAGE_ID.xhtml`. 비밀 모양의 문자열이 든 블록은 가려진다. 한국어 이름(`비밀번호: ...`, `토큰 = ...`)도 잡는다. 단, `비밀번호는 ...`처럼 조사 뒤의 값은 ASCII 글자와 숫자를 모두 담을 때만 가린다 (`DESIGN-C.md` 10.13).
4. 받은 폴더(DIR)는 원문 그대로라서 커밋하지 않는다.

## 알려진 한계

- 위의 세 가지 모양만 다룬다. Reactor(`Mono` 사슬)로 쓴 client 프로토콜은 아직 읽지 못한다 (설계: `spike/protocol2r/DESIGN-E6-v2.md`).
- 묶인 Kotlin 지역 변수의 계산식을 바꿔도(예: `val now = nowToEpochTime() + lockTimeoutMillis`) PROVEN이 그대로다. 도구가 `free` 값의 계산을 번역하지 않기 때문이다 (`DESIGN-v0.md` 2.12 규칙 1, `DESIGN-C.md` 10.16). 그래서 묶인 지역 변수의 계산식은 사람이 읽어야 한다.
- `free` 묶기는 서명이 덮지 않는다. `kv resolve`는 한 값을 두 번 묶는 것(`free-duplicate`)과, client에서 읽히지 않는 경로에 묶은 것(`free-unused`)을 잡는다 (`DESIGN-C.md` 10.16). 두 묶기를 맞바꾼 경우와 L1의 `param`·`local` 오묶기는 아직 잡지 못한다. 그런 sidecar는 FAILED나 ERROR가 된다.
- Confluence 가져오기는 공식 API 스펙에 맞춰 만들었지만, 실제 Confluence 페이지로는 아직 확인하지 않았다 (`DESIGN-C.md` 10.8의 미확인 항목).

판별력 측정(변이 검사, AI가 쓴 코드의 판정)은 `MUTATION.md`, `AI-CODE.md`, `PROTOCOL2.md`에 있다.

## 문서

| 문서 | 내용 |
|---|---|
| `DEVELOPMENT.md` | 도구 개발: 테스트 실행, Gradle 작업, 폴더 구조 |
| `HISTORY.md` | 작업 이력 |
| `PHASE1.md` | 조사와 1단계 계획 |
| `DESIGN-v0.md` | KVIR, sidecar 명세, ghost 상태 (L1 모듈) |
| `DESIGN-B.md` | aspect 캐시 모듈 |
| `DESIGN-C.md` | 자연어 명세 → sidecar 흐름, 서명 규칙 |
| `DESIGN-D.md` | compute 밖의 예약 단계 |
| `DESIGN-E.md` | L3 client 프로토콜 |
| `MUTATION.md`, `AI-CODE.md`, `PROTOCOL2.md` | 측정 계획(결과 전 예측)과 결과 |
