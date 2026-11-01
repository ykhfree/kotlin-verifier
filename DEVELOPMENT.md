# 도구 개발

이 문서는 kotlin-verifier 자체를 고치고 시험하는 사람을 위한 것이다. 도구 사용법은 `README.md`에 있다.

## 테스트 준비

테스트와 아래 Gradle 작업은 req-shield 코드를 시험 대상(fixture)으로 쓴다.

1. `README.md`의 "설치" 1-2번 (JDK 21, Viper).
2. req-shield의 `2e792d2` 커밋 checkout과 그 jar (`*/build/libs/*-1.0.0.jar`, req-shield에서 `./gradlew build`로 만든다).
   - 기본 경로는 worktree `/Users/ykhfree/IdeaProjects/req-shield-verifier-pin`이다.
   - 다른 경로는 Gradle 속성으로 준다: `./gradlew -PreqShieldDir=/path/to/req-shield test`.
   - 테스트와 변이 기대값은 이 커밋에 맞춰져 있다. 다른 커밋을 쓰면 테스트가 실패할 수 있다.
   - `2e792d2`는 원격의 어느 브랜치나 태그에도 없다. 로컬 pin worktree에만 남아 있다 (2026-11-01 확인).
   - ykhfree fork 브랜치의 기준 커밋 `5513126`은 같은 업그레이드의 다른 판이다. 두 커밋은 예제 모듈 3개(`req-shield-spring-boot4-*example`)에서만 다르고, 검증 대상 라이브러리 모듈은 같다. `5513126`에서 테스트를 돌려 보지는 않았다.

## 테스트 실행

모든 명령은 `translator/`에서 실행한다.

```bash
cd translator
./gradlew test            # 테스트 전체 (Silicon 실행 포함, 측정 약 21분)
```

자주 쓰는 Gradle 작업:

| 작업 | 하는 일 |
|---|---|
| `verifyReqShield` | KeyLocalLock 3개 변형을 증명하고 보고서를 출력한다 |
| `verifyAspect` | aspect 캐시 모듈을 증명한다 |
| `resolveClient`, `lowerClient` | ReqShield core sidecar를 묶고, KVIR를 출력한다 |
| `resolveClientCoroutine`, `lowerClientCoroutine`, `verifyClientCoroutine` | ReqShield coroutine sidecar를 묶고, KVIR를 출력하고, 증명한다 |
| `mutantsReqShield`, `mutantsAspect`, `mutantsClient`, `mutantsCoroutine` | 변이 검사를 돌리고 기대 판정과 비교한다 |

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

작업 이력과 측정 결과의 요약은 `HISTORY.md`에 있다.
