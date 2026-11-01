# Coverage of SOURCES.md for the local lock (KeyLocalLock)

Each row covers one block of SOURCES.md that states a requirement on the local lock.
Clause IDs refer to DRAFT.kv. "Not encoded" rows give the reason.
Blocks with no row are listed at the end.

| Anchor | Required behavior | Clause IDs, or "not encoded" with reason |
|---|---|---|
| [CLAUDE.md#project-overview/1] | One request per cache key runs at a time. | `backed_exclusive` (lemma). Scope: backed tokens only (QUESTIONS Q7). |
| [CLAUDE.md#key-components/2] | `tryLock` returns a token. `unLock` releases only when the token matches. | `issue`, `progress_new_key`, `tryLock_null_when_owned`, `tryLock_null_only_when_owned`, `unLock_result`, `unLock_refused_keeps_entry`. |
| [CLAUDE.md#key-components/5] | Spring aspects namespace cache and lock keys as `{cacheName}::{key}`. | Not encoded: key derivation runs in the Spring adapter, before the lock. `Key` is opaque. |
| [CLAUDE.md#reqshieldconfiguration-parameters/4] | `lockTimeoutMillis` is the lock timeout. Default is 3000 ms. | `timeout` input in `grant_lease` and `issue`. Meaning of the value: QUESTIONS Q2. Default value: not encoded (configuration). |
| [CLAUDE.md#work-modes/2] | ONLY_CREATE_CACHE: the lock applies only when an entry is created. | Not encoded: ReqShield decides when to call the lock. `KeyLocalLock` behaves the same. |
| [CLAUDE.md#work-modes/3] | ONLY_UPDATE_CACHE: the lock applies only when an entry is refreshed. | Not encoded: same reason as the row above. |
| [README.md#local-lock-map-size/1] | One map entry per (cache key, lock type). The map is shared by the JVM. | `state m` (shared module state) and the opaque `Key` type. Not encoded: the claim that a cache key uses up to two entries. It follows from `Key`, and the language has no entry count. |
| [README.md#local-lock-map-size/2] | `unLock` drops the entry. The monitor drops an unreleased entry after `lockTimeoutMillis`, with up to 1 s lag. | `unLock_release_removes`, `sweep_only_drops`, `sweep_keeps_live`, `progress_sweep_drops_expired`, `sweep_drops_expired`, `grant_lease`. Not encoded: the 1 s interval and the lag (timing; LANGUAGE.md section 6). Not encoded: map size tracks locked keys, and the 50 ms example (the text gives no size bound). |
| [README.md#local-lock-map-size/3] | The map is uncapped by default. `0` means uncapped. | The uncapped case follows from the trusted contract `rej ==> limit != 0` (skeleton line 38). No new clause. Not encoded: the config key and the sizing advice (configuration and advice). |
| [README.md#local-lock-map-size/4] | A full map gives a new key an unbacked permit, and the request runs without an entry. Keys with an entry skip the cap. | `ub_capped_new_key`, `capped_new_key_not_inserted`, `progress_new_key`, `progress_expired_takeover`, `tryLock_null_when_owned`. Not encoded: "no added latency" and the cache write (outside the lock model). |
| [README.md#local-lock-map-size/5] | The cap is soft. Concurrent callers can push the map slightly past the cap. | Not encoded: the text gives no number for "slightly" (QUESTIONS Q6). `rej` is an input that the step does not recheck. |
| [README.md#local-lock-map-size/6] | The first refusal and every 1000th refusal after it are logged at WARN. | Not encoded: logging is outside the model (LANGUAGE.md section 6). |
| [README.md#local-lock-map-size/7] | A cap value that is not a non-negative number is logged and ignored. A programmatic `-1` fails fast. | Not encoded: configuration parsing is outside the model. |
| [README.md#global-lock-guidance/2] | Every lock acquisition carries an ownership token. Only the acquirer can release it. | `issue` (a token per grant) and `unLock_result`. The block sits under the heading "Global lock guidance", but its sentence starts with "Every lock acquisition". The draft applies it to the local lock too. |
| [README.md#cache-key-layout/2] | Lock keys carry the prefix `reqshield:lock:`. | Not encoded: key derivation runs before the lock. `Key` is opaque. |
| [README.md#cache-key-layout/3] | Core uses the key the caller passes, unchanged. | Not encoded as a clause. The model takes the caller's key as input `k`, with no transformation. |
| [KeyLock.kt(core)#KeyLock] | The token is opaque. Only the acquirer can release it. A holder whose lock was taken over cannot release the new owner's lock. | `Token` type, `issue`, `unLock_result`, `unLock_refused_keeps_owner`, `held_backed_carried`. |
| [KeyLock.kt(core)#tryLock] | Acquires the lock for the key. Returns a token, or null when another holder owns the lock. | `tryLock_null_when_owned`, `tryLock_null_only_when_owned`, `tryLock_null_keeps_owner`, `progress_new_key`, `progress_expired_takeover`, `grant_carried`, `grant_lease`, `tryLock_takeover`. |
| [KeyLock.kt(core)#unLock] | Releases only when the token matches. Returns false when the lock is not held or the token does not match. | `unLock_result`, `unLock_release_removes`, `unLock_refused_keeps_entry`, `unLock_refused_keeps_owner`, `unLock_release`. |
| [KeyLock.kt(reactor)#tryLock] | Reactive variant. Emits a token, or completes empty when another holder owns the lock. | Not encoded: the skeleton binds the core `KeyLocalLock` only (QUESTIONS Q12). |
| [KeyLock.kt(reactor)#unLock] | Reactive variant. Emits false when the lock is not held or the token does not match. | Not encoded: same reason as the row above. |
| [KeyLock.kt(coroutine)#tryLock] | Suspend variant. Returns a token, or null when another holder owns the lock. | Not encoded: same reason as the reactive rows (QUESTIONS Q12). |
| [KeyLock.kt(coroutine)#unLock] | Suspend variant. Returns false when not held or on token mismatch. | Not encoded: same reason as the reactive rows (QUESTIONS Q12). |
| [KeyLock.kt(coroutine)#lockKeyOf] | Lock keys are prefixed so a lock entry never collides with a cache entry. | Not encoded: key derivation is outside the model. Cache entries are not in the model. |

Summary: 24 rows. 11 rows have clause IDs. 13 rows are not encoded.

## Blocks with no row

These blocks state no requirement on `KeyLocalLock`:

- README.md: top link, summary, requirements, usage, release and snapshot versions, Redis integration tests, Reactor 3.8 Kotlin, WebFlux null handling, eviction semantics, thread pools, WebFlux and coroutine adapters, contributing, license, contact.
- README.md: global lock blocks `global-lock-guidance/1`, `/3` and `/4`. They cover `GlobalLockSupport` and the Redis implementation (`isLocalLock = false`).
- README.md: `waiting-for-another-request-lock-not-acquired/1` to `/3`. They describe ReqShield polling after a lost lock, not the lock itself.
- README.md: `cache-key-layout/1`. It covers cache entries, not lock entries.
- CLAUDE.md: build, test, code quality, example, module, toolchain, compatibility and test-coverage blocks.
- CLAUDE.md: `key-components/1`, `/3`, `/4`, `/6`. They cover ReqShield, configuration, data and `GlobalLockSupport`.
- CLAUDE.md: `reqshieldconfiguration-parameters/1`, `/2`, `/3`, `/5`, `/6`, and `/7`. They cover lock selection, global functions, executors, TTL refresh, polling and work mode.
- CLAUDE.md: `work-modes/1`. It covers the default work mode.
