package kv.client

import kv.ReqShield
import kv.frontend.Frontend
import kv.frontend.ResolveOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DESIGN-E 6.10 E1 acceptance: the names of a client sidecar bind to FIR symbols (the signed core ReqShield sidecar and the
 * construct fixture), and the resolver reports what does not bind: a `hole loop` that names no loop or no single loop
 * (DESIGN-E 12.5), a trusted value or trusted fun whose types do not stand for the Kotlin ones, a free value path
 * through a var or of an unmodeled type.
 */
class ClientResolveTest {
    @TempDir
    lateinit var dir: File

    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")

    private val body =
        """
        val token = config.lock.tryLock(key, Kind.CREATE)
        if (token != null) config.lock.unLock(key, Kind.CREATE, token)
        var attempts = 0
        while (attempts < config.maxAttempt) {
            attempts++
        }
        return Data(value = callable.call(), ttl = 0L)
        """

    private fun resolve(
        sidecar: String = ClientFixture.sidecar(),
        client: String = ClientFixture.client(body),
    ): ResolveOutcome = ClientFixture.resolve(dir, client, sidecar).also { assertNull(it.compileMessages, it.compileMessages) }

    private fun codes(o: ResolveOutcome) = o.errors.map { it.code }

    @Test
    fun `the signed core ReqShield sidecar binds every name`() {
        val outcome = Frontend.resolve(File(specDir, "reqshield.core.kv"), ReqShield.dir, ReqShield.classpath, ReqShield.kotlincArgs)
        assertNull(outcome.compileMessages)
        assertEquals(emptyList(), outcome.errors)
        val bindings = outcome.resolution!!.bindings.map { "${it.sidecar} -> ${it.kotlin}${it.kotlinPos?.let { p -> " @${p.line}:${p.col}" } ?: ""}" }
        assertEquals(
            listOf(
                "module com.linecorp.cse.reqshield.ReqShield -> class com.linecorp.cse.reqshield.ReqShield @35:1",
                "enum LockType -> class com.linecorp.cse.reqshield.LockType @49:1",
                "enum Mode -> class com.linecorp.cse.reqshield.config.ReqShieldWorkMode @114:1",
                "class Data -> class com.linecorp.cse.reqshield.support.model.ReqShieldData",
                "field Data.value -> property com.linecorp.cse.reqshield.support.model.ReqShieldData.value",
                "trusted tryLock -> fun com.linecorp.cse.reqshield.KeyLock.tryLock(kotlin.String, com.linecorp.cse.reqshield.LockType) @32:5",
                "trusted unLock -> fun com.linecorp.cse.reqshield.KeyLock.unLock(kotlin.String, com.linecorp.cse.reqshield.LockType, kotlin.String) @42:5",
                "trusted value getCacheFunction -> property com.linecorp.cse.reqshield.config.ReqShieldConfiguration.getCacheFunction @34:5",
                "trusted value setCacheFunction -> property com.linecorp.cse.reqshield.config.ReqShieldConfiguration.setCacheFunction @33:5",
                "trusted call -> fun java.util.concurrent.Callable.call()",
                "trusted decideToUpdateCache -> fun com.linecorp.cse.reqshield.support.utils.decideToUpdateCache(kotlin.Long, kotlin.Long, kotlin.Int)",
                "entry getAndSet -> fun com.linecorp.cse.reqshield.ReqShield.getAndSetReqShieldData(kotlin.String, java.util.concurrent.Callable, kotlin.Long) @38:5",
                "free getAndSet.mode -> property com.linecorp.cse.reqshield.config.ReqShieldConfiguration.reqShieldWorkMode @60:5",
                "free getAndSet.maxAttempt -> property com.linecorp.cse.reqshield.config.ReqShieldConfiguration.maxAttemptGetCache @59:5",
                "hole loop ReqShield.handleLockFailure -> loop of fun com.linecorp.cse.reqshield.ReqShield.handleLockFailure @181:9",
            ),
            bindings,
        )
    }

