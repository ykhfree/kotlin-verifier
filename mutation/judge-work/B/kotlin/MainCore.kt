// Judge-B entry point for the core variant. Usage: MainCoreKt original|mutant SCENARIO
package com.linecorp.cse.reqshield

import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import judge.*
import java.util.concurrent.ScheduledExecutorService

fun main(args: Array<String>) {
    val mutant = args[0] == "mutant"
    val id = args[1]
    val map = installHookedMap(KeyLocalLock::class.java)
    val timeout = if (id == "RACY-03") 50L else 100L
    val lock = KeyLocalLock(timeout)
    // Stop the shared monitor, so that no sweep runs during the scenario.
    val f = KeyLocalLock::class.java.getDeclaredField("sharedScheduler").apply { isAccessible = true }
    (f.get(null) as ScheduledExecutorService).shutdownNow()
    val api = Api(map, "reqshield:lock:k_CREATE", { lock.tryLock("k", LockType.CREATE) },
        { t -> lock.unLock("k", LockType.CREATE, t) }, { c -> LocalLockLimit.maxEntries = c })
    log("core ${args[0]} $id, lockTimeoutMillis = $timeout")
    when (id) {
        "AOR-03" -> takeover(api, mutant, 5, timeout)
        "COR-01" -> takeover(api, mutant, 0, timeout)
        "RET-06" -> keepAfterUnlock(api, mutant)
        "RET-08" -> unlockEmpty(api, mutant)
        "SDL-12" -> firstLockNull(api, mutant, timeout)
        "RACY-01" -> racy01(api, mutant, cap = false)
        "RACY-01-cap" -> racy01(api, mutant, cap = true)
        "RACY-03" -> racy03(api, mutant, timeout)
        "RACY-06" -> racy06(api, mutant)
        else -> error("unknown scenario $id")
    }
    finish()
}
