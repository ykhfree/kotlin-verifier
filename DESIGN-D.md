# Translator D 설계: compute 밖에서 만든 토큰 (Part B)

날짜: 2026-10-26. 상태: 설계안 (도구 코드 변경 전). 범위: `AI-CODE.md` 14.1절의 Part B. AI 구현이 소유 토큰을 `lockMap.compute` 밖에서 만드는 모양을 건전하게 검증하는 방법.
기준: `DESIGN-v0.md`(L1 모델, KVIR, 5.2절 emitter 템플릿, 3.5절 ghost 규칙), `DESIGN-C.md` 7.3절(도구 사실). 이 문서는 그 위에 더하는 것만 정한다.

수치 표시 규칙: "측정"은 이번 작업에서 실행해서 얻은 값이다. "추정"은 실행하지 않은 값이다. "판단"은 논증이고 실행하지 않았다.
실험 환경: Silicon `v.26.08-release`, Z3 4.16.0, Apple M4 Pro.
실험 모델: 손으로 옮긴 "예상 emitter 출력"이다 (`DESIGN-v0.md` 3.3절과 같은 방식). 머리 부분(도메인, 필드, macro, 서명된 hole)은 지금 도구가 참조 구현에서 낸 모델(`kv emit`, 2026-10-26)을 그대로 썼다. 파일은 부록 A에 있다.
저장소에서 바꾼 파일은 이 문서뿐이다.

## 0. 요약

| 항목 | 결정 (권고) | 근거 |
|---|---|---|
| 모델 | **(a) 연산 하나를 atomic 단계 둘로 모델링한다.** 예약 단계 R(`tokenSequence.incrementAndGet()`)과 action B(`lockMap.compute`)다. R과 B 사이에는 다른 스레드의 action과 예약 단계가 몇 번이든 끼어든다 | 2.1절, 3.1절 |
| ghost | 도구 ghost 필드 `gPend` 하나를 더한다. 뜻: 예약되었고 아직 소비되지 않은 토큰. R이 세우고, 같은 스레드의 B가 지운다. 도구 사실 `MI_PEND`와 `PEND`를 모든 메서드가 본문과 함께 증명한다 | 2.1절, 6.6절 |
| set-once | 뜻은 그대로다. 예약 단계가 있는 모듈에서는 발급마다 `!gPend(res)`도 확인한다 | 6.6절 |
| 건전성 | 축소(reduction)나 데이터 독립 가정 없이 성립한다. hole의 내용, 성질의 모양, 예약 값의 쓰임새에 기대지 않는다 | 3.1절 |
| 측정 | T-haiku-1, -2, -4의 손 모델에서 Silicon이 원본에 보고한 실패는 tryLock과 sweep의 `ends_only_if P3a`뿐이다. 이것은 정답의 결함과 같다. 경계만 고친 대조군 3개는 서명된 hole 그대로 PROVEN이다 | 4절 |
| 다른 안 | (b) 신뢰 fresh 값: 그대로 쓰면 거짓 PROVEN 2건 (측정). (c) increment를 action 안으로 옮김(Lipton 축소): 조건 C1-C4가 필요하고, C4는 도구가 다 확인하지 못한다. 조건을 어긴 3건에서 거짓 PROVEN (측정) | 3.3절, 3.4절 |
| W10 | 결과가 셀, 상수, 그 연산의 예약 지역 변수만 읽으면 허용한다. `return if (granted.get()) token else null`이 여기에 든다 | 7절 |
| 기존 모듈 | 바뀌지 않는다. 예약 단계가 없는 모듈은 지금과 같은 Viper를 낸다 (설계 규칙; D3에서 바이트 비교로 확인) | 6.10절 |
| 비용 | Silicon: 손 모델 9.9-12.4 s, 지금 참조 모델 5.1 s (측정). 늘어난 시간은 대부분 probe다. 구현: 마일스톤 D0-D3, 5.5-8.5 인일 (추정) | 4절, 9절 |

## 1. 문제

### 1.1 코드 모양

AI 구현 12개 중 3개(T-haiku-1, T-haiku-2, T-haiku-4)가 토큰을 compute 밖에서 만든다 (측정: 12개 파일에서 `incrementAndGet`의 위치를 grep).
세 구현의 tryLock은 같은 모양으로 시작한다.

```kotlin
val token = "local-${tokenSequence.incrementAndGet()}"   // T-haiku-4 37행: compute 앞
val granted = AtomicBoolean(false)
lockMap.compute(lockKey) { _, existing -> ... LockInfo(..., token = token) ... }
return if (granted.get()) token else null                 // T-haiku-4 60행
```

1. T-haiku-1(41행)과 T-haiku-2(45행)는 결과를 셀(`grantedToken.get()`)로 돌려준다.
2. T-haiku-4는 결과에서 `token`을 직접 읽는다.
3. 나머지 9개는 increment를 compute 안에서 한다. 참조 구현도 그렇다: `nextToken()`(80행)을 compute 안의 169, 182, 187행에서 부른다.
4. `RULES.md`(AI-CODE.md 4.1절)는 이 모양을 막지 않는다. 규칙 1은 토큰의 모양만 정한다. 규칙 3은 lockMap 접근만 compute 안으로 묶는다. 그래서 같은 규칙으로 만든 AI 코드는 이 모양을 계속 낼 것이다 (판단).

### 1.2 지금의 처리

1. L1 모델에서 연산은 atomic action 하나다 (`DESIGN-v0.md` 1.1절 원칙 6, W10).
2. counter `tokenSequence`는 모듈 상태 `state seq: Counter`다. action 밖의 `incrementAndGet()`은 ledger에서 UNSUPPORTED 모듈 상태 접근이다 (`Audit.access`).
3. AI-CODE.md 13절 수정 1 뒤에는, 입력 값이 그 접근을 거치는 action을 검증하지 않는다 (`Inputs.kt`의 `checkInputs`). 2b 보고서(T-haiku-2): "UNSUPPORTED input newToken in action tryLock#47 at KeyLocalLock.kt:45:9: its value comes from code outside the action that the model does not translate (tokenSequence operation outside an action: tokenSequence.incrementAndGet() at KeyLocalLock.kt:45:33)".
4. T-haiku-4의 결과는 W10에도 걸린다: "UNSUPPORTED operation result in action tryLock#39 at KeyLocalLock.kt:60:16: the result reads only cells and constants (W10); if (granted.get()) token else null is neither".
5. 수정 1 전(2차)에는 이 입력을 임의 값으로 두고 검증했다. 그 결과 T-haiku-2의 tryLock은 실제 위반(P3a) 말고도 MI_INV_1, MI_INV_3, cap_unbacked, set-once에서 실패했다 (AI-CODE.md 12.1의 거짓 이유). 임의 값은 NULL이거나 이미 발급된 토큰일 수 있기 때문이다.

### 1.3 무엇이 어려운가

1. 연산 하나에 atomic 단계가 둘이다. R은 `seq := seq + 1`이고 값 n을 준다. B는 compute다.
2. R과 B 사이에는 다른 스레드가 무엇이든 할 수 있다: 다른 tryLock의 R과 B, unLock, monitor의 sweep.
3. 그래서 B가 시작할 때 n보다 큰 토큰이 이미 발급되었을 수 있다. "n은 앞선 모든 값보다 크다"는 B 시점에서 참이 아니다.
4. B의 발급 검사(set-once, `assert !tok(res).gIssued`)에는 "n이 B 시점까지 발급되지 않았다"가 필요하다. 이 사실은 "다른 action이 n을 발급하지 않는다"에 기댄다. action마다 따로 하는 지금의 검사는 이것을 보지 않는다.

### 1.4 정답 (AI-CODE.md 11절, 10.1절)

