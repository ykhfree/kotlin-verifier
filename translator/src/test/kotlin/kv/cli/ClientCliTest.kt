package kv.cli

import kv.ReqShield
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DESIGN-E 6.10 E1-E3: `kv resolve` and `kv lower` on the signed core ReqShield sidecar, and on an unsigned copy with
 * and without --draft; `kv emit` writes the client model of the unsigned copy with --draft only (ClientVerifyTest
 * checks `kv verify`).
 */
class ClientCliTest {
    @TempDir
    lateinit var dir: File

    private val sidecar = File(System.getProperty("kv.specDir"), "reqshield/reqshield.core.kv")

    /** The sidecar as it was before the signing round of 2026-10-27: every `reviewed` note removed. */
    private fun unsigned(): File {
        val lines = sidecar.readLines().filterNot { it.trimStart().startsWith("reviewed \"") }
        check(sidecar.readLines().size - lines.size == 22) { "the signed sidecar has 22 notes" }
        return File(dir, "reqshield.core.kv").apply { writeText(lines.joinToString("\n", postfix = "\n")) }
    }

    private fun kv(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val all = listOf(args[0], "--root", ReqShield.dir.path, "--classpath", ReqShield.classpath.joinToString(File.pathSeparator), "--kotlinc", ReqShield.kotlincArgs.joinToString(" ")) + args.drop(1)
        val exit = runCli(all, PrintStream(out), PrintStream(err))
        return Triple(exit, out.toString(), err.toString())
    }

    @Test
    fun `resolve binds the signed sidecar`() {
        val (exit, out, err) = kv("resolve", sidecar.path)
        assertEquals(0, exit, err)
        assertTrue(out.lines().any { it.startsWith("entry getAndSet -> fun com.linecorp.cse.reqshield.ReqShield.getAndSetReqShieldData") }, out)
        assertTrue(out.lines().any { it.startsWith("hole loop ReqShield.handleLockFailure -> loop of fun") && it.endsWith("@ReqShield.kt:181:9") }, out)
    }

    @Test
    fun `resolve without --draft reports every unsigned clause, assumption and trusted declaration, and --draft accepts them`() {
        val copy = unsigned()
        val (exit, _, err) = kv("resolve", copy.path)
        assertEquals(1, exit)
        assertEquals(22, err.lines().count { "error[unreviewed]" in it }, err)
        val (draftExit, _, draftErr) = kv("resolve", "--draft", copy.path)
        assertEquals(0, draftExit, draftErr)
    }

    @Test
    fun `lower prints the client KVIR, complete and well formed`() {
        val (exit, out, err) = kv("lower", sidecar.path)
        assertEquals(0, exit, err)
        assertTrue(out.startsWith("client module com.linecorp.cse.reqshield.ReqShield\n"), out)
        assertTrue(out.lines().any { it.startsWith("task task_125 = lambda in createReqShieldData via runInBackground, spawned by getAndSet") }, out)
        assertTrue(out.trimEnd().endsWith("module complete; W1-W22: 0 violation(s)"), out)
    }

    @Test
    fun `emit refuses the unsigned draft, and with --draft writes the client model`() {
        val copy = unsigned()
        val (emitExit, _, emitErr) = kv("emit", copy.path)
        assertEquals(1, emitExit)
        assertTrue("error[unreviewed]" in emitErr, emitErr)
        val (exit, out, err) = kv("emit", "--draft", copy.path)
        assertEquals(0, exit, err)
        assertTrue(out.lines().any { it.startsWith("method getAndSetReqShieldData(k: Key, mode: Int, maxAttempt: Int) returns (exc: Int, res: Ref, tr: Ref)") }, out)
        assertTrue(out.lines().any { it.startsWith("method task_69(k: Key, mode: Int, phase: Int, lockType__62: Int, token__66: Int)") }, out)
    }
}
