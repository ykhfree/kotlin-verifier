package kv.lower

import kv.kvir.Unsupported
import kv.kvir.walk
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fix 3 of AI-CODE.md 13: the report lists every unsupported construct of an action. After an unsupported part, the
 * lowering still lowers the parts that a supported action would also lower: the branches after a condition, both
 * sides of an operator, every argument, a helper's other parameters and its body, and the value of a local it cannot
 * declare. The flattening checks give every construction or use that breaks a field. Round 1 showed only `Int toLong`
 * for T-haiku-3's tryLock: its `Semaphore(1).also { ... }` construction was in a branch after that condition.
 */
class ReasonTest {
    @TempDir
    lateinit var dir: File

    private fun LockFixture.where(fragment: String) = at(fragment).let { (l, c) -> "Lock.kt:$l:$c" }

    /** The constructs of the Unsupported statements of `run`, in the order the lowering found them. */
    private fun kv.kvir.Module.constructs() = run.body.walk().filterIsInstance<Unsupported>().map { it.what }.toList()

    @Test
    fun `an unsupported condition does not hide an unsupported construction in a later branch`() {
        // The shape of T-haiku-3's tryLock in round 1, with `now * 2` for the `Int toLong` of that round. Its
        // `Semaphore(1).also { it.tryAcquire() }` is supported since AI-CODE.md 14.1 A1, so the also lambda here
        // does something else as well.
        val f = LockFixture("if (e != null && now * 2 > e.expiresAt) e\nelse Info(Semaphore(1).also { it.toString() }, now)")
        val m = f.lower(dir)
        assertUnsupported(m, "Long *", f.at("now * 2"))
        val u = assertUnsupported(m, "flattened field semaphore", f.at("Semaphore(1).also"))
        assertTrue("the lambda of also does something other than a jdk.Semaphore operation on its receiver whose result is unused at ${f.where("it.toString()")}" in u.reason, u.reason)
        // The argument for semaphore is given; it is not a missing constructor default.
        assertEquals(listOf("Long *", "flattened field semaphore"), m.constructs(), m.runText)
    }

    @Test
    fun `a local alias does not hide its initializer`() {
        // T-opus-4's tryLock as a var: `var entry = if (existing == null || ...) LockInfo(...) else existing`. The
        // val is supported since AI-CODE.md 14.1 A3.
        val f = LockFixture("var x = if (e == null) Info(Semaphore(1), now) else e\nx")
        val m = f.lower(dir)
        assertUnsupported(m, "local alias x", f.at("var x"))
        assertUnsupported(m, "if/when used as a value", f.at("if (e == null)"))
        assertEquals(2, m.constructs().size, m.runText)
    }

    @Test
    fun `both sides of && report their unsupported operators`() {
        val f = LockFixture("if (e != null && now * 2 > 0 && now % 3 > 0) e.expiresAt = now\ne")
        val m = f.lower(dir)
        assertUnsupported(m, "Long *", f.at("now * 2"))
        assertUnsupported(m, "Long %", f.at("now % 3"))
    }

    @Test
    fun `an unsupported helper argument does not hide the helper body`() {
        val f = LockFixture("if (e != null) e.expiresAt = later(now * 2, timeout)\ne", companion = "        private fun later(at: Long, d: Long): Long = at / d")
        val m = f.lower(dir)
        assertUnsupported(m, "Long *", f.at("now * 2"))
        assertUnsupported(m, "Long /", f.at("at / d"))
    }

    @Test
    fun `the reason of a flattened field names each construction that breaks it`() {
        // Each lambda does something other than model operations on its receiver (AI-CODE.md 14.1 A1).
        val body = "if (e == null) return@compute Info(Semaphore(1).also { it.toString() }, now)\nif (now < 0) return@compute Info(Semaphore(1).apply { hashCode() }, now)\ne"
        val f = LockFixture(body)
        val m = f.lower(dir)
        val u = assertUnsupported(m, "flattened field semaphore", f.at("Semaphore(1).also"))
        for ((scope, fragment) in listOf("also" to "it.toString()", "apply" to "hashCode()")) {
            assertTrue("the lambda of $scope does something other than a jdk.Semaphore operation on its receiver whose result is unused at ${f.where(fragment)}" in u.reason, u.reason)
        }
        // Each construction is reported where the lowering meets it, with the same reasons.
        val at = m.run.body.walk().filterIsInstance<Unsupported>().filter { it.what == "flattened field semaphore" }.map { it.pos.line to it.pos.col }.toList()
        assertEquals(listOf(f.at("Semaphore(1).also"), f.at("Semaphore(1).apply")), at, m.runText)
    }

    @Test
    fun `a local of a type outside the model does not hide its initializer`() {
        // T-sonnet-3's tryLock: `val semaphore = Semaphore(1)` inside compute.
        val f = LockFixture("if (e != null) return@compute e\nval s = Semaphore(1)\nInfo(Semaphore(1), now)")
        val m = f.lower(dir)
        assertUnsupported(m, "value of type java.util.concurrent.Semaphore", f.at("val s ="))
        assertUnsupported(m, "new Semaphore", f.at("Semaphore(1)", after = "val s ="))
    }
}