1. 세 구현의 tryLock은 모두 결함이다. 만료 경계가 `>=`라서 lease 시각에 아직 유효한 lock을 넘겨받는다 (P3a, progress_live).
2. 세 구현의 sweep에도 같은 경계 결함이 있다. 세 구현의 unLock은 정상이다.
3. 판정자들은 그 밖의 sidecar 성질(P1a, P1a_entry, P3b, cap_unbacked, expired_takeover 등)이 세 구현에서 성립한다고 판정했다 (`judgments-1.md` 1절: T-haiku-1, T-haiku-4; `judgments-3.md` 2절 표: T-haiku-2).
4. 판정자들은 compute 밖의 토큰 생성을 결함으로 보지 않았다.
   - `judgments-3.md` 주 1 (T-haiku-2): "번호는 호출마다 하나뿐이므로 실행에서는 P1a가 성립한다. 검증기는 이것을 증명하지 못할 수 있다".
   - `judgments-1.md` 8절 (T-haiku-1, T-haiku-4): "번호에 빈칸이 생길 뿐 성질에는 영향이 없다".
5. 판정자 1은 부 결함 하나를 더 적었다: `now + lockTimeoutMillis`의 `Long` overflow (`judgments-1.md` 7절 2, "모델 의존"). 도구는 `Long`을 무한 정수로 가정하고, 이 가정을 보고서에 적는다. 그래서 이 결함은 모델 밖이다.
6. 그래서 경계만 엄격하게 고친 복사본은 모델 안에서 정상이어야 한다. 이 문서는 그 복사본을 대조군으로 쓴다. 판정자 1의 대조군 CTRL-strict(T-haiku-1에서 경계 한 줄만 바꿈)와 같은 생각이다.

## 2. 선택지

### 2.1 (a) 두 atomic 단계와 예약 ghost

**전이 시스템 (L1′).**
1. 전역 상태는 지금과 같다: 모듈 상태 S(map, counter, 객체)와 토큰마다의 ghost 기록. ghost 기록에 도구 필드 `gPend`를 더한다.
2. 전이는 세 종류다.
   - action: 지금과 같다 (compute 한 번).
   - 예약 단계 R (새로 더함): `seq := seq + 1; n := seq`. 그 스레드는 n을 지역 `val`로 가진다.
   - 스레드 지역 코드: S와 ghost를 바꾸지 않는다. ledger가 확인한다 (action 밖의 모듈 상태 쓰기는 지금도 UNSUPPORTED다).
3. 예약 단계가 있는 연산은 R 다음에 B를 실행한다.
4. R과 B 사이에 끼어들 수 있는 것: 다른 스레드의 R, 다른 스레드의 action(tryLock, unLock, sweep, stray action), 그 스레드 자신의 지역 코드. 횟수와 순서는 제한이 없다.
5. 모델은 끼어듦을 하나씩 나열하지 않는다. 대신 B의 전제를 모든 전이가 지키는 사실(`gPend(n)`)로 쓴다. 각 전이가 그 사실을 지킨다는 것을 Silicon이 메서드마다 증명한다 (3.1절).

**ghost 규칙.**
1. R: n이 발급되지 않았음(`!gIssued`)과 아무도 예약하지 않았음(`!gPend`)을 assert한다. 그다음 `gPend(n) := true`.
2. B: 전제는 `gPend(n)`이다. 커밋 지점에서 END 다음에 `gPend(n) := false`(소비), 그다음 ISSUE다.
3. 모든 발급(ISSUE): 지금의 `assert !tok(res).gIssued` 뒤에 `assert !tok(res).gPend`를 더한다. 뜻: 다른 스레드가 예약한 토큰을 발급하지 않는다.
4. 도구 사실 (본문과 함께 증명):
   - `MI_PEND`: 예약된 토큰은 발급되지 않았고, `0 <= qt <= S.seq`다.
   - `PEND`: R은 예약에 n 하나만 더한다. B는 자기 n 하나만 지운다. 그 밖의 action은 `gPend`를 바꾸지 않는다.
5. set-once `gIssued`, END, ISSUE 값, 허용 조건, HIST는 그대로다.

**값의 흐름.** n은 B 메서드의 파라미터가 된다. B 안에서 n은 정확한 값이다. 산술, 비교, 토큰 템플릿에 모두 쓸 수 있다.

**고려하고 버린 형태: 권한으로 예약.** 예약을 Viper 권한 `acc(tok(n).gPend)`으로 MI 밖에 꺼내 둘 수도 있다. 그러면 다른 action이 n을 건드리지 못함을 권한 계산이 보장한다. 그러나 `GHOST_PERMS`에 "예약되지 않은 토큰만"이라는 heap 의존 조건이 들어가고, hole과 MI의 구조가 바뀐다. Bool 필드와 증명된 PEND 사실로 같은 보장을 얻으므로 쓰지 않는다 (판단).

### 2.2 (b) 신뢰 fresh 값 공급원

action 밖의 `AtomicLong.incrementAndGet()`을 신뢰 호출로 본다. 계약은 "유일하고, 앞선 모든 값보다 크다"이고, 모델 상태에 다른 효과는 없다. action은 그 값을 계약이 있는 입력으로 받는다.

세 형태를 비교했다.
1. **(b) 그대로**: 입력 n의 전제는 `!gIssued(n) && (forall qt :: gIssued(qt) ==> qt < n)`이다. 모델의 counter는 바뀌지 않는다.
2. **(b counted)**: R의 increment는 모델에 남긴다. 전제는 `!gIssued(n) && 0 <= n <= S.seq`다. "크다"는 뺐다.
3. **(b′)**: (b counted)에 모듈 전체의 출처 규칙을 더한다. 규칙: 모든 발급 값은 NULL, 그 연산 자신의 예약 값, action 안 increment 값 중 하나다. 도구가 데이터 흐름으로 확인한다.

결과:
1. (b)와 (b counted)는 건전하지 않다 (3.3절, 측정).
2. (b′)는 건전하다 (판단, 3.3절 4).
3. 그러나 (b′)의 출처 규칙은 새 종류의 구문 검사다. 규칙에 맞지 않는 결함 코드(예: entry의 옛 토큰을 다시 발급)는 FAILED가 아니라 UNSUPPORTED가 된다. 지금 도구는 그런 코드를 set-once로 FAILED라고 판정한다.

### 2.3 (c) increment를 action 안으로 옮김 (Lipton 축소)

R을 B의 맨 앞으로 옮겨 모델링한다. lowering이 B 본문 맨 앞에 `ModelOp(incrementAndGet, seq)`를 넣는다. emitter, ghost 규칙, probe는 그대로다. 생성 모델은 참조 구현과 같은 모양이 된다.

근거는 Lipton 축소다.
1. R은 counter만 바꾼다. 그래서 R과 다른 스레드의 전이는 순서를 바꿀 수 있다 (R은 right mover).
2. 단, 순서를 바꾸면 counter가 돌려주는 값이 바뀐다.
3. 그래서 "토큰 값의 이름을 바꾸어도 판정이 같다"(데이터 독립)가 함께 필요하다.

조건 (하나라도 어기면 축소는 건전하지 않다):
- **C1**: counter는 increment로만 바뀌고, action 안에서 `get()`으로 읽히지 않는다. action 안에서 읽히면 R은 right mover가 아니다.
- **C2**: R 모양(6.3절): 조건 없이 한 번, B 앞, 같은 함수.
- **C3**: 예약 값은 토큰 템플릿으로만 쓰인다. 산술이나 비교에 쓰이면 데이터 독립이 깨진다.
- **C4**: sidecar의 hole 밖 식은 토큰의 순서와 counter 값을 보지 않는다. hole은 증명 도구이므로 조건 밖이다.

