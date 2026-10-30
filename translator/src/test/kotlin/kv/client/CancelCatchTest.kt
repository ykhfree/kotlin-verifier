package kv.client

import kv.emit.emitClient
import kv.kvir.Module
import kv.kvir.Status
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import kv.kvir.printModule
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The position column of a printed KVIR line. */
private val POSITION = Regex("""\s+@\d+:\d+( fake=\S+)?$""")

/**
 * DESIGN-E5 6.2 S2: the kinds a catch takes, with and without CANCEL (2.1). A module uses CANCEL when a trusted
 * declaration throws it (the suspend fixture: its SuspendLock calls do). Then Throwable, Exception and RuntimeException
 * take a cancellation too, and a CancellationException catch takes only CANCEL. Without CANCEL a CancellationException
 * is one of the RTEs, so its catch is unsupported. A catch of a class the module throws takes that class's kinds,
 * unless an RTE or CHK of the try body reaches it.
 */
class CancelCatchTest {
    @TempDir
    lateinit var dir: File

    private var n = 0

    /** The module of [body] and [helpers]; with [cancel], the suspend client and sidecar, which use CANCEL. */
    private fun lower(
        body: String,
        cancel: Boolean,
        helpers: String = "",
    ): Module {
        val head = if (cancel) ClientFixture.SUSPEND_SIDECAR_HEAD else ClientFixture.SIDECAR_HEAD
        val o = ClientFixture.resolve(File(dir, "m${n++}").apply { mkdirs() }, ClientFixture.client(body, helpers, suspend = cancel), ClientFixture.sidecar(head = head))
        assertNull(o.compileMessages, o.compileMessages)
        assertEquals(emptyList(), o.errors)
        val m = o.module ?: fail("no module")
        assertEquals(emptyList(), checkWellFormed(m))
        return m
    }

    private fun lines(m: Module) = printModule(m).lines().map { it.replace(POSITION, "").trim() }

    private fun unsupported(m: Module) = (m.entries.map { it.status } + m.tasks.map { it.status }).filterIsInstance<Status.Unsupported>().flatMap { it.diagnostics }.map { it.message }

    /** The catch line of `try { config.get(key) } catch (e: TYPE) { throw ClientError(GET, e) }`, or the UNSUPPORTED messages. */
    private fun catchOf(
        type: String,
        cancel: Boolean,
    ): String {
        val m =
            lower(
                """
                val cur =
                    try {
                        config.get.invoke(key)
                    } catch (e: $type) {
                        throw ClientError(Code.GET, e)
                    }
                if (cur != null) return cur
                return Data(value = callable.call(), ttl = 0L)
                """,
                cancel,
            )
        return if (m.incomplete) unsupported(m).joinToString("\n") else lines(m).single { it.startsWith("catch ") }
    }

    @Test
    fun `Throwable, Exception and RuntimeException take CANCEL only in a module that uses it`() {
        assertEquals("catch Throwable: REE, RTE, CHK, INTR, ERR, ClientError(GET)", catchOf("Throwable", cancel = false))
        assertEquals("catch Throwable: REE, RTE, CHK, INTR, CANCEL, ERR, ClientError(GET)", catchOf("Throwable", cancel = true))
        assertEquals("catch Exception: REE, RTE, CHK, INTR, ClientError(GET)", catchOf("Exception", cancel = false))
        assertEquals("catch Exception: REE, RTE, CHK, INTR, CANCEL, ClientError(GET)", catchOf("Exception", cancel = true))
        assertEquals("catch RuntimeException: REE, RTE, ClientError(GET)", catchOf("RuntimeException", cancel = false))
        assertEquals("catch RuntimeException: REE, RTE, CANCEL, ClientError(GET)", catchOf("RuntimeException", cancel = true))
    }

    @Test
    fun `a CancellationException catch takes CANCEL, and without CANCEL it is unsupported`() {
        // kotlin.coroutines.cancellation.CancellationException is an alias of the JDK class, as kotlinx's is.
        val type = "kotlin.coroutines.cancellation.CancellationException"
        assertEquals("catch CancellationException: CANCEL", catchOf(type, cancel = true))
        val without = catchOf(type, cancel = false)
        assertTrue("UNSUPPORTED catch (e: java.util.concurrent.CancellationException)" in without, without)
        // A type the model never maps stays unsupported with CANCEL too.
        assertTrue("UNSUPPORTED catch (e: java.lang.IllegalStateException)" in catchOf("IllegalStateException", cancel = true))
    }

    /** `read` turns every exception of the cache read into ClientError(GET). */
    private val read =
        """
        private fun read(key: String): Data<T>? =
            try {
                config.get.invoke(key)
            } catch (e: Exception) {
                throw ClientError(Code.GET, e)
            }
        """

