All scenarios use one local lock with `lockTimeoutMillis = 10` and one lock key `k`, unless they say otherwise.
"Acquired at time 0" means: `tryLock(k)` ran at clock reading 0 on an empty map with no cap and returned token `t1`.
Times are clock readings in milliseconds.
