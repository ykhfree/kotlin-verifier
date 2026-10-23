package kv.frontend

import kv.Harness
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Compiles fixtures in-process and checks which calls discovery reports as `ConcurrentHashMap.compute` sites. */
class DiscoveryTest {
    @TempDir
    lateinit var dir: File

    /** 1-based line and column of the first occurrence of [fragment] in [text]. */
    private fun at(
        text: String,
        fragment: String,
    ): Pair<Int, Int> {
        val line = text.lines().indexOfFirst { fragment in it }
        check(line >= 0) { "no '$fragment' in fixture" }
        return line + 1 to text.lines()[line].indexOf(fragment) + 1
    }

    @Test
    fun `reports a compute call with its receiver, enclosing class and function, and lambda`() {
        val text =
            """
            package fixture

            import java.util.concurrent.ConcurrentHashMap

            class Box {
                companion object {
                    private val table = ConcurrentHashMap<String, Long>()

                    fun bump(k: String) {
                        listOf(k).forEach { key ->
                            table.compute(key) { _, old -> (old ?: 0L) + 1 }
                        }
                    }
                }
            }
            """.trimIndent()
        val sites = Harness.compile(dir, "Box.kt" to text).sites
        val site = sites.single()
        val (line, col) = at(text, "table.compute")
        assertEquals(line to col, site.pos.line to site.pos.col)
        assertTrue(site.pos.file.endsWith("Box.kt"), site.pos.file)
        assertEquals("table", site.receiver)
        // Lambdas are transparent: the innermost class is the companion, the innermost named function is bump.
        assertEquals("fixture.Box.Companion", site.inClass)
        assertEquals("bump", site.inFunction)
        assertEquals(at(text, "{ _, old"), site.lambda!!.line to site.lambda!!.col)
    }

    @Test
    fun `ignores compute on other map types and other ConcurrentHashMap methods`() {
        val text =
            """
            package fixture

            import java.util.concurrent.ConcurrentHashMap

            fun other(chm: ConcurrentHashMap<String, Int>, plain: HashMap<String, Int>, view: MutableMap<String, Int>) {
                plain.compute("a") { _, v -> v }
                view.compute("b") { _, v -> v }
                chm.computeIfAbsent("c") { 1 }
                chm.computeIfPresent("d") { _, v -> v }
                chm.merge("e", 1) { a, b -> a + b }
                chm.compute("f") { _, v -> v }
            }
            """.trimIndent()
        val sites = Harness.compile(dir, "Other.kt" to text).sites
        // Only the last call is ConcurrentHashMap.compute; the MutableMap-typed call resolves to the Kotlin interface.
        assertEquals(listOf(at(text, "chm.compute(\"f\")")), sites.map { it.pos.line to it.pos.col })
        assertNull(sites.single().inClass, "a top-level call has no enclosing class")
        assertEquals("other", sites.single().inFunction)
    }

    @Test
    fun `reports a compute call whose function argument is not a lambda literal, without a lambda`() {
        val text =
            """
            package fixture

            import java.util.concurrent.ConcurrentHashMap
            import java.util.function.BiFunction

            object Holder {
                val m = ConcurrentHashMap<String, String>()
                val keep = BiFunction<String, String?, String?> { _, v -> v }

                fun viaVariable() = m.compute("k", keep)
            }
            """.trimIndent()
        val site = Harness.compile(dir, "Holder.kt" to text).sites.single()
        assertEquals(at(text, "m.compute"), site.pos.line to site.pos.col)
        assertEquals("fixture.Holder", site.inClass)
        assertNull(site.lambda)
    }

    @Test
    fun `finds compute inherited by a subclass but not an override of it`() {
        val text =
            """
            package fixture

            import java.util.concurrent.ConcurrentHashMap
            import java.util.function.BiFunction

            class Plain : ConcurrentHashMap<String, Int>()

            class Overriding : ConcurrentHashMap<String, Int>() {
                override fun compute(key: String, f: BiFunction<in String, in Int?, out Int?>): Int? = null
            }

            fun use(p: Plain, o: Overriding) {
                p.compute("x") { _, v -> v }
                o.compute("y") { _, v -> v }
            }
            """.trimIndent()
        val sites = Harness.compile(dir, "Sub.kt" to text).sites
        assertEquals(listOf(at(text, "p.compute")), sites.map { it.pos.line to it.pos.col })
        assertFalse(sites.any { it.receiver == "o" })
    }

    @Test
    fun `reports sites in every compiled file`() {
        val a = "package fixture\n\nimport java.util.concurrent.ConcurrentHashMap\n\nval m = ConcurrentHashMap<String, Int>()\n"
        val b = "package fixture\n\nfun put(k: String) {\n    m.compute(k) { _, _ -> 1 }\n}\n"
        val sites = Harness.compile(dir, "A.kt" to a, "B.kt" to b).sites
        val site = sites.single()
        assertTrue(site.pos.file.endsWith("B.kt"), site.pos.file)
        assertEquals(4 to 5, site.pos.line to site.pos.col)
        assertEquals("m", site.receiver)
    }

    @Test
    fun `reports a compile error instead of sites`() {
        val result = Harness.compile(dir, "Bad.kt" to "package fixture\n\nfun f(): Int = \"no\"\n", mustCompile = false)
        assertFalse(result.compiled)
        assertTrue("Bad.kt" in result.messages && "error" in result.messages, result.messages)
    }
}
