package kv.client

import kv.frontend.ResolveOutcome
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

/**
 * DESIGN-C 10.16 B2c: `kv resolve` reports `free-unused` at a `free NAME = this.PATH` line when no supported unit of
 * the operation declares NAME (no translated code reads PATH) and a sidecar text uses NAME. Without the check, NAME
 * reaches Silicon as an undeclared identifier and the module is ERROR with no pointer to the free line.
 */
class FreeUnusedTest {
    @TempDir
    lateinit var dir: File

    /** A val Int of the class: its read is an unnamed instance value of the same Kotlin type as config.maxAttempt. */
    private val limit = "private val limit: Int = 2"

    /** The c1 shape: config.maxAttempt is read only in an argument the model drops (Config.set's Long). */
    private val dropped =
        """
        val data = Data(value = callable.call(), ttl = 0L)
        val big = limit > 1
        config.set.invoke(key, data, config.maxAttempt.toLong())
        return data
        """

    private val freeLine = "free n = this.config.maxAttempt"

    private val ensuresN =
        """
        ensures N: n >= 0 ==> exc == NONE
          source none "fixture"
        """

    private fun items(vararg clauses: String) = (listOf(freeLine, "result res") + clauses.map { it.trimIndent() }).joinToString("\n")

    private fun resolve(
        body: String,
        items: String,
        helpers: String = limit,
        extra: String = "",
    ): Pair<String, ResolveOutcome> {
        val sidecar = ClientFixture.sidecar(items, extra)
        val outcome = ClientFixture.resolve(dir, ClientFixture.client(body, helpers), sidecar)
        assertNull(outcome.compileMessages, outcome.compileMessages)
        return sidecar to outcome
    }

    /** The 1-based line of the first line of [text] that contains [part]. */
    private fun lineOf(
        text: String,
        part: String,
    ) = text.lines().indexOfFirst { part in it }.also { check(it >= 0) { "no '$part'" } } + 1

    /** Asserts [o] has no sidecar error and a complete, well-formed module. */
    private fun assertComplete(o: ResolveOutcome) {
        assertEquals(emptyList(), o.errors)
        val m = o.module ?: fail("no module")
        assertFalse(m.incomplete, printModule(m))
        assertEquals(emptyList(), checkWellFormed(m))
    }

    @Test
    fun `a path read only in a dropped argument, with a clause that uses its name, is free-unused at the free line`() {
        val (sidecar, o) = resolve(dropped, items(ensuresN))
        val e = o.errors.singleOrNull() ?: fail("expected one error: ${o.errors}")
        assertEquals("free-unused", e.code)
        assertEquals(lineOf(sidecar, freeLine), e.pos.line)
        assertTrue(e.message.startsWith("free run.n: this.config.maxAttempt is read by no translated code, but n is used by N; "), e.message)
        // The hint names the unnamed read of another Kotlin Int: `limit` in `val big = limit > 1`.
        val client = ClientFixture.client(dropped, limit)
        val read = "Client.kt:${lineOf(client, "val big = limit")}:${client.lines()[lineOf(client, "val big = limit") - 1].indexOf("limit") + 1} (this.limit)"
        assertTrue(e.message.endsWith("unnamed translated reads of a Kotlin Int: $read (DESIGN-C 10.16)"), e.message)
        assertNull(o.module)
    }

    @Test
    fun `a derived value and a loop hole that use the name are named in the message, without a hint when no other Int is read`() {
        val body =
            """
            var attempts = 0
            while (attempts < 3) {
                attempts++
            }
            val data = Data(value = callable.call(), ttl = 0L)
            config.set.invoke(key, data, config.maxAttempt.toLong())
            return data
            """
        val trace = "trace {\n  nsup = count Callable.call\n  derived LIMITED = nsup >= n\n}\n"
        val hole = "hole loop Client.run \"\"\"\n  0 <= attempts || n < 0\n\"\"\"\n"
        val (_, o) = resolve(body, items(), helpers = "", extra = trace + hole)
        val e = o.errors.singleOrNull() ?: fail("expected one error: ${o.errors}")
        assertEquals("free-unused", e.code)
        assertEquals("free run.n: this.config.maxAttempt is read by no translated code, but n is used by derived LIMITED, hole loop Client.run (DESIGN-C 10.16)", e.message)
    }

    @Test
    fun `the same free line is no error when no sidecar text uses its name`() {
        val (_, o) = resolve(dropped, items())
        assertComplete(o)
        assertTrue(o.module!!.entries.single().free.none { it.sidecarName == "n" })
    }

    @Test
    fun `a translated read of the path declares the name, so a clause may use it`() {
        val body =
            """
            val data = Data(value = callable.call(), ttl = 0L)
            val big = config.maxAttempt > 3
            return data
            """
        val (_, o) = resolve(body, items(ensuresN))
        assertComplete(o)
        assertEquals(listOf("n"), o.module!!.entries.single().free.mapNotNull { it.sidecarName })
    }

    @Test
    fun `a read in a supported task declares the name for the whole operation`() {
        val release =
            """
            private fun release(
                key: String,
                task: () -> Unit,
            ) {
                CompletableFuture.runAsync(task, config.executor)
            }
            """
        val body =
            """
            val data = Data(value = callable.call(), ttl = 0L)
            release(key) {
                if (config.maxAttempt > 0) config.set.invoke(key, data, 0L)
            }
            return data
            """
        val (_, o) = resolve(body, items(ensuresN), helpers = release)
        assertComplete(o)
        val m = o.module!!
        assertTrue(m.entries.single().free.none { it.sidecarName == "n" }, printModule(m))
        assertEquals(listOf("n"), m.tasks.single().free.mapNotNull { it.sidecarName })
    }

    @Test
    fun `a read only inside an unsupported construct is no error, and the module stays incomplete`() {
        val body =
            """
            val data = Data(value = callable.call(), ttl = 0L)
            var attempts = 0
            do {
                attempts++
            } while (attempts < config.maxAttempt)
            return data
            """
        val (_, o) = resolve(body, items(ensuresN))
        assertEquals(emptyList(), o.errors)
        val m = o.module ?: fail("no module")
        assertTrue(m.incomplete, printModule(m))
        val status = m.entries.single().status as? Status.Unsupported ?: fail("the entry is supported")
        assertTrue(status.diagnostics.any { "do-while" in it.message }, status.diagnostics.toString())
        assertEquals(emptyList(), checkWellFormed(m))
    }
}
