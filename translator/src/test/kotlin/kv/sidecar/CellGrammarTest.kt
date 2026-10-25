package kv.sidecar

import kv.emit.specDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The cell file fixture of the 19 formal KeyLocalLock pilot cells (DESIGN-C.md 3.2), from the test classpath. */
val pilotCells = File(CellGrammarTest::class.java.getResource("/spec/cells/keylocallock.cells")?.toURI() ?: error("missing fixture spec/cells/keylocallock.cells"))

/** The cell file of `kv spec check --cells` (DESIGN-C.md 3.2): its grammar and its checks against a sidecar. */
class CellGrammarTest {
    private val sidecar by lazy { SidecarLoader.load(File(specDir, "keylocallock.coroutine.kv")) }

    private fun cells(text: String) = parseCells("t.cells", text.trimIndent())

    private fun check(text: String) = checkCells(sidecar, cells(text))

    @Test
    fun `the pilot fixture has the 19 formal cells, C09 dropped, and passes the checks against the current sidecar`() {
        val cs = parseCells(pilotCells.path, pilotCells.readText())
        assertEquals((1..20).filter { it != 9 }.map { "C%02d".format(it) }, cs.map { it.id.text })
        assertEquals(emptyList(), checkCells(sidecar, cs))
        val c11 = cs.single { it.id.text == "C11" }
        assertEquals(listOf("k" to "Key", "x" to "Token"), c11.params.map { it.name.text to it.type.text })
        assertEquals("Acquired at time 0: unLock(k, x) with a token x that was never issued", c11.title)
        assertEquals(listOf("ProbeCall", "CellAssume", "CellAssume", "ProbeCall"), c11.steps.map { it::class.simpleName })
        assertEquals("x >= 0 && x != t1 && !tok(x).gIssued", (c11.steps[2] as CellAssume).expr.text)
        assertEquals(listOf("true" to "b", "false" to "!b"), c11.outcomes.map { it.name.text to it.expr.text })
        val c14 = cs.single { it.id.text == "C14" }
        val sweep = c14.steps[2] as ProbeCall
        assertEquals(emptyList(), sweep.outs)
        assertEquals("sweep", sweep.op.text)
    }

    @Test
    fun `a cell without a title, an outcome over two lines, and positions`() {
        val cs =
            cells(
                """
                // a comment
                cell X1(k: Key) {
                  r := tryLock(k, now = 0, timeout = 10, rej = false, limit = 0)
                  outcome token: r != NULL &&
                      tok(r).gBacked
                }
                """,
            )
        val c = cs.single()
        assertNull(c.title)
        assertEquals(SPos("t.cells", 2, 1), c.pos)
        assertEquals("r != NULL &&\n      tok(r).gBacked", c.outcomes.single().expr.text)
        assertEquals(SPos("t.cells", 4, 18), c.outcomes.single().expr.pos)
    }

    @Test
    fun `syntax errors - a step after an outcome, an assert, a missing brace`() {
        fun syntax(text: String) = assertFailsWith<SidecarException> { cells(text) }.errors.single()

        val late = syntax("cell X(k: Key) {\n  outcome a: true\n  r := tryLock(k, now = 0, timeout = 1, rej = false, limit = 0)\n}")
        assertEquals(3, late.pos.line)
        assertTrue("a step after an outcome" in late.message, late.message)
        // `assert` is a probe step, not a cell step: a cell decides nothing by itself, its outcomes are the questions.
        assertTrue("expected ':='" in syntax("cell X(k: Key) {\n  assert true\n}").message)
        assertTrue("expected '{'" in syntax("cell X(k: Key) outcome a: true }").message)
        assertTrue("expected 'cell'" in syntax("probe X(k: Key) {}").message)
    }

    @Test
    fun `checks - names, operations and arguments as in a probe, and outcomes`() {
        fun codes(text: String) = check(text).map { it.code to it.pos.line }

        assertEquals(emptyList(), codes("cell X(k: Key) {\n  sweep(k, now = 1)\n  outcome a: has(S, k)\n}"))
        assertEquals(listOf("unknown-name" to 2), codes("cell X(k: Key) {\n  r := lock(k, now = 1)\n  outcome a: true\n}"))
        assertEquals(listOf("probe-args" to 2), codes("cell X(k: Key) {\n  sweep(k)\n  outcome a: true\n}"))
        assertEquals(listOf("cell-outcomes" to 1), codes("cell X(k: Key) {\n  sweep(k, now = 1)\n}"))
        assertEquals(listOf("duplicate-decl" to 4), codes("cell X(k: Key) {\n  sweep(k, now = 1)\n  outcome a: true\n  outcome a: false\n}"))
        assertEquals(listOf("duplicate-decl" to 4), codes("cell X(k: Key) {\n  outcome a: true\n}\ncell X(k: Key) {\n  outcome a: true\n}"))
        assertEquals(listOf("unknown-name" to 1), codes("cell X(k: Lock) {\n  outcome a: true\n}"))
        val twice = check("cell X(k: Key) {\n  b := unLock(k, t = 1)\n  b := unLock(k, t = 1)\n  outcome a: b\n}").single()
        assertEquals("name-clash" to 3, twice.code to twice.pos.line)
        assertTrue(twice.message.startsWith("cell X name 'b' is declared twice"), twice.message)
        val field = check("cell X(k: Key, expiresAt: Int) {\n  outcome a: true\n}").single()
        assertTrue(field.message.startsWith("cell X name 'expiresAt' clashes with field of LockInfo"), field.message)
    }
}
