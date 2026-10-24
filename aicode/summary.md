# AI가 쓴 코드: 집계 (AI-CODE.md 7)

`translator/scripts/aicode.py summary`가 `results.jsonl`에서 만든다. 손으로 고치지 않는다.

## 구현별

| ID | 모델 | 종류 | 컴파일 | compile.sh 호출 | 유사도 | 테스트 | 도구 (처음) | 묶기 이름 바꿈 | hole | 최종 | 판정자 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| T-haiku-1 | haiku | template | 예 | 1 | 0.230 | 14/14 (shim) | INCOMPLETE | 1 | - | out of scope | defect-property |
| T-haiku-2 | haiku | template | 예 | 2 | 0.233 | 14/14 (shim) | FAILED (property) | 0 | skipped: the blind judges confirmed the sweep P3a violation the tool reported; no hole proves a real violation (AI-CODE.md change log) | FAILED (property) | defect-property |
| T-haiku-3 | haiku | template | 예 | 1 | 0.221 | 14/14 (shim) | INCOMPLETE | 1 | - | out of scope | defect-property |
| T-haiku-4 | haiku | template | 예 | 1 | 0.217 | 14/14 (shim) | FAILED (property) | 1 | skipped: the blind judges confirmed the sweep P3a violation the tool reported; no hole proves a real violation (AI-CODE.md change log) | FAILED (property) | defect-property |
| T-sonnet-1 | sonnet | template | 예 | 1 | 0.317 | 14/14 (shim) | FAILED (property) | 0 | skipped: the blind judges confirmed the sweep P3a violation the tool reported; no hole proves a real violation (AI-CODE.md change log) | FAILED (property) | defect-property |
| T-sonnet-2 | sonnet | template | 예 | 1 | 0.331 | 14/14 (shim) | binding failure | 0 | - | binding failure | defect-property |
| T-sonnet-3 | sonnet | template | 예 | 1 | 0.317 | 14/14 (shim) | FAILED (property) | 0 | skipped: the blind judges confirmed the sweep P3a violation the tool reported; no hole proves a real violation (AI-CODE.md change log) | FAILED (property) | defect-property |
| T-sonnet-4 | sonnet | template | 예 | 1 | 0.292 | 14/14 (shim) | FAILED (property) | 1 | skipped: the blind judges confirmed the sweep P3a violation the tool reported; no hole proves a real violation (AI-CODE.md change log) | FAILED (property) | defect-property |
| T-opus-1 | opus | template | 예 | 3 | 0.569 | 14/14 (shim) | binding failure | 0 | - | binding failure | defect-property |
| T-opus-2 | opus | template | 예 | 1 | 0.374 | 14/14 (shim) | FAILED (property) | 0 | skipped: the blind judges confirmed the sweep P3a violation the tool reported; no hole proves a real violation (AI-CODE.md change log) | FAILED (property) | defect-property |
| T-opus-3 | opus | template | 예 | 3 | 0.362 | 14/14 (shim) | binding failure | 0 | - | binding failure | defect-property |
| T-opus-4 | opus | template | 예 | 2 | 0.348 | 14/14 (shim) | binding failure | 0 | - | binding failure | defect-property |
| F-sonnet-1 | sonnet | free | 예 | 1 | 0.329 | test compile failed | binding failure | 0 | - | binding failure | - |
| F-sonnet-2 | sonnet | free | 예 | 3 | 0.343 | test compile failed | binding failure | 0 | - | binding failure | - |
| F-sonnet-3 | sonnet | free | 예 | 1 | 0.360 | test compile failed | binding failure | 0 | - | binding failure | - |

## 오염 검사 (4.3)

- 유사도 분포: 0.217, 0.221, 0.230, 0.233, 0.292, 0.317, 0.317, 0.329, 0.331, 0.343, 0.348, 0.360, 0.362, 0.374, 0.569
- 0.9 이상: 0개 (없음)

## 7.1 범위

