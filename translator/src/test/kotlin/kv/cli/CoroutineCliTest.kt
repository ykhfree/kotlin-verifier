package kv.cli

import kv.ReqShield
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DESIGN-E5 6.2 S7: `kv resolve` and `kv lower` on the signed coroutine ReqShield sidecar (26 notes, signed
 * 2026-10-30), and on an unsigned copy with and without --draft (CoroutineVerifyTest checks `kv verify`).
 */
class CoroutineCliTest {
    @TempDir
    lateinit var dir: File

    private val sidecar = File(System.getProperty("kv.specDir"), "reqshield/reqshield.coroutine.kv")

    private fun kv(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val all = listOf(args[0], "--root", ReqShield.dir.path, "--classpath", ReqShield.classpath.joinToString(File.pathSeparator), "--kotlinc", ReqShield.kotlincArgs.joinToString(" ")) + args.drop(1)
        val exit = runCli(all, PrintStream(out), PrintStream(err))
        return Triple(exit, out.toString(), err.toString())
    }

    @Test
    fun `resolve binds the signed coroutine sidecar, the supplier as a trusted param`() {
        val (exit, out, err) = kv("resolve", sidecar.path)
        assertEquals(0, exit, err)
        assertTrue(out.lines().any { it.startsWith("entry getAndSet -> fun com.linecorp.cse.reqshield.kotlin.coroutine.ReqShield.getAndSetReqShieldData") }, out)
        assertTrue("trusted param callable -> param callable @ReqShield.kt:45:9" in out.lines(), out)
        assertTrue(out.lines().any { it.startsWith("hole loop ReqShield.handleLockFailure -> loop of fun") && it.endsWith("@ReqShield.kt:165:9") }, out)
    }

    @Test
    fun `resolve without --draft reports each of the 26 notes once it is removed, and --draft accepts the copy`() {
        val lines = sidecar.readLines().filterNot { it.trimStart().startsWith("reviewed \"") }
        assertEquals(26, sidecar.readLines().size - lines.size, "the signed sidecar has 26 notes")
        val copy = File(dir, "reqshield.coroutine.kv").apply { writeText(lines.joinToString("\n", postfix = "\n")) }
        val (exit, _, err) = kv("resolve", copy.path)
        assertEquals(1, exit)
        assertEquals(26, err.lines().count { "error[unreviewed]" in it }, err)
        // The E5 items among them: the supplier parameter, A6 and RET_cancel.
        assertEquals(1, err.lines().count { "error[unreviewed]: trusted param ReqShield.getAndSetReqShieldData.callable needs" in it }, err)
        assertEquals(1, err.lines().count { "error[unreviewed]: assume A6 needs" in it }, err)
        assertEquals(1, err.lines().count { "error[unreviewed]: ensures RET_cancel of getAndSet needs" in it }, err)
        val (draftExit, _, draftErr) = kv("resolve", "--draft", copy.path)
        assertEquals(0, draftExit, draftErr)
    }

    @Test
    fun `lower prints the coroutine KVIR, complete and well formed, with the two launched tasks`() {
        val (exit, out, err) = kv("lower", sidecar.path)
        assertEquals(0, exit, err)
        assertTrue(out.startsWith("client module com.linecorp.cse.reqshield.kotlin.coroutine.ReqShield\n"), out)
        assertTrue("task task_75 = lambda in updateReqShieldData, spawned by getAndSet @75:69" in out.lines(), out)
        assertTrue("task task_138 = lambda in createReqShieldData, spawned by getAndSet @138:69" in out.lines(), out)
        assertTrue(out.trimEnd().endsWith("module complete; W1-W22: 0 violation(s)"), out)
    }
}
