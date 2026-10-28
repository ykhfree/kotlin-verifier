package kv.sidecar

import kv.Aspects
import kv.ReqShield
import kv.frontend.Frontend
import kv.frontend.ResolveOutcome
import kv.lower.AspectFixture
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DESIGN-B.md 8, B1: the three Spring aspect sidecars and their include parse, pass the checks and bind every Kotlin
 * name to a FIR symbol; each listed sidecar error is reported with its sidecar line and column. Error cases edit a
 * copy of the specs or compile a small fixture.
 */
class AspectSidecarTest {
    @TempDir
    lateinit var dir: File

    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")
    private val modules = listOf("core-spring", "webflux", "coroutine")

    private fun copySpecs() = specDir.listFiles()!!.forEach { it.copyTo(File(dir, it.name), overwrite = true) }

    /** Replaces [old] (which must occur exactly once) in the copied spec [file]. */
    private fun edit(
        file: String,
        old: String,
        new: String,
    ) {
        val f = File(dir, file)
        val text = f.readText()
        check(text.split(old).size == 2) { "'$old' occurs ${text.split(old).size - 1} times in $file" }
        f.writeText(text.replace(old, new))
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

    private fun resolve(module: String): ResolveOutcome = Frontend.resolve(File(dir, "aspect.$module.kv"), ReqShield.dir, Aspects.classpath, ReqShield.kotlincArgs)

    private fun assertSingleError(
        errors: List<SidecarError>,
        code: String,
        file: String,
        position: Pair<Int, Int>,
    ): SidecarError {
        val e = errors.singleOrNull() ?: error("expected one error, got:\n${errors.joinToString("\n")}")
        assertEquals(code, e.code, e.toString())
        assertTrue(e.pos.file.endsWith(file), e.toString())
        assertEquals(position, e.pos.line to e.pos.col, e.toString())
        return e
    }

    @Test
    fun `the three aspect specs pass the checks`() {
        for (module in modules) assertEquals(emptyList(), checkSidecar(SidecarLoader.load(File(specDir, "aspect.$module.kv"))), module)
    }

    /** B1 acceptance 1: each aspect sidecar binds every Kotlin name; the value class resolves through the module file's import. */
    @Test
    fun `the three aspect sidecars bind every Kotlin name to a FIR symbol`() {
        copySpecs()
        val expected =
            mapOf(
                "core-spring" to listOf("84:5", "116:5", "111:5", "112:9", "113:13", "com.linecorp.cse.reqshield.ReqShield"),
                "webflux" to listOf("77:5", "129:5", "124:5", "125:9", "126:13", "com.linecorp.cse.reqshield.reactor.ReqShield"),
                "coroutine" to listOf("90:5", "236:5", "231:5", "232:9", "233:13", "com.linecorp.cse.reqshield.kotlin.coroutine.ReqShield"),
            )
        for ((module, want) in expected) {
            val outcome = resolve(module)
            assertNull(outcome.compileMessages, module)
            assertEquals(emptyList(), outcome.errors, module)
            val resolution = assertNotNull(outcome.resolution, module)
            assertEquals(emptyList(), resolution.unsupported, module)
            val b = resolution.bindings.associateBy { it.sidecar }
            assertEquals(
                setOf("module ${outcome.sidecar!!.modules.single().fqn.text}", "class Shield", "state m", "trusted createReqShield", "operation getOrCreate", "action getOrCreate", "free getOrCreate.thrown"),
                b.keys,
                module,
            )
            val pos = listOf("state m", "trusted createReqShield", "operation getOrCreate", "action getOrCreate", "free getOrCreate.thrown").map { b.getValue(it).kotlinPos!!.let { p -> "${p.line}:${p.col}" } }
            assertEquals(want.dropLast(1), pos, module)
            assertEquals("class ${want.last()}", b.getValue("class Shield").kotlin, module)
            assertEquals("computeIfAbsent call", b.getValue("action getOrCreate").kotlin, module)
        }
    }

    // ---- B1 acceptance 2: errors with sidecar line and column ----

    @Test
    fun `reports set-once on a state that is not a map`() {
        copySpecs()
        // Signed as a person would (review intent, DESIGN-C.md 10.14), so only the error under test remains.
        edit("keylocallock.core.kv", "state seq: Counter            = tokenCounter\n", "state seq: Counter            = tokenCounter\nset-once P_seq: seq\n  source none \"t\"\n  reviewed \"t\"\n")
        kv.rereview(File(dir, "keylocallock.core.kv"))
        val errors = checkSidecar(SidecarLoader.load(File(dir, "keylocallock.core.kv")))
        val e = assertSingleError(errors, "set-once", "keylocallock.core.kv", at("keylocallock.core.kv", "set-once P_seq: seq", ": seq").let { (l, c) -> l to c + 2 })
        assertTrue("which is a Counter" in e.message, e.message)
    }

    @Test
    fun `reports an opaque type bound to a key class the trusted model table does not know`() {
        copySpecs()
        edit("aspect.common.kv", "opaque java.lang.reflect.Method", "opaque java.lang.Class")
        kv.rereview(File(dir, "aspect.common.kv"))
        val e = assertSingleError(resolve("core-spring").errors, "unknown-key-class", "aspect.common.kv", at("aspect.common.kv", "type MethodKey", "java.lang.Class"))
        assertTrue("java.lang.reflect.Method" in e.message, e.message)
    }

    @Test
    fun `reports a fresh trusted function without a reviewed note`() {
        copySpecs()
        val file = File(dir, "aspect.webflux.kv")
        file.writeText(file.readText().replace(Regex("\n  reviewed \"[^\"]*\"( sha256 \"[0-9a-f]*\")?"), ""))
        assertSingleError(resolve("webflux").errors, "unreviewed", "aspect.webflux.kv", at("aspect.webflux.kv", "trusted fun"))
    }

    @Test
    fun `reports a protected or public state field`() {
        for (decl in listOf("protected val shieldMap = ConcurrentHashMap<Method, Shield>()", "val shieldMap = ConcurrentHashMap<Method, Shield>()")) {
            val f = AspectFixture(mapDecl = decl)
            val e = assertSingleError(f.compile(File(dir, decl.substringBefore(' '))).sidecarErrors, "state-visibility", "Aspect.kv", f.sidecarAt("state m", "shieldMap"))
            assertTrue("private" in e.message && "internal" in e.message, e.message)
        }
    }

    @Test
    fun `reports a class name that neither the package nor an import of the module file resolves`() {
        copySpecs()
        edit("aspect.common.kv", "class Shield = ReqShield { }", "class Shield = ReqShieldX { }")
        kv.rereview(File(dir, "aspect.common.kv"))
        val e = assertSingleError(resolve("coroutine").errors, "unresolved", "aspect.common.kv", at("aspect.common.kv", "class Shield", "ReqShieldX"))
        assertTrue("the imports of ReqShieldAspect.kt" in e.message, e.message)
    }

    // ---- more checks of the B declarations ----

    @Test
    fun `set-once names a sidecar state once, and its ID is a global name`() {
        val base = AspectFixture()
        val text = base.sidecar.replace("set-once P_stable: m", "set-once P_stable: n\nset-once P_once: m\nset-once P_twice: m")
        val errors = checkSidecar(parseSidecar("t.kv", text))
        assertEquals(listOf("name-clash", "unknown-name", "duplicate-decl"), errors.map { it.code }, errors.joinToString("\n"))
        assertEquals(base.sidecar.replace("set-once P_stable: m", "set-once P_stable: n").lines().indexOfFirst { "P_stable: n" in it } + 1, errors[1].pos.line)
    }

    @Test
    fun `a fresh trusted function names a sidecar class and returns nothing else`() {
        val unknown = AspectFixture(trusted = AspectFixture.DEFAULT_TRUSTED.replace("returns fresh Shield", "returns fresh Guard"))
        assertEquals(listOf("unknown-name"), checkSidecar(parseSidecar("t.kv", unknown.sidecar)).map { it.code })
        // The named result is a second value of the call, so `free thrown = call create` misses one too.
        val both = AspectFixture(trusted = AspectFixture.DEFAULT_TRUSTED.replace("returns fresh Shield", "returns fresh Shield\n  returns ok: Bool"))
        assertEquals(listOf("fresh-returns", "free-arity"), checkSidecar(parseSidecar("t.kv", both.sidecar)).map { it.code })
    }

    @Test
    fun `a call free item of a throwing trusted function names its throw flag`() {
        val two = AspectFixture(operations = AspectFixture.DEFAULT_OPERATION.replace("free thrown = call create", "free thrown, other = call create"))
        val e = checkSidecar(parseSidecar("t.kv", two.sidecar)).single()
        assertEquals("free-arity", e.code)
        assertTrue("gives 1 value(s) [throw flag]" in e.message, e.message)
    }

    @Test
    fun `a probe call names at most the result and exc, and exc is a reserved name`() {
        val probe = "probe(k1: MethodKey) {\n  r1, e1, x1 := getOrCreate(k1, thrown = false)\n}"
        val errors = checkSidecar(parseSidecar("t.kv", AspectFixture(sidecarExtra = probe).sidecar))
        assertEquals(listOf("probe-outs"), errors.map { it.code }, errors.joinToString("\n"))
        val exc = checkSidecar(parseSidecar("t.kv", AspectFixture(operations = AspectFixture.DEFAULT_OPERATION.replace("free thrown", "free exc")).sidecar))
        assertEquals("name-clash", exc.first().code, exc.joinToString("\n"))
    }

    @Test
    fun `reports a fresh function whose Kotlin return type is not the class`() {
        val f =
            AspectFixture(
                factory = AspectFixture.DEFAULT_FACTORY.replace("private fun create(jp: Any): Shield {", "private fun create(jp: Any): Shield? {"),
                op = AspectFixture.DEFAULT_OP.replace("{ create(jp) }", "{ create(jp)!! }"),
            )
        val e = assertSingleError(f.compile(dir).sidecarErrors, "kotlin-type", "Aspect.kv", f.sidecarAt("returns fresh", "Shield"))
        assertTrue("returns fixture.Shield?" in e.message, e.message)
    }

    @Test
    fun `a module class and a class imported under an alias resolve`() {
        val f =
            AspectFixture(
                imports = "import lib.Guard as Shield2",
                otherFiles = mapOf("Lib.kt" to "package lib\n\nclass Guard\n"),
                sidecarExtra = "class Guard = Shield2 { }",
            )
        val result = f.compile(dir)
        assertEquals(emptyList(), result.sidecarErrors)
        val b = result.resolution!!.bindings.associateBy { it.sidecar }
        assertEquals("class fixture.Aspect", b.getValue("module fixture.Aspect").kotlin)
        assertEquals("class lib.Guard", b.getValue("class Guard").kotlin)
    }

    @Test
    fun `a call free item binds the first call in the operation, and no call is not an error`() {
        val twice = AspectFixture(op = "    fun getOrCreate(jp: Any): Shield {\n        create(jp)\n        return shieldMap.computeIfAbsent(target(jp)) { create(jp) }\n    }")
        val bound = twice.compile(File(dir, "twice")).also { assertEquals(emptyList(), it.sidecarErrors) }.resolution!!.bindings.single { it.sidecar == "free getOrCreate.thrown" }
        assertEquals(twice.at("create(jp)", after = "fun getOrCreate"), bound.kotlinPos!!.line to bound.kotlinPos!!.col)
        val none = AspectFixture(op = "    fun getOrCreate(jp: Any): Shield =\n        shieldMap.computeIfAbsent(target(jp)) { shared }")
        val unbound = none.compile(File(dir, "none")).also { assertEquals(emptyList(), it.sidecarErrors) }.resolution!!.bindings.single { it.sidecar == "free getOrCreate.thrown" }
        assertEquals(none.at("private fun create"), unbound.kotlinPos!!.line to unbound.kotlinPos!!.col)
    }
}
