package kv.frontend

import kv.ReqShield
import kv.cli.runCli
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JG4 B1 (DESIGN-C.md 10.16): two free items of one operation that bind one value are a `kv resolve` error at the
 * second item. The shapes are those of the JG4 measurement: L1, L2 (KeyLocalLock tryLock) and C4 (the client core).
 */
class FreeDuplicateTest {
    @TempDir
    lateinit var dir: File

    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")

    /** A copy of the signed specs with [old] replaced (once) by [new] in [file]; returns the copy of [file]. */
    private fun spec(
        file: String,
        old: String,
        new: String,
    ): File {
        specDir.listFiles()!!.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
        val f = File(dir, file)
        val text = f.readText()
        check(text.split(old).size == 2) { "'$old' occurs ${text.split(old).size - 1} times in $file" }
        f.writeText(text.replace(old, new))
        return f
    }

    /** `kv resolve` of [sidecar] on the req-shield checkout: the exit code and the error lines. */
    private fun resolve(sidecar: File): Pair<Int, List<String>> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val args = listOf("resolve", "--root", ReqShield.dir.path, "--classpath", ReqShield.classpath.joinToString(File.pathSeparator), "--kotlinc", ReqShield.kotlincArgs.joinToString(" "), sidecar.path)
        val exit = runCli(args, PrintStream(out), PrintStream(err))
        return exit to err.toString().lines().filter { "error[" in it }
    }

    /** The 1-based line of the copy of [file] that contains [fragment]. */
    private fun lineOf(
        file: String,
        fragment: String,
    ) = File(dir, file).readLines().indexOfFirst { fragment in it } + 1

    private fun assertDuplicate(
        sidecar: File,
        file: String,
        at: String,
        message: String,
    ) {
        val (exit, errors) = resolve(sidecar)
        assertEquals(1, exit, errors.joinToString("\n"))
        val e = errors.single()
        assertTrue(e.startsWith("${File(dir, file).path}:${lineOf(file, at)}:") && "error[free-duplicate]: $message" in e, e)
    }

    @Test
    fun `L1 - a this path bound twice in tryLock is an error at the second item`() {
        spec("keylocallock.common.kv", "free now        = local now", "free now        = this.lockTimeoutMillis")
        assertDuplicate(
            File(dir, "keylocallock.core.kv"),
            "keylocallock.common.kv",
            "free timeout    = this.lockTimeoutMillis",
            "free tryLock.timeout binds the value that free tryLock.now binds (lockTimeoutMillis)",
        )
    }

    @Test
    fun `L2 - a local bound twice in tryLock is an error at the second item`() {
        spec("keylocallock.common.kv", "free timeout    = this.lockTimeoutMillis", "free timeout    = local now")
        assertDuplicate(File(dir, "keylocallock.coroutine.kv"), "keylocallock.common.kv", "free timeout    = local now", "free tryLock.timeout binds the value that free tryLock.now binds (now)")
    }

    @Test
    fun `a call bound twice in tryLock is an error at the second item`() {
        val common = spec("keylocallock.common.kv", "  free rej, limit = call rejectsNewEntry\n", "  free rej, limit = call rejectsNewEntry\n  free rej2, limit2 = call rejectsNewEntry\n")
        // The probe must give the new names a value; it is re-signed as a person would, so only B1 can fail.
        common.writeText(common.readText().replace("rej = false, limit = 0)", "rej = false, limit = 0, rej2 = false, limit2 = 0)"))
        kv.rereview(common)
        assertDuplicate(File(dir, "keylocallock.reactor.kv"), "keylocallock.common.kv", "free rej2, limit2", "free tryLock.rej2, limit2 binds the value that free tryLock.rej, limit binds (rejectsNewEntry)")
    }

    @Test
    fun `C4 - a client path bound twice is an error at the second item, and the signed core resolves`() {
        assertEquals(0 to emptyList(), resolve(spec("reqshield.core.kv", "free maxAttempt =", "free maxAttempt =")))
        val core = spec("reqshield.core.kv", "free maxAttempt = this.reqShieldConfig.maxAttemptGetCache", "free maxAttempt = this.reqShieldConfig.reqShieldWorkMode")
        assertDuplicate(core, "reqshield.core.kv", "free maxAttempt =", "free getAndSet.maxAttempt binds the value that free getAndSet.mode binds (reqShieldConfig.reqShieldWorkMode)")
    }
}
