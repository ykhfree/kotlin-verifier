package kv.lower

import kv.kvir.And
import kv.kvir.Captured
import kv.kvir.CounterVar
import kv.kvir.DiagnosticKind
import kv.kvir.DispositionKind
import kv.kvir.Ite
import kv.kvir.KType
import kv.kvir.Module
import kv.kvir.NullLit
import kv.kvir.Or
import kv.kvir.Reservation
import kv.kvir.Reserve
import kv.kvir.Status
import kv.kvir.TokenOf
import kv.kvir.Var
import kv.kvir.checkWellFormed
import kv.kvir.exprs
import kv.kvir.incomplete
import kv.kvir.printModule
import kv.kvir.returnedValue
import kv.kvir.walk
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DESIGN-D.md 6.3-6.5 (milestone D0): the reserve step R. The accepted shapes (a token template around the counter, a
 * raw Long, a helper call, and the result shapes of W10 and DESIGN-D.md 7) lower to a [Reserve] whose val the action
 * reads as an exact value; every other counter increment outside an action stays an unsupported module-state access,
 * and the report names the rule it breaks (6.3). AI-CODE.md 15.1 item 5: the negative shapes stay unsupported.
 */
class ReserveTest {
    @TempDir
    lateinit var dir: File

    private companion object {
        const val TEMPLATE = "        val token = \"local-\${counter.incrementAndGet()}\""
        const val NEXT_TOKEN = "        private fun nextToken(): String = \"local-\${counter.incrementAndGet()}\""

        /** A new entry that carries [token]; the cell `result` gets the token. */
        fun install(token: String) = "if (e != null) return@compute e\nresult.set($token)\nInfo(Semaphore(0), now, AtomicBoolean(true), $token)"

        /** `fun run` with the AtomicBoolean cell `granted`, [before] before the compute, and the result [result]. */
        fun grantedRun(
            result: String,
            before: String = TEMPLATE,
            cells: String = "        val granted = AtomicBoolean(false)",
        ) = """
            |    fun run(key: String, now: Long): String? {
            |$before
            |$cells
            |        lockMap.compute(key) { _, e ->
            |            if (e != null) return@compute e
            |            granted.set(true)
            |            Info(Semaphore(0), now, AtomicBoolean(true), token)
            |        }
            |        return $result
            |    }
        """.trimMargin()
    }

    private val Module.reserve: Reserve? get() = operations.single { it.name == "run" }.reserve

    private fun LockFixture.where(
        fragment: String,
        after: String? = null,
    ) = at(fragment, after).let { (l, c) -> "Lock.kt:$l:$c" }

    /** Asserts that `run` has a reserve step for the val declared at [decl], of [type], and returns it. */
    private fun assertReserve(
        m: Module,
        f: LockFixture,
        decl: String,
        type: KType,
    ): Reserve {
        val text = assertSupported(m)
        val r = assertNotNull(m.reserve, text)
        assertEquals(f.at(decl).first, r.local.pos.line, text)
        assertEquals(type, r.local.type, text)
        assertEquals("seq", r.counter.name, text)
        // The action reads the val as its one free value of origin Reservation (W17), a parameter of the method.
        assertEquals(listOf(r.local), m.run.free.filter { it.origin is Reservation }.map { it.local }, text)
        // The increment has an ASSUMPTION (W13), and the ledger has no unsupported node: R translated it (W14).
        assertTrue(m.diagnostics.any { it.id == r.assumption && it.kind == DiagnosticKind.ASSUMPTION && it.pos == r.pos }, text)
        assertEquals(emptyList(), m.ledger.filter { it.kind == DispositionKind.UNSUPPORTED }, text)
        assertTrue(m.diagnostics.none { it.message.startsWith(NOT_RESERVE) }, m.diagnostics.toString())
        return r
    }

