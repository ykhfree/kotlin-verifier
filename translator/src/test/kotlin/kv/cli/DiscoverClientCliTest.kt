package kv.cli

import kv.ReqShield
import kv.client.ClientFixture
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DESIGN-E 6.10 E0 acceptance: `kv discover --client` finds the client module candidates, their entries and tasks. */
class DiscoverClientCliTest {
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

    /** Each output line without the file path: `line:col kind rest`. */
    private fun lines(out: String): List<String> = out.lines().filter { it.isNotBlank() }.map { it.substringAfter(".kt:") }

    @Test
    fun `finds the core ReqShield with one entry and the two tasks at lines 69 and 125`() {
        val run =
            kv(
                "discover", "--client",
                "--classpath", ReqShield.classpath.joinToString(File.pathSeparator),
                "--kotlinc", ReqShield.kotlincArgs.joinToString(" "),
                File(ReqShield.dir, "core/src/main/kotlin").path,
            )
        assertEquals(0, run.exit, run.err)
        assertTrue(run.out.lines().filter { it.isNotBlank() }.all { it.substringBefore(".kt:").endsWith("/ReqShield") }, run.out)
        assertEquals(
            listOf(
                "35:1 client com.linecorp.cse.reqshield.ReqShield entries=1 tasks=2",
                "38:5 entry getAndSetReqShieldData helpers=executeGetCacheFunction,shouldUpdateCache,updateReqShieldData," +
                    "handleLockForCacheCreation,runInBackground,buildReqShieldData,executeCallable,executeSetCacheFunction," +
                    "createReqShieldData,handleLockFailure,logAsyncFailure,sleepBetweenPolls",
                "69:51 task fun=updateReqShieldData via=runInBackground spawn=144:13",
                "125:47 task fun=createReqShieldData via=runInBackground spawn=144:13",
            ),
            lines(run.out),
        )
    }

    @Test
    fun `without --client the same sources list the map action calls only`() {
        val run =
            kv(
                "discover",
                "--classpath", ReqShield.classpath.joinToString(File.pathSeparator),
                "--kotlinc", ReqShield.kotlincArgs.joinToString(" "),
                File(ReqShield.dir, "core/src/main/kotlin").path,
            )
        assertEquals(0, run.exit, run.err)
        assertEquals(listOf("109:25", "173:9", "229:9"), lines(run.out).map { it.substringBefore(' ') }, run.out)
    }

    @Test
    fun `follows a task through helper parameters and reports a spawn it cannot follow`(
        @TempDir dir: File,
    ) {
        val client =
            ClientFixture.client(
                """
                val data = Data(value = callable.call(), ttl = 0L)
                outer { config.set.invoke(key, data, 0L) }
                CompletableFuture.runAsync(Runnable { }, config.executor)
                CompletableFuture.runAsync({ log.info("direct") }, config.executor)
                return data
                """,
                """
                private fun outer(task: () -> Unit) = inner(task)

                private fun inner(job: () -> Unit) {
                    CompletableFuture.runAsync(job, config.executor)
                }

                fun other() = inner { log.info("from other") }
                """,
            )
        File(dir, "Base.kt").writeText(ClientFixture.BASE)
        File(dir, "Client.kt").writeText(client)
        val run = kv("discover", "--client", "--classpath", ClientFixture.classpath.joinToString(File.pathSeparator), File(dir, "Base.kt").path, File(dir, "Client.kt").path)
        assertEquals(0, run.exit, run.err)
        val out = lines(run.out)
        assertTrue(out.size == 7, run.out)
        assertEquals("10:1 client fx.Client entries=2 tasks=3", out[0], run.out)
        assertEquals(listOf("13:5 entry run helpers=outer,inner", "29:5 entry other helpers=inner"), out.filter { it.split(' ')[1] == "entry" })
        // The lambda in run passes outer and inner; the direct one passes nothing; the one in other passes inner.
        assertEquals(
            listOf("18:15 task fun=run via=outer,inner spawn=26:9", "20:36 task fun=run via=- spawn=20:9", "29:25 task fun=other via=inner spawn=26:9"),
            out.filter { it.split(' ')[1] == "task" },
        )
        // A `Runnable { }` SAM-constructor call is not a lambda literal discovery follows.
        assertEquals(listOf("19:9 spawn unresolved: the task argument is neither a lambda literal nor a function-typed parameter"), out.filter { it.split(' ')[1] == "spawn" })
    }
}
