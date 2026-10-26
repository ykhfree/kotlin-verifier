# Part B: 기계적 변이의 모델 해시 (AI-CODE.md 15.1 예측 3)

날짜: 2026-10-26. Silicon은 돌리지 않았다. 해시만 계산했다.

## 방법

1. `hashes.py`가 `translator/scripts/mechanical.py`의 함수(1-4단계: 변이 지점 목록, 복사본, `kv emit`, 정규화 해시)를 그대로 불러 쓴다.
2. 같은 입력(지금의 `specs/reqshield`, req-shield `2e792d2`)에 도구 두 개를 돌렸다.
   - `hashes-partb.jsonl`: Part B 뒤의 도구 (커밋 `f794827`의 installDist).
   - `hashes-base-adbe2f8.jsonl`: Part B 전의 도구 (`adbe2f8`의 git worktree).
3. 두 실행 모두 `mutation/rerun-c4/results.jsonl`과도 비교했다 (`*.log`).

## 결과

| 비교 | 결과 |
|---|---|
| Part B 뒤 도구 대 `adbe2f8` 도구 | 284개 행(변이 278개, 원본 6개) 모두 같다. 편집, 컴파일 여부, 모델 해시가 같다. 두 jsonl 파일은 바이트 단위로 같다 |
| 각 도구 대 `rerun-c4` | 공통 변이 264개의 편집과 컴파일 여부는 같다. 모델이 있는 204개는 두 도구 모두 해시가 다르다 |

`rerun-c4`와 해시가 다른 이유: `rerun-c4`(커밋 `ed39a94`) 뒤에 sidecar가 바뀌었다 (커밋 `2137e85`: unLock과 sweep에 `no_entry_created`). 그래서 지금의 도구 둘 다 `rerun-c4`의 해시를 다시 만들 수 없다. Part B 자체가 만든 해시 차이는 0이다.

`rerun-c4`에는 aspect 모듈의 변이가 없다. 그래서 aspect 변이 14개는 "only now"에만 있다.
