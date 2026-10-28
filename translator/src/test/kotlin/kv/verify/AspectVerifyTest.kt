package kv.verify

import kv.Aspects
import kv.ReqShield
import kv.emit.Source
import kv.emit.specDir
import kv.frontend.Frontend
import kv.kvir.Nondet
import kv.kvir.walk
import kv.lower.AspectFixture
import kv.lower.getOrCreate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * With Silicon (DESIGN-B.md 8, B3): the three Spring aspect modules verify, and the fixtures of DESIGN-B.md 9.4
 * question 5 and of the set-once rules give the verdicts the design names. These tests fail, not skip, without Silicon.
 */
class AspectVerifyTest {
    @TempDir
    lateinit var dir: File

    private var n = 0

    @BeforeEach
    fun setUp() {
        Silicon.check(Silicon.defaultHome())
    }

    private fun AspectFixture.verified(): Report = verify(File(dir, "f${n++}"))

    /** The sidecar or generated IDs of the failing obligations of [label]. */
    private fun Report.failing(label: String) =
        row(label).findings.map {
            when (val s = it.source) {
                is Source.Sidecar -> s.id
                is Source.Generated -> s.what
                else -> "$s"
            }
        }.toSet()

    private fun op(body: String) = "    fun getOrCreate(jp: Any): Shield {\n$body\n    }"

    @Test
    fun `the three aspect modules are PROVEN`() {
        for (module in Aspects.roots.keys) {
            val outcome = Frontend.resolve(File(specDir, "aspect.$module.kv"), ReqShield.dir, Aspects.classpath, ReqShield.kotlincArgs)
            assertNull(outcome.compileMessages)
            assertEquals(emptyList(), outcome.errors)
            val r = verify(outcome.module!!, outcome.sidecar!!, File(dir, "$module.vpr"))
            val text = r.render()
            assertEquals(ModuleVerdict.PROVEN, r.verdict, "$module:\n$text")
            assertEquals(listOf("getOrCreate", "init", "probe"), r.rows.map { it.label }.sorted(), text)
            assertEquals(listOf(Verdict.PROVEN), r.rows.map { it.verdict }.distinct(), text)
            // Under review intent (DESIGN-C.md 10.14) the set-once is listed once, with the other intent clauses.
            val intent = "set-once P_stable, ensures P_same of getOrCreate, ensures progress_ok of getOrCreate, ensures factory_throw of getOrCreate, invariant P_once, the probe"
            assertTrue("Spec to review: intent clauses (review intent): $intent; trusted createReqShield: returns fresh Shield, throws, effects none" in text, text)
            // Nothing failed, so no property check (pass 2) ran.
            assertNull(r.propertyCheck, text)
            assertTrue(!File(dir, "$module.pass2.vpr").exists() && "Property check" !in text, text)
        }
    }

    // ---- DESIGN-B.md 9.4 question 5 ----

    /**
     * Create first, then [between], then putIfAbsent with a key computed before the create call. The hole says only
     * that a created object's key is mapped, and there is no P_once: so the shape is correct unless a throw between
     * the create call and putIfAbsent orphans the object.
     */
    private fun createThenPutIfAbsent(
        between: String,
        members: String = "",
        uses: String = AspectFixture.DEFAULT_USES,
        classpath: List<File> = emptyList(),
    ) = AspectFixture(
        op = op("        val method = target(jp)\n        val created = create(jp)\n$between\n        return shieldMap.putIfAbsent(method, created) ?: created"),
        members = members,
        uses = uses,
        classpath = classpath,
        invariants = "",
        hole = "(forall qr: Ref :: { qr in S.created } qr in S.created ==> has(S, qr.gFor))",
    )

    /** The question 5 flags of getOrCreate's section. */
    private fun question5Flags(f: AspectFixture) = f.lower(File(dir, "f${n++}")).getOrCreate.body.walk().filterIsInstance<Nondet>().filter { "may throw" in it.model }.toList()

    @Test
    fun `(a) an unmodeled call after the fresh call and before putIfAbsent may throw, so MI_INV_1 fails on the orphan path`() {
        val f = createThenPutIfAbsent("        audit(jp)", members = "    private fun audit(jp: Any) {\n        println(jp)\n    }")
        val r = f.verified()
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        assertEquals(setOf("MI_INV_1"), r.failing("getOrCreate"), text)
        assertEquals(listOf(Verdict.PROVEN), r.rows.filter { it.label != "getOrCreate" }.map { it.verdict }.distinct(), text)
        // The one raise is at the unmodeled call.
        assertEquals(listOf(f.at("audit(jp)", after = "val created")), question5Flags(f).map { it.pos.line to it.pos.col })
    }

