package kv.client

import kv.ReqShield
import kv.frontend.Frontend
import kv.frontend.ResolveOutcome
import kv.kvir.checkWellFormed
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DESIGN-C.md 10.17: a client `free NAME = param P` names the entry's parameter P as NAME, so the sidecar text reads it.
 * Before, the parameter was declared as `P__LINE` and a clause that used NAME reached Silicon as an undeclared
 * identifier (the JG4 case C3). A parameter that no translated code reads, or the obligation key (the local k), declares
 * no NAME, so a clause that uses NAME is `free-unused` at the free line.
 */
class FreeParamTest {
    @TempDir
    lateinit var dir: File

    /** The fixture client with an Int parameter `limit` after `callable`, and [body]. */
    private fun client(body: String) =
        ClientFixture.client(body).also { check("        callable: Callable<T?>,\n    )" in it) }
            .replace("        callable: Callable<T?>,\n    )", "        callable: Callable<T?>,\n        limit: Int,\n    )")

    private val items =
        """
        free n = param limit
        result res
        ensures N: n >= 0 ==> exc == NONE
          source none "fixture"
        """

    @Test
    fun `a named parameter that the entry reads is its local with the sidecar name`() {
        val o = ClientFixture.resolve(dir, client("val big = limit > 1\nreturn Data(value = callable.call(), ttl = 0L)"), ClientFixture.sidecar(items))
        assertNull(o.compileMessages, o.compileMessages)
        assertEquals(emptyList(), o.errors)
        val entry = o.module!!.entries.single()
        assertTrue(entry.params.any { it.name == "n" } && entry.params.none { it.name.startsWith("limit") }, entry.params.toString())
        assertEquals(emptyList(), checkWellFormed(o.module!!))
        // Without the free line the parameter keeps its generated name.
        val plain = ClientFixture.resolve(File(dir, "plain"), client("val big = limit > 1\nreturn Data(value = callable.call(), ttl = 0L)"), ClientFixture.sidecar("result res"))
        assertTrue(plain.module!!.entries.single().params.any { it.name.startsWith("limit__") }, plain.module!!.entries.single().params.toString())
    }

    private val freeLine = "free maxAttempt = this.reqShieldConfig.maxAttemptGetCache"

    /** `kv resolve` of a copy of the signed core sidecar with its maxAttempt line replaced by [line]. */
    private fun core(line: String): Pair<File, ResolveOutcome> {
        val text = File(System.getProperty("kv.specDir"), "reqshield/reqshield.core.kv").readText()
        check(text.split(freeLine).size == 2)
        val copy = File(dir, "reqshield.core.kv").apply { writeText(text.replace(freeLine, line)) }
        return copy to Frontend.resolve(copy, ReqShield.dir, ReqShield.classpath, ReqShield.kotlincArgs).also { assertNull(it.compileMessages) }
    }

    @Test
    fun `C3 - a parameter read only in dropped arguments is free-unused at the free line`() {
        // getAndSet reads timeToLiveMillis only as arguments of setCacheFunction and decideToUpdateCache, which the
        // model drops, so no translated code reads it.
        val (copy, o) = core("free maxAttempt = param timeToLiveMillis")
        val e = o.errors.single()
        assertEquals("free-unused" to copy.readLines().indexOfFirst { "param timeToLiveMillis" in it } + 1, e.code to e.pos.line, e.toString())
        assertTrue(e.message.startsWith("free getAndSet.maxAttempt: param timeToLiveMillis is read by no translated code, or it is the obligation key, which the sidecar reads as k, but maxAttempt is used by POLL_bound"), e.message)
    }

    @Test
    fun `naming the obligation key is free-unused too, since the key is the local k`() {
        val (_, o) = core("free maxAttempt: Key = param key")
        val e = o.errors.single()
        assertEquals("free-unused", e.code, e.toString())
        assertTrue(e.message.startsWith("free getAndSet.maxAttempt: param key is read by no translated code, or it is the obligation key"), e.message)
    }
}
