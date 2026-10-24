# 1차 측정과 재측정의 비교

- 1차: `mutation/results.jsonl` (sidecar를 57c36eb에서 바꾸기 전, 성질 확인(pass 2) 없음).
- 재측정: `mutation/rerun/results.jsonl` (sidecar 57c36eb, 도구 10688ed: 성질 확인(pass 2) 포함).
- 1차 측정에는 하위 상태가 없다. 이 표에서 1차의 하위 상태는 pass 1 실패 의무에서 유도한 값이다: hole 조각(`MI_INV_i`) 아닌 의무가 있으면 `property`, 모두 hole 조각이면 `hole only (no property check)`.
- 실패 의무는 두 측정 모두 pass 1의 오류다. 재측정의 pass 2 오류는 따로 적는다.
- 이 문서는 기계적 대조다. 동등·놓침 판정은 하지 않는다.
- 생성: `python3 -I mutation/rerun/compare.py mutation/results.jsonl mutation/rerun/results.jsonl mutation/rerun/compare.md 10688ed`.

## 1. 판정, 하위 상태, 실패 의무가 바뀐 변이 (74개)

| 변이 | 판정 1차 → 재측정 | 실패 의무 1차 | 실패 의무 재측정 (pass 1) | 하위 상태 1차 (유도) → 재측정 | pass 2 오류 (재측정) |
|---|---|---|---|---|---|
| core/AOR-01 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| core/AOR-02 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| core/AOR-03 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| core/AOR-04 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| core/BOOL-01 | PROVEN → FAILED | - | tryLock: expired_takeover | - → tryLock: property | - |
| core/BOOL-03 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| core/BOOL-04 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| core/MOP-01 | PROVEN → FAILED | - | tryLock: expired_takeover | - → tryLock: property | - |
| core/MOP-03 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| core/NEG-04 | FAILED → FAILED | tryLock: unbacked_only_if P3b | tryLock: cap_unbacked<br>tryLock: unbacked_only_if P3b | tryLock: property → tryLock: property | - |
| core/RACY-02 | PROVEN → FAILED | - | tryLock: expired_takeover | - → tryLock: property | - |
| core/RACY-03 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: property | tryLock: expired_takeover |
| core/RACY-04 | PROVEN → FAILED | - | tryLock: cap_unbacked | - → tryLock: property | - |
| core/RET-06 | FAILED → FAILED | unLock: MI_INV_1 | unLock: MI_INV_1 | unLock: hole only (no property check) → unLock: property | unLock: P1 |
| core/RET-11 | FAILED → FAILED | sweep: MI_INV_1 | sweep: MI_INV_1 | sweep: hole only (no property check) → sweep: invariant only | - |
| core/SDL-03 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: property | tryLock: expired_takeover |
| core/SDL-04 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| core/SDL-05 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: property | tryLock: unbacked_only_if P3b |
| core/SDL-06 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| core/SDL-07 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: property | tryLock: expired_takeover |
| core/SDL-08 | PROVEN → FAILED | - | tryLock: cap_unbacked | - → tryLock: property | - |
| core/SDL-09 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| core/SDL-10 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| core/SDL-11 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: property | tryLock: unbacked_only_if P3b |
| core/SDL-12 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: property | tryLock: progress_free |
| coroutine/AOR-01 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| coroutine/AOR-02 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| coroutine/AOR-03 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| coroutine/AOR-04 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| coroutine/BOOL-01 | PROVEN → FAILED | - | tryLock: expired_takeover | - → tryLock: property | - |
| coroutine/BOOL-03 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| coroutine/BOOL-04 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| coroutine/MOP-01 | PROVEN → FAILED | - | tryLock: expired_takeover | - → tryLock: property | - |
| coroutine/MOP-03 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| coroutine/NEG-04 | FAILED → FAILED | tryLock: unbacked_only_if P3b | tryLock: cap_unbacked<br>tryLock: unbacked_only_if P3b | tryLock: property → tryLock: property | - |
| coroutine/RACY-02 | PROVEN → FAILED | - | tryLock: expired_takeover | - → tryLock: property | - |
| coroutine/RACY-03 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: property | tryLock: expired_takeover |
| coroutine/RACY-04 | PROVEN → FAILED | - | tryLock: cap_unbacked | - → tryLock: property | - |
| coroutine/RET-06 | FAILED → FAILED | unLock: MI_INV_1 | unLock: MI_INV_1 | unLock: hole only (no property check) → unLock: property | unLock: P1 |
| coroutine/RET-11 | FAILED → FAILED | sweep: MI_INV_1 | sweep: MI_INV_1 | sweep: hole only (no property check) → sweep: invariant only | - |
| coroutine/SDL-03 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: property | tryLock: expired_takeover |
| coroutine/SDL-04 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| coroutine/SDL-05 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: property | tryLock: unbacked_only_if P3b |
| coroutine/SDL-06 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| coroutine/SDL-07 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: property | tryLock: expired_takeover |
| coroutine/SDL-08 | PROVEN → FAILED | - | tryLock: cap_unbacked | - → tryLock: property | - |
| coroutine/SDL-09 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| coroutine/SDL-10 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| coroutine/SDL-11 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: property | tryLock: progress_free |
| reactor/AOR-01 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| reactor/AOR-02 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| reactor/AOR-03 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| reactor/AOR-04 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| reactor/BOOL-01 | PROVEN → FAILED | - | tryLock: expired_takeover | - → tryLock: property | - |
| reactor/BOOL-03 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| reactor/BOOL-04 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| reactor/MOP-01 | PROVEN → FAILED | - | tryLock: expired_takeover | - → tryLock: property | - |
| reactor/MOP-03 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| reactor/NEG-04 | FAILED → FAILED | tryLock: unbacked_only_if P3b | tryLock: cap_unbacked<br>tryLock: unbacked_only_if P3b | tryLock: property → tryLock: property | - |
| reactor/RACY-02 | PROVEN → FAILED | - | tryLock: expired_takeover | - → tryLock: property | - |
| reactor/RACY-03 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: property | tryLock: expired_takeover |
| reactor/RACY-04 | PROVEN → FAILED | - | tryLock: cap_unbacked | - → tryLock: property | - |
| reactor/RET-05 | FAILED → FAILED | unLock: MI_INV_1 | unLock: MI_INV_1 | unLock: hole only (no property check) → unLock: property | unLock: P1 |
| reactor/RET-08 | FAILED → FAILED | sweep: MI_INV_1 | sweep: MI_INV_1 | sweep: hole only (no property check) → sweep: invariant only | - |
| reactor/SDL-02 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: property | tryLock: expired_takeover |
| reactor/SDL-04 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| reactor/SDL-05 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| reactor/SDL-06 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: property | tryLock: unbacked_only_if P3b |
| reactor/SDL-07 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: property | tryLock: expired_takeover |
| reactor/SDL-08 | PROVEN → FAILED | - | tryLock: cap_unbacked | - → tryLock: property | - |
| reactor/SDL-09 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| reactor/SDL-10 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: invariant only | - |
| reactor/SDL-11 | FAILED → FAILED | tryLock: MI_INV_1 | tryLock: MI_INV_1 | tryLock: hole only (no property check) → tryLock: property | tryLock: unbacked_only_if P3b |
| reactor/SDL-12 | FAILED → FAILED | tryLock: MI_INV_4 | tryLock: MI_INV_4 | tryLock: hole only (no property check) → tryLock: property | tryLock: progress_free |

