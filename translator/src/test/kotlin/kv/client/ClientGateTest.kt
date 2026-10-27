package kv.client

import kv.emit.emitClient
import kv.emit.python
import kv.emit.spikeDir
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DESIGN-E 6.10 E3 acceptance: the generated model of the core ReqShield is spike/protocol2/target.vpr, and that of
 * the fixed copy (fixed-core.patch) is spike/protocol2/fixed.vpr, up to the rename table and the structural edits of
 * scripts/protocol2_gate.py and the normalizer change it names (DESIGN-E 6.10: LOCAL).
 */
class ClientGateTest {
    @TempDir
    lateinit var dir: File

    private fun gate(
        base: String,
        generated: String,
    ): Pair<Int, String> {
        val vpr = File(dir, "$base.vpr").apply { writeText(generated) }
        val script = File(System.getProperty("kv.scriptsDir"), "protocol2_gate.py")
        return python(script.path, "--spike", spikeDir.path, "--base", base, "--write", File(dir, "expected.$base.vpr").path, vpr.path)
    }

    @Test
    fun `the generated core model is target_vpr`() {
        val o = ClientGoldenTest.outcome
        val (code, out) = gate("canonical", emitClient(o.module!!, o.sidecar!!).text)
        assertEquals(0, code, out)
    }

    @Test
    fun `the generated model of the fixed copy is fixed_vpr`() {
        val o = Protocol2.resolveFixed(dir)
        val (code, out) = gate("fixed", emitClient(o.module!!, o.sidecar!!).text)
        assertEquals(0, code, out)
    }
}