    @Test
    fun `the fixture sidecar binds its entry, free values and trusted declarations`() {
        val outcome = resolve(ClientFixture.sidecar("free mode = this.config.mode\nfree n = this.config.maxAttempt\nresult res", loopHole("Client.run")))
        assertEquals(emptyList(), outcome.errors)
        val names = outcome.resolution!!.bindings.map { it.sidecar }
        assertEquals(
            listOf(
                "module fx.Client", "enum Kind", "enum Mode", "class Data", "field Data.value", "trusted tryLock", "trusted unLock", "trusted value get",
                "trusted value set", "trusted call", "trusted decide", "entry run", "free run.mode", "free run.n", "hole loop Client.run",
            ),
            names,
        )
    }

    /** A `hole loop` declaration of [name] with body `true`. */
    private fun loopHole(name: String) = "hole loop $name \"\"\"\n  true\n\"\"\"\n"

    /** The client of [body] with [above] before its class, resolved with one `hole loop` per name in [names]. */
    private fun resolveLoops(
        body: String,
        vararg names: String,
        above: String = "",
    ): Pair<String, ResolveOutcome> {
        val client = ClientFixture.client(body).replace("class Client<T>(", "${above}class Client<T>(")
        return client to resolve(ClientFixture.sidecar(extra = names.joinToString("") { loopHole(it) }), client)
    }

    /** The Kotlin line and column of each `hole loop` binding of [o]: `NAME @LINE:COL`. */
    private fun loopBindings(o: ResolveOutcome) =
        o.resolution!!.bindings.filter { it.sidecar.startsWith("hole loop ") }.map { "${it.sidecar.removePrefix("hole loop ")} @${it.kotlinPos!!.line}:${it.kotlinPos!!.col}" }

    /** The 1-based line of the first line of [client] that contains [text]. */
    private fun lineOf(
        client: String,
        text: String,
    ) = client.lines().indexOfFirst { text in it }.also { check(it >= 0) { "no '$text'" } } + 1

