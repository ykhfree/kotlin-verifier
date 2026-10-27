package kv.frontend

import kv.ReqShield
import kv.cli.runCli
import kv.sidecar.sha16
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DESIGN-E 2.5: the `reviewed` notes of the trusted declarations of the signed core ReqShield sidecar. A trusted value
 * is bound to its declaration (here a constructor `val` parameter of ReqShieldConfiguration); a JDK function, which has
 * no source, to its signature. Copies of the core and support sources and of the sidecar; no Silicon run.
 */
class ClientTrustedReviewTest {
    @TempDir
    lateinit var dir: File

    private val config = "core/src/main/kotlin/com/linecorp/cse/reqshield/config/ReqShieldConfiguration.kt"
    private val getter = "    val getCacheFunction: (String) -> ReqShieldData<T>?,\n"

    /** A copy of the core and support main sources, with [old] replaced (once) by [new] in ReqShieldConfiguration.kt. */
    private fun root(
        old: String = getter,
        new: String = getter,
    ): File {
        val root = File(dir, "root")
        for (src in listOf("core/src/main/kotlin", "support/src/main/kotlin")) File(ReqShield.dir, src).copyRecursively(File(root, src))
        val f = File(root, config)
        val text = f.readText()
        check(text.split(old).size == 2) { "'$old' occurs ${text.split(old).size - 1} times in $config" }
        f.writeText(text.replace(old, new))
        return root
    }

    /** A copy of the sidecar with [old] replaced (once) by [new]. */
    private fun sidecar(
        old: String = "",
        new: String = "",
    ): File {
        val text = File(System.getProperty("kv.specDir"), "reqshield/reqshield.core.kv").readText()
        check(old.isEmpty() || text.split(old).size == 2) { "'$old' occurs ${text.split(old).size - 1} times in the sidecar" }
        return File(dir, "reqshield.core.kv").apply { writeText(if (old.isEmpty()) text else text.replace(old, new)) }
    }

    /** `kv resolve` of [sidecar] under [root]: the exit code and the error lines. */
    private fun resolve(
        root: File,
        sidecar: File,
    ): Pair<Int, List<String>> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val args = listOf("resolve", "--root", root.path, "--classpath", ReqShield.classpath.joinToString(File.pathSeparator), "--kotlinc", ReqShield.kotlincArgs.joinToString(" "), sidecar.path)
        val exit = runCli(args, PrintStream(out), PrintStream(err))
        return exit to err.toString().lines().filter { "error[" in it }
    }

    @Test
    fun `a changed trusted value declaration is stale-review at its note, with the fingerprint of the new declaration`() {
        val (exit, errors) = resolve(root(new = "    val getCacheFunction: (key: String) -> ReqShieldData<T>?,\n"), sidecar())
        assertEquals(1, exit)
        val e = errors.single()
        val now = sha16("val getCacheFunction: (key: String) -> ReqShieldData<T>?")
        assertTrue("error[stale-review]: trusted value getCacheFunction changed after its review" in e && "sha256 \"87e6f63305e3fe90\"" in e, e)
        assertTrue("the declaration at ReqShieldConfiguration.kt:34 now has sha256 \"$now\"" in e, e)
    }

    @Test
    fun `KDoc and an annotation before a trusted value are outside its reviewed text`() {
        val (exit, errors) = resolve(root(new = "    /** A comment a person did not review. */\n    @Suppress(\"unused\")\n$getter"), sidecar())
        assertEquals(0, exit, errors.joinToString("\n"))
    }

    @Test
    fun `a trusted value note without a fingerprint names the fingerprint to record`() {
        val (exit, errors) = resolve(root(), sidecar(" sha256 \"87e6f63305e3fe90\"", ""))
        assertEquals(1, exit)
        val e = errors.single()
        assertTrue("error[missing-fingerprint]: the reviewed note of trusted value getCacheFunction" in e && "sha256 \"87e6f63305e3fe90\" (the declaration at ReqShieldConfiguration.kt:34)" in e, e)
    }

    @Test
    fun `a JDK trusted fun is bound to its signature`() {
        assertEquals("f6747f6649ac463f", sha16("java.util.concurrent.Callable.call(): V"))
        val (exit, errors) = resolve(root(), sidecar(" sha256 \"f6747f6649ac463f\"", " sha256 \"0123456789abcdef\""))
        assertEquals(1, exit)
        val e = errors.single()
        assertTrue("error[stale-review]: trusted fun call changed after its review" in e && "the library signature java.util.concurrent.Callable.call(): V now has sha256 \"f6747f6649ac463f\"" in e, e)
    }
}
