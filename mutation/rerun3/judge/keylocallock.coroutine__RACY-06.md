# keylocallock.coroutine/RACY-06

- 모듈: keylocallock.coroutine (sidecar `keylocallock.coroutine.kv`)
- 규칙: RACY (operation unLock)
- 위치: `core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLocalLock.kt:213:17-213:86`
- ledger 종류: translated
- 판정: FAILED
- 실패 의무: unLock: progress_owner
- 판정자가 보는 이유: FAILED, 예측 7.2

## Kotlin 원본
```kotlin
 206 |         // Release inside compute() so that it is atomic with respect to acquisition and cleanup
 207 |         // of the same key: the token check, the semaphore release and the removal cannot be
 208 |         // interleaved with a reacquisition.
 209 |         lockMap.compute(completeKey) { _, existing ->
 210 |             if (existing == null) return@compute null
 211 | 
 212 |             // Only the current owner may release: a stale token belongs to an expired holder.
 213 |             if (existing.token == token && existing.isHeld.compareAndSet(true, false)) {
 214 |                 existing.semaphore.release()
 215 |                 released.set(true)
 216 |                 // Drop the entry immediately rather than leaving it for the monitor. No reference
```

## Kotlin 변이
```kotlin
 206 |         // Release inside compute() so that it is atomic with respect to acquisition and cleanup
 207 |         // of the same key: the token check, the semaphore release and the removal cannot be
 208 |         // interleaved with a reacquisition.
 209 |         val racy_6 = lockMap.containsKey(completeKey)
 210 |         lockMap.compute(completeKey) { _, existing ->
 211 |             if (existing == null) return@compute null
 212 | 
 213 |             // Only the current owner may release: a stale token belongs to an expired holder.
 214 |             if (racy_6 && existing.token == token && existing.isHeld.compareAndSet(true, false)) {
 215 |                 existing.semaphore.release()
 216 |                 released.set(true)
 217 |                 // Drop the entry immediately rather than leaving it for the monitor. No reference
```

## 정규화한 Viper diff (원본 → 변이)
```diff
--- original.vpr
+++ mutant.vpr
@@ -185,16 +185,17 @@
 var pre_tok : Int := _v1 != null ? _v1 . token : NULL
 var _v2 : Bool := false
+var _v3 : Bool
 if ( _v1 == null ) {
 S . m := S . m [ k := null ]
 }
 else {
-var _v3 : Bool := false
-if ( _v1 . token == t ) {
-_v3 := _v1 . isHeld == true
-if ( _v3 ) {
+var _v4 : Bool := false
+if ( _v3 && _v1 . token == t ) {
+_v4 := _v1 . isHeld == true
+if ( _v4 ) {
 _v1 . isHeld := false
 }
 }
-if ( _v3 ) {
+if ( _v4 ) {
 _v1 . permits := _v1 . permits + 1
 _v2 := true
```

## sidecar 성질 (원문과 출처 인용)
- issue res / tryLock (keylocallock.common.kv:60): `gKey = k; gBacked = carried(S, k, res); gLease = now + timeout`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: README Global lock guidance: every lock acquisition carries an ownership token"
- ensures P1a / tryLock (keylocallock.common.kv:62): `res != NULL ==> !old(tok(res).gIssued) && tok(res).gIssued &&
      tok(res).gKey == k && tok(res).gHeld && tok(res).gLease == now + timeout`
- ensures P1a_entry / tryLock (keylocallock.common.kv:64): `res != NULL && tok(res).gBacked ==> has(S, k) && S.m[k].token == res`
- ends_only_if P3a / tryLock (keylocallock.common.kv:65): `now > old(tok(qt).gLease)`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: README: only an expired lock is taken over (a slow holder whose lock already expired)"
- unbacked_only_if P3b / tryLock (keylocallock.common.kv:67): `!old(has(S, k)) && rej && limit != 0`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: README Local lock map size: at the cap a new key is handed a permit that no map entry backs"
- ensures progress_free / tryLock (keylocallock.common.kv:69): `!old(has(S, k)) && !rej ==> res != NULL && tok(res).gBacked`
- ensures progress_live / tryLock (keylocallock.common.kv:70): `old(has(S, k)) && old(S.m[k].isHeld) && now <= old(S.m[k].expiresAt) ==> res == NULL`
- ensures cap_unbacked / tryLock (keylocallock.common.kv:71): `!old(has(S, k)) && rej ==> res != NULL && !tok(res).gBacked && !has(S, k)`
- ensures expired_takeover / tryLock (keylocallock.common.kv:72): `old(has(S, k)) && now > old(S.m[k].expiresAt) ==> res != NULL && tok(res).gBacked`
- ensures refusal_keeps_entry / tryLock (keylocallock.common.kv:74): `res == NULL ==> old(has(S, k)) && has(S, k) &&
      S.m[k].token == old(S.m[k].token) && S.m[k].expiresAt == old(S.m[k].expiresAt)`
- ensures P1 / unLock (keylocallock.common.kv:81): `res ==> old(has(S, k)) && old(S.m[k].token) == t && old(tok(t).gIssued) &&
      old(tok(t).gKey) == k && old(tok(t).gBacked) && old(tok(t).gHeld) && !tok(t).gHeld && !has(S, k)`
- ends_only_if P3a / unLock (keylocallock.common.kv:83): `qt == t && res`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: README: only the holder that acquired the lock can release it"
- ensures progress_owner / unLock (keylocallock.common.kv:85): `old(has(S, k)) && old(S.m[k].token) == t ==> res`
- ends_only_if P3a / sweep (keylocallock.common.kv:90): `now > old(tok(qt).gLease)`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: README: the cleanup monitor drops an entry once lockTimeoutMillis has passed"
- invariant P2 (keylocallock.common.kv:95): `forall qr: Ref :: { qr in S.created } qr in S.created ==> 0 <= qr.permits && qr.permits <= 1`
- invariant entry_lease (keylocallock.common.kv:98): `forall qk: Key :: { S.m[qk] } has(S, qk) && S.m[qk].token != NULL ==>
    S.m[qk].expiresAt == tok(S.m[qk].token).gLease`
- lemma P3c (keylocallock.common.kv:100): `forall t1: Int, t2: Int :: { tok(t1).gHeld, tok(t2).gHeld }
    tok(t1).gIssued && tok(t2).gIssued && tok(t1).gBacked && tok(t1).gHeld &&
    tok(t2).gBacked && tok(t2).gHeld && tok(t1).gKey == tok(t2).gKey ==> t1 == t2`
- trusted fun com.linecorp.cse.reqshield.support.utils.nowToEpochTime (keylocallock.common.kv:35): `clock`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: support/.../utils/TimeUtils.kt:23: fun nowToEpochTime() = System.currentTimeMillis()"
- trusted fun com.linecorp.cse.reqshield.support.config.LocalLockLimit.rejectsNewEntry (keylocallock.common.kv:37): `returns rej: Bool; ghost limit: Int; ensures rej ==> limit != 0; effects none`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: support/.../config/LocalLockLimit.kt:86-101: returns false when limit == UNLIMITED_LOCK_ENTRIES (0L) or currentSize < limit"
- ghost record tok (keylocallock.common.kv:45): `gIssued: Bool set-once; gKey: Key frozen; gBacked: Bool frozen; gHeld: Bool ends; gLease: Int frozen`
