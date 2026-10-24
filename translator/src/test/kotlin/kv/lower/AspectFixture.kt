package kv.lower

import kv.Harness
import kv.frontend.Frontend
import kv.frontend.FrontendResult
import kv.kvir.Module
import kv.kvir.Operation
import kv.kvir.incomplete
import kv.kvir.printOperation
import kv.sidecar.Sidecar
import kv.sidecar.checkSidecar
import kv.sidecar.parseSidecar
import kv.sidecar.reviewFingerprint
import kv.verify.Report
import kv.verify.Silicon
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * A small Spring-aspect-like instance module for DESIGN-B.md fixtures: class `fixture.Aspect` holds the internal map
 * `shieldMap` (Method keys, `Shield` values), the trusted fresh factory `create` (it may throw: `require`) and
 * operation `getOrCreate`, whose whole function is [op] (indented 4 spaces). [members] adds members to the class,
 * [topLevel] top-level declarations, [otherFiles] more files of package `fixture` (audited with the module file,
 * because the map is internal). `shared` is a Shield the aspect gets from outside, so that no constructor outside
 * `create` makes one (DESIGN-B.md 3.3 rule 4). The default sidecar is the aspect sidecar of DESIGN-B.md 6.1 with a
 * trivial hole.
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
    /** The sidecar's state properties. */
    val invariants: String = DEFAULT_INVARIANTS,
    /** The text of `hole MI_INV`. */
    val hole: String = "true",
    /** More jars on the compile classpath, e.g. slf4j for a logger. */
    val classpath: List<File> = emptyList(),
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
        |class Aspect(private val prefix: String, private val shared: Shield) {
        |    $mapDecl
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

    /**
     * [trusted] with the fingerprint of [factory] in its `reviewed` note (B6), unless the note has one: fixtures
     * review their factory as written. A factory starts at its first modifier and has no KDoc or annotation.
     */
    private val stampedTrusted: String
        get() {
            if ("sha256" in trusted) return trusted
            val sha = reviewFingerprint(factory)
            return trusted.replace(Regex("""(\n\s*reviewed "[^"]*")"""), "$1 sha256 \"$sha\"")
        }

    val sidecar: String =
        """
        |module fixture.Aspect
        |source "Aspect.kt"
        |type MethodKey = opaque java.lang.reflect.Method
        |state m: Map<MethodKey, Shield> = shieldMap
        |$setOnce
        |class Shield = Shield { }
        |$uses
        |$stampedTrusted
        |$operations
        |$invariants
        |hole MI_INV ""${'"'} $hole ""${'"'}
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
    fun compile(dir: File): FrontendResult = compiled(dir).first

    private fun compiled(dir: File): Pair<FrontendResult, Sidecar> {
        dir.mkdirs()
        val sidecarFile = File(dir, "Aspect.kv").apply { writeText(sidecar) }
        val parsed = parseSidecar(sidecarFile.path, sidecar)
        assertEquals(emptyList(), checkSidecar(parsed), "fixture sidecar")
        val sources = (mapOf("Aspect.kt" to kotlin) + otherFiles).map { (name, text) -> File(dir, name).apply { writeText(text) } }
        val result = Frontend.run(sources, listOf(Harness.stdlib) + classpath, emptyList(), parsed)
        if (!result.compiled) fail("fixture did not compile:\n${result.messages}\n$kotlin")
        return result to parsed
    }

    /** Compiles and lowers the fixture; the sidecar must resolve. */
    fun lower(dir: File): Module = lowered(dir).first

    /** The lowered module and its sidecar; the sidecar must resolve. */
    fun lowered(dir: File): Pair<Module, Sidecar> {
        val (result, sidecar) = compiled(dir)
        assertEquals(emptyList(), result.sidecarErrors, "fixture sidecar resolves")
        return (result.module ?: fail("no module")) to sidecar
    }

    /** Lowers the fixture, emits it to [dir]/Aspect.vpr and verifies it with Silicon (DESIGN-v0.md 5.6-5.8). */
    fun verify(dir: File): Report = lowered(dir).let { (m, s) -> kv.verify.verify(m, s, File(dir, "Aspect.vpr"), Silicon.defaultHome()) }

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

        const val DEFAULT_INVARIANTS =
            """invariant P_once: forall qr1: Ref, qr2: Ref :: { qr1 in S.created, qr2 in S.created }
    qr1 in S.created && qr2 in S.created && qr1.gFor == qr2.gFor ==> qr1 == qr2"""

        /** The hole of the aspect sidecars (DESIGN-B.md 6.1): every created object is the value mapped at its key. */
        const val CREATED_ARE_MAPPED = "(forall qr: Ref :: { qr in S.created } qr in S.created ==> has(S, qr.gFor) && S.m[qr.gFor] == qr)"

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

/** The printed section of `getOrCreate` (its `body` lines before the action), without positions and common indentation. */
val Module.sectionShape: List<String>
    get() {
        val lines = getOrCreateText.lines().dropWhile { it.trim() != "body" }.drop(1).takeWhile { !it.trimStart().startsWith("action ") }
        return unindent(lines)
    }

/** The printed body of `getOrCreate`'s action, without positions and common indentation; empty for a call without a lambda. */
val Module.actionShape: List<String>
    get() {
        val text = getOrCreateText.lines().dropWhile { !it.trimStart().startsWith("action ") }
        return unindent(text.dropWhile { it.trim() != "body" }.drop(1).takeWhile { !it.trim().startsWith("commit") })
    }

private fun unindent(lines: List<String>): List<String> {
    val stripped = lines.map { it.replace(Regex("""\s+@[\w.]*:?\d+:\d+.*$"""), "").trimEnd() }.filter { it.isNotBlank() }
    val indent = stripped.minOfOrNull { it.length - it.trimStart().length } ?: 0
    return stripped.map { it.drop(indent) }
}

/** Asserts that `getOrCreate` is supported, that W1-W16 hold and that the module is complete; returns the printed operation. */
fun assertSupportedOp(m: Module): String {
    val text = m.getOrCreateText
    assertEquals(kv.kvir.Status.Supported, m.getOrCreate.action.status, text)
    assertEquals(emptyList(), kv.kvir.checkWellFormed(m), text)
    assertEquals(emptyList(), m.ledger.filter { it.kind == kv.kvir.DispositionKind.UNSUPPORTED } + m.diagnostics.filter { it.kind == kv.kvir.DiagnosticKind.UNSUPPORTED }, text)
    return text
}

/**
 * Asserts that `getOrCreate` is unsupported with an Unsupported statement whose construct name contains [what], at
 * the Kotlin [position], that the module is INCOMPLETE and that W1-W16 still hold. Returns the statement.
 */
fun assertUnsupportedOp(
    m: Module,
    what: String,
    position: Pair<Int, Int>,
): kv.kvir.Unsupported {
    val text = m.getOrCreateText
    val status = m.getOrCreate.action.status as? kv.kvir.Status.Unsupported ?: fail("getOrCreate is supported:\n$text")
    val u = m.getOrCreate.action.body.filterIsInstance<kv.kvir.Unsupported>().firstOrNull { what in it.what } ?: fail("no unsupported '$what' in:\n$text")
    assertEquals(position, u.pos.line to u.pos.col, text)
    kotlin.test.assertTrue(status.diagnostics.any { it.pos == u.pos && what in it.message }, text)
    kotlin.test.assertTrue(m.incomplete, "an unsupported action makes the module INCOMPLETE")
    assertEquals(emptyList(), kv.kvir.checkWellFormed(m), text)
    return u
}
