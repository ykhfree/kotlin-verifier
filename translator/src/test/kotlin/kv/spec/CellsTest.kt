package kv.spec

import kv.cli.runCli
import kv.emit.specDir
import kv.sidecar.CellDecl
import kv.sidecar.ProbeCall
import kv.sidecar.SidecarLoader
import kv.sidecar.checkCells
import kv.sidecar.parseCells
import kv.sidecar.pilotCells
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C4 acceptance 1 (DESIGN-C.md 8): each formal pilot cell, the generated cell of the same scenario, and per pilot
 * outcome the generated outcomes it covers. The pilot's null/token cells have three generated result outcomes (null,
 * backed, unbacked); its stays/dropped cells are state cells.
 */
val pilotToGenerated: Map<String, Pair<String, Map<String, Set<String>>>> =
    run {
        val token = mapOf("null" to setOf("null"), "token" to setOf("backed", "unbacked"))
        val issued = listOf("null", "backed", "unbacked").associateWith { setOf(it) }
        val bool = listOf("true", "false").associateWith { setOf(it) }
        val sweep = mapOf("stays" to setOf("holds_t1"), "dropped" to setOf("no_entry"))
        mapOf(
            "C01" to ("E1r" to issued), "C02" to ("E3r" to issued), "C03" to ("H1r" to token), "C04" to ("H2r" to token),
            "C05" to ("H3r" to token), "C06" to ("O2r" to bool), "C07" to ("O3r" to bool), "C08" to ("H10r" to bool),
            "C10" to ("O6r" to bool), "C11" to ("H11r" to bool), "C12" to ("U2r" to bool), "C13" to ("U4r" to issued),
            "C14" to ("H12s" to sweep), "C15" to ("H13s" to sweep), "C16" to ("H14s" to sweep), "C17" to ("O9r" to bool),
            "C18" to ("E2r" to issued), "C19" to ("H9r" to issued), "C20" to ("K1r" to token),
        )
    }

/** A generated cell's verdict in the outcome names of pilot cell [pilot]; an outcome no pilot outcome covers stays. */
fun pilotVerdict(
    pilot: String,
    generated: String,
): String {
    val outcomes = pilotToGenerated.getValue(pilot).second
    val m = Regex("""DETERMINED\((.*)\)""").matchEntire(generated) ?: return generated
    val named = m.groupValues[1].split("|").map { o -> outcomes.entries.singleOrNull { o in it.value }?.key ?: "unmapped $o" }
    return CellVerdict.determined(named.distinct())
}

/** `kv spec cells` (DESIGN-C.md 3.2, C4) without Silicon: the rule on KeyLocalLock, on the aspect and on edited sidecars. */
class CellsTest {
    @TempDir
    lateinit var dir: File

    private val kll by lazy { generateCells(SidecarLoader.load(File(specDir, "keylocallock.coroutine.kv"))) }
    private val kllCells by lazy { parseCells("kll.cells", kll.text) }

    private fun ids(prefix: Char, n: Int, sides: (Int) -> String) = (1..n).flatMap { i -> sides(i).map { "$prefix$i$it" } }

    @Test
    fun `KeyLocalLock - 65 cells in five prefixes, a result and a state cell per scenario, the boundary cells at the lease`() {
        val sweepH = setOf(12, 13, 14)
        val expected =
            ids('E', 5) { if (it == 5) "s" else "rs" } + ids('H', 14) { if (it in sweepH) "s" else "rs" } + ids('K', 4) { if (it == 4) "s" else "rs" } +
                ids('O', 10) { if (it in setOf(4, 7, 10)) "s" else "rs" } + ids('U', 4) { if (it == 3) "s" else "rs" }
        assertEquals(expected, kll.cells.map { it.id })
        assertEquals(65, kll.cells.size)
        assertEquals(listOf("H2r", "H2s", "H5r", "H5s", "H8r", "H8s", "H13s"), kll.cells.filter { it.boundary }.map { it.id })
        assertEquals(kll.cells.filter { it.boundary }.map { it.id }, kllCells.filter { it.boundary }.map { it.id.text })
        // The text parses back to the same cells, passes the cell checks, and the rule is deterministic.
        assertEquals(kll.cells.map { it.id }, kllCells.map { it.id.text })
        assertEquals(emptyList(), checkCells(SidecarLoader.load(File(specDir, "keylocallock.coroutine.kv")), kllCells))
        assertEquals(kll.text, generateCells(SidecarLoader.load(File(specDir, "keylocallock.coroutine.kv"))).text)
    }

