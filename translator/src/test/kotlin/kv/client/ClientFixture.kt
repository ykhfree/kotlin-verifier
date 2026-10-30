package kv.client

import kv.Harness
import kv.frontend.Frontend
import kv.frontend.ResolveOutcome
import java.io.File

/**
 * A small client module in the shape of req-shield's core ReqShield (DESIGN-E 1.2), for the construct fixtures of
 * DESIGN-E 6.4: a lock interface, enums, a data class, a configuration with function-typed values and a coroutine
 * scope, and an error class.
 * [client] writes a class `fx.Client` with entry `run` and the given helpers; [sidecar] is a client sidecar for it.
 */
object ClientFixture {
    /** The library the client module uses. Stands for KeyLock.kt, ReqShieldConfiguration.kt and the support jar. */
    val BASE =
        """
        package fx

        import java.util.concurrent.Executor

        interface Lock {
            fun tryLock(key: String, kind: Kind): String?

            fun unLock(key: String, kind: Kind, token: String): Boolean
        }

        interface SuspendLock {
            suspend fun tryLock(key: String, kind: Kind): String?

            suspend fun unLock(key: String, kind: Kind, token: String): Boolean
        }

        enum class Kind { CREATE, UPDATE }

        enum class Mode { BOTH, ONLY_CREATE, ONLY_UPDATE }

        enum class Code { GET, SET, SUP }

        class ClientError(val code: Code, cause: Throwable? = null) : RuntimeException(cause)

        data class Data<T>(var value: T?, val createdAt: Long, val ttl: Long) {
            constructor(value: T? = null, ttl: Long) : this(value, 0L, ttl)
        }

        class Config<T>(
            val get: (String) -> Data<T>?,
            val set: (String, Data<T>, Long) -> Boolean,
            val sget: suspend (String) -> Data<T>?,
            val lock: Lock,
            val slock: SuspendLock,
            val executor: Executor,
            val mode: Mode,
            val maxAttempt: Int,
            var spare: Int,
            val scope: kotlinx.coroutines.CoroutineScope,
        )

        object Limits {
            const val MAX_FAILURES = 3
            const val INTERVAL = 50L
        }

        fun decide(createdAt: Long, ttl: Long): Boolean = createdAt + ttl < System.currentTimeMillis()

        fun stamp(): Long = System.currentTimeMillis()
        """.trimIndent() + "\n"

    /** The kotlinx.coroutines names a coroutine fixture uses (DESIGN-E5 2), imported on one line to keep line numbers. */
    private const val COROUTINE_IMPORTS =
        "; import kotlinx.coroutines.CoroutineStart; import kotlinx.coroutines.Dispatchers; import kotlinx.coroutines.NonCancellable" +
            "; import kotlinx.coroutines.currentCoroutineContext; import kotlinx.coroutines.delay; import kotlinx.coroutines.ensureActive" +
            "; import kotlinx.coroutines.launch; import kotlinx.coroutines.withContext"

    /**
     * The client class: entry `run(key, callable)` with [body], then [helpers] (members of the class). With [suspend],
     * a coroutine client (DESIGN-E5): `suspend fun run(key, callable, supplier)`, `supplier` a suspend function value,
     * with the kotlinx.coroutines imports on the line of the first import.
     */
    fun client(
        body: String,
        helpers: String = "",
        result: String = "Data<T>",
        suspend: Boolean = false,
    ): String =
        """
        |package fx
        |
        |import org.slf4j.LoggerFactory${if (suspend) COROUTINE_IMPORTS else ""}
        |import java.util.concurrent.Callable
        |import java.util.concurrent.CompletableFuture
        |import java.util.concurrent.RejectedExecutionException
        |
        |private val log = LoggerFactory.getLogger(Client::class.java)
        |
        |class Client<T>(
        |    private val config: Config<T>,
        |) {
        |    ${if (suspend) "suspend " else ""}fun run(
        |        key: String,
        |        callable: Callable<T?>,${if (suspend) "\n|        supplier: suspend () -> T?," else ""}
        |    ): $result {
        |${body.trimIndent().prependIndent("        ")}
        |    }
        |${helpers.trimIndent().prependIndent("    ")}
        |}
        |
        """.trimMargin()

