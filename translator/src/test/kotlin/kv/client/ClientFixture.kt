package kv.client

import kv.Harness
import kv.frontend.Frontend
import kv.frontend.ResolveOutcome
import java.io.File

/**
 * A small client module in the shape of req-shield's core ReqShield (DESIGN-E 1.2), for the construct fixtures of
 * DESIGN-E 6.4: a lock interface, enums, a data class, a configuration with function-typed values, and an error class.
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
            val lock: Lock,
            val executor: Executor,
            val mode: Mode,
            val maxAttempt: Int,
            var spare: Int,
        )

        object Limits {
            const val MAX_FAILURES = 3
            const val INTERVAL = 50L
        }

        fun decide(createdAt: Long, ttl: Long): Boolean = createdAt + ttl < System.currentTimeMillis()

        fun stamp(): Long = System.currentTimeMillis()
        """.trimIndent() + "\n"

    /** The client class: entry `run(key, callable)` with [body], then [helpers] (members of the class). */
    fun client(
        body: String,
        helpers: String = "",
        result: String = "Data<T>",
    ): String =
        """
        |package fx
        |
        |import org.slf4j.LoggerFactory
        |import java.util.concurrent.Callable
        |import java.util.concurrent.CompletableFuture
        |import java.util.concurrent.RejectedExecutionException
        |
        |private val log = LoggerFactory.getLogger(Client::class.java)
        |
        |class Client<T>(
        |    private val config: Config<T>,
        |) {
        |    fun run(
        |        key: String,
        |        callable: Callable<T?>,
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

    /** A whole fixture sidecar: [SIDECAR_HEAD], the operation of `run` with [items], and [extra] declarations. */
    fun sidecar(
        items: String = "result res",
        extra: String = "",
    ): String = SIDECAR_HEAD + "\noperation run = fun Client.run {\n${items.trimIndent().prependIndent("  ")}\n}\n" + extra

    /** The test classpath: the stdlib and slf4j (from the req-shield classpath). */
    val classpath: List<File> by lazy { listOf(Harness.stdlib) + kv.ReqShield.classpath.filter { "slf4j" in it.name } }

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
