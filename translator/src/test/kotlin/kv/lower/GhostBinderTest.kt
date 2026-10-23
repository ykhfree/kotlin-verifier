package kv.lower

import kv.emit.Workspace
import kv.emit.lowerReqShield
import kv.kvir.EndIfNotCarried
import kv.kvir.Status
import kv.kvir.checkWellFormed
import kv.kvir.printOperation
import kv.sidecar.SidecarException
import kv.sidecar.parseSidecar
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The ghost binder (DESIGN-v0.md 3.5, M6): END for every supported action, set-once check and ISSUE for an issue rule. */
class GhostBinderTest {
    @TempDir
    lateinit var dir: File

    /** The commit lines of the printed operation, without positions. */
    private fun commit(text: String): List<String> =
        text.lines().dropWhile { it.trim() != "commit" }.drop(1).takeWhile { it.startsWith("      ") }.map { it.replace(Regex("""\s+@\d+:\d+$"""), "").trim() }

    @Test
    fun `KeyLocalLock tryLock ends the pre-state token, checks the issued token is fresh, and issues it`() {
        val (m, s) = lowerReqShield("coroutine")
        val bound = bindGhosts(m, s)
        assertEquals(emptyList(), checkWellFormed(bound), "W11 holds for the bound commits")
        assertEquals(
            listOf(
                "end-if-not-carried(witness LockInfo.token)",
                "assert-fresh(res)",
                "issue(res) {gKey = k, gBacked = carried(S, k, res), gLease = now + timeout}",
            ),
            commit(printOperation(bound, "tryLock")),
        )
        for (op in listOf("unLock", "sweep")) assertEquals(listOf("end-if-not-carried(witness LockInfo.token)"), commit(printOperation(bound, op)), op)
        // END carries the compute position, ISSUE the result position.
        val tryLock = bound.operations.single { it.name == "tryLock" }
        assertEquals(155 to 9, tryLock.action.commit[0].pos.let { it.line to it.col })
        assertEquals(195 to 16, tryLock.action.commit[2].pos.let { it.line to it.col })
    }

    @Test
    fun `the lowering leaves the commit empty, so only the binder fills it`() {
        val (m, _) = lowerReqShield("core")
        assertTrue(m.operations.all { it.action.commit.isEmpty() })
    }

    private fun fixture(
        body: String,
        members: String = "",
    ): LockFixture =
        LockFixture(
            body,
            members = members,
            operations =
                "ghost record tok(Token) {\n  gIssued: Bool set-once\n  gKey: Key frozen\n  gHeld: Bool ends\n}\nwitness Info.token\n" +
                    "operation run = fun Lock.run {\n  free now     = param now\n  free timeout = this.timeout\n  result res\n" +
                    "  issue res { gKey = k }\n  reviewed \"fixture\"\n}",
        )

    private fun bind(f: LockFixture) = bindGhosts(f.lower(dir), parseSidecar(File(dir, "Lock.kv").path, f.sidecar))

    @Test
    fun `a stray action gets END only, and an unsupported action gets no commit`() {
        val f = fixture("if (e != null) result.set(e.token)\ne", members = "    fun drop(k: String) {\n        lockMap.compute(k) { _, _ -> null }\n    }\n    fun spin(k: String, n: Long) {\n        lockMap.compute(k) { _, v ->\n            while (n < 0) {\n            }\n            v\n        }\n    }")
        val m = bind(f)
        val (drop, spin) = m.strayActions.partition { it.id.startsWith("drop#") }
        assertEquals(listOf(EndIfNotCarried::class), drop.single().commit.map { it::class })
        assertTrue(spin.single().status is Status.Unsupported)
        assertEquals(emptyList(), spin.single().commit)
        assertEquals(listOf("end-if-not-carried(witness Info.token)", "assert-fresh(res)", "issue(res) {gKey = k}"), commit(printOperation(m, "run")))
    }

    @Test
    fun `an issue value outside the v0 vocabulary is a sidecar error at its position`() {
        val ws = Workspace(dir)
        ws.editSpec("keylocallock.common.kv", "gLease = now + timeout }", "gLease = now * timeout }")
        val (m, s) = ws.lower("coroutine")
        val e = assertFailsWith<SidecarException> { bindGhosts(m, s) }.errors.single()
        assertEquals("issue-value", e.code)
        assertEquals(ws.at("keylocallock.common.kv", "gLease = now * timeout", "*"), e.pos.line to e.pos.col)
    }

    @Test
    fun `carried in an issue value must name the action key`() {
        val ws = Workspace(dir)
        ws.editSpec("keylocallock.common.kv", "gBacked = carried(S, k, res)", "gBacked = carried(S, res, res)")
        val (m, s) = ws.lower("coroutine")
        val e = assertFailsWith<SidecarException> { bindGhosts(m, s) }.errors.single()
        assertEquals("issue-value", e.code)
        assertTrue("action key" in e.message, e.message)
        assertEquals(ws.at("keylocallock.common.kv", "gBacked = carried(S, res, res)", "carried"), e.pos.line to e.pos.col)
    }
}
