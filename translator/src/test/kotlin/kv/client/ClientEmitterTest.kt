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

    /** The module and sidecar of a fixture; with [suspend], the suspend client and sidecar of DESIGN-E5. */
    private fun build(
        body: String,
        helpers: String = "",
        items: String = "free mode = this.config.mode\nresult res",
        extra: String = "",
        suspend: Boolean = false,
    ): Pair<Module, Sidecar> {
        val head = if (suspend) ClientFixture.SUSPEND_SIDECAR_HEAD else ClientFixture.SIDECAR_HEAD
        val outcome = ClientFixture.resolve(dir, ClientFixture.client(body, helpers, suspend = suspend), ClientFixture.sidecar(items, extra, head))
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
        suspend: Boolean = false,
    ): Emitted = build(body, helpers, items, extra, suspend).let { (m, s) -> emitClient(m, s) }

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

    private fun hole(text: String) = "hole loop Client.run \"\"\"\n$text\n\"\"\"\n"

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

    /** `exc != CHK` holds (a CHK becomes ClientError(GET)) and `exc != RTE` does not (an RTE is rethrown). */
    private val rethrowItems =
        """
        free mode = this.config.mode
        result res
        ensures NO_CHK: exc != CHK
          source none "fixture"
        ensures NO_RTE: exc != RTE
          source none "fixture"
        """

    @Test
    fun `a test of the caught exception reads its catch's flag in flag mode (DESIGN-E5 2 row 4)`() {
        val (m, s) =
            build(
                """
                val cur =
                    try {
                        config.get.invoke(key)
                    } catch (e: Exception) {
                        if (e is RuntimeException) throw e
                        throw ClientError(Code.GET, e)
                    }
                if (cur != null) return cur
                throw ClientError(Code.SUP)
                """,
                items = rethrowItems,
            )
        val ls = emitClient(m, s).lines()
        val flag = ls.single { Regex("""^e_\d+_\d+, cur__\d+ := get\(k\)$""").matches(it) }.substringBefore(",")
        assertTrue("if ($flag == ClientError_GET || $flag == ClientError_SUP || $flag == REE || $flag == RTE) {" in ls, ls.joinToString("\n"))
        assertEquals(emptyList(), failures(emitClient(m, s, "NO_CHK")))
        assertTrue(failures(emitClient(m, s, "NO_RTE")).single().startsWith("Postcondition of run might not hold"))
    }

    @Test
    fun `a negated test of the caught exception reads the saved kind in exc mode (DESIGN-E5 2 row 4)`() {
        val (m, s) =
            build(
                """
                var cur: Data<T>? = null
                try {
                    if (config.mode != Mode.ONLY_UPDATE) cur = config.get.invoke(key)
                } catch (e: Exception) {
                    if (e !is RuntimeException) throw ClientError(Code.GET, e)
                    throw e
                }
                if (cur != null) return cur
                throw ClientError(Code.SUP)
                """,
                items = rethrowItems,
            )
        val ls = emitClient(m, s).lines()
        val saved = ls.single { Regex("""^var caught_\d+_\d+: Int := exc$""").matches(it) }.removePrefix("var ").substringBefore(":")
        assertTrue("if (!($saved == ClientError_GET || $saved == ClientError_SUP || $saved == REE || $saved == RTE)) {" in ls, ls.joinToString("\n"))
        assertEquals(emptyList(), failures(emitClient(m, s, "NO_CHK")))
        assertTrue(failures(emitClient(m, s, "NO_RTE")).single().startsWith("Postcondition of run might not hold"))
    }

    @Test
    fun `a catch of a class the module throws tests the kinds of that class (DESIGN-E5 2 item 1)`() {
        val (m, s) =
            build(
                """
                val cur =
                    try {
                        read(key)
                    } catch (e: ClientError) {
                        throw ClientError(Code.SUP, e)
                    }
                if (cur != null) return cur
                throw ClientError(Code.SET)
                """,
                """
                private fun read(key: String): Data<T>? =
                    try {
                        config.get.invoke(key)
                    } catch (e: Exception) {
                        throw ClientError(Code.GET, e)
                    }
                """,
                items =
                    """
                    free mode = this.config.mode
                    result res
                    ensures NO_GET: exc != ClientError(GET)
                      source none "fixture"
                    ensures NO_SUP: exc != ClientError(SUP)
                      source none "fixture"
                    """,
            )
        val ls = emitClient(m, s).lines()
        // The helper's GET reaches the catch (an Error does not): one equality test, in exc mode.
        assertTrue("if (exc != NONE && exc == ClientError_GET) {" in ls, ls.joinToString("\n"))
        assertEquals(emptyList(), failures(emitClient(m, s, "NO_GET")))
        assertTrue(failures(emitClient(m, s, "NO_SUP")).single().startsWith("Postcondition of run might not hold"))
    }

    @Test
    fun `a cancellation point's model takes cx and may raise CANCEL only with it, a plain model is unchanged (DESIGN-E5 2 item 2)`() {
        val ls =
            emit(
                """
                val token = config.slock.tryLock(key, Kind.CREATE)
                if (token != null) config.slock.unLock(key, Kind.CREATE, token)
                val cur = config.sget(key)
                if (cur != null) return cur
                return Data(value = produce(supplier), ttl = 0L)
                """,
                "private suspend fun produce(f: suspend () -> T?): T? = f()",
                suspend = true,
            ).lines()
        val text = ls.joinToString("\n")
        val lockEnsures = "ensures exc == NONE || (LOCK_CALLS_THROW && exc == RTE) || (cx && exc == CANCEL) || (MAY_ERR && exc == ERR)"
        val expected =
            listOf(
                "domain CancelledRelease {",
                "function cancelKeeps(key: Key, kind: Int, t: Int): Bool",
                "define IS_EXCEPTION(e) (REE <= e && e <= CANCEL)",
                "method SuspendLock_tryLock(key: Key, kind: Int, cx: Bool) returns (exc: Int, t: Int)",
                "method SuspendLock_unLock(key: Key, kind: Int, token: Int, cx: Bool) returns (exc: Int, released: Bool)",
                // A4 does not cover a cancellation; a cancelled release may or may not keep the obligation (2.3).
                "ensures exc != NONE && exc != CANCEL && !THROWING_UNLOCK_RELEASES ==> Lease(key, kind, token)",
                "ensures exc == CANCEL && cancelKeeps(key, kind, token) ==> Lease(key, kind, token)",
                "method sget(key: Key, cx: Bool) returns (exc: Int, d: Ref)",
                "ensures exc == NONE || exc == RTE || exc == CHK || (cx && exc == CANCEL) || (MAY_ERR && exc == ERR)",
                "method supplier(cx: Bool) returns (exc: Int, v: Int)",
                "ensures exc == NONE || exc == RTE || exc == CHK || exc == INTR || (cx && exc == CANCEL) || (MAY_ERR && exc == ERR)",
                // A model without CANCEL keeps the text of DESIGN-E 6.6.
                "method get(key: Key) returns (exc: Int, d: Ref)",
                "ensures exc == NONE || exc == RTE || exc == CHK || (MAY_ERR && exc == ERR)",
            )
        expected.forEach { assertTrue(it in ls, "lacks '$it':\n$text") }
        assertEquals(2, ls.count { it == lockEnsures }, text)
        // Every call of a cancellation point passes its context: outside withContext(NonCancellable), true.
        for (call in listOf("""token__\d+ := SuspendLock_tryLock\(k, CREATE, true\)""", """released_\d+_\d+ := SuspendLock_unLock\(k, CREATE, token__\d+, true\)""", """cur__\d+ := sget\(k, true\)""", """r_\d+_\d+ := supplier\(true\)""")) {
            assertTrue(ls.any { Regex("^e_\\d+_\\d+, $call$").matches(it) }, "no call $call:\n$text")
        }
    }

    @Test
    fun `a release a cancellation may end keeps the obligation by cancelKeeps, so LD_noleak fails (DESIGN-E5 2 item 3)`() {
        val body =
            """
            val token = config.LOCK.tryLock(key, Kind.CREATE)
            if (token != null) config.LOCK.unLock(key, Kind.CREATE, token)
            return Data(value = callable.call(), ttl = 0L)
            """
        // A cancellable release (cx is true): the cancellation may come before the release took effect.
        val cancelled = failures(emit(body.replace("LOCK", "slock"), extra = assumes, suspend = true))
        assertTrue(cancelled.singleOrNull()?.let { it.startsWith("Postcondition of run might not hold") && "NO_LEASES" in it } == true, "LD_noleak must fail alone: $cancelled")
        // The same release that no cancellation ends: A4 ends the obligation on a throw.
        assertEquals(emptyList(), failures(emit(body.replace("LOCK", "lock"), extra = assumes)))
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
