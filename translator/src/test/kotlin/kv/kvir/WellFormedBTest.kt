package kv.kvir

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * DESIGN-B.md 8, B2 acceptance 3: for each changed or new rule (W3, W6, W10, W15, W16), a valid KVIR passes and an
 * invalid one fails with exactly that rule ID. Every module is [AspectKvirFixture] with one change.
 */
class WellFormedBTest {
    private val f = AspectKvirFixture
    private val p = f::p

    private fun assertValid(m: Module) = assertEquals(emptyList(), checkWellFormed(m))

    private fun assertOnly(
        rule: String,
        m: Module,
    ) {
        val violations = checkWellFormed(m)
        assertEquals(setOf(rule), violations.map { it.rule }.toSet(), violations.joinToString("\n"))
    }

    private fun withAction(a: Action) = f.module.copy(operations = listOf(f.operation.copy(action = a)))

    private fun withSection(body: List<Stmt>) = f.module.copy(operations = listOf(f.operation.copy(body = body)))

    @Test
    fun `the hand-built getOrCreate module is well-formed`() = assertValid(f.module)

    // ---- W3 ----

    @Test
    fun `W3 accepts an action path that raises without writing the map`() = assertValid(f.module)

    @Test
    fun `W3 rejects a statement after raise`() {
        val raising = If(Var(f.thrown.local, p(113, 13)), listOf(Raise(p(113, 13)), Exit(ExitResult.Keep, p(113, 14))), (f.body[1] as If).els, p(113, 13))
        assertOnly("W3", withAction(f.action.copy(body = listOf(f.body[0], raising))))
    }

    @Test
    fun `W3 rejects a lambda body in an action that has no lambda`() {
        val put = f.action.copy(kind = ActionKind.PUT_IF_ABSENT, value = NullLit(f.shieldOrNull, p(112, 50)), out = f.cur)
        assertOnly("W3", f.module.copy(operations = listOf(f.operation.copy(action = put, body = listOf(RunAction(p(112, 9)), Return(Var(f.cur, p(112, 9)), p(112, 9)))))))
    }

    // ---- W6 ----

    @Test
    fun `W6 accepts putting a fresh object and an object of a class with no modeled field (M5)`() {
        assertValid(f.module)
        val shared = FreeValue(Local("shared__113", f.shield, p(113, 13)), InstanceVal("sharedReqShield", p(113, 13)), null, "this.sharedReqShield")
        assertValid(withAction(f.action.copy(free = listOf(f.thrown, shared), body = listOf(Exit(ExitResult.Put(Var(shared.local, p(113, 13))), p(113, 13))))))
    }

    @Test
    fun `W6 rejects putting an object of a class with fields that the action did not create`() {
        val t = TryLockFixture
        val other = FreeValue(Local("other__152", KType.RefT("LockInfo", false), t.p(152, 9)), Captured(t.p(152, 9)), null, "other")
        val putOther = t.action.copy(free = t.action.free + other, body = listOf(Exit(ExitResult.Put(Var(other.local, t.p(192, 21))), t.p(192, 17))))
        val violations = checkWellFormed(t.module.copy(operations = listOf(t.operation.copy(action = putOther))))
        assertEquals(setOf("W6"), violations.map { it.rule }.toSet(), violations.joinToString("\n"))
    }

    // ---- W10 ----

    @Test
    fun `W10 accepts a section that runs the action once on each path and returns`() {
        val v = Local("v_112_9", f.shieldOrNull, p(112, 9))
        val fastPath =
            listOf(
                RacyGet(v, f.m, Var(f.key.local, p(112, 38)), contains = false, contract = true, pos = p(112, 9)),
                If(Cmp(CmpOp.NE, Var(v, p(112, 9)), NullLit(f.shieldOrNull, p(112, 9)), p(112, 9)), listOf(Return(Var(v, p(112, 9)), p(112, 9))), f.section, p(112, 9)),
            )
        assertValid(withSection(fastPath))
    }

    @Test
    fun `W10 rejects running the action twice on one path`() {
        val noResult = f.operation.copy(action = f.action.copy(out = null), result = null, body = listOf(RunAction(p(112, 9)), RunAction(p(112, 9)), Return(null, p(112, 9))))
        assertOnly("W10", f.module.copy(operations = listOf(noResult)))
    }

    @Test
    fun `W10 rejects a section path without return or raise`() = assertOnly("W10", withSection(listOf(RunAction(p(112, 9)))))

    @Test
    fun `W10 rejects a result that reads a named free value`() {
        val named = FreeValue(Local("given", f.shield, p(111, 30)), Captured(p(111, 30)), "given", "given")
        val m = f.module.copy(operations = listOf(f.operation.copy(action = f.action.copy(free = f.action.free + named), body = listOf(RunAction(p(112, 9)), Return(Var(named.local, p(112, 9)), p(112, 9))))))
        assertOnly("W10", m)
    }

    // ---- W15 ----

    @Test
    fun `W15 accepts a fresh object of the class its fresh function makes`() = assertValid(f.module)

    @Test
    fun `W15 rejects a fresh object of a function that is not a fresh function`() {
        val body = listOf(f.body[0], (f.body[1] as If).copy(els = listOf(Fresh(f.r, f.shieldClass, "getTargetMethod", p(113, 13)), Exit(ExitResult.Put(Var(f.r, p(113, 13))), p(113, 13)))))
        assertOnly("W15", withAction(f.action.copy(body = body)))
    }

    @Test
    fun `W15 rejects a constructor of the class a fresh function makes`() {
        val body = listOf(f.body[0], (f.body[1] as If).copy(els = listOf(New(f.r, f.shieldClass, emptyMap(), p(113, 13)), Exit(ExitResult.Put(Var(f.r, p(113, 13))), p(113, 13)))))
        assertOnly("W15", withAction(f.action.copy(body = body)))
    }

    // ---- W16 ----

    private val v = Local("v_110_9", f.shieldOrNull, p(110, 9))

    private fun racy(contract: Boolean) = RacyGet(v, f.m, Var(f.key.local, p(110, 20)), contains = false, contract = contract, pos = p(110, 9))

    @Test
    fun `W16 accepts the set-once contract on a read before the action and no contract on a read after it`() {
        assertValid(withSection(listOf(racy(true)) + f.section))
        assertValid(withSection(listOf(RunAction(p(112, 9)), racy(false), Return(Var(f.out, p(112, 9)), p(112, 9)))))
    }

    @Test
    fun `W16 rejects the contract on a map that is not set-once`() {
        val plain = f.m.copy(setOnce = null)
        val m = f.module.copy(state = listOf(plain), operations = listOf(f.operation.copy(action = f.action.copy(map = plain), body = listOf(racy(true).copy(map = plain)) + f.section)))
        assertOnly("W16", m)
    }

    @Test
    fun `W16 rejects the contract on a read after the action`() = assertOnly("W16", withSection(listOf(RunAction(p(112, 9)), racy(true), Return(Var(f.out, p(112, 9)), p(112, 9)))))

    @Test
    fun `W16 rejects a racy read inside an action`() {
        val body = listOf(racy(false), f.body[0], f.body[1])
        assertOnly("W16", withAction(f.action.copy(body = body)))
    }
}