    @Test
    fun `a catch of a class the module throws takes its kinds when no RTE or CHK reaches it`() {
        for (cancel in listOf(false, true)) {
            val m =
                lower(
                    """
                    val cur =
                        try {
                            read(key)
                        } catch (e: ClientError) {
                            throw ClientError(Code.SUP, e)
                        }
                    if (cur != null) return cur
                    return Data(value = callable.call(), ttl = 0L)
                    """,
                    cancel,
                    read,
                )
            assertFalse(m.incomplete, unsupported(m).joinToString("\n"))
            // The module throws ClientError with SUP (in run) and GET (in read): the catch takes both.
            assertEquals("catch ClientError: ClientError(SUP), ClientError(GET)", lines(m).single { it.startsWith("catch ClientError") }, "cancel $cancel")
        }
    }

    @Test
    fun `a catch of a class the module throws is unsupported when an RTE or CHK of the try body reaches it`() {
        val m =
            lower(
                """
                val cur =
                    try {
                        config.get.invoke(key)
                    } catch (e: ClientError) {
                        throw ClientError(Code.SUP, e)
                    }
                if (cur != null) return cur
                return Data(value = callable.call(), ttl = 0L)
                """,
                cancel = false,
            )
        assertTrue(m.incomplete)
        assertTrue(unsupported(m).single().let { "catch (e: fx.ClientError)" in it && "may be a ClientError too" in it }, unsupported(m).toString())
        // An earlier catch that takes the RTE and the CHK leaves only the module's kinds for it.
        val earlier =
            lower(
                """
                val cur =
                    try {
                        config.get.invoke(key)
                    } catch (e: RuntimeException) {
                        throw ClientError(Code.GET, e)
                    } catch (e: Exception) {
                        throw ClientError(Code.SET, e)
                    } catch (e: ClientError) {
                        throw ClientError(Code.SUP, e)
                    }
                if (cur != null) return cur
                return Data(value = callable.call(), ttl = 0L)
                """,
                cancel = false,
            )
        assertFalse(earlier.incomplete, unsupported(earlier).joinToString("\n"))
    }

    @Test
    fun `an is-test of CancellationException on the caught exception is CANCEL, and without CANCEL it is unsupported`() {
        val body =
            """
            val cur =
                try {
                    config.get.invoke(key)
                } catch (e: Exception) {
                    if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                    throw ClientError(Code.GET, e)
                }
            if (cur != null) return cur
            return Data(value = callable.call(), ttl = 0L)
            """
        assertTrue("if caught is {CANCEL}" in lines(lower(body, cancel = true)))
        val without = lower(body, cancel = false)
        assertTrue(unsupported(without).single().let { "the model cannot tell which kinds java.util.concurrent.CancellationException takes" in it }, unsupported(without).toString())
    }

    @Test
    fun `a rethrow of an outer catch's exception inside an inner catch is unsupported, and the inner one's rethrow is not`() {
        // The emitter rethrows the kind of the innermost catch, so `throw e` there would rethrow f's kind.
        fun body(rethrown: String) =
            """
            val cur =
                try {
                    config.get.invoke(key)
                } catch (e: Exception) {
                    try {
                        config.set.invoke(key, Data(value = callable.call(), ttl = 0L), 0L)
                    } catch (f: RuntimeException) {
                        throw $rethrown
                    }
                    throw ClientError(Code.GET, e)
                }
            if (cur != null) return cur
            return Data(value = callable.call(), ttl = 0L)
            """
        val outer = lower(body("e"), cancel = false)
        assertTrue(outer.incomplete)
        assertTrue(unsupported(outer).single().let { "UNSUPPORTED throw e" in it && "a rethrow of the exception of an outer catch" in it }, unsupported(outer).toString())
        val inner = lower(body("f"), cancel = false)
        assertFalse(inner.incomplete, unsupported(inner).joinToString("\n"))
        assertTrue("rethrow" in lines(inner), printModule(inner))
    }

    @Test
    fun `the emitted kinds have CANCEL before ERR, and IS_EXCEPTION ends at it, only in a module that uses CANCEL`() {
        val body = "return Data(value = callable.call(), ttl = 0L)"
        fun kinds(cancel: Boolean): List<String> {
            val o = ClientFixture.resolve(File(dir, "e${n++}").apply { mkdirs() }, ClientFixture.client(body, suspend = cancel), ClientFixture.sidecar(head = if (cancel) ClientFixture.SUSPEND_SIDECAR_HEAD else ClientFixture.SIDECAR_HEAD))
            val text = emitClient(o.module!!, o.sidecar!!).text
            return text.lines().map { it.substringBefore("//").trim() }.filter { it.startsWith("define ") && (it.contains(" <= e") || Regex("""^define [A-Z]+ \d+$""").matches(it)) }
        }
        assertEquals(listOf("define NONE 0", "define REE 1", "define RTE 2", "define CHK 3", "define INTR 4", "define ERR 5", "define IS_EXCEPTION(e) (REE <= e && e <= INTR)"), kinds(cancel = false).filter { "CREATE" !in it && "UPDATE" !in it && "BOTH" !in it })
        assertEquals(listOf("define NONE 0", "define REE 1", "define RTE 2", "define CHK 3", "define INTR 4", "define CANCEL 5", "define ERR 6", "define IS_EXCEPTION(e) (REE <= e && e <= CANCEL)"), kinds(cancel = true).filter { "CREATE" !in it && "UPDATE" !in it && "BOTH" !in it })
    }
}
