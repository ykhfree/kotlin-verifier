package kv.lower

import kv.Harness
import kv.frontend.Frontend
import kv.frontend.FrontendResult
import kv.kvir.Module
import kv.kvir.Operation
import kv.kvir.printOperation
import kv.sidecar.checkSidecar
import kv.sidecar.parseSidecar
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * A small Spring-aspect-like instance module for DESIGN-B.md fixtures: class `fixture.Aspect` holds the internal map
 * `shieldMap` (Method keys, `Shield` values), the trusted fresh factory `create` (it may throw: `require`) and
 * operation `getOrCreate`, whose whole function is [op] (indented 4 spaces). [members] adds members to the class,
 * [topLevel] top-level declarations, [otherFiles] more files of package `fixture` (audited with the module file,
 * because the map is internal). The default sidecar is the aspect sidecar of DESIGN-B.md 6.1 with a trivial hole.
 */
data class AspectFixture(
    val op: String = DEFAULT_OP,
    val members: String = "",
    val topLevel: String = "",
    val operations: String = DEFAULT_OPERATION,
    val sidecarExtra: String = "",
    val mapDecl: String = "internal val shieldMap = ConcurrentHashMap<Method, Shield>()",
    val factory: String = DEFAULT_FACTORY,
    val otherFiles: Map<String, String> = emptyMap(),
    val trusted: String = DEFAULT_TRUSTED,
    val uses: String = DEFAULT_USES,
    val setOnce: String = "set-once P_stable: m",
    /** More import lines of the module file. */
    val imports: String = "",
) {
    val kotlin: String =
        """
        |package fixture
        |
        |import java.lang.reflect.Method
        |import java.util.concurrent.ConcurrentHashMap
        |$imports
        |
        |class Shield(val name: String)
        |
        |$topLevel
        |
        |class Aspect(private val prefix: String) {
        |    $mapDecl
        |    private val shared = Shield("shared")
        |    private var calls = 0L
        |
        |    fun target(jp: Any): Method = jp.javaClass.methods[0]
        |
        |$factory
        |
        |$op
        |$members
        |}
        |
        """.trimMargin()

    val sidecar: String =
        """
        |module fixture.Aspect
        |source "Aspect.kt"
        |type MethodKey = opaque java.lang.reflect.Method
        |state m: Map<MethodKey, Shield> = shieldMap
        |$setOnce
        |class Shield = Shield { }
        |$uses
        |$trusted
        |$operations
        |invariant P_once: forall qr1: Ref, qr2: Ref :: { qr1 in S.created, qr2 in S.created }
        |    qr1 in S.created && qr2 in S.created && qr1.gFor == qr2.gFor ==> qr1 == qr2
        |hole MI_INV ""${'"'} true ""${'"'}
        |$sidecarExtra
        |
        """.trimMargin()

    /** 1-based line and column of the first [fragment] in the Kotlin source, after the first [after] if given. */
    fun at(
        fragment: String,
        after: String? = null,
    ): Pair<Int, Int> {
        val lines = kotlin.lines()
        val start = after?.let { a -> lines.indexOfFirst { a in it }.also { check(it >= 0) { "no '$a' in fixture" } } } ?: 0
        val n = (start until lines.size).firstOrNull { fragment in lines[it] } ?: error("no '$fragment' in fixture:\n$kotlin")
        return n + 1 to lines[n].indexOf(fragment) + 1
    }

    /** 1-based line and column of [fragment] in the sidecar, within the first line that contains [line]. */
    fun sidecarAt(
        line: String,
        fragment: String = line,
    ): Pair<Int, Int> {
        val lines = sidecar.lines()
        val n = lines.indexOfFirst { line in it }.also { check(it >= 0) { "no '$line' in the fixture sidecar" } }
        return n + 1 to lines[n].indexOf(fragment, lines[n].indexOf(line)) + 1
    }

    /** Compiles the fixture under [dir] with its sidecar; the sidecar must pass its checks. */
    fun compile(dir: File): FrontendResult {
        dir.mkdirs()
        val sidecarFile = File(dir, "Aspect.kv").apply { writeText(sidecar) }
        val parsed = parseSidecar(sidecarFile.path, sidecar)
        assertEquals(emptyList(), checkSidecar(parsed), "fixture sidecar")
        val sources = (mapOf("Aspect.kt" to kotlin) + otherFiles).map { (name, text) -> File(dir, name).apply { writeText(text) } }
        val result = Frontend.run(sources, listOf(Harness.stdlib), emptyList(), parsed)
        if (!result.compiled) fail("fixture did not compile:\n${result.messages}\n$kotlin")
        return result
    }

    /** Compiles and lowers the fixture; the sidecar must resolve. */
    fun lower(dir: File): Module {
        val result = compile(dir)
        assertEquals(emptyList(), result.sidecarErrors, "fixture sidecar resolves")
        return result.module ?: fail("no module")
    }

    companion object {
        const val DEFAULT_OP = "    fun getOrCreate(jp: Any): Shield =\n        shieldMap.computeIfAbsent(target(jp)) { create(jp) }"

        const val DEFAULT_FACTORY =
            """    private fun create(jp: Any): Shield {
        require(prefix.isNotEmpty()) { "no prefix" }
        return Shield(prefix + jp)
    }"""

        const val DEFAULT_TRUSTED =
            """trusted fun Aspect.create(kotlin.Any)
  returns fresh Shield
  throws
  effects none
  reviewed "fixture: the only return is a new Shield; require runs first""""

        const val DEFAULT_USES =
            """use jdk.ConcurrentHashMap.computeIfAbsent
use jdk.ConcurrentHashMap.compute
use jdk.ConcurrentHashMap.putIfAbsent
use jdk.ConcurrentHashMap.put
use jdk.ConcurrentHashMap.remove
use jdk.ConcurrentHashMap.racyRead
use kotlin.collections.getOrPut"""

        const val DEFAULT_OPERATION =
            """operation getOrCreate = fun Aspect.getOrCreate {
  free thrown = call create
  result res
  ensures P_same: !exc ==> res != null && has(S, k) && S.m[k] == res
}"""
    }
}

/** The operation `getOrCreate`. */
val Module.getOrCreate: Operation get() = operations.single { it.name == "getOrCreate" }

/** The printed operation `getOrCreate`. */
val Module.getOrCreateText: String get() = printOperation(this, "getOrCreate")