    @Test
    fun `hole loop KNAME binds the only loop of the function (DESIGN-E 12 D3)`() {
        val (client, o) = resolveLoops(body, "Client.run")
        assertEquals(emptyList(), o.errors)
        val binding = o.resolution!!.bindings.single { it.sidecar.startsWith("hole loop") }
        assertEquals("hole loop Client.run" to "loop of fun fx.Client.run", binding.sidecar to binding.kotlin)
        assertEquals(listOf("Client.run @${lineOf(client, "while (attempts")}:9"), loopBindings(o))
    }

    @Test
    fun `a function without a loop has no loop for KNAME or KNAME#1 (DESIGN-E 12 D4)`() {
        val (_, o) = resolveLoops("return Data(value = callable.call(), ttl = 0L)", "Client.run", "Client.run#1")
        assertEquals(listOf("no-loop", "no-loop"), codes(o), o.errors.joinToString("\n"))
        assertEquals(
            listOf("hole loop Client.run: fun run has no loop", "hole loop Client.run#1: fun run has 0 loop(s), so #1 names none"),
            o.errors.map { it.message },
        )
    }

    private val twoLoops =
        """
        var attempts = 0
        while (attempts < config.maxAttempt) {
            attempts++
        }
        var failures = 0
        while (failures < 3) {
            failures++
        }
        return Data(value = callable.call(), ttl = 0L)
        """

    @Test
    fun `with two loops KNAME is ambiguous, KNAME#2 binds the second and KNAME#3 names none (DESIGN-E 12 D5)`() {
        val (client, o) = resolveLoops(twoLoops, "Client.run", "Client.run#3")
        assertEquals(listOf("ambiguous", "no-loop"), codes(o), o.errors.joinToString("\n"))
        val first = lineOf(client, "while (attempts")
        val second = lineOf(client, "while (failures")
        assertEquals("hole loop Client.run: fun run has 2 loops (lines $first, $second); name one: Client.run#1 or Client.run#2", o.errors[0].message)
        assertEquals("hole loop Client.run#3: fun run has 2 loop(s), so #3 names none", o.errors[1].message)
        // A resolution with errors has no bindings, so the second loop binds in a run of its own.
        val (_, two) = resolveLoops(twoLoops, "Client.run#2")
        assertEquals(emptyList(), two.errors)
        assertEquals(listOf("Client.run#2 @$second:9"), loopBindings(two))
    }

    @Test
    fun `loops count in source pre-order, the outer loop before the loops in it (DESIGN-E 12 D6)`() {
        val nested =
            """
            var i = 0
            while (i < 3) {
                var j = 0
                do {
                    j++
                } while (j < 2)
                i++
            }
            while (i > 0) {
                i--
            }
            return Data(value = callable.call(), ttl = 0L)
            """
        val (client, o) = resolveLoops(nested, "Client.run#1", "Client.run#2", "Client.run#3")
        assertEquals(emptyList(), o.errors)
        assertEquals(
            listOf("Client.run#1 @${lineOf(client, "while (i < 3)")}:9", "Client.run#2 @${lineOf(client, "do {")}:13", "Client.run#3 @${lineOf(client, "while (i > 0)")}:9"),
            loopBindings(o),
        )
    }

    @Test
    fun `a loop in a lambda of the function counts (DESIGN-E 12 D7)`() {
        val inLambda =
            """
            val count = {
                var n = 0
                while (n < 2) {
                    n++
                }
                n
            }
            count()
            return Data(value = callable.call(), ttl = 0L)
            """
        val (client, o) = resolveLoops(inLambda, "Client.run")
        assertEquals(emptyList(), o.errors)
        assertEquals(listOf("Client.run @${lineOf(client, "while (n < 2)")}:13"), loopBindings(o))
    }

    @Test
    fun `a Kotlin for loop is a FIR while loop, so it counts before a later while (DESIGN-E 12 D8)`() {
        val forThenWhile =
            """
            var attempts = 0
            for (i in 0 until 3) {
                attempts += i
            }
            while (attempts < config.maxAttempt) {
                attempts++
            }
            return Data(value = callable.call(), ttl = 0L)
            """
        val (client, o) = resolveLoops(forThenWhile, "Client.run#2")
        assertEquals(emptyList(), o.errors)
        assertEquals(listOf("Client.run#2 @${lineOf(client, "while (attempts")}:9"), loopBindings(o))
        val (_, both) = resolveLoops(forThenWhile, "Client.run")
        assertEquals(listOf("ambiguous"), codes(both), both.errors.joinToString("\n"))
        assertTrue("has 2 loops (lines ${lineOf(client, "for (i in")}, ${lineOf(client, "while (attempts")})" in both.errors.single().message, both.errors.single().message)
    }

    @Test
    fun `two holes that name one loop are a duplicate (DESIGN-E 12 D9)`() {
        val (_, o) = resolveLoops(body, "Client.run", "Client.run#1")
        assertEquals(listOf("duplicate-decl"), codes(o), o.errors.joinToString("\n"))
        // The holes follow the fixture sidecar: `Client.run` on its first line after it, `Client.run#1` three lines later.
        val first = ClientFixture.sidecar().lines().size
        val e = o.errors.single()
        assertTrue(e.message.startsWith("hole loop Client.run#1 names the loop of hole loop Client.run at ") && e.message.endsWith("client.kv:$first:1"), e.message)
        assertEquals(first + 3, e.pos.line)
    }

    @Test
    fun `an edit above the loop, outside or inside its function, leaves the hole on the same loop (DESIGN-E 12 D10)`() {
        val (client, o) = resolveLoops(body, "Client.run")
        val six = "private fun spare(\n    a: Int,\n    b: Int,\n): Int {\n    return a + b\n}\n"
        val (above, aboveOutcome) = resolveLoops(body, "Client.run", above = six)
        val (inside, insideOutcome) = resolveLoops(body.replace("var attempts = 0", "val spare = 1\n        var attempts = 0"), "Client.run")
        for (r in listOf(o, aboveOutcome, insideOutcome)) assertEquals(emptyList(), r.errors)
        val line = lineOf(client, "while (attempts")
        assertEquals(line + 6, lineOf(above, "while (attempts"))
        assertEquals(line + 1, lineOf(inside, "while (attempts"))
        assertEquals(listOf("Client.run @$line:9"), loopBindings(o))
        assertEquals(listOf("Client.run @${line + 6}:9"), loopBindings(aboveOutcome))
        assertEquals(listOf("Client.run @${line + 1}:9"), loopBindings(insideOutcome))
    }


    @Test
    fun `a trusted value's types must stand for its Kotlin function type`() {
        val result = resolve(ClientFixture.sidecar().replace("Config.get(key: Key) returns d: Data?", "Config.get(key: Key) returns d: Bool"))
        assertEquals(listOf("kotlin-type"), codes(result), result.errors.joinToString("\n"))
        assertTrue("declared (Key) -> Bool, but 'Config.get' is a kotlin.Function1" in result.errors.single().message, result.errors.single().message)
        val arity = resolve(ClientFixture.sidecar().replace("Config.set(key: Key, data: Data, kotlin.Long)", "Config.set(key: Key, data: Data)"))
        assertEquals(listOf("kotlin-type"), codes(arity), arity.errors.joinToString("\n"))
        val notFunction = resolve(ClientFixture.sidecar().replace("trusted value Config.get(key: Key)", "trusted value Config.mode(key: Key)"))
        assertEquals(listOf("kotlin-type"), codes(notFunction), notFunction.errors.joinToString("\n"))
        assertTrue("not a Kotlin function type" in notFunction.errors.single().message)
    }

    @Test
    fun `a named trusted parameter or result must stand for its Kotlin type`() {
        val kind = resolve(ClientFixture.sidecar().replace("Lock.tryLock(key: Key, kind: Kind)", "Lock.tryLock(key: Key, kind: Mode)"))
        assertEquals(listOf("kotlin-type"), codes(kind), kind.errors.joinToString("\n"))
        // Token (not Token?) does not stand for a String? result.
        val result = resolve(ClientFixture.sidecar().replace("returns t: Token? creates Lease(key, kind, t) if t != null", "returns t: Token creates Lease(key, kind, t)"))
        assertEquals(listOf("kotlin-type"), codes(result), result.errors.joinToString("\n"))
    }

    @Test
    fun `a free value is a path of vals of a modeled type`() {
        val viaVar = resolve(ClientFixture.sidecar("free s = this.config.spare\nresult res"))
        assertEquals(listOf("free-var"), codes(viaVar), viaVar.errors.joinToString("\n"))
        val lock = resolve(ClientFixture.sidecar("free l = this.config.lock\nresult res"))
        assertEquals(listOf("free-type"), codes(lock), lock.errors.joinToString("\n"))
        val key = resolve(ClientFixture.sidecar("free kk = param key\nresult res"))
        assertEquals(listOf("free-type"), codes(key), key.errors.joinToString("\n"))
        assertTrue("names its sidecar type" in key.errors.single().message)
        assertEquals(emptyList(), resolve(ClientFixture.sidecar("free kk: Key = param key\nresult res")).errors)
    }

    @Test
    fun `an enum binds a Kotlin enum class and an entry a public function of the module class`() {
        val notEnum = resolve(ClientFixture.sidecar().replace("enum Mode = Mode", "enum Mode = Config"))
        assertEquals(listOf("unresolved"), codes(notEnum), notEnum.errors.joinToString("\n"))
        val helper = ClientFixture.client(body, "private fun hidden(): Int = 1")
        val notPublic = resolve(ClientFixture.sidecar().replace("operation run = fun Client.run", "operation run = fun Client.hidden"), helper)
        assertEquals(listOf("client-entry"), codes(notPublic), notPublic.errors.joinToString("\n"))
    }
}
