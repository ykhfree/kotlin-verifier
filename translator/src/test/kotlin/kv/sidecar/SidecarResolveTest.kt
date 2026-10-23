package kv.sidecar

import kv.ReqShield
import kv.frontend.Frontend
import kv.frontend.ResolveOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2 acceptance (DESIGN-v0.md section 7): both KeyLocalLock sidecars parse and every Kotlin name binds to a FIR
 * symbol; each listed error is reported with its sidecar line and column. Error cases edit a copy of the specs.
 */
class SidecarResolveTest {
    @TempDir
    lateinit var dir: File

    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")

    private fun copySpecs() = specDir.listFiles()!!.forEach { it.copyTo(File(dir, it.name)) }

    /** Replaces [old] (which must occur exactly [count] times) in the copied spec [file]. */
    private fun edit(
        file: String,
        old: String,
        new: String,
        count: Int = 1,
    ) {
        val f = File(dir, file)
        val text = f.readText()
        check(text.split(old).size - 1 == count) { "'$old' occurs ${text.split(old).size - 1} times in $file" }
        f.writeText(text.replace(old, new))
    }

    /** Removes the `reviewed` note on the line right after the one that ends with [clause], whatever the note says. */
    private fun dropReviewedAfter(
        file: String,
        clause: String,
    ) {
        val f = File(dir, file)
        val text = f.readText()
        val pattern = Regex(Regex.escape(clause) + "\n *reviewed \"[^\"]*\"\n")
        val matches = pattern.findAll(text).count()
        check(matches == 1) { "'$clause' followed by a reviewed note occurs $matches times in $file" }
        f.writeText(text.replace(pattern, clause + "\n"))
    }

    /** 1-based line and column of [fragment] in the copied spec [file], within the line that contains [line]. */
    private fun at(
        file: String,
        line: String,
        fragment: String = line,
    ): Pair<Int, Int> {
        val lines = File(dir, file).readLines()
        val n = lines.indexOfFirst { line in it }
        check(n >= 0) { "no '$line' in $file" }
        return n + 1 to lines[n].indexOf(fragment, lines[n].indexOf(line)) + 1
    }

    private fun resolve(variant: String): ResolveOutcome =
        Frontend.resolve(File(dir, "keylocallock.$variant.kv"), ReqShield.dir, ReqShield.classpath, ReqShield.kotlincArgs)

    private fun assertSingleError(
        outcome: ResolveOutcome,
        code: String,
        file: String,
        position: Pair<Int, Int>,
    ): SidecarError {
        assertNull(outcome.compileMessages)
        val e = outcome.errors.singleOrNull() ?: error("expected one error, got:\n${outcome.errors.joinToString("\n")}")
        assertEquals(code, e.code, e.toString())
        assertTrue(e.pos.file.endsWith(file), e.toString())
        assertEquals(position, e.pos.line to e.pos.col, e.toString())
        return e
    }

    // ---- both sidecars resolve ----

    /** The sidecar items that name Kotlin code; each needs one binding. */
    private fun expectedLabels(s: Sidecar): Set<String> =
        buildSet {
            s.modules.forEach { add("module ${it.fqn.text}") }
            s.classes.forEach { c ->
                add("class ${c.name.text}")
                c.fields.forEach { add("field ${c.name.text}.${it.name.text}") }
            }
            s.states.forEach { add("state ${it.name.text}") }
            s.trusted.forEach { add("trusted ${it.shortName}") }
            s.operations.forEach { op ->
                add("operation ${op.name.text}")
                add("action ${op.name.text}")
                op.free.forEach { add("free ${op.name.text}.${it.names.joinToString(", ") { n -> n.text }}") }
            }
        }

    private fun assertResolves(
        variant: String,
        expected: Map<String, String>,
    ) {
        copySpecs()
        val outcome = resolve(variant)
        assertEquals(emptyList(), outcome.errors)
        assertNull(outcome.compileMessages)
        val resolution = assertNotNull(outcome.resolution)
        assertEquals(emptyList(), resolution.unsupported)
        val sidecar = SidecarLoader.load(File(dir, "keylocallock.$variant.kv"))
        assertEquals(expectedLabels(sidecar), resolution.bindings.map { it.sidecar }.toSet(), "every Kotlin name of the sidecar has a binding")
        assertEquals(resolution.bindings.size, resolution.bindings.map { it.sidecar }.toSet().size, "no name binds twice")
        val actual = resolution.bindings.associate { it.sidecar to "${it.kotlin.substringAfterLast(' ').substringAfterLast('.')} ${it.kotlinPos?.let { p -> "${p.line}:${p.col}" } ?: "library"}" }
        assertEquals(expected.toSortedMap(), actual.filterKeys { it in expected }.toSortedMap())
    }

