package kv.cli

import kv.ReqShield
import kv.emit.Workspace
import kv.verify.Silicon
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `kv verify` exit codes (DESIGN-v0.md 8.3, Q7): 0 PROVEN, 1 FAILED, 2 usage error, 3 INCOMPLETE, 4 ERROR.
 * The cases that run Silicon fail, not skip, without it.
 */
class VerifyCliTest {
    @TempDir
    lateinit var dir: File

    private lateinit var ws: Workspace

    @BeforeEach
    fun setUp() {
        ws = Workspace(dir)
    }

    private fun kv(args: List<String>): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = runCli(args, PrintStream(out), PrintStream(err))
        return Triple(exit, out.toString(), err.toString())
    }

    /** `kv verify` on the workspace's [variant], with [options] before the sidecar. */
    private fun verify(
        variant: String,
        vararg options: String,
    ) = kv(
        listOf(
            "verify", "--root", ws.root.path,
            "--classpath", ReqShield.classpath.joinToString(File.pathSeparator),
            "--kotlinc", ReqShield.kotlincArgs.joinToString(" "),
            "--out", File(dir, "$variant.vpr").path,
        ) + options + File(ws.specs, "keylocallock.$variant.kv").path,
    )

    @Test
    fun `exit 0 for a PROVEN module`() {
        Silicon.check(Silicon.defaultHome())
        val (exit, out, err) = verify("core")
        assertEquals(0, exit, out + err)
        assertTrue("module: PROVEN" in out.lines().first(), out)
    }

    @Test
    fun `exit 1 for a FAILED module`() {
        Silicon.check(Silicon.defaultHome())
        ws.editKotlin("coroutine", "existing.token == token && ", "")
        val (exit, out, err) = verify("coroutine")
        assertEquals(1, exit, out + err)
        assertTrue("module: FAILED" in out.lines().first(), out)
    }

    @Test
    fun `exit 3 for an INCOMPLETE module`() {
        Silicon.check(Silicon.defaultHome())
        ws.editKotlin("coroutine", "            if (existing == null) return@compute null\n", "            if (existing == null) return@compute null\n            val seen = key\n")
        val (exit, out, err) = verify("coroutine")
        assertEquals(3, exit, out + err)
        assertTrue("module: INCOMPLETE" in out.lines().first(), out)
    }

    @Test
    fun `exit 4 for a sidecar error, without a Silicon run`() {
        ws.editSpec("keylocallock.common.kv", "free t: Token = param token", "free t = param token")
        val (exit, _, err) = verify("coroutine")
        assertEquals(4, exit, err)
        assertTrue("error[free-type]" in err, err)
    }

    @Test
    fun `exit 4 for a compile error, without a Silicon run`() {
        ws.editKotlin("coroutine", "    fun cancel() {", "    fun cancel() {\n        undefinedCall()")
        val (exit, _, err) = verify("coroutine")
        assertEquals(4, exit, err)
        assertTrue("compilation failed" in err, err)
    }

    @Test
    fun `exit 4 for a missing Silicon and for a run killed at the wall clock limit`() {
        val (missing, _, missingErr) = verify("core", "--viper-home", File(dir, "no-viper").path)
        assertEquals(4, missing, missingErr)
        assertTrue("Silicon is not installed" in missingErr, missingErr)

        Silicon.check(Silicon.defaultHome())
        val (killed, out, err) = verify("core", "--wall-seconds", "1")
        assertEquals(4, killed, out + err)
        assertTrue("module: ERROR" in out.lines().first(), out)
    }

    @Test
    fun `exit 2 for a usage error`() {
        assertEquals(2, kv(listOf("verify", File(ws.specs, "keylocallock.core.kv").path)).first)
        assertEquals(2, kv(listOf("verify", "--root", ws.root.path, "--wall-seconds", "soon", File(ws.specs, "keylocallock.core.kv").path)).first)
    }
}
