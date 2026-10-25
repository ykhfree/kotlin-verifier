# keylocallock.reactor/RACY-04

- 모듈: keylocallock.reactor (sidecar `keylocallock.reactor.kv`)
- 규칙: RACY (operation tryLock)
- 위치: `core-reactor/src/main/kotlin/com/linecorp/cse/reqshield/reactor/KeyLocalLock.kt:196:25-196:79`
- ledger 종류: translated
- 판정: FAILED
- 실패 의무: tryLock: cap_unbacked
- 판정자가 보는 이유: FAILED, 예측 7.2

## Kotlin 원본
```kotlin
 167 | 
 168 |             // Use compute() for atomic lock acquisition.
 169 |             // This ensures mutual exclusion with cleanup - they cannot race on the same key.
 170 |             lockMap.compute(completeKey) { _, existing ->
 171 |                 if (existing != null) {
 172 |                     // Force-release expired locks to allow reacquisition.
 173 |                     // Use CAS to prevent race condition with concurrent unLock().
 174 |                     // Without CAS, if unLock() executes between isHeld.get() and release(),
 175 |                     // both threads would call release(), causing over-release (permits > 1).
 176 |                     if (now > existing.expiresAt && existing.isHeld.compareAndSet(true, false)) {
 177 |                         existing.semaphore.release()
 178 |                         // The previous holder lost ownership: its token must no longer release the lock.
 179 |                         existing.token = null
 180 |                     }
 181 | 
 182 |                     // Existing entry: try to acquire semaphore
 183 |                     if (existing.semaphore.tryAcquire()) {
 184 |                         val token = nextToken()
 185 |                         existing.isHeld.set(true)
 186 |                         existing.expiresAt = now + lockTimeoutMillis
 187 |                         existing.token = token
 188 |                         acquiredToken.set(token)
 189 |                     }
 190 |                     existing
 191 |                 } else {
 192 |                     // At the cap, hand out a permit that no map entry backs instead of refusing.
 193 |                     // The caller then takes its normal path and writes the cache; making it wait
 194 |                     // would stall it on a holder that does not exist. Only this key's collapsing
 195 |                     // is lost, and the later unLock simply finds nothing to release.
 196 |                     if (LocalLockLimit.rejectsNewEntry(lockMap.mappingCount())) {
 197 |                         acquiredToken.set(nextToken())
 198 |                         return@compute null
 199 |                     }
```

## Kotlin 변이
```kotlin
 167 | 
 168 |             // Use compute() for atomic lock acquisition.
 169 |             // This ensures mutual exclusion with cleanup - they cannot race on the same key.
 170 |             val racy_4 = lockMap.containsKey(completeKey)
 171 |             lockMap.compute(completeKey) { _, existing ->
 172 |                 if (existing != null) {
 173 |                     // Force-release expired locks to allow reacquisition.
 174 |                     // Use CAS to prevent race condition with concurrent unLock().
 175 |                     // Without CAS, if unLock() executes between isHeld.get() and release(),
 176 |                     // both threads would call release(), causing over-release (permits > 1).
 177 |                     if (now > existing.expiresAt && existing.isHeld.compareAndSet(true, false)) {
 178 |                         existing.semaphore.release()
 179 |                         // The previous holder lost ownership: its token must no longer release the lock.
 180 |                         existing.token = null
 181 |                     }
 182 | 
 183 |                     // Existing entry: try to acquire semaphore
 184 |                     if (existing.semaphore.tryAcquire()) {
 185 |                         val token = nextToken()
 186 |                         existing.isHeld.set(true)
 187 |                         existing.expiresAt = now + lockTimeoutMillis
 188 |                         existing.token = token
 189 |                         acquiredToken.set(token)
 190 |                     }
 191 |                     existing
 192 |                 } else {
 193 |                     // At the cap, hand out a permit that no map entry backs instead of refusing.
 194 |                     // The caller then takes its normal path and writes the cache; making it wait
 195 |                     // would stall it on a holder that does not exist. Only this key's collapsing
 196 |                     // is lost, and the later unLock simply finds nothing to release.
 197 |                     if (racy_4 && LocalLockLimit.rejectsNewEntry(lockMap.mappingCount())) {
 198 |                         acquiredToken.set(nextToken())
 199 |                         return@compute null
 200 |                     }
```

