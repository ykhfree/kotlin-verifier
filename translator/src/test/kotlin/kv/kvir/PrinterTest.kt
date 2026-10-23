package kv.kvir

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M1 acceptance: the hand-built tryLock KVIR prints to the golden file. */
class PrinterTest {
    private fun golden(name: String): String =
        PrinterTest::class.java.getResource("/golden/$name")?.readText() ?: error("missing golden file src/test/resources/golden/$name")

    /** Compares with the golden file; on a mismatch, writes the actual text under build/ for review. */
    private fun assertGolden(
        name: String,
        actual: String,
    ) {
        val expected = golden(name)
        if (expected != actual) File("build/golden-actual/$name").apply { parentFile.mkdirs() }.writeText(actual)
        assertEquals(expected, actual, "printed KVIR differs from golden/$name; actual text: build/golden-actual/$name")
    }

    @Test
    fun `hand-built tryLock KVIR prints to the golden file`() {
        assertGolden("tryLock.kvir", printModule(TryLockFixture.module))
    }

    @Test
    fun `printing shows a change in the action body`() {
        // The golden comparison must notice a one-statement edit (the SB3 mutant drops the expiresAt refresh).
        val f = TryLockFixture
        val withoutRefresh = f.existingBranch.map { s -> if (s is If && s.pos == f.p(168, 17)) s.copy(then = s.then.filterNot { it is FieldWrite && it.field == f.expiresAt }) else s }
        val action = f.action.copy(body = listOf((f.body.single() as If).copy(then = withoutRefresh)))
        val printed = printModule(f.module.copy(operations = listOf(f.operation.copy(action = action))))
        assertTrue("existing__155.expiresAt := now + timeout" in printModule(f.module))
        assertTrue("existing__155.expiresAt := now + timeout" !in printed, printed)
    }

    @Test
    fun `operators inside operators get parentheses`() {
        val f = TryLockFixture
        val p = f.p(1, 1)
        val e = Not(And(Cmp(CmpOp.GT, Var(f.now, p), Arith(ArithOp.MINUS, Var(f.timeout, p), IntLit(1, p), "A", p), p), BoolLit(true, p), p), p)
        val stmt = Let(Local("x__1", KType.BoolT, p), e, false, p)
        val action = f.action.copy(body = listOf(stmt, Exit(ExitResult.Keep, p)))
        val printed = printModule(f.module.copy(operations = listOf(f.operation.copy(action = action))))
        assertTrue("let x__1: Bool := !((now > (timeout - 1)) && true) [ASSUME A]" in printed, printed)
    }
}
