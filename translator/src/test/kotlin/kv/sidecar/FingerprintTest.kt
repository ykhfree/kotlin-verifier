package kv.sidecar

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The SHA-256 of [s] (UTF-8) in hex, first 16 characters: computed here, apart from [reviewFingerprint]. */
internal fun sha16(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

/**
 * B6 (DESIGN-v0.md 8.3 "reviewed 지문"): the fingerprint rule, the `reviewed ... sha256` syntax and the reviewed unit
 * of a clause, and the clause checks on edited copies of the KeyLocalLock specs.
 */
class FingerprintTest {
    @TempDir
    lateinit var dir: File

    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")

    @Test
    fun `the fingerprint is the first 16 hex characters of the SHA-256 of the text with its whitespace runs as one space`() {
        assertEquals(sha16("fun f(x: Long) = x + 1"), reviewFingerprint("fun f(x: Long) = x + 1"))
        assertEquals(sha16("fun f(x: Long) = x + 1"), reviewFingerprint("  fun f(x: Long)\r\n\t  =   x + 1\n"))
        assertEquals(sha16("a b"), reviewFingerprint("a\r\nb"))
        // A whitespace run becomes one space; it does not vanish. Any other character counts.
        assertNotEquals(reviewFingerprint("fun f(x: Long) = x + 1"), reviewFingerprint("fun f(x:Long) = x + 1"))
        assertNotEquals(reviewFingerprint("fun f(x: Long) = x + 1"), reviewFingerprint("fun f(x: Int) = x + 1"))
        // The reviewed TimeUtils.kt:23 at req-shield 2e792d2, as keylocallock.common.kv records it.
        assertEquals("aa569e5b9740b13b", reviewFingerprint("fun nowToEpochTime() = System.currentTimeMillis()"))
    }

    @Test
    fun `the parser reads the fingerprint, and a clause's unit runs from its keyword through its expression`() {
        val s =
            parseSidecar(
                "t.kv",
                """
                |trusted fun C.f() = clock
                |  reviewed "clock" sha256 "fedcba9876543210"
                |operation op = fun C.op {
                |  result res
                |  issue res { gKey = k;
                |      gLease = now }
                |  reviewed "issue" sha256 "0123456789abcdef"
                |  ends_only_if P3a: now >
                |      // a comment line inside the expression
                |      old(x)   // a comment after it
                |  reviewed "read"
                |}
                """.trimMargin(),
            )
        val t = s.trusted.single()
        assertEquals("fedcba9876543210", t.sha256)
        assertEquals(SPos("t.kv", 2, 3), t.reviewPos)
        val issue = s.operations.single().items.filterIsInstance<IssueItem>().single()
        assertEquals("issue res { gKey = k;\n      gLease = now }", issue.unit)
        assertEquals("0123456789abcdef", issue.sha256)
        assertEquals(SPos("t.kv", 7, 3), issue.reviewPos)
        val p3a = s.operations.single().items.filterIsInstance<AllowanceItem>().single()
        assertEquals("ends_only_if P3a: now >\n      // a comment line inside the expression\n      old(x)", p3a.unit)
        assertNull(p3a.sha256)
        assertEquals("read", p3a.reviewed)
    }

    /** The checks of the coroutine spec after replacing [old] (once) with [new] in the common file. */
    private fun check(
        old: String,
        new: String,
    ): List<SidecarError> {
        specDir.listFiles()!!.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
        val common = File(dir, "keylocallock.common.kv")
        val text = common.readText()
        require(text.split(old).size == 2) { "'$old' must occur once" }
        common.writeText(text.replace(old, new))
        return checkSidecar(SidecarLoader.load(File(dir, "keylocallock.coroutine.kv")))
    }

    private fun lineOf(fragment: String) = File(dir, "keylocallock.common.kv").readLines().indexOfFirst { fragment in it } + 1

    @Test
    fun `a changed allowance clause is stale-review, with the fingerprint of the new text`() {
        val errors = check("ends_only_if P3a: qt == t && res", "ends_only_if P3a: qt == t || res")
        val e = errors.single()
        assertEquals("stale-review", e.code, e.toString())
        assertEquals(lineOf("ends_only_if P3a: qt == t || res") + 1 to 3, e.pos.line to e.pos.col)
        assertTrue("sha256 \"d679f5c705a959be\"" in e.message && "sha256 \"${sha16("ends_only_if P3a: qt == t || res")}\"" in e.message, e.message)
    }

    @Test
    fun `a changed issue rule is stale-review`() {
        val e = check("gLease = now + timeout }", "gLease = now + timeout + 1 }").single()
        assertEquals("stale-review", e.code, e.toString())
        assertTrue("the issue rule of tryLock" in e.message, e.message)
        assertTrue(sha16("issue res { gKey = k; gBacked = carried(S, k, res); gLease = now + timeout + 1 }") in e.message, e.message)
    }

    @Test
    fun `a whitespace-only change of a clause keeps its review`() {
        val errors = check("unbacked_only_if P3b: !old(has(S, k)) && rej && limit != 0", "unbacked_only_if P3b:   !old(has(S, k))\n      &&  rej\t&& limit != 0")
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `the five reviewed clauses of the specs keep their fingerprints, since their reviewed text is the clause text`() {
        val notes =
            specDir.listFiles()!!.filter { it.name.endsWith(".kv") }.sortedBy { it.name }.flatMap { f ->
                parseSidecar(f.path, f.readText()).intentClauses().filter { (_, c) -> c.reviewed != null }.map { (what, c) -> "${f.name}: $what" to c }
            }
        assertEquals(
            listOf(
                "keylocallock.common.kv: the issue rule of tryLock" to "023e62e52c1fc231",
                "keylocallock.common.kv: ends_only_if P3a of tryLock" to "1c0ae67dbfe9757d",
                "keylocallock.common.kv: unbacked_only_if P3b of tryLock" to "23aa1de7c7b2dde4",
                "keylocallock.common.kv: ends_only_if P3a of unLock" to "d679f5c705a959be",
                "keylocallock.common.kv: ends_only_if P3a of sweep" to "1c0ae67dbfe9757d",
            ),
            notes.map { (what, c) -> what to c.sha256 },
        )
        for ((what, c) in notes) {
            assertEquals(emptyList(), c.sources, what)
            assertEquals(c.unit, c.reviewedText, what)
            assertEquals(c.sha256, reviewFingerprint(c.reviewedText), what)
        }
    }

    private val p3a = "  ends_only_if P3a: qt == t && res\n"
    private val quote = "Only the holder that acquired the lock can release it"
    private val sourceLine = "source \"README.md#global-lock-guidance/2\" quote \"$quote\" sha256 \"ffd91c43c843b5f8\""

    /** The fingerprint of unLock's P3a with [line] as its source line, computed here from the normalized text. */
    private fun withSource(line: String) = sha16("ends_only_if P3a: qt == t && res $line")

    @Test
    fun `adding a source line to a signed clause is stale-review, with the fingerprint of the clause and the line`() {
        val e = check(p3a, "$p3a    $sourceLine\n").single()
        assertEquals("stale-review", e.code, e.toString())
        assertEquals(lineOf("only the holder that acquired") to 3, e.pos.line to e.pos.col)
        assertTrue("ends_only_if P3a of unLock" in e.message && "the clause text and its source lines now have sha256 \"${withSource(sourceLine)}\"" in e.message, e.message)
    }

    @Test
    fun `a signed clause's source line is part of its review - a changed quote, anchor or removed line is stale-review`() {
        val signed = "$p3a    $sourceLine\n  reviewed \"ykhfree 2026-10-23: README: only the holder that acquired the lock can release it\" sha256 \"${withSource(sourceLine)}\"\n"
        val note = "  reviewed \"ykhfree 2026-10-23: README: only the holder that acquired the lock can release it\" sha256 \"d679f5c705a959be\"\n"
        assertEquals(emptyList(), check(p3a + note, signed))
        // A whitespace-only change keeps the review.
        assertEquals(emptyList(), check(p3a + note, signed.replace("quote \"$quote\"", "quote   \"$quote\"")))
        val edits =
            listOf(
                "quote" to signed.replace("can release it\" sha256 \"ffd9", "may release it\" sha256 \"ffd9"),
                "anchor" to signed.replace("global-lock-guidance/2", "global-lock-guidance/1"),
                "block fingerprint" to signed.replace("ffd91c43c843b5f8", "0000000000000000"),
                "removed line" to signed.replace("    $sourceLine\n", ""),
            )
        for ((what, edited) in edits) {
            val e = check(p3a + note, edited).single()
            assertEquals("stale-review", e.code, "$what: $e")
            assertTrue("records sha256 \"${withSource(sourceLine)}\"" in e.message, "$what: ${e.message}")
        }
    }

    @Test
    fun `a reviewed clause without a fingerprint is an error that names the fingerprint to record`() {
        val e = check("README: only the holder that acquired the lock can release it\" sha256 \"d679f5c705a959be\"", "README: only the holder that acquired the lock can release it\"").single()
        assertEquals("missing-fingerprint", e.code, e.toString())
        assertEquals(lineOf("only the holder that acquired") to 3, e.pos.line to e.pos.col)
        assertTrue("sha256 \"d679f5c705a959be\"" in e.message, e.message)
    }
}
