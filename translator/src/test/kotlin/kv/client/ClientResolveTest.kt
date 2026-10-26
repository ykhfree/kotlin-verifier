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
 * DESIGN-E 6.10 E1 acceptance: the names of a client sidecar bind to FIR symbols (the core ReqShield draft and the
 * construct fixture), and the resolver reports what does not bind: a `hole loop` without a loop, a trusted value or
 * trusted fun whose types do not stand for the Kotlin ones, a free value path through a var or of an unmodeled type.
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
    fun `the core ReqShield draft binds every name`() {
        val outcome = Frontend.resolve(File(specDir, "reqshield.core.kv"), ReqShield.dir, ReqShield.classpath, ReqShield.kotlincArgs, draft = true)
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
                "hole loop ReqShield.handleLockFailure@181 -> loop of fun com.linecorp.cse.reqshield.ReqShield.handleLockFailure @181:9",
            ),
            bindings,
        )
    }

    @Test
    fun `the fixture sidecar binds its entry, free values and trusted declarations`() {
        val outcome = resolve(ClientFixture.sidecar("free mode = this.config.mode\nfree n = this.config.maxAttempt\nresult res", "hole loop Client.run@20 \"\"\"\n  true\n\"\"\"\n"))
        assertEquals(emptyList(), outcome.errors)
        val names = outcome.resolution!!.bindings.map { it.sidecar }
        assertEquals(
            listOf(
                "module fx.Client", "enum Kind", "enum Mode", "class Data", "field Data.value", "trusted tryLock", "trusted unLock", "trusted value get",
                "trusted value set", "trusted call", "trusted decide", "entry run", "free run.mode", "free run.n", "hole loop Client.run@20",
            ),
            names,
        )
    }

    @Test
    fun `a hole loop must name a line of the function that holds a loop`() {
        val outcome = resolve(ClientFixture.sidecar(extra = "hole loop Client.run@15 \"\"\"\n  true\n\"\"\"\n"))
        assertEquals(listOf("no-loop"), codes(outcome), outcome.errors.joinToString("\n"))
        assertTrue("fun run has no loop at line 15" in outcome.errors.single().message, outcome.errors.single().message)
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
