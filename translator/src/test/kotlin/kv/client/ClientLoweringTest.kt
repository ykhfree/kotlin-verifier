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
