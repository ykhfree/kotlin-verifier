# 두 번째 실제 프로토콜: 도입 비용 측정 (2026-10-26, 결과 전)

사용자 결정 (2026-10-26): 도구를 두 번째 실제 프로토콜에 붙여, 새 프로토콜의 도입 비용을 잰다.

## 1. 대상

req-shield의 `ReqShield` (요청 합치기, single-flight): `core/.../ReqShield.kt` (277줄), `core-kotlin-coroutine/.../ReqShield.kt` (253줄), `core-reactor/.../ReqShield.kt` (374줄). 같은 key의 요청이 몰리면 lock을 잡은 요청 하나만 공급 함수(supplier)를 돌리고 캐시를 쓴다. 나머지는 캐시를 polling한다.

지금까지의 두 프로토콜(lease lock, 한 번만 만드는 캐시)과 다른 점:
1. 공유 상태가 모듈 안의 map이 아니라 외부 캐시(사용자 함수, IO)와 `KeyLock` 인터페이스다.
2. 성질이 "자원 사용 규율"이다: 잡은 lock은 모든 경로(성공, supplier 예외, 캐시 쓰기 실패, executor 거부)에서 정확히 한 번 풀린다; 남의 토큰으로 풀지 않는다; 캐시 쓰기와 supplier 실행은 lock을 가진 경로에서만 일어난다 (모드에 따라 다름).
3. 비동기 실행(executor, coroutine, reactor)과 polling, 예외 경로가 있다.

## 2. 무엇을 재는가 (비용)

단계마다 기록한다: agent 토큰과 시간, 메인 세션 개입, 사용자 결정 수.
1. 프로토콜 분석과 어휘 설계 (ghost 상태, 성질 목록) — deep-reasoner.
2. 손 Viper 모델(spike): 원본 PROVEN, 손으로 고른 결함 변이 FAILED.
3. 도구에 필요한 변화의 목록과 크기 (새 구문, 새 신뢰 모델, KVIR, emitter, 보고서).
4. 구현 (설계를 보고 사용자가 진행을 정한 뒤).
5. 결과: 원본 세 변형의 판정, 변이 판별력.

비교 기준: 2번째 프로토콜이었던 aspect 캐시는 DESIGN-B 설계와 B0-B4 구현이 필요했다 (HISTORY.md 2026-10-24).

## 3. 예측 (결과 전)

1. 지금 도구는 `ReqShield`의 연산을 하나도 그대로 판정하지 못한다 (모듈 상태 map이 없고, 성질이 lock 사용 규율이다).
2. 어휘 설계는 새 ghost 상태(lock 소유, 해제 의무)와 `KeyLock` 호출의 신뢰 계약을 요구한다. 지금의 "연산당 atomic action 하나" 모델 밖이다.
3. 설계 단계에서 사용자에게 물을 명세 결정이 적어도 하나 나온다 (예: 캐시 쓰기 실패 뒤의 lock, 모드별 의무).

## 4. 설계 단계 결과 (2026-10-26, `DESIGN-E.md`, `c400308`)

예측 대조 (DESIGN-E 8절, 메인 세션 확인):
1. 맞음 (측정): 지금 도구는 `ReqShield.kt`에서 action 0개를 찾고, 연산은 UNSUPPORTED, 모듈은 INCOMPLETE다.
2. 맞음: 새 층(L3)이 필요하다. 선형 의무 `Lease(k, lt, t)`, 사건 기록, 과제와 의무 넘기기, 예외 종류, 반복문 불변식 hole.
3. 맞음: 사용자 결정 6개 (UE1-UE6).

손 Viper 모델 (core 변형, `spike/protocol2/`, 메인 세션이 다시 돌림): 원본 PROVEN, 결함 변이 12개 모두 FAILED, 동치 대조 3개 PROVEN, 신뢰 가정 토글 3개는 끄면 FAILED, 분기 21곳 도달 가능. 메서드당 약 2.1 s.

