package kv.lower

import kv.kvir.DiagnosticKind
import kv.kvir.DispositionKind
import kv.kvir.Status
import kv.kvir.Unsupported
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import kv.kvir.walk
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M3 acceptance 2 and 3 (DESIGN-v0.md section 7): for each construct of the M3 row, one fixture that lowers and one
 * that is unsupported. An unsupported construct names itself, carries its Kotlin position, and makes the action
 * unsupported (checked by [assertUnsupported]); every lowered module passes W1-W14 (FIR -> KVIR -> checker).
 */
class ConstructTest {
    @TempDir
    lateinit var dir: File

    private companion object {
        const val NEXT_TOKEN = "        private fun nextToken(): String = \"local-\${counter.incrementAndGet()}\""
    }

    /** The mangled name of the lambda's entry parameter `e`. */
    private fun LockFixture.e() = "e__${at("{ _, e ->").first}"

    private fun LockFixture.line(fragment: String) = at(fragment).first

    // ---- if/else and when ----

    @Test
    fun `if-else in result position becomes an If with an exit in each branch`() {
        val f =
            LockFixture(
                """
                if (e != null) {
                    e.expiresAt = now
                    e
                } else {
                    null
                }
                """.trimIndent(),
            )
        val m = f.lower(dir)
        assertEquals(listOf("if ${f.e()} != null", "  ${f.e()}.expiresAt := now", "  exit keep", "else", "  exit remove"), shape(assertSupported(m)))
    }

    @Test
    fun `when with a subject is unsupported`() {
        val f = LockFixture("when (e) {\n    null -> null\n    else -> e\n}")
        assertUnsupported(f.lower(dir), "when with subject", f.at("when (e)"))
    }

    @Test
    fun `when in result position lowers each branch in result position`() {
        val f = LockFixture("when {\n    e == null -> null\n    now > e.expiresAt -> null\n    else -> e\n}")
        val e = f.e()
        assertEquals(
            listOf("if $e == null", "  exit remove", "else", "  if now > $e.expiresAt", "    exit remove", "  else", "    exit keep"),
            shape(assertSupported(f.lower(dir))),
        )
    }

    @Test
    fun `if used as a value outside result position is a local that each branch assigns`() {
        // AI-CODE.md 14.1 A2; ValueIfTest has the other shapes.
        val f = LockFixture("val t = if (e == null) 0L else e.expiresAt\ne")
        val (l, c) = f.at("if (e == null)")
        val t = "t__${f.line("val t =")}"
        assertEquals(
            listOf("var value_${l}_$c: Int := 0", "if ${f.e()} == null", "  value_${l}_$c := 0", "else", "  value_${l}_$c := ${f.e()}.expiresAt", "let $t: Int := value_${l}_$c", "exit keep"),
            shape(assertSupported(f.lower(dir))),
        )
    }

    // ---- early return@compute ----

    @Test
    fun `statements after an early return move into the else branch`() {
        val f = LockFixture("if (e == null) return@compute null\ne.expiresAt = now\ne")
        val m = f.lower(dir)
        val e = f.e()
        assertEquals(listOf("if $e == null", "  exit remove", "else", "  $e.expiresAt := now", "  exit keep"), shape(assertSupported(m)))
        // The exit of a written return is placed at the return, not at its value.
        val text = m.runText
        assertTrue(Regex("""exit remove\s+@${f.line("return@compute null")}:${f.at("return@compute").second}\b""").containsMatchIn(text), text)
    }

    @Test
    fun `an exit on only some paths of a branch is unsupported`() {
        val f = LockFixture("if (e != null) {\n    if (now > e.expiresAt) return@compute null\n}\ne")
        assertUnsupported(f.lower(dir), "partial exit", f.at("if (e != null)"))
    }

    // ---- && and || with side effects ----

    @Test
    fun `a CAS on the right of && is computed into a condition variable first`() {
        val f = LockFixture("if (e != null && e.isHeld.compareAndSet(true, false)) {\n    e.semaphore.release()\n}\ne")
        val (l, c) = f.at("e != null &&")
        val e = f.e()
        assertEquals(
            listOf(
                "var cond_${l}_$c: Bool := false",
                "if $e != null",
                "  cond_${l}_$c := jdk.AtomicBoolean.compareAndSet($e.isHeld, true, false)",
                "if cond_${l}_$c",
                "  jdk.Semaphore.release($e.permits)",
                "exit keep",
            ),
            shape(assertSupported(f.lower(dir))),
        )
    }

    @Test
    fun `an effect on the right of || runs only when the left side is false`() {
        val f = LockFixture("if (e == null || e.semaphore.tryAcquire()) return@compute null\ne")
        val (l, c) = f.at("e == null ||")
        val e = f.e()
        assertEquals(
            listOf(
                "var cond_${l}_$c: Bool := true",
                "if !($e == null)",
                "  cond_${l}_$c := jdk.Semaphore.tryAcquire($e.permits)",
                "if cond_${l}_$c",
                "  exit remove",
                "else",
                "  exit keep",
            ),
            shape(assertSupported(f.lower(dir))),
        )
    }

    @Test
    fun `an unmodeled call on the right of && is unsupported`() {
        val f = LockFixture("if (e != null && e.semaphore.availablePermits() > 0) {\n    e.expiresAt = now\n}\ne")
        assertUnsupported(f.lower(dir), "unknown call Semaphore.availablePermits", f.at("e.semaphore.availablePermits"))
    }

    // ---- smart casts ----

    @Test
    fun `a stable smart cast is stripped`() {
        val f = LockFixture("if (e != null) {\n    e.expiresAt = now\n}\ne")
        assertEquals(listOf("if ${f.e()} != null", "  ${f.e()}.expiresAt := now", "exit keep"), shape(assertSupported(f.lower(dir))))
    }

    @Test
    fun `an unstable smart cast is unsupported`() {
        // e.token is a var property: after the null check the compiler records a smart cast it does not trust.
        val f = LockFixture("if (e != null && e.token != null) {\n    val s: String? = e.token\n    e.token = s\n}\ne")
        assertUnsupported(f.lower(dir), "unstable smart cast", f.at("= e.token").let { (l, c) -> l to c + 2 })
    }

    // ---- null checks ----

    @Test
    fun `null checks of an object and of a token compare with null`() {
        val f = LockFixture("if (e == null) return@compute null\nif (e.token == null) e.expiresAt = now\ne")
        val e = f.e()
        assertEquals(
            listOf("if $e == null", "  exit remove", "else", "  if $e.token == null", "    $e.expiresAt := now", "  exit keep"),
            shape(assertSupported(f.lower(dir))),
        )
    }

    @Test
    fun `a null check of a nullable Long is unsupported`() {
        val f = LockFixture("if (limit == null) return@compute null\ne", before = "        val limit: Long? = if (key.isEmpty()) null else 1L")
        assertUnsupported(f.lower(dir), "nullable Long", f.at("limit == null"))
    }

    // ---- Long arithmetic ----

    @Test
    fun `Long plus and minus are mathematical integers with one assumption per site`() {
        val f = LockFixture("if (e != null) {\n    e.expiresAt = now + timeout\n    e.expiresAt = now - 1\n}\ne")
        val m = f.lower(dir)
        val text = assertSupported(m)
        val plus = f.line("now + timeout")
        val minus = f.line("now - 1")
        assertTrue(shape(text).containsAll(listOf("  ${f.e()}.expiresAt := now + timeout [ASSUME A$plus]", "  ${f.e()}.expiresAt := now - 1 [ASSUME A$minus]")), text)
        val assumptions = m.diagnostics.filter { it.kind == DiagnosticKind.ASSUMPTION }
        assertEquals(listOf("A$plus" to "Long unbounded: now + timeout", "A$minus" to "Long unbounded: now - 1"), assumptions.map { it.id to it.message })
        assertEquals(f.at("now + timeout"), assumptions[0].pos.line to assumptions[0].pos.col)
    }

    @Test
    fun `Long times is unsupported`() {
        val f = LockFixture("if (e != null) {\n    e.expiresAt = now * 2\n}\ne")
        assertUnsupported(f.lower(dir), "Long *", f.at("now * 2"))
    }

    // ---- string templates ----

    @Test
    fun `an unused helper that changes module state makes the module incomplete`() {
        // Conservative audit: a module-state operation outside every action is unsupported, even in dead code.
        val f = LockFixture("e", companion = NEXT_TOKEN)
        val m = f.lower(dir)
        assertSupported(m, complete = false)
        val what = m.ledger.filter { it.kind == DispositionKind.UNSUPPORTED }.map { it.what to it.pos.line }.distinct().single()
        assertEquals("counter operation outside an action: counter.incrementAndGet()" to f.line("counter.incrementAndGet()"), what)
    }

    @Test
    fun `a template that matches the Token type becomes tokenOf`() {
        val f = LockFixture("if (e != null) {\n    e.token = \"local-\${counter.incrementAndGet()}\"\n}\ne")
        val (l, c) = f.at("counter.incrementAndGet()")
        val text = assertSupported(f.lower(dir))
        assertEquals(
            listOf("if ${f.e()} != null", "  n_${l}_$c := jdk.AtomicLong.incrementAndGet(seq) [ASSUME A$l]", "  ${f.e()}.token := tokenOf(n_${l}_$c)", "exit keep"),
            shape(text),
        )
    }

    @Test
    fun `a template with another prefix is unsupported`() {
        val f = LockFixture("if (e != null) {\n    e.token = \"lock-\${counter.incrementAndGet()}\"\n}\ne")
        val u = assertUnsupported(f.lower(dir), "string template", f.at("\"lock-"))
        assertTrue("\"lock-\" + <Long>" in u.reason && "\"local-\${Long}\"" in u.reason, u.reason)
    }

    @Test
    fun `a template with two expression parts is unsupported`() {
        val f = LockFixture("if (e != null) {\n    e.token = \"local-\${now}-\${timeout}\"\n}\ne")
        val u = assertUnsupported(f.lower(dir), "string template", f.at("\"local-\${now}"))
        assertTrue("\"local-\" + <Long> + \"-\" + <Long>" in u.reason, u.reason)
    }

    // ---- cells ----

    @Test
    fun `a non-escaping AtomicReference is a cell and the result reads it`() {
        val f = LockFixture("if (e != null) result.set(e.token)\ne", operations = LockFixture.RESULT_OPERATION)
        val m = f.lower(dir)
        val text = assertSupported(m)
        val cell = "result__${f.line("val result")}"
        assertEquals(listOf("if ${f.e()} != null", "  $cell := ${f.e()}.token", "exit keep"), shape(text))
        assertTrue("cell   $cell: Token? := null" in text && "result res: Token? := $cell" in text, text)
        // The cell's declaration and the read after the action are in the ledger as `cell`.
        assertEquals(setOf(f.line("val result"), f.line("return result.get()")), m.ledger.filter { it.kind == DispositionKind.CELL }.map { it.pos.line }.toSet())
    }

    @Test
    fun `a cell that escapes is unsupported`() {
        val f = LockFixture("if (e != null) result.set(e.token)\ne", after = "        println(result)", operations = LockFixture.RESULT_OPERATION)
        val u = assertUnsupported(f.lower(dir), "cell escapes", f.at("result.set"))
        assertTrue("used other than as the receiver of set or get at Lock.kt:${f.line("println(result)")}" in u.reason, u.reason)
    }

    // ---- constructor defaults ----

    @Test
    fun `a constructor call takes missing arguments from the constructor defaults`() {
        val f = LockFixture("if (e != null) return@compute e\nval fresh = Info(Semaphore(1), now)\nfresh")
        val fresh = "fresh__${f.line("val fresh")}"
        assertEquals(
            listOf("if ${f.e()} != null", "  exit keep", "else", "  new $fresh: Info {permits := 1, isHeld := false, token := null, expiresAt := now}", "  exit put($fresh)"),
            shape(assertSupported(f.lower(dir))),
        )
    }

    @Test
    fun `a constructor default that is not a constant is unsupported`() {
        val info = LockFixture.DEFAULT_INFO.replace("var token: String? = null", "var token: String? = \"none\"")
        val f = LockFixture("if (e != null) return@compute e\nval fresh = Info(Semaphore(1), now)\nfresh", info = info)
        assertUnsupported(f.lower(dir), "constructor default of token", f.at("Info(Semaphore(1), now)"))
    }

    // ---- helper inlining ----

    @Test
    fun `a module helper is inlined with its parameters as locals`() {
        val f =
            LockFixture(
                "if (e == null) return@compute null\ne.token = nextToken()\ne.expiresAt = later(now, timeout)\ne",
                companion = NEXT_TOKEN + "\n        private fun later(at: Long, delta: Long): Long = at + delta",
            )
        val (l, c) = f.at("counter.incrementAndGet()")
        val p = f.line("private fun later")
        val e = f.e()
        assertEquals(
            listOf(
                "if $e == null",
                "  exit remove",
                "else",
                "  n_${l}_$c := jdk.AtomicLong.incrementAndGet(seq) [ASSUME A$l]",
                "  $e.token := tokenOf(n_${l}_$c)",
                "  let at__$p: Int := now",
                "  let delta__$p: Int := timeout",
                "  $e.expiresAt := at__$p + delta__$p [ASSUME A$p]",
                "  exit keep",
            ),
            shape(assertSupported(f.lower(dir))),
        )
    }

    @Test
    fun `a recursive helper is unsupported`() {
        val f = LockFixture("if (e != null) e.token = spin()\ne", companion = "        private fun spin(): String = spin()")
        assertUnsupported(f.lower(dir), "recursive helper spin", f.at("= spin()").let { (l, c) -> l to c + 2 })
    }

    // ---- try, loops, unknown calls, captured vars ----

    /** The ledger entries that hold the nodes of function `run` outside its action and the free values. */
    private fun notModeledInRun(m: kv.kvir.Module) = m.ledger.count { it.kind == DispositionKind.NOT_MODELED && it.what == "fun run" }

    @Test
    fun `a try outside the action is not modeled, and the action lowers`() {
        val body = "if (e != null) e.expiresAt = now\ne"
        val f = LockFixture(body, before = "        try {\n            println(key)\n        } catch (x: Exception) {\n        }")
        val m = f.lower(dir)
        assertSupported(m)
        // try, its block, println(key), the read of key, the catch parameter and the catch block are not modeled.
        assertEquals(6, notModeledInRun(m) - notModeledInRun(LockFixture(body).lower(File(dir, "plain").apply { mkdirs() })), printModuleLedger(m))
    }

    private fun printModuleLedger(m: kv.kvir.Module) = kv.kvir.printModule(m).substringAfter("ledger:")

    @Test
    fun `a try inside the action is unsupported`() {
        val f = LockFixture("try {\n    if (e != null) e.expiresAt = now\n} catch (x: Exception) {\n}\ne")
        val u = assertUnsupported(f.lower(dir), "try expression", f.at("try {"))
        assertEquals("exception paths inside an action are not modeled in v0", u.reason)
    }

    @Test
    fun `a loop outside the action is not modeled, and the action lowers`() {
        val f = LockFixture("if (e != null) e.expiresAt = now\ne", before = "        while (now < 0) {\n        }")
        assertSupported(f.lower(dir))
    }

    @Test
    fun `a loop inside the action is unsupported`() {
        val f = LockFixture("while (now < 0) {\n}\ne")
        assertUnsupported(f.lower(dir), "loop", f.at("while (now < 0)"))
    }

    @Test
    fun `a call of a trusted model lowers to a model operation`() {
        val f = LockFixture("if (e != null) e.semaphore.release()\ne")
        assertEquals(listOf("if ${f.e()} != null", "  jdk.Semaphore.release(${f.e()}.permits)", "exit keep"), shape(assertSupported(f.lower(dir))))
    }

    @Test
    fun `an unknown call is unsupported`() {
        val f = LockFixture("if (e != null) e.token = key.uppercase()\ne")
        assertUnsupported(f.lower(dir), "unknown call uppercase", f.at("key.uppercase()"))
    }

    @Test
    fun `a captured var read inside the action is an unnamed free value`() {
        val f = LockFixture("if (e != null) e.expiresAt = count\ne", before = "        var count = 0L")
        val m = f.lower(dir)
        val text = assertSupported(m)
        val count = "count__${f.line("var count")}"
        assertTrue("free  $count (unnamed): Int <- count (captured @${f.line("var count")}:" in text, text)
        assertTrue("  ${f.e()}.expiresAt := $count" in shape(text), text)
    }

    @Test
    fun `an assignment to a captured var is unsupported`() {
        val f = LockFixture("count = 1L\ne", before = "        var count = 0L")
        assertUnsupported(f.lower(dir), "assignment to captured var count", f.at("count = 1L"))
    }

    // ---- collecting all unsupported constructs ----

    @Test
    fun `lowering goes on after the first unsupported construct and reports each one`() {
        val f = LockFixture("if (e != null) {\n    e.expiresAt = now * 2\n    e.token = key.uppercase()\n}\ne")
        val m = f.lower(dir)
        assertUnsupported(m, "Long *", f.at("now * 2"))
        assertUnsupported(m, "unknown call uppercase", f.at("key.uppercase()"))
        assertEquals(2, m.run.body.walk().filterIsInstance<Unsupported>().count(), m.runText)
    }

    // ---- decisions 5 and 6 ----

    @Test
    fun `a compute inside a local function is a stray action reported as unsupported (decision 5)`() {
        val f =
            LockFixture(
                "if (e != null) e.expiresAt = now\ne",
                members = "    fun other(k: String) {\n        fun local() {\n            lockMap.compute(k) { _, v -> v }\n        }\n        local()\n    }",
            )
        val m = f.lower(dir)
        assertSupported(m, complete = false)
        val stray = m.strayActions.single()
        val status = stray.status as Status.Unsupported
        val u = stray.body.single() as Unsupported
        assertEquals("compute in local function local", u.what)
        assertEquals(f.at("lockMap.compute(k)"), u.pos.line to u.pos.col)
        assertTrue(status.diagnostics.single().message.startsWith("UNSUPPORTED compute in local function local in action local#${f.line("lockMap.compute(k)")} at Lock.kt:"), status.diagnostics.single().message)
        assertTrue(m.incomplete)
        assertEquals(emptyList(), checkWellFormed(m))
    }

    @Test
    fun `an operation whose function is in another file is unsupported (decision 6)`() {
        val other = "package fixture\n\nfun elsewhere(k: String): String? = k.ifEmpty { null }\n"
        val f =
            LockFixture(
                "if (e != null) e.expiresAt = now\ne",
                operations = LockFixture.DEFAULT_OPERATION + "\noperation away = fun elsewhere {\n}",
                otherFiles = mapOf("Other.kt" to other),
            )
        val m = f.lower(dir)
        assertSupported(m, complete = false)
        val d = m.diagnostics.single { it.kind == DiagnosticKind.UNSUPPORTED }
        assertTrue("operation away" in d.message && "fun elsewhere is declared in Other.kt, outside the module file Lock.kt" in d.message, d.message)
        assertEquals(3 to 1, d.pos.line to d.pos.col)
        assertTrue(d.pos.file.endsWith("Other.kt"), d.pos.file)
        assertTrue(m.incomplete)
    }
}