확인 가능성:
1. C1-C3는 lowering이 KVIR에서 확인할 수 있다 (판단).
2. C4의 "counter 값" 부분은 이름 검사로 확인할 수 있다.
3. C4의 "토큰 순서" 부분은 도구가 다 확인하지 못한다.
   - sidecar 식은 Viper 텍스트다.
   - 토큰은 Viper `Int`로 나간다.
   - 도구에는 sidecar 식의 타입 검사기가 없다.
   - 예: `now > old(tok(qt).gLease)`(허용)와 `res > qt`(금지)를 가르려면, `tok(qt).gLease`는 `Int` 필드이고 `qt`는 토큰임을 알아야 한다.

### 2.4 (d) 지원하지 않음 (대조)

지금처럼 UNSUPPORTED로 둔다. 다음 생성 실험의 `RULES.md`에 "토큰은 compute 람다 안에서 만든다"를 더할 수 있다.
1. 비용은 0이다.
2. 세 tryLock은 판정을 받지 못한다.
3. 규칙 없이 쓴 AI 코드는 이 모양을 계속 낸다.

## 3. 건전성

### 3.1 (a)의 논증

전역 불변식 J:
1. `MI(S) && MI_WITNESS(S) && MI_PEND(S)`.
2. R을 마치고 B를 아직 시작하지 않은 스레드 T마다 `gPend(n_T)`.
3. 그런 스레드들의 `n_T`는 서로 다르다.

각 전이가 J를 지킨다.
1. **init**: init 메서드가 1을 증명한다. 아직 그런 스레드가 없으므로 2와 3은 참이다.
2. **스레드 T의 R**: R 메서드가 1을 보존한다. R은 `!gPend(n)`을 증명한 뒤 `gPend(n)`을 세운다. 그래서 n은 다른 스레드의 예약과 다르다 (3). PEND 사실은 "R은 n 말고 다른 예약을 바꾸지 않는다"를 증명한다 (2).
3. **스레드 T의 B**: B의 전제 `gPend(n_T)`는 J.2가 준다. B 메서드가 1을 보존한다. PEND 사실은 "B는 `n_T`만 지운다"를 증명한다. 다른 스레드의 `n_T'`는 J.3에 따라 `n_T`와 다르므로 남는다.
4. **그 밖의 action과 stray action**: PEND 사실은 "`gPend`는 그대로다"를 증명한다.
5. **스레드 지역 코드**: S와 ghost를 바꾸지 않는다 (ledger).

결론:
1. 도달 가능한 모든 상태에서 J가 성립한다. 그래서 실제 실행에서 모든 메서드의 전제가 성립한다.
2. 각 action의 사후조건(성질)은 v0와 같은 이유로 실제 실행에서 성립한다.
3. B의 set-once는 `MI_PEND`(예약된 토큰은 발급되지 않음)와 `gPend(n_T)`에서 나온다.
4. 이 논증은 hole의 내용에 기대지 않는다. 성질이 토큰의 순서나 counter를 보아도 된다. 예약 값을 산술이나 비교에 써도 된다. 모델이 실제 값과 실제 끼어듦을 담기 때문이다.

한계:
1. R 뒤에 스레드가 죽거나 예외로 B에 가지 못하면, 그 예약은 영원히 남는다. `gPend(n)`은 참으로 남고, 아무도 n을 발급할 수 없다. 이것은 건너뛴 토큰일 뿐이다. J.2는 살아 있는 스레드에만 말한다.
2. J를 엮는 논증은 문서의 것이다. 각 단계의 의무(MI, `MI_PEND`, PEND, R의 assert)는 Silicon이 증명한다. v0의 "모든 action이 MI를 보존하므로 도달 가능한 모든 상태에서 MI가 성립한다"와 같은 종류의 논증이다.

### 3.2 (a)에서 거짓 PROVEN이 될 수 있는 길

| 길 | 막는 장치 |
|---|---|
| lowering이 R 모양이 아닌 코드를 R로 받음 (조건부 increment, 반복, 두 번 예약, 다른 곳으로 새는 값, B 뒤의 increment) | 6.3절의 좁은 모양 규칙. 거부하는 모양마다 fixture. W17 |
| emitter의 gPend 규칙 오류 | `MI_PEND`와 PEND를 사후조건으로 증명한다. 측정: 소비를 지운 모델(brk2)은 PEND와 발급 검사에서 FAILED다. 발급 검사를 지운 모델(brk1)도 `MI_PEND`에서 FAILED다 |
| R과 B 사이의 코드가 모듈 상태를 바꿈 | 지금의 ledger 규칙 (UNSUPPORTED, 모듈 INCOMPLETE) |
| 결과가 B 뒤에 바뀌는 값을 읽음 | W10 완화는 셀, 상수, 예약 지역 `val`만 허용한다. `val`은 바뀌지 않는다. 셀은 그 스레드의 지역 변수와 같다 (`DESIGN-v0.md` 2.5절 5) |
| 예약 단계가 서로 다른 counter를 쓰고, 두 counter가 같은 토큰 공간을 만듦 | v1은 모듈의 예약 단계가 쓰는 counter를 하나로 제한한다 (W17). 어기면 UNSUPPORTED |

### 3.3 (b)의 반례 (측정)

1. **(b) 그대로, E5 fence.** 가상 구현이다. 만료된 lock은 `n > existing.epoch`일 때만 넘겨받는다. epoch는 주인이 예약한 값이다 (fencing).
   - 실제 실행: T1이 n1 = 1을 예약하고 늦어진다. T2가 n2 = 2를 예약하고 lock을 잡는다. T2의 lock이 만료된 뒤 T1이 시계를 읽고 B를 돈다. T1은 만료를 보고도 1 < 2라서 거절한다. 그래서 서명된 `expired_takeover`("만료된 entry가 있으면 토큰을 받는다")가 깨진다.
   - 이 실행은 시계를 예약 뒤에 읽는 구현에서 단조 시계로도 생긴다. 도구의 시계 모델은 단조성을 가정하지 않는다 (`jdk.System.currentTimeMillis`). 그래서 시계를 먼저 읽는 구현에서도 모델은 이 실행을 허용한다.
   - (b): 전제 "n은 앞서 발급된 모든 값보다 크다"가 이 실행을 모델에서 지운다. 결과는 PROVEN이다 (거짓).
   - (a): `expired_takeover`에서 FAILED다.
2. **(b counted), E6 steal.** 작은 모듈이다.
   - 연산 Y는 n을 예약한다. action 안에서 `n != seq.get()`이면 n을 발급한다.
   - 연산 X는 action 안에서 `seq.get()` 값을 발급하고 increment한다.
   - hole: `0 <= S.seq`, "발급된 토큰은 `S.seq` 이하", "`S.seq`는 발급되지 않음".
   - 실제 실행: Y가 5를 예약한다 (seq = 5). X가 5를 발급하고 seq를 6으로 만든다. Y의 B는 5 != 6이므로 5를 발급한다. 같은 토큰이 두 번 발급된다 (P1a의 `!old(gIssued)`가 깨짐).
   - (b counted): Y의 전제 `!gIssued(n)`이 이 실행을 지운다. 결과는 PROVEN이다 (거짓).
   - (a): X의 `assert !tok(res).gPend`에서 FAILED다. 뜻: X가 다른 스레드의 예약을 발급한다.
3. **원인.** `!gIssued(n)`은 "다른 action이 예약된 값을 발급하지 않는다"에 기댄다. action별 검사는 이것을 보지 않는다. 이것을 확인하는 길은 둘이다: (a)의 gPend, 또는 (b′)의 출처 규칙.
4. **(b′)가 건전한 이유 (판단).** 출처 규칙 아래에서 모든 발급 값은 자기 예약 값이거나 action 안 increment 값이다.
   - action 안 increment 값은 그 시점의 `S.seq + 1`이다. 그래서 모든 예약 값(`S.seq` 이하)보다 크다.
   - 자기 예약 값은 counter의 유일성 때문에 다른 스레드의 예약 값과 다르다.
   - 그래서 예약된 n은 B 전에 발급되지 않는다.

