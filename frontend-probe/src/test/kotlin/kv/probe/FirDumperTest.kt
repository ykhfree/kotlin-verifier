package kv.probe

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Compiles small fixtures in-process with the built plugin jar and checks the dumped facts.
 * Each test compiles its own fixture in its own temporary directory.
 */
class FirDumperTest {
    @TempDir
    lateinit var dir: File

    private val fixture =
        """
        package fixture

        import java.util.concurrent.ConcurrentHashMap

        class Box(@Volatile var v: Long, var plain: Long)

        val map = ConcurrentHashMap<String, Box>()

        suspend fun pause() {}

        fun plainCall(): Long = 1L

        suspend fun work(b: Box?, s: String?): String {
            pause()
            map.compute("k") { _, old ->
                if (old == null) return@compute null
                old.v = old.plain + plainCall()
                old.plain = 2
                old
            }
            val n = s?.length ?: -1
            if (b != null) b.v = n.toLong()
            return "n=${'$'}n"
        }
        """.trimIndent()

    /** Compiles [sources] with the plugin, dumping only files under `dir/included`, and returns the dump. */
    private fun dump(vararg sources: Pair<String, String>): String {
        val included = File(dir, "included").apply { mkdirs() }
        val files =
            sources.map { (relativePath, text) ->
                File(dir, relativePath).apply {
                    parentFile.mkdirs()
                    writeText(text)
                }
            }
        val out = File(dir, "dump.txt")
        val stdlib = File(KotlinVersion::class.java.protectionDomain.codeSource.location.toURI())
        val messages = ByteArrayOutputStream()
        val exit =
            K2JVMCompiler().exec(
                PrintStream(messages),
                "-Xplugin=${System.getProperty("probe.pluginJar")}",
                "-P", "plugin:$PLUGIN_ID:include=${included.path}",
                "-P", "plugin:$PLUGIN_ID:out=${out.path}",
                "-no-stdlib", "-no-reflect",
                "-classpath", stdlib.path,
                "-d", File(dir, "classes").path,
                *files.map { it.path }.toTypedArray(),
            )
        assertEquals(ExitCode.OK, exit, messages.toString())
        return out.readText()
    }

    private fun String.lineWith(fragment: String): String = lines().single { fragment in it }

    @Test
    fun `marks calls to suspend functions and only those`() {
        val text = dump("included/Fixture.kt" to fixture)
        assertTrue(text.lineWith("CALL fixture/pause(").contains("[SUSPEND "), text)
        assertFalse(text.lineWith("CALL fixture/plainCall(").contains("SUSPEND"), text)
        assertTrue(text.lineWith("fun fixture/work(").contains("[SUSPEND "), text)
    }

    @Test
    fun `marks writes of volatile properties and not of plain ones`() {
        val text = dump("included/Fixture.kt" to fixture)
        val writes = text.lines().filter { "WRITE property var fixture/Box." in it }
        assertEquals(3, writes.size, text)
        assertTrue(writes.filter { "fixture/Box.v " in it }.all { "VOLATILE" in it }, text)
        assertTrue(writes.filter { "fixture/Box.plain " in it }.none { "VOLATILE" in it }, text)
        // The read of the plain property inside the compute lambda is not volatile either.
        assertFalse(text.lineWith("READ property var fixture/Box.plain").contains("VOLATILE"), text)
    }

    @Test
    fun `resolves labeled and implicit returns to the compute lambda`() {
        val text = dump("included/Fixture.kt" to fixture)
        // 1-based position of the lambda's opening brace in the fixture.
        val lambdaLine = fixture.lines().indexOfFirst { "map.compute" in it } + 1
        val lambdaCol = fixture.lines()[lambdaLine - 1].indexOf('{') + 1
        val target = "lambda@compute{$lambdaLine:$lambdaCol}"
        assertTrue(text.lineWith("return label=compute ").contains("-> $target"), text)
        assertTrue(text.lines().any { "return label=null -> $target" in it && "fake=FromLastStatement" in it }, text)
        assertTrue(text.lineWith("function-conversion kind=Sam").contains("java.util.function.BiFunction"), text)
        assertTrue(text.lineWith("return label=work ").contains("-> fun fixture/work"), text)
    }

    @Test
    fun `shows smart casts, safe calls, elvis and template parts`() {
        val text = dump("included/Fixture.kt" to fixture)
        assertTrue(text.lines().any { "smartcast fixture.Box? -> fixture.Box stable=true" in it }, text)
        assertTrue("safe-call : kotlin.Int?" in text, text)
        assertTrue("elvis : kotlin.Int" in text, text)
        assertTrue("part: const String n=" in text, text)
        assertTrue(Regex("""part: READ local n\{\d+:\d+\} : kotlin.Int""").containsMatchIn(text), text)
        assertFalse("UNRESOLVED" in text || "?FirError" in text, text)
    }

    @Test
    fun `dumps only files under the include path`() {
        val text = dump("included/Fixture.kt" to fixture, "other/Other.kt" to "package other\n\nfun outside(): Int = 1\n")
        assertTrue("fun fixture/work(" in text, text)
        assertFalse("other/outside" in text, text)
    }
}
