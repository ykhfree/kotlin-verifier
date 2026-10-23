package kv.kvir

import kv.Harness
import kv.kvir.KType.Abs
import kv.kvir.KType.BoolT
import kv.kvir.KType.IntT
import kv.kvir.KType.RefT
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M1 acceptance: for each rule W1-W14, a valid KVIR passes and an invalid one fails with exactly that rule ID.
 * Every invalid module is the hand-built tryLock module with one defect.
 */
class WellFormedTest {
    private val f = TryLockFixture
    private val p = f::p

    private fun rules(m: Module): Set<String> = checkWellFormed(m).map { it.rule }.toSet()

    private fun assertValid(m: Module) = assertEquals(emptyList(), checkWellFormed(m))

    private fun assertOnly(
        rule: String,
        m: Module,
    ) {
        val violations = checkWellFormed(m)
        assertEquals(setOf(rule), violations.map { it.rule }.toSet(), violations.joinToString("\n"))
    }

    private fun withAction(a: Action) = f.module.copy(operations = listOf(f.operation.copy(action = a)))

    private fun withBody(body: List<Stmt>) = withAction(f.action.copy(body = body))

    /** Replaces the one statement of the tryLock body that [match] selects, wherever it is nested. */
    private fun edit(
        match: (Stmt) -> Boolean,
        replace: (Stmt) -> List<Stmt>,
    ): List<Stmt> {
        var hits = 0

        fun go(list: List<Stmt>): List<Stmt> =
            list.flatMap { s ->
                when {
                    match(s) -> {
                        hits++
                        replace(s)
                    }
                    s is If -> listOf(s.copy(then = go(s.then), els = go(s.els)))
                    else -> listOf(s)
                }
            }
        val body = go(f.body)
        check(hits == 1) { "edit matched $hits statements" }
        return body
    }

    private fun isExit(r: ExitResult) = { s: Stmt -> s is Exit && s.result == r }

    @Test
    fun `the hand-built tryLock module is well-formed`() = assertValid(f.module)

    // ---- W1 ----

    @Test
    fun `W1 accepts a compiler-made node that carries the fake source kind of its Kotlin node`() =
        assertValid(withBody(edit(isExit(ExitResult.Keep)) { listOf(Exit(ExitResult.Keep, p(175, 17).copy(fake = "FromLastStatement"))) }))

    @Test
    fun `W1 rejects a node without a source position`() =
        assertOnly("W1", withBody(edit(isExit(ExitResult.Keep)) { listOf(Exit(ExitResult.Keep, Pos("KeyLocalLock.kt", 0, 0))) }))

    // ---- W2: enforced by the Kotlin type system ----

    @Test
    fun `W2 a pure expression fits an expression slot and an effectful statement does not compile there`(
        @TempDir dir: File,
    ) {
        val kvir = File(Module::class.java.protectionDomain.codeSource.location.toURI())
        val header = "package snippet\n\nimport kv.kvir.*\n\nval p = Pos(\"A.kt\", 1, 1)\nval l = Local(\"x\", KType.IntT, p)\n"
        val pure = Harness.compile(File(dir, "pure"), "Pure.kt" to "${header}val ok = Let(l, Var(l, p), false, p)\n", classpath = listOf(kvir))
        assertTrue(pure.compiled, pure.messages)
        val effect = "val bad = Let(l, ModelOp(null, \"jdk.AtomicLong.incrementAndGet\", StateTarget(CounterVar(\"seq\", \"s\", p, 0)), emptyList(), \"A\", p), false, p)\n"
        val effectful = Harness.compile(File(dir, "effect"), "Effect.kt" to header + effect, classpath = listOf(kvir), mustCompile = false)
        assertFalse(effectful.compiled)
        assertTrue("'ModelOp'" in effectful.messages && "'Expr'" in effectful.messages, effectful.messages)
    }

    // ---- W3 ----