비용 (측정):
- 설계와 spike: agent 1개, 약 59만 토큰, 47분 (rate limit 재개 포함한 실행 시간 합). agent의 자체 기록: 설계 약 46-56분, spike 약 16분.
- 메인 세션: spike 재실행과 검토.
- 구현 추정 (DESIGN-E 6.10, 추정): core 12.5-19.5 인일 (E0-E4), coroutine +3-5, reactor +10-20. aspect 캐시(B)는 7-12 인일이었다.

판단: 도입 비용은 새 프로토콜이 기존 모델 층에 맞는지에 크게 달려 있다. aspect 캐시는 L1에 맞았고, ReqShield는 새 층을 요구한다. 손 모델(spike)은 병목이 아니다 (약 16분). 비용은 함수 본문 전체의 lowering(예외, 람다, 반복문)에 있다.

## 5. 구현 전 예측 (2026-10-27, 결과 전)

사용자 결정 (2026-10-26): core 변형 구현 (E0-E4). UE1 호출 하나의 규율, UE2·UE4 가정, UE3 "이미 얻은 결과를 잃지 않는다". 손 모델의 기준은 A2를 끈 `spike/protocol2/target.vpr`이고, 고친 core는 `fixed.vpr`과 `fixed-core.patch`다 (DESIGN-E 5.6).

예측 (도구가 낼 결과):
1. 원본 core: FAILED, 깨지는 의무는 정확히 RET_hit, RET_recheck, RET_supplier_error. LD_* 7개, MODE, SUP_once, POLL_bound, TERM, 나머지 RET은 PROVEN.
2. 고친 core (`fixed-core.patch`를 적용한 사본): PROVEN.
3. 손으로 고른 변이 12개와 대조 3개, 가정 토글: 판정과 깨지는 의무가 `spike/protocol2/expected-verdicts.txt`와 같다 (원본 위와 고친 코드 위 모두).
4. 생성 모델은 정규화 뒤 `target.vpr`, `fixed.vpr`과 같다 (gate).
5. 기존 결과는 그대로다: 기존 sidecar 8개의 `kv discover`/`kv lower` 출력과 생성 Viper가 바이트 단위로 같다; AI 구현 24개의 보고서가 Silicon 시간 말고 같다; 테스트 621개와 손 변이 63회 불일치 0.
6. 사용자 서명: 16개 (의도 절 12, 가정 4) + 신뢰 선언 6개 + 어휘 층 승인 1번 (DESIGN-E 2.5).

진행 방식: 마일스톤 E0-E2(발견, 문법, 함수 본문 lowering)를 먼저 하고 메인 세션이 확인한 뒤 E3-E4(emitter와 gate, 보고서와 변이)를 한다. 구현 agent는 `spike/protocol2/`를 고치지 못한다. req-shield 코드는 도구가 예측 1을 보고한 뒤에 고친다 (사용자에게 묻고).

## 6. 구현 결과 (2026-10-27)

E0-E2 `dc232ad`, `1e2d072`, `73e2cc4`; E3-E4 `b9512f6`, `9d65182`.

예측 대조 (5절):

