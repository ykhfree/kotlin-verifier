package kv.verify

import kv.emit.FACT_MI_PEND
import kv.emit.FACT_PEND
import kv.emit.MethodKind
import kv.emit.RESERVE_FRESH
import kv.emit.Source
import kv.emit.Workspace
import kv.emit.emitViper
import kv.emit.specDir
import kv.lower.LockFixture
import kv.lower.bindGhosts
import kv.sidecar.parseSidecar
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * With Silicon: DESIGN-D.md option (a), milestone D1 and the acceptance tests of DESIGN-D.md 8. A reserve step R
 * reserves a counter value outside the action; the action B consumes it; every issue checks that no other thread
 * reserved the token. The AI implementations are the frozen files of `aicode/round2/impl` (AI-CODE.md 15).
 */
class ReserveVerifyTest {
    @TempDir
    lateinit var dir: File

    private companion object {
        /** The repository root: the parent of `specs/`. */
        val repo: File = specDir.parentFile.parentFile

        /** The text of the AI implementation [id] of round 2. */
        fun ai(id: String) = File(repo, "aicode/round2/impl/$id/KeyLocalLock.kt").readText()

        /** The minimal ghost record of the E5 and E6 fixtures. */
        const val GHOST = "ghost record tok(Token) {\n  gIssued: Bool set-once\n  gKey:    Key  frozen\n  gHeld:   Bool ends\n}\nwitness Info.token\n"

        /** The hole of E6 (DESIGN-D.md 3.3): the counter is never negative; issued tokens are 0..seq; [extra] more. */
        fun e6Hole(extra: String = "") = "0 <= S.seq && (forall qt: Int :: { tok(qt).gIssued } tok(qt).gIssued ==> 0 <= qt && qt <= S.seq)$extra"

        /** E6's operation X: it issues the counter value its action [issues] makes. */
        fun e6Steal(issues: String) =
            """
            |    fun steal(key: String, now: Long): String? {
            |        val result = AtomicReference<String?>(null)
            |        lockMap.compute(key) { _, e ->
            |$issues
            |            result.set("local-${'$'}t")
            |            e
            |        }
            |        return result.get()
            |    }
            """.trimMargin()

        /** E6's sidecar operations: Y is `run`, X is `steal`; each issues its result and states P1a. */
        val E6_OPERATIONS =
            GHOST + listOf("run" to "  free now     = param now\n  free timeout = this.timeout\n", "steal" to "  free now     = param now\n").joinToString("") { (op, free) ->
                "operation $op = fun Lock.$op {\n$free  result res\n  issue res { gKey = k }\n  reviewed \"fixture\"\n" +
                    "  ensures P1a: res != NULL ==> !old(tok(res).gIssued) && tok(res).gIssued\n}\n"
            }
    }

    /** The number of [verifyAi] runs: each gets its own workspace. */
    private var runs = 0

    /** The sidecar ids, generated names or Kotlin notes of a row's pass-1 findings. */
    private fun Row.ids(): List<String?> =
        findings.map {
            when (val s = it.source) {
                is Source.Sidecar -> s.id
                is Source.Generated -> s.what
                is Source.Kotlin -> s.note ?: "kotlin"
                null -> null
            }
        }

    /** Verifies AI implementation [id] of round 2, with [edits] and its binding adaptations of AI-CODE.md 6.1. */
    private fun verifyAi(
        id: String,
        vararg edits: Pair<String, String>,
    ): Report {
        Silicon.check(Silicon.defaultHome())
        val ws = Workspace(File(dir, "$id-${++runs}").apply { mkdirs() })
        var text = ai(id)
        for ((old, new) in edits) {
            check(text.split(old).size == 2) { "'$old' occurs ${text.split(old).size - 1} times in $id" }
            text = text.replace(old, new)
        }
        ws.writeKotlin("coroutine", text)
        // bindings.txt: `class LockInfo LockInfo` (T-haiku-1, T-haiku-4).
        if ("class LockInfo LockInfo" in File(repo, "aicode/round2/impl/$id/bindings.txt").takeIf { it.exists() }?.readText().orEmpty()) {
            ws.editSpec("keylocallock.common.kv", "class LockInfo = KeyLocalLock.LockInfo", "class LockInfo = LockInfo")
        }
        return ws.verify("coroutine")
    }

