package kv.lower

import kv.Harness
import kv.ReqShield
import kv.frontend.Frontend
import kv.kvir.Captured
import kv.kvir.DispositionKind
import kv.kvir.Var
import kv.kvir.returnedValue
import kv.sidecar.parseSidecar
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reactor.Mono.fromCallable model (DESIGN-v0.md 8.3, reactor variant): decision 1, the one accepted shape of a
 * `fun` operation and the UNSUPPORTED shapes, each with its Kotlin position; decision 2, `free x = local x` binds a
 * local of the lambda that holds the action, and the reactor wrappers of a `compute in` operation are not modeled;
 * decision 4, a log call inside an action is the slf4j.Logger no-effect model. The fixtures write `Mono` with its
 * package, so the fixture file needs no import.
 */
class FromCallableTest {
    @TempDir
    lateinit var dir: File

    private companion object {
        const val MONO = "reactor.core.publisher.Mono"

        /** The fixture operation `run` with the cell `result` as its result, and the model in use. */
        const val OPERATION = "use reactor.Mono.fromCallable\n" + LockFixture.RESULT_OPERATION

        /** The body of an accepted fromCallable lambda: the cell, the compute call, then [last]; [before] goes first. */
        fun lambda(
            last: String = "result.get()",
            before: String = "",
        ): String =
            """
            |            val result = AtomicReference<String?>(null)
            |$before
            |            lockMap.compute(key) { _, e ->
            |                if (e != null) result.set(e.token)
            |                e
            |            }
            |            $last
            """.trimMargin()

        /** `fun run` whose expression body is `Mono.fromCallable { [lambda] }`. */
        fun fromCallable(lambda: String = lambda()) = "    fun run(key: String, now: Long) =\n        $MONO.fromCallable {\n$lambda\n        }"
    }

    private fun LockFixture.line(fragment: String) = at(fragment).first

    private fun LockFixture.result() = "result__${line("val result")}"

    // ---- decision 1: the accepted shape ----

    @Test
    fun `an expression body Mono_fromCallable is the operation body, and its last expression is the result`() {
        val f = LockFixture("", run = fromCallable(), operations = OPERATION)
        val m = f.lower(dir)
        val text = assertSupported(m)
        val op = m.operations.single { it.name == "run" }
        assertEquals(f.at("result.get()"), op.returnedValue!!.pos.line to op.returnedValue!!.pos.col, text)
        assertEquals(f.result(), (op.returnedValue as Var).local.name, text)
        assertEquals(listOf(f.result()), op.cells.map { it.local.name }, text)
        // The ledger: the cell and its read as the last expression; the free key and the named parameter now; the
        // rest of fun run, the fromCallable call included, is the frame of the operation (not modeled).
        val constructs = m.ledger.filter { it.pos.line >= f.line("fun run") }.map { it.kind to it.what }.distinct()
        assertEquals(
            listOf(
                DispositionKind.NOT_MODELED to "fun run",
                DispositionKind.CELL to "val result = AtomicReference<String?>(null)",
                DispositionKind.FREE_SOURCE to "param key",
                DispositionKind.FREE_SOURCE to "param now",
                DispositionKind.FREE_SOURCE to "key of run#${f.line("lockMap.compute")}: key",
                DispositionKind.CELL to "result.get()",
            ).sortedBy { it.toString() },
            constructs.sortedBy { it.toString() },
            m.ledger.joinToString("\n"),
        )
    }

    @Test
    fun `a single return of Mono_fromCallable is the same shape`() {
        val run = "    fun run(key: String, now: Long): $MONO<String?> {\n        return $MONO.fromCallable {\n${lambda()}\n        }\n    }"
        val f = LockFixture("", run = run, operations = OPERATION)
        val op = f.lower(dir).also { assertSupported(it) }.operations.single { it.name == "run" }
        assertEquals(f.at("result.get()"), op.returnedValue!!.pos.line to op.returnedValue!!.pos.col)
    }

