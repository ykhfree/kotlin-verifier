package kv.cli

import kv.ReqShield
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DESIGN-E 6.10 E1 and E2: `kv resolve` and `kv lower` on the draft core ReqShield sidecar, with and without --draft;
 * `kv emit` and `kv verify` refuse a client module until E3.
 */
class ClientCliTest {
    private val sidecar = File(System.getProperty("kv.specDir"), "reqshield/reqshield.core.kv")

    private fun kv(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val all = listOf(args[0], "--root", ReqShield.dir.path, "--classpath", ReqShield.classpath.joinToString(File.pathSeparator), "--kotlinc", ReqShield.kotlincArgs.joinToString(" ")) + args.drop(1)
        val exit = runCli(all, PrintStream(out), PrintStream(err))
        return Triple(exit, out.toString(), err.toString())
    }

    @Test
    fun `resolve --draft binds the draft sidecar`() {
        val (exit, out, err) = kv("resolve", "--draft", sidecar.path)
        assertEquals(0, exit, err)
        assertTrue(out.lines().any { it.startsWith("entry getAndSet -> fun com.linecorp.cse.reqshield.ReqShield.getAndSetReqShieldData") }, out)
        assertTrue(out.lines().any { it.startsWith("hole loop ReqShield.handleLockFailure@181 -> loop of fun") && it.endsWith("@ReqShield.kt:181:9") }, out)
    }

    @Test
    fun `resolve without --draft reports every unsigned clause, assumption and trusted declaration`() {
        val (exit, _, err) = kv("resolve", sidecar.path)
        assertEquals(1, exit)
        assertEquals(22, err.lines().count { "error[unreviewed]" in it }, err)
    }

    @Test
    fun `lower --draft prints the client KVIR, complete and well formed`() {
        val (exit, out, err) = kv("lower", "--draft", sidecar.path)
        assertEquals(0, exit, err)
        assertTrue(out.startsWith("client module com.linecorp.cse.reqshield.ReqShield\n"), out)
        assertTrue(out.lines().any { it.startsWith("task task_125 = lambda in createReqShieldData via runInBackground, spawned by getAndSet") }, out)
        assertTrue(out.trimEnd().endsWith("module complete; W1-W22: 0 violation(s)"), out)
    }

    @Test
    fun `verify refuses a client module until E3, and emit the unsigned draft`() {
        // kv emit takes no --draft: the draft's unsigned clauses stop it before the client check.
        val (emitExit, _, emitErr) = kv("emit", sidecar.path)
        assertEquals(1, emitExit)
        assertTrue("error[unreviewed]" in emitErr, emitErr)
        val (verifyExit, _, verifyErr) = kv("verify", "--draft", sidecar.path)
        assertEquals(4, verifyExit)
        assertTrue("its verification comes with DESIGN-E E3" in verifyErr, verifyErr)
    }
}
