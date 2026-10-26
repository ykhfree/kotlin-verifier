# 판정자 묶음

MUTATION.md 6절과 변경 기록에 따라 판정자가 볼 변이다. 파일 하나에 변이 하나다.

- "PROVEN, 모델 바뀜" 45개가 상한 30을 넘는다: ROR 경계와 RET 9개를 모두 넣고, 나머지 36개에서 무작위 21개를 뽑았다 (seed 20261025). 이 범주의 판정은 표본이다.
- 그 밖의 FAILED 129개에서 무작위 10개 (seed 20261024).

| 범주 | 개수 |
|---|---|
| FAILED, 무작위 표본 | 10 |
| FAILED, 예측 7.2 | 15 |
| PROVEN, 모델 바뀜 | 30 |

| 파일 | 모듈 | 규칙 | 판정 | 범주 |
|---|---|---|---|---|
| [keylocallock.coroutine/RET-02](keylocallock.coroutine__RET-02.md) | keylocallock.coroutine | RET | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.coroutine/RET-05](keylocallock.coroutine__RET-05.md) | keylocallock.coroutine | RET | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.coroutine/RET-10](keylocallock.coroutine__RET-10.md) | keylocallock.coroutine | RET | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.core/RET-02](keylocallock.core__RET-02.md) | keylocallock.core | RET | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.core/RET-05](keylocallock.core__RET-05.md) | keylocallock.core | RET | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.core/RET-10](keylocallock.core__RET-10.md) | keylocallock.core | RET | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.reactor/RET-02](keylocallock.reactor__RET-02.md) | keylocallock.reactor | RET | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.reactor/RET-04](keylocallock.reactor__RET-04.md) | keylocallock.reactor | RET | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.reactor/RET-07](keylocallock.reactor__RET-07.md) | keylocallock.reactor | RET | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.core/BOOL-07](keylocallock.core__BOOL-07.md) | keylocallock.core | BOOL | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.core/BOOL-09](keylocallock.core__BOOL-09.md) | keylocallock.core | BOOL | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.core/MOP-05](keylocallock.core__MOP-05.md) | keylocallock.core | MOP | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.core/SDL-17](keylocallock.core__SDL-17.md) | keylocallock.core | SDL | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.core/SDL-18](keylocallock.core__SDL-18.md) | keylocallock.core | SDL | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.coroutine/BOOL-02](keylocallock.coroutine__BOOL-02.md) | keylocallock.coroutine | BOOL | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.coroutine/BOOL-07](keylocallock.coroutine__BOOL-07.md) | keylocallock.coroutine | BOOL | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.coroutine/BOOL-09](keylocallock.coroutine__BOOL-09.md) | keylocallock.coroutine | BOOL | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.coroutine/NEG-10](keylocallock.coroutine__NEG-10.md) | keylocallock.coroutine | NEG | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.coroutine/SDL-16](keylocallock.coroutine__SDL-16.md) | keylocallock.coroutine | SDL | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.reactor/BOOL-02](keylocallock.reactor__BOOL-02.md) | keylocallock.reactor | BOOL | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.reactor/BOOL-07](keylocallock.reactor__BOOL-07.md) | keylocallock.reactor | BOOL | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.reactor/BOOL-09](keylocallock.reactor__BOOL-09.md) | keylocallock.reactor | BOOL | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.reactor/BOOL-10](keylocallock.reactor__BOOL-10.md) | keylocallock.reactor | BOOL | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.reactor/MOP-05](keylocallock.reactor__MOP-05.md) | keylocallock.reactor | MOP | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.reactor/NEG-09](keylocallock.reactor__NEG-09.md) | keylocallock.reactor | NEG | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.reactor/RACY-08](keylocallock.reactor__RACY-08.md) | keylocallock.reactor | RACY | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.reactor/RACY-09](keylocallock.reactor__RACY-09.md) | keylocallock.reactor | RACY | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.reactor/SDL-03](keylocallock.reactor__SDL-03.md) | keylocallock.reactor | SDL | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.reactor/SDL-14](keylocallock.reactor__SDL-14.md) | keylocallock.reactor | SDL | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.reactor/SDL-17](keylocallock.reactor__SDL-17.md) | keylocallock.reactor | SDL | PROVEN | PROVEN, 모델 바뀜 |
| [keylocallock.coroutine/RACY-01](keylocallock.coroutine__RACY-01.md) | keylocallock.coroutine | RACY | FAILED | FAILED, 예측 7.2 |
| [keylocallock.coroutine/RACY-02](keylocallock.coroutine__RACY-02.md) | keylocallock.coroutine | RACY | FAILED | FAILED, 예측 7.2 |
| [keylocallock.coroutine/RACY-03](keylocallock.coroutine__RACY-03.md) | keylocallock.coroutine | RACY | FAILED | FAILED, 예측 7.2 |
| [keylocallock.coroutine/RACY-04](keylocallock.coroutine__RACY-04.md) | keylocallock.coroutine | RACY | FAILED | FAILED, 예측 7.2 |
| [keylocallock.coroutine/RACY-06](keylocallock.coroutine__RACY-06.md) | keylocallock.coroutine | RACY | FAILED | FAILED, 예측 7.2 |
| [keylocallock.core/RACY-01](keylocallock.core__RACY-01.md) | keylocallock.core | RACY | FAILED | FAILED, 예측 7.2 |
| [keylocallock.core/RACY-02](keylocallock.core__RACY-02.md) | keylocallock.core | RACY | FAILED | FAILED, 예측 7.2 |
| [keylocallock.core/RACY-03](keylocallock.core__RACY-03.md) | keylocallock.core | RACY | FAILED | FAILED, 예측 7.2 |
| [keylocallock.core/RACY-04](keylocallock.core__RACY-04.md) | keylocallock.core | RACY | FAILED | FAILED, 예측 7.2 |
| [keylocallock.core/RACY-06](keylocallock.core__RACY-06.md) | keylocallock.core | RACY | FAILED | FAILED, 예측 7.2 |
| [keylocallock.reactor/RACY-01](keylocallock.reactor__RACY-01.md) | keylocallock.reactor | RACY | FAILED | FAILED, 예측 7.2 |
| [keylocallock.reactor/RACY-02](keylocallock.reactor__RACY-02.md) | keylocallock.reactor | RACY | FAILED | FAILED, 예측 7.2 |
| [keylocallock.reactor/RACY-03](keylocallock.reactor__RACY-03.md) | keylocallock.reactor | RACY | FAILED | FAILED, 예측 7.2 |
| [keylocallock.reactor/RACY-04](keylocallock.reactor__RACY-04.md) | keylocallock.reactor | RACY | FAILED | FAILED, 예측 7.2 |
| [keylocallock.reactor/RACY-06](keylocallock.reactor__RACY-06.md) | keylocallock.reactor | RACY | FAILED | FAILED, 예측 7.2 |
| [keylocallock.core/BOOL-01](keylocallock.core__BOOL-01.md) | keylocallock.core | BOOL | FAILED | FAILED, 무작위 표본 |
| [keylocallock.core/MOP-04](keylocallock.core__MOP-04.md) | keylocallock.core | MOP | FAILED | FAILED, 무작위 표본 |
| [keylocallock.core/NEG-04](keylocallock.core__NEG-04.md) | keylocallock.core | NEG | FAILED | FAILED, 무작위 표본 |
| [keylocallock.core/RET-01](keylocallock.core__RET-01.md) | keylocallock.core | RET | FAILED | FAILED, 무작위 표본 |
| [keylocallock.coroutine/AOR-02](keylocallock.coroutine__AOR-02.md) | keylocallock.coroutine | AOR | FAILED | FAILED, 무작위 표본 |
| [keylocallock.coroutine/BOOL-03](keylocallock.coroutine__BOOL-03.md) | keylocallock.coroutine | BOOL | FAILED | FAILED, 무작위 표본 |
| [keylocallock.coroutine/SDL-04](keylocallock.coroutine__SDL-04.md) | keylocallock.coroutine | SDL | FAILED | FAILED, 무작위 표본 |
| [keylocallock.coroutine/SDL-14](keylocallock.coroutine__SDL-14.md) | keylocallock.coroutine | SDL | FAILED | FAILED, 무작위 표본 |
| [keylocallock.reactor/AOR-02](keylocallock.reactor__AOR-02.md) | keylocallock.reactor | AOR | FAILED | FAILED, 무작위 표본 |
| [keylocallock.reactor/SDL-15](keylocallock.reactor__SDL-15.md) | keylocallock.reactor | SDL | FAILED | FAILED, 무작위 표본 |