### 3.4 (c)의 조건과 반례 (측정)

조건을 하나씩 어긴 세 모델에서 (c)는 거짓 PROVEN을 냈다. (a)는 셋 모두 FAILED였다.

| 어긴 조건 | 모델 | (c) | (a) | 실제 코드 |
|---|---|---|---|---|
| C4 (성질이 토큰 순서를 봄) | T-haiku-2 대조군에 가상 성질 `P_mono`를 더함: "새 토큰은 앞서 발급된 모든 토큰보다 크다" | PROVEN | FAILED (P_mono) | 위반한다. T1이 1을 예약하고, T2가 2를 예약하고 발급하고, T1이 1을 발급한다 |
| C3 (예약 값을 비교에 씀) | E5 fence | PROVEN | FAILED (expired_takeover) | 위반한다 (3.3절 1) |
| C1 (action 안 `get()`) | E6 steal | PROVEN | FAILED (`!gPend` 발급 검사) | 위반한다 (3.3절 2) |

1. C1-C4가 모두 성립하면 (c)는 건전하다 (판단; Lipton 축소와 데이터 독립의 표준 논증).
2. T-haiku-1, -2, -4와 서명된 sidecar는 C1-C4를 만족한다 (판단). 서명된 sidecar의 hole 밖 식은 토큰을 `==`, `!=`, `tok(·)`의 색인으로만 쓰고, `S.seq`를 쓰지 않는다.
3. 그러나 순서를 보는 성질이 하나라도 sidecar에 들어오면, (c)는 조용히 거짓 PROVEN을 낼 수 있다 (P_mono, 측정).

## 4. 실험 (측정)

방법:
1. `kv emit`으로 참조 구현(coroutine)의 모델을 만들었다 (지금 도구, 서명된 sidecar).
2. 그 1-105행(도메인, 필드, macro, 서명된 hole)을 그대로 썼다. AI 구현의 action은 v0 규칙대로 손으로 옮겼다.
3. A1(`Semaphore(1).apply { tryAcquire() }`)과 A5(`existing.isExpired(now)`)는 3차 구현(AI-CODE.md 14.1)이 낼 모양을 가정해 옮겼다.
4. 같은 본문에 (a) 또는 (c)의 틀을 씌웠다. E5와 E6은 따로 만든 모델이다.
5. 파일마다 `kv verify`와 같은 Silicon 옵션으로 한 번씩 차례로 돌렸다 (`run_all.sh`). 표의 시간은 그 실행의 값이다. 같은 파일을 따로 돌린 실행과는 최대 2.7 s 달랐다.

| 모델 | 선택지 | 결과 | 실패한 의무 | Silicon (s) |
|---|---|---|---|---|
| 참조 구현 (`kv emit` 출력) | 지금 도구 | PROVEN | - | 5.11 |
| T-haiku-1 원본 | (a) | FAILED | tryLock `ends_only_if P3a`, sweep `ends_only_if P3a` | 10.03 |
| T-haiku-1 대조군 | (a) | PROVEN | - | 9.90 |
| T-haiku-2 원본 | (a) | FAILED | 위와 같음 | 10.29 |
| T-haiku-2 대조군 | (a) | PROVEN | - | 12.39 |
| T-haiku-4 원본 | (a) | FAILED | 위와 같음 | 12.13 |
| T-haiku-4 대조군 | (a) | PROVEN | - | 10.91 |
| T-haiku-1 / -2 / -4 원본 | (c) | FAILED | 위와 같음 | 6.28 / 6.34 / 6.58 |
| T-haiku-1 / -2 / -4 대조군 | (c) | PROVEN | - | 6.23 / 6.66 / 6.06 |
| T-haiku-2 대조군 + `P_mono` | (a) | FAILED | P_mono | 12.26 |
| T-haiku-2 대조군 + `P_mono` | (c) | **PROVEN (거짓)** | - | 6.20 |
| E5 fence | (a) | FAILED | expired_takeover | 5.42 |
| E5 fence | (b) | **PROVEN (거짓)** | - | 4.43 |
| E5 fence | (c), C3 무시 | **PROVEN (거짓)** | - | 4.01 |
| E6 steal | (a) | FAILED | X의 `assert !tok(res).gPend` | 2.51 |
| E6 steal | (b counted) | **PROVEN (거짓)** | - | 1.78 |
| E6 steal | (c), C1 무시 | **PROVEN (거짓)** | - | 2.10 |
| E6 대조 (X가 increment한 뒤 그 값을 발급; hole에서 "`S.seq`는 발급되지 않음"을 뺌) | (a) | PROVEN | - | 2.39 |
| PB6: T-haiku-4 대조군의 결과를 항상 `token`으로 | (a) | FAILED | `unbacked_only_if P3b` | 12.04 |
| brk1: E6에서 X의 `!gPend` 발급 검사를 지움 | (a) | FAILED | X의 `MI_PEND` | 1.78 |
| brk2: T-haiku-2 대조군에서 B의 소비(`gPend := false`)를 지움 | (a) | FAILED | tryLock의 PEND, 발급 검사 `!gPend` | 10.60 |
| T-haiku-2 대조군, `MI_PEND`를 MI 안에 넣음 | (a) 변형 | FAILED (거짓 실패) | probe의 호출 전제 3개 | 36.79 |
| T-haiku-2 대조군, PEND 사실을 모두 뺌 | (a) 변형 | FAILED | probe에서 tryLock의 전제 `gPend` | 13.33 |

관찰:
1. (a)에서 원본 3개에 보고된 실패는 정답의 결함과 같다 (tryLock과 sweep의 경계; unLock은 PROVEN). 2차의 거짓 이유(MI_INV_1, MI_INV_3, cap_unbacked, set-once)는 나오지 않는다. 모델이 예약 값을 "NULL이 아니고 발급되지 않은 값"으로 알기 때문이다.
2. Silicon은 경로마다 처음 실패한 사후조건만 보고한다 (`DESIGN-v0.md` 6절). 그래서 경계 경로의 progress_live 위반은 따로 나오지 않는다. 계약에서 P3a가 progress_live보다 앞에 있기 때문이다. 같은 이유로, 경계 경로에서 P3a보다 앞에 있는 절(hole 조각, HIST, 도구 사실)과 본문의 set-once assert는 그 경로에서 성립한다.
3. 대조군 3개는 서명된 hole을 바꾸지 않고 PROVEN이다. hole의 `0 <= S.seq`와 "발급된 토큰은 0 이상 `S.seq` 이하"가 R의 fresh 검사를 증명한다.
4. probe도 검증된다. probe는 tryLock을 부르기 전마다 R을 부른다.
5. 메서드별 시간 (T-haiku-2 대조군, `TIMES=1`로 따로 실행): (a)는 tryLock 1.89 s, reserve 0.76 s, probe 7.50 s다. (c)는 tryLock 1.99 s, probe 2.94 s다. 늘어난 시간은 대부분 probe다. action 메서드의 시간은 비슷하다.
6. brk1: 발급 검사 `!gPend(res)`를 지워도 `MI_PEND`("예약된 토큰은 발급되지 않음")가 같은 결함을 잡는다. 그래서 발급 검사는 진단 문구를 위한 것이고, 건전성은 `MI_PEND`도 지킨다. 테스트는 판정만이 아니라 보고 문구도 확인해야 한다.
7. `MI_PEND`는 MI 밖에서 따로 `requires`/`ensures`로 둔다 (`MI_WITNESS`와 같은 방식). MI 안에 넣으면 probe가 호출 전제를 증명하지 못했다.
8. PEND 사실은 R에도 필요하다. 빼면 probe에서 tryLock의 전제 `gPend(r)`를 증명하지 못한다.