    /** DESIGN-D.md 8 AT1-AT3: the tryLock and sweep of the original fail only P3a; the step, unLock and the probe hold. */
    private fun assertBoundaryDefect(r: Report) {
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        assertEquals(listOf("ends_only_if P3a"), r.row("tryLock").ids(), text)
        assertEquals(SubStatus.PROPERTY, r.row("tryLock").sub, text)
        assertEquals(listOf("ends_only_if P3a"), r.row("sweep").ids(), text)
        assertEquals(Verdict.PROVEN to Verdict.PROVEN, r.row("reserve tryLock").verdict to r.row("unLock").verdict, text)
        assertEquals(Verdict.PROVEN, r.row("probe").verdict, text)
        // No false reason of round 2 (AI-CODE.md 12.1): set-once, cap_unbacked, MI_INV_1 and MI_INV_3 hold.
        assertTrue(text.lines().any { it.startsWith("  step reserve tryLock ") && it.endsWith("PROVEN") }, text)
        assertTrue("Unsupported: none" in text, text)
    }

    // ---- AT1-AT4, AT6 on the frozen AI implementations ----

    @Test
    fun `AT1 - T-haiku-2 tryLock fails only ends_only_if P3a, and its boundary control is PROVEN (AT4)`() {
        assertBoundaryDefect(verifyAi("T-haiku-2"))
        val control = verifyAi("T-haiku-2", "existing.expiresAt > now -> existing" to "existing.expiresAt >= now -> existing", "existing.expiresAt <= now) null" to "existing.expiresAt < now) null")
        assertEquals(ModuleVerdict.PROVEN, control.verdict, control.render())
    }

    @Test
    fun `AT2 and AT6 - T-haiku-4 with its result if-else fails P3a, its control is PROVEN, and always returning the token fails P3b`() {
        assertBoundaryDefect(verifyAi("T-haiku-4"))
        val fixed = arrayOf("now < existing.expiresAt -> existing" to "now <= existing.expiresAt -> existing", "existing.expiresAt <= now) null" to "existing.expiresAt < now) null")
        val control = verifyAi("T-haiku-4", *fixed)
        assertEquals(ModuleVerdict.PROVEN, control.verdict, control.render())
        // PB6: the result is the token even when the lock is refused.
        val pb6 = verifyAi("T-haiku-4", *fixed, "return if (granted.get()) token else null" to "return if (granted.get() || true) token else null")
        val text = pb6.render()
        assertEquals(ModuleVerdict.FAILED, pb6.verdict, text)
        assertTrue("unbacked_only_if P3b" in pb6.row("tryLock").ids(), text)
    }

    @Test
    fun `AT3 - T-haiku-1 tryLock fails P3a, and its control is PROVEN`() {
        assertBoundaryDefect(verifyAi("T-haiku-1"))
        val control = verifyAi("T-haiku-1", "fun isExpired(now: Long): Boolean = now >= expiresAt" to "fun isExpired(now: Long): Boolean = now > expiresAt")
        assertEquals(ModuleVerdict.PROVEN, control.verdict, control.render())
    }

    // ---- AT5: the Part B mutants of T-haiku-2 ----

