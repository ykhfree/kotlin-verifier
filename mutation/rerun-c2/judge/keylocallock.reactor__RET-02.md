# keylocallock.reactor/RET-02

- 모듈: keylocallock.reactor (sidecar `keylocallock.reactor.kv`)
- 규칙: RET (operation tryLock)
- 위치: `core-reactor/src/main/kotlin/com/linecorp/cse/reqshield/reactor/KeyLocalLock.kt:198:40-198:44`
- ledger 종류: translated
- 판정: PROVEN
- 실패 의무: 없음
- 판정자가 보는 이유: PROVEN, 모델 바뀜

## Kotlin 원본
```kotlin
 195 |                     // is lost, and the later unLock simply finds nothing to release.
 196 |                     if (LocalLockLimit.rejectsNewEntry(lockMap.mappingCount())) {
 197 |                         acquiredToken.set(nextToken())
 198 |                         return@compute null
 199 |                     }
 200 | 
 201 |                     // New entry: create and acquire
```

## Kotlin 변이
```kotlin
 195 |                     // is lost, and the later unLock simply finds nothing to release.
 196 |                     if (LocalLockLimit.rejectsNewEntry(lockMap.mappingCount())) {
 197 |                         acquiredToken.set(nextToken())
 198 |                         return@compute existing
 199 |                     }
 200 | 
 201 |                     // New entry: create and acquire
```

## 정규화한 Viper diff (원본 → 변이)
```diff
--- original.vpr
+++ mutant.vpr
@@ -144,5 +144,4 @@
 assert 0 <= _v8
 _v2 := _v8
-S . m := S . m [ k := null ]
 }
 else {
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
  - 검토 메모(출처 인용): "ykhfree 2026-10-25: a refused tryLock does not renew the holder's lease"
- ensures P1 / unLock (keylocallock.common.kv:83): `res ==> old(has(S, k)) && old(S.m[k].token) == t && old(tok(t).gIssued) &&
      old(tok(t).gKey) == k && old(tok(t).gBacked) && old(tok(t).gHeld) && !tok(t).gHeld && !has(S, k)`
- ends_only_if P3a / unLock (keylocallock.common.kv:85): `qt == t && res`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: README: only the holder that acquired the lock can release it"
- ensures progress_owner / unLock (keylocallock.common.kv:87): `old(has(S, k)) && old(S.m[k].token) == t ==> res`
- ends_only_if P3a / sweep (keylocallock.common.kv:92): `now > old(tok(qt).gLease)`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: README: the cleanup monitor drops an entry once lockTimeoutMillis has passed"
- invariant P2 (keylocallock.common.kv:97): `forall qr: Ref :: { qr in S.created } qr in S.created ==> 0 <= qr.permits && qr.permits <= 1`
- invariant entry_lease (keylocallock.common.kv:100): `forall qk: Key :: { S.m[qk] } has(S, qk) && S.m[qk].token != NULL ==>
    S.m[qk].expiresAt == tok(S.m[qk].token).gLease`
  - 검토 메모(출처 인용): "ykhfree 2026-10-25: an unreleased lock expires lockTimeoutMillis after it was acquired"
- lemma P3c (keylocallock.common.kv:105): `forall t1: Int, t2: Int :: { tok(t1).gHeld, tok(t2).gHeld }
    tok(t1).gIssued && tok(t2).gIssued && tok(t1).gBacked && tok(t1).gHeld &&
    tok(t2).gBacked && tok(t2).gHeld && tok(t1).gKey == tok(t2).gKey ==> t1 == t2`
- trusted fun com.linecorp.cse.reqshield.support.utils.nowToEpochTime (keylocallock.common.kv:35): `clock`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: support/.../utils/TimeUtils.kt:23: fun nowToEpochTime() = System.currentTimeMillis()"
- trusted fun com.linecorp.cse.reqshield.support.config.LocalLockLimit.rejectsNewEntry (keylocallock.common.kv:37): `returns rej: Bool; ghost limit: Int; ensures rej ==> limit != 0; effects none`
  - 검토 메모(출처 인용): "ykhfree 2026-10-23: support/.../config/LocalLockLimit.kt:86-101: returns false when limit == UNLIMITED_LOCK_ENTRIES (0L) or currentSize < limit"
- ghost record tok (keylocallock.common.kv:45): `gIssued: Bool set-once; gKey: Key frozen; gBacked: Bool frozen; gHeld: Bool ends; gLease: Int frozen`
