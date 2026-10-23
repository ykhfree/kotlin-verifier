package kv.cli

import kv.ReqShield
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `kv resolve` through the CLI, the in-process compiler, the plugin and the resolver. */
class ResolveCliTest {
    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")

    private fun kv(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = runCli(args.toList(), PrintStream(out), PrintStream(err))
        return Triple(exit, out.toString(), err.toString())
    }

    private fun resolve(sidecar: File) =
        kv(
            "resolve",
            "--root", ReqShield.dir.path,
            "--classpath", ReqShield.classpath.joinToString(File.pathSeparator),
            "--kotlinc", ReqShield.kotlincArgs.joinToString(" "),
            sidecar.path,
        )

    @Test
    fun `prints one binding per sidecar name and exits 0`() {
        val (exit, out, err) = resolve(File(specDir, "keylocallock.coroutine.kv"))
        assertEquals(0, exit, err)
        val lines = out.lines().filter { it.isNotBlank() }
        assertEquals(21, lines.size, out)
        assertTrue("action sweep -> compute call @KeyLocalLock.kt:97:37" in lines, out)
        assertTrue("free unLock.t -> param token @KeyLocalLock.kt:201:9" in lines, out)
        assertTrue(lines.any { it.startsWith("trusted rejectsNewEntry -> fun com.linecorp.cse.reqshield.support.config.LocalLockLimit.rejectsNewEntry(kotlin.Long) (library)") }, out)
    }

    @Test
    fun `prints sidecar errors with file, line and column and exits 1`(
        @TempDir dir: File,
    ) {
        specDir.listFiles()!!.forEach { it.copyTo(File(dir, it.name)) }
        val common = File(dir, "keylocallock.common.kv")
        common.writeText(common.readText().replace("compute in KeyLocalLock.Companion", "compute in KeyLocalLock"))
        val (exit, out, err) = resolve(File(dir, "keylocallock.core.kv"))
        assertEquals(1, exit)
        assertEquals("", out)
        val line = common.readLines().indexOfFirst { "compute in KeyLocalLock" in it } + 1
        assertTrue("keylocallock.common.kv:$line:30: error[ambiguous-selector]: compute in KeyLocalLock matches 2 action calls [173:9, 229:9]" in err, err)
    }

    @Test
    fun `needs a root and exactly one sidecar`() {
        assertEquals(2, kv("resolve", File(specDir, "keylocallock.core.kv").path).first)
        assertEquals(2, kv("resolve", "--root", ReqShield.dir.path).first)
    }
}
