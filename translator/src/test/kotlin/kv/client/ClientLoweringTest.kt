package kv.client

import kv.frontend.ResolveOutcome
import kv.kvir.Module
import kv.kvir.Status
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import kv.kvir.printModule
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The position column of a printed KVIR line: ` @line:col`, maybe with a fake kind. */
private val POSITION = Regex("""\s+@\d+:\d+( fake=\S+)?$""")

/**
 * DESIGN-E 6.10 E2 acceptance: for each construct of DESIGN-E 6.4 one supported fixture, which lowers completely and
 * well formed (W1-W22) with the KVIR lines below, and one rejected fixture, which is UNSUPPORTED with its reason.
 */
class ClientLoweringTest {
    @TempDir
    lateinit var dir: File

    private fun lower(
        snippet: Snippet,
        name: String,
    ): Module {
        val outcome: ResolveOutcome = ClientFixture.resolve(File(dir, name).apply { mkdirs() }, ClientFixture.client(snippet.body, snippet.helpers))
        assertNull(outcome.compileMessages, outcome.compileMessages)
        assertEquals(emptyList(), outcome.errors, name)
        return outcome.module ?: fail("no module for $name")
    }

    /** KVIR lines each supported fixture must hold (trimmed), by construct row. */
    private val expected =
        mapOf(
            1 to listOf("if cur__17 != null", "return cur__17", "else"),
            2 to listOf("var token__17: Token? := null", "if !(config_mode__17 == Mode.ONLY_UPDATE)"),
            3 to listOf("finally", "return cached__21", "done__19 := true", "if !done__19"),
            4 to listOf("inline read -> cur__17: Data?", "catch Throwable: REE, RTE, CHK, INTR, ERR, ClientError(GET)", "throw ClientError(GET)"),
            5 to listOf("call Config.set(k, data__18) throws RTE, CHK", "catch Exception: REE, RTE, CHK, INTR, ClientError(SET)", "finally"),
            6 to listOf("catch RejectedExecutionException: REE", "catch Exception: REE, RTE, CHK, INTR", "holds (token__17 != null) ==> Lease(k, Kind.UPDATE, token__17)"),
            7 to listOf("while attempts__17 < config_maxAttempt__19 [measure config_maxAttempt__19 - attempts__17]", "failures__18 := failures__18 + 1 [ASSUME A26_21]", "if failures__18 >= 3", "break"),
            8 to listOf("call a__17: Data? := Config.get(k) throws RTE, CHK", "call b__18: Data? := Config.get(k) throws RTE, CHK", "inline fetch -> c__19: Data?"),
            9 to listOf("spawn task_18(k, data__17) by jdk.CompletableFuture.runAsync throws REE", "task task_18 = lambda in run via spawn, spawned by run"),
            10 to listOf("spawn task_19(k, data__17) by jdk.CompletableFuture.runAsync throws REE", "task task_19 = lambda in run, spawned by run"),
            11 to listOf("call jdk.Thread.sleep() throws INTR", "catch InterruptedException: INTR", "throw ClientError(GET)"),
            12 to listOf("inline build -> out_17_16: Data", "new new_23_9: Data {value := arg_17_22}"),
            13 to listOf("let onlyCreate__17: Bool := config_mode__17 == Mode.ONLY_CREATE", "let kind__18: Kind := Kind.UPDATE"),
            14 to listOf("throw ClientError(SUP)", "if (null != null) && (Kind.CREATE != null)"),
            15 to listOf("var arg_18_22: Kind? := null", "if (token__17 != null) && (arg_18_22 != null)"),
            16 to listOf("call r_18_29: Int := Callable.call() throws RTE, CHK, INTR", "return new_18_16"),
            17 to listOf("catch Exception: REE, RTE, CHK, INTR, ClientError(GET)", "if caught is {REE, RTE, ClientError(GET)}", "rethrow"),
        )