    @Test
    fun `W3 accepts an unsupported action whose body has no exit`() {
        val diag = Diagnostic("U1", DiagnosticKind.UNSUPPORTED, "try expression in action tryLock", p(160, 13))
        val body = edit(isExit(ExitResult.Keep)) { listOf(Unsupported("try expression", "exception paths are not modeled in v0", p(160, 13))) }
        assertValid(withAction(f.action.copy(body = body, status = Status.Unsupported(listOf(diag)))))
    }

    @Test
    fun `W3 rejects a statement after an exit`() {
        val release = ModelOp(null, "jdk.Semaphore.release", FieldTarget(f.ex(p(176, 1)), f.permits), emptyList(), null, p(176, 1))
        assertOnly("W3", withBody(edit(isExit(ExitResult.Keep)) { listOf(it, release) }))
    }

    @Test
    fun `W3 rejects a path without exit`() = assertOnly("W3", withBody(edit(isExit(ExitResult.Remove)) { emptyList() }))

    @Test
    fun `W3 rejects an early exit that more statements follow`() {
        val early = edit({ it is If && it.pos == p(161, 17) }) { s -> listOf((s as If).copy(then = s.then + Exit(ExitResult.Remove, p(165, 17)))) }
        assertOnly("W3", withBody(early))
    }

    // ---- W4 ----

    @Test
    fun `W4 accepts an op result assigned to a mutable local declared on an outer path`() {
        // The CAS writes cond_161_21, declared as a var before the If; the nondet call binds the named free values.
        val cas = f.body.walk().filterIsInstance<ModelOp>().single { it.op.endsWith("compareAndSet") }
        assertEquals(f.cas, cas.out)
        assertValid(f.module)
    }

    @Test
    fun `W4 rejects a local used outside the path that declares it`() {
        val leak = Assign(f.acquiredToken, Var(f.token169, p(174, 21)), p(174, 21))
        assertOnly("W4", withBody(edit(isExit(ExitResult.Keep)) { listOf(leak, it) }))
    }

    @Test
    fun `W4 rejects an op result written into an immutable local`() =
        assertOnly("W4", withBody(edit({ it is Let && it.v == f.cas }) { listOf((it as Let).copy(mutable = false)) }))

    @Test
    fun `W4 rejects a name declared twice in one action, even on different paths`() {
        val again = Let(Local("cond_161_21", BoolT, p(181, 13)), BoolLit(true, p(181, 13)), false, p(181, 13))
        assertOnly("W4", withBody(edit({ it is Nondet }) { listOf(again, it) }))
    }

    // ---- W5 ----

    @Test
    fun `W5 accepts a nullable token compared with null and a token assigned to a token cell`() = assertValid(f.module)

    @Test
    fun `W5 rejects a Key compared with a Token`() {
        val keyIsToken = Cmp(CmpOp.EQ, Var(f.k, p(168, 21)), NullLit(f.tokenOrNull, p(168, 30)), p(168, 21))
        assertOnly("W5", withBody(edit({ it is If && it.pos == p(168, 17) }) { listOf((it as If).copy(cond = keyIsToken)) }))
    }

    @Test
    fun `W5 rejects a String type that no sidecar type replaced`() {
        val label = Let(Local("label__180", Abs("String", true), p(180, 17)), NullLit(Abs("String", true), p(180, 29)), false, p(180, 17))
        assertOnly("W5", withBody(edit({ it is Nondet }) { listOf(label, it) }))
    }

    // ---- W6 ----

    @Test
    fun `W6 accepts writes through the entry and the new object and an op on the counter`() = assertValid(f.module)

    @Test
    fun `W6 rejects a write to an object read outside the action`() {
        val other = FreeValue(Local("other__152", f.lockInfoOrNull, p(152, 9)), RacyRead(p(152, 21)), null, "lockMap[completeKey]")
        val write = FieldWrite(Var(other.local, p(174, 21)), f.expiresAt, Var(f.now, p(174, 40)), p(174, 21))
        val body = edit(isExit(ExitResult.Keep)) { listOf(write, it) }
        assertOnly("W6", withAction(f.action.copy(free = f.freeValues + other, body = body)))
    }