    // ---- decision 1: rejected shapes, each UNSUPPORTED at its Kotlin position ----

    @Test
    fun `a chained call on the Mono is unsupported at the operator`() {
        for ((call, name) in listOf("map { it }" to "map", "subscribeOn(reactor.core.scheduler.Schedulers.single())" to "subscribeOn")) {
            val f = LockFixture("", run = fromCallable() + ".$call", operations = OPERATION)
            assertUnsupported(f.lower(File(dir, name).apply { mkdirs() }), "chained call $name on Mono.fromCallable", f.at(call))
        }
    }

    @Test
    fun `a second Mono_fromCallable in the operation function is unsupported`() {
        val f = LockFixture("", run = fromCallable(lambda(before = "            val warm = $MONO.fromCallable { 1L }")), operations = OPERATION)
        assertUnsupported(f.lower(dir), "second Mono.fromCallable", f.at(MONO, after = "val warm"))
    }

    @Test
    fun `a nested Mono_fromCallable around the compute call is unsupported`() {
        val inner = "            val inner = $MONO.fromCallable {\n${lambda().prependIndent("    ")}\n            }\n            inner.block()"
        val f = LockFixture("", run = fromCallable(inner), operations = OPERATION)
        assertUnsupported(f.lower(dir), "nested Mono.fromCallable", f.at(MONO, after = "val inner"))
    }

    @Test
    fun `a compute call inside Mono_defer is unsupported at the factory`() {
        val run = "    fun run(key: String, now: Long) =\n        $MONO.defer {\n${lambda(last = "$MONO.justOrEmpty(result.get())")}\n        }"
        val f = LockFixture("", run = run, operations = OPERATION)
        assertUnsupported(f.lower(dir), "compute inside the lambda of Mono.defer", f.at("$MONO.defer"))
    }

    @Test
    fun `a compute call inside Mono_create is unsupported at the factory`() {
        val run = "    fun run(key: String, now: Long) =\n        $MONO.create<String> { sink ->\n${lambda(last = "sink.success(result.get())")}\n        }"
        val f = LockFixture("", run = run, operations = OPERATION)
        assertUnsupported(f.lower(dir), "compute inside the lambda of Mono.create", f.at("$MONO.create"))
    }

    @Test
    fun `a Mono_fromCallable whose lambda holds no compute call is unsupported`() {
        val run =
            """
            |    fun run(key: String, now: Long): $MONO<String?> {
            |        val result = AtomicReference<String?>(null)
            |        lockMap.compute(key) { _, e ->
            |            if (e != null) result.set(e.token)
            |            e
            |        }
            |        return $MONO.fromCallable { result.get() }
            |    }
            """.trimMargin()
        val f = LockFixture("", run = run, operations = OPERATION)
        assertUnsupported(f.lower(dir), "Mono.fromCallable without the action", f.at("$MONO.fromCallable"))
    }

    @Test
    fun `a Mono_fromCallable that is not the whole function body is unsupported`() {
        val run = "    fun run(key: String, now: Long): $MONO<String?> {\n        val mono = $MONO.fromCallable {\n${lambda()}\n        }\n        return mono\n    }"
        val f = LockFixture("", run = run, operations = OPERATION)
        assertUnsupported(f.lower(dir), "Mono.fromCallable that is not the whole body", f.at("$MONO.fromCallable"))
    }

    @Test
    fun `the fromCallable shape needs the sidecar to use the model`() {
        val f = LockFixture("", run = fromCallable(), operations = LockFixture.RESULT_OPERATION)
        val u = assertUnsupported(f.lower(dir), "trusted model reactor.Mono.fromCallable", f.at("$MONO.fromCallable"))
        assertTrue("does not `use`" in u.reason, u.reason)
    }

    @Test
    fun `a Mono_fromCallable inside the action is unsupported`() {
        val f = LockFixture("$MONO.fromCallable { 1L }\ne", operations = OPERATION)
        assertUnsupported(f.lower(dir), "reactor.Mono.fromCallable inside an action", f.at(MONO))
    }

