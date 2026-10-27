package kv.client

import kv.emit.Emitted
import kv.emit.emitClient
import kv.emit.switchableIds
import kv.kvir.Module
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import kv.kvir.printModule
import kv.sidecar.Sidecar
import kv.sidecar.SidecarException
import kv.verify.Silicon
import kv.verify.SiliconOutcome
import kv.verify.classify
import kv.verify.SiliconBudget
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DESIGN-E 6.6 (E3): the client emitter's encoding rules on small fixtures in the shape of ReqShield, each checked on
 * the emitted text and, where the rule decides a verdict, with Silicon: the exception-flag dispatch, the guard of the
 * code after a raising statement (G1), the else of an early return (G2), pending returns and breaks, the finally that
 * cancels a pending return, the task precondition and the spawn, the trace events, the loop frame and hole vocabulary,
 * arbitrary values, and the isolation runs.
 */
class ClientEmitterTest {
    @TempDir
    lateinit var dir: File

    private fun build(
        body: String,
        helpers: String = "",
        items: String = "free mode = this.config.mode\nresult res",
        extra: String = "",
    ): Pair<Module, Sidecar> {
        val outcome = ClientFixture.resolve(dir, ClientFixture.client(body, helpers), ClientFixture.sidecar(items, extra))
        assertNull(outcome.compileMessages, outcome.compileMessages)
        assertEquals(emptyList(), outcome.errors)
        val m = outcome.module ?: fail("no module")
        assertFalse(m.incomplete, printModule(m))
        assertEquals(emptyList(), checkWellFormed(m))
        return m to outcome.sidecar!!
    }

    private fun emit(
        body: String,
        helpers: String = "",
        items: String = "free mode = this.config.mode\nresult res",
        extra: String = "",
    ): Emitted = build(body, helpers, items, extra).let { (m, s) -> emitClient(m, s) }

    /** The emitted lines, trimmed, without `//` comments. */
    private fun Emitted.lines(): List<String> = text.lines().map { it.substringBefore("//").trim() }.filter { it.isNotEmpty() }

    /** Silicon's outcome on [e]. */
    private fun silicon(e: Emitted): SiliconOutcome {
        val f = File(dir, "model.vpr").apply { writeText(e.text) }
        val budget = SiliconBudget()
        return classify(Silicon.run(f, Silicon.defaultHome(), budget), budget)
    }

    /** The failing lines of [e]'s Silicon run, each with the source text of its line. */
    private fun failures(e: Emitted): List<String> =
        when (val o = silicon(e)) {
            SiliconOutcome.Verified -> emptyList()
            is SiliconOutcome.Failed -> o.errors.map { "${it.message.substringBefore(".")} @ ${e.text.lines()[it.line - 1].trim()}" }
            is SiliconOutcome.Abnormal -> fail("Silicon: ${o.reason}: ${o.errors}\n${e.text}")
        }

    /** A spawn helper that releases the lock when the executor rejects the task (ReqShield's runInBackground). */
    private val spawnHelper =
        """
        private fun background(
            key: String,
            kind: Kind,
            token: String?,
            task: () -> Unit,
        ) {
            try {
                CompletableFuture.runAsync(task, config.executor)
            } catch (e: RejectedExecutionException) {
                if (token != null) config.lock.unLock(key, kind, token)
            }
        }
        """

    /** The same helper whose catch only logs (M2's shape). */
    private val loggingSpawnHelper = spawnHelper.replace("if (token != null) config.lock.unLock(key, kind, token)", "log.warn(\"rejected for key '{}'\", key, e)")

    /** The canonical assumptions of DESIGN-E 4 (A2 off), for fixtures that must verify. */
    private val assumes =
        """
        assume A1: errors-are-fatal
          source none "fixture"
        assume A3: accepted-tasks-run
          source none "fixture"
        assume A4: throwing-release-ends-obligation
          source none "fixture"
        assume A5: throwing-acquire-takes-nothing
          source none "fixture"
        """.trimIndent()

