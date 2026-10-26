package kv.sidecar

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sidecar checks that need no Kotlin code, on edited copies of the coroutine KeyLocalLock spec. */
class CheckTest {
    @TempDir
    lateinit var dir: File

    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")

    /**
     * Checks the coroutine spec after replacing [old] (once) with [new] in the common file. With [rereview], an edited
     * reviewed clause gets its new fingerprint, as if a person read it again, so that only the check under test reports.
     */
    private fun check(
        old: String,
        new: String,
        rereview: Boolean = true,
    ): List<SidecarError> {
        specDir.listFiles()!!.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
        val common = File(dir, "keylocallock.common.kv")
        val text = common.readText()
        require(text.split(old).size == 2) { "'$old' must occur once" }
        common.writeText(text.replace(old, new))
        if (rereview) kv.rereview(common)
        return checkSidecar(SidecarLoader.load(File(dir, "keylocallock.coroutine.kv")))
    }

    private fun lineOf(fragment: String) = File(dir, "keylocallock.common.kv").readLines().indexOfFirst { fragment in it } + 1

    @Test
    fun `the three KeyLocalLock specs pass the checks`() {
        for (variant in listOf("coroutine", "core", "reactor")) {
            assertEquals(emptyList(), checkSidecar(SidecarLoader.load(File(specDir, "keylocallock.$variant.kv"))), variant)
        }
    }

    @Test
    fun `an issue rule may set only frozen fields, and all of them`() {
        val errors = check("issue res { gKey = k;", "issue res { gHeld = true; gKey = k; gKey = k;")
        assertEquals(listOf("issue-fields", "issue-fields"), errors.map { it.code }, errors.joinToString("\n"))
        assertTrue("gHeld' is a ends field; the tool sets it" in errors[0].message, errors[0].message)
        assertTrue("'gKey' is set twice" in errors[1].message, errors[1].message)
        assertEquals(lineOf("issue res {"), errors[0].pos.line)
    }

    @Test
    fun `an abstract type of a free value is a sidecar type, and call values take none`() {
        val unknown = check("free t: Token = param token", "free t: Tok = param token")
        assertEquals(listOf("unknown-name"), unknown.map { it.code }, unknown.joinToString("\n"))
        assertEquals(lineOf("free t: Tok") to 11, unknown.single().pos.line to unknown.single().pos.col)
        val call = check("free rej, limit = call rejectsNewEntry", "free rej: Bool, limit = call rejectsNewEntry")
        assertEquals(listOf("free-type"), call.map { it.code }, call.joinToString("\n"))
        assertTrue("'returns' and 'ghost' clauses" in call.single().message, call.single().message)
        assertEquals(lineOf("free rej: Bool"), call.single().pos.line)
    }

    @Test
    fun `an issue rule names the operation result`() {
        val errors = check("issue res { gKey", "issue out { gKey")
        assertEquals("unknown-name", errors.single().code)
        assertTrue("the result of tryLock is 'res'" in errors.single().message)
    }

    @Test
    fun `R1 allows carried(S, k, t) but not S elsewhere, old or tok`() {
        assertEquals(emptyList(), check("gLease = now + timeout }", "gLease = now + timeout - 1 }"))
        val old = check("gLease = now + timeout }", "gLease = old(now) }")
        assertEquals(listOf("R1"), old.map { it.code })
        assertTrue("'old'" in old.single().message)
    }

    @Test
    fun `a call free item names one value per result and ghost value`() {
        val errors = check("free rej, limit = call rejectsNewEntry", "free rej = call rejectsNewEntry")
        assertEquals("free-arity", errors.first().code, errors.joinToString("\n"))
        assertTrue("gives 2 value(s) [rej, limit]" in errors.first().message)
    }

    @Test
    fun `a probe call gives every named free value once`() {
        val errors = check("ok := unLock(k, t = t1)", "ok := unLock(k, u = t1)")
        assertEquals(listOf("probe-args", "probe-args"), errors.map { it.code }, errors.joinToString("\n"))
        assertTrue(errors.any { "no free value 'u'" in it.message })
        assertTrue(errors.any { "gives no value to [t]" in it.message })
    }

    @Test
    fun `a witness names a template-typed field of a sidecar class`() {
        val errors = check("witness LockInfo.token", "witness LockInfo.expiresAt")
        assertEquals("witness-type", errors.single().code)
        assertEquals(lineOf("witness LockInfo.expiresAt") to 18, errors.single().pos.line to errors.single().pos.col)
    }

    @Test
    fun `a sidecar name may not reuse a tool or reserved name`() {
        val errors = check("state m:   Map<Key, LockInfo> = lockMap", "state MI:   Map<Key, LockInfo> = lockMap")
        assertEquals("name-clash", errors.single().code, errors.joinToString("\n"))
        assertTrue("a name the tool generates" in errors.single().message)
    }

    @Test
    fun `the names of reserve steps are tool names - gPend, MI_PEND and the method prefix reserve_ (DESIGN-D 6_6)`() {
        val pend = check("state m:   Map<Key, LockInfo> = lockMap", "state gPend:   Map<Key, LockInfo> = lockMap")
        assertEquals("name-clash", pend.single().code, pend.joinToString("\n"))
        assertTrue("a name the tool generates" in pend.single().message)
        val prefix = check("operation sweep = compute in KeyLocalLock.Companion {", "operation reserve_sweep = compute in KeyLocalLock.Companion {")
        assertEquals(listOf("name-clash"), prefix.map { it.code }, prefix.joinToString("\n"))
        assertTrue("prefix of the tool's reserve step methods" in prefix.single().message, prefix.single().message)
        assertEquals(lineOf("operation reserve_sweep"), prefix.single().pos.line)
    }
}