    @Test
    fun `every supported construct lowers completely and well formed`() {
        for (c in ClientConstructs.all) {
            val m = lower(c.supported, "s${c.row}")
            val text = printModule(m)
            assertFalse(m.incomplete, "row ${c.row}:\n$text")
            assertEquals(emptyList(), checkWellFormed(m), "row ${c.row}")
            assertTrue((m.entries.map { it.status } + m.tasks.map { it.status }).all { it == Status.Supported }, "row ${c.row}")
            val lines = text.lines().map { it.replace(POSITION, "").trim() }
            expected.getValue(c.row).forEach { assertTrue(it in lines, "row ${c.row} lacks '$it':\n$text") }
        }
    }

    @Test
    fun `logging and Thread interrupt leave no statement, and the whenComplete callback goes to the ledger as no-effect`() {
        val logging = printModule(lower(ClientConstructs.all.single { it.row == 16 }.supported, "log"))
        assertFalse("info" in logging.substringBefore("ledger:"), logging)
        val interrupt = printModule(lower(ClientConstructs.all.single { it.row == 11 }.supported, "interrupt"))
        assertFalse("interrupt" in interrupt.substringBefore("ledger:").lines().filter { it.trimStart().startsWith("call") }.joinToString(), interrupt)
        val callback = printModule(lower(ClientConstructs.all.single { it.row == 10 }.supported, "callback"))
        assertTrue(callback.lines().any { "no-effect whenComplete callback: logs only" in it }, callback)
        assertTrue(callback.lines().any { "no-effect fun report: called by a no-effect callback" in it }, callback)
    }

    @Test
    fun `a suspend call, a suspend value and a trusted param passed to a helper are cancellation points (DESIGN-E5 2 row 12)`() {
        val body =
            """
            val token = config.slock.tryLock(key, Kind.CREATE)
            if (token != null) config.slock.unLock(key, Kind.CREATE, token)
            val cur = config.sget(key)
            if (cur != null) return cur
            return Data(value = produce(supplier), ttl = 0L)
            """
        val helper = "private suspend fun produce(f: suspend () -> T?): T? = f()"
        val o = ClientFixture.resolve(File(dir, "suspend").apply { mkdirs() }, ClientFixture.client(body, helper, suspend = true), ClientFixture.sidecar(head = ClientFixture.SUSPEND_SIDECAR_HEAD))
        assertNull(o.compileMessages, o.compileMessages)
        assertEquals(emptyList(), o.errors)
        val m = o.module ?: fail("no module")
        val text = printModule(m)
        assertFalse(m.incomplete, text)
        assertEquals(emptyList(), checkWellFormed(m))
        for (call in listOf(
            "SuspendLock.tryLock(k, Kind.CREATE) throws RTE, CANCEL cx=true; creates Lease(",
            "SuspendLock.unLock(k, Kind.CREATE, token__18) throws RTE, CANCEL cx=true; consumes Lease(",
            "cur__20: Data? := Config.sget(k) throws RTE, CHK, CANCEL cx=true",
            ": Int := supplier() throws RTE, CHK, INTR, CANCEL cx=true",
        )) {
            assertTrue(call in text, "lacks '$call':\n$text")
        }
    }

    /** The coroutine module of [body] and [helpers] (DESIGN-E5 2: the suspend client with the kotlinx models), well formed. */
    private fun coroutine(
        body: String,
        helpers: String = "",
        name: String,
    ): Module {
        val o = ClientFixture.resolve(File(dir, name).apply { mkdirs() }, ClientFixture.client(body, helpers, suspend = true), ClientFixture.sidecar(head = ClientFixture.COROUTINE_SIDECAR_HEAD))
        assertNull(o.compileMessages, o.compileMessages)
        assertEquals(emptyList(), o.errors, name)
        val m = o.module ?: fail("no module for $name")
        assertEquals(emptyList(), checkWellFormed(m), name)
        return m
    }

    private fun lines(m: Module) = printModule(m).lines().map { it.replace(POSITION, "").trim() }

    private fun unsupported(m: Module) = (m.entries.map { it.status } + m.tasks.map { it.status }).filterIsInstance<Status.Unsupported>().flatMap { it.diagnostics }.map { it.message }