## 5. 비교와 권고

| 기준 | (a) 두 단계 + 예약 ghost | (b) 신뢰 fresh 값 | (c) action 안으로 옮김 | (d) 지원하지 않음 |
|---|---|---|---|---|
| 건전성 | 조건 없이 성립 (3.1절) | 그대로는 거짓 PROVEN (측정 2건). 고치려면 (a)의 gPend나 (b′)의 출처 규칙이 필요 | C1-C4가 성립할 때만. C4를 기계로 다 확인하지 못함. 조건을 어기면 거짓 PROVEN (측정 3건) | 판정 없음 |
| T-haiku-1, -2, -4의 tryLock | 판정. 원본 FAILED (P3a), 대조군 PROVEN (측정, 손 모델) | (b counted)로 판정할 수 있으나 위의 문제 | 판정. (a)와 같은 결과 (측정, 손 모델) | UNSUPPORTED |
| 예약 값을 산술이나 비교에 씀 | 정확히 모델링 | 거짓 PROVEN 위험 (E5) | UNSUPPORTED (C3) | UNSUPPORTED |
| action 안에서 counter `get()` | 정확히 모델링 | 거짓 PROVEN 위험 (E6) | UNSUPPORTED (C1) | 해당 없음 |
| 순서를 보는 성질 | 바르게 판정 | 거짓 PROVEN 위험 | 거짓 PROVEN (C4를 확인하지 못하면) | 해당 없음 |
| Silicon 시간 (측정, 손 모델; 참조 5.11 s) | 9.90-12.39 s | 쟀지 않음 | 6.06-6.66 s | - |
| 도구 변화 | lowering, KVIR, W 규칙, emitter(ghost 필드 1, 메서드 1종, 사실 2), 보고서 | lowering, emitter 전제, (b′)는 출처 분석 | lowering, C1-C4 검사 | 없음 |
| 구현 비용 (추정) | 5.5-8.5 인일 | (b′) 4-6 인일 | 3.5-5 인일 | 0 |
| 기존 모듈 (gate 4개, 손 변이 63회) | 바뀌지 않음 | 바뀌지 않음 | 바뀌지 않음 | 바뀌지 않음 |

**권고: (a).**
1. (a)는 코드가 하는 일을 그대로 모델링한다: atomic 단계 둘과 그 사이의 끼어듦. 그래서 건전성이 sidecar의 모양이나 예약 값의 쓰임새에 기대지 않는다.
2. (c)는 싸다. 그러나 C4를 도구가 다 확인하지 못한다. 순서를 보는 성질이 하나라도 sidecar에 들어오면 거짓 PROVEN이 될 수 있다 (측정: P_mono).
3. (b)는 그대로는 건전하지 않다 (측정 2건). 고치면 (a)의 gPend나 (b′)의 출처 규칙이 필요하다. 출처 규칙은 결함 코드를 FAILED 대신 UNSUPPORTED로 보낸다.
4. (a)의 대가는 둘이다. 구현 비용이 (c)보다 약 2-3.5 인일 크다 (추정). 예약 단계가 있는 모듈의 Silicon 시간이 약 2배다 (측정, 대부분 probe). 실행당 예산 300 s에는 여유가 크다.

## 6. 도구 변화 ((a))

### 6.1 L1 전이 시스템

1. "연산마다 atomic action 하나"를 "연산마다 예약 단계 0개 또는 1개, 그 뒤 atomic action 하나"로 바꾼다.
2. 예약 단계는 counter 하나에 대한 atomic 전이다 (`jdk.AtomicLong.incrementAndGet`).
3. action의 정의, 커밋 지점, ghost 규칙 END와 ISSUE는 그대로다.
4. sidecar의 주석 "Operations: exactly one atomic action each"(`keylocallock.common.kv` 54행)는 설명 문구다. 문법과 뜻은 바뀌지 않는다.

### 6.2 KVIR

```kotlin
// One step outside the action of an operation (DESIGN-D): the reserve of a fresh counter value.
data class Reserve(
    val local: Local,         // the reserved val: token__37 (Token, from a template) or n__37 (Int, a raw Long)
    val counter: CounterVar,  // the `state ... : Counter` the step increments
    val pos: Pos,             // the incrementAndGet call
    val template: Pos?,       // the token template around the call, if the step builds the token
)
// Operation gets: val reserve: Reserve? = null

/** The reserved val of the operation's reserve step: the action method takes it as a parameter. */
data class Reserved(override val pos: Pos) : Origin
```

1. action의 자유 값 하나가 `Reserved` 출처를 가진다. 이 값은 sidecar 이름이 없어도 메서드 파라미터가 된다. B의 전제 `gPend(x)`를 쓰려면 파라미터여야 하기 때문이다.
2. T-haiku-4의 결과에는 조건 식이 필요하다. A2(값으로 쓴 if)가 조건 식 노드를 더하면 그것을 쓴다. 더하지 않으면 `Ite(c, a, b)` 식을 더한다 (Viper `c ? a : b`).

### 6.3 lowering: R 모양 규칙

action 밖의 `C.incrementAndGet()`은 다음을 모두 만족할 때만 예약 단계다.
1. 연산의 선택자가 `fun`이다 (`compute in`이 아니다). 모듈은 object 모듈이고, action은 `compute`다 (v1).
2. 연산 함수의 본문(또는 `Mono.fromCallable` 람다의 본문)에 지역 `val x = E`가 있다.
   - 이 선언은 본문의 직접 문장이다. if, when, 반복문, try, 다른 람다 안에 있지 않다.
   - 이 선언은 action을 부르는 문장보다 앞에 있다.
3. E는 다음 중 하나다. C는 sidecar의 `state ... : Counter`다.
   - `C.incrementAndGet()` (Long)
   - `C.incrementAndGet()`를 `${}`로 담은 토큰 템플릿 (`"local-${tokenSequence.incrementAndGet()}"`)
   - 본문이 위 둘 중 하나인 모듈 helper의 호출 (예: `nextToken()`; 지금의 helper 인라인 규칙을 따른다)
4. x는 다음 곳에서만 읽힌다: action 람다 안, 연산 결과 식, 효과 없는 신뢰 호출(slf4j)의 인자.
5. 연산마다 예약 단계는 많아야 하나다. 모듈의 예약 단계는 모두 같은 counter를 쓴다.

하나라도 어기면 지금처럼 처리한다 (UNSUPPORTED 모듈 상태 접근, 입력 오염). 보고서는 어긴 규칙을 함께 적는다. 예: "not a reserve step (DESIGN-D 6.3 rule 2): the increment is inside an if at 41:9".

T-haiku-1(41행), T-haiku-2(45행), T-haiku-4(37행)는 규칙 3의 둘째 형태다 (판단: 세 파일을 읽음).

### 6.4 audit, ledger, 입력 검사

1. R 문장의 FIR 노드는 예약 단계가 번역한 노드다. Audit에 새 영역(RESERVE)을 두고, lowering이 그 노드를 표시한다 (W14).
2. 그래서 그 increment는 `Audit.stateAccesses`에 들어가지 않는다. `checkInputs`는 x를 오염시키지 않는다.
3. R 모양이 아닌 increment는 지금처럼 `stateAccesses`에 들어간다.
4. 예약 increment 위치에 ASSUMPTION "Long unbounded"를 낸다 (W13).

### 6.5 W 규칙

