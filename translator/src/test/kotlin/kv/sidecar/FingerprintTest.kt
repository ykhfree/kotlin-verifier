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

    private val p3a = "  ends_only_if P3a: qt == t && res\n"
    private val quote = "Only the holder that acquired the lock can release it"
    private val sourceLine = "source \"README.md#global-lock-guidance/2\" quote \"$quote\" sha256 \"ffd91c43c843b5f8\""

    /** The fingerprint of unLock's P3a with [line] as its source line, computed here from the normalized text. */
    private fun withSource(line: String) = sha16("ends_only_if P3a: qt == t && res $line")

    /** unLock's P3a as signed on 2026-10-28 (DESIGN-C.md 10.14): its source line, then its note. */
    private val note = "  reviewed \"ykhfree 2026-10-23: README: only the holder that acquired the lock can release it\" sha256 \"${withSource(sourceLine)}\"\n"
    private val signed = "$p3a    $sourceLine\n$note"

    @Test
    fun `a changed allowance clause is stale-review, with the fingerprint of the new text`() {
        val errors = check("ends_only_if P3a: qt == t && res", "ends_only_if P3a: qt == t || res")
        val e = errors.single()
        assertEquals("stale-review", e.code, e.toString())
        // The note follows the source line.
        assertEquals(lineOf("ends_only_if P3a: qt == t || res") + 2 to 3, e.pos.line to e.pos.col)
        assertTrue("sha256 \"3d4d5f9434a6cee5\"" in e.message && "sha256 \"${sha16("ends_only_if P3a: qt == t || res $sourceLine")}\"" in e.message, e.message)
    }

    @Test
    fun `a changed issue rule is stale-review`() {
        val e = check("gLease = now + timeout }", "gLease = now + timeout + 1 }").single()
        assertEquals("stale-review", e.code, e.toString())
        assertTrue("the issue rule of tryLock" in e.message, e.message)
        val source = "source \"README.md#global-lock-guidance/2\" quote \"Every lock acquisition carries an ownership token.\" sha256 \"ffd91c43c843b5f8\""
        assertTrue(sha16("issue res { gKey = k; gBacked = carried(S, k, res); gLease = now + timeout + 1 } $source") in e.message, e.message)
    }

    @Test
    fun `a whitespace-only change of a clause keeps its review`() {
        val errors = check("unbacked_only_if P3b: !old(has(S, k)) && rej && limit != 0", "unbacked_only_if P3b:   !old(has(S, k))\n      &&  rej\t&& limit != 0")
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `the reviewed clauses of the specs keep their fingerprints, and without source lines the reviewed text is the clause text`() {
        val notes =
            specDir.listFiles()!!.filter { it.name.endsWith(".kv") }.sortedBy { it.name }.flatMap { f ->
                parseSidecar(f.path, f.readText()).intentClauses().filter { (_, c) -> c.reviewed != null }.map { (what, c) -> "${f.name}: $what" to c }
            }
        assertEquals(
            listOf(
                // The L1 sidecars under review intent (DESIGN-C.md 10.14, 2026-10-28): every clause has a source line.
                // factory_throw (10.4), refusal_keeps_entry, entry_lease and entry_held (10, UC2 and UC3) and the two
                // no_entry_created (10.4) kept their fingerprints; the 2026-10-23 notes of the issue rule, P3a and P3b
                // were re-recorded with their new source lines (note text unchanged); the others are new.
                "aspect.common.kv: set-once P_stable" to "a0d3dc9284e8a9ef",
                "aspect.common.kv: ensures P_same of getOrCreate" to "4c3eae5f584ea521",
                "aspect.common.kv: ensures progress_ok of getOrCreate" to "32acbf2df32b41a0",
                "aspect.common.kv: ensures factory_throw of getOrCreate" to "debfb2380fd3b4fa",
                "aspect.common.kv: invariant P_once" to "cb1f3d7e530b3fce",
                "aspect.common.kv: the probe" to "f3f68380847a6c16",
                "keylocallock.common.kv: the issue rule of tryLock" to "96c214a31662eaee",
                "keylocallock.common.kv: ensures P1a of tryLock" to "c76edf11a3116538",
                "keylocallock.common.kv: ensures P1a_entry of tryLock" to "c31c25acde4a0c8e",
                "keylocallock.common.kv: ends_only_if P3a of tryLock" to "3dd0a68bbaa68ef4",
                "keylocallock.common.kv: unbacked_only_if P3b of tryLock" to "a185c9abbb1779b1",
                "keylocallock.common.kv: ensures progress_free of tryLock" to "b0a991950143c582",
                "keylocallock.common.kv: ensures progress_live of tryLock" to "3ec02c509bb76274",
                "keylocallock.common.kv: ensures cap_unbacked of tryLock" to "05566188e07547db",
                "keylocallock.common.kv: ensures expired_takeover of tryLock" to "d8fa38990be32fdb",
                "keylocallock.common.kv: ensures refusal_keeps_entry of tryLock" to "94198e9729f7b868",
                "keylocallock.common.kv: ensures P1 of unLock" to "5f05845a7165e5f1",
                "keylocallock.common.kv: ends_only_if P3a of unLock" to "3d4d5f9434a6cee5",
                "keylocallock.common.kv: ensures progress_owner of unLock" to "b751dc61762dbef0",
                "keylocallock.common.kv: ensures no_entry_created of unLock" to "509d721ad1aca002",
                "keylocallock.common.kv: ends_only_if P3a of sweep" to "3c68af4c1e630a46",
                "keylocallock.common.kv: ensures no_entry_created of sweep" to "509d721ad1aca002",
                "keylocallock.common.kv: invariant P2" to "e253d83565837981",
                "keylocallock.common.kv: invariant entry_lease" to "c2ae43c5a13c3c35",
                "keylocallock.common.kv: invariant entry_held" to "be2c7e636d7ac55a",
                "keylocallock.common.kv: lemma P3c" to "d6ff0d0c25088bbe",
                "keylocallock.common.kv: the probe" to "6b996ce1ab3885c4",
                // Signed on 2026-10-27, every clause with source lines (DESIGN-E 2.5, the client module).
                "reqshield.core.kv: assume A1" to "35eb5c3e1a8b21ef",
                "reqshield.core.kv: assume A3" to "fde6bbe239879d87",
                "reqshield.core.kv: assume A4" to "d4f37ee0db6d53c7",
                "reqshield.core.kv: assume A5" to "f6820a13679b2999",
                "reqshield.core.kv: guard LD_write of getAndSet" to "c4c14c27307e737d",
                "reqshield.core.kv: guard LD_supplier of getAndSet" to "ca26eadd855a7371",
                "reqshield.core.kv: ensures MODE_create of getAndSet" to "b15ab050000139eb",
                "reqshield.core.kv: ensures MODE_update of getAndSet" to "3abfc83b10817177",
                "reqshield.core.kv: ensures SUP_once of getAndSet" to "c41cb4b3ad8252fe",
                "reqshield.core.kv: ensures POLL_bound of getAndSet" to "7fba6f874904cd28",
                "reqshield.core.kv: ensures RET_hit of getAndSet" to "edfa670dc5cc5575",
                "reqshield.core.kv: ensures RET_get_error of getAndSet" to "9ca1c027a6bbcbea",
                "reqshield.core.kv: ensures RET_recheck of getAndSet" to "297cf5cfd83cc146",
                "reqshield.core.kv: ensures RET_poll of getAndSet" to "90958e08c41e5038",
                "reqshield.core.kv: ensures RET_supplier of getAndSet" to "8e8bd9577b8508df",
                "reqshield.core.kv: ensures RET_supplier_error of getAndSet" to "788b282edd61f30f",
            ),
            notes.map { (what, c) -> what to c.sha256 },
        )
        for ((what, c) in notes) {
            // Since DESIGN-C.md 10.14 every signed clause has source lines: the reviewed text is the clause, then them.
            assertTrue(c.sources.isNotEmpty() && c.reviewedText.startsWith(c.unit), what)
            assertEquals(c.sha256, reviewFingerprint(c.reviewedText), what)
        }
    }

    @Test
    fun `adding a source line to a signed clause is stale-review, with the fingerprint of the clause and the lines`() {
        val second = "source \"CLAUDE.md#key-components/2\" quote \"`unLock` only releases when the token matches\" sha256 \"58d3866dfcb69dcf\""
        val e = check("$p3a    $sourceLine\n", "$p3a    $sourceLine\n    $second\n").single()
        assertEquals("stale-review", e.code, e.toString())
        assertEquals(lineOf("only the holder that acquired the lock can release it\" sha256") to 3, e.pos.line to e.pos.col)
        assertTrue("ends_only_if P3a of unLock" in e.message && "the clause text and its source lines now have sha256 \"${withSource("$sourceLine $second")}\"" in e.message, e.message)
    }

    @Test
    fun `a signed clause's source line is part of its review - a changed quote, anchor or removed line is stale-review`() {
        // The spec holds exactly this text, so its note fingerprint is the one computed here.
        assertEquals(emptyList(), check(signed, signed))
        // A whitespace-only change keeps the review.
        assertEquals(emptyList(), check(signed, signed.replace("quote \"$quote\"", "quote   \"$quote\"")))
        val edits =
            listOf(
                "quote" to signed.replace("can release it\" sha256 \"ffd9", "may release it\" sha256 \"ffd9"),
                "anchor" to signed.replace("global-lock-guidance/2", "global-lock-guidance/1"),
                "block fingerprint" to signed.replace("ffd91c43c843b5f8", "0000000000000000"),
                "removed line" to signed.replace("    $sourceLine\n", ""),
            )
        for ((what, edited) in edits) {
            val errors = check(signed, edited)
            // Under review intent a clause without a source line is also unsourced.
            assertEquals(if (what == "removed line") listOf("stale-review", "unsourced") else listOf("stale-review"), errors.map { it.code }, "$what: $errors")
            val e = errors.first()
            assertTrue("records sha256 \"${withSource(sourceLine)}\"" in e.message, "$what: ${e.message}")
        }
    }

    @Test
    fun `a reviewed clause without a fingerprint is an error that names the fingerprint to record`() {
        val e = check(note, note.replace(" sha256 \"${withSource(sourceLine)}\"", "")).single()
        assertEquals("missing-fingerprint", e.code, e.toString())
        assertEquals(lineOf("only the holder that acquired the lock can release it\"") to 3, e.pos.line to e.pos.col)
        assertTrue("sha256 \"${withSource(sourceLine)}\"" in e.message, e.message)
    }
}