    @Test
    fun `KeyLocalLock - the vocabulary the rule read - lease 10, clocks, the instance value, the input combinations`() {
        assertEquals(
            listOf(
                "state map m: Map<Key, LockInfo>; object module (ghost record tok(Token), backed field gBacked)",
                "issuing operation: tryLock (set-up: tryLock(k, now = 0, timeout = 10, rej = false, limit = 0))",
                "lease: gLease = now + timeout, 10 at the set-up; times 5, 10, 11 (before, at, after)",
                "operation tryLock(now clock, timeout instance value 10, rej {false, true}, limit {0, 5}) -> issued value; trusted contracts: rej ==> limit != 0",
                "operation unLock(t token) -> Bool",
                "operation sweep(now clock) -> no result",
            ),
            kll.notes,
        )
        // rej ==> limit != 0 leaves out (true, 0): three combinations, at time 0 in E and at 5, 10, 11 in H.
        fun call(id: String) = (kllCells.single { it.id.text == id }.steps.last() as ProbeCall).let { c -> c.args.joinToString { a -> a.name?.let { "${it.text} = ${a.value.text}" } ?: a.value.text } }
        assertEquals(
            listOf("k, now = 0, timeout = 10, rej = false, limit = 0", "k, now = 0, timeout = 10, rej = false, limit = 5", "k, now = 0, timeout = 10, rej = true, limit = 5"),
            listOf("E1r", "E2r", "E3r").map(::call),
        )
        assertEquals((1..9).map { i -> "now = ${listOf(5, 10, 11)[(i - 1) % 3]}" }, (1..9).map { call("H${it}r").split(", ")[1] })
        assertEquals(listOf("k, t = t1", "k, t = x"), listOf("H10r", "H11r").map(::call))
        assertEquals(listOf("k2, now = 5, timeout = 10, rej = false, limit = 0", "k2, t = t1", "k2, t = x", "k2, now = 5"), listOf("K1r", "K2r", "K3r", "K4s").map(::call))
    }

    private fun cell(
        cells: List<CellDecl>,
        id: String,
    ) = cells.single { it.id.text == id }

    private fun lastCall(c: CellDecl) = (c.steps.last() as ProbeCall).let { p -> p.op.text to p.args.map { (it.name?.text ?: "key") to it.value.text } }

    @Test
    fun `every formal pilot cell has a generated cell with the same final call, but C13, and its outcomes cover the pilot's`() {
        val pilot = parseCells(pilotCells.path, pilotCells.readText())
        assertEquals(pilot.map { it.id.text }.toSet(), pilotToGenerated.keys)
        for ((p, mapped) in pilotToGenerated) {
            val (g, outcomes) = mapped
            val pc = cell(pilot, p)
            val gc = cell(kllCells, g)
            if (p != "C13") assertEquals(lastCall(pc), lastCall(gc), "$p -> $g")
            assertEquals(pc.outcomes.map { it.name.text }.toSet(), outcomes.keys, "$p -> $g")
            assertTrue(gc.outcomes.map { it.name.text }.containsAll(outcomes.values.flatten()), "$p -> $g")
            assertEquals(pc.boundary, gc.boundary, "$p -> $g")
        }
        // C13 repeats C02's call at time 1; U4r repeats the call that returned u, whose inputs Silicon picks.
        assertEquals("tryLock" to listOf("key" to "k", "now" to "1", "timeout" to "10", "rej" to "true", "limit" to "5"), lastCall(cell(pilot, "C13")))
        assertEquals("tryLock" to listOf("key" to "k", "now" to "0", "timeout" to "10", "rej" to "rej0", "limit" to "limit0"), lastCall(cell(kllCells, "U4r")))
    }