## 정규화한 Viper diff (원본 → 변이)
```diff
--- original.vpr
+++ mutant.vpr
@@ -100,59 +100,64 @@
 var pre_tok : Int := _v1 != null ? _v1 . token : NULL
 var _v2 : Int := NULL
+var _v3 : Bool
 if ( _v1 != null ) {
-var _v3 : Bool := false
+var _v4 : Bool := false
 if ( now > _v1 . expiresAt ) {
-_v3 := _v1 . isHeld == true
-if ( _v3 ) {
+_v4 := _v1 . isHeld == true
+if ( _v4 ) {
 _v1 . isHeld := false
 }
 }
-if ( _v3 ) {
+if ( _v4 ) {
 _v1 . permits := _v1 . permits + 1
 _v1 . token := NULL
 }
-var _v4 : Bool := _v1 . permits > 0
-if ( _v4 ) {
+var _v5 : Bool := _v1 . permits > 0
+if ( _v5 ) {
 _v1 . permits := _v1 . permits - 1
 }
-if ( _v4 ) {
+if ( _v5 ) {
 S . seq := S . seq + 1
-var _v5 : Int := S . seq
-assert 0 <= _v5
-var _v6 : Int := _v5
+var _v6 : Int := S . seq
+assert 0 <= _v6
+var _v7 : Int := _v6
 _v1 . isHeld := true
 _v1 . expiresAt := now + timeout
-_v1 . token := _v6
-_v2 := _v6
-}
-}
-else {
-var _v7 : Int
-if ( rej ) {
+_v1 . token := _v7
+_v2 := _v7
+}
+}
+else {
+var _v8 : Bool := false
+if ( _v3 ) {
+var _v9 : Int
+_v8 := rej
+}
+if ( _v8 ) {
 S . seq := S . seq + 1
-var _v8 : Int := S . seq
-assert 0 <= _v8
-_v2 := _v8
+var _v10 : Int := S . seq
+assert 0 <= _v10
+_v2 := _v10
 S . m := S . m [ k := null ]
 }
 else {
 S . seq := S . seq + 1
-var _v9 : Int := S . seq
-assert 0 <= _v9
-var _v10 : Int := _v9
-var _v11 : Ref := new ( permits , isHeld , token , expiresAt )
-S . created := S . created union Set ( _v11 )
-_v11 . permits := 1
-_v11 . isHeld := false
-_v11 . token := NULL
-_v11 . expiresAt := now + timeout
-var _v12 : Bool := _v11 . permits > 0
-if ( _v12 ) {
-_v11 . permits := _v11 . permits - 1
-}
-_v11 . isHeld := true
-_v11 . token := _v10
-_v2 := _v10
-S . m := S . m [ k := _v11 ]
+var _v11 : Int := S . seq
+assert 0 <= _v11
+var _v12 : Int := _v11
+var _v13 : Ref := new ( permits , isHeld , token , expiresAt )
+S . created := S . created union Set ( _v13 )
+_v13 . permits := 1
+_v13 . isHeld := false
+_v13 . token := NULL
+_v13 . expiresAt := now + timeout
+var _v14 : Bool := _v13 . permits > 0
+if ( _v14 ) {
+_v13 . permits := _v13 . permits - 1
+}
+_v13 . isHeld := true
+_v13 . token := _v12
+_v2 := _v12
+S . m := S . m [ k := _v13 ]
 }
 }
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
- invariant entry_lease (keylocallock.common.kv:98): `forall qk: Key :: { S.m[qk] } has(S, qk) ==> S.m[qk].expiresAt == tok(S.m[qk].token).gLease`
- lemma P3c (keylocallock.common.kv:99): `forall t1: Int, t2: Int :: { tok(t1).gHeld, tok(t2).gHeld }
    tok(t1).gIssued && tok(t2).gIssued && tok(t1).gBacked && tok(t1).gHeld &&
    tok(t2).gBacked && tok(t2).gHeld && tok(t1).gKey == tok(t2).gKey ==> t1 == t2`
- trusted fun com.linecorp.cse.reqshield.support.utils.nowToEpochTime (keylocallock.common.kv:35): `clock`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: support/.../utils/TimeUtils.kt:23: fun nowToEpochTime() = System.currentTimeMillis()"
- trusted fun com.linecorp.cse.reqshield.support.config.LocalLockLimit.rejectsNewEntry (keylocallock.common.kv:37): `returns rej: Bool; ghost limit: Int; ensures rej ==> limit != 0; effects none`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: support/.../config/LocalLockLimit.kt:86-101: returns false when limit == UNLIMITED_LOCK_ENTRIES (0L) or currentSize < limit"
- ghost record tok (keylocallock.common.kv:45): `gIssued: Bool set-once; gKey: Key frozen; gBacked: Bool frozen; gHeld: Bool ends; gLease: Int frozen`
