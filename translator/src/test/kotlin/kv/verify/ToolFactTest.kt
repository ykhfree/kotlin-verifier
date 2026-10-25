package kv.verify

import kv.emit.FACT_END
import kv.emit.Source
import kv.emit.emitViper
import kv.lower.LockFixture
import kv.lower.bindGhosts
import kv.sidecar.parseSidecar
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * With Silicon: the tool facts of DESIGN-C.md 7.3 on a fixture that the KeyLocalLock models do not cover. The
 * originals (VerifyTest, AspectVerifyTest) already prove FRAME, END and ISSUE with their bodies.
 */
class ToolFactTest {
    @TempDir
    lateinit var dir: File

    /**
     * END leaves out the token the action issues. In KeyLocalLock the entry always carries an issued token, so the
     * set-once check keeps the result apart from it. Here the entry carries a token that was never issued; the
     * action issues that token and removes the entry. The token ends at the END rule and is held again at ISSUE.
     */
    @Test
    fun `END leaves out the issued token - without that END fails, named tool fact END`() {
        Silicon.check(Silicon.defaultHome())
        val ghost = "ghost record tok(Token) {\n  gIssued: Bool set-once\n  gKey: Key frozen\n  gHeld: Bool ends\n}\nwitness Info.token\n"
        val op =
            "operation run = fun Lock.run {\n  free now = param now\n  free timeout = this.timeout\n  result res\n  issue res { gKey = k }\n" +
                "  reviewed \"fixture\"\n}\n"
        // An entry carries a token of its own that was never issued, so the set-once check of ISSUE holds.
        val invariants =
            "invariant fresh_entry: forall qk: Key :: { S.m[qk] } has(S, qk) ==> !tok(S.m[qk].token).gIssued\n" +
                "invariant own_token: forall qk1: Key, qk2: Key :: { S.m[qk1], S.m[qk2] }\n" +
                "    has(S, qk1) && has(S, qk2) && qk1 != qk2 ==> S.m[qk1].token != S.m[qk2].token\n"
        val f = LockFixture("if (e != null) result.set(e.token)\nnull", operations = ghost + op + invariants)
        val m = f.lower(dir)
        val s = parseSidecar(File(dir, "Lock.kv").path, f.sidecar)

        val proven = verify(m, s, File(dir, "fact.vpr"))
        assertEquals(ModuleVerdict.PROVEN, proven.verdict, proven.render())

        // The same program with END not leaving out the result: the fixture needs the exclusion.
        val emitted = emitViper(bindGhosts(m, s), s)
        val exclusion = " && qt != res ==> !tok(qt).gHeld"
        assertEquals(1, emitted.text.split(exclusion).size - 1, emitted.text)
        val broken = emitted.copy(text = emitted.text.replace(exclusion, " ==> !tok(qt).gHeld"))
        val file = File(dir, "broken.vpr").apply { writeText(broken.text) }
        val run = Silicon.run(file)
        val r = buildReport(bindGhosts(m, s), s, broken, classify(run, SiliconBudget()), run.seconds)
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        assertEquals(listOf(Source.Generated(FACT_END)), r.row("run").findings.map { it.source }, text)
        assertTrue("generated $FACT_END" in text, text)
    }
}
