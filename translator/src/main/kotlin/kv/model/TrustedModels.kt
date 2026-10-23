package kv.model

/** What a matched library callable means to the translator (DESIGN-v0.md 4.3). */
enum class Role {
    /** ConcurrentHashMap.compute: one atomic action of the module. */
    ACTION,

    /** An arbitrary value with no contract inside an action; outside every action, a racy read. */
    NONDET,

    /** Outside every action: an arbitrary value of the result type, recorded in the ledger. Inside: unsupported. */
    RACY_READ,

    /** Builds a flattened field, the counter or a cell. Argument 0 is the initial value (none: 0). */
    CTOR,

    /** An operation on a flattened field, the module counter or a cell. */
    OP,

    /** An arbitrary Long; no monotonicity. */
    CLOCK,

    /** No effect on modeled state; the arguments must be pure. */
    NO_EFFECT,

    /** reactor Mono.fromCallable: its lambda is the body of a `fun` operation (DESIGN-v0.md 8.3). */
    OPERATION_BODY,
}

/** The value a model operation returns. */
enum class Returns { UNIT, BOOL, LONG, ARG }

/**
 * One library callable of a built-in model. [owner] is the class id (`java/util/concurrent/Semaphore`), [name] the
 * function or property name (`<init>` for a constructor), [params] the parameter class ids; `?` matches a type
 * parameter and a null list matches every overload. Java parameters have no names (`p0`), so matching uses positions.
 */
data class Entry(
    val model: String,
    val owner: String,
    val name: String,
    val params: List<String>?,
    val role: Role,
    val returns: Returns = Returns.UNIT,
    /** The model treats the result as unbounded Long arithmetic: each call site needs an ASSUMPTION (W13). */
    val unbounded: Boolean = false,
) {
    /** The KVIR operation name, e.g. `jdk.Semaphore.tryAcquire`. */
    val op: String get() = "$model.$name"
}

/**
 * The built-in trusted models (DESIGN-v0.md 4.3), as a Kotlin table (decision 4: no model grammar in v0).
 * Every entry is a call that does not throw; v0 does not model exception paths inside an action (2.3).
 */
object TrustedModels {
    private const val CHM = "java/util/concurrent/ConcurrentHashMap"
    private const val SEMAPHORE = "java/util/concurrent/Semaphore"
    private const val ATOMIC_BOOLEAN = "java/util/concurrent/atomic/AtomicBoolean"
    private const val ATOMIC_LONG = "java/util/concurrent/atomic/AtomicLong"
    private const val ATOMIC_REFERENCE = "java/util/concurrent/atomic/AtomicReference"
    private const val BOOLEAN = "kotlin/Boolean"
    private const val INT = "kotlin/Int"
    private const val LONG = "kotlin/Long"

    /** The model of a module `state ... : Counter`. */
    const val COUNTER = "jdk.AtomicLong"

    /** The model of non-escaping local AtomicReference and AtomicBoolean values (DESIGN-v0.md 2.5). */
    const val CELL = "jdk.AtomicCell"

    /** The model of a `fun` operation whose whole body is one `Mono.fromCallable { ... }` call (DESIGN-v0.md 8.3). */
    const val FROM_CALLABLE = "reactor.Mono.fromCallable"