- **W10 (바뀜)**: object 모듈의 결과는 셀, 상수, 그 연산의 예약 지역 변수만 읽는다. 조건 식은 각 부분이 이 규칙을 따르면 허용한다.
- **W17 (새)**: 예약 단계는 연산마다 많아야 하나다. 그 counter는 `state ... : Counter`다. 그 지역 변수는 그 연산 action의 `Reserved` 자유 값이다. 모듈의 예약 단계는 모두 같은 counter를 쓴다.
- W11, W13, W14: 규칙은 그대로이고, 예약 단계에도 적용한다.

### 6.6 emitter (`DESIGN-v0.md` 5.2절 템플릿에 더하는 것)

모듈에 예약 단계가 하나라도 있을 때만 아래를 낸다.

```text
field gPend: Bool        // tool ghost (DESIGN-D): reserved by a step outside an action, not consumed yet
define GHOST_PERMS ( ... && acc(tok(qt).gPend) )
define MI_PEND(S) (forall qt: Int :: { tok(qt).gPend } tok(qt).gPend ==> !tok(qt).gIssued && 0 <= qt && qt <= S.<counter>)
init:  ensures MI_PEND(S); inhale ... !tok(qt).gIssued && !tok(qt).gPend

// One method per reserve step.
method reserve_<op>(S: Ref) returns (<x>: Int)
  requires MI(S); requires MI_WITNESS(S); requires MI_PEND(S)
  ensures <the invariant block of an action: MI_PERMS, FRAME for every key, MI_OWN, MI_WITNESS, MI_PEND,
           invariants, MI_INV_i, HIST>
  ensures forall qt: Int :: { tok(qt).gHeld } tok(qt).gHeld == old(tok(qt).gHeld)        // the step ends nothing
  ensures forall qt: Int :: { tok(qt).gIssued } tok(qt).gIssued == old(tok(qt).gIssued)  // the step issues nothing
  ensures forall qt: Int :: { tok(qt).gPend } tok(qt).gPend == (old(tok(qt).gPend) || qt == <x>)   // PEND
{
  S.<counter> := S.<counter> + 1        // incrementAndGet @pos [ASSUME Long unbounded]
  var n_<l>_<c>: Int := S.<counter>
  assert 0 <= n_<l>_<c>                 // the reserved value is not the null sentinel
  <x> := n_<l>_<c>
  assert !tok(<x>).gIssued              // reserve: never issued
  assert !tok(<x>).gPend                // reserve: no thread holds it
  tok(<x>).gPend := true
}

// The action of an operation with a reserve step.
method <op>(S: Ref, k: Key, <named free values>, <x>: Int) returns (<res>)
  requires ...; requires MI_PEND(S)
  requires tok(<x>).gPend               // the thread holds the reservation of reserve_<op>
  ensures ...; ensures MI_PEND(S)
  ensures forall qt: Int :: { tok(qt).gPend } tok(qt).gPend == (old(tok(qt).gPend) && qt != <x>)   // PEND
{
  ... body ...                          // reads <x> as an exact value
  <res> := <result>                     // may read <x> (W10)
  // commit point: END as now, then
  tok(<x>).gPend := false               // consume the reservation
  if (<res> != NULL) { assert !tok(<res>).gIssued; assert !tok(<res>).gPend; <ISSUE writes as now> }
}

// Every other action and stray action of the module.
  requires MI_PEND(S); ensures MI_PEND(S)
  ensures forall qt: Int :: { tok(qt).gPend } tok(qt).gPend == old(tok(qt).gPend)       // PEND
  ISSUE (if the operation issues): assert !tok(<res>).gPend after the set-once assert

// probe, and scenario cells run on code: before each call of an operation with a reserve step
  var r_<i>: Int
  r_<i> := reserve_<op>(S)
  <outs> := <op>(S, ..., r_<i>)
```

1. 계약의 순서: `MI_PEND`는 `MI_WITNESS` 바로 뒤에 둔다. PEND 사실은 ISSUE_ONLY 바로 뒤에 둔다. 이 줄들은 예약 단계가 있는 모듈에만 있다. 그래서 기존 모델의 실패 의무 ID는 바뀌지 않는다.
2. 이름: `gPend`, `MI_PEND`, 메서드 접두어 `reserve_`를 `Reserved.tool`에 더한다. sidecar 이름이 이것과 겹치면 sidecar 오류다.
3. R의 assert는 늘 낸다. `0 <= n`은 R이 토큰 템플릿을 만들지 않아도 낸다. `MI_PEND`의 `0 <= qt`가 이것에 기대기 때문이다.
4. `MI_WITNESS` 줄은 지금처럼 모듈에 WITNESS 사실이 있을 때만 낸다.
5. 오류 대응 (`DESIGN-v0.md` 5.7절):
   - R의 `0 <= n`: Kotlin increment 위치.
   - R의 `!gIssued`, `!gPend`: "reserve: the next counter value may be issued or reserved; the hole must show that it is fresh"와 increment 위치.
   - 발급 검사 `!gPend(res)`: "issue <op>: the token is reserved by another thread"와 sidecar `issue` 위치.

### 6.7 보고서와 판정

```text
  action tryLock  KeyLocalLock.kt:47:9   FAILED (property)
      Postcondition of tryLock might not hold: sidecar ends_only_if P3a keylocallock.common.kv:65:3
  step   reserve tryLock  KeyLocalLock.kt:45:33   PROVEN   (tokenSequence.incrementAndGet() before the action; DESIGN-D)
```

1. 예약 단계는 action처럼 판정을 받는다: PROVEN, FAILED, ERROR.
2. 하위 상태도 같다. R의 실패가 hole 조각이나 R의 fresh assert뿐이면 "invariant only"다. fresh assert는 MI에만 기대기 때문이다.
3. 모듈 판정 규칙은 그대로다. 예약 단계는 action과 같이 센다.
4. "Assumptions"의 "Long unbounded"에 예약 increment 위치를 더한다.
5. 종료 코드는 그대로다.

### 6.8 sidecar 문법

바꾸지 않는다. 예약 단계는 코드의 구조이고, 명세가 아니다. 서명된 sidecar 8개와 T-haiku의 묶기 수정(`bindings.txt`)을 그대로 쓴다.

### 6.9 DESIGN-C 도구와 변이 도구

1. `kv spec check`(`--root` 없음, sidecar만): 코드가 없으므로 예약 단계도 없다. 그대로다.
2. `kv spec check --root`(절 분리): 실행마다 예약 메서드를 넣는다. 예약 메서드에는 sidecar 절이 없다.
3. 코드로 도는 칸: probe처럼 호출 앞에 예약 호출을 넣는다.
4. `kv mutation-sites`: R 문장의 노드도 변이 지점이다. 예: MOP `incrementAndGet` → `get`은 R 모양을 깬다. 그러면 값은 경쟁 읽기(임의 값)가 되고, 판정은 FAILED여야 한다.
5. M9 분기 도달성 복제본: B 메서드의 새 파라미터와 전제를 그대로 가진다. 예약 메서드에는 분기가 없으므로 복제하지 않는다.

### 6.10 바뀌지 않는 것

1. 예약 단계가 없는 모듈은 지금과 같은 Viper를 낸다.
   - 그래서 gate 4개, 손 변이 63회(`mutantsReqShield` 33, `mutantsAspect` 30), 기계적 변이 264개의 판정과 실패 의무 ID는 바뀌지 않는다.
   - 근거: 실제 모듈과 손 변이에는 action 밖 increment가 없다 (`scripts/mutants.py`의 편집을 읽음). SB4는 `nextToken()` 안의 increment를 `get()`으로 바꾸고, `nextToken()`은 compute 안에서만 불린다. aspect 모듈에는 counter가 없다.
   - D3가 바이트 비교로 확인한다.
2. ghost 규칙 END, ISSUE의 값, 허용 조건, HIST, set-once의 뜻.
3. hole 흐름(`kv hole check`)과 hole의 문법.
4. 판정 규칙과 종료 코드.