    @Test
    fun `the prefixes - set-up, another key, owner change, a token no entry backs`() {
        fun steps(id: String) = kll.cells.single { it.id == id }.text.lines().drop(1).takeWhile { !it.trimStart().startsWith("outcome") }.map { it.trim() }
        val setup = listOf("t1 := tryLock(k, now = 0, timeout = 10, rej = false, limit = 0)", "assume t1 != NULL && tok(t1).gBacked")
        assertEquals(setup + "assume x >= 0 && !tok(x).gIssued" + "r := unLock(k, t = x)", steps("H11r"))
        assertEquals(setup + "assume k2 != k" + "r := tryLock(k2, now = 5, timeout = 10, rej = false, limit = 0)", steps("K1r"))
        assertEquals(setup + listOf("t2 := tryLock(k, now = 11, timeout = 10, rej = false, limit = 0)", "assume t2 != NULL && !carried(S, k, t1)", "r := unLock(k, t = t2)"), steps("O3r"))
        assertEquals(setup + listOf("o1 := unLock(k, t = t1)", "assume !carried(S, k, t1)", "r := unLock(k, t = t1)"), steps("O6r"))
        assertEquals(setup + listOf("sweep(k, now = 11)", "assume !carried(S, k, t1)", "r := unLock(k, t = t1)"), steps("O9r"))
        assertEquals(
            listOf("assume rej0 ==> limit0 != 0", "u := tryLock(k, now = 0, timeout = 10, rej = rej0, limit = limit0)", "assume u != NULL && !tok(u).gBacked", "r := unLock(k, t = u)"),
            steps("U2r"),
        )
        // The state cell after an issuing step: the new value r counts only if it is none of the named values.
        assertEquals(
            listOf(
                "no_entry" to "!has(S, k)",
                "holds_t1" to "carried(S, k, t1)",
                "holds_t2" to "carried(S, k, t2)",
                "holds_r" to "r != NULL && r != t1 && r != t2 && carried(S, k, r)",
                "other" to "has(S, k) && !carried(S, k, t1) && !carried(S, k, t2) && !(r != NULL && r != t1 && r != t2 && carried(S, k, r))",
            ),
            cell(kllCells, "O1s").outcomes.map { it.name.text to it.expr.text },
        )
        // On another key, the state cell looks at k, so the new value of k2 is no outcome.
        assertEquals(listOf("no_entry", "holds_t1", "other"), cell(kllCells, "K1s").outcomes.map { it.name.text })
    }

    @Test
    fun `the aspect - an instance module - exc first, the same object as an outcome, no U prefix, no boundary`() {
        val g = generateCells(SidecarLoader.load(File(specDir, "aspect.core-spring.kv")))
        assertEquals(listOf("E1r", "E1s", "E2r", "E2s", "H1r", "H1s", "H2r", "H2s", "K1r", "K1s", "O1r", "O1s"), g.cells.map { it.id })
        assertEquals(emptyList(), g.cells.filter { it.boundary })
        assertEquals("instance module (no ghost record)", g.notes[0].substringAfter("; "))
        assertEquals("operation getOrCreate(thrown {false, true}) -> issued value, may raise", g.notes[3])
        val cells = parseCells("aspect.cells", g.text)
        assertEquals(emptyList(), checkCells(SidecarLoader.load(File(specDir, "aspect.core-spring.kv")), cells))
        assertEquals(
            listOf(
                "exc" to "e",
                "null" to "!e && r == null",
                "same_t1" to "!e && r == t1",
                "backed" to "!e && r != null && r != t1 && has(S, k) && S.m[k] == r",
                "unbacked" to "!e && r != null && r != t1 && !(has(S, k) && S.m[k] == r)",
            ),
            cell(cells, "H1r").outcomes.map { it.name.text to it.expr.text },
        )
        assertEquals(listOf("t1, e1 := getOrCreate(k, thrown = false)", "assume !e1 && t1 != null && has(S, k) && S.m[k] == t1"), cell(cells, "H2s").text().take(2))
        assertEquals("getOrCreate" to listOf("key" to "k2", "thrown" to "false"), lastCall(cell(cells, "K1r")))
    }

    private fun CellDecl.text() = steps.map { s -> if (s is ProbeCall) "${s.outs.joinToString { it.text }} := ${s.op.text}(${s.args.joinToString { a -> a.name?.let { "${it.text} = ${a.value.text}" } ?: a.value.text }})" else "assume " + (s as kv.sidecar.CellAssume).expr.text }

    /** A copy of the KeyLocalLock specs with [old] replaced by [new] in the common file (fingerprints are not checked). */
    private fun edited(
        old: String,
        new: String,
    ): GeneratedCells {
        val d = File(dir, "specs").apply { mkdirs() }
        specDir.listFiles()!!.forEach { it.copyTo(File(d, it.name), overwrite = true) }
        val common = File(d, "keylocallock.common.kv")
        require(common.readText().split(old).size == 2) { "'$old' is not in the common file once" }
        common.writeText(common.readText().replace(old, new))
        return generateCells(SidecarLoader.load(File(d, "keylocallock.coroutine.kv")))
    }