    @Test
    fun `AT5 - no Part B mutant of T-haiku-2 is PROVEN`() {
        val fixed = arrayOf("existing.expiresAt > now -> existing" to "existing.expiresAt >= now -> existing", "existing.expiresAt <= now) null" to "existing.expiresAt < now) null")
        val reserve = "val newToken = \"local-\${tokenSequence.incrementAndGet()}\""
        // PB1: get() outside the action is a racy read: the token is an arbitrary value, and the issue check fails.
        val pb1 = verifyAi("T-haiku-2", *fixed, reserve to "val newToken = \"local-\${tokenSequence.get()}\"")
        assertEquals(ModuleVerdict.FAILED, pb1.verdict, pb1.render())
        // PB2: the step inside an if (rule 2), PB3: two steps (rule 5, W17), PB4: the token leaks to a field (rule 4).
        val pb2 = verifyAi("T-haiku-2", *fixed, reserve to "val newToken = if (now > 0L) \"local-\${tokenSequence.incrementAndGet()}\" else \"local-0\"")
        val pb3 = verifyAi("T-haiku-2", *fixed, reserve to "$reserve\n        val spare = \"local-\${tokenSequence.incrementAndGet()}\"", "grantedToken.set(newToken)\n                    null" to "grantedToken.set(spare)\n                    null")
        val pb4 = verifyAi("T-haiku-2", *fixed, reserve to "$reserve\n        lastToken = newToken", "class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {" to "class KeyLocalLock(private val lockTimeoutMillis: Long) : KeyLock {\n    private var lastToken: String? = null")
        for ((name, r) in listOf("PB2" to pb2, "PB3" to pb3, "PB4" to pb4)) {
            val text = r.render()
            assertEquals(ModuleVerdict.INCOMPLETE, r.verdict, "$name\n$text")
            assertEquals(Verdict.UNSUPPORTED, r.row("tryLock").verdict, "$name\n$text")
            assertTrue("Counter operations outside an action: " in text && "not a reserve step (DESIGN-D 6.3 rule" in text, "$name\n$text")
        }
        assertTrue("(DESIGN-D 6.3 rule 2)" in pb2.render() && "(DESIGN-D 6.3 rule 5)" in pb3.render() && "(DESIGN-D 6.3 rule 4)" in pb4.render())
        // PB5: the entry gets a token from get() inside the action, which another thread may have reserved.
        val pb5 = verifyAi("T-haiku-2", *fixed, "token = newToken," to "token = \"local-\${tokenSequence.get()}\",")
        assertEquals(ModuleVerdict.FAILED, pb5.verdict, pb5.render())
    }

    // ---- AT7: E5 fence, E6 steal, E6 control (DESIGN-D.md 3.3, 4) ----

    @Test
    fun `AT7 - E5 fence - a reserved value compared with the holder's epoch fails expired_takeover`() {
        Silicon.check(Silicon.defaultHome())
        val run =
            """
            |    fun run(key: String, now: Long): String? {
            |        val result = AtomicReference<String?>(null)
            |        val n = counter.incrementAndGet()
            |        lockMap.compute(key) { _, e ->
            |            if (e != null && now <= e.expiresAt) return@compute e
            |            if (e != null && n <= e.epoch) return@compute e
            |            result.set("local-${'$'}n")
            |            Info(Semaphore(0), now + timeout, AtomicBoolean(true), "local-${'$'}n", n)
            |        }
            |        return result.get()
            |    }
            """.trimMargin()
        val ghost = "ghost record tok(Token) {\n  gIssued: Bool set-once\n  gKey:    Key  frozen\n  gBacked: Bool frozen\n  gHeld:   Bool ends\n  gLease:  Int  frozen\n}\nwitness Info.token\n"
        val op =
            "operation run = fun Lock.run {\n  free now     = param now\n  free timeout = this.timeout\n  result res\n" +
                "  issue res { gKey = k; gBacked = carried(S, k, res); gLease = now + timeout }\n  reviewed \"fixture\"\n" +
                "  ends_only_if P3a: now > old(tok(qt).gLease)\n  reviewed \"fixture\"\n" +
                "  ensures expired_takeover: old(has(S, k)) && now > old(S.m[k].expiresAt) ==> res != NULL\n}\n"
        val hole =
            "(forall qk: Key :: { S.m[qk] } has(S, qk) ==> S.m[qk].permits == 0 && S.m[qk].isHeld && S.m[qk].token != NULL) && " +
                "0 <= S.seq && (forall qt: Int :: { tok(qt).gIssued } tok(qt).gIssued ==> 0 <= qt && qt <= S.seq) && " +
                "(forall qk: Key :: { S.m[qk] } has(S, qk) ==> tok(S.m[qk].token).gIssued && tok(S.m[qk].token).gKey == qk && " +
                "tok(S.m[qk].token).gBacked && tok(S.m[qk].token).gHeld && tok(S.m[qk].token).gLease == S.m[qk].expiresAt) && " +
                "(forall qt: Int :: { tok(qt).gHeld } tok(qt).gIssued && tok(qt).gBacked && tok(qt).gHeld ==> has(S, tok(qt).gKey) && S.m[tok(qt).gKey].token == qt) && " +
                "(forall qk: Key :: { S.m[qk] } has(S, qk) ==> S.m[qk].epoch == S.m[qk].token)"
        val f =
            LockFixture(
                "",
                run = run,
                operations = ghost + op,
                hole = hole,
                info = LockFixture.DEFAULT_INFO + "\n        val epoch: Long = 0L,",
                classFields = LockFixture.DEFAULT_CLASS_FIELDS + "\n  epoch:     Long          = epoch",
            )
        val m = f.lower(dir)
        val r = verify(m, parseSidecar(File(dir, "Lock.kv").path, f.sidecar), File(dir, "e5.vpr"))
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        assertEquals(listOf("expired_takeover"), r.row("run").ids(), text)
        assertEquals(Verdict.PROVEN, r.row("reserve run").verdict, text)
    }

