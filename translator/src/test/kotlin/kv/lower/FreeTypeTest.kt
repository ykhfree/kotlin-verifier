package kv.lower

import kv.emit.Workspace
import kv.emit.emitBound
import kv.kvir.Captured
import kv.kvir.KType
import kv.kvir.Status
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The abstract type of a String free value (DESIGN-v0.md 4.2, restriction 4) as a fixed point of the 2.13 inference,
 * and named free values that the action never reads (2.12 rule 3). SB1 and SB2 (section 6) remove the only code
 * that compares unLock's token with a sidecar field, so only the annotation types it.
 */
class FreeTypeTest {
    @TempDir
    lateinit var dir: File

    /** SB1 (DESIGN-v0.md 6): unLock no longer compares the token. */
    private fun sb1(ws: Workspace, variant: String) = ws.editKotlin(variant, "existing.token == token && ", "")

    /** SB2 (DESIGN-v0.md 6): unLock compares the token outside the action, a racy read. */
    private fun sb2(ws: Workspace, variant: String) {
        ws.editKotlin(variant, "        val released = AtomicBoolean(false)\n", "        val released = AtomicBoolean(false)\n        val sawMatch = lockMap[completeKey]?.token == token\n")
        ws.editKotlin(variant, "if (existing.token == token && existing.isHeld", "if (sawMatch && existing.isHeld")
    }

    @Test
    fun `an annotated free value the action never reads is a Token parameter with its type fact (SB1, both variants)`() {
        for (variant in listOf("coroutine", "core")) {
            val ws = Workspace(File(dir, variant))
            sb1(ws, variant)
            val (m, s) = ws.lower(variant)
            val unLock = m.operations.single { it.name == "unLock" }
            assertEquals(Status.Supported, unLock.action.status, variant)
            val t = unLock.action.free.single()
            assertEquals("t" to KType.Abs("Token", false), t.sidecarName to t.local.type, variant)
            assertEquals(Captured::class, t.origin::class, variant)
            val text = emitBound(m, s).text
            assertTrue("method unLock(S: Ref, k: Key, t: Int) returns (res: Bool)\n  requires MI(S)\n  requires t != NULL" in text, "$variant:\n$text")
        }
    }

    @Test
    fun `SB2 lowers with the annotated token as a parameter and the racy compare as an unnamed free value`() {
        val ws = Workspace(dir)
        sb2(ws, "coroutine")
        val (m, _) = ws.lower("coroutine")
        val unLock = m.operations.single { it.name == "unLock" }.action
        assertEquals(Status.Supported, unLock.status)
        val line = ws.kotlinLine("coroutine", "val sawMatch")
        assertEquals(listOf("t" to KType.Abs("Token", false), "sawMatch__$line" to KType.BoolT), unLock.free.map { it.local.name to it.local.type })
    }

    // ---- named free values that the action never reads: reported at the declaration, outside the action ----

    private val limitOperation =
        """operation run = fun Lock.run {
  free now     = param now
  free timeout = this.timeout
  free limit   = local limit
}"""

    @Test
    fun `an unread named free value of type Long? makes the action unsupported, and W14 holds`() {
        // assertUnsupported checks W1-W14, and lower() checks them again after the ghost binder: the declaration
        // of limit is outside the action, so it must be in the ledger and not also translated.
        val f = LockFixture("if (e != null) e.expiresAt = now\ne", before = "        val limit: Long? = null", operations = limitOperation)
        val u = assertUnsupported(f.lower(dir), "nullable Long", f.at("val limit"))
        assertTrue("without null" in u.reason, u.reason)
    }

    @Test
    fun `an unread named free value of a type outside the model makes the action unsupported, W1-W14 hold`() {
        val f = LockFixture("if (e != null) e.expiresAt = now\ne", before = "        val limit = java.util.UUID.randomUUID()", operations = limitOperation)
        assertUnsupported(f.lower(dir), "value of type java.util.UUID", f.at("val limit"))
    }
}
