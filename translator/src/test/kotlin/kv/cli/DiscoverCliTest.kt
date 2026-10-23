package kv.cli

import kv.Aspects
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
    fun `finds the three compute sites of the reactor variant`() {
        val run = discover(ReqShield.reactorDir)
        assertEquals(0, run.exit, run.err)
        assertEquals(
            listOf("105:41 Companion.startMonitoringOnce", "170:13 KeyLocalLock.tryLock", "227:13 KeyLocalLock.unLock"),
            sites(run.out),
        )
    }

    /**
     * DESIGN-B.md 8, B0 acceptance 1: in each Spring aspect module, `kv discover` on the main source root finds the
     * three computeIfAbsent calls of section 1.1 (reqShieldMap, expressionMap, keyGeneratorMap), all in the aspect file.
     */
    @Test
    fun `finds the three computeIfAbsent calls of each Spring aspect module`() {
        val expected =
            mapOf(
                "core-spring" to listOf("112:9 reqShieldMap getOrCreateReqShield", "237:9 expressionMap getOrParseExpression", "246:16 keyGeneratorMap getOrCreateKeyGenerator"),
                "webflux" to listOf("125:9 reqShieldMap getOrCreateReqShield", "249:9 expressionMap getOrParseExpression", "258:16 keyGeneratorMap getOrCreateKeyGenerator"),
                "coroutine" to listOf("196:34 expressionMap getCacheKeyOrDefault", "232:9 reqShieldMap getOrCreateReqShield", "282:16 keyGeneratorMap getOrCreateKeyGenerator"),
            )
        for ((module, sites) in expected) {
            val run =
                kv(
                    "discover",
                    "--classpath", Aspects.classpath.joinToString(File.pathSeparator),
                    "--kotlinc", ReqShield.kotlincArgs.joinToString(" "),
                    File(ReqShield.dir, Aspects.roots.getValue(module)).path,
                )
            assertEquals(0, run.exit, run.err)
            val lines = run.out.lines().filter { it.isNotBlank() }
            assertTrue(lines.all { it.substringBefore(' ').substringBeforeLast(':').substringBeforeLast(':').endsWith(Aspects.aspectFile(module)) }, run.out)
            val actual =
                lines.map { line ->
                    val (pos, call, _, fn) = line.split(" ")
                    assertEquals("computeIfAbsent", call.substringAfter('.'), line)
                    "${pos.substringAfter("ReqShieldAspect.kt:")} ${call.substringBefore('.')} ${fn.removePrefix("fun=")}"
                }
            assertEquals(sites, actual, module)
        }
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