    @Test
    fun `(b) a log call of the slf4j no-effect model adds no raise`() {
        val slf4j = ReqShield.classpath.filter { it.name.startsWith("slf4j-api") }
        assertEquals(1, slf4j.size, "slf4j-api on the req-shield classpath")
        val f =
            createThenPutIfAbsent(
                "        log.debug(\"created {}\", jp)",
                members = "    private val log = org.slf4j.LoggerFactory.getLogger(Aspect::class.java)",
                uses = AspectFixture.DEFAULT_USES + "\nuse slf4j.Logger",
                classpath = slf4j,
            )
        assertEquals(emptyList(), question5Flags(f))
        val r = f.verified()
        assertEquals(ModuleVerdict.PROVEN, r.verdict, r.render())
    }

    @Test
    fun `(c) the original shape, with the fresh call inside the action, adds no raise and is PROVEN`() {
        val f = AspectFixture(hole = AspectFixture.CREATED_ARE_MAPPED)
        assertEquals(emptyList(), question5Flags(f))
        val r = f.verified()
        assertEquals(ModuleVerdict.PROVEN, r.verdict, r.render())
    }

    @Test
    fun `an unmodeled call before the fresh call or after the action adds no raise`() {
        val audit = "    private fun audit(jp: Any) {\n        println(jp)\n    }"
        val before =
            AspectFixture(
                op = op("        audit(jp)\n        val created = create(jp)\n        return shieldMap.putIfAbsent(target(jp), created) ?: created"),
                members = audit,
            )
        // The key argument target(jp) still runs after the create call: that is the one raise.
        assertEquals(listOf(before.at("target(jp), created")), question5Flags(before).map { it.pos.line to it.pos.col })
        val after =
            AspectFixture(
                op = op("        val method = target(jp)\n        val created = create(jp)\n        val s = shieldMap.putIfAbsent(method, created) ?: created\n        audit(jp)\n        return s"),
                members = audit,
            )
        assertEquals(emptyList(), question5Flags(after))
    }

    @Test
    fun `a not-null assertion the section does not translate may throw after the fresh call`() {
        val f =
            createThenPutIfAbsent(
                "        maybe(jp)!!",
                members = "    private fun maybe(jp: Any): Any? = jp",
            )
        // The !! is the first node that may throw in the statement; it starts where its operand starts.
        assertEquals(listOf(f.at("maybe(jp)!!")), question5Flags(f).map { it.pos.line to it.pos.col })
    }

    // ---- set-once (DESIGN-B.md 2.4, 5.4 rule 5) ----

    @Test
    fun `without set-once the racy fast path read is arbitrary, so P_same fails`() {
        val f =
            AspectFixture(
                op = op("        val method = target(jp)\n        return shieldMap[method] ?: shieldMap.computeIfAbsent(method) { create(jp) }"),
                setOnce = "",
                hole = AspectFixture.CREATED_ARE_MAPPED,
            )
        val r = f.verified()
        val text = File(dir, "f${n - 1}/Aspect.vpr").readText()
        assertTrue("inhale v_" !in text && "P_stable" !in text, text)
        assertEquals(ModuleVerdict.FAILED, r.verdict, r.render())
        assertEquals(setOf("P_same"), r.failing("getOrCreate"), r.render())
    }

    @Test
    fun `with set-once the racy fast path is PROVEN (R2)`() {
        val f =
            AspectFixture(
                op = op("        val method = target(jp)\n        return shieldMap[method] ?: shieldMap.computeIfAbsent(method) { create(jp) }"),
                hole = AspectFixture.CREATED_ARE_MAPPED,
            )
        val r = f.verified()
        assertEquals(ModuleVerdict.PROVEN, r.verdict, r.render())
    }

    @Test
    fun `a stray remove has the set-once line, so it fails P_stable (M6)`() {
        val f = AspectFixture(members = "    fun drop(jp: Any) {\n        shieldMap.remove(target(jp))\n    }", hole = AspectFixture.CREATED_ARE_MAPPED)
        val r = f.verified()
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        val stray = "stray action drop#${f.at("shieldMap.remove").first}"
        assertEquals(setOf("P_stable"), r.failing(stray), text)
        assertEquals(Verdict.PROVEN, r.row("getOrCreate").verdict, text)
        assertTrue("default contract MI and P_stable for stray drop#" in text, text)
    }

    @Test
    fun `a section that may return a Shield other than the mapped one fails P_same`() {
        val op = "    fun getOrCreate(jp: Any): Shield? {\n        var r: Shield? = shieldMap.computeIfAbsent(target(jp)) { create(jp) }\n        if (calls > 0L) r = shared\n        return r\n    }"
        val r = AspectFixture(op = op, hole = AspectFixture.CREATED_ARE_MAPPED).verified()
        val text = r.render()
        assertEquals(ModuleVerdict.FAILED, r.verdict, text)
        assertEquals(setOf("P_same"), r.failing("getOrCreate"), text)
    }
}