    /** Verifies E6 with X's action [issues] and the hole [hole]; the report and the emitted program. */
    private fun e6(
        issues: String,
        hole: String,
    ): Pair<Report, LockFixture> {
        Silicon.check(Silicon.defaultHome())
        val f =
            LockFixture(
                "if (n != counter.get()) result.set(\"local-\$n\")\ne",
                before = "        val n = counter.incrementAndGet()",
                members = e6Steal(issues),
                operations = E6_OPERATIONS,
                hole = hole,
            )
        val m = f.lower(dir)
        return verify(m, parseSidecar(File(dir, "Lock.kv").path, f.sidecar), File(dir, "e6.vpr")) to f
    }

    @Test
    fun `AT7 - E6 steal - an action that issues the counter's current value fails the reservation check of its issue`() {
        val (r, f) = e6("            val t = counter.get()\n            counter.incrementAndGet()", e6Hole(" && !tok(S.seq).gIssued"))
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        // X issues a value that Y may hold: the issue check names the reservation (DESIGN-D.md 6.6 item 5).
        assertEquals(listOf("issue steal: the token is reserved by another thread"), r.row("steal").ids(), text)
        assertTrue("sidecar issue steal: the token is reserved by another thread" in text, text)
        assertEquals(Verdict.PROVEN to Verdict.PROVEN, r.row("run").verdict to r.row("reserve run").verdict, text)
        assertEquals(f.at("counter.incrementAndGet()").first, r.row("reserve run").kotlin!!.line)
    }

    @Test
    fun `AT7 - E6 control - an action that issues the value it increments to is PROVEN`() {
        val (r, _) = e6("            val t = counter.incrementAndGet()", e6Hole())
        assertEquals(ModuleVerdict.PROVEN, r.verdict, r.render())
    }

    // ---- AT8: the tool facts catch an emitter that drops a gPend rule (DESIGN-D.md 3.2, 4 brk1 and brk2) ----

    /** Runs Silicon on [text], an edit of [emitted]'s program for [m], and reports. */
    private fun reportOf(
        m: kv.kvir.Module,
        s: kv.sidecar.Sidecar,
        emitted: kv.emit.Emitted,
        text: String,
        name: String,
    ): Report {
        val file = File(dir, name).apply { writeText(text) }
        val run = Silicon.run(file)
        return buildReport(bindGhosts(m, s), s, emitted.copy(text = text), classify(run, SiliconBudget()), run.seconds)
    }

