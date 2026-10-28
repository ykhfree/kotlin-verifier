package kv.sidecar

import kv.spec.Block
import kv.spec.staleCheck
import kv.verify.specToReview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * DESIGN-C.md 10.14 (JG5): a top-level `set-once` is an intent clause. It takes source lines and a `reviewed` note
 * like an `assume`; under `review intent` it needs both.
 */
class SetOnceReviewTest {
    private fun parse(text: String) = parseSidecar("t.kv", text)

    private fun check(
        text: String,
        draft: Boolean = false,
    ) = checkSidecar(parse(text), draft)

    /** The fingerprint of `set-once P: m` with the source line [source] (DESIGN-C.md 5.2). */
    private fun sha(source: String = "source none \"x\"") = sha16("set-once P: m $source")

    /**
     * A small L1 sidecar with one set-once; [setOnce] replaces its declaration. The vocabulary note signs `type Key` and
     * `class Info` (DESIGN-E 12.3), so only the set-once can give an error.
     */
    private fun sidecar(
        setOnce: String,
        intent: Boolean = true,
    ) = """
        |module a.C
        |source "C.kt"
        |${if (intent) "review intent" else ""}
        |type Key = opaque
        |state m: Map<Key, Info> = lockMap
        |$setOnce
        |class Info = C.Info { }
        |hole MI_INV ${"\"\"\""}true${"\"\"\""}
        |review vocabulary
        |  reviewed "v" sha256 "${sha16("type Key = opaque class Info = C.Info { }")}"
    """.trimMargin()

    private val signed = "set-once P: m   // a mapped key keeps its value\n  source none \"x\"\n  reviewed \"p\" sha256 \"${sha()}\""

    @Test
    fun `a set-once takes source lines and a note, and its unit stops before the end-of-line comment`() {
        val d = parse(sidecar(signed)).setOnce.single()
        assertEquals("set-once P: m", d.unit)
        assertEquals(listOf("source none \"x\""), d.sources.map { it.text })
        assertEquals("p" to sha(), d.reviewed to d.sha256)
        assertEquals(8 to 3, d.reviewPos!!.line to d.reviewPos!!.col)
        assertEquals(listOf("set-once P"), parse(sidecar(signed)).intentClauses().map { it.first })
    }

    @Test
    fun `a module source line after a set-once stays a module source, and a second note is a syntax error`() {
        val s = parse("module a.C\nstate m: Map<Key, Info> = lockMap\nset-once P: m\nsource \"D.kt\"\n")
        assertEquals(emptyList(), s.setOnce.single().sources)
        assertEquals(listOf("D.kt"), s.sources.map { it.path })
        val e = assertFailsWith<SidecarException> { parse(sidecar("$signed\n  reviewed \"again\"")) }
        assertEquals("syntax", e.errors.single().code)
    }

    @Test
    fun `under review intent a bare set-once needs a note and a source line, and a draft still needs the source line`() {
        val bare = sidecar("set-once P: m")
        val errors = check(bare)
        assertEquals(listOf("unreviewed" to 6, "unsourced" to 6), errors.map { it.code to it.pos.line }, errors.joinToString("\n"))
        assertTrue(errors[0].message.startsWith("set-once P needs a 'reviewed' note: under 'review intent'"), errors[0].message)
        assertEquals(listOf("unsourced"), check(bare, draft = true).map { it.code })
        // Without review intent a bare set-once is no error (the L1 sidecars before DESIGN-C.md 10.14).
        assertEquals(emptyList(), check(sidecar("set-once P: m", intent = false)))
    }

    @Test
    fun `a signed and sourced set-once is no error and is no vocabulary`() {
        assertEquals(emptyList(), check(sidecar(signed)))
        assertEquals(listOf("type Key", "class Info"), parse(sidecar(signed)).vocabulary.map { it.name })
    }

    @Test
    fun `a set-once note's fingerprint is checked, with or without review intent`() {
        val wrong = signed.replace(sha(), "0000000000000000")
        assertEquals(listOf("stale-review"), check(sidecar(wrong)).map { it.code })
        assertEquals(listOf("stale-review"), check(sidecar(wrong, intent = false)).map { it.code })
        assertEquals(listOf("stale-review"), check(sidecar(wrong), draft = true).map { it.code })
        val stale = check(sidecar(wrong)).single()
        assertTrue("set-once P changed after its review" in stale.message && sha() in stale.message, stale.message)
        // Renamed after signing: the note no longer matches.
        assertEquals(listOf("stale-review"), check(sidecar(signed.replace("set-once P: m", "set-once Q: m"))).map { it.code })
        val noSha = signed.replace(" sha256 \"${sha()}\"", "")
        assertEquals(listOf("missing-fingerprint"), check(sidecar(noSha)).map { it.code })
        assertEquals(emptyList(), check(sidecar(noSha), draft = true))
    }

    @Test
    fun `under review intent a quoted source line of a set-once needs its block fingerprint`() {
        val source = "source \"R#a\" quote \"q\""
        val errors = check(sidecar("set-once P: m\n  $source\n  reviewed \"p\" sha256 \"${sha(source)}\""))
        assertEquals(listOf("missing-block-fingerprint"), errors.map { it.code }, errors.joinToString("\n"))
        assertTrue(errors.single().message.startsWith("set-once P: the source line of R#a"), errors.single().message)
    }

    @Test
    fun `the report lists a set-once once, as an intent clause under review intent`() {
        assertEquals("intent clauses (review intent): set-once P", specToReview(parse(sidecar(signed)), null))
        assertEquals("set-once P: m", specToReview(parse(sidecar(signed, intent = false)), null))
    }

    @Test
    fun `kv spec stale follows the quote of a set-once`() {
        val source = "source \"R#a\" quote \"one value per key\" sha256 \"00000000000000aa\""
        val s = parse(sidecar("set-once P: m\n  $source\n  reviewed \"p\" sha256 \"${sha(source)}\""))
        fun block(text: String) = Block("R#a", "R", "R.md", 1, 1, text, "00000000000000aa")
        assertEquals(emptyList(), staleCheck(s, listOf(block("We keep one value per key."))).findings)
        val gone = staleCheck(s, listOf(block("We keep many values per key."))).findings.single()
        assertEquals("stale-source", gone.code)
        assertTrue(gone.message.startsWith("set-once P (6:1)"), gone.message)
    }
}