    @Test
    fun `coroutine sidecar parses and every Kotlin name binds to a FIR symbol`() =
        assertResolves(
            "coroutine",
            mapOf(
                "module com.linecorp.cse.reqshield.kotlin.coroutine.KeyLocalLock.Companion" to "Companion 68:5",
                "class LockInfo" to "LockInfo 44:5",
                "field LockInfo.permits" to "semaphore 45:9",
                "field LockInfo.expiresAt" to "expiresAt 50:9",
                "field LockInfo.isHeld" to "isHeld 57:9",
                "field LockInfo.token" to "token 65:9",
                "state m" to "lockMap 69:9",
                "state seq" to "tokenSequence 75:9",
                "trusted nowToEpochTime" to "nowToEpochTime() library",
                "trusted rejectsNewEntry" to "Long) library",
                "operation tryLock" to "LockType) 145:5",
                "action tryLock" to "call 155:9",
                "free tryLock.now" to "now 150:9",
                "free tryLock.timeout" to "lockTimeoutMillis 39:20",
                "free tryLock.rej, limit" to "Long) 181:21",
                "operation unLock" to "String) 198:5",
                "action unLock" to "call 209:9",
                "free unLock.t" to "token 201:9",
                "operation sweep" to "Companion 68:5",
                "action sweep" to "call 97:37",
                "free sweep.now" to "now 90:33",
            ),
        )

    @Test
    fun `core sidecar parses and every Kotlin name binds to a FIR symbol`() =
        assertResolves(
            "core",
            mapOf(
                "module com.linecorp.cse.reqshield.KeyLocalLock.Companion" to "Companion 65:5",
                "class LockInfo" to "LockInfo 40:5",
                "field LockInfo.permits" to "semaphore 41:9",
                "field LockInfo.expiresAt" to "expiresAt 46:9",
                "field LockInfo.isHeld" to "isHeld 53:9",
                "field LockInfo.token" to "token 62:9",
                "state m" to "lockMap 67:9",
                "state seq" to "tokenCounter 70:9",
                "operation tryLock" to "LockType) 163:5",
                "action tryLock" to "call 173:9",
                "free tryLock.now" to "now 168:9",
                "free tryLock.timeout" to "lockTimeoutMillis 35:20",
                "free tryLock.rej, limit" to "Long) 199:21",
                "operation unLock" to "String) 217:5",
                "action unLock" to "call 229:9",
                "free unLock.t" to "token 220:9",
                "operation sweep" to "Companion 65:5",
                "action sweep" to "call 109:25",
                "free sweep.now" to "now 101:21",
            ),
        )

    // ---- the error cases of the M2 acceptance list ----

    @Test
    fun `reports a class field whose Kotlin property does not exist`() {
        copySpecs()
        edit("keylocallock.common.kv", "expiresAt: Long          = expiresAt", "expiresAt: Long          = expiryTime")
        val e = assertSingleError(resolve("coroutine"), "missing-kotlin-field", "keylocallock.common.kv", at("keylocallock.common.kv", "= expiryTime", "expiryTime"))
        assertTrue("KeyLocalLock.LockInfo has no property 'expiryTime'" in e.message, e.message)
    }

    @Test
    fun `reports a free value whose name clashes with a model field`() {
        copySpecs()
        edit("keylocallock.common.kv", "free t = param token", "free token = param token")
        edit("keylocallock.common.kv", "t = t1)", "token = t1)", count = 2)
        val e = assertSingleError(resolve("coroutine"), "name-clash", "keylocallock.common.kv", at("keylocallock.common.kv", "free token", "token"))
        assertTrue("field of LockInfo" in e.message, e.message)
    }

    @Test
    fun `reports a compute-in selector that matches two compute calls`() {
        copySpecs()
        edit("keylocallock.common.kv", "compute in KeyLocalLock.Companion", "compute in KeyLocalLock")
        val e = assertSingleError(resolve("coroutine"), "ambiguous-selector", "keylocallock.common.kv", at("keylocallock.common.kv", "compute in KeyLocalLock", "KeyLocalLock"))
        assertTrue("2 compute calls [155:9, 209:9]" in e.message, e.message)
    }

    @Test
    fun `reports an unknown trusted model`() {
        copySpecs()
        edit("keylocallock.common.kv", "use jdk.AtomicLong\n", "use jdk.AtomicLongArray\n")
        assertSingleError(resolve("core"), "unknown-model", "keylocallock.common.kv", at("keylocallock.common.kv", "use jdk.AtomicLongArray", "jdk.AtomicLongArray"))
    }