## 7. W10: `return if (granted.get()) token else null`

| 선택지 | 결과 식 | 왜 건전한가 |
|---|---|---|
| (a) | `res := granted ? x : NULL`. x는 B의 파라미터(예약 값)다 | x는 지역 `val`이므로 B가 본 값과 결과가 읽는 값이 같다. 셀 `granted`는 그 스레드의 지역 변수와 같다. 그래서 커밋 지점에서 계산한 결과는 실제 결과와 같고, ISSUE는 바른 값에 묶인다 |
| (b) | 같은 식. x는 신뢰 입력이다 | 같은 이유. 단, (b) 자체의 문제는 3.3절 |
| (c) | 같은 식. x는 B 안에서 만든 지역 변수다 | 같은 이유. 단, (c) 자체의 조건은 C1-C4 |

측정 (손 모델, (a)):
1. T-haiku-4 원본에서 Silicon이 보고한 실패는 tryLock과 sweep의 P3a뿐이다. 대조군은 PROVEN이다.
2. 거절할 때도 결과로 `token`을 돌려주는 변이(PB6)는 `unbacked_only_if P3b`에서 FAILED다. 다른 주인이 있는 key에 entry 없는 토큰을 내기 때문이다.

W10을 "캡처한 모든 `val`"로 넓혀도 같은 이유로 건전하다 (판단). v1은 예약 지역 변수만 허용한다 (10.2절 JD6).

## 8. 수락 테스트

정답은 AI-CODE.md 11절의 action 표다. 세 tryLock은 모두 결함이다 (경계 `>=`; P3a, progress_live).

| ID | 대상 | 필요한 것 | 기대 판정 | 손 모델 (측정) |
|---|---|---|---|---|
| AT1 | T-haiku-2 tryLock | Part B | FAILED (property). 첫 실패 의무는 `ends_only_if P3a`. set-once, cap_unbacked, MI_INV_1, MI_INV_3의 실패는 없다 | 같음 |
| AT2 | T-haiku-4 tryLock | A1, Part B (W10 포함) | FAILED (property), `ends_only_if P3a` | 같음 |
| AT3 | T-haiku-1 tryLock | A1, A5, Part B | FAILED (property), `ends_only_if P3a` | 같음 |
| AT4 | 세 구현의 대조군: tryLock과 sweep의 경계만 엄격하게 고침 (T-haiku-1은 `isExpired`의 `>=`를 `>`로) | AT1-AT3과 같음 | 모듈 PROVEN. 서명된 hole 그대로. probe 포함 | 3개 모두 PROVEN |
| AT5 | T-haiku-2의 Part B 변이 | Part B | 어느 것도 PROVEN이 아니다. PB1 action 밖 `get()`: FAILED (임의 토큰). PB2 `if` 안의 예약: UNSUPPORTED (6.3절 규칙 2). PB3 예약 둘: UNSUPPORTED (W17). PB4 토큰을 action 밖에서 companion 필드에 저장: UNSUPPORTED (6.3절 규칙 4: 값이 샘). PB5 entry에 action 안 `get()`으로 만든 토큰: FAILED | 쟀지 않음 |
| AT6 | T-haiku-4 대조군의 PB6 (결과를 항상 `token`) | A1, Part B | FAILED (`unbacked_only_if P3b`) | 같음 |
| AT7 | fixture: E5 fence, E6 steal, E6 대조 | Part B와 fixture sidecar | FAILED (expired_takeover); FAILED (X의 `!gPend` 발급 검사); PROVEN | 같음 |
| AT8 | emitter 깨뜨림 2개: 소비 지움, `!gPend` 검사 지움 | Part B | 둘 다 FAILED. 둘째는 보고 문구가 `MI_PEND`임을 확인한다 | 같음 |
| AT9 | 기존 | - | 실제 모듈 4개의 생성 모델이 바이트 단위로 같다. gate 4개 exit 0. `mutantsReqShield` 33회와 `mutantsAspect` 30회 불일치 0. 기계적 변이 재측정이 직전 재측정과 모든 행에서 같다 | - |
| AT10 | AI 구현 12개 다시 돌림 (A1-A5 뒤) | 전부 | A1-A5가 AI-CODE.md 14.2절 예측대로 들어가면 36개 action이 모두 판정을 받는다. 세 tryLock은 검출(FAILED)이다. 놓침 0, 오경보 0 | - |

## 9. 마일스톤

feature-developer가 순서대로 구현한다. 추정은 FIR과 Viper를 아는 사람 엔지니어 1명 기준이다. AI agent 세션의 시간은 넣지 않았다.

| D | 내용 | 수락 | 추정 (인일) |
|---|---|---|---|
| D0 | lowering: R 모양 규칙, KVIR `Reserve`와 `Reserved`, audit 영역, 입력 검사, W10 완화와 결과의 조건 식, W17, ASSUMPTION | fixture: 받는 모양 4개(템플릿, raw Long, helper `nextToken()`, T-haiku-4의 결과 모양). 거부하는 모양 6개(if 안, 반복문 안, 두 번 예약, 값이 필드로 샘, compute 뒤의 increment, `compute in` 연산). 받는 모양의 KVIR golden | 2-3 |
| D1 | emitter와 보고서: `gPend`, `MI_PEND`, 예약 메서드, B의 파라미터·전제·소비, 발급 검사, PEND 사실, probe 호출, 위치 표, 판정 | AT1, AT5-AT9 (T-haiku-2와 fixture만으로 된다) | 2-3 |
| D2 | DESIGN-C 도구와 변이 도구: 절 분리, 코드로 도는 칸, `kv mutation-sites` | T-haiku-2 복사본에서 `kv spec check --root`가 돈다. `kv mutation-sites`가 R 노드를 낸다 | 0.5-1 |
| D3 | 전체 확인과 기록: AT2-AT4, AT10, AI-CODE.md와 HISTORY.md 기록 | AT1-AT10 | 1-1.5 |
| 합계 | | | 5.5-8.5 (추정) |

순서 조건:
1. D0은 3차 구현(A1-A5)이 커밋된 뒤에 시작한다. 같은 곳(`ActionLowering.kt`의 결과 lowering과 값 if)을 고치기 때문이다. 이 문서를 쓰는 동안 작업 트리에는 A1-A5의 커밋되지 않은 변경이 있었다 (`git status`: `ActionLowering.kt`, `ModuleContext.kt`, lowering 테스트들, `ReasonTest.kt`, `aicode/round3a/`).
2. T-haiku-1과 T-haiku-4의 tryLock은 A1(T-haiku-1은 A5도)이 있어야 범위에 든다. T-haiku-2(AT1)는 Part B만 있으면 된다.

## 10. 열린 질문

### 10.1 사용자가 정할 것

| ID | 질문 | 권고 |
|---|---|---|
| UD1 | (a)를 채택하는가? (a)는 sidecar의 모양이나 예약 값의 쓰임새에 조건이 없다. 대가는 구현 5.5-8.5 인일(추정)과, 예약 단계가 있는 모듈의 Silicon 시간 약 2배(측정, 손 모델)다. (c)는 3.5-5 인일(추정)이지만, sidecar가 토큰 순서를 보지 않는다는 조건(C4)을 도구가 다 확인하지 못한다 | (a) |
| UD2 | 예약 값을 action 안에서 산술이나 비교에 쓰는 코드(예: fencing epoch)를 지원 범위에 넣는가? (a)에서는 추가 비용이 없다 | 넣는다 |
| UD3 | 다음 생성 실험의 `RULES.md`에 "토큰은 compute 람다 안에서 만든다"를 더하는가? 지금 규칙은 이 모양을 허용한다. 14.3절 일반화 측정은 같은 규칙을 쓰기로 등록했다 | 14.3절 측정이 끝난 뒤에 정한다. 그 전에는 바꾸지 않는다 |
| UD4 | 보고서에 예약 단계를 따로 한 줄로 보이는가? | 예 (6.7절) |