    @Test
    fun `a catch of every kind whose body throws has no success branch, and the rest runs under exc == NONE (G1)`() {
        val e =
            emit(
                """
                val cur = runCatching { config.get.invoke(key) }.getOrElse { throw ClientError(Code.GET, it) }
                if (cur != null) return cur
                return Data(value = callable.call(), ttl = 0L)
                """,
            )
        val ls = e.lines()
        val call = ls.indexOfFirst { Regex("""^e_\d+_\d+, cur__\d+ := get\(k\)$""").matches(it) }
        assertTrue(call > 0, e.text)
        val flag = ls[call].substringBefore(",")
        // The catch takes every kind (Throwable) and always raises: one branch, no test of the kind.
        assertEquals(listOf("if ($flag != NONE) {", "exc := ClientError_GET", "}", "if (exc == NONE) {"), ls.subList(call + 1, call + 5))
        // G2: `if (cur != null) return cur` moves the rest into its else.
        assertTrue(ls.subList(call, ls.size).containsAll(listOf("res := cur__17", "} else {")), e.text)
        assertEquals(emptyList(), failures(e))
    }

    @Test
    fun `an empty catch of one kind with a success branch propagates only the other kinds, negated as e != K`() {
        val e =
            emit(
                """
                val data = Data(value = callable.call(), ttl = 0L)
                background(key, Kind.CREATE, null) { config.set.invoke(key, data, 0L) }
                return data
                """,
                loggingSpawnHelper,
            )
        val ls = e.lines()
        val at = ls.indexOfFirst { it.matches(Regex("""^if \(e_\d+_\d+_at\d+ == NONE\) \{$""")) || it.matches(Regex("""^if \(e_\d+_\d+ == NONE\) \{$""")) }
        assertTrue(at > 0, e.text)
        val flag = ls[at].removePrefix("if (").substringBefore(" ==")
        // M2's shape: the catch only logs, so a rejection is swallowed and only an Error propagates.
        assertTrue("} elseif ($flag != REE) {" in ls && "exc := $flag" in ls, e.text)
        assertFalse(ls.any { "!$flag ==" in it }, e.text)
        assertEquals(emptyList(), failures(e))
    }

    @Test
    fun `a return inside a try with a finally is a pending flag, and a release that throws in the finally cancels it`() {
        val e =
            emit(
                """
                val token = config.lock.tryLock(key, Kind.CREATE)
                if (token != null) {
                    var keep = false
                    try {
                        val cached = config.get.invoke(key)
                        if (cached != null) return cached
                        keep = true
                    } finally {
                        if (!keep) config.lock.unLock(key, Kind.CREATE, token)
                    }
                    background(key, Kind.CREATE, token) { config.lock.unLock(key, Kind.CREATE, token) }
                }
                return Data(value = callable.call(), ttl = 0L)
                """,
                spawnHelper,
                extra = assumes,
            )
        val ls = e.lines()
        val flag = ls.single { it.matches(Regex("""^var ret_\d+_\d+: Bool := false$""")) }.removePrefix("var ").substringBefore(":")
        // The return writes the result and sets the flag; the finally's raise clears both (the hand model's ret_96).
        val ret = ls.indexOf("res := cached__21")
        assertEquals("$flag := true", ls[ret + 1], e.text)
        val clear = ls.indexOf("$flag := false")
        assertTrue(clear > ret && ls[clear + 1] == "res := null" && ls[clear - 1].startsWith("exc := e_"), e.text)
        // The rest after the try runs under the exception and the flag.
        assertTrue("if (exc == NONE && !$flag) {" in ls, e.text)
        assertEquals(emptyList(), failures(e))
    }

    private val loop =
        """
        var attempts = 0
        var failures = 0
        while (attempts < config.maxAttempt) {
            attempts++
            try {
                val cached = config.get.invoke(key)
                if (cached != null) return cached
                failures = 0
            } catch (e: Exception) {
                if (++failures >= 3) break
            }
        }
        return Data(value = callable.call(), ttl = 0L)
        """

    private fun hole(text: String) = "hole loop Client.run@19 \"\"\"\n$text\n\"\"\"\n"