| 항목 | 본 실험 (template) | 범위 실험 (free) |
|---|---|---|
| 사산 (컴파일 실패) | 0개 (-) | 0개 (-) |
| 묶기 실패 | T-haiku-1: spec/keylocallock.common.kv:16:18: error[unresolved]: no class 'KeyLocalLock.LockInfo' in the module object, its outer class, package com.linecorp.cse.reqshield.kotlin.coroutine or the imports of KeyLocalLock.kt; 이름 바꿈 1개<br>T-haiku-3: spec/keylocallock.common.kv:16:18: error[unresolved]: no class 'KeyLocalLock.LockInfo' in the module object, its outer class, package com.linecorp.cse.reqshield.kotlin.coroutine or the imports of KeyLocalLock.kt; 이름 바꿈 1개<br>T-haiku-4: spec/keylocallock.common.kv:16:18: error[unresolved]: no class 'KeyLocalLock.LockInfo' in the module object, its outer class, package com.linecorp.cse.reqshield.kotlin.coroutine or the imports of KeyLocalLock.kt; 이름 바꿈 1개<br>T-sonnet-2: spec/keylocallock.common.kv:17:30: error[kotlin-type]: field permits: Semaphore needs a Kotlin java.util.concurrent.Semaphore, but 'semaphore' is kotlinx.coroutines.sync.Semaphore; 이름 바꿈 0개<br>T-sonnet-4: spec/keylocallock.common.kv:16:18: error[unresolved]: no class 'KeyLocalLock.LockInfo' in the module object, its outer class, package com.linecorp.cse.reqshield.kotlin.coroutine or the imports of KeyLocalLock.kt; 이름 바꿈 1개<br>T-opus-1: spec/keylocallock.common.kv:17:30: error[kotlin-type]: field permits: Semaphore needs a Kotlin java.util.concurrent.Semaphore, but 'semaphore' is kotlinx.coroutines.sync.Semaphore; 이름 바꿈 0개<br>T-opus-3: spec/keylocallock.common.kv:17:30: error[kotlin-type]: field permits: Semaphore needs a Kotlin java.util.concurrent.Semaphore, but 'semaphore' is kotlinx.coroutines.sync.Semaphore; 이름 바꿈 0개<br>T-opus-4: spec/keylocallock.common.kv:17:30: error[kotlin-type]: field permits: Semaphore needs a Kotlin java.util.concurrent.Semaphore, but 'semaphore' is kotlinx.coroutines.sync.Semaphore; 이름 바꿈 0개 | F-sonnet-1: spec/keylocallock.common.kv:16:18: error[unresolved]: no class 'KeyLocalLock.LockInfo' in the module object, its outer class, package com.linecorp.cse.reqshield.kotlin.coroutine or the imports of KeyLocalLock.kt / spec/keylocallock.coroutine.kv:8:33: error[unresolved]: no property 'tokenSequence' in the module object, its outer class or package com.linecorp.cse.reqshield.kotlin.coroutine / spec/keylocallock.common.kv:13:33: error[unresolved]: no property 'lockMap' in the module object, its outer class or package com.linecorp.cse.reqshield.kotlin.coroutine / spec/keylocallock.common.kv:85:30: error[ambiguous-selector]: compute in KeyLocalLock.Companion matches 0 action calls []; it must match exactly one; 이름 바꿈 0개<br>F-sonnet-2: spec/keylocallock.common.kv:16:18: error[unresolved]: no class 'KeyLocalLock.LockInfo' in the module object, its outer class, package com.linecorp.cse.reqshield.kotlin.coroutine or the imports of KeyLocalLock.kt / spec/keylocallock.coroutine.kv:8:33: error[unresolved]: no property 'tokenSequence' in the module object, its outer class or package com.linecorp.cse.reqshield.kotlin.coroutine / spec/keylocallock.common.kv:13:33: error[unresolved]: no property 'lockMap' in the module object, its outer class or package com.linecorp.cse.reqshield.kotlin.coroutine / spec/keylocallock.common.kv:85:30: error[ambiguous-selector]: compute in KeyLocalLock.Companion matches 0 action calls []; it must match exactly one; 이름 바꿈 0개<br>F-sonnet-3: spec/keylocallock.common.kv:16:18: error[unresolved]: no class 'KeyLocalLock.LockInfo' in the module object, its outer class, package com.linecorp.cse.reqshield.kotlin.coroutine or the imports of KeyLocalLock.kt / spec/keylocallock.coroutine.kv:8:33: error[unresolved]: no property 'tokenSequence' in the module object, its outer class or package com.linecorp.cse.reqshield.kotlin.coroutine / spec/keylocallock.common.kv:13:33: error[unresolved]: no property 'lockMap' in the module object, its outer class or package com.linecorp.cse.reqshield.kotlin.coroutine / spec/keylocallock.common.kv:85:30: error[ambiguous-selector]: compute in KeyLocalLock.Companion matches 0 action calls []; it must match exactly one; 이름 바꿈 0개 |
| 범위 밖 (INCOMPLETE) | T-haiku-1: tryLock: UNSUPPORTED Int toLong in action tryLock#43 at KeyLocalLock.kt:48:56: v0 models only + and - on Int (DESIGN-v0.md 2.3); UNSUPPORTED helper call on existing in action tryLock#43 at KeyLocalLock.kt:59:17: v0 inlines helpers of this object or class only; unLock: UNSUPPORTED flattened field semaphore in action unLock#80 at KeyLocalLock.kt:84:17: a construction of semaphore does not pass a new jdk.Semaphore object at KeyLocalLock.kt:52:41; sweep: UNSUPPORTED helper call on existing in action sweep#112 at KeyLocalLock.kt:113:45: v0 inlines helpers of this object or class only; tryLock KeyLocalLock.kt:43:9; unLock KeyLocalLock.kt:80:9; sweep KeyLocalLock.kt:112:17; probe; tokenSequence operation outside an action: tokenSequence.incrementAndGet() 41:30<br>T-haiku-3: tryLock: UNSUPPORTED Int toLong in action tryLock#25 at KeyLocalLock.kt:29:75: v0 models only + and - on Int (DESIGN-v0.md 2.3); unLock: UNSUPPORTED flattened field semaphore in action unLock#57 at KeyLocalLock.kt:59:17: a construction of semaphore does not pass a new jdk.Semaphore object at KeyLocalLock.kt:38:33; sweep: UNSUPPORTED safe call ?. in action sweep#94 at KeyLocalLock.kt:94:55: not translated inside an action in v0; tryLock KeyLocalLock.kt:25:9; unLock KeyLocalLock.kt:57:9; sweep KeyLocalLock.kt:94:17; probe; tokenSequence operation outside an action: tokenSequence.incrementAndGet() 88:44 | 없음 |