    @Test
    fun `W6 rejects a model op on the map`() {
        val onMap = ModelOp(Local("n_1_1", IntT, p(174, 21)), "jdk.AtomicLong.incrementAndGet", StateTarget(f.m), emptyList(), "A80", p(174, 21))
        assertOnly("W6", withBody(edit(isExit(ExitResult.Keep)) { listOf(onMap, it) }))
    }

    // ---- W7 ----

    @Test
    fun `W7 accepts flattened fields as op targets and plain fields as reads and writes`() = assertValid(f.module)

    @Test
    fun `W7 rejects a direct read of a flattened field`() {
        val readsPermits = Cmp(CmpOp.GT, FieldRead(f.ex(p(161, 21)), f.permits, p(161, 21)), IntLit(0, p(161, 40)), p(161, 21))
        assertOnly("W7", withBody(edit({ it is If && it.pos == p(161, 21) }) { listOf((it as If).copy(cond = readsPermits)) }))
    }

    @Test
    fun `W7 rejects an op of one model on a field of another`() {
        val wrong = ModelOp(f.acquired, "jdk.Semaphore.tryAcquire", FieldTarget(f.ex(p(168, 21)), f.isHeld), emptyList(), null, p(168, 21))
        assertOnly("W7", withBody(edit({ it is ModelOp && it.out == f.acquired }) { listOf(wrong) }))
    }

    // ---- W8 ----

    @Test
    fun `W8 accepts one cell written by Assign`() = assertValid(f.module)

    @Test
    fun `W8 rejects a cell represented twice`() {
        val cell = f.operation.cells.single()
        assertOnly("W8", f.module.copy(operations = listOf(f.operation.copy(cells = listOf(cell, cell.copy(pos = p(151, 10)))))))
    }

    @Test
    fun `W8 rejects a cell written by a model op`() {
        val intoCell = ModelOp(f.acquiredToken, "jdk.AtomicLong.incrementAndGet", StateTarget(f.seq), emptyList(), "A80", p(174, 21))
        assertOnly("W8", withBody(edit(isExit(ExitResult.Keep)) { listOf(intoCell, it) }))
    }

    // ---- W9 ----

    @Test
    fun `W9 accepts named free values that use no tool or field name`() = assertValid(f.module)

    @Test
    fun `W9 rejects a named free value that clashes with a model field`() {
        val token = FreeValue(Local("token", f.token, p(201, 9)), Captured(p(201, 9)), "token", "token")
        assertOnly("W9", withAction(f.action.copy(free = f.freeValues + token)))
    }

    @Test
    fun `W9 rejects a named free value that clashes with a reserved name`() {
        val qt = FreeValue(Local("qt", IntT, p(201, 9)), Captured(p(201, 9)), "qt", "qt")
        assertOnly("W9", withAction(f.action.copy(free = f.freeValues + qt)))
    }

    // ---- W10 ----

    @Test
    fun `W10 accepts a result that reads a cell`() = assertValid(f.module)

    @Test
    fun `W10 rejects a result that reads a free value`() {
        val last = FreeValue(Local("last__152", f.tokenOrNull, p(152, 9)), RacyRead(p(152, 20)), null, "lockMap[completeKey]?.token")
        val op = f.operation.copy(action = f.action.copy(free = f.freeValues + last), body = listOf(RunAction(p(155, 9)), Return(Var(last.local, p(195, 16)), p(195, 16))))
        assertOnly("W10", f.module.copy(operations = listOf(op)))
    }

    @Test
    fun `W10 rejects one action in two operations`() = assertOnly("W10", f.module.copy(operations = listOf(f.operation, f.operation.copy(name = "tryLockAgain"))))

    // ---- W11 ----

    @Test
    fun `W11 accepts issue values over the key, free values and carried`() = assertValid(f.module)

    @Test
    fun `W11 rejects an issue value that reads module state (R1)`() {
        val issue = f.commit.filterIsInstance<Issue>().single()
        val leaseFromState = FieldRead(Var(f.existing, p(155, 9)), f.expiresAt, p(155, 9))
        val commit = f.commit.map { if (it is Issue) issue.copy(fields = issue.fields + ("gLease" to leaseFromState)) else it }
        assertOnly("W11", withAction(f.action.copy(commit = commit)))
    }

