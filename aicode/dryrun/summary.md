# AI가 쓴 코드: 집계 (AI-CODE.md 7)

`translator/scripts/aicode.py summary`가 `results.jsonl`에서 만든다. 손으로 고치지 않는다.

## 구현별

| ID | 모델 | 종류 | 컴파일 | compile.sh 호출 | 유사도 | 테스트 | 도구 (처음) | 묶기 이름 바꿈 | hole | 최종 | 판정자 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| D1 | dryrun (original) | template | 예 | 1 | 1.000 ⚑ | 14/14 | PROVEN | 0 | - | PROVEN | - |
| D2 | dryrun (SB1) | template | 예 | 1 | 0.996 ⚑ | 12/14 | FAILED (property) | 0 | FAILED, 1 call(s) | FAILED (property) | - |

## 오염 검사 (4.3)

- 유사도 분포: 0.996, 1.000
- 0.9 이상: 2개 (D1, D2)

## 7.1 범위

| 항목 | 본 실험 (template) | 범위 실험 (free) |
|---|---|---|
| 사산 (컴파일 실패) | 0개 (-) | 0개 (-) |
| 묶기 실패 | 없음 | 없음 |
| 범위 밖 (INCOMPLETE) | 없음 | 없음 |

### 자유 구현

| ID | 컴파일 | 묶기에서 깨지는 것 | 이름만 바꾸면 돌아가는가 |
|---|---|---|---|

## 7.2 정확성 (본 실험 중 범위 안)

칸의 ID는 그 구현이다. FAILED (invariant only)는 hole을 다시 써도 증명되지 않은 것이다. FAILED (pending)는 hole 단계가 남은 것이다.

| 정답 \ 도구 최종 | PROVEN | FAILED (property) | FAILED (invariant only) | FAILED (pending) |
|---|---|---|---|---|
| 결함 (sidecar 성질) | - | - | - | - |
| 결함 (sidecar 밖) | - | - | - | - |
| 정상 | - | - | - | - |
| 미결 | - | - | - | - |
| 판정 대기 | D1 | D2 | - | - |

범위 밖이거나 대기 중인 본 실험 구현: 없음
