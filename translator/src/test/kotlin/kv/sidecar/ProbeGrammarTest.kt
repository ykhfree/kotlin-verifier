package kv.sidecar

import kv.spec.pilotDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C1 (DESIGN-C.md 7.2, 8): named probes, a call without a result, and `reviewed` notes after a probe, an `ensures`,
 * an `invariant` and a `lemma`; the opt-in policy `review intent` (10 UC4).
 */
class ProbeGrammarTest {
    private fun parse(text: String) = parseSidecar("t.kv", text.trimIndent())

    private fun syntaxError(text: String): SidecarError {
        val e = assertFailsWith<SidecarException> { parse(text) }
        return e.errors.single().also { assertEquals("syntax", it.code) }
    }

    @Test
    fun `the haiku pilot's first probe, with sweep called without a result, parses`() {
        val draft = File(pilotDir, "frozen-phase1/D-haiku/DRAFT.kv").readText()
        val probe = draft.substring(draft.indexOf("probe(key: Key"))
        val s = parseSidecar("haiku.kv", probe)
        val calls = s.probes.single().steps.filterIsInstance<ProbeCall>()
        assertEquals(listOf("tryLock", "tryLock", "sweep", "unLock"), calls.map { it.op.text })
        val sweep = calls[2]
        assertEquals(emptyList(), sweep.outs)
        assertEquals(listOf(null to "key", "now" to "a + 3000"), sweep.args.map { it.name?.text to it.value.text })
        assertEquals(SPos("haiku.kv", 5, 3), sweep.pos)
        assertNull(s.probes.single().name)
    }

    @Test
    fun `a sidecar may have many named probes, each with source lines and a note`() {
        val s =
            parse(
                """
                probe first(a: Int) requires a > 0 {
                  x := op(k1, now = a)
                  assert x
                }
                  source "R#a" quote "one"
                  reviewed "p1" sha256 "0123456789abcdef"
                probe second(a: Int) {
                  op2(k1)
                }
                probe(a: Int) {
                  assert true
                }
                """,
            )
        assertEquals(listOf("first", "second", null), s.probes.map { it.name?.text })
        assertEquals(listOf("probe first", "probe second", "probe"), s.probes.map { it.label })
        assertEquals(listOf("probe_first", "probe_second", "probe"), s.probes.map { it.method })
        val first = s.probes[0]
        assertEquals("p1" to "0123456789abcdef", first.reviewed to first.sha256)
        assertEquals(SPos("t.kv", 6, 3), first.reviewPos)
        assertEquals("probe first(a: Int) requires a > 0 {\n  x := op(k1, now = a)\n  assert x\n}\nsource \"R#a\" quote \"one\"", first.reviewedText)
        assertEquals(listOf("op2"), s.probes[1].steps.filterIsInstance<ProbeCall>().map { it.op.text })
    }

    @Test
    fun `an ensures, an invariant and a lemma take a note, and the note ends a multi-line expression at any indent`() {
        val s =
            parse(
                """
                operation op = fun C.op {
                  ensures E: true
                    source none "derived"
                  reviewed "e"
                }
                invariant I: forall x: Int ::
                    x == x
                  reviewed "i" sha256 "00000000000000aa"
                lemma L: true
                  source "R#b" quote "two"
                reviewed "l"
                """,
            )
        val e = s.operations.single().items.single() as EnsuresItem
        assertEquals("e" to SPos("t.kv", 4, 3), e.reviewed to e.reviewPos)
        val (i, l) = s.properties
        assertEquals("forall x: Int ::\n    x == x", i.expr.text)
        assertEquals("i" to "00000000000000aa", i.reviewed to i.sha256)
        assertEquals("l" to 1, l.reviewed to l.sources.size)
    }

    @Test
    fun `a second note, or a note after nothing to sign, is a syntax error`() {
        assertTrue("is already reviewed" in syntaxError("operation op = fun C.op {\n  ensures E: true\n  reviewed \"a\"\n  reviewed \"b\"\n}").message)
        val twice = syntaxError("invariant I: true\nreviewed \"a\"\nreviewed \"b\"")
        assertEquals(3 to 1, twice.pos.line to twice.pos.col)
        assertTrue("'reviewed' must follow" in twice.message, twice.message)
        assertTrue("'reviewed' must follow" in syntaxError("type Key = opaque\nreviewed \"a\"").message)
        assertTrue("'intent'" in syntaxError("review intents").message)
    }

