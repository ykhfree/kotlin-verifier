package kv.cli

import kv.ReqShield
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `kv lower` through the CLI: the KVIR of a module, its W1-W14 check and its completeness. */
class LowerCliTest {
    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")

    private fun lower(sidecar: File): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val args = listOf("lower", "--root", ReqShield.dir.path, "--classpath", ReqShield.classpath.joinToString(File.pathSeparator), "--kotlinc", ReqShield.kotlincArgs.joinToString(" "), sidecar.path)
        val exit = runCli(args, PrintStream(out), PrintStream(err))
        return Triple(exit, out.toString(), err.toString())
    }

    @Test
    fun `prints the KVIR of the core module, which is complete and well formed`() {
        val (exit, out, err) = lower(File(specDir, "keylocallock.core.kv"))
        assertEquals(0, exit, err)
        assertTrue("  action tryLock#173 = lockMap.compute" in out, out)
        assertTrue("          newLock__206.token := token__205" in out, out)
        assertTrue(out.trimEnd().endsWith("module complete; W1-W14: 0 violation(s)"), out)
    }

    @Test
    fun `reports an unsupported operation and an INCOMPLETE module`(
        @TempDir dir: File,
    ) {
        specDir.listFiles()!!.forEach { it.copyTo(File(dir, it.name)) }
        // stopMonitoring has no compute call: v0 cannot translate that operation (U2).
        File(dir, "keylocallock.common.kv").appendText("\noperation stop = fun KeyLocalLock.Companion.stopMonitoring {\n}\n")
        val (exit, out, err) = lower(File(dir, "keylocallock.coroutine.kv"))
        assertEquals(0, exit, err)
        assertTrue(out.lines().any { it.trimStart().startsWith("U129_9 UNSUPPORTED operation stop") && "0 action calls" in it }, out)
        assertTrue(out.trimEnd().endsWith("module INCOMPLETE (unsupported parts above); W1-W14: 0 violation(s)"), out)
    }
}
