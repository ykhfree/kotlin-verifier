package kv.emit

import kv.lower.AspectFixture
import kv.sidecar.SidecarException
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The emitter of instance modules (DESIGN-B.md 5.4) on [AspectFixture] shapes that the gate of GateTest does not
 * cover: the action value in an out local, the v0 parts an instance module leaves out, and probe outs.
 */
class InstanceEmitterTest {
    @TempDir
    lateinit var dir: File

    private var n = 0

    private fun AspectFixture.emitted(): Emitted = lowered(File(dir, "f${n++}")).let { (m, s) -> emitBound(m, s) }

    /** The body lines of method [name], without comments and indentation. */
    private fun Emitted.body(name: String): List<String> =
        text.substringAfter("method $name(").substringAfter("\n{\n").substringBefore("\n}\n").lines()
            .map { it.substringBefore("  //").trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }

    @Test
    fun `an instance module has no ghost record parts, and set-once comes right after MI_OWN`() {
        val e = AspectFixture().emitted()
        for (v0 in listOf("GHOST_PERMS", "HIST", "carried(", "define NULL", "inhale GHOST_PERMS", "ends_only_if", FACT_END, FACT_ISSUE)) assertFalse(v0 in e.text, "$v0 in:\n${e.text}")
        val ensures = e.text.substringAfter("method getOrCreate(").substringBefore("\n{").lines().filter { it.trim().startsWith("ensures") }.map { it.substringBefore("  //").trim() }
        // DESIGN-C.md 7.3: the tool fact FRAME follows MI_PERMS; without a ghost record there is no END or ISSUE.
        val frame = "ensures forall qk: MethodKey :: { S.m[qk] } qk != k ==> (has(S, qk) == old(has(S, qk))) && (has(S, qk) ==> S.m[qk] == old(S.m[qk]))"
        assertEquals(listOf("ensures MI_PERMS(S)", frame, "ensures MI_OWN(S)", "ensures P_stable(S)", "ensures P_once(S)", "ensures MI_INV_1(S)"), ensures.take(6))
        // A stray action gets FRAME at its own key too (AspectVerifyTest M6 verifies it).
        val stray = AspectFixture(members = "    fun drop(jp: Any) {\n        shieldMap.remove(target(jp))\n    }").emitted()
        val contract = stray.text.substringAfter("method stray_drop_").substringBefore("\n{").lines().map { it.substringBefore("  //").trim() }
        assertEquals(listOf("ensures MI_PERMS(S)", frame, "ensures MI_OWN(S)"), contract.filter { it.startsWith("ensures") }.take(3))
        assertTrue("method getOrCreate(S: Ref, k: MethodKey, thrown: Bool) returns (res: Ref, exc: Bool)" in e.text, e.text)
    }

    @Test
    fun `an action value the section does not return directly goes to an out local`() {
        val f = AspectFixture(op = "    fun getOrCreate(jp: Any): Shield =\n        shieldMap.computeIfAbsent(target(jp)) { create(jp) } ?: shared")
        val e = f.emitted()
        val call = f.at("shieldMap.computeIfAbsent").let { "${it.first}_${it.second}" }
        val r = f.at("create(jp) }").let { "r_${it.first}_${it.second}" }
        val body = e.body("getOrCreate")
        assertTrue("var out_$call: Ref" in body, body.joinToString("\n"))
        assertTrue("out_$call := $r" in body && "out_$call := cur_$call" in body, body.joinToString("\n"))
        // The rest of the section runs only if the action did not raise.
        val shared = body.single { it.startsWith("var shared__") }.substringAfter("var ").substringBefore(":")
        assertEquals(listOf("if (!exc) {", "res := out_$call != null ? out_$call : $shared", "}"), body.takeLast(3))
    }

    @Test
    fun `exc is a result when only the sidecar names it`() {
        // The action body reads an instance value: no path raises, but P_same and the probe name exc (M5).
        val f = AspectFixture(op = "    fun getOrCreate(jp: Any): Shield =\n        shieldMap.computeIfAbsent(target(jp)) { shared }")
        val e = f.emitted()
        assertTrue("returns (res: Ref, exc: Bool)" in e.text, e.text)
        assertEquals("exc := false", e.body("getOrCreate").first())
        val g = AspectFixture(op = f.op, operations = AspectFixture.DEFAULT_OPERATION.replace("!exc ==> ", ""))
        assertTrue("returns (res: Ref)\n" in g.emitted().text, "no raise and no exc in the sidecar: no exc result")
    }

    @Test
    fun `a probe call must name one out per result`() {
        val probe = "probe(k1: MethodKey) {\n  r1 := getOrCreate(k1, thrown = false)\n}"
        val err = assertFailsWith<SidecarException> { AspectFixture(sidecarExtra = probe).emitted() }.errors.single()
        assertEquals("emit", err.code)
        assertTrue("returns the result and exc; the probe call names 1 out(s)" in err.message, err.message)
        val ok = AspectFixture(sidecarExtra = "probe(k1: MethodKey) {\n  r1, e1 := getOrCreate(k1, thrown = false)\n  assert !e1\n}").emitted()
        assertTrue("var r1: Ref\n  var e1: Bool\n  r1, e1 := getOrCreate(S, k1, false)" in ok.text, ok.text)
    }
}
