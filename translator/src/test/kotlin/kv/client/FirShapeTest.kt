package kv.client

import kv.frontend.Frontend
import kv.frontend.findClass
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DESIGN-E 6.10 E0: the FIR shapes of the 16 constructs of DESIGN-E 6.4, as K2 2.4.21 resolves the supported construct
 * fixtures ([ClientConstructs]). The client lowering reads these shapes, so a compiler change that alters one shows
 * here first. The rendering is [FirShape]; golden file: `client/fir-shapes.txt`.
 */
class FirShapeTest {
    @TempDir
    lateinit var dir: File

    /** The rendered functions of class fx.Client in [source]. */
    @OptIn(DirectDeclarationsAccess::class)
    private fun shapes(source: String): List<String> {
        File(dir, "Base.kt").writeText(ClientFixture.BASE)
        File(dir, "Client.kt").writeText(source)
        val out = mutableListOf<String>()
        val result =
            Frontend.run(listOf(File(dir, "Base.kt"), File(dir, "Client.kt")), ClientFixture.classpath, onFile = { f ->
                findClass(f, "fx.Client")?.let { c -> out += c.declarations.filterIsInstance<FirNamedFunction>().map(FirShape::of) }
            })
        assertTrue(result.compiled, result.messages)
        return out
    }

    @Test
    fun `the FIR shapes of the supported construct fixtures match the golden file`() {
        val actual =
            ClientConstructs.all.joinToString("") { c ->
                "## ${c.row} ${c.title}\n" + shapes(ClientFixture.client(c.supported.body, c.supported.helpers)).joinToString("") { "$it\n" }
            }
        val expected = FirShapeTest::class.java.getResource("/client/fir-shapes.txt")?.readText()
        if (expected != actual) File("build/golden-actual/client/fir-shapes.txt").apply { parentFile.mkdirs() }.writeText(actual)
        assertEquals(expected, actual, "FIR shapes differ from client/fir-shapes.txt; actual text: build/golden-actual/client/fir-shapes.txt")
    }

    @Test
    fun `every rejected construct fixture compiles`() {
        for (c in ClientConstructs.all) assertTrue(shapes(ClientFixture.client(c.rejected.body, c.rejected.helpers)).isNotEmpty(), "row ${c.row}")
    }
}
