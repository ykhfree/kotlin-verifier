package kv.emit

import kv.lower.bindGhosts
import kv.sidecar.SidecarException
import kv.sidecar.SidecarLoader
import kv.sidecar.parseCells
import kv.sidecar.pilotCells
import kv.spec.sidecarModule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The programs of `kv spec check` (DESIGN-C.md 3.2, 4.1) without Silicon: the contract-only model, built from the
 * code and from the sidecar alone, and the clause isolation runs. The coroutine KeyLocalLock with the current specs.
 */
class ContractOnlyTest {
    private val cells by lazy { parseCells(pilotCells.path, pilotCells.readText()) }
    private val code by lazy { lowerReqShield("coroutine").let { (m, s) -> bindGhosts(m, s) to s } }

    private fun contract(
        holeTrue: Boolean,
        assumed: Set<AssumedFact> = emptySet(),
    ) = code.let { (m, s) -> emitViper(m, s, contract = ContractOnly(cells, holeTrue, true, assumed)) }

    /** The lines of method [name] in [text]: from its header to the line before the next blank line. */
    private fun method(
        text: String,
        name: String,
    ): List<String> = text.lines().dropWhile { !it.startsWith("method $name(") }.takeWhile { it.isNotEmpty() }

    @Test
    fun `no method has a body, init states the empty map only, no stray, lemma or probe, a method per outcome and one reach method per cell`() {
        val e = contract(holeTrue = false)
        val kinds = e.methods.groupingBy { it.kind }.eachCount()
        val outcomes = cells.sumOf { it.outcomes.size }
        assertEquals(mapOf(MethodKind.INIT to 1, MethodKind.ACTION to 3, MethodKind.CELL to outcomes + cells.size), kinds)
        assertEquals(listOf("method init() returns (S: Ref)", "  ensures MI(S)", "  ensures S.m == Map[Key, Ref]()"), method(e.text, "init"))
        for (op in listOf("tryLock", "unLock", "sweep")) {
            val lines = method(e.text, op)
            assertTrue(lines.drop(1).all { it.startsWith("  requires") || it.startsWith("  ensures") || it.startsWith("  //") || it.startsWith("    ") }, "$op:\n${lines.joinToString("\n")}")
        }
        assertFalse("pre_tok" in e.text || "method probe" in e.text || "lemma_" in e.text || "S.seq ==" in e.text, e.text)
        // Mode a keeps the hole; the tool facts are there.
        assertTrue("define MI_INV_5(S)" in e.text && "tool fact ISSUE" in e.text && "tool fact FRAME" in e.text && "tool fact END" in e.text)
        val c11 = method(e.text, "cell_C11_out_false")
        assertEquals("method cell_C11_out_false(k: Key, x: Int)", c11[0])
        assertEquals(
            listOf(
                "  var S: Ref",
                "  S := init()",
                "  var t1: Int",
                "  t1 := tryLock(S, k, 0, 10, false, 0)",
                "  assume t1 != NULL && tok(t1).gBacked",
                "  assume x >= 0 && x != t1 && !tok(x).gIssued",
                "  var b: Bool",
                "  b := unLock(S, k, x)",
                "  assert !b",
                "}",
            ),
            c11.drop(2),
        )
        assertEquals("  refute false  // fails exactly when the end is unreachable", method(e.text, "cell_C11_reach").dropLast(1).last())
        assertEquals(listOf("cell C14 outcome stays", "cell C14 outcome dropped", "cell C14 reach"), e.methods.filter { it.name.startsWith("cell_C14_") }.map { it.label })
    }

    @Test
    fun `mode b - the hole is one conjunct true, the invariants stay`() {
        val e = contract(holeTrue = true)
        assertTrue("define MI_INV_1(S) (true)" in e.text, e.text)
        assertFalse("define MI_INV_2(S)" in e.text)
        assertTrue("define entry_lease(S)" in e.text && "  ensures entry_lease(S)" in e.text)
        assertEquals(3, e.text.split("  ensures MI_INV_1(S)").size - 1)
    }

    /**
     * C3 acceptance 3 by construction: the module from the sidecar alone gives every action the same contract as the
     * module from the code, except the type fact of unLock's token (the sidecar does not say it is non-null).
     */
    @Test
    fun `from the sidecar alone, every action contract is the code's, except the type fact`() {
        val (_, s) = code
        val alone = sidecarModule(s)
        assertEquals(
            listOf("free value now of tryLock has no type: an Int", "free value timeout of tryLock has no type: an Int", "result res of unLock has no issue rule: a Bool", "free value now of sweep has no type: an Int"),
            alone.assumptions,
        )
        val a = emitViper(alone.module, s, contract = ContractOnly(cells, holeTrue = true, fromCode = false))
        val b = contract(holeTrue = true)
        for (op in listOf("tryLock", "unLock", "sweep")) {
            val fromCode = method(b.text, op).filter { !it.trimStart().startsWith("//") }
            val fromSidecar = method(a.text, op).filter { !it.trimStart().startsWith("//") }
            assertEquals(fromCode.filter { "type fact" !in it }, fromSidecar, op)
        }
        assertEquals(listOf("  requires t != NULL  // type fact: token is a non-null Token"), method(b.text, "unLock").filter { "type fact" in it })
        // Everything before the action methods is the same text too, without the comments.
        fun rest(t: String) = t.lines().filter { !it.startsWith("//") }.joinToString("\n") { it.substringBefore("  //") }.substringBefore("method tryLock(")
        assertEquals(rest(b.text), rest(a.text))
        assertEquals(b.methods.map { it.name }, a.methods.map { it.name })
    }

