package kv.lower

import kv.Harness
import kv.ReqShield
import kv.emit.emitViper
import kv.frontend.Frontend
import kv.kvir.Action
import kv.kvir.DiagnosticKind
import kv.kvir.GhostKind
import kv.kvir.GhostSchema
import kv.kvir.Module
import kv.kvir.Status
import kv.kvir.Unsupported
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import kv.kvir.printOperation
import kv.kvir.walk
import kv.sidecar.checkSidecar
import kv.sidecar.parseSidecar
import kv.verify.ModuleVerdict
import kv.verify.SiliconOutcome
import kv.verify.buildReport
import kv.verify.render
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A small KeyLocalLock-like module for construct fixtures: class `fixture.Lock` with a companion that holds the map
 * and the counter, and operation `run`, whose compute lambda is [body] (parameters `_` and `e`). The other arguments
 * add code around it; [run] replaces the whole function `run`. The default sidecar names `now` and `timeout` as free
 * values; [RESULT_OPERATION] also makes the cell `result` the operation result.
 */
data class LockFixture(
    val body: String,
    val before: String = "",
    val after: String = "",
    val companion: String = "",
    val members: String = "",
    val topLevel: String = "",
    val info: String = DEFAULT_INFO,
    val operations: String = DEFAULT_OPERATION,
    /** More source files of package `fixture`, by file name. */
    val otherFiles: Map<String, String> = emptyMap(),
    /** The whole function `run` (indented 4 spaces); null for the default one built from [before], [body], [after]. */
    val run: String? = null,
) {
    private val defaultRun: String =
        """
        |    fun run(key: String, now: Long): String? {
        |        val result = AtomicReference<String?>(null)
        |$before
        |        lockMap.compute(key) { _, e ->
        |$body
        |        }
        |$after
        |        return result.get()
        |    }
        """.trimMargin()

    val kotlin: String =
        """
        |package fixture
        |
        |import java.util.concurrent.ConcurrentHashMap
        |import java.util.concurrent.Semaphore
        |import java.util.concurrent.atomic.AtomicBoolean
        |import java.util.concurrent.atomic.AtomicLong
        |import java.util.concurrent.atomic.AtomicReference
        |
        |$topLevel
        |
        |class Lock(private val timeout: Long) {
        |    private class Info(
        |$info
        |    )
        |
        |    companion object {
        |        private val lockMap = ConcurrentHashMap<String, Info>()
        |        private val counter = AtomicLong()
        |$companion
        |    }
        |
        |${run ?: defaultRun}
        |$members
        |}
        |
        """.trimMargin()

    val sidecar: String =
        """
        |module fixture.Lock.Companion
        |source "Lock.kt"
        |type Key   = opaque
        |type Token = template "local-${'$'}{Long}"
        |state m:   Map<Key, Info> = lockMap
        |state seq: Counter        = counter
        |class Info = Lock.Info {
        |  permits:   Semaphore     = semaphore
        |  isHeld:    AtomicBoolean = isHeld
        |  token:     Token?        = token
        |  expiresAt: Long          = expiresAt
        |}
        |use jdk.ConcurrentHashMap.compute
        |use jdk.ConcurrentHashMap.mappingCount
        |use jdk.ConcurrentHashMap.racyRead
        |use jdk.Semaphore
        |use jdk.AtomicBoolean
        |use jdk.AtomicLong
        |use jdk.AtomicCell
        |use jdk.System.currentTimeMillis
        |use slf4j.Logger
        |$operations
        |hole MI_INV ""${'"'} true ""${'"'}
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

    /**
     * Compiles the fixture under [dir] and lowers it. The sidecar must pass its checks and resolve. An incomplete
     * module also goes through the rest of the pipeline ([assertIncompleteThroughPipeline]).
     */
    fun lower(dir: File): Module {
        val sidecarFile = File(dir, "Lock.kv").apply { writeText(sidecar) }
        val parsed = parseSidecar(sidecarFile.path, sidecar)
        assertEquals(emptyList(), checkSidecar(parsed), "fixture sidecar")
        val sources = (mapOf("Lock.kt" to kotlin) + otherFiles).map { (name, text) -> File(dir, name).apply { writeText(text) } }
        val result = Frontend.run(sources, listOf(Harness.stdlib) + ReqShield.classpath, emptyList(), parsed)
        if (!result.compiled) fail("fixture did not compile:\n${result.messages}\n$kotlin")
        assertEquals(emptyList(), result.sidecarErrors, "fixture sidecar resolves")
        val m = result.module ?: fail("no module")
        if (m.incomplete) assertIncompleteThroughPipeline(m, sidecarFile.path)
        return m
    }

    /**
     * The pipeline after lowering, as `kv verify` runs it: the ghost binder, W1-W14 on the bound module, the emitter,
     * and the report of a clean Silicon run. The report must say INCOMPLETE, never ERROR. The emitter needs a ghost
     * record and a witness, so a fixture sidecar without them gets [MINIMAL_GHOST] here; the lowering does not read
     * the ghost record, so the module only takes its schema.
     */
    private fun assertIncompleteThroughPipeline(
        m: Module,
        sidecarPath: String,
    ) {
        val withGhost = "ghost record" in sidecar
        val s = parseSidecar(sidecarPath, if (withGhost) sidecar else sidecar + MINIMAL_GHOST)
        val module = if (withGhost) m else m.copy(ghost = GhostSchema("tok", mapOf("gIssued" to GhostKind.SET_ONCE, "gHeld" to GhostKind.ENDS)))
        val bound = bindGhosts(module, s)
        assertEquals(emptyList(), checkWellFormed(bound), "W1-W14 after the ghost binder\n${printOperation(bound, "run")}")
        val report = buildReport(bound, s, emitViper(bound, s), SiliconOutcome.Verified, null)
        assertEquals(ModuleVerdict.INCOMPLETE, report.verdict, report.render())
    }

    companion object {
        const val DEFAULT_INFO =
            """        val semaphore: Semaphore,
        @Volatile var expiresAt: Long,
        val isHeld: AtomicBoolean = AtomicBoolean(false),
        @Volatile var token: String? = null,"""

        const val DEFAULT_OPERATION =
            """operation run = fun Lock.run {
  free now     = param now
  free timeout = this.timeout
}"""

        /** The ghost record and witness [lower] adds to a sidecar without them, so that the emitter can run. */
        const val MINIMAL_GHOST = "ghost record tok(Token) {\n  gIssued: Bool set-once\n  gHeld: Bool ends\n}\nwitness Info.token\n"

        /** The default operation with the result cell `result` as its result `res`. */
        const val RESULT_OPERATION =
            """operation run = fun Lock.run {
  free now     = param now
  free timeout = this.timeout
  result res
}"""
    }
}

/** The action of operation `run`. */
val Module.run: Action get() = operations.single { it.name == "run" }.action

/** The printed operation `run`, for readable assertions on the body. */
val Module.runText: String get() = printOperation(this, "run")

/** The body lines of the printed action in [text], without positions and common indentation. */
fun shape(text: String): List<String> {
    val body = text.lines().dropWhile { it.trim() != "body" }.drop(1).takeWhile { !it.trim().startsWith("commit") }
    val lines = body.map { it.replace(Regex("""\s+@\d+:\d+.*$"""), "").trimEnd() }
    val indent = lines.filter { it.isNotBlank() }.minOf { it.length - it.trimStart().length }
    return lines.map { it.drop(indent) }
}

/**
 * Asserts that `run` is supported, that W1-W14 hold, and that the module is complete unless [complete] is false
 * (another part of the module is unsupported). Returns the printed operation.
 */
fun assertSupported(
    m: Module,
    complete: Boolean = true,
): String {
    val text = m.runText
    assertEquals(Status.Supported, m.run.status, text)
    assertEquals(emptyList(), checkWellFormed(m), text)
    assertEquals(complete, !m.incomplete, "module complete\n$text\n${m.ledger.filter { it.kind == kv.kvir.DispositionKind.UNSUPPORTED }}\n${m.diagnostics}")
    return text
}

/**
 * Asserts that `run` is unsupported with an Unsupported statement whose construct name contains [what], at the
 * Kotlin [position], with a matching UNSUPPORTED diagnostic; that the module is INCOMPLETE; and that the unsupported
 * KVIR still passes W1-W14. Returns the statement.
 */
fun assertUnsupported(
    m: Module,
    what: String,
    position: Pair<Int, Int>,
): Unsupported {
    val text = m.runText
    val status = m.run.status as? Status.Unsupported ?: fail("run is supported:\n$text")
    val u = m.run.body.walk().filterIsInstance<Unsupported>().firstOrNull { what in it.what } ?: fail("no unsupported '$what' in:\n$text")
    assertEquals(position, u.pos.line to u.pos.col, text)
    assertTrue(status.diagnostics.any { it.kind == DiagnosticKind.UNSUPPORTED && it.pos == u.pos && what in it.message }, text)
    assertTrue(m.incomplete, "an unsupported action makes the module INCOMPLETE")
    assertEquals(emptyList(), checkWellFormed(m), text)
    return u
}