    @Test
    fun `a compute call inside another lambda of a fun operation is unsupported`() {
        // Without the shape check, one call of run would run the action once per element.
        val f = LockFixture("", run = "    fun run(key: String, now: Long) {\n        listOf(key).forEach { k ->\n            lockMap.compute(k) { _, e -> e }\n        }\n    }")
        assertUnsupported(f.lower(dir), "compute inside the lambda of forEach", f.at("listOf(key).forEach"))
    }

    @Test
    fun `the lambda's last expression reads only cells and constants`() {
        val f = LockFixture("", run = fromCallable(lambda(last = "key.trim()")), operations = OPERATION)
        val u = assertUnsupported(f.lower(dir), "operation result", f.at("key.trim()"))
        assertTrue("(W10); key.trim() is neither" in u.reason, u.reason)
    }

    @Test
    fun `an early return from the fromCallable lambda is a second return`() {
        val f = LockFixture("", run = fromCallable(lambda(before = "            if (now < 0) return@fromCallable null")), operations = OPERATION)
        val u = assertUnsupported(f.lower(dir), "operation result", f.at("{", after = "$MONO.fromCallable"))
        assertTrue("exactly one return; the Mono.fromCallable lambda of run has 2" in u.reason, u.reason)
    }

    // ---- decision 2: free values ----

    private val localNow = "use reactor.Mono.fromCallable\noperation run = fun Lock.run {\n  free now = local now\n  result res\n}"

    /** A fromCallable `run` whose lambda declares `now`; [visible] false keeps it in a block the compute call is not in. */
    private fun localNowRun(visible: Boolean): String {
        val now = if (visible) "            val now = System.currentTimeMillis()" else "            if (key.isEmpty()) {\n                val now = 0L\n            }"
        val shadow = if (visible) "            if (key.isEmpty()) {\n                val now = 0L\n            }\n" else ""
        // The compute call reads now only where a now is visible; else the fixture would not compile.
        val write = if (visible) "e.expiresAt = now; " else ""
        return "    fun run(key: String) =\n        $MONO.fromCallable {\n            val result = AtomicReference<String?>(null)\n$shadow$now\n" +
            "            lockMap.compute(key) { _, e ->\n                if (e != null) { ${write}result.set(e.token) }\n                e\n            }\n            result.get()\n        }"
    }

    @Test
    fun `free local binds the local of the fromCallable lambda that the compute call sees`() {
        val f = LockFixture("", run = localNowRun(visible = true), operations = localNow)
        val m = f.lower(dir)
        val text = assertSupported(m)
        val now = m.run.free.single { it.sidecarName == "now" }
        assertEquals(f.at("val now = System"), now.local.pos.line to now.local.pos.col, text)
        assertEquals(Captured::class, now.origin::class, text)
        assertEquals(f.at("val now = System"), now.origin.pos.line to now.origin.pos.col, text)
        assertEquals(DispositionKind.FREE_SOURCE, m.ledger.first { it.pos == now.local.pos }.kind)
    }

    @Test
    fun `free local does not bind a lambda local the compute call cannot see`() {
        val f = LockFixture("", run = localNowRun(visible = false), operations = localNow)
        val sidecar = File(dir, "Lock.kv").apply { writeText(f.sidecar) }
        val result = Frontend.run(listOf(File(dir, "Lock.kt").apply { writeText(f.kotlin) }), listOf(Harness.stdlib) + ReqShield.classpath, emptyList(), parseSidecar(sidecar.path, f.sidecar))
        assertTrue(result.compiled, result.messages)
        val e = result.sidecarErrors.single()
        assertEquals("unresolved", e.code, e.toString())
        assertTrue("no local 'now' is visible at the compute call at ${f.line("lockMap.compute")}:" in e.message, e.message)
    }

