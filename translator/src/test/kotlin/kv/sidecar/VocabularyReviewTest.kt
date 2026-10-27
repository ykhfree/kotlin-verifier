package kv.sidecar

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DESIGN-E 12.3 (V-C): `review vocabulary` and its note. Under `review intent` the note signs every vocabulary
 * declaration (type, enum, class, obligation, trace, ghost record, witness); its fingerprint covers their text without
 * comments (12.3.2). The cases are those of DESIGN-E 12.7 A and B.
 */
class VocabularyReviewTest {
    @TempDir
    lateinit var dir: File

    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")

    /** The signed core ReqShield sidecar (DESIGN-E 2.5, vocabulary note of 2026-10-28). */
    private val signed = File(specDir, "reqshield.core.kv").readText()

    private fun errors(
        text: String,
        draft: Boolean = false,
    ) = checkSidecar(parseSidecar("t.kv", text), draft)

    /** The errors of [text] that concern the vocabulary or its note. */
    private fun vocabularyErrors(
        text: String,
        draft: Boolean = false,
    ) = errors(text, draft).filter { "vocabulary" in it.message }

    /** [text] with [old] replaced by [new]; [old] must occur exactly once. */
    private fun edit(
        text: String,
        old: String,
        new: String,
    ): String {
        check(text.split(old).size == 2) { "'$old' must occur once" }
        return text.replace(old, new)
    }

    private val note = Regex("""(?m)^review vocabulary\n {2}reviewed "[^"]*" sha256 "[0-9a-f]{16}"\n""")

    /** [signed] with its vocabulary note replaced by [replacement]. */
    private fun withNote(replacement: String) = signed.replace(note, replacement).also { check(it != signed) { "no vocabulary note" } }

    @Test
    fun `review vocabulary reads its note and fingerprint, and needs no note to parse`() {
        val s = parseSidecar("t.kv", "review vocabulary\n  reviewed \"n\" sha256 \"0123456789abcdef\"")
        val v = s.vocabularyReviews.single()
        assertEquals("n" to "0123456789abcdef", v.reviewed to v.sha256)
        assertEquals(2 to 3, v.reviewPos!!.line to v.reviewPos.col)
        val bare = parseSidecar("t.kv", "review vocabulary").vocabularyReviews.single()
        assertEquals(null to null, bare.reviewed to bare.sha256)
    }

    @Test
    fun `review takes intent or vocabulary, and a note after a vocabulary declaration is a syntax error`() {
        val e = assertFailsWith<SidecarException> { parseSidecar("t.kv", "review vocab") }.errors.single()
        assertEquals("syntax", e.code)
        assertTrue("expected 'intent' or 'vocabulary'" in e.message, e.message)
        val type = assertFailsWith<SidecarException> { parseSidecar("t.kv", "type Key = opaque\nreviewed \"a\"") }.errors.single()
        assertTrue("'reviewed' must follow" in type.message, type.message)
    }

    @Test
    fun `the signed sidecar passes, and its vocabulary fingerprint is the reference value`() {
        assertEquals(emptyList(), errors(signed))
        val s = parseSidecar("t.kv", signed)
        assertEquals(listOf("type Key", "type Token", "enum LockType", "enum Mode", "class Data", "obligation Lease", "trace"), s.vocabulary.map { it.name })
        // DESIGN-E 12.8 prediction 1: the value of the reference script ref/vocab_fp.py.
        assertEquals("92a6a3bbf1f4b44b", reviewFingerprint(s.vocabularyText))
    }

    @Test
    fun `under review intent a missing vocabulary note is unreviewed, except in a draft`() {
        val without = withNote("")
        val errors = vocabularyErrors(without)
        assertEquals(listOf("unreviewed"), errors.map { it.code }, errors.joinToString("\n"))
        // The error names every covered declaration and sits at the first one (type Key).
        assertTrue("the vocabulary (type Key, type Token, enum LockType, enum Mode, class Data, obligation Lease, trace)" in errors[0].message, errors[0].message)
        assertEquals(signed.lines().indexOfFirst { it.startsWith("type Key") } + 1, errors[0].pos.line)
        assertEquals(emptyList(), vocabularyErrors(without, draft = true))
        // A `review vocabulary` line without a note: unreviewed at that line.
        val bare = withNote("review vocabulary\n")
        val bareErrors = vocabularyErrors(bare)
        assertEquals(listOf("unreviewed"), bareErrors.map { it.code })
        assertEquals(bare.lines().indexOf("review vocabulary") + 1, bareErrors[0].pos.line)
        assertEquals(emptyList(), vocabularyErrors(bare, draft = true))
    }