    @Test
    fun `without a lease the clock inputs are not varied, and without an issue rule only the empty module is left`() {
        val noLease = edited("gLease = now + timeout }", "gLease = timeout }")
        assertEquals("lease: none, so clock inputs are not varied", noLease.notes[2])
        // H: 3 tryLock combinations at one time, unLock t1 and x, one sweep.
        assertEquals((1..6).map { "H$it" }, noLease.cells.map { it.id.dropLast(1) }.filter { it.startsWith("H") }.distinct())
        assertTrue(noLease.cells.none { it.boundary })
        val calls = parseCells("t.cells", noLease.text).filter { it.id.text.startsWith("H") }.map { (it.steps.last() as ProbeCall).args.firstOrNull { a -> a.name?.text == "now" }?.value?.text }
        assertEquals(setOf("0", null), calls.toSet())

        val noIssue =
            edited(
                "  issue res { gKey = k; gBacked = carried(S, k, res); gLease = now + timeout }\n" +
                    "  reviewed \"ykhfree 2026-10-23: README Global lock guidance: every lock acquisition carries an ownership token\" sha256 \"023e62e52c1fc231\"\n",
                "",
            )
        assertEquals("issuing operation: none, so no set-up: only prefix E", noIssue.notes[1])
        assertEquals(setOf('E'), noIssue.cells.map { it.id[0] }.toSet())
        // Without an issue rule tryLock's result is a Bool (as kv spec check without --root assumes).
        assertEquals(listOf("true", "false"), parseCells("t.cells", noIssue.text).first().outcomes.map { it.name.text })
    }

    @Test
    fun `evalExpr - the trusted contract filter reads literals and operators, and anything else is unknown`() {
        assertEquals(false, evalExpr("rej ==> limit != 0", mapOf("rej" to true, "limit" to 0L)))
        assertEquals(true, evalExpr("rej ==> limit != 0", mapOf("rej" to false, "limit" to 0L)))
        assertEquals(true, evalExpr("1 + 2 * 3 == 7 && !(4 - 6 >= 0) || x", mapOf("x" to false)))
        assertEquals(10L, evalExpr("now + timeout", mapOf("now" to 0L, "timeout" to 10L)))
        assertEquals(true, evalExpr("a ==> b ==> c", mapOf("a" to true, "b" to false, "c" to false)))
        assertEquals(-3L, evalExpr("-(7 / 2)", emptyMap()))
        assertNull(evalExpr("f(x) > 0", mapOf("x" to 1L)))
        assertNull(evalExpr("y > 0", emptyMap()))
        assertNull(evalExpr("tok(t).gHeld", mapOf("t" to 1L)))
        assertNull(evalExpr("1 / 0", emptyMap()))
        assertNull(evalExpr("1 +", emptyMap()))
    }

    private fun kv(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(args.toList(), PrintStream(out), PrintStream(err))
        return Triple(code, out.toString(), err.toString())
    }

    @Test
    fun `kv spec cells - to stdout, to a file, usage errors and a sidecar error`() {
        val sidecar = File(specDir, "keylocallock.coroutine.kv").path
        val (code, out, _) = kv("spec", "cells", sidecar)
        assertEquals(0 to kll.text, code to out)
        val file = File(dir, "kll.cells")
        val (code2, out2, _) = kv("spec", "cells", "--out", file.path, sidecar)
        assertEquals(0, code2)
        assertEquals("kv: wrote ${file.path}: 65 cells, 7 boundary\n", out2)
        assertEquals(kll.text, file.readText())
        assertEquals(2, kv("spec", "cells").first)
        assertEquals(2, kv("spec", "cells", "--out").first)
        assertEquals(2, kv("spec", "cells", File(dir, "none.kv").path).first)
        val bad = File(dir, "bad.kv").apply { writeText("module a.B\nstate m: Map<Key, V> = x\nfrobnicate\n") }
        val (code3, _, err3) = kv("spec", "cells", bad.path)
        assertEquals(4, code3, err3)
        val noMap = File(dir, "nomap.kv").apply { writeText("module a.B\n") }
        val (code4, _, err4) = kv("spec", "cells", noMap.path)
        assertEquals(4, code4)
        assertTrue("error[cells]: the cell rule needs exactly one state map" in err4, err4)
    }
}