    /**
     * A sweep in the shape of the reactor monitor: `compute in Lock.Companion`, inside Flux.interval, flatMap,
     * Mono.fromRunnable, forEach, onErrorResume and subscribe, with `free now = local now`. If [outerNow], fun sweep
     * also declares a `now` that the fromRunnable lambda shadows.
     */
    private fun sweepFixture(outerNow: Boolean): LockFixture {
        val sweep =
            """
            |        fun sweep() {
            |${if (outerNow) "            val now = 0L" else ""}
            |            reactor.core.publisher.Flux.interval(java.time.Duration.ofMillis(10))
            |                .flatMap({
            |                    $MONO.fromRunnable<Unit> {
            |                        val now = System.currentTimeMillis()
            |                        lockMap.keys.forEach { key ->
            |                            lockMap.compute(key) { _, info ->
            |                                if (info == null) return@compute null
            |                                if (now > info.expiresAt) null else info
            |                            }
            |                        }
            |                    }.onErrorResume { $MONO.empty() }
            |                }, 1).subscribe()
            |        }
            """.trimMargin()
        val ops = LockFixture.DEFAULT_OPERATION + "\noperation sweep = compute in Lock.Companion {\n  free now = local now\n}"
        return LockFixture("e", companion = sweep, operations = ops)
    }

    @Test
    fun `a sweep inside reactor wrappers binds the fromRunnable local, and the wrappers are not modeled`() {
        val f = sweepFixture(outerNow = false)
        val m = f.lower(dir)
        assertSupported(m)
        val op = m.operations.single { it.name == "sweep" }
        val now = op.action.free.single { it.sidecarName == "now" }
        assertEquals(f.at("val now = System"), now.local.pos.line to now.local.pos.col)
        // Flux.interval, flatMap, fromRunnable, onErrorResume and subscribe are part of the not-modeled fun sweep;
        // only the free value, the racy keys read and the action key are other constructs.
        val sweepLines = f.line("fun sweep()")..f.line("}, 1).subscribe()")
        val constructs = m.ledger.filter { it.pos.line in sweepLines }.map { it.kind to it.what }.distinct()
        assertEquals(
            listOf(
                DispositionKind.NOT_MODELED to "fun sweep",
                DispositionKind.FREE_SOURCE to "val now = System.currentTimeMillis()",
                DispositionKind.RACY_READ to "lockMap.keys",
                DispositionKind.FREE_SOURCE to "param key",
                DispositionKind.FREE_SOURCE to "key of sweep#${f.line("lockMap.compute(key) { _, info")}: key",
            ).sortedBy { it.toString() },
            constructs.sortedBy { it.toString() },
            m.ledger.joinToString("\n"),
        )
    }

    @Test
    fun `free local binds the innermost now, the one the action reads, not a now outside the lambda`() {
        val f = sweepFixture(outerNow = true)
        val m = f.lower(dir)
        assertSupported(m)
        val free = m.operations.single { it.name == "sweep" }.action.free
        assertEquals(listOf("now" to f.at("val now = System")), free.map { it.sidecarName to (it.local.pos.line to it.local.pos.col) })
    }

    // ---- decision 4: a log call inside an action ----

    private val logger = "private val log = org.slf4j.LoggerFactory.getLogger(\"fixture\")"

    @Test
    fun `a log call inside an action has no effect`() {
        val f = LockFixture("if (e != null) log.debug(\"entry for {}\", key)\ne", topLevel = logger)
        val m = f.lower(dir)
        // No statement; W14 (in assertSupported) finds a log node that the action does not mark.
        assertEquals(listOf("if e__${f.line("{ _, e ->")} != null", "exit keep"), shape(assertSupported(m)))
    }

    @Test
    fun `a log argument with an effect is unsupported`() {
        val f = LockFixture("if (e != null) log.debug(\"took {}\", e.semaphore.tryAcquire())\ne", topLevel = logger)
        assertUnsupported(f.lower(dir), "call in a log argument", f.at("e.semaphore.tryAcquire()"))
    }
}
