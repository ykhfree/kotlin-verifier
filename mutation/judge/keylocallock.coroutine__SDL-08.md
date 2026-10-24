# keylocallock.coroutine/SDL-08

- 모듈: keylocallock.coroutine (sidecar `keylocallock.coroutine.kv`)
- 규칙: SDL (operation tryLock)
- 위치: `core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine/KeyLocalLock.kt:182:21-182:51`
- ledger 종류: translated
- 판정: PROVEN
- 실패 의무: 없음
- 판정자가 보는 이유: PROVEN, 모델 바뀜

## Kotlin 원본
```kotlin
 179 |                 // would stall it on a holder that does not exist. Only this key's collapsing
 180 |                 // is lost, and the later unLock simply finds nothing to release.
 181 |                 if (LocalLockLimit.rejectsNewEntry(lockMap.mappingCount())) {
 182 |                     acquiredToken.set(nextToken())
 183 |                     return@compute null
 184 |                 }
 185 | 
```

## Kotlin 변이
```kotlin
 179 |                 // would stall it on a holder that does not exist. Only this key's collapsing
 180 |                 // is lost, and the later unLock simply finds nothing to release.
 181 |                 if (LocalLockLimit.rejectsNewEntry(lockMap.mappingCount())) {
 182 |                     
 183 |                     return@compute null
 184 |                 }
 185 | 
```

## 정규화한 Viper diff (원본 → 변이)
```diff
--- original.vpr
+++ mutant.vpr
@@ -123,28 +123,24 @@
 var _v7 : Int
 if ( rej ) {
+S . m := S . m [ k := null ]
+}
+else {
 S . seq := S . seq + 1
 var _v8 : Int := S . seq
 assert 0 <= _v8
-_v2 := _v8
-S . m := S . m [ k := null ]
-}
-else {
-S . seq := S . seq + 1
-var _v9 : Int := S . seq
-assert 0 <= _v9
-var _v10 : Int := _v9
-var _v11 : Ref := new ( permits , isHeld , token , expiresAt )
-S . created := S . created union Set ( _v11 )
-_v11 . permits := 1
-_v11 . isHeld := false
-_v11 . token := _v10
-_v11 . expiresAt := now + timeout
-var _v12 : Bool := _v11 . permits > 0
-if ( _v12 ) {
-_v11 . permits := _v11 . permits - 1
-}
-_v11 . isHeld := true
-_v2 := _v10
-S . m := S . m [ k := _v11 ]
+var _v9 : Int := _v8
+var _v10 : Ref := new ( permits , isHeld , token , expiresAt )
+S . created := S . created union Set ( _v10 )
+_v10 . permits := 1
+_v10 . isHeld := false
+_v10 . token := _v9
+_v10 . expiresAt := now + timeout
+var _v11 : Bool := _v10 . permits > 0
+if ( _v11 ) {
+_v10 . permits := _v10 . permits - 1
+}
+_v10 . isHeld := true
+_v2 := _v9
+S . m := S . m [ k := _v10 ]
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
- ensures progress_live / tryLock (keylocallock.common.kv:70): `old(has(S, k)) && now <= old(S.m[k].expiresAt) ==> res == NULL`
- ensures P1 / unLock (keylocallock.common.kv:76): `res ==> old(has(S, k)) && old(S.m[k].token) == t && old(tok(t).gIssued) &&
      old(tok(t).gKey) == k && old(tok(t).gBacked) && old(tok(t).gHeld) && !tok(t).gHeld && !has(S, k)`
- ends_only_if P3a / unLock (keylocallock.common.kv:78): `qt == t && res`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: README: only the holder that acquired the lock can release it"
- ensures progress_owner / unLock (keylocallock.common.kv:80): `old(has(S, k)) && old(S.m[k].token) == t ==> res`
- ends_only_if P3a / sweep (keylocallock.common.kv:85): `now > old(tok(qt).gLease)`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: README: the cleanup monitor drops an entry once lockTimeoutMillis has passed"
- invariant P2 (keylocallock.common.kv:90): `forall qr: Ref :: { qr in S.created } qr in S.created ==> 0 <= qr.permits && qr.permits <= 1`
- lemma P3c (keylocallock.common.kv:91): `forall t1: Int, t2: Int :: { tok(t1).gHeld, tok(t2).gHeld }
    tok(t1).gIssued && tok(t2).gIssued && tok(t1).gBacked && tok(t1).gHeld &&
    tok(t2).gBacked && tok(t2).gHeld && tok(t1).gKey == tok(t2).gKey ==> t1 == t2`
- trusted fun com.linecorp.cse.reqshield.support.utils.nowToEpochTime (keylocallock.common.kv:35): `clock`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: support/.../utils/TimeUtils.kt:23: fun nowToEpochTime() = System.currentTimeMillis()"
- trusted fun com.linecorp.cse.reqshield.support.config.LocalLockLimit.rejectsNewEntry (keylocallock.common.kv:37): `returns rej: Bool; ghost limit: Int; ensures rej ==> limit != 0; effects none`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: support/.../config/LocalLockLimit.kt:86-101: returns false when limit == UNLIMITED_LOCK_ENTRIES (0L) or currentSize < limit"
- ghost record tok (keylocallock.common.kv:45): `gIssued: Bool set-once; gKey: Key frozen; gBacked: Bool frozen; gHeld: Bool ends; gLease: Int frozen`