    @Test
    fun `without a ghost record, the sidecar alone gives no module`() {
        val aspect = SidecarLoader.load(File(specDir, "aspect.core-spring.kv"))
        assertEquals("contract-only", assertFailsWith<SidecarException> { sidecarModule(aspect) }.errors.single().code)
    }

    @Test
    fun `isolation run j keeps the bodies, the tool ensures, the hole and clause j of each action`() {
        val (m, s) = code
        val full = emitViper(m, s)
        assertEquals(
            listOf(
                "tryLock" to "ends_only_if P3a", "tryLock" to "unbacked_only_if P3b", "tryLock" to "ensures P1a", "tryLock" to "ensures P1a_entry",
                "tryLock" to "ensures progress_free", "tryLock" to "ensures progress_live", "tryLock" to "ensures cap_unbacked",
                "tryLock" to "ensures expired_takeover", "tryLock" to "ensures refusal_keeps_entry",
                "unLock" to "ends_only_if P3a", "unLock" to "ensures P1", "unLock" to "ensures progress_owner",
                "sweep" to "ends_only_if P3a",
            ),
            full.clauses.map { it.action to it.name },
        )
        assertEquals((0..8).toList() + (0..2) + 0, full.clauses.map { it.index })
        val run1 = emitViper(m, s, onlyClause = 1)
        assertEquals(listOf("tryLock" to "unbacked_only_if P3b", "unLock" to "ensures P1"), run1.clauses.map { it.action to it.name })
        assertEquals(listOf("init", "tryLock", "unLock", "sweep"), run1.methods.map { it.name })
        val tryLock = method(run1.text, "tryLock")
        assertTrue(tryLock.any { it.startsWith("  ensures res != NULL && !tok(res).gBacked ==>") }, tryLock.joinToString("\n"))
        assertFalse(tryLock.any { "progress_live" in it || it.startsWith("  ensures old(has(S, k)) && old(S.m[k].isHeld)") })
        for (kept in listOf("ensures MI_INV_5(S)", "tool fact FRAME", "tool fact END", "tool fact ISSUE", "ensures HIST", "ensures entry_lease(S)")) {
            assertTrue(tryLock.any { kept in it }, kept)
        }
        assertTrue("pre_tok" in run1.text)
        // Each kept clause's range holds its whole ensures.
        fun inRange(c: EmittedClause) = run1.text.lines().subList(c.range.line - 1, c.range.endLine).joinToString("\n")
        assertTrue(inRange(run1.clauses[0]).endsWith("  ensures res != NULL && !tok(res).gBacked ==> (!old(has(S, k)) && rej && limit != 0)"), inRange(run1.clauses[0]))
        assertTrue(inRange(run1.clauses[1]).startsWith("  ensures res ==> old(has(S, k))") && inRange(run1.clauses[1]).endsWith("!tok(t).gHeld && !has(S, k)"), inRange(run1.clauses[1]))
        // sweep has one clause: run 1 keeps none of it.
        assertFalse(method(run1.text, "sweep").any { "now > old(tok(qt).gLease)" in it })
    }

    @Test
    fun `the measurement facts - WITNESS in MI and every action, the field frame after MI_OWN`() {
        val e = contract(holeTrue = true, assumed = setOf(AssumedFact.WITNESS, AssumedFact.FIELD_FRAME))
        assertTrue("define MI_WITNESS(S) (\n  forall qt: Int :: { tok(qt).gHeld } tok(qt).gIssued && tok(qt).gBacked && tok(qt).gHeld ==> carried(S, tok(qt).gKey, qt)\n)" in e.text, e.text)
        assertTrue("define MI(S) (MI_PERMS(S) && MI_OWN(S) && MI_WITNESS(S) && P2(S) && entry_lease(S) && MI_INV(S))" in e.text)
        assertEquals(3, e.text.split("  ensures MI_WITNESS(S)").size - 1)
        val frame = "  ensures forall qk: Key :: { S.m[qk] } qk != k && old(has(S, qk)) && has(S, qk) ==> S.m[qk].permits == old(S.m[qk].permits) && S.m[qk].isHeld == old(S.m[qk].isHeld) && " +
            "S.m[qk].token == old(S.m[qk].token) && S.m[qk].expiresAt == old(S.m[qk].expiresAt)"
        assertEquals(3, e.text.split(frame).size - 1)
        assertFalse("MI_WITNESS" in contract(holeTrue = true).text || "MI_WITNESS" in code.let { (m, s) -> emitViper(m, s).text })
    }
}