    @Test
    fun `a note without a fingerprint is an error that gives it, except in a draft, and a wrong one is stale even in a draft`() {
        val missing = vocabularyErrors(withNote("review vocabulary\n  reviewed \"n\"\n"))
        assertEquals(listOf("missing-fingerprint"), missing.map { it.code })
        assertTrue("sha256 \"92a6a3bbf1f4b44b\"" in missing[0].message, missing[0].message)
        assertEquals(emptyList(), vocabularyErrors(withNote("review vocabulary\n  reviewed \"n\"\n"), draft = true))
        val wrong = withNote("review vocabulary\n  reviewed \"n\" sha256 \"0000000000000000\"\n")
        for (draft in listOf(false, true)) {
            val stale = vocabularyErrors(wrong, draft)
            assertEquals(listOf("stale-review"), stale.map { it.code })
            assertTrue("now has sha256 \"92a6a3bbf1f4b44b\"" in stale[0].message, stale[0].message)
        }
    }

    @Test
    fun `one changed token in any kind of vocabulary declaration makes the note stale`() {
        val edits =
            listOf(
                "type Token = opaque" to "type Ticket = opaque",
                "enum Mode = ReqShieldWorkMode" to "enum Mode = LockType",
                "{ value: Int = value }" to "{ value: Int = timeToLiveMillis }",
                "obligation Lease(Key, LockType, Token)" to "obligation Lease(Key, Key, Token)",
                "nsup    = count Callable.call" to "nsup    = last Callable.call",
                "ntryC   = count KeyLock.tryLock where lt == CREATE" to "ntryC   = count KeyLock.tryLock where lt == UPDATE",
                "derived GAVE_UP = npoll >= maxAttempt || consec >= 3" to "derived GAVE_UP = true",
                "recheck = getCacheFunction while holds(Lease(k, CREATE, _))" to "recheck = getCacheFunction while holds(Lease(k, UPDATE, _))",
            )
        for ((old, new) in edits) {
            val errors = vocabularyErrors(edit(signed, old, new))
            assertEquals(listOf("stale-review"), errors.map { it.code }, "$old -> $new: $errors")
        }
    }

    @Test
    fun `comments, spaces and moves past other declarations keep the fingerprint, but a new order or a new field does not`() {
        val cosmetic =
            edit(
                edit(signed, "// a read under the CREATE lease", "// a read while this call holds the CREATE lease"),
                "type Key   = opaque",
                "type Key = opaque // a new comment\n   ",
            ).let { edit(it, "  consec  = trailing failures of npoll", "      consec =   trailing failures of npoll") }
        assertEquals(emptyList(), vocabularyErrors(cosmetic))
        // `enum Mode` moved below the `use` lines: the vocabulary keeps its order.
        val mode = "enum Mode = ReqShieldWorkMode                             // CREATE_AND_UPDATE_CACHE, ONLY_CREATE_CACHE, ONLY_UPDATE_CACHE\n"
        val moved = edit(edit(signed, mode, ""), "use slf4j.Logger\n", "use slf4j.Logger\n$mode")
        assertEquals(listOf("stale-review"), vocabularyErrors(moved).map { it.code }, "enum Mode now follows class Data and the trace")
        val swapped = edit(signed, "type Key   = opaque", "type Kez   = opaque").let { edit(it, "type Token = opaque", "type Key = opaque") }.let { edit(it, "type Kez   = opaque", "type Token = opaque") }
        assertEquals(listOf("stale-review"), vocabularyErrors(swapped).map { it.code })
        val added = edit(signed, "  nsup    = count Callable.call\n", "  nsup    = count Callable.call\n  nsup2   = count Callable.call\n")
        assertEquals(listOf("stale-review"), vocabularyErrors(added).map { it.code })
    }

