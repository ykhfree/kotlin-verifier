package kv.sidecar

import kv.spec.pilotDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The pilot draft [name] (haiku, sonnet, opus) with its source comments written as source lines (C0 fixture). */
fun draftFixture(name: String): String = SourceLineTest::class.java.getResource("/spec/drafts/$name.kv")?.readText() ?: error("missing fixture spec/drafts/$name.kv")

/** The quoted source lines of [s], in file order, as (anchor, quote). */
fun quotes(s: Sidecar): List<Pair<String, String>> = s.intentClauses().flatMap { (_, c) -> c.sources.filterIsInstance<SourceQuote>().map { it.anchor to it.quote } }

/** DESIGN-C.md 5.2 and 7.2: the source line after an intent clause, and the C0 fixture of the pilot drafts. */
class SourceLineTest {
    private fun parse(text: String) = parseSidecar("t.kv", text.trimIndent())

    private fun syntaxError(text: String): SidecarError {
        val e = assertFailsWith<SidecarException> { parse(text) }
        return e.errors.single().also { assertEquals("syntax", it.code) }
    }

    @Test
    fun `source lines follow each kind of intent clause, and source FILE stays the module's source declaration`() {
        val s =
            parse(
                """
                module a.C
                source "C.kt"
                operation op = fun C.op {
                  result res
                  issue res { gKey = k }
                    source "README.md#a/1" quote "one" sha256 "0123456789abcdef"
                    source none "no text says it"
                  ensures E: res != NULL ==>
                      res == res
                    source "README.md#a/2" quote "two  words"
                  ends_only_if P: true
                  source "R#b" quote "three"
                }
                invariant I: forall x: Int :: x == x
                  source "R#c" quote "four"
                source "D.kt"
                lemma L: true
                  source none "derived"
                probe(a: Int) {
                  assert a == a
                }
                  source "R#d" quote "five"
                """,
            )
        assertEquals(listOf("C.kt", "D.kt"), s.sources.map { it.path })
        val (issue, ensures, allowance) = s.operations.single().items.filterIsInstance<ReviewedItem>()
        assertEquals(
            listOf(
                SourceQuote("README.md#a/1", "one", "0123456789abcdef", SPos("t.kv", 6, 5), "source \"README.md#a/1\" quote \"one\" sha256 \"0123456789abcdef\""),
                SourceNone("no text says it", SPos("t.kv", 7, 5), "source none \"no text says it\""),
            ),
            issue.sources,
        )
        // The expression goes on over the deeper line, and stops at the source line, though that line is deeper too.
        assertEquals("res != NULL ==>\n      res == res", (ensures as EnsuresItem).expr.text)
        assertEquals("ensures E: res != NULL ==>\n      res == res", ensures.unit)
        assertEquals(listOf("two  words"), ensures.sources.map { (it as SourceQuote).quote })
        assertEquals(listOf(SPos("t.kv", 12, 3)), allowance.sources.map { it.pos })
        assertEquals(
            listOf(
                "the issue rule of op" to 2,
                "ensures E of op" to 1,
                "ends_only_if P of op" to 1,
                "invariant I" to 1,
                "lemma L" to 1,
                "the probe" to 1,
            ),
            s.intentClauses().map { (what, c) -> what to c.sources.size },
        )
        assertEquals("forall x: Int :: x == x", s.properties[0].expr.text)
        assertEquals("probe(a: Int) {\n  assert a == a\n}", s.probes.single().unit)
        assertEquals("probe(a: Int) {\n  assert a == a\n}\nsource \"R#d\" quote \"five\"", s.probes.single().reviewedText)
    }

    @Test
    fun `a clause without source lines has its text as the reviewed text`() {
        val s = parse("operation op = fun C.op {\n  ends_only_if P: qt == t\n  reviewed \"read\"\n}")
        val p = s.operations.single().items.single() as AllowanceItem
        assertEquals("ends_only_if P: qt == t", p.reviewedText)
        assertEquals(p.unit, p.reviewedText)
    }

    @Test
    fun `a source line where no intent clause is before it is an error`() {
        val misplaced = "a source line must follow an issue rule, an ensures, an allowance clause, an invariant, a lemma, a probe, an assume or a set-once"
        val afterFree = syntaxError("operation op = fun C.op {\n  free now = local now\n  source \"R#a\" quote \"x\"\n}")
        assertEquals(3 to 3, afterFree.pos.line to afterFree.pos.col)
        assertTrue(misplaced in afterFree.message, afterFree.message)
        val afterType = syntaxError("type Key = opaque\nsource \"R#a\" quote \"x\"")
        assertEquals(2 to 1, afterType.pos.line to afterType.pos.col)
        assertTrue(misplaced in afterType.message, afterType.message)
        assertTrue(misplaced in syntaxError("hole MI_INV \"\"\"true\"\"\"\nsource none \"x\"").message)
        // A source line belongs to the clause the reviewed note signs, so it comes first.
        val afterNote = syntaxError("operation op = fun C.op {\n  ends_only_if P: true\n  reviewed \"read\"\n  source \"R#a\" quote \"x\"\n}")
        assertEquals(4 to 3, afterNote.pos.line to afterNote.pos.col)
        assertTrue("comes before the 'reviewed' note" in afterNote.message, afterNote.message)
    }

    @Test
    fun `a malformed source line is a syntax error at the bad part`() {
        val noQuote = syntaxError("operation op = fun C.op {\n  ends_only_if P: true\n  source \"R#a\" \"x\"\n}")
        assertEquals(3 to 16, noQuote.pos.line to noQuote.pos.col)
        assertTrue("'quote'" in noQuote.message, noQuote.message)
        val empty = syntaxError("operation op = fun C.op {\n  ends_only_if P: true\n  source \"R#a\" quote \"  \"\n}")
        assertEquals(3 to 22, empty.pos.line to empty.pos.col)
        assertTrue("a quote must not be empty" in empty.message, empty.message)
        assertTrue("expected a string" in syntaxError("invariant I: true\n  source none reason").message)
    }

    @Test
    fun `the pilot drafts parse with their 114 quotes as source lines, the quotes of their source comments`() {
        val ref = Regex("""\[([^\]\n]+?)\]\s*"([^"\n]+)"""")
        val counts =
            listOf("haiku", "sonnet", "opus").map { name ->
                val s = parseSidecar("$name.kv", draftFixture(name))
                val draft = File(pilotDir, "frozen-final/D-$name/DRAFT.kv").readText()
                // tracecheck.py's reading of the draft: every [ANCHOR] "QUOTE" whose anchor holds '#'.
                val comments = ref.findAll(draft).map { it.groupValues[1] to it.groupValues[2] }.filter { '#' in it.first }.toList()
                assertEquals(comments, quotes(s), name)
                val none = Regex("""//\s*source:\s*none""").findAll(draft).count()
                assertEquals(none, s.intentClauses().sumOf { (_, c) -> c.sources.count { it is SourceNone } }, name)
                quotes(s).size
            }
        assertEquals(listOf(28, 36, 50), counts)
    }
}
