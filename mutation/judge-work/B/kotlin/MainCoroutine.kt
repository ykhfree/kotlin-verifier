// Judge-B entry point for the coroutine variant. Usage: MainCoroutineKt original|mutant SCENARIO
package com.linecorp.cse.reqshield.kotlin.coroutine

import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import judge.*
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val mutant = args[0] == "mutant"
    val id = args[1]
    val map = installHookedMap(KeyLocalLock::class.java)
    val timeout = when (id) { "RACY-03" -> 50L; "RET-11-clock" -> 300L; "RET-11" -> 50L; "SDL-06" -> 50L; else -> 100L }
    val lock = KeyLocalLock(timeout)
    // Only the RET-11 scenarios need the monitor; the others stop it so that no sweep runs.
    if (!id.startsWith("RET-11")) KeyLocalLock.stopMonitoring()
    val api = Api(map, "reqshield:lock:k_CREATE", { runBlocking { lock.tryLock("k", LockType.CREATE) } },
        { t -> runBlocking { lock.unLock("k", LockType.CREATE, t) } }, { c -> LocalLockLimit.maxEntries = c })
    log("coroutine ${args[0]} $id, lockTimeoutMillis = $timeout")
    when (id) {
        "RACY-01" -> racy01(api, mutant, cap = false)
        "RACY-01-cap" -> racy01(api, mutant, cap = true)
        "RACY-03" -> racy03(api, mutant, timeout)
        "RACY-06" -> racy06(api, mutant)
        "RET-11" -> sweepKeeps(api, mutant)
        "RET-11-clock" -> sweepStaleClock(api, mutant, timeout)
        "SDL-06" -> takeoverNoRenew(api, mutant, timeout)
        else -> error("unknown scenario $id")
    }
    finish()
}
