# 도구 개발

이 문서는 kotlin-verifier 자체를 고치고 시험하는 사람을 위한 것이다. 도구 사용법은 `README.md`에 있다.

## 테스트 준비

테스트와 아래 Gradle 작업은 req-shield 코드를 시험 대상(fixture)으로 쓴다. req-shield 실험에서 가져온 테스트 입력은 `translator/src/test/fixtures/req-shield/`에 있다. 출처와 라이선스는 그 폴더의 `NOTICE.md`에 있다.

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
| `specs/reqshield/` | req-shield의 서명된 sidecar 명세 (sidecar 예이자 테스트 입력) |
| `translator/src/test/fixtures/req-shield/` | req-shield 실험에서 가져온 테스트 입력: 손 Viper 모델(`spike/`), 파일럿 초안(`pilot-c/`), 명세 스냅숏(`specdraft/`), AI가 쓴 구현(`aicode/`) |
| `upstream/` | SnaKt 저장소의 기존 이슈에 남길 댓글 초안 (게시하지 않음) |

실험 기록(변이 검사, AI가 쓴 코드, 파일럿, 손 모델 전체, 측정 보고서 `MUTATION.md`·`AI-CODE.md`·`PROTOCOL2.md`·`PHASE1.md`)은 비공개 저장소 `kotlin-verifier-experiments`에 있다.
설계 문서가 `spike/`, `aicode/`, `mutation/`, `pilot-c/`, `specdraft/`, `frontend-probe/` 아래의 경로를 가리키면, 그 파일은 테스트 입력 폴더나 실험 저장소에 있다.