    @Test
    fun `W11 rejects an issue that sets a field the tool owns`() {
        val issue = f.commit.filterIsInstance<Issue>().single()
        val commit = f.commit.map { if (it is Issue) issue.copy(fields = issue.fields + ("gHeld" to BoolLit(true, p(155, 9)))) else it }
        assertOnly("W11", withAction(f.action.copy(commit = commit)))
    }

    @Test
    fun `W11 rejects a token check in a stray action, which has no operation result`() {
        val stray =
            f.action.copy(
                id = "stray#209",
                pos = p(209, 9),
                free = emptyList(),
                body = listOf(Exit(ExitResult.Keep, p(221, 13))),
                commit = listOf(AssertFresh(Var(f.res, p(209, 9)), p(209, 9))),
                translated = emptySet(),
            )
        assertOnly("W11", f.module.copy(strayActions = listOf(stray)))
    }

    // ---- W12 ----

    private val unsupportedBody = edit({ it is Nondet }) { listOf(Unsupported("try expression", "exception paths are not modeled in v0", p(180, 17)), it) }
    private val unsupportedDiag = Diagnostic("U180", DiagnosticKind.UNSUPPORTED, "try expression in action tryLock", p(180, 17))

    @Test
    fun `W12 accepts an action with an unsupported node and unsupported status`() =
        assertValid(withAction(f.action.copy(body = unsupportedBody, status = Status.Unsupported(listOf(unsupportedDiag)))))

    @Test
    fun `W12 rejects an action with an unsupported node that claims to be supported`() =
        assertOnly("W12", withAction(f.action.copy(body = unsupportedBody)))

    @Test
    fun `W12 rejects an unsupported status without an UNSUPPORTED diagnostic`() =
        assertOnly("W12", withAction(f.action.copy(body = unsupportedBody, status = Status.Unsupported(emptyList()))))

    // ---- W13 ----

    @Test
    fun `W13 accepts Long operations that name ASSUMPTION diagnostics`() = assertValid(f.module)

    @Test
    fun `W13 rejects Long arithmetic without an ASSUMPTION diagnostic`() {
        val unmarked = edit({ it is FieldWrite && it.field == f.expiresAt }) { s -> listOf((s as FieldWrite).copy(e = (s.e as Arith).copy(assumption = null))) }
        assertOnly("W13", withBody(unmarked))
    }

    @Test
    fun `W13 rejects an increment that names a missing diagnostic`() {
        val unknown = edit({ it is ModelOp && it.out == f.n1 }) { listOf((it as ModelOp).copy(assumption = "A99")) }
        assertOnly("W13", withBody(unknown))
    }

    // ---- W14 ----

    @Test
    fun `W14 accepts a module whose ledger and actions cover every FIR node once`() = assertValid(f.module)

    @Test
    fun `W14 rejects a FIR node without disposition`() = assertOnly("W14", f.module.copy(firNodeCount = 9))

    @Test
    fun `W14 rejects a FIR node that is both translated and in the ledger`() =
        assertOnly("W14", f.module.copy(ledger = f.module.ledger + Disposition(4, DispositionKind.NOT_MODELED, "log.debug(...)", p(225, 13))))

    @Test
    fun `W14 rejects a FIR node with two ledger entries`() =
        assertOnly("W14", f.module.copy(ledger = f.module.ledger + Disposition(0, DispositionKind.RACY_READ, "lockMap.keys", p(96, 33))))

    @Test
    fun `violations carry the position of the defect`() {
        // The path of the rejected new entry now ends at the assignment of the unbacked token.
        val violation = checkWellFormed(withBody(edit(isExit(ExitResult.Remove)) { emptyList() })).single()
        assertEquals(p(182, 21), violation.pos, violation.toString())
        assertTrue(rules(f.module).isEmpty())
    }
}