    @Test
    fun `a loop with a return and a break - flags in the loop condition, the tool frame, and the hole vocabulary`() {
        val items = "free mode = this.config.mode\nfree maxAttempt = this.config.maxAttempt\nresult res"
        val e = emit(loop, items = items, extra = hole("  0 <= attempts && (failures >= 3 ==> broke) &&\n  (returned ==> result != null && exc == NONE)"))
        val ls = e.lines()
        val ret = ls.single { it.startsWith("var ret_") }.removePrefix("var ").substringBefore(":")
        val brk = ls.single { it.startsWith("var brk_") }.removePrefix("var ").substringBefore(":")
        assertTrue(ls.indexOf("var $ret: Bool := false") < ls.indexOfFirst { it.startsWith("while") }, e.text)
        assertEquals("while (exc == NONE && !$ret && !$brk && attempts__17 < maxAttempt)", ls.single { it.startsWith("while") })
        assertTrue("invariant NO_LEASES" in ls && "decreases maxAttempt - attempts__17" in ls, e.text)
        assertTrue("invariant exc == NONE || (MAY_ERR && exc == ERR)" in ls, e.text)
        // The hole: Kotlin names, `broke`, `returned`, `result`; one invariant per group of lines ending with &&.
        assertTrue("invariant 0 <= attempts__17 && (failures__18 >= 3 ==> $brk)" in ls, e.text)
        assertTrue("invariant $ret ==> res != null && exc == NONE" in ls, e.text)
        assertTrue("if (exc == NONE && !$ret) {" in ls, e.text)
        assertEquals(emptyList(), failures(e))
        // A name the hole vocabulary does not have is an error at its position in the sidecar.
        val (m, s) = build(loop, items = items, extra = hole("  0 <= attempts && nonsense > 0"))
        val error = assertFailsWith<SidecarException> { emitClient(m, s) }.errors.single()
        assertTrue("unknown name 'nonsense'" in error.message, error.toString())
        assertEquals(2, error.pos.line - s.holes.single().pos.line + 1, error.toString())
    }

    @Test
    fun `a spawn hands the task the obligation its spawning call created, and the exhale checks it (LD_transfer)`() {
        val body =
            """
            val data = Data(value = callable.call(), ttl = 0L)
            val token = config.lock.tryLock(key, Kind.UPDATE)
            if (token != null) {
                background(key, Kind.UPDATE, token) {
                    try {
                        config.set.invoke(key, data, 0L)
                    } finally {
                        config.lock.unLock(key, Kind.UPDATE, token)
                    }
                }
            }
            return data
            """
        val e = emit(body, spawnHelper, extra = assumes)
        val ls = e.lines()
        val pre = ls.indexOfFirst { it.matches(Regex("""^define TASK_\d+_PRE\(k, token__18\) \($""")) }
        assertTrue(pre >= 0, e.text)
        // Part 3 (the if around the spawn), then part 1: the obligation tryLock created, held if its token is not null.
        assertEquals("token__18 != NULL && (token__18 != NULL ==> Lease(k, UPDATE, token__18)))", ls[pre + 1])
        assertTrue(ls.any { it.matches(Regex("""^exhale TASK_\d+_PRE\(k, token__18\)$""")) }, e.text)
        assertEquals(emptyList(), failures(e))
        // A release before the spawn leaves the caller nothing to hand over: the exhale fails (M12's LD_transfer).
        val early = emit(body.replace("    background(key", "    config.lock.unLock(key, Kind.UPDATE, token)\n    background(key"), spawnHelper, extra = assumes)
        assertTrue(failures(early).any { it.startsWith("Exhale might fail") && it.contains("exhale TASK_") }, early.text)
        // A release of another lock type in the task: the precondition stays, the release fails LD_own (M6).
        val other = emit(body.replace("unLock(key, Kind.UPDATE, token)\n                    }", "unLock(key, Kind.CREATE, token)\n                    }"), spawnHelper, extra = assumes)
        assertTrue(other.lines().any { it.endsWith("(token__18 != NULL ==> Lease(k, UPDATE, token__18)))") }, other.text)
        assertTrue(failures(other).any { it.startsWith("The precondition of method Lock_unLock might not hold") }, other.text)
    }

