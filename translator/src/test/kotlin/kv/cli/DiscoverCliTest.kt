package kv.cli

import kv.Harness
import kv.ReqShield
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M0 acceptance test 1 (DESIGN-v0.md section 7): `kv discover` on the unmodified req-shield sources,
 * through the CLI, the in-process compiler and the plugin.
 */
class DiscoverCliTest {
    private class Run(
        val exit: Int,
        val out: String,
        val err: String,
    )

    private fun kv(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = runCli(args.toList(), PrintStream(out), PrintStream(err))
        return Run(exit, out.toString(), err.toString())
    }

    private fun discover(variantDir: File): Run =
        kv(
            "discover",
            "--classpath", ReqShield.classpath.joinToString(File.pathSeparator),
            "--kotlinc", ReqShield.kotlincArgs.joinToString(" "),
            *ReqShield.sources(variantDir).map { it.path }.toTypedArray(),
        )

    /** `line:col class.fun` of each reported site in KeyLocalLock.kt, in output order. */
    private fun sites(out: String): List<String> =
        out.lines().filter { it.isNotBlank() }.map { line ->
            val (pos, _, cls, fn) = line.split(" ")
            assertTrue(pos.substringBeforeLast(':').substringBeforeLast(':').endsWith("/KeyLocalLock.kt"), line)
            "${pos.substringAfter("KeyLocalLock.kt:")} ${cls.substringAfterLast('.')}.${fn.removePrefix("fun=")}"
        }

    @Test
    fun `finds the three compute sites of the coroutine variant`() {
        val run = discover(ReqShield.coroutineDir)
        assertEquals(0, run.exit, run.err)
        assertEquals(
            listOf("97:37 Companion.ensureMonitorStarted", "155:9 KeyLocalLock.tryLock", "209:9 KeyLocalLock.unLock"),
            sites(run.out),
        )
    }

    @Test
    fun `finds the three compute sites of the core variant`() {
        val run = discover(ReqShield.coreDir)
        assertEquals(0, run.exit, run.err)
        assertEquals(
            listOf("109:25 Companion.startMonitoring", "173:9 KeyLocalLock.tryLock", "229:9 KeyLocalLock.unLock"),
            sites(run.out),
        )
    }

    @Test
    fun `fails with the compiler messages when the sources do not compile`() {
        // Without the req-shield classpath, KeyLocalLock.kt cannot resolve support and slf4j.
        val run = kv("discover", "--classpath", Harness.stdlib.path, *ReqShield.sources(ReqShield.coroutineDir).map { it.path }.toTypedArray())
        assertEquals(1, run.exit)
        assertTrue("unresolved reference 'support'" in run.err && "compilation failed" in run.err, run.err)
        assertEquals("", run.out)
    }

    @Test
    fun `rejects a call without files and an unknown command`() {
        assertEquals(2, kv("discover").exit)
        assertEquals(2, kv("verify").exit)
        assertEquals(2, kv("discover", "--frobnicate", "x.kt").exit)
    }
}
