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

    /** The model of `getOrPut` on a ConcurrentMap (DESIGN-B.md 2.5). */
    const val GET_OR_PUT = "kotlin.collections.getOrPut"

    /** The model of a `fun` operation whose whole body is one `Mono.fromCallable { ... }` call (DESIGN-v0.md 8.3). */
    const val FROM_CALLABLE = "reactor.Mono.fromCallable"

    val entries: List<Entry> =
        listOf(
            Entry("jdk.ConcurrentHashMap.compute", CHM, "compute", listOf("?", "java/util/function/BiFunction"), Role.ACTION),
            // DESIGN-B.md 2.3, Corretto 21.0.10 src.zip ConcurrentHashMap.java and ConcurrentMap.java. Each is one
            // atomic action on a state map, with the call's key argument as the action key.
            // computeIfAbsent: 1670 "method invocation is performed atomically.  The supplied"; 1672 "if the key is
            //   absent, else not at all.  Some attempted update"; 1669 "and enters it into this map unless {@code
            //   null}.  The entire"; 1689 "in which case the mapping is left unestablished".
            Entry("jdk.ConcurrentHashMap.computeIfAbsent", CHM, "computeIfAbsent", listOf("?", "java/util/function/Function"), Role.ACTION),
            // putIfAbsent: ConcurrentMap.java:127-128 "If the specified key is not already associated" "with a value,
            //   associates it with the given value."; 136 "except that the action is performed atomically."
            Entry("jdk.ConcurrentHashMap.putIfAbsent", CHM, "putIfAbsent", listOf("?", "?"), Role.ACTION),
            // put: 993 "Maps the specified key to the specified value in this table." Also `m[k] = v`: stdlib 2.0.21
            //   Maps.kt:266-267 "public inline operator fun <K, V> MutableMap<K, V>.set(key: K, value: V): Unit {" "put(key, value)"
            Entry("jdk.ConcurrentHashMap.put", CHM, "put", listOf("?", "?"), Role.ACTION),
            // remove: 1093 "Removes the key (and its corresponding value) from this map."
            Entry("jdk.ConcurrentHashMap.remove", CHM, "remove", listOf("?"), Role.ACTION),
            // getOrPut on a ConcurrentMap (DESIGN-B.md 2.5): a racy read, the default function outside any action, then
            // the putIfAbsent action. stdlib 2.0.21 MapsJVM.kt:72-73 "return this.get(key)"
            // "?: defaultValue().let { default -> this.putIfAbsent(key, default) ?: default }". A top-level function:
            // calls match it by MapCall, not by this entry.
            Entry(GET_OR_PUT, "kotlin/collections", "getOrPut", listOf("?", "kotlin/Function0"), Role.ACTION),
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
            //   runs    each run of the lambda is one call of the operation: at most once per subscription (on its
            //           first request), and once per direct block() or call() of the Mono (MonoCallable.java:51-73)
            //   result  the lambda's last expression, which reads only a cell get or a constant; null -> empty Mono (NULL)
            //   effects none beyond the lambda
            //   source  "reactor-core 3.7.19 sources jar, reactor/core/publisher/Mono.java:512-513: 'If the Callable
            //           resolves to {@code null}, the resulting Mono completes empty.' MonoCallable.java:48 (cold: each
            //           subscriber gets its own subscription): 'actual.onSubscribe(new MonoCallableSubscription<>(actual,
            //           this.callable));' MonoCallable.java:129 and 137 (the callable runs once, on the first
            //           request): 'if (this.requestedOnce == 1 || !REQUESTED_ONCE.compareAndSet(this, 0 , 1)) {' (its
            //           body returns), then 'value = this.callable.call();'"
            //           The Gradle cache holds only the 3.7.19 binary jar. The sources jar came from Maven Central (SHA-1
            //           860cffe5cb59cf6f79290d37329c664035e07b0c); javap of the cached binary shows the same request().
            Entry(FROM_CALLABLE, "reactor/core/publisher/Mono", "fromCallable", listOf("java/util/concurrent/Callable"), Role.OPERATION_BODY),
        ) +
            listOf("trace", "debug", "info", "warn", "error", "isTraceEnabled").map {
                Entry("slf4j.Logger", "org/slf4j/Logger", it, null, Role.NO_EFFECT)
            }

    /**
     * Kotlin classes an opaque sidecar type may bind (DESIGN-B.md 2.2): Viper `==` on the type stands for the class's
     * `equals`, with the source that justifies it.
     */
    val keyClasses: Map<String, String> =
        mapOf(
            // jdk.reflect.Method key: JDK 21 Method.java:361-364 "Two {@code Methods} are the same if" "they were declared
            // by the same class and have the same name" "and formal parameter types and return type."; ConcurrentHashMap
            // compares keys with equals (ConcurrentHashMap.java:928 "such that {@code key.equals(k)}").
            "java.lang.reflect.Method" to "JDK 21 Method.java:361-364",
        )

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