    /** The declarations every fixture sidecar shares (DESIGN-E 6.2), without the operation. */
    val SIDECAR_HEAD =
        """
        module fx.Client client
        source "src"
        type Key = opaque
        type Token = opaque
        enum Kind = Kind
        enum Mode = Mode
        class Data = Data { value: Int = value }
        obligation Lease(Key, Kind, Token)

        trusted fun Lock.tryLock(key: Key, kind: Kind) returns t: Token? creates Lease(key, kind, t) if t != null throws RTE
        trusted fun Lock.unLock(key: Key, kind: Kind, token: Token) returns released: Bool consumes Lease(key, kind, token) throws RTE
        trusted value Config.get(key: Key) returns d: Data? throws RTE, CHK
        trusted value Config.set(key: Key, data: Data, kotlin.Long) returns ok: Bool throws RTE, CHK
        trusted fun java.util.concurrent.Callable.call() returns v: Int throws RTE, CHK, INTR
        trusted fun fx.decide(kotlin.Long, kotlin.Long) returns b: Bool
        use jdk.CompletableFuture.runAsync
        use jdk.CompletableFuture.whenComplete
        use jdk.Thread.sleep
        use jdk.Thread.interrupt
        use kotlin.runCatching
        use slf4j.Logger
        """.trimIndent() + "\n"

    /**
     * [SIDECAR_HEAD] for a suspend client (DESIGN-E5): the lock is SuspendLock, whose calls a cancellation may end, so
     * they throw CANCEL too; so may the suspend value `Config.sget` and the supplier, the trusted param `run.supplier`.
     */
    val SUSPEND_SIDECAR_HEAD: String =
        listOf("tryLock(key: Key, kind: Kind)", "unLock(key: Key, kind: Kind, token: Token)").fold(SIDECAR_HEAD) { head, fn ->
            val line = head.lines().single { it.startsWith("trusted fun Lock.$fn") }
            head.replace(line, line.replace("Lock.$fn", "SuspendLock.$fn") + ", CANCEL")
        } +
            """
            trusted value Config.sget(key: Key) returns d: Data? throws RTE, CHK, CANCEL
            trusted param Client.run.supplier() returns v: Int throws RTE, CHK, INTR, CANCEL
            """.trimIndent() + "\n"

    /** [SUSPEND_SIDECAR_HEAD] with the coroutine models of DESIGN-E5 2 (rows 6-9): launch, withContext, ensureActive, delay. */
    val COROUTINE_SIDECAR_HEAD: String =
        SUSPEND_SIDECAR_HEAD +
            """
            use kotlinx.launch
            use kotlinx.withContext
            use kotlinx.ensureActive
            use kotlinx.delay
            """.trimIndent() + "\n"

    /** A whole fixture sidecar: [head], the operation of `run` with [items], and [extra] declarations. */
    fun sidecar(
        items: String = "result res",
        extra: String = "",
        head: String = SIDECAR_HEAD,
    ): String = head + "\noperation run = fun Client.run {\n${items.trimIndent().prependIndent("  ")}\n}\n" + extra

    /** The test classpath: the stdlib, slf4j and kotlinx-coroutines (from the req-shield classpath). */
    val classpath: List<File> by lazy { listOf(Harness.stdlib) + kv.ReqShield.classpath.filter { "slf4j" in it.name || "kotlinx-coroutines-core" in it.name } }

    /** Writes the fixture under [dir] (`src/fx/Base.kt`, `src/fx/Client.kt`, `client.kv`) and resolves it as a draft. */
    fun resolve(
        dir: File,
        client: String,
        sidecar: String = sidecar(),
    ): ResolveOutcome {
        File(dir, "src/fx").mkdirs()
        File(dir, "src/fx/Base.kt").writeText(BASE)
        File(dir, "src/fx/Client.kt").writeText(client)
        val kv = File(dir, "client.kv").apply { writeText(sidecar) }
        return Frontend.resolve(kv, dir, classpath, draft = true)
    }
}
