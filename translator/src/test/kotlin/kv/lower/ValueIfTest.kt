package kv.lower

import kv.emit.Workspace
import kv.verify.ModuleVerdict
import kv.verify.Silicon
import kv.verify.Verdict
import kv.verify.render
import kv.verify.row
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AI-CODE.md 14.1 A2: an if or when used as a value inside an action is a mutable local. It starts as 0, false or
 * null, and each branch assigns its value after its statements. The value is a Long, Int or Boolean, or a nullable
 * Token or Key; an object value is a local alias (A3, AliasTest) or the lambda result.
 */
class ValueIfTest {
    @TempDir
    lateinit var dir: File

    private fun LockFixture.e() = "e__${at("{ _, e ->").first}"

    /** The value local of the if or when at [fragment]. */
    private fun LockFixture.value(fragment: String) = at(fragment, after = "{ _, e ->").let { (l, c) -> "value_${l}_$c" }

    private fun sub(name: String) = File(dir, name).apply { mkdirs() }

    @Test
    fun `a Long if value is assigned in each branch and then used`() {
        val f = LockFixture("if (e != null) e.expiresAt = if (now > e.expiresAt) now + timeout else e.expiresAt\ne")
        val e = f.e()
        val v = f.value("if (now >")
        assertEquals(
            listOf(
                "if $e != null",
                "  var $v: Int := 0",
                "  if now > $e.expiresAt",
                "    $v := now + timeout [ASSUME A${f.at("now + timeout").first}]",
                "  else",
                "    $v := $e.expiresAt",
                "  $e.expiresAt := $v",
                "exit keep",
            ),
            shape(assertSupported(f.lower(dir))),
        )
    }

    @Test
    fun `a Boolean when value runs the statements of its branch before the value`() {
        val f = LockFixture("val live = when {\n    e == null -> false\n    now > e.expiresAt -> {\n        val late = now - e.expiresAt\n        late < 0\n    }\n    else -> true\n}\nif (live) e else null")
        val e = f.e()
        val v = f.value("when {")
        val late = "late__${f.at("val late").first}"
        val live = "live__${f.at("val live").first}"
        assertEquals(
            listOf(
                "var $v: Bool := false",
                "if $e == null",
                "  $v := false",
                "else",
                "  if now > $e.expiresAt",
                "    let $late: Int := now - $e.expiresAt [ASSUME A${f.at("val late").first}]",
                "    $v := $late < 0",
                "  else",
                "    $v := true",
                "let $live: Bool := $v",
                "if $live",
                "  exit keep",
                "else",
                "  exit remove",
            ),
            shape(assertSupported(f.lower(dir))),
        )
    }

    @Test
    fun `a nullable Token if value starts as null`() {
        val f = LockFixture("val t = if (e != null) e.token else null\nresult.set(t)\ne")
        val v = f.value("if (e != null)")
        val t = "t__${f.at("val t =").first}"
        val text = assertSupported(f.lower(dir))
        val lines = shape(text)
        assertEquals(listOf("var $v: Token? := null", "if ${f.e()} != null", "  $v := ${f.e()}.token", "else", "  $v := null", "let $t: Token? := $v"), lines.take(6), text)
        assertTrue(lines.any { it.startsWith("result__") && it.endsWith(":= $t") }, text)
    }

    @Test
    fun `an object if value outside a local val and a non-null Token if value are unsupported`() {
        val obj = LockFixture("if (pick(if (now > 0) e else null) == null) return@compute null\ne", companion = "        private fun pick(i: Info?): Info? = i")
        val u = assertUnsupported(obj.lower(sub("object")), "if/when used as a value", obj.at("if (now > 0)"))
        assertTrue("AI-CODE.md 14.1 A3" in u.reason, u.reason)
        val token = LockFixture("val t = if (now > 0) \"local-\$now\" else \"local-\${now + 1}\"\nresult.set(t)\ne")
        val v = assertUnsupported(token.lower(sub("token")), "if/when used as a value", token.at("if (now > 0)"))
        assertTrue("has no initial value" in v.reason, v.reason)
    }

    // ---- Silicon ----

    private fun verifyLease(
        name: String,
        lease: String,
    ): kv.verify.Report {
        val ws = Workspace(sub(name))
        ws.editKotlin("coroutine", "existing.expiresAt = now + lockTimeoutMillis", "existing.expiresAt = $lease")
        return ws.verify("coroutine")
    }

    @Test
    fun `an if value in tryLock is PROVEN when both branches give the lease, and FAILED when one does not`() {
        Silicon.check(Silicon.defaultHome())
        val ok = verifyLease("same", "if (lockTimeoutMillis >= 0) now + lockTimeoutMillis else now + lockTimeoutMillis")
        assertEquals(ModuleVerdict.PROVEN, ok.verdict, ok.render())
        // A negative timeout takes the else branch, whose expiresAt differs from the issued lease.
        val bad = verifyLease("other", "if (lockTimeoutMillis >= 0) now + lockTimeoutMillis else now")
        val text = bad.render()
        assertEquals(ModuleVerdict.FAILED, bad.verdict, text)
        assertEquals(Verdict.FAILED, bad.row("tryLock").verdict, text)
    }
}
