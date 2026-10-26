# AI가 쓴 코드: 집계 (AI-CODE.md 7)

`translator/scripts/aicode.py summary`가 `results.jsonl`에서 만든다. 손으로 고치지 않는다.

## 구현별

| ID | 모델 | 종류 | 컴파일 | compile.sh 호출 | 유사도 | 테스트 | 도구 (처음) | 묶기 이름 바꿈 | hole | 최종 | 판정자 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| G-haiku-1 | haiku | template | 예 | 1 | - | - | FAILED (property) | 1 | skipped: round 4 (AI-CODE.md 15, Part B) re-runs the tool only to measure DESIGN-D (a); no hole stage | FAILED (property) | defect-property |
| G-haiku-2 | haiku | template | 예 | 1 | - | - | FAILED (property) | 0 | skipped: round 4 (AI-CODE.md 15, Part B) re-runs the tool only to measure DESIGN-D (a); no hole stage | FAILED (property) | defect-property |
| G-haiku-3 | haiku | template | 예 | 1 | - | - | FAILED (property) | 1 | skipped: round 4 (AI-CODE.md 15, Part B) re-runs the tool only to measure DESIGN-D (a); no hole stage | FAILED (property) | defect-property |
| G-haiku-4 | haiku | template | 예 | 1 | - | - | FAILED (property) | 1 | skipped: round 4 (AI-CODE.md 15, Part B) re-runs the tool only to measure DESIGN-D (a); no hole stage | FAILED (property) | defect-property |
| G-sonnet-1 | sonnet | template | 예 | 1 | - | - | FAILED (property) | 1 | skipped: round 4 (AI-CODE.md 15, Part B) re-runs the tool only to measure DESIGN-D (a); no hole stage | FAILED (property) | defect-property |
| G-sonnet-2 | sonnet | template | 예 | 1 | - | - | FAILED (property) | 1 | skipped: round 4 (AI-CODE.md 15, Part B) re-runs the tool only to measure DESIGN-D (a); no hole stage | FAILED (property) | defect-property |
| G-sonnet-3 | sonnet | template | 예 | 1 | - | - | FAILED (property) | 1 | skipped: round 4 (AI-CODE.md 15, Part B) re-runs the tool only to measure DESIGN-D (a); no hole stage | FAILED (property) | defect-property |
| G-sonnet-4 | sonnet | template | 예 | 1 | - | - | FAILED (property) | 0 | skipped: round 4 (AI-CODE.md 15, Part B) re-runs the tool only to measure DESIGN-D (a); no hole stage | FAILED (property) | defect-property |
| G-opus-1 | opus | template | 예 | 1 | - | - | FAILED (property) | 0 | skipped: round 4 (AI-CODE.md 15, Part B) re-runs the tool only to measure DESIGN-D (a); no hole stage | FAILED (property) | defect-property |
| G-opus-2 | opus | template | 예 | 1 | - | - | FAILED (property) | 1 | skipped: round 4 (AI-CODE.md 15, Part B) re-runs the tool only to measure DESIGN-D (a); no hole stage | FAILED (property) | defect-property |
| G-opus-3 | opus | template | 예 | 1 | - | - | FAILED (property) | 0 | skipped: round 4 (AI-CODE.md 15, Part B) re-runs the tool only to measure DESIGN-D (a); no hole stage | FAILED (property) | defect-property |
| G-opus-4 | opus | template | 예 | 1 | - | - | FAILED (property) | 1 | skipped: round 4 (AI-CODE.md 15, Part B) re-runs the tool only to measure DESIGN-D (a); no hole stage | FAILED (property) | defect-property |

## 오염 검사 (4.3)

- 유사도 분포: 없음
- 0.9 이상: 0개 (없음)

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
| 결함 (sidecar 성질) | - | G-haiku-1, G-haiku-2, G-haiku-3, G-haiku-4, G-sonnet-1, G-sonnet-2, G-sonnet-3, G-sonnet-4, G-opus-1, G-opus-2, G-opus-3, G-opus-4 | - | - |
| 결함 (sidecar 밖) | - | - | - | - |
| 정상 | - | - | - | - |
| 미결 | - | - | - | - |
| 판정 대기 | - | - | - | - |

범위 밖이거나 대기 중인 본 실험 구현: 없음
