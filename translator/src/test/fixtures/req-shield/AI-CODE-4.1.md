<!-- Excerpt of AI-CODE.md section 4.1 (the generation prompt and RULES.md) for AicodeScriptTest and scripts/aicode.py. -->

### 4.1 본 실험: 모양 틀이 있는 구현 12개

모델 3종(haiku, sonnet, opus) × 4회. 같은 모델의 반복은 서로 상관되므로, 실질 표본은 12보다 작다.

생성 프롬프트 (원문, `<SANDBOX>`만 바꾼다):

```text
You are implementing one Kotlin class for a team. Work only inside the directory <SANDBOX>. Do not read, list or
search any file outside it. Do not use the web, memory tools, or other agents.

Read SPEC.md (the team's specification, copied from the project documentation), KeyLock.kt (the interface you
implement), HELPERS.md (functions and constants you may call) and RULES.md (rules every implementation must follow).
Write KeyLocalLock.kt in this directory: package com.linecorp.cse.reqshield.kotlin.coroutine,
class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock.

Check that it compiles with ./compile.sh. It only compiles; it does not test or verify. You may call it at most
10 times. When you are done, report: whether the final file compiles, and every design decision you made that the
specification did not settle.
```

`RULES.md` 원문:

```text
The team checks this class with a formal verifier that supports a subset of Kotlin. Follow these rules exactly.

1. Shared state lives in the class's companion object:
   private val lockMap = ConcurrentHashMap<String, LockInfo>()
   private val tokenSequence = AtomicLong(0)
   A new ownership token is "local-${tokenSequence.incrementAndGet()}".
2. One lock entry is:
   private class LockInfo(
       val semaphore: Semaphore,
       @Volatile var expiresAt: Long,
       val isHeld: AtomicBoolean = AtomicBoolean(false),
       @Volatile var token: String? = null,
   )
3. tryLock and unLock each read or change lockMap only inside exactly one call
   lockMap.compute(key) { _, existing -> ... }.
   Values that must leave the lambda go through an AtomicReference or AtomicBoolean declared before the call.
4. tryLock reads the clock into a local `val now = nowToEpochTime()` before its compute call.
5. A monitor in the companion object removes expired entries. For each key it reads `val now = System.currentTimeMillis()`
   and calls lockMap.compute(key) once.
6. Do not use other ConcurrentHashMap methods to change lockMap, and do not use synchronized, locks, or
   try/catch inside a compute lambda.
```

### 4.2 범위 실험: 자유 구현 3개 (필수)