    /** Asserts that `run` has no reserve step and that the increment at [increment] breaks rule [rule] with [why]. */
    private fun assertNotReserve(
        m: Module,
        f: LockFixture,
        increment: String,
        rule: Int,
        why: String,
        after: String? = null,
    ) {
        assertNull(m.reserve, m.runText)
        val pos = f.at(increment, after)
        val d = m.diagnostics.singleOrNull { it.kind == DiagnosticKind.INFO && it.message.startsWith(NOT_RESERVE) } ?: kotlin.test.fail("no reason in ${m.diagnostics}")
        assertEquals(pos, d.pos.line to d.pos.col, d.message)
        assertTrue(d.message.startsWith("$NOT_RESERVE (DESIGN-D 6.3 rule $rule): ") && why in d.message, d.message)
        // As before DESIGN-D: the increment stays an unsupported module-state access, so the module is INCOMPLETE.
        assertTrue(m.incomplete)
        assertTrue(m.ledger.any { it.kind == DispositionKind.UNSUPPORTED && it.pos.line == pos.first }, m.ledger.joinToString("\n"))
    }

    // ---- accepted shapes (DESIGN-D.md 9, D0: four shapes, with their KVIR) ----

    @Test
    fun `a token template around the counter before the action is a reserve step`() {
        val f = LockFixture(install("token"), before = TEMPLATE, operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        val r = assertReserve(m, f, "val token", KType.Abs("Token", false))
        val line = f.at("val token").first
        assertEquals(f.at("counter.incrementAndGet()"), r.pos.line to r.pos.col)
        assertEquals(f.at("\"local-"), r.template!!.line to r.template!!.col)
        // The KVIR golden of the operation: the step before the action, and the val as a reserved free value.
        val text = m.runText
        val (l, c) = f.at("counter.incrementAndGet()")
        assertTrue(text.lines().any { it.matches(Regex("""  reserve token__$line: Token := tokenOf\(seq\.incrementAndGet\(\)\) \[ASSUME A$l] +@$l:$c""")) }, text)
        assertTrue(text.lines().any { it.matches(Regex("""    free  token__$line: Token <- token \(reserved @$l:$c\) +@$line:9""")) }, text)
        assertEquals(listOf("if e__${f.at("{ _, e ->").first} != null", "  exit keep", "else", "  result__${f.at("val result").first} := token__$line", "  new new_${f.at("Info(Semaphore").first}_1: Info {permits := 0, isHeld := true, token := token__$line, expiresAt := now}", "  exit put(new_${f.at("Info(Semaphore").first}_1)"), shape(text).map { it.replace(Regex("new_(\\d+)_\\d+"), "new_$1_1") })
        // The val's declaration is the step's: no ledger node, no free-source entry.
        val decl = f.at("val token").first
        assertTrue(m.ledger.none { it.pos.line == decl }, m.ledger.joinToString("\n"))
        assertTrue(r.translated.isNotEmpty())
    }

    @Test
    fun `a raw Long from the counter is a reserve step whose val is an Int`() {
        val f = LockFixture(install("\"local-\$n\""), before = "        val n = counter.incrementAndGet()", operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        val r = assertReserve(m, f, "val n", KType.IntT)
        assertNull(r.template)
        // The action builds the token from the exact Long: tokenOf(n).
        val tokens = m.run.body.walk().flatMap { it.exprs() }.flatMap { it.walk() }.filterIsInstance<TokenOf>().toList()
        assertTrue(tokens.isNotEmpty() && tokens.all { (it.n as? Var)?.local == r.local }, m.runText)
    }

    @Test
    fun `a call of a companion helper that returns the template is a reserve step that translates the helper`() {
        // G-sonnet-1 (AI-CODE.md 15.1): private fun nextToken(): String = "local-${tokenSequence.incrementAndGet()}".
        val f = LockFixture(install("token"), before = "        val token = nextToken()", companion = NEXT_TOKEN, operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        val r = assertReserve(m, f, "val token", KType.Abs("Token", false))
        // The step's increment is the one in the helper.
        assertEquals(f.at("counter.incrementAndGet()"), r.pos.line to r.pos.col)
        val helper = f.at("private fun nextToken").first
        assertTrue(m.ledger.none { it.pos.line == helper }, m.ledger.joinToString("\n"))
    }

    @Test
    fun `the result if (granted get) token else null reads the cell and the reserved val (W10, DESIGN-D 7)`() {
        // T-haiku-4 (AI-CODE.md 15.1): return if (granted.get()) token else null.
        val f = LockFixture("", run = grantedRun("if (granted.get()) token else null"), operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        val r = assertReserve(m, f, "val token", KType.Abs("Token", false))
        val v = assertIs<Ite>(m.operations.single().returnedValue, m.runText)
        assertEquals("granted__${f.at("val granted").first}", (v.cond as Var).local.name)
        assertEquals(r.local, (v.a as Var).local)
        assertIs<NullLit>(v.b)
        assertEquals(KType.Abs("Token", true), v.type)
        assertEquals(KType.Abs("Token", true), m.operations.single().result!!.type)
    }

    @Test
    fun `a result condition of two cells is an Or (G-sonnet-1)`() {
        val f =
            LockFixture(
                "",
                run = grantedRun("if (granted.get() || unbacked.get()) token else null", cells = "        val granted = AtomicBoolean(false)\n        val unbacked = AtomicBoolean(false)"),
                operations = LockFixture.RESULT_OPERATION,
            )
        val m = f.lower(dir)
        assertReserve(m, f, "val token", KType.Abs("Token", false))
        val v = assertIs<Ite>(m.operations.single().returnedValue, m.runText)
        assertIs<Or>(v.cond)
    }

    @Test
    fun `a result condition with && and ! of cells, and a null first branch, keeps the Token type`() {
        val f = LockFixture("", run = grantedRun("if (!granted.get() && true) null else token"), operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        assertReserve(m, f, "val token", KType.Abs("Token", false))
        val v = assertIs<Ite>(m.operations.single().returnedValue, m.runText)
        assertIs<And>(v.cond)
        assertIs<NullLit>(v.a)
        assertEquals(KType.Abs("Token", true), v.type)
    }

    @Test
    fun `a conditional result of cells only is supported without a reserve step (W10 change)`() {
        val run =
            """
            |    fun run(key: String, now: Long): String? {
            |        val result = AtomicReference<String?>(null)
            |        val granted = AtomicBoolean(false)
            |        lockMap.compute(key) { _, e ->
            |            if (e != null) granted.set(true)
            |            if (e != null) result.set(e.token)
            |            e
            |        }
            |        return if (granted.get()) result.get() else null
            |    }
            """.trimMargin()
        val f = LockFixture("", run = run, operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        assertSupported(m)
        assertNull(m.reserve)
        assertIs<Ite>(m.operations.single().returnedValue)
    }

    @Test
    fun `a result that reads another local is still unsupported, and names the part`() {
        val f = LockFixture("", run = grantedRun("if (granted.get()) token else other", before = TEMPLATE + "\n        val other = \"local-\${now}\""), operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        val u = assertUnsupported(m, "operation result", f.at("other", after = "return if"))
        assertTrue("W10, DESIGN-D.md 7" in u.reason && u.reason.endsWith("other is none of them"), u.reason)
    }

    @Test
    fun `a log call may read the reserved val`() {
        val before = TEMPLATE + "\n        log.info(\"token {}\", token)"
        val f =
            LockFixture(
                install("token"),
                before = before,
                operations = LockFixture.RESULT_OPERATION,
                members = "    private val log = org.slf4j.LoggerFactory.getLogger(Lock::class.java)",
            )
        val m = f.lower(dir)
        assertReserve(m, f, "val token", KType.Abs("Token", false))
    }

    @Test
    fun `a reserve step in a Mono_fromCallable lambda before the compute`() {
        val run =
            """
            |    fun run(key: String, now: Long) =
            |        reactor.core.publisher.Mono.fromCallable {
            |            val result = AtomicReference<String?>(null)
            |            val token = "local-${'$'}{counter.incrementAndGet()}"
            |            lockMap.compute(key) { _, e ->
            |                if (e != null) return@compute e
            |                result.set(token)
            |                Info(Semaphore(0), now, AtomicBoolean(true), token)
            |            }
            |            result.get()
            |        }
            """.trimMargin()
        val f = LockFixture("", run = run, operations = "use reactor.Mono.fromCallable\n" + LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        assertReserve(m, f, "val token", KType.Abs("Token", false))
    }

    // ---- rejected shapes (D0: six or more), each stays unsupported and names its rule ----

    @Test
    fun `an increment in a val inside an if is not a reserve step (rule 2)`() {
        val run =
            """
            |    fun run(key: String, now: Long): String? {
            |        val result = AtomicReference<String?>(null)
            |        if (now >= 0L) {
            |            val token = "local-${'$'}{counter.incrementAndGet()}"
            |            lockMap.compute(key) { _, e ->
            |                if (e != null) return@compute e
            |                result.set(token)
            |                Info(Semaphore(0), now, AtomicBoolean(true), token)
            |            }
            |        }
            |        return result.get()
            |    }
            """.trimMargin()
        val f = LockFixture("", run = run, operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        assertNotReserve(m, f, "counter.incrementAndGet()", 2, "val token is inside an if or when at ${f.where("if (now >= 0L)")}")
        // The input check names the rule with the construct (AI-CODE.md 13 fix 1).
        val u = assertUnsupported(m, "input token", f.at("val token"))
        assertTrue("counter.incrementAndGet() at ${f.where("counter.incrementAndGet()")}, $NOT_RESERVE (DESIGN-D 6.3 rule 2)" in u.reason, u.reason)
    }

    @Test
    fun `a conditional increment is not a reserve step (rule 2)`() {
        val before = "        val n = if (now > 0L) counter.incrementAndGet() else 0L"
        val f = LockFixture(install("\"local-\$n\""), before = before, operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        assertNotReserve(m, f, "counter.incrementAndGet()", 2, "the increment is not the value of a local val")
        assertUnsupported(m, "input n", f.at("val n"))
    }

    @Test
    fun `an increment in a val inside a loop is not a reserve step (rule 2)`() {
        val run =
            """
            |    fun run(key: String, now: Long): String? {
            |        val result = AtomicReference<String?>(null)
            |        for (i in 0 until 1) {
            |            val token = "local-${'$'}{counter.incrementAndGet()}"
            |            lockMap.compute(key) { _, e ->
            |                if (e != null) return@compute e
            |                result.set(token)
            |                Info(Semaphore(0), now, AtomicBoolean(true), token)
            |            }
            |        }
            |        return result.get()
            |    }
            """.trimMargin()
        val f = LockFixture("", run = run, operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        assertNotReserve(m, f, "counter.incrementAndGet()", 2, "val token is inside a loop")
        assertUnsupported(m, "input token", f.at("val token"))
    }

    @Test
    fun `a var is not a reserve step (rule 2)`() {
        val f = LockFixture(install("token"), before = TEMPLATE.replace("val token", "var token"), operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        assertNotReserve(m, f, "counter.incrementAndGet()", 2, "token is a var")
    }

    @Test
    fun `an increment after the action call is not a reserve step (rule 2)`() {
        val f = LockFixture("e", after = TEMPLATE + "\n        println(token)", operations = LockFixture.DEFAULT_OPERATION)
        val m = f.lower(dir)
        assertNotReserve(m, f, "counter.incrementAndGet()", 2, "val token is not before the action call")
    }

    @Test
    fun `a val whose value is more than the increment is not a reserve step (rule 3)`() {
        val before = "        val token = \"local-\${counter.incrementAndGet() + 1L}\""
        val f = LockFixture(install("token"), before = before, operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        assertNotReserve(m, f, "counter.incrementAndGet()", 3, "the value of val token is neither the increment")
        assertUnsupported(m, "input token", f.at("val token"))
    }

    @Test
    fun `two reserve steps in one operation are not reserve steps (rule 5, W17)`() {
        // PB3 of DESIGN-D.md 8 AT5.
        val before = TEMPLATE + "\n        val spare = \"local-\${counter.incrementAndGet()}\""
        val f = LockFixture(install("if (now > 0L) token else spare"), before = before, operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        assertNull(m.reserve, m.runText)
        val reasons = m.diagnostics.filter { it.message.startsWith(NOT_RESERVE) }
        assertEquals(2, reasons.size, m.diagnostics.toString())
        assertTrue(reasons.all { "rule 5" in it.message && "operation run has 2 reserve steps" in it.message }, reasons.toString())
        assertEquals(Status.Unsupported::class, m.run.status::class)
    }

    @Test
    fun `a reserved val stored outside the action is not a reserve step (rule 4)`() {
        // PB4 of DESIGN-D.md 8 AT5: the value leaks to a field.
        val f =
            LockFixture(
                install("token"),
                before = TEMPLATE + "\n        last = token",
                operations = LockFixture.RESULT_OPERATION,
                members = "    private var last: String? = null",
            )
        val m = f.lower(dir)
        assertNotReserve(m, f, "counter.incrementAndGet()", 4, "token is read at ${f.where("token", after = "last = ")}")
        assertUnsupported(m, "input token", f.at("val token"))
    }

    @Test
    fun `a reserved val written into module state outside the action is not a reserve step (AI-CODE 15_1 item 5)`() {
        val f = LockFixture(install("token"), before = TEMPLATE + "\n        lockMap[key]?.token = token", operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        assertNotReserve(m, f, "counter.incrementAndGet()", 4, "token is read at")
        assertUnsupported(m, "input token", f.at("val token"))
    }

    @Test
    fun `an increment before a compute of a compute-in operation is not a reserve step (rule 1)`() {
        val ops = "operation run = compute in Lock {\n  free now     = param now\n  free timeout = this.timeout\n}"
        val f = LockFixture(install("token").replace("result.set(token)\n", ""), before = TEMPLATE, operations = ops)
        val m = f.lower(dir)
        assertNotReserve(m, f, "counter.incrementAndGet()", 1, "selected by `compute in`")
    }

    @Test
    fun `a reserved val used by a second compute of the operation function stays unsupported (U2)`() {
        // AI-CODE.md 15.1 item 5: a second action, and two computes in one operation.
        val after = "        lockMap.compute(key + \"x\") { _, e -> if (e == null) null else Info(Semaphore(0), now, AtomicBoolean(true), token) }"
        val f = LockFixture(install("token"), before = TEMPLATE, after = after, operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        assertTrue(m.operations.none { it.reserve != null })
        assertTrue(m.incomplete)
        assertTrue(m.diagnostics.any { it.kind == DiagnosticKind.UNSUPPORTED && "an operation has exactly one" in it.message }, m.diagnostics.toString())
        assertTrue(m.ledger.any { it.kind == DispositionKind.UNSUPPORTED && "counter operation outside an action" in it.what }, m.ledger.joinToString("\n"))
        assertTrue(m.strayActions.all { it.status is Status.Unsupported }, printModule(m))
    }

    @Test
    fun `reserve steps on two counters keep only the first (rule 5)`() {
        val members =
            """
            |    fun run2(key: String, now: Long): String? {
            |        val result = AtomicReference<String?>(null)
            |        val token = "local-${'$'}{counter2.incrementAndGet()}"
            |        lockMap.compute(key) { _, e ->
            |            if (e != null) return@compute e
            |            result.set(token)
            |            Info(Semaphore(0), now, AtomicBoolean(true), token)
            |        }
            |        return result.get()
            |    }
            """.trimMargin()
        val ops =
            "state seq2: Counter = counter2\n" + LockFixture.RESULT_OPERATION +
                "\noperation run2 = fun Lock.run2 {\n  free now     = param now\n  free timeout = this.timeout\n  result res\n}"
        val f = LockFixture(install("token"), before = TEMPLATE, companion = "        private val counter2 = AtomicLong()", members = members, operations = ops)
        val m = f.lower(dir)
        assertNotNull(m.reserve)
        assertNull(m.operations.single { it.name == "run2" }.reserve)
        val d = m.diagnostics.single { it.message.startsWith(NOT_RESERVE) }
        assertTrue("rule 5" in d.message && "counter2" in d.message, d.message)
        assertEquals(f.at("counter2.incrementAndGet()").first, d.pos.line)
    }

    // ---- AI-CODE.md 15.1 item 5: tokens that do not come from the counter ----

    @Test
    fun `a token from System_nanoTime, Random or a parameter is no reserve step but an arbitrary captured value`() {
        for ((name, before) in listOf("nano" to "        val token = \"local-\${System.nanoTime()}\"", "random" to "        val token = \"local-\${kotlin.random.Random.nextLong()}\"")) {
            val f = LockFixture(install("token"), before = before, operations = LockFixture.RESULT_OPERATION)
            val m = f.lower(File(dir, name).apply { mkdirs() })
            assertNull(m.reserve, m.runText)
            assertEquals(listOf(Captured::class), m.run.free.filter { it.kotlin == "token" }.map { it.origin::class }, m.runText)
            assertTrue(m.diagnostics.none { it.message.startsWith(NOT_RESERVE) })
        }
        val run =
            """
            |    fun run(key: String, now: Long, token: String): String? {
            |        val result = AtomicReference<String?>(null)
            |        lockMap.compute(key) { _, e ->
            |${install("token").prependIndent("            ")}
            |        }
            |        return result.get()
            |    }
            """.trimMargin()
        val f = LockFixture("", run = run, operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(File(dir, "param").apply { mkdirs() })
        assertNull(m.reserve)
        assertEquals(listOf(Captured::class), m.run.free.filter { it.kotlin == "token" }.map { it.origin::class }, m.runText)
    }

    // ---- W17 and the W10 change on the KVIR ----

    @Test
    fun `W17 rejects a reserve step whose val is not the action's reserved value, or on a counter outside the state`() {
        val f = LockFixture(install("token"), before = TEMPLATE, operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        assertEquals(emptyList(), checkWellFormed(m))
        val op = m.operations.single()
        val r = op.reserve!!
        val other = r.copy(local = r.local.copy(name = "other__1"))
        assertTrue(checkWellFormed(m.copy(operations = listOf(op.copy(reserve = other)))).any { it.rule == "W17" && "must be the one free value" in it.message })
        val counter = r.copy(counter = CounterVar("seq9", "counter9", r.pos, 0))
        assertTrue(checkWellFormed(m.copy(operations = listOf(op.copy(reserve = counter)))).any { it.rule == "W17" && "not module state" in it.message })
        // Without its step, the reserved free value breaks W17; the result that reads it breaks W10.
        val noStep = checkWellFormed(m.copy(operations = listOf(op.copy(reserve = null))))
        assertTrue(noStep.any { it.rule == "W17" && "has no reserve step" in it.message }, noStep.toString())
        val int = r.copy(local = r.local.copy(type = KType.IntT))
        assertTrue(checkWellFormed(m.copy(operations = listOf(op.copy(reserve = int)))).any { it.rule == "W17" && "has type" in it.message })
        val noAssumption = r.copy(assumption = null)
        assertTrue(checkWellFormed(m.copy(operations = listOf(op.copy(reserve = noAssumption)))).any { it.rule == "W13" })
        val untranslated = r.copy(translated = emptySet())
        assertTrue(checkWellFormed(m.copy(operations = listOf(op.copy(reserve = untranslated)))).any { it.rule == "W14" })
    }

    @Test
    fun `W10 lets an object module's result read the reserved val only with its step`() {
        val f = LockFixture("", run = grantedRun("if (granted.get()) token else null"), operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        assertEquals(emptyList(), checkWellFormed(m))
        val op = m.operations.single()
        val violations = checkWellFormed(m.copy(operations = listOf(op.copy(reserve = null))))
        assertTrue(violations.any { it.rule == "W10" && "reads Var ${op.reserve!!.local.name}" in it.message }, violations.toString())
    }
}
