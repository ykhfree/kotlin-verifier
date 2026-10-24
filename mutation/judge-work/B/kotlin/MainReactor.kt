// Judge-B entry point for the reactor variant. Usage: MainReactorKt original|mutant SCENARIO
package com.linecorp.cse.reqshield.reactor

import com.linecorp.cse.reqshield.support.config.LocalLockLimit
import judge.*

fun main(args: Array<String>) {
    val mutant = args[0] == "mutant"
    val id = args[1]
    val map = installHookedMap(KeyLocalLock::class.java)
    val timeout = when (id) { "RACY-03" -> 50L; "SDL-02" -> 50L; "MOP-02" -> 50L; else -> 100L }
    val lock = KeyLocalLock(timeout)
    // Only SDL-02 needs the monitor; the others stop it so that no sweep runs.
    if (id != "SDL-02") KeyLocalLock.stopMonitoring()
    val api = Api(map, "reqshield:lock:k_CREATE", { lock.tryLock("k", LockType.CREATE).block() },
        { t -> lock.unLock("k", LockType.CREATE, t).block()!! }, { c -> LocalLockLimit.maxEntries = c })
    log("reactor ${args[0]} $id, lockTimeoutMillis = $timeout")
    when (id) {
        "AOR-03" -> takeover(api, mutant, 5, timeout)
        "MOP-02" -> permitsGrow(api, mutant, timeout)
        "SDL-02" -> deadEntry(api, mutant, timeout)
        "RACY-01" -> racy01(api, mutant, cap = false)
        "RACY-01-cap" -> racy01(api, mutant, cap = true)
        "RACY-03" -> racy03(api, mutant, timeout)
        "RACY-06" -> racy06(api, mutant)
        else -> error("unknown scenario $id")
    }
    finish()
}