### 자유 구현

| ID | 컴파일 | 묶기에서 깨지는 것 | 이름만 바꾸면 돌아가는가 |
|---|---|---|---|
| F-sonnet-1 | 예 | spec/keylocallock.common.kv:16:18: error[unresolved]: no class 'KeyLocalLock.LockInfo' in the module object, its outer class, package com.linecorp.cse.reqshield.kotlin.coroutine or the imports of KeyLocalLock.kt; spec/keylocallock.coroutine.kv:8:33: error[unresolved]: no property 'tokenSequence' in the module object, its outer class or package com.linecorp.cse.reqshield.kotlin.coroutine; spec/keylocallock.common.kv:13:33: error[unresolved]: no property 'lockMap' in the module object, its outer class or package com.linecorp.cse.reqshield.kotlin.coroutine; spec/keylocallock.common.kv:85:30: error[ambiguous-selector]: compute in KeyLocalLock.Companion matches 0 action calls []; it must match exactly one | 아니오 (이름 바꿈 0개 뒤에도 묶기 실패) |
| F-sonnet-2 | 예 | spec/keylocallock.common.kv:16:18: error[unresolved]: no class 'KeyLocalLock.LockInfo' in the module object, its outer class, package com.linecorp.cse.reqshield.kotlin.coroutine or the imports of KeyLocalLock.kt; spec/keylocallock.coroutine.kv:8:33: error[unresolved]: no property 'tokenSequence' in the module object, its outer class or package com.linecorp.cse.reqshield.kotlin.coroutine; spec/keylocallock.common.kv:13:33: error[unresolved]: no property 'lockMap' in the module object, its outer class or package com.linecorp.cse.reqshield.kotlin.coroutine; spec/keylocallock.common.kv:85:30: error[ambiguous-selector]: compute in KeyLocalLock.Companion matches 0 action calls []; it must match exactly one | 아니오 (이름 바꿈 0개 뒤에도 묶기 실패) |
| F-sonnet-3 | 예 | spec/keylocallock.common.kv:16:18: error[unresolved]: no class 'KeyLocalLock.LockInfo' in the module object, its outer class, package com.linecorp.cse.reqshield.kotlin.coroutine or the imports of KeyLocalLock.kt; spec/keylocallock.coroutine.kv:8:33: error[unresolved]: no property 'tokenSequence' in the module object, its outer class or package com.linecorp.cse.reqshield.kotlin.coroutine; spec/keylocallock.common.kv:13:33: error[unresolved]: no property 'lockMap' in the module object, its outer class or package com.linecorp.cse.reqshield.kotlin.coroutine; spec/keylocallock.common.kv:85:30: error[ambiguous-selector]: compute in KeyLocalLock.Companion matches 0 action calls []; it must match exactly one | 아니오 (이름 바꿈 0개 뒤에도 묶기 실패) |

## 7.2 정확성 (본 실험 중 범위 안)

칸의 ID는 그 구현이다. FAILED (invariant only)는 hole을 다시 써도 증명되지 않은 것이다. FAILED (pending)는 hole 단계가 남은 것이다.

| 정답 \ 도구 최종 | PROVEN | FAILED (property) | FAILED (invariant only) | FAILED (pending) |
|---|---|---|---|---|
| 결함 (sidecar 성질) | - | T-haiku-2, T-haiku-4, T-sonnet-1, T-sonnet-3, T-sonnet-4, T-opus-2 | - | - |
| 결함 (sidecar 밖) | - | - | - | - |
| 정상 | - | - | - | - |
| 미결 | - | - | - | - |
| 판정 대기 | - | - | - | - |

범위 밖이거나 대기 중인 본 실험 구현: T-haiku-1 (out of scope), T-haiku-3 (out of scope), T-sonnet-2 (binding failure), T-opus-1 (binding failure), T-opus-3 (binding failure), T-opus-4 (binding failure)
