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
