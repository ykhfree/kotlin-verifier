package kv.client

import kv.kvir.Module
import kv.kvir.Task
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import kv.kvir.printModule
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.fail

/** The position column of a printed KVIR line: ` @line:col`, maybe with a fake kind. */
private val POSITION = Regex("""\s+@\d+:\d+( fake=\S+)?$""")

/**
 * DESIGN-E 3.4: the precondition the lowering derives for a task. Part 1: the obligations the spawning unit created
 * over values the spawn gives the task, held if the created result is not null (E4: not the obligations the task's
 * release calls name). Part 2: captured vals with a constant value. Part 3: the conditions around the spawn over
 * captured and instance values; a conjunct over another local is dropped.
 */
class TaskPreconditionTest {
    @TempDir
    lateinit var dir: File

    /** The printed precondition lines of [t]: requires, then holds. */
    private fun pre(
        m: Module,
        t: Task,
    ): List<String> =
        printModule(m).lines().dropWhile { !it.startsWith("task ${t.id} ") }.drop(1).takeWhile { it.startsWith("  ") && !it.startsWith("  translates") }
            .map { it.replace(POSITION, "").trim() }.filter { it.startsWith("requires") || it.startsWith("holds") }

    private fun lower(
        body: String,
        helpers: String,
        items: String = "free mode = this.config.mode\nresult res",
    ): Module {
        val outcome = ClientFixture.resolve(dir, ClientFixture.client(body, helpers), ClientFixture.sidecar(items))
        assertNull(outcome.compileMessages, outcome.compileMessages)
        assertEquals(emptyList(), outcome.errors)
        val m = outcome.module ?: fail("no module")
        assertFalse(m.incomplete, printModule(m))
        assertEquals(emptyList(), checkWellFormed(m))
        return m
    }

    private val release =
        """
        private fun release(
            key: String,
            task: () -> Unit,
        ) {
            CompletableFuture.runAsync(task, config.executor)
        }
        """

    @Test
    fun `the core tasks get the precondition of spike target vpr TASK69_PRE and TASK125_PRE`() {
        val m = ClientGoldenTest.core
        assertEquals(
            listOf(
                "requires lockType__62 == LockType.UPDATE",
                "requires (mode == Mode.ONLY_CREATE_CACHE) || (token__66 != null)",
                "holds (token__66 != null) ==> Lease(k, lockType__62, token__66)",
            ),
            pre(m, m.tasks[0]),
        )
        assertEquals(
            listOf(
                "requires lockType__85 == LockType.CREATE",
                "requires (mode == Mode.ONLY_UPDATE_CACHE) || (token__89 != null)",
                "holds (token__89 != null) ==> Lease(k, lockType__85, token__89)",
            ),
            pre(m, m.tasks[1]),
        )
        assertEquals(listOf("k", "lockType__62", "token__66"), m.tasks[0].params.map { it.name })
        assertEquals(listOf("k", "lockType__85", "token__89", "reqShieldData__120"), m.tasks[1].params.map { it.name })
    }

    @Test
    fun `conjuncts over captured values stay, others go, and an else branch is negated`() {
        val m =
            lower(
                """
                val data = Data(value = callable.call(), ttl = 0L)
                val kind = Kind.UPDATE
                val token = config.lock.tryLock(key, kind)
                val big = config.maxAttempt > 3
                var mark = 0
                if (token != null && mark == 0 && config.mode == Mode.BOTH) {
                    if (big) {
                        log.info("big")
                    } else {
                        mark = 1
                        release(key) {
                            config.set.invoke(key, data, 0L)
                            config.lock.unLock(key, kind, token)
                        }
                    }
                }
                return data
                """,
                release,
            )
        val t = m.tasks.single()
        assertEquals(listOf("k", "data__17", "kind__18", "token__19"), t.params.map { it.name })
        assertEquals(
            listOf(
                // Part 2: kind is a val with a constant value.
                "requires kind__18 == Kind.UPDATE",
                // Part 3: `mark == 0` is gone (mark is a var the task does not capture); the else branch of `if (big)`
                // is `!big`, with big's value over the instance value config.maxAttempt.
                "requires token__19 != null",
                "requires mode == Mode.BOTH",
                "requires !(config_maxAttempt__20 > 3)",
                // Part 1: tryLock created Lease(k, kind, token) if token is not null.
                "holds (token__19 != null) ==> Lease(k, kind__18, token__19)",
            ),
            pre(m, t),
        )
    }

    @Test
    fun `the obligation handed over is the one created, whatever the task's releases name`() {
        val m =
            lower(
                """
                val data = Data(value = callable.call(), ttl = 0L)
                val token = config.lock.tryLock(key, Kind.UPDATE)
                if (token != null) {
                    release(key) {
                        config.lock.unLock(key, Kind.CREATE, token)
                        config.lock.unLock(key, Kind.UPDATE, token)
                    }
                }
                return data
                """,
                release,
            )
        val t = m.tasks.single()
        // M6's shape (DESIGN-E E4): a release of another lock type does not add an obligation the caller never had.
        assertEquals(
            listOf("requires token__18 != null", "holds (token__18 != null) ==> Lease(k, Kind.UPDATE, token__18)"),
            pre(m, t),
            printModule(m),
        )
    }

    @Test
    fun `a task that releases nothing has no holds, and a condition over a value the task does not capture is dropped`() {
        val m =
            lower(
                """
                val data = Data(value = callable.call(), ttl = 0L)
                val token = config.lock.tryLock(key, Kind.CREATE)
                if (token == null) {
                    release(key) { config.set.invoke(key, data, 0L) }
                } else {
                    config.lock.unLock(key, Kind.CREATE, token)
                }
                return data
                """,
                release,
            )
        assertEquals(emptyList(), pre(m, m.tasks.single()), printModule(m))
    }
}