    @Test
    fun `reports a missing hole at the module declaration`() {
        copySpecs()
        val common = File(dir, "keylocallock.common.kv")
        val text = common.readText()
        common.writeText(text.substring(0, text.indexOf("hole MI_INV")) + text.substring(text.lastIndexOf("\"\"\"") + 3))
        val e = assertSingleError(resolve("coroutine"), "missing-hole", "keylocallock.coroutine.kv", at("keylocallock.coroutine.kv", "module "))
        assertTrue("hole MI_INV" in e.message, e.message)
    }

    @Test
    fun `reports an issue value that reads module state (R1)`() {
        copySpecs()
        edit("keylocallock.common.kv", "gLease = now + timeout }", "gLease = S.m[k].expiresAt }")
        val e = assertSingleError(resolve("coroutine"), "R1", "keylocallock.common.kv", at("keylocallock.common.kv", "gLease = S.m[k]", "S.m[k]"))
        assertTrue("module state 'S'" in e.message, e.message)
    }

    @Test
    fun `reports an allowance clause without a reviewed note`() {
        copySpecs()
        dropReviewedAfter("keylocallock.common.kv", "  ends_only_if P3a: qt == t && res")
        val e = assertSingleError(resolve("core"), "unreviewed", "keylocallock.common.kv", at("keylocallock.common.kv", "ends_only_if P3a: qt == t", "ends_only_if"))
        assertTrue("ends_only_if P3a" in e.message, e.message)
    }

    @Test
    fun `reports an issue rule without a reviewed note`() {
        copySpecs()
        dropReviewedAfter("keylocallock.common.kv", "gLease = now + timeout }")
        assertSingleError(resolve("coroutine"), "unreviewed", "keylocallock.common.kv", at("keylocallock.common.kv", "issue res {", "issue"))
    }

    @Test
    fun `reports a trusted function without a reviewed note`() {
        copySpecs()
        dropReviewedAfter("keylocallock.common.kv", "trusted fun com.linecorp.cse.reqshield.support.utils.nowToEpochTime() = clock")
        val e = assertSingleError(resolve("core"), "unreviewed", "keylocallock.common.kv", at("keylocallock.common.kv", "trusted fun com.linecorp.cse.reqshield.support.utils.nowToEpochTime", "trusted"))
        assertTrue("trusted fun com.linecorp.cse.reqshield.support.utils.nowToEpochTime" in e.message, e.message)
    }

    @Test
    fun `reports a trusted function with clauses but no reviewed note`() {
        copySpecs()
        dropReviewedAfter("keylocallock.common.kv", "  effects none                          // rejectionCount and logging are outside the model")
        val e = assertSingleError(resolve("coroutine"), "unreviewed", "keylocallock.common.kv", at("keylocallock.common.kv", "trusted fun com.linecorp.cse.reqshield.support.config", "trusted"))
        assertTrue("rejectsNewEntry" in e.message, e.message)
    }

    // ---- further resolver cases ----

    @Test
    fun `reports a local that is not visible at the compute call`() {
        copySpecs()
        // tryLock declares `val token` inside the compute lambda, so the compute call does not see it.
        edit("keylocallock.common.kv", "free now        = local now", "free now        = local token")
        assertSingleError(resolve("coroutine"), "unresolved", "keylocallock.common.kv", at("keylocallock.common.kv", "local token", "token"))
    }

    @Test
    fun `reports a module class that no source declares`() {
        copySpecs()
        edit("keylocallock.core.kv", "module com.linecorp.cse.reqshield.KeyLocalLock.Companion", "module com.linecorp.cse.reqshield.KeyLocalLock.Missing")
        assertSingleError(resolve("core"), "unresolved", "keylocallock.core.kv", at("keylocallock.core.kv", "module ", "com.linecorp"))
    }

    @Test
    fun `reports a trusted function whose parameter types do not match`() {
        copySpecs()
        edit("keylocallock.common.kv", "LocalLockLimit.rejectsNewEntry(kotlin.Long)", "LocalLockLimit.rejectsNewEntry(kotlin.Int)")
        val e = assertSingleError(resolve("core"), "unresolved", "keylocallock.common.kv", at("keylocallock.common.kv", "trusted fun com.linecorp.cse.reqshield.support.config", "com.linecorp"))
        assertTrue("[[kotlin.Long]]" in e.message, e.message)
    }

    @Test
    fun `marks an operation whose function has no compute call as unsupported, not as an error`() {
        copySpecs()
        File(dir, "keylocallock.common.kv").appendText("\noperation stop = fun KeyLocalLock.Companion.stopMonitoring {\n}\n")
        val outcome = resolve("coroutine")
        assertEquals(emptyList(), outcome.errors)
        val u = outcome.resolution!!.unsupported.single()
        assertEquals("stop", u.operation)
        assertTrue("0 compute calls" in u.reason, u.reason)
    }
}