    @Test
    fun `a vocabulary declaration moved past other kinds of declarations keeps the fingerprint`() {
        // The trace, the last vocabulary declaration, moves below the trusted and use lines: the vocabulary order stays.
        val trace = Regex("""(?ms)^trace \{.*?^}\n""").find(signed)!!.value
        val moved = edit(edit(signed, trace, ""), "use slf4j.Logger\n", "use slf4j.Logger\n$trace")
        assertEquals(emptyList(), vocabularyErrors(moved))
        assertEquals(emptyList(), errors(moved))
    }

    @Test
    fun `a double slash inside a template string is text, not a comment`() {
        val base = "module a.C\nsource \"C.kt\"\ntype T = template \"a//\${Long}\"   // a real comment\nreview vocabulary\n  reviewed \"n\" sha256 \"%s\"\n"
        val fp = reviewFingerprint("type T = template \"a//\${Long}\"")
        assertEquals(fp, reviewFingerprint(parseSidecar("t.kv", base.format(fp)).vocabularyText))
        val changed = parseSidecar("t.kv", base.replace("a//", "a//b").format(fp))
        assertEquals(listOf("stale-review"), checkSidecar(changed).filter { "vocabulary" in it.message }.map { it.code })
    }

    @Test
    fun `without review intent no vocabulary note is needed, but a note's fingerprint is checked`() {
        val plain = edit(withNote(""), "review intent\n", "")
        assertEquals(emptyList(), vocabularyErrors(plain))
        val l1 = File(specDir, "keylocallock.common.kv").readText()
        assertEquals(emptyList(), checkSidecar(parseSidecar("l1.kv", l1)).filter { "vocabulary" in it.message })
        val stale = plain + "review vocabulary\n  reviewed \"n\" sha256 \"0000000000000000\"\n"
        assertEquals(listOf("stale-review"), vocabularyErrors(stale).map { it.code })
    }

    @Test
    fun `a second review vocabulary is a duplicate`() {
        val twice = signed + "\nreview vocabulary\n"
        val errors = vocabularyErrors(twice)
        assertEquals(listOf("duplicate-decl"), errors.map { it.code }, errors.joinToString("\n"))
    }

    @Test
    fun `the note covers the vocabulary of an included file`() {
        val types = signed.lines().filter { it.startsWith("type ") }.joinToString("\n", postfix = "\n")
        File(dir, "types.kv").writeText(types)
        val root = File(dir, "root.kv").apply { writeText(signed.replace(types, "include \"types.kv\"\n")) }
        assertEquals(emptyList(), checkSidecar(SidecarLoader.load(root)).filter { "vocabulary" in it.message })
        File(dir, "types.kv").writeText(types.replace("type Token", "type Ticket"))
        assertEquals(listOf("stale-review"), checkSidecar(SidecarLoader.load(root)).filter { "vocabulary" in it.message }.map { it.code })
    }

    @Test
    fun `under review intent the ghost record and the witness of an L1 sidecar are vocabulary`() {
        val l1 = File(specDir, "keylocallock.common.kv").readText()
        val s0 = parseSidecar("l1.kv", l1)
        assertTrue(s0.vocabulary.any { it.name == "ghost record tok" } && s0.vocabulary.any { it.name == "witness LockInfo.token" }, s0.vocabulary.toString())
        val signedL1 = "review intent\n$l1\nreview vocabulary\n  reviewed \"n\" sha256 \"${reviewFingerprint(s0.vocabularyText)}\"\n"
        assertEquals(emptyList(), vocabularyErrors(signedL1))
        val frozen = edit(signedL1, "gHeld:   Bool ends", "gHeld:   Bool frozen")
        assertEquals(listOf("stale-review"), vocabularyErrors(frozen).map { it.code })
        val witness = edit(signedL1, "witness LockInfo.token", "witness LockInfo.expiresAt")
        assertEquals(listOf("stale-review"), vocabularyErrors(witness).map { it.code })
    }
}
