package kv.spec

import kv.Aspects
import kv.ReqShield
import kv.emit.specDir
import kv.sidecar.SidecarLoader
import kv.sidecar.pilotCells
import kv.verify.Silicon
import kv.verify.SiliconBudget
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * C4 acceptance 1 and 3 with Silicon (DESIGN-C.md 8): `kv spec check --root` on the cells that `kv spec cells` writes
 * for the current KeyLocalLock sidecar and for the core-spring aspect sidecar.
 */
class SpecCellsAcceptanceTest {
    @TempDir
    lateinit var dir: File

    private fun check(
        sidecar: File,
        cells: File,
        classpath: List<File>,
        name: String,
    ): SpecCheckReport {
        Silicon.check(Silicon.defaultHome())
        return specCheck(
            SpecCheckOptions(sidecar, File(pilotDir, "packet"), cells, ReqShield.dir, classpath, ReqShield.kotlincArgs, false, File(dir, name), Silicon.defaultHome(), SiliconBudget()),
        )
    }

    private fun generated(sidecar: File) = File(dir, sidecar.nameWithoutExtension + ".cells").apply { writeText(generateCells(SidecarLoader.load(sidecar)).text) }

    private fun SpecCheckReport.cells(mode: Mode): Map<String, String> = determinacy.single { it.mode == mode }.also { assertEquals(null, it.error, render()) }.cells

    private fun Map<String, String>.withVerdict(v: String) = filterValues { it == v }.keys.toList()

    @Test
    fun `KeyLocalLock - every mapped pilot cell has the fixture's verdict in modes a and b, and the OPEN cells are known`() {
        val sidecar = File(specDir, "keylocallock.coroutine.kv")
        val gen = check(sidecar, generated(sidecar), ReqShield.classpath, "generated")
        val fix = check(sidecar, pilotCells, ReqShield.classpath, "fixture")
        for (mode in Mode.entries) {
            val f = fix.cells(mode)
            val g = gen.cells(mode)
            assertEquals(f, pilotToGenerated.mapValues { (p, mapped) -> pilotVerdict(p, g.getValue(mapped.first)) }, "mode ${mode.letter}:\n${gen.render()}")
            // The current sidecar: 18 pilot cells DETERMINED, C16 (the monitor at L + 1, no monitor duty: UC1) OPEN.
            assertEquals(listOf("C16"), f.withVerdict(CellVerdict.OPEN))
            assertEquals(emptyList(), g.withVerdict(CellVerdict.INFEASIBLE) + g.values.filter { it.startsWith("ERROR") })
        }
        // Mode a: only C16's cell. Mode b: C16 and the four cells after a monitor pass at L + 1, which hang on C16
        // (no monitor duty: UC1). no_entry_created (DESIGN-C.md 10.4) closed E4s, E5s, O6s, O7s, U2s and U3s.
        assertEquals(listOf("H14s"), gen.cells(Mode.A).withVerdict(CellVerdict.OPEN))
        assertEquals(listOf("H14s", "O8r", "O8s", "O9s", "O10s"), gen.cells(Mode.B).withVerdict(CellVerdict.OPEN))
        assertEquals(0, gen.errorCount, gen.render())
    }

    @Test
    fun `the aspect - the signed sidecar decides every cell, and the owner change is impossible`() {
        val sidecar = File(specDir, "aspect.core-spring.kv")
        val r = check(sidecar, generated(sidecar), Aspects.classpath, "aspect")
        val expected =
            mapOf(
                "E1r" to "DETERMINED(backed)", "E1s" to "DETERMINED(holds_r)", "E2r" to "DETERMINED(exc)", "E2s" to "DETERMINED(no_entry)",
                "H1r" to "DETERMINED(same_t1)", "H1s" to "DETERMINED(holds_t1)", "H2r" to "DETERMINED(same_t1)", "H2s" to "DETERMINED(holds_t1)",
                "K1r" to "DETERMINED(backed)", "K1s" to "DETERMINED(holds_t1)", "O1r" to "INFEASIBLE", "O1s" to "INFEASIBLE",
            )
        assertEquals(expected, r.cells(Mode.A), r.render())
        assertEquals(expected, r.cells(Mode.B), r.render())
        assertEquals(0, r.errorCount, r.render())
    }
}