| 예측 | 결과 | 근거 |
|---|---|---|
| 1. 원본 core는 RET_hit, RET_recheck, RET_supplier_error에서만 FAILED | **맞음** | 메인 세션이 `kv verify --draft`를 직접 돌림: "module: FAILED", FAILED 성질은 정확히 그 셋. LD_* 7개(LD_noleak, LD_own, LD_transfer, LD_wait, LD_write, LD_supplier 등), MODE 2개, SUP_once, POLL_bound, TERM, HOLE, 나머지 RET은 PROVEN |
| 2. 고친 core는 PROVEN | **맞음** | 메인 세션이 req-shield 사본에 `fixed-core.patch`를 적용해 돌림: "module: PROVEN" |
| 3. 변이·대조·토글이 손 모델 기록과 같음 | 42행 중 40행 같음 | M12 두 행에서 도구가 RET_supplier를 더 보고한다. 손 변이 M12에 `if (exc == NONE)` guard가 빠져 있었다. guard를 넣은 손 변이는 도구와 같은 집합이다 (agent 측정). 판정(DETECTED)은 같다 |
| 4. 생성 모델 = 손 모델 (정규화 뒤) | 이름표, 구조 편집, 정규화기 변경 뒤 같음 | 이름표와 구조 편집 16곳(`translator/scripts/protocol2_gate.py`에 이유와 함께). 편집한 손 모델도 같은 판정을 낸다 (agent 측정). 구조 차이 중 둘은 손 모델 안의 불일치에서 왔다 |
| 5. 기존 결과 그대로 | 맞음 | 기존 출력 36파일 중 24개 바이트 동일, 12개는 checkout 경로만 다름; AI 구현 24개 보고서 같음 (agent 측정) |
| 6. 서명 수 16 + 6 + 1 | 맞음 | 새 문법 `hit`, `derived`는 어휘 승인에 속한다 |

메인 세션 결정 (구현 agent의 열린 질문):
- 손 모델(`spike/protocol2/`)은 설계 때의 기준으로 그대로 둔다. 이름표와 구조 편집은 gate 스크립트에 이유와 함께 둔다.
- M12의 기대값은 도구의 집합을 쓴다 (손 변이의 guard 누락).
- DESIGN-E 3.4 part 1은 "넘기는 쪽이 만든 의무"로 고친다 (아래 DESIGN-E 기록).
- `kv.arbitrary`(module class의 var 읽기 = 임의 값)는 과대 근사라서 건전하다.
- 자동 isolation(FAILED일 때 약 14 s 더)은 기본으로 둔다.
- `hole loop @LINE`은 코드가 바뀌면 줄이 움직인다 (고친 사본에서 181 → 187). 함수 안 순서로 묶는 방식은 뒤로 미룬다.
- 어휘 결정(`hit`, `derived`, 사건 그룹, spawn count, 과제의 경로 값, hole 어휘)은 서명 때 사용자에게 묻는다.

비용 (측정, rate limit 대기 제외): E0-E2 agent 약 89만 토큰, 약 3시간 15분; E3-E4 agent 약 92만 토큰, 약 3시간 10분. 설계와 spike agent 약 74만 토큰. 합계 약 255만 토큰, agent 작업 약 7시간 반. 사람 엔지니어 추정(12.5-19.5 인일)과는 비교할 수 없다.

## 7. req-shield 수정 (사용자 결정 2026-10-27)

1. 작업 브랜치 `feature/NOISSUE/upgrade-kotlin2-and-deps`에 로컬 커밋 `3a63adc` "Keep the caller's result when a lock store call fails" (push 안 함).
2. 테스트 3개를 먼저 넣었다: 갱신 `tryLock` 실패 시 hit 유지, 재확인 뒤 `unLock` 실패 시 데이터 유지, supplier와 `unLock` 모두 실패 시 `SUPPLIER_ERROR`. 수정 전 코드에서 세 테스트 모두 실패했다 (메인 세션 측정).
3. `fixed-core.patch`를 적용했다. req-shield `./gradlew build` (모든 모듈 테스트, ktlint) 통과.
4. 도구로 고친 브랜치를 검증했다: `module: PROVEN` (hole loop의 줄 번호 181 → 187인 sidecar 사본으로).
5. 검증 도구의 기본 req-shield 경로를 `2e792d2`에 고정한 worktree `~/IdeaProjects/req-shield-verifier-pin`으로 바꿨다 (`translator/build.gradle.kts`). 작업 브랜치가 움직여도 테스트·gate·측정이 같은 커밋 위에서 돈다. 확인: 테스트 683개, 변이 33·30·42회 불일치 0.
