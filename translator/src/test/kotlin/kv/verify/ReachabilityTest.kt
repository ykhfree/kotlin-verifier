package kv.verify

import kv.emit.Emitted
import kv.emit.Workspace
import kv.emit.emitViper
import kv.emit.lowerReqShield
import kv.lower.bindGhosts
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M9 (DESIGN-v0.md 5.4 item 4, section 7): the branch reachability clone, how its Silicon run is read (fake runs),
 * and, with Silicon, the acceptance test: on the coroutine variant the unreachable branches are exactly the CAS
 * failures at 161:49, 213:44 and 104:49. The result is INFO and changes no verdict. The Silicon tests fail, not
 * skip, without Silicon.
 */
class ReachabilityTest {
    @TempDir
    lateinit var dir: File

    private val budget = SiliconBudget()
    private val banner = "Silicon 1.1-SNAPSHOT (6b540aa4@(detached))\n"

    private fun clone(variant: String): Emitted = lowerReqShield(variant).let { (m, s) -> emitViper(bindGhosts(m, s), s, reach = true) }

    /** A Silicon run that refutes the probes on [lines] and reports [others] as further error lines. */
    private fun refuting(
        lines: List<Int>,
        others: List<String> = emptyList(),
    ): SiliconRun {
        val refutes = lines.map { "Refute holds in all cases or could not be reached (e.g. see Silicon `numberOfErrorsToReport`). Assertion false definitely holds. (r.vpr@$it.7--$it.11)" }
        val all = refutes + others
        val text = all.withIndex().joinToString("") { (i, e) -> "  [$i] $e\n\n" }
        return SiliconRun(1, banner + "Silicon found ${all.size} errors in 4.1s:\n" + text, 4.1)
    }

    @Test
    fun `the clone opens each branch with refute false and has no postconditions, init, lemma or probe`() {
        val c = clone("coroutine")
        assertEquals(listOf("tryLock", "unLock", "sweep"), c.methods.map { it.name })
        val text = c.text
        assertTrue(text.lines().none { it.trim().startsWith("ensures") } && "method init" !in text && "method probe" !in text && "method lemma" !in text, text)
        assertEquals(c.branches.size, text.lines().count { it.trim().startsWith("refute false") })
        for (b in c.branches) assertTrue(text.lines()[b.line - 1].trim().startsWith("refute false"), "probe ${b.what} at Viper line ${b.line}")
        // The CAS at 161:49 is a branch (its result is read); the tryAcquire at 189:17 is not (its result is not).
        assertEquals(listOf("compareAndSet succeeds", "compareAndSet fails"), c.branches.filter { it.pos.line == 161 && it.pos.col == 49 }.map { it.what })
        assertEquals(emptyList(), c.branches.filter { it.pos.line == 189 })
        // The verified program has no probe.
        val (m, s) = lowerReqShield("coroutine")
        assertTrue("refute" !in emitViper(bindGhosts(m, s), s).text)
    }

    @Test
    fun `each refute error marks its probe unreachable, and a clean run marks none`() {
        val c = clone("coroutine")
        val fails = c.branches.filter { it.what == "compareAndSet fails" }
        val r = reachability(c, refuting(fails.map { it.line }), budget)
        assertNull(r.notChecked)
        assertEquals(c.branches.size, r.probed)
        assertEquals(fails, r.unreachable)
        val clean = reachability(c, SiliconRun(0, banner + "Silicon finished verification successfully in 4.0s.\n", 4.0), budget)
        assertEquals(Reachability(c.branches.size, emptyList()), clean)
    }

    @Test
    fun `a run without an answer leaves the check unanswered, never all reachable`() {
        val c = clone("core")
        val probe = c.branches.first().line
        assertTrue("wall clock" in reachability(c, SiliconRun(null, banner, 300.0), budget).notChecked!!)
        assertTrue("no branch probe" in reachability(c, refuting(listOf(1)), budget).notChecked!!)
        val parse = "Parse error: Expected (parens | atom):3:1, found \"{\". (r.vpr@3.1--3.1)"
        assertTrue("parse" in reachability(c, refuting(listOf(probe), listOf(parse)), budget).notChecked!!)
        val canceled = SiliconRun(1, banner + "Silicon found 1 error in 4s:\n  [0] Assert might fail. Assertion x might not hold. (r.vpr@9.1--9.2)\nPotential prover incompleteness: canceled\n", 4.0)
        assertTrue("canceled" in reachability(c, canceled, budget).notChecked!!)
    }

    @Test
    fun `another verification error is kept as a note next to the unreachable branches`() {
        val c = clone("coroutine")
        val probe = c.branches.first()
        val r = reachability(c, refuting(listOf(probe.line), listOf("Assert might fail. Assertion 0 <= n might not hold. (r.vpr@120.14--120.26)")), budget)
        assertEquals(listOf(probe), r.unreachable)
        assertTrue("1 other error" in r.note!!, r.note)
    }

    // ---- with Silicon ----

    /** The unreachable branches of [r] as `action line:col what`. */
    private fun unreachable(r: Report) = r.reach!!.unreachable.map { "${it.action} ${it.pos.line}:${it.pos.col} ${it.what}" }

    @Test
    fun `M9 - the unreachable coroutine branches are exactly the three CAS failures, as INFO in a PROVEN report`() {
        Silicon.check(Silicon.defaultHome())
        val r = Workspace(dir).verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.PROVEN, r.verdict, text)
        assertEquals(listOf(Verdict.PROVEN), r.rows.map { it.verdict }.distinct(), text)
        assertNull(r.reach!!.notChecked, text)
        assertEquals(listOf("tryLock 161:49 compareAndSet fails", "unLock 213:44 compareAndSet fails", "sweep 104:49 compareAndSet fails"), unreachable(r), text)
        assertTrue("  INFO unreachable branch: tryLock KeyLocalLock.kt:161:49 compareAndSet fails\n" in text, text)
        assertTrue(File(dir, "coroutine.reach.vpr").isFile)
    }

    @Test
    fun `M9 on core finds the same three CAS failures at their core positions`() {
        Silicon.check(Silicon.defaultHome())
        val r = Workspace(dir).verify("core")
        assertEquals(ModuleVerdict.PROVEN, r.verdict, r.render())
        assertEquals(listOf("tryLock 179:49 compareAndSet fails", "unLock 232:44 compareAndSet fails", "sweep 116:37 compareAndSet fails"), unreachable(r), r.render())
    }

    @Test
    fun `without the sweep CAS (SB5b) its failure branch is gone, and the verdict stays PROVEN`() {
        Silicon.check(Silicon.defaultHome())
        val ws = Workspace(dir)
        ws.editKotlin("coroutine", "if (lockInfo.isHeld.compareAndSet(true, false)) {", "if (true) {")
        val r = ws.verify("coroutine")
        assertEquals(ModuleVerdict.PROVEN, r.verdict, r.render())
        assertEquals(listOf("tryLock 161:49 compareAndSet fails", "unLock 213:44 compareAndSet fails"), unreachable(r), r.render())
    }
}