## 2. 범주별 개수

| 범주 | 1차 | 재측정 |
|---|---|---|
| abstracted by rule | 7 | 7 |
| blocked by review | 47 | 47 |
| detected | 114 | 123 |
| detected (prediction 7.1) | 6 | 6 |
| pending judge: FAILED, prediction 7.2 | 9 | 15 |
| pending judge: PROVEN, model changed | 60 | 45 |
| stillborn | 27 | 27 |
| unsupported | 8 | 8 |

| 판정 | 1차 | 재측정 |
|---|---|---|
| ERROR | 47 | 47 |
| FAILED | 129 | 144 |
| INCOMPLETE | 8 | 8 |
| PROVEN | 67 | 52 |
| 사산 | 27 | 27 |

FAILED action의 하위 상태 (변이×action 단위):

| 하위 상태 | 1차 (유도) | 재측정 |
|---|---|---|
| hole only (no property check) | 56 | 0 |
| invariant only | 0 | 36 |
| property | 73 | 108 |

1차에서 hole 조각만 보고된 변이 56개의 재측정 결과: invariant only 36, property 20.

## 3. 미리 등록한 예측 6개 (MUTATION.md 변경 기록)

| # | 예측 | 대조 | 변이별 재측정 결과 |
|---|---|---|---|
| 1 | MOP-01 ×2 (core, coroutine: 판정자 A의 D1)와 core RACY-02는 `expired_takeover`로 FAILED가 된다 | 맞음 | core/MOP-01: 맞음 — FAILED (tryLock: property; pass 1 tryLock: expired_takeover)<br>coroutine/MOP-01: 맞음 — FAILED (tryLock: property; pass 1 tryLock: expired_takeover)<br>core/RACY-02: 맞음 — FAILED (tryLock: property; pass 1 tryLock: expired_takeover) |
| 2 | RACY-04 ×3과 coroutine SDL-08은 `cap_unbacked`로 FAILED가 된다 | 맞음 | core/RACY-04: 맞음 — FAILED (tryLock: property; pass 1 tryLock: cap_unbacked)<br>coroutine/RACY-04: 맞음 — FAILED (tryLock: property; pass 1 tryLock: cap_unbacked)<br>reactor/RACY-04: 맞음 — FAILED (tryLock: property; pass 1 tryLock: cap_unbacked)<br>coroutine/SDL-08: 맞음 — FAILED (tryLock: property; pass 1 tryLock: cap_unbacked) |
| 3 | coroutine RACY-09와 reactor RACY-08은 PROVEN으로 남는다 | 맞음 | coroutine/RACY-09: 맞음 — PROVEN<br>reactor/RACY-08: 맞음 — PROVEN |
| 4 | RACY-03 ×3, coroutine RET-11, reactor SDL-02는 "불변식만 불일치"(invariant only)로 FAILED다 | 안 맞음 (coroutine/RACY-03, core/RACY-03, reactor/RACY-03, reactor/SDL-02) | coroutine/RACY-03: 안 맞음 — FAILED (tryLock: property; pass 1 tryLock: MI_INV_1; pass 2 tryLock: expired_takeover)<br>core/RACY-03: 안 맞음 — FAILED (tryLock: property; pass 1 tryLock: MI_INV_1; pass 2 tryLock: expired_takeover)<br>reactor/RACY-03: 안 맞음 — FAILED (tryLock: property; pass 1 tryLock: MI_INV_1; pass 2 tryLock: expired_takeover)<br>coroutine/RET-11: 맞음 — FAILED (sweep: invariant only; pass 1 sweep: MI_INV_1)<br>reactor/SDL-02: 안 맞음 — FAILED (tryLock: property; pass 1 tryLock: MI_INV_1; pass 2 tryLock: expired_takeover) |
| 5 | RACY-06 ×3은 성질 위반(`progress_owner`)으로 FAILED다 | 맞음 | coroutine/RACY-06: 맞음 — FAILED (unLock: property; pass 1 unLock: progress_owner)<br>core/RACY-06: 맞음 — FAILED (unLock: property; pass 1 unLock: progress_owner)<br>reactor/RACY-06: 맞음 — FAILED (unLock: property; pass 1 unLock: progress_owner) |
| 6 | "불변식만 불일치"는 무해하다는 뜻이 아니다: 판정자 B의 core·reactor AOR-03과 coroutine SDL-06(다음 단계에서 P3a를 깨는 결함)은 "불변식만 불일치"로 보고될 수 있다. 아래 "맞음"은 그 경우가 실제로 나타났다는 뜻이다 | 맞음 | core/AOR-03: 맞음 — FAILED (tryLock: invariant only; pass 1 tryLock: MI_INV_4)<br>reactor/AOR-03: 맞음 — FAILED (tryLock: invariant only; pass 1 tryLock: MI_INV_4)<br>coroutine/SDL-06: 맞음 — FAILED (tryLock: invariant only; pass 1 tryLock: MI_INV_4) |

참고: 예측 1의 "MOP-01 ×2"는 판정자 A가 본 core와 coroutine이다. 판정 표본에 없던 reactor MOP-01의 재측정 결과: FAILED (tryLock: property; pass 1 tryLock: expired_takeover).