### 10.2 메인 세션이 정할 것

| ID | 판단 | 이 문서의 의견 |
|---|---|---|
| JD1 | Part B를 AI-CODE.md 14.3절 일반화 측정 전에 넣는가, 뒤에 넣는가? 전에 넣으면 새 12개의 "수정 뒤 판정 비율"에 Part B의 몫이 섞인다. 기준 75%는 A1-A5에 대해 등록했다 | 측정을 A1-A5만으로 먼저 한다. Part B는 그 뒤에 같은 새 12개로 따로 잰다 |
| JD2 | `MI_PEND`의 위치 | MI 밖에서 `MI_WITNESS`처럼 따로 둔다. MI 안에서는 probe가 실패했다 (측정) |
| JD3 | 예약 메서드: 예약 지점마다 하나인가, counter마다 하나인가 | 지점마다 하나. 오류가 Kotlin 위치 하나에 맞는다. 메서드가 작아 시간 차이도 작다 (예약 메서드 0.76 s, 측정) |
| JD4 | AI-CODE.md 13절 수정 1의 테스트가 T-haiku 모양을 쓰면 기대값이 바뀐다 | 그 테스트는 R 모양이 아닌 모양(예: `if` 안의 increment)으로 바꿔 옛 이유를 지킨다. R 모양은 새 테스트로 덮는다 |
| JD5 | 실험 파일(scratch의 `partb/gen`)을 저장소에 둘지 | `spike/partb/`로 옮기면 E4-E6이 D1 fixture의 기준이 된다. scratch는 세션과 함께 사라진다 |
| JD6 | W10 완화의 범위 | v1은 예약 지역 변수만 허용한다. 캡처한 `val` 전체로 넓히는 것은 수요가 생길 때 한다 |
| JD7 | 예약 단계가 서로 다른 counter를 쓰는 모듈 | v1은 UNSUPPORTED |
| JD8 | instance 모듈(DESIGN-B의 연산 구간)에도 예약 단계를 허용하는가 | v1은 object 모듈만. 연산 구간이 있는 모듈은 6.3절 규칙 2를 구간 문장으로 다시 써야 한다 |
| JD9 | 이 설계에 대한 critic 검토와 advisor 상담 (CLAUDE.md rule 2) | 이 작업은 advisor를 쓰지 않았다 (지시). L1 모델의 변경은 되돌리기 비싼 결정이다. 메인 세션이 정한다 |

## 부록 A. 실험 재현

파일 (session-local scratch이므로 세션과 함께 사라진다): `spike/partb/`

| 파일 | 내용 |
|---|---|
| `out/ref.vpr` | `kv emit`이 참조 구현(coroutine)과 서명된 sidecar에서 낸 모델 |
| `roots/` | `kv emit`에 쓴 소스 복사본 (참조, T-haiku-1, -2, -4) |
| `gen/header_ref.vpr` | `out/ref.vpr`의 1-105행 (도메인, 필드, macro, 서명된 hole) |
| `gen/gen_kll.py` | T-haiku-2, T-haiku-4의 (a), (c) 모델과 `P_mono` 모델 |
| `gen/gen_h1.py` | T-haiku-1의 (a), (c) 모델 |
| `gen/gen_e56.py` | E5 fence ((a), (b), (c)), E6 steal ((a), (b counted), (c)) |
| `gen/run.sh` | 파일마다 `kv verify`와 같은 Silicon 옵션(`--reportReasonUnknown --numberOfErrorsToReport 0 --assertTimeout 20000 --checkTimeout 20000`)으로 실행 |
| `gen/run_all.sh` | 모델을 모두 다시 만들고 차례로 돌린다. 변이와 깨뜨림(PB6, brk1, brk2, `MI_PEND` 위치 변형)도 만든다 |
| `results.txt` | `run_all.sh`의 출력 (4절 표의 원자료) |

재현 순서:
1. `kv emit --root <roots/ref> --classpath <CP> --kotlinc <ARGS> --out out/ref.vpr specs/reqshield/keylocallock.coroutine.kv` (CP와 ARGS는 `translator/build/kv/aicode-env.json`의 값).
2. `out/ref.vpr`의 1-105행을 `gen/header_ref.vpr`로 저장한다.
3. `sh gen/run_all.sh`. 차례로 도는 데 약 4분이 걸린다 (측정, 벽시계 합).

## 부록 B. (a)가 더하는 신뢰 가정 (Silicon이 확인하지 않음)

1. 3.1절의 전역 논증 J. 각 단계의 의무는 Silicon이 증명한다. 단계들을 엮는 논증은 문서의 것이다. v0의 귀납 논증과 같은 종류다.
2. 6.3절의 R 모양 규칙이 "R과 B 사이의 코드는 스레드 지역이다"를 보장한다는 것. 모듈 상태 접근은 ledger가 확인한다.
3. 예약 increment의 `Long` 무한 (W13, 위치마다 진단).
4. `AtomicLong.incrementAndGet()`의 원자성과 결과의 유일성 (기존 `jdk.AtomicLong` 신뢰 모델).
5. init의 ghost 할당 inhale에 "예약된 토큰 없음"(`!gPend`)이 더해진다. 기존 init 가정("발급된 토큰 없음")과 같은 종류다. 보고서의 init 가정 줄도 함께 고친다.

## 부록 C. 근거

| 출처 | 쓴 곳 |
|---|---|
| `aicode/impl/T-haiku-{1,2,4}/KeyLocalLock.kt`, 12개 구현의 `incrementAndGet` 위치 | 1.1절, 4절, 6.3절 |
| `aicode/round2b/logs/T-haiku-{1,2,4}/tool.report`, `aicode/round2/logs/T-haiku-2/tool.report` | 1.2절 |
| `AI-CODE.md` 4.1절(RULES.md), 10.1절, 11절, 12.1절, 13절, 14절 | 1절, 8절, 10절 |
| `DESIGN-v0.md` 1.1절, 1.4절(W10-W14), 2.5절, 2.12절, 2.14절, 3.5절, 4.3절, 5.2절, 5.7절, 5.8절 | 2절, 3절, 6절 |
| `DESIGN-C.md` 7.3절 | 6.6절 |
| `specs/reqshield/keylocallock.common.kv` (issue 규칙, `gIssued set-once`, hole) | 1절, 3절, 4절 |
| req-shield coroutine `KeyLocalLock.kt` (80행, 155-195행) | 1.1절 |
| `translator/src/main/kotlin/kv/lower/Audit.kt`, `Inputs.kt`, `GhostBinder.kt`, `kv/emit/Emitter.kt`, `kv/kvir/WellFormed.kt`, `kv/kvir/Reserved.kt` | 1.2절, 6절 |
| `translator/scripts/mutants.py` (손 변이의 편집) | 6.10절 |


## 메인 세션 기록 (2026-10-26)

- 실험 파일은 세션 scratch에서 `spike/partb/`로 옮겼다 (`out/`, `roots/` 제외). 문서 안의 경로도 바꿨다.
- 메인 세션이 다시 돌려 확인: `a_h2.vpr` FAILED (tryLock, sweep의 P3a), `a_h2_fixed.vpr` PROVEN, `b_fence.vpr` PROVEN (안 (b)의 거짓 PROVEN), `a_fence.vpr` FAILED.
- JD1: Part B는 AI-CODE.md 14.3의 일반화 측정 뒤에 넣는다 (75% 기준은 A1-A5에 대해 등록했다). L1 모델 변경이므로 구현 전에 advisor 상담을 한다.