    private fun check(text: String) = checkSidecar(parse(text))

    /** A small sidecar with `review intent` and one clause of each kind; [extra] goes before the hole. */
    private fun intent(
        op: String,
        extra: String = "",
    ) = """
        |module a.C
        |source "C.kt"
        |review intent
        |type Key = opaque
        |state m: Map<Key, Info> = lockMap
        |class Info = C.Info {
        |  isHeld: AtomicBoolean = isHeld
        |}
        |operation op = fun C.op {
        |$op
        |}
        |$extra
        |hole MI_INV ${"\"\"\""}true${"\"\"\""}
    """.trimMargin()

    @Test
    fun `under review intent every intent clause needs a note and a source line, and source none counts`() {
        val errors = check(intent("  ensures E: true", "invariant I: true\n  source none \"derived\"\n  reviewed \"i\" sha256 \"${sha16("invariant I: true source none \"derived\"")}\""))
        assertEquals(listOf("unreviewed" to 10, "unsourced" to 10), errors.map { it.code to it.pos.line }, errors.joinToString("\n"))
        assertTrue(errors[0].message.startsWith("ensures E of op needs a 'reviewed' note: under 'review intent'"), errors[0].message)
        assertTrue(errors[1].message.startsWith("ensures E of op has no source line"), errors[1].message)
        // Signed and sourced: no error.
        val ok = "  ensures E: true\n    source \"R#a\" quote \"q\"\n  reviewed \"e\" sha256 \"${sha16("ensures E: true source \"R#a\" quote \"q\"")}\""
        assertEquals(emptyList(), check(intent(ok)))
        // A probe and a lemma too.
        val probe = check(intent(ok, "probe p(a: Int) {\n  assert true\n}\nlemma L: true\n  reviewed \"l\" sha256 \"${sha16("lemma L: true")}\""))
        assertEquals(listOf("unreviewed" to "probe p", "unsourced" to "probe p", "unsourced" to "lemma L"), probe.map { it.code to it.message.substringBefore(" needs").substringBefore(" has no") })
    }

    @Test
    fun `without review intent an unsigned ensures, invariant, lemma or probe is no error, but a note's fingerprint is checked`() {
        val plain = intent("  ensures E: true", "invariant I: true\nprobe p(a: Int) {\n  assert true\n}").replace("review intent\n", "")
        assertEquals(emptyList(), check(plain))
        val stale = check(plain.replace("invariant I: true", "invariant I: true\n  reviewed \"i\" sha256 \"0000000000000000\""))
        assertEquals(listOf("stale-review"), stale.map { it.code })
        assertTrue("invariant I changed after its review" in stale.single().message && sha16("invariant I: true") in stale.single().message, stale.single().message)
        val probeNote = check(plain.replace("  assert true\n}", "  assert true\n}\n  reviewed \"p\""))
        assertEquals(listOf("missing-fingerprint"), probeNote.map { it.code })
        assertTrue(sha16("probe p(a: Int) { assert true }") in probeNote.single().message, probeNote.single().message)
    }

    @Test
    fun `two probes with one name, or two unnamed probes, are an error`() {
        val probes = "probe p(a: Int) {\n  assert true\n}\nprobe p(b: Int) {\n  assert true\n}\nprobe(c: Int) {\n  assert true\n}\nprobe(d: Int) {\n  assert true\n}"
        val errors = check(intent("  ensures E: true", probes).replace("review intent\n", ""))
        assertEquals(listOf("duplicate-decl" to (14 to 7), "duplicate-decl" to (20 to 1)), errors.map { it.code to (it.pos.line to it.pos.col) }, errors.joinToString("\n"))
        assertTrue("a second probe p" in errors[0].message && "a second unnamed probe" in errors[1].message)
    }
}