    val entries: List<Entry> =
        listOf(
            Entry("jdk.ConcurrentHashMap.compute", CHM, "compute", listOf("?", "java/util/function/BiFunction"), Role.ACTION),
            Entry("jdk.ConcurrentHashMap.mappingCount", CHM, "mappingCount", emptyList(), Role.NONDET, Returns.LONG),
            // Racy reads; `keys` and `size` are Kotlin properties of the Java map.
            Entry("jdk.ConcurrentHashMap.racyRead", CHM, "get", null, Role.RACY_READ),
            Entry("jdk.ConcurrentHashMap.racyRead", CHM, "containsKey", null, Role.RACY_READ),
            Entry("jdk.ConcurrentHashMap.racyRead", CHM, "keys", null, Role.RACY_READ),
            Entry("jdk.ConcurrentHashMap.racyRead", CHM, "keySet", null, Role.RACY_READ),
            Entry("jdk.ConcurrentHashMap.racyRead", CHM, "size", null, Role.RACY_READ),
            Entry("jdk.ConcurrentHashMap.racyRead", CHM, "isEmpty", null, Role.RACY_READ),
            // Semaphore: permits := arg0; tryAcquire = permits > 0 ? { permits -= 1; true } : false; release: permits += 1.
            Entry("jdk.Semaphore", SEMAPHORE, "<init>", listOf(INT), Role.CTOR),
            Entry("jdk.Semaphore", SEMAPHORE, "tryAcquire", emptyList(), Role.OP, Returns.BOOL),
            Entry("jdk.Semaphore", SEMAPHORE, "release", emptyList(), Role.OP),
            // AtomicBoolean as a flattened field: v := arg0; compareAndSet = v == arg0 ? { v := arg1; true } : false.
            Entry("jdk.AtomicBoolean", ATOMIC_BOOLEAN, "<init>", listOf(BOOLEAN), Role.CTOR),
            Entry("jdk.AtomicBoolean", ATOMIC_BOOLEAN, "compareAndSet", listOf(BOOLEAN, BOOLEAN), Role.OP, Returns.BOOL),
            Entry("jdk.AtomicBoolean", ATOMIC_BOOLEAN, "set", listOf(BOOLEAN), Role.OP),
            Entry("jdk.AtomicBoolean", ATOMIC_BOOLEAN, "get", emptyList(), Role.OP, Returns.BOOL),
            // AtomicLong as the module counter: v := arg0 (or 0); incrementAndGet = { v := v + 1; v }.
            Entry(COUNTER, ATOMIC_LONG, "<init>", emptyList(), Role.CTOR),
            Entry(COUNTER, ATOMIC_LONG, "<init>", listOf(LONG), Role.CTOR),
            Entry(COUNTER, ATOMIC_LONG, "incrementAndGet", emptyList(), Role.OP, Returns.LONG, unbounded = true),
            Entry(COUNTER, ATOMIC_LONG, "get", emptyList(), Role.OP, Returns.LONG),
            // Cells: cell := arg0; set(v): cell := v; get() = cell.
            Entry(CELL, ATOMIC_REFERENCE, "<init>", listOf("?"), Role.CTOR),
            Entry(CELL, ATOMIC_REFERENCE, "set", listOf("?"), Role.OP),
            Entry(CELL, ATOMIC_REFERENCE, "get", emptyList(), Role.OP, Returns.ARG),
            Entry(CELL, ATOMIC_BOOLEAN, "<init>", listOf(BOOLEAN), Role.CTOR),
            Entry(CELL, ATOMIC_BOOLEAN, "set", listOf(BOOLEAN), Role.OP),
            Entry(CELL, ATOMIC_BOOLEAN, "get", emptyList(), Role.OP, Returns.BOOL),
            Entry("jdk.System.currentTimeMillis", "java/lang/System", "currentTimeMillis", emptyList(), Role.CLOCK, Returns.LONG),
            // model reactor.Mono.fromCallable
            //   match   reactor.core.publisher.Mono.fromCallable(java.util.concurrent.Callable)
            //   role    operation body: the whole body of a `fun` operation is this one call, as an expression body or
            //           a single return, and its lambda holds the action; anything else is UNSUPPORTED
            //   runs    each run of the lambda is one call of the operation; no subscription, no call
            //   result  the lambda's last expression, which reads only a cell get or a constant; null -> empty Mono (NULL)
            //   effects none beyond the lambda
            //   source  "reactor-core 3.7.19 sources jar, reactor/core/publisher/Mono.java:512-513: 'If the Callable
            //           resolves to {@code null}, the resulting Mono completes empty.' MonoCallable.java:48 (cold: each
            //           subscriber gets its own subscription): 'actual.onSubscribe(new MonoCallableSubscription<>(actual,
            //           this.callable));' MonoCallable.java:129 and 137 (the callable runs once, on the first
            //           request): 'if (this.requestedOnce == 1 || !REQUESTED_ONCE.compareAndSet(this, 0 , 1)) {' (its
            //           body returns), then 'value = this.callable.call();'"
            Entry(FROM_CALLABLE, "reactor/core/publisher/Mono", "fromCallable", listOf("java/util/concurrent/Callable"), Role.OPERATION_BODY),
        ) +
            listOf("trace", "debug", "info", "warn", "error", "isTraceEnabled").map {
                Entry("slf4j.Logger", "org/slf4j/Logger", it, null, Role.NO_EFFECT)
            }

    /** Names a sidecar may `use`. */
    val builtIn: Set<String> = entries.map { it.model }.toSet()

    /** Operations the models treat as unbounded Long arithmetic: each needs an ASSUMPTION diagnostic (W13). */
    val unboundedLongOps: Set<String> = entries.filter { it.unbounded }.map { it.op }.toSet()

    /** The entries of the callable `owner.name(params)`; several when models share it (AtomicBoolean: field or cell). */
    fun lookup(
        owner: String,
        name: String,
        params: List<String?>,
    ): List<Entry> =
        entries.filter { e ->
            e.owner == owner && e.name == name &&
                (e.params == null || e.params.size == params.size && e.params.zip(params).all { (want, got) -> want == "?" || want == got })
        }
}
