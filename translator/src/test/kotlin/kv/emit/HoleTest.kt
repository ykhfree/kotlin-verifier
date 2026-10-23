package kv.emit

import kv.sidecar.SidecarException
import kv.sidecar.SidecarLoader
import kv.sidecar.parseSidecar
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The hole split of DESIGN-v0.md 5.7: one conjunct per top-level `&&`, each named by its comment. A conjunct with
 * no comment of its own takes the nearest earlier comment.
 */
class HoleTest {
    private fun hole(body: String) = splitHole(parseSidecar("h.kv", "hole MI_INV \"\"\"$body\"\"\"\n").holes.single())

    @Test
    fun `the KeyLocalLock hole has five conjuncts, and the third takes the comment of the second`() {
        val s = SidecarLoader.load(File(specDir, "keylocallock.coroutine.kv"))
        val parts = splitHole(s.holes.single())
        assertEquals(5, parts.size)
        assertEquals("0 <= S.seq", parts[1].expr.text)
        assertEquals(listOf(), parts[2].ownComment)
        val counter = "The counter is never negative, and every issued token is between 0 and the counter."
        assertEquals(
            listOf(
                "INV-E: every entry is held, has no free permit, and carries a token.",
                counter,
                counter,
                "Each entry's token is a held backed token issued for this key, with the entry's lease.",
                "Each held backed token is the token of its key's entry.",
            ),
            parts.map { it.name },
        )
        // Positions are sidecar positions: `0 <= S.seq` is on its own line of keylocallock.common.kv.
        val lines = File(specDir, "keylocallock.common.kv").readLines()
        val line = lines.indexOfFirst { it.trim() == "0 <= S.seq &&" } + 1
        assertEquals(line to 3, parts[1].expr.pos.line to parts[1].expr.pos.col)
        assertEquals(line + 1 to 3, parts[2].expr.pos.line to parts[2].expr.pos.col)
        assertEquals("keylocallock.common.kv", File(parts[1].expr.pos.file).name)
    }

    @Test
    fun `&& inside brackets or comments does not split, and a first conjunct without comment has no name`() {
        val parts = hole("\n  (a && b) &&\n  // x && y\n  m[k && j] && f(c && d)\n")
        assertEquals(listOf("(a && b)", "m[k && j]", "f(c && d)"), parts.map { it.expr.text })
        assertEquals(listOf(null, "x && y", "x && y"), parts.map { it.name })
        assertNull(parts[0].name)
    }

    @Test
    fun `a top-level operator weaker than && keeps the hole in one piece`() {
        for (body in listOf("a && b ==> c", "a || b && c", "a ? b && c : d", "forall x: Int :: a(x) && b(x)", "a && b <==> c")) {
            val parts = hole(body)
            assertEquals(listOf(body), parts.map { it.expr.text }, body)
        }
    }

    @Test
    fun `an empty conjunct is a sidecar error at its position`() {
        val e = assertFailsWith<SidecarException> { hole("\n  a &&\n") }
        assertEquals("hole", e.errors.single().code)
        assertEquals(2, e.errors.single().pos.line)
    }
}