    /** A release of the CREATE lock in a launched block; [start] is the launch's argument list before the block. */
    private fun launched(start: String) =
        """
        val token = config.slock.tryLock(key, Kind.CREATE)
        if (token != null) {
            config.scope.launch$start {
                config.slock.unLock(key, Kind.CREATE, token)
            }
        }
        return Data(value = supplier(), ttl = 0L)
        """

    @Test
    fun `launch is a spawn of its block, atomic only with start = ATOMIC (DESIGN-E5 2 rows 9 and 10)`() {
        for ((start, atomic) in listOf("(start = CoroutineStart.ATOMIC)" to true, "" to false, "(start = CoroutineStart.DEFAULT)" to false)) {
            val m = coroutine(launched(start), name = "launch$atomic${start.length}")
            assertFalse(m.incomplete, printModule(m))
            val spawn = lines(m).single { it.startsWith("spawn ") }
            assertEquals("spawn task_20(k, token__18) by kotlinx.launch throws REE, RTE atomic=$atomic", spawn, start)
            // The block is a task: a coroutine of its own, so its suspend call is cancellable.
            assertEquals("task_20", m.tasks.single().id)
            assertTrue("call SuspendLock.unLock(k, Kind.CREATE, token__18) throws RTE, CANCEL cx=true; consumes Lease(k, Kind.CREATE, token__18)" in lines(m), printModule(m))
        }
    }

    @Test
    fun `launch with a lazy or undispatched start, a context or a used Job is unsupported (DESIGN-E5 2_4 item 5)`() {
        val cases =
            listOf(
                launched("(start = CoroutineStart.LAZY)") to "LAZY and UNDISPATCHED are out of scope",
                launched("(start = CoroutineStart.UNDISPATCHED)") to "LAZY and UNDISPATCHED are out of scope",
                launched("(Dispatchers.IO)") to "a context argument is not translated",
                // The Job is no value of the model, so its type rejects it first.
                launched("").replace("config.scope.launch", "val job = config.scope.launch") to "value of type kotlinx.coroutines.Job",
            )
        cases.forEachIndexed { i, (body, reason) ->
            val m = coroutine(body, name = "nolaunch$i")
            assertTrue(m.incomplete, printModule(m))
            assertTrue(unsupported(m).any { reason in it }, "case $i: no '$reason' in ${unsupported(m)}")
        }
    }

    @Test
    fun `an entry without a result that returns the Job of launch gets the launch reason, not the runAsync one`() {
        // The one way a Job reaches the spawn with a value sink: a return from an entry whose sidecar names no result,
        // so no type check of the entry's out rejects the Job first (a local, a helper's out or an argument does).
        val body = "return config.scope.launch(start = CoroutineStart.ATOMIC) { log.info(\"refresh\") }"
        val o =
            ClientFixture.resolve(
                File(dir, "jobentry").apply { mkdirs() },
                ClientFixture.client(body, result = "kotlinx.coroutines.Job", suspend = true),
                ClientFixture.sidecar(items = "", head = ClientFixture.COROUTINE_SIDECAR_HEAD),
            )
        assertNull(o.compileMessages, o.compileMessages)
        assertEquals(emptyList(), o.errors)
        val m = o.module ?: fail("no module")
        assertTrue(m.incomplete, printModule(m))
        val launch = unsupported(m).filter { "value of launch" in it }
        assertEquals(1, launch.size, unsupported(m).toString())
        assertTrue("the Job launch returns is not used (DESIGN-E5 2.4 item 5)" in launch.single(), launch.single())
    }

