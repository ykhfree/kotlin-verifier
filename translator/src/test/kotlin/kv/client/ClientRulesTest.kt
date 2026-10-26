package kv.client

import kv.kvir.Module
import kv.kvir.Status
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import kv.kvir.printModule
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Lowering rules of DESIGN-E 6.3 and appendix B that no single construct row shows: a model call needs its `use`; the
 * inlined helpers have no cycle (rule 2); a loop body hands no task to an executor (rule 4); every trusted call of a
 * unit uses the unit's key k (appendix B item 7).
 */
class ClientRulesTest {
    @TempDir
    lateinit var dir: File

    private fun lower(
        body: String,
        helpers: String = "",
        sidecar: String = ClientFixture.sidecar(),
    ): Module {
        val outcome = ClientFixture.resolve(dir, ClientFixture.client(body, helpers), sidecar)
        assertNull(outcome.compileMessages, outcome.compileMessages)
        assertEquals(emptyList(), outcome.errors)
        val m = outcome.module ?: fail("no module")
        assertEquals(emptyList(), checkWellFormed(m))
        return m
    }

    private fun messages(m: Module) = (m.entries.map { it.status } + m.tasks.map { it.status }).filterIsInstance<Status.Unsupported>().flatMap { it.diagnostics }.map { it.message }

    private fun assertRejected(
        m: Module,
        reason: String,
    ) {
        assertTrue(m.incomplete, printModule(m))
        assertTrue(messages(m).any { reason in it }, "no message with '$reason' in ${messages(m)}")
    }

    private val supplied = "return Data(value = callable.call(), ttl = 0L)"

    @Test
    fun `a client model call needs the sidecar's use`() {
        val c = ClientConstructs.all.single { it.row == 11 }.supported
        assertRejected(lower(c.body, c.helpers, ClientFixture.sidecar().replace("use jdk.Thread.sleep\n", "")), "needs model jdk.Thread.sleep, which the sidecar does not `use`")
    }

    @Test
    fun `a recursive helper is not inlined`() {
        val m =
            lower(
                "return again(3)",
                """
                private fun again(n: Int): Data<T> = if (n <= 0) Data(value = null, ttl = 0L) else again(n - 1)
                """,
            )
        assertRejected(m, "recursive call of again")
    }

    @Test
    fun `a loop body hands no task to an executor`() {
        val m =
            lower(
                """
                val data = Data(value = callable.call(), ttl = 0L)
                var n = 0
                while (n < config.maxAttempt) {
                    n++
                    CompletableFuture.runAsync({ config.set.invoke(key, data, 0L) }, config.executor)
                }
                return data
                """,
            )
        assertRejected(m, "a loop body has no Spawn")
    }

    @Test
    fun `every trusted call uses the unit's key`() {
        // Two parameters of the key type: the entry has no single key k, so no lock or cache call may use either.
        val client =
            ClientFixture.client(
                """
                val cached = config.get.invoke(key)
                if (cached != null) return cached
                val token = config.lock.tryLock(other, Kind.CREATE)
                $supplied
                """,
            ).replace("        key: String,\n", "        key: String,\n        other: String,\n")
        val outcome = ClientFixture.resolve(dir, client)
        assertNull(outcome.compileMessages, outcome.compileMessages)
        val m = outcome.module ?: fail("no module: ${outcome.errors}")
        assertRejected(m, "uses its key k (DESIGN-E appendix B item 7)")
        assertEquals(2, messages(m).count { "appendix B item 7" in it }, messages(m).toString())
    }
}