    @Test
    fun `a racy field and a null of a type parameter are arbitrary values, declared and never assigned`() {
        val e =
            emit(
                """
                val data = Data(value = supply(callable), ttl = 0L)
                val token = config.lock.tryLock(key, Kind.CREATE)
                last = token
                if (token != null) config.lock.unLock(key, Kind.CREATE, last ?: token)
                return data
                """,
                "@Volatile private var last: String? = null\n\nprivate fun supply(callable: Callable<T?>): T? = runCatching { callable.call() }.getOrElse { null }",
            )
        val ls = e.lines()
        assertTrue(ls.any { it.matches(Regex("""^var racy_\d+_\d+: Int$""")) }, e.text)
        assertTrue(ls.any { it.matches(Regex("""^var arb_\d+_\d+: Int$""")) }, e.text)
        assertFalse(ls.any { it.startsWith("racy_") && ":=" in it }, e.text)
        // The release may name another token than the one held: LD_own fails (M5's shape).
        assertTrue(failures(e).any { it.startsWith("The precondition of method Lock_unLock might not hold") }, e.text)
    }

    @Test
    fun `trace events chain by condition, a task inherits the path value and has false for a run value`() {
        val trace =
            """
            trace {
              first = first Config.get
              npoll = count Config.get where !first
              nsup  = count Callable.call
              ntryC = count Lock.tryLock where kind == CREATE
              ntryU = count Lock.tryLock where kind == UPDATE
              derived PHASE = first == HIT ? UPDATE : CREATE
              derived GAVE_UP = npoll >= 0
            }
            """.trimIndent()
        val items =
            """
            free mode = this.config.mode
            result res
            guard SUP: Callable.call requires PHASE == CREATE ==> holds(Lease(k, CREATE, _)) || GAVE_UP
              source none "fixture"
            ensures ONE: nsup <= 1
              source none "fixture"
            """
        val e =
            emit(
                """
                val cur = config.get.invoke(key)
                if (cur != null) {
                    val token = config.lock.tryLock(key, Kind.UPDATE)
                    if (token != null) {
                        background(key, Kind.UPDATE, token) {
                            try {
                                callable.call()
                            } finally {
                                config.lock.unLock(key, Kind.UPDATE, token)
                            }
                        }
                    }
                    return cur
                }
                return Data(value = callable.call(), ttl = 0L)
                """,
                spawnHelper,
                items,
                trace + "\n" + assumes,
            )
        val ls = e.lines()
        // The second group of Config.get is the rest of the chain (`!first`): an else; tryLock's groups cover the enum.
        val ev = ls.indexOf("define EV_get(tr, e, d) {")
        assertEquals(listOf("if (tr.first == 0) {"), ls.subList(ev + 1, ev + 2), e.text)
        assertTrue(ls.subList(ev, ls.size).take(8).contains("} else {"), e.text)
        assertTrue(ls.contains("define EV_Lock_tryLock(tr, kind) {"), e.text)
        // The task: the spawn site is on the hit path, so phase == UPDATE; GAVE_UP is false there (no wait).
        assertTrue(ls.any { it.matches(Regex("""^phase == UPDATE && token__\d+ != NULL && .*""")) }, e.text)
        assertTrue(ls.any { it.matches(Regex("""^assert GUARD_SUP\(k, phase, false\)$""")) }, e.text)
        assertTrue(ls.any { it == "assert GUARD_SUP(k, PHASE(tr), GAVE_UP(tr))" }, e.text)
        // SUP's ensures reads only nsup, which the task writes: the task states it too.
        assertEquals(2, ls.count { it == "ensures tr.nsup <= 1" }, e.text)
        assertEquals(emptyList(), failures(e))
    }

    @Test
    fun `an isolation run keeps one switchable check and turns the others into true`() {
        val (m, s) =
            build(
                """
                val token = config.lock.tryLock(key, Kind.CREATE)
                return Data(value = callable.call(), ttl = 0L)
                """,
                items =
                    """
                    free mode = this.config.mode
                    result res
                    ensures R: exc == NONE ==> res != null
                      source none "fixture"
                    """,
            )
        assertEquals(listOf("LD_noleak", "LD_wait", "R"), switchableIds(s))
        val all = emitClient(m, s).lines()
        val onlyR = emitClient(m, s, "R").lines()
        assertTrue("ensures NO_LEASES" in all && "ensures exc == NONE ==> res != null" in all)
        assertTrue("ensures true" in onlyR && "ensures exc == NONE ==> res != null" in onlyR && "ensures NO_LEASES" !in onlyR)
        // The lock is never released: only the LD_noleak run fails.
        assertTrue(failures(emitClient(m, s, "LD_noleak")).single().startsWith("Postcondition of run might not hold"))
        assertEquals(emptyList(), failures(emitClient(m, s, "R")))
    }
}
