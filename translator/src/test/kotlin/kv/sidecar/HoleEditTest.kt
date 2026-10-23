package kv.sidecar

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The hole checker (DESIGN-v0.md 8.3, M7 edit surface) with the rules of spike/e3/e3_harness.py, ported from the
 * Viper hole markers to the sidecar's `hole MI_INV """..."""` text: the edit may change only the hole text, and the
 * hole may hold no forbidden word outside comments. Every rejection names its position in the edited file.
 */
class HoleEditTest {
    private val common = File(System.getProperty("kv.specDir"), "reqshield/keylocallock.common.kv").readText()
    private val skeleton = holeSkeleton("before.kv", common)

    /** The reasons to reject [after] as an edit of the skeleton. */
    private fun check(after: String) = checkHoleEdit("before.kv", skeleton, "after.kv", after)

    /** The skeleton with its hole text replaced by [hole]. */
    private fun withHole(hole: String) = skeleton.replace("\"\"\"$SKELETON_HOLE\"\"\"", "\"\"\"$hole\"\"\"").also { check(it != skeleton) }

    /** 1-based line and column of the first [fragment] in [text]. */
    private fun at(
        text: String,
        fragment: String,
    ): Pair<Int, Int> {
        val lines = text.lines()
        val n = lines.indexOfFirst { fragment in it }
        return n + 1 to lines[n].indexOf(fragment) + 1
    }

    @Test
    fun `the skeleton differs from the sidecar only in its hole, which is true`() {
        assertEquals(SKELETON_HOLE, parseSidecar("s.kv", skeleton).holes.single().text)
        val hole = parseSidecar("c.kv", common).holes.single().text
        assertEquals(common, skeleton.replace("\"\"\"$SKELETON_HOLE\"\"\"", "\"\"\"$hole\"\"\""))
    }

    @Test
    fun `an edit of the hole text only is accepted, the section 4_4 hole included`() {
        assertEquals(emptyList(), check(common))
        assertEquals(emptyList(), check(withHole("\n  0 <= S.seq && (forall qk: Key :: { S.m[qk] } has(S, qk) ==> S.m[qk].permits == 0)\n")))
    }

    @Test
    fun `an edit before or after the hole is rejected at the first changed character`() {
        val before = common.replace("ensures progress_owner:", "ensures progress_owner_x:")
        val e = check(before).single()
        assertEquals("hole-edit", e.code)
        assertEquals("after.kv" to at(before, "progress_owner_x").let { (l, c) -> l to c + "progress_owner".length }, e.pos.file to (e.pos.line to e.pos.col))

        val after = common.replace("  assert !ok2\n", "  assert !ok2\n  assert true\n")
        val e2 = check(after).single()
        assertEquals("hole-edit", e2.code)
        // The old tail goes on with the probe's closing brace, the new one with the added line.
        assertEquals(at(after, "assert true").first to 1, e2.pos.line to e2.pos.col)
    }

    @Test
    fun `whitespace next to the triple quotes is outside the hole`() {
        val e = check(skeleton.replace("hole MI_INV \"\"\"", "hole MI_INV  \"\"\"")).single()
        assertEquals("hole-edit", e.code)
    }

    @Test
    fun `each forbidden word in the hole is rejected at its position`() {
        val words =
            mapOf(
                "assume" to "assume S.seq >= 0",
                "inhale" to "inhale MI_PERMS(S)",
                "exhale" to "exhale MI_PERMS(S)",
                "refute" to "refute false",
                "import" to "import \"x.vpr\"",
                "method" to "method m() {}",
                "function" to "function f(): Int",
                "predicate" to "predicate p(x: Ref)",
                "axiom" to "axiom a { true }",
                "domain D {" to "domain D { }",
                "field f:" to "field f: Int",
            )
        for ((word, text) in words) {
            val edited = withHole("\n  true &&\n  $text\n")
            val e = check(edited).singleOrNull() ?: error("$word: ${check(edited)}")
            assertEquals("hole-forbidden", e.code, word)
            assertTrue("'$word'" in e.message, e.message)
            assertEquals(at(edited, text), e.pos.line to e.pos.col, word)
        }
    }

    @Test
    fun `a forbidden word in a comment, or inside a longer name, is allowed`() {
        assertEquals(emptyList(), check(withHole("\n  // do not assume or inhale anything here\n  true\n")))
        assertEquals(emptyList(), check(withHole("\n  forall assumed: Int :: { tok(assumed).gHeld } tok(assumed).gHeld ==> fieldwise(assumed)\n")))
    }

    @Test
    fun `a hole that ends early, or a second hole, is rejected`() {
        val early = withHole("\n  true\"\"\" && \"\"\"x\n")
        assertTrue(check(early).isNotEmpty())
        val two = check(common + "hole MI_INV \"\"\" true \"\"\"\n").single()
        assertEquals("hole-edit" to "after.kv", two.code to two.pos.file, two.toString())
        assertTrue("2 'hole MI_INV' declarations" in two.message, two.message)
    }

    @Test
    fun `the file before the edit must hold exactly one hole`() {
        val e = assertFailsWith<SidecarException> { checkHoleEdit("before.kv", "module a.B\n", "after.kv", "module a.B\n") }.errors.single()
        assertEquals("hole-edit", e.code)
        assertTrue("0 'hole MI_INV' declarations" in e.message, e.message)
    }
}