    @Test
    fun `withContext(NonCancellable) runs its block in place with cx = false, and another context is unsupported (DESIGN-E5 2 row 8)`() {
        val body =
            """
            val token = config.slock.tryLock(key, Kind.CREATE)
            if (token != null) {
                val released = withContext(NonCancellable) {
                    delay(Limits.INTERVAL)
                    config.slock.unLock(key, Kind.CREATE, token)
                }
                if (released) log.info("released")
            }
            currentCoroutineContext().ensureActive()
            delay(Limits.INTERVAL)
            return Data(value = supplier(), ttl = 0L)
            """
        val m = coroutine(body, name = "nc")
        assertFalse(m.incomplete, printModule(m))
        val ls = lines(m)
        // Inside the block: no cancellation point raises CANCEL; the block's value is the release's.
        assertTrue("call kotlinx.delay() throws CANCEL cx=false" in ls, printModule(m))
        assertTrue("call released__20: Bool := SuspendLock.unLock(k, Kind.CREATE, token__18) throws RTE, CANCEL cx=false; consumes Lease(k, Kind.CREATE, token__18)" in ls, printModule(m))
        // After it, the context is cancellable again: ensureActive and delay are cancellation points (rows 6, 7).
        assertEquals(listOf("call kotlinx.ensureActive() throws CANCEL cx=true", "call kotlinx.delay() throws CANCEL cx=true"), ls.filter { it.startsWith("call kotlinx.") && "cx=true" in it })
        val io = coroutine(body.replace("NonCancellable", "Dispatchers.IO"), name = "io")
        assertTrue(io.incomplete)
        assertTrue(unsupported(io).single().let { "withContext(Dispatchers.IO)" in it && "withContext(NonCancellable) only" in it }, unsupported(io).toString())
    }

    @Test
    fun `x also { } runs its lambda after x, and its value is x (DESIGN-E5 2 row 11)`() {
        val m =
            coroutine(
                """
                var failures = 1
                val cur = config.sget(key).also { failures = 0 }
                if (cur != null && failures == 0) return cur
                return Data(value = supplier(), ttl = 0L)
                """,
                name = "also",
            )
        assertFalse(m.incomplete, printModule(m))
        val ls = lines(m)
        val call = ls.indexOf("call r_19_19: Data? := Config.sget(k) throws RTE, CHK, CANCEL cx=true")
        assertTrue(call >= 0, printModule(m))
        assertEquals(listOf("failures__18 := 0", "let cur__19: Data? := r_19_19"), ls.subList(call + 1, call + 3))
    }

    @Test
    fun `a trusted param captured into a launched block is a cancellation point of the task (DESIGN-E5 JE9)`() {
        val m =
            coroutine(
                """
                val token = config.slock.tryLock(key, Kind.CREATE)
                if (token != null) {
                    config.scope.launch(start = CoroutineStart.ATOMIC) {
                        try {
                            config.set.invoke(key, Data(value = supplier(), ttl = 0L), 0L)
                        } finally {
                            withContext(NonCancellable) { config.slock.unLock(key, Kind.CREATE, token) }
                        }
                    }
                }
                return Data(value = supplier(), ttl = 0L)
                """,
                name = "captured",
            )
        assertFalse(m.incomplete, printModule(m))
        val task = m.tasks.single()
        // The supplier is no value the task captures: only the token and the key are its parameters.
        assertEquals(listOf("k", "token__18"), task.params.map { it.name })
        val body = printModule(m).substringAfter("task ${task.id} ").lines().map { it.replace(POSITION, "").trim() }
        assertTrue(body.any { Regex("""^call r_\d+_\d+: Int := supplier\(\) throws RTE, CHK, INTR, CANCEL cx=true$""").matches(it) }, printModule(m))
        assertTrue("call SuspendLock.unLock(k, Kind.CREATE, token__18) throws RTE, CANCEL cx=false; consumes Lease(k, Kind.CREATE, token__18)" in body, printModule(m))
    }

    @Test
    fun `every rejected construct is unsupported with its reason`() {
        for (c in ClientConstructs.all) {
            val m = lower(c.rejected, "r${c.row}")
            assertTrue(m.incomplete, "row ${c.row} is not rejected:\n${printModule(m)}")
            val messages = (m.entries.map { it.status } + m.tasks.map { it.status }).filterIsInstance<Status.Unsupported>().flatMap { it.diagnostics }.map { it.message }
            assertTrue(messages.any { c.reason in it }, "row ${c.row}: no message with '${c.reason}' in $messages")
            // An unsupported unit still accounts for every FIR node (W14).
            assertEquals(emptyList(), checkWellFormed(m), "row ${c.row}")
        }
    }
}
