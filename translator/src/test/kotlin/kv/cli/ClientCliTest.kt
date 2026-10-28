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

    /** The sidecar without its signatures: every `reviewed` note removed, the vocabulary and scope notes too. */
    private fun unsigned(): File {
        val lines = sidecar.readLines().filterNot { it.trimStart().startsWith("reviewed \"") }
        check(sidecar.readLines().size - lines.size == 24) { "the signed sidecar has 24 notes" }
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
    fun `resolve without --draft reports every unsigned clause, assumption, trusted declaration and the vocabulary, and --draft accepts them`() {
        val copy = unsigned()
        val (exit, _, err) = kv("resolve", copy.path)
        assertEquals(1, exit)
        assertEquals(24, err.lines().count { "error[unreviewed]" in it }, err)
        assertEquals(1, err.lines().count { "error[unreviewed]: scope \"README.md#waiting-for-another-request-lock-not-acquired\"" in it }, err)
        assertEquals(1, err.lines().count { "error[unreviewed]: the vocabulary (type Key, type Token, enum LockType, enum Mode, class Data, obligation Lease, trace)" in it }, err)
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

    /** A copy of the signed sidecar with [old] replaced by [new]; [old] must occur exactly once. */
    private fun edited(
        old: String,
        new: String,
    ): File {
        val text = sidecar.readText()
        check(text.split(old).size == 2) { "'$old' must occur once" }
        return File(dir, "edited.kv").apply { writeText(text.replace(old, new)) }
    }

    @Test
    fun `under review intent a trusted note covers its contract, so dropping throws RTE is stale - DESIGN-E 12 point 4`() {
        // Before T-A this copy resolved, and the original core verified PROVEN: an unsigned A2.
        val copy =
            edited("creates Lease(k, lt, t) if t != null throws RTE", "creates Lease(k, lt, t) if t != null").readText()
                .replace("consumes Lease(k, lt, t) throws RTE", "consumes Lease(k, lt, t)")
                .let { File(dir, "throws.kv").apply { writeText(it) } }
        val (exit, _, err) = kv("resolve", copy.path)
        assertEquals(1, exit)
        val stale = err.lines().filter { "error[" in it }
        assertEquals(listOf("tryLock", "unLock"), stale.map { it.substringAfter("error[stale-review]: trusted fun ").substringBefore(" ") }, err)
        assertTrue(stale.all { "the contract and the declaration at KeyLock.kt" in it }, err)
    }

    @Test
    fun `a comment in a trusted contract, or its note moved before the throws clause, keeps the fingerprint`() {
        val tryLock = "trusted fun KeyLock.tryLock(k: Key, lt: LockType) returns t: Token? creates Lease(k, lt, t) if t != null throws RTE"
        val commented = edited(tryLock, "$tryLock // a new comment")
        val (exit, _, err) = kv("resolve", commented.path)
        assertEquals(0, exit, err)
        val note = sidecar.readLines().first { it.trimStart().startsWith("reviewed \"ykhfree 2026-10-27: KeyLock.kt contract: a non-null token") }
        val moved = edited("$tryLock\n$note\n", "${tryLock.removeSuffix(" throws RTE")}\n$note\n  throws RTE\n")
        val (movedExit, _, movedErr) = kv("resolve", moved.path)
        assertEquals(0, movedExit, movedErr)
    }
}
