package kv.model

/**
 * Names of the built-in trusted models (DESIGN-v0.md 4.3) and the facts about them that M0-M2 need.
 * The model semantics (signatures, effects, contracts) come with action lowering in M3.
 */
object TrustedModels {
    val builtIn =
        setOf(
            "jdk.ConcurrentHashMap.compute",
            "jdk.ConcurrentHashMap.mappingCount",
            "jdk.ConcurrentHashMap.racyRead",
            "jdk.Semaphore",
            "jdk.AtomicBoolean",
            "jdk.AtomicLong",
            "jdk.AtomicCell",
            "jdk.System.currentTimeMillis",
            "slf4j.Logger",
        )

    /** The model of a module `state ... : Counter`. */
    const val COUNTER = "jdk.AtomicLong"

    /** Operations the models treat as unbounded Long arithmetic: each needs an ASSUMPTION diagnostic (W13). */
    val unboundedLongOps = setOf("jdk.AtomicLong.incrementAndGet")
}