    @Test
    fun `AT8 - without the issue check the reservation of E6 fails at tool fact MI_PEND, and without the consumption E6 control fails`() {
        Silicon.check(Silicon.defaultHome())
        val f =
            LockFixture(
                "if (n != counter.get()) result.set(\"local-\$n\")\ne",
                before = "        val n = counter.incrementAndGet()",
                members = e6Steal("            val t = counter.get()\n            counter.incrementAndGet()"),
                operations = E6_OPERATIONS,
                hole = e6Hole(" && !tok(S.seq).gIssued"),
            )
        val m = f.lower(dir)
        val s = parseSidecar(File(dir, "Lock.kv").path, f.sidecar)
        val emitted = emitViper(bindGhosts(m, s), s)
        val check = "    assert !tok(res).gPend  // DESIGN-D.md 2.1: not a token another thread reserved\n"
        assertEquals(2, emitted.text.split(check).size - 1, emitted.text)
        // A comment keeps the line numbers, so that the position table still maps the errors.
        val brk1 = reportOf(m, s, emitted, emitted.text.replace(check, "    // (the issue check is dropped)\n"), "brk1.vpr")
        assertEquals(ModuleVerdict.FAILED, brk1.verdict, brk1.render())
        assertEquals(listOf(FACT_MI_PEND), brk1.row("steal").ids(), brk1.render())

        val c = LockFixture("if (n != counter.get()) result.set(\"local-\$n\")\ne", before = "        val n = counter.incrementAndGet()", members = e6Steal("            val t = counter.incrementAndGet()"), operations = E6_OPERATIONS, hole = e6Hole())
        val cm = c.lower(File(dir, "control").apply { mkdirs() })
        val cs = parseSidecar(File(dir, "control/Lock.kv").path, c.sidecar)
        val ce = emitViper(bindGhosts(cm, cs), cs)
        val consume = Regex("""  tok\(n__\d+\)\.gPend := false  // consume the reservation of step reserve_run \(DESIGN-D\.md 2\.1\)\n""")
        assertEquals(1, consume.findAll(ce.text).count(), ce.text)
        val brk2 = reportOf(cm, cs, ce, ce.text.replace(consume, "  // (the consumption is dropped)\n"), "brk2.vpr")
        val text = brk2.render()
        assertEquals(ModuleVerdict.FAILED, brk2.verdict, text)
        assertTrue(brk2.row("run").ids().any { it == FACT_PEND || it == "issue run: the token is reserved by another thread" }, text)
    }

    // ---- the reference coroutine KeyLocalLock with its token reserved before compute ----

    @Test
    fun `the coroutine KeyLocalLock with val fresh = nextToken() before compute is PROVEN with a reserve step`() {
        Silicon.check(Silicon.defaultHome())
        val ws = Workspace(dir)
        ws.editKotlin("coroutine", "val acquiredToken = AtomicReference<String?>(null)\n", "val acquiredToken = AtomicReference<String?>(null)\n        val fresh = nextToken()\n")
        ws.editKotlin("coroutine", "val token = nextToken()\n                    existing.isHeld.set(true)", "val token = fresh\n                    existing.isHeld.set(true)")
        ws.editKotlin("coroutine", "acquiredToken.set(nextToken())", "acquiredToken.set(fresh)")
        ws.editKotlin("coroutine", "val token = nextToken()\n                val newLock", "val token = fresh\n                val newLock")
        val r = ws.verify("coroutine")
        val text = r.render()
        assertEquals(ModuleVerdict.PROVEN, r.verdict, text)
        val step = r.rows.single { it.kind == MethodKind.RESERVE }
        assertEquals("reserve tryLock", step.label, text)
        assertEquals(ws.kotlinLine("coroutine", "private fun nextToken()"), step.kotlin!!.line, text)
        assertTrue(text.lines().any { it.startsWith("  Reserve steps (DESIGN-D.md 2.1") && "tryLock: fresh__" in it && "tokenSequence.incrementAndGet()" in it }, text)
        assertTrue("Init: the ghost records are allocated by an inhale with no record issued or reserved (trusted)" in text, text)
    }

    // ---- DESIGN-D.md 6.7 item 2: a fresh assert of the step that fails for a weak hole is "invariant only" ----

    @Test
    fun `a reserve step whose fresh assert needs more of the hole is FAILED invariant only`() {
        Silicon.check(Silicon.defaultHome())
        // The hole does not bound the issued tokens by the counter, so R cannot show that seq + 1 is fresh.
        val f =
            LockFixture(
                "if (n != counter.get()) result.set(\"local-\$n\")\ne",
                before = "        val n = counter.incrementAndGet()",
                members = e6Steal("            val t = counter.incrementAndGet()"),
                operations = E6_OPERATIONS,
                hole = "0 <= S.seq",
            )
        val m = f.lower(dir)
        val r = verify(m, parseSidecar(File(dir, "Lock.kv").path, f.sidecar), File(dir, "weak.vpr"))
        val text = r.render()
        val step = r.row("reserve run")
        assertEquals(Verdict.FAILED, step.verdict, text)
        assertEquals(listOf(RESERVE_FRESH), step.ids(), text)
        assertEquals(SubStatus.INVARIANT_ONLY, step.sub, text)
        assertTrue(text.lines().any { it.startsWith("  step reserve run ") && it.endsWith("FAILED (invariant only)") }, text)
        assertTrue("reserve run" in r.propertyCheck!!.actions, text)
    }
}
