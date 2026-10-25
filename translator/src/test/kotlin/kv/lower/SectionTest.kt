package kv.lower

import kv.kvir.ActionKind
import kv.kvir.DiagnosticKind
import kv.kvir.DispositionKind
import kv.kvir.ModuleKind
import kv.kvir.RacyGet
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import kv.kvir.walk
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DESIGN-B.md 8, B2 acceptance 2: for each construct of the B lowering, one fixture that lowers and one that does
 * not (unsupported, with its Kotlin position, or a sidecar error). The fixtures are the Kotlin shapes of the mutants
 * of DESIGN-B.md 7 on [AspectFixture]; the expected KVIR follows spike/aspect/mutants.py.
 */
class SectionTest {
    @TempDir
    lateinit var dir: File

    private var n = 0

    private fun AspectFixture.lowered() = lower(File(dir, "f${n++}"))

    private fun op(body: String) = "    fun getOrCreate(jp: Any): Shield {\n$body\n    }"

    /**
     * DESIGN-B.md 9.4 question 5: the raise at an unmodeled call of [fn] at [at] that runs after a fresh call and
     * before the action, as printed KVIR lines indented by [indent]; the statements after it follow in its else.
     */
    private fun q5Raise(
        at: Pair<Int, Int>,
        fn: String = "Aspect.target",
        indent: String = "  ",
    ): List<String> {
        val flag = "cond_${at.first}_${at.second}"
        return listOf("${indent}nondet $flag := call $fn may throw", "${indent}if $flag", "$indent  raise", "${indent}else")
    }

    /** The position of the first `target(jp)` call in the body of getOrCreate. */
    private fun AspectFixture.targetCall() = at("target(jp)", after = "fun getOrCreate")

    // ---- the original and its action ----

    @Test
    fun `the original computeIfAbsent is the action, the fresh call inside it may throw`() {
        val f = AspectFixture()
        val m = f.lowered()
        assertSupportedOp(m)
        assertEquals(ModuleKind.INSTANCE, m.kind)
        assertEquals(ActionKind.COMPUTE_IF_ABSENT, m.getOrCreate.action.kind)
        val r = "r_${f.at("create(jp) }").first}_${f.at("create(jp) }").second}"
        assertEquals(listOf("run action getOrCreate#${f.at("shieldMap.computeIfAbsent").first}", "return out_${f.at("shieldMap.computeIfAbsent").let { "${it.first}_${it.second}" }}"), m.sectionShape)
        assertEquals(listOf("nondet thrown := Aspect.create", "if thrown", "  raise", "else", "  fresh $r: Shield := create", "  exit put($r)"), m.actionShape)
    }

    // ---- elvis ----

    @Test
    fun `elvis with an effect on the right branches on the left value (R2 fast path)`() {
        val f = AspectFixture(op = op("        val method = target(jp)\n        return shieldMap[method] ?: shieldMap.computeIfAbsent(method) { create(jp) }"))
        val m = f.lowered()
        assertSupportedOp(m)
        val v = f.at("shieldMap[method]").let { "v_${it.first}_${it.second}" }
        val out = f.at("shieldMap.computeIfAbsent").let { "out_${it.first}_${it.second}" }
        assertEquals(
            listOf("racy $v: Shield? := shieldMap.get(k) [set-once contract]", "if $v != null", "  return $v", "else", "  run action getOrCreate#${f.at("shieldMap.computeIfAbsent").first}", "  return $out"),
            m.sectionShape,
        )
    }

    @Test
    fun `elvis with a pure right side is one value (M4)`() {
        val f = AspectFixture(op = op("        val created = create(jp)\n        return shieldMap.putIfAbsent(target(jp), created) ?: created"))
        val m = f.lowered()
        assertSupportedOp(m)
        val r = f.at("create(jp)", after = "fun getOrCreate").let { "r_${it.first}_${it.second}" }
        val cur = f.at("shieldMap.putIfAbsent").let { "cur_${it.first}_${it.second}" }
        // The key argument target(jp) runs after the fresh call: it may throw and drop the object (question 5).
        assertEquals(
            listOf("nondet thrown := Aspect.create", "if thrown", "  raise", "else", "  fresh $r: Shield := create") + q5Raise(f.targetCall()) +
                listOf("    run action getOrCreate#${f.at("shieldMap.putIfAbsent").first}", "    return $cur ?: $r"),
            m.sectionShape,
        )
        assertEquals(ActionKind.PUT_IF_ABSENT, m.getOrCreate.action.kind)
        assertEquals(r, (m.getOrCreate.action.value as kv.kvir.Var).local.name)
    }

    @Test
    fun `elvis whose right side throws is unsupported`() {
        val f = AspectFixture(op = op("        shieldMap.computeIfAbsent(target(jp)) { create(jp) }\n        return shieldMap[target(jp)] ?: throw IllegalStateException(\"none\")"))
        assertUnsupportedOp(f.lowered(), "throw", f.at("throw IllegalStateException"))
    }

    // ---- !! ----

    @Test
    fun `not-null assertion on the action result raises on null (R1)`() {
        val f = AspectFixture(op = "    fun getOrCreate(jp: Any): Shield =\n        shieldMap.compute(target(jp)) { _, old -> old ?: create(jp) }!!")
        val m = f.lowered()
        assertSupportedOp(m)
        val out = f.at("shieldMap.compute").let { "out_${it.first}_${it.second}" }
        assertEquals(listOf("run action getOrCreate#${f.at("shieldMap.compute").first}", "if $out == null", "  raise", "else", "  return $out"), m.sectionShape)
        val old = "old__${f.at("old ->").first}"
        val r = f.at("create(jp) }").let { "r_${it.first}_${it.second}" }
        assertEquals(listOf("if $old != null", "  exit keep", "else", "  nondet thrown := Aspect.create", "  if thrown", "    raise", "  else", "    fresh $r: Shield := create", "    exit put($r)"), m.actionShape)
    }

    @Test
    fun `not-null assertion inside an action is unsupported`() {
        val f =
            AspectFixture(
                op = "    fun getOrCreate(jp: Any): Shield =\n        shieldMap.computeIfAbsent(target(jp)) { maybe(jp)!! }",
                members = "    private fun maybe(jp: Any): Shield? = null",
            )
        assertUnsupportedOp(f.lowered(), "!!", f.at("maybe(jp)!!"))
    }

    // ---- also and let ----

    @Test
    fun `also with an exactly-once lambda is inlined, a get then a put (M1)`() {
        val f = AspectFixture(op = op("        val method = target(jp)\n        return shieldMap[method] ?: create(jp).also { shieldMap[method] = it }"))
        val m = f.lowered()
        assertSupportedOp(m)
        val v = f.at("shieldMap[method] ?:").let { "v_${it.first}_${it.second}" }
        val r = f.at("create(jp).also").let { "r_${it.first}_${it.second}" }
        assertEquals(
            listOf(
                "racy $v: Shield? := shieldMap.get(k) [set-once contract]", "if $v != null", "  return $v", "else",
                "  nondet thrown := Aspect.create", "  if thrown", "    raise", "  else", "    fresh $r: Shield := create",
                "    run action getOrCreate#${f.at("shieldMap[method] = it").first}", "    return $r",
            ),
            m.sectionShape,
        )
        assertEquals(ActionKind.PUT, m.getOrCreate.action.kind)
    }

    @Test
    fun `let binds its parameter to the receiver and returns the lambda value`() {
        val f = AspectFixture(op = op("        return create(jp).let { s -> shieldMap.putIfAbsent(target(jp), s) ?: s }"))
        val m = f.lowered()
        assertSupportedOp(m)
        val r = f.at("create(jp).let").let { "r_${it.first}_${it.second}" }
        assertEquals("    return cur_${f.at("shieldMap.putIfAbsent").let { "${it.first}_${it.second}" }} ?: $r", m.sectionShape.last())
    }

    @Test
    fun `apply and run bind this to the receiver`() {
        val apply = AspectFixture(op = op("        return create(jp).apply { shieldMap.putIfAbsent(target(jp), this) }"))
        val m1 = apply.lowered()
        assertSupportedOp(m1)
        val r1 = apply.at("create(jp).apply").let { "r_${it.first}_${it.second}" }
        assertEquals(r1, (m1.getOrCreate.action.value as kv.kvir.Var).local.name)
        assertEquals("    return $r1", m1.sectionShape.last())
        val run = AspectFixture(op = op("        return create(jp).run { shieldMap.putIfAbsent(target(jp), this) ?: this }"))
        val m2 = run.lowered()
        assertSupportedOp(m2)
        val r2 = run.at("create(jp).run").let { "r_${it.first}_${it.second}" }
        assertEquals("    return cur_${run.at("shieldMap.putIfAbsent").let { "${it.first}_${it.second}" }} ?: $r2", m2.sectionShape.last())
    }

    @Test
    fun `an operation without a result whose function ends without return returns no value`() {
        val f =
            AspectFixture(
                op = "    fun getOrCreate(jp: Any) {\n        shieldMap.putIfAbsent(target(jp), create(jp))\n    }",
                operations = "operation getOrCreate = fun Aspect.getOrCreate {\n  free thrown = call create\n}",
            )
        val m = f.lowered()
        assertSupportedOp(m)
        assertEquals(null, m.getOrCreate.result)
        assertEquals("  return", m.sectionShape.last())
    }

    @Test
    fun `also with a function reference is unsupported`() {
        val f = AspectFixture(op = op("        return create(jp).also(::keep).let { shieldMap.computeIfAbsent(target(jp)) { _ -> it } }"), members = "    private fun keep(s: Shield) {}")
        assertUnsupportedOp(f.lowered(), "also with a function value", f.at("create(jp).also"))
    }

    // ---- early return ----

    @Test
    fun `an early return moves the rest of the function into the else branch`() {
        val f = AspectFixture(op = op("        val method = target(jp)\n        val v = shieldMap[method]\n        if (v != null) return v\n        return shieldMap.computeIfAbsent(method) { create(jp) }"))
        val m = f.lowered()
        assertSupportedOp(m)
        val v = f.at("shieldMap[method]").let { "v_${it.first}_${it.second}" }
        assertEquals(listOf("racy $v: Shield? := shieldMap.get(k) [set-once contract]", "if $v != null", "  return $v", "else", "  run action getOrCreate#${f.at("shieldMap.computeIfAbsent").first}", "  return out_${f.at("shieldMap.computeIfAbsent").let { "${it.first}_${it.second}" }}"), m.sectionShape)
    }

    @Test
    fun `a statement that assigns a local the result reads is in the section`() {
        val op = "    fun getOrCreate(jp: Any): Shield? {\n        var r: Shield? = shieldMap.computeIfAbsent(target(jp)) { create(jp) }\n        if (calls > 0L) r = shared\n        return r\n    }"
        val m = AspectFixture(op = op).lowered()
        assertSupportedOp(m)
        assertTrue(m.sectionShape.any { it.startsWith("if ") } && m.sectionShape.any { "shared" in it }, m.getOrCreateText)
    }

    @Test
    fun `a statement that assigns a local the result does not read stays in the ledger`() {
        val f = AspectFixture(op = op("        var n = 0L\n        if (calls > 0L) n = 1L\n        return shieldMap.computeIfAbsent(target(jp)) { create(jp) }"))
        val m = f.lowered()
        assertSupportedOp(m)
        assertEquals(listOf("run action getOrCreate#${f.at("shieldMap.computeIfAbsent").first}", "return out_${f.at("shieldMap.computeIfAbsent").let { "${it.first}_${it.second}" }}"), m.sectionShape)
    }

    @Test
    fun `a return inside a loop of the section is unsupported`() {
        val f = AspectFixture(op = op("        while (true) {\n            return shieldMap.computeIfAbsent(target(jp)) { create(jp) }\n        }"))
        assertUnsupportedOp(f.lowered(), "loop", f.at("while (true)"))
    }

    // ---- getOrPut ----

    @Test
    fun `getOrPut is a racy read, the default value outside any action, and a putIfAbsent action (M3)`() {
        val f = AspectFixture(op = "    fun getOrCreate(jp: Any): Shield =\n        shieldMap.getOrPut(target(jp)) { create(jp) }")
        val m = f.lowered()
        assertSupportedOp(m)
        val at = f.at("shieldMap.getOrPut").let { "${it.first}_${it.second}" }
        val r = f.at("create(jp) }").let { "r_${it.first}_${it.second}" }
        assertEquals(
            listOf(
                "racy v_$at: Shield? := shieldMap.get(k) [set-once contract]", "if v_$at != null", "  return v_$at", "else",
                "  nondet thrown := Aspect.create", "  if thrown", "    raise", "  else", "    fresh $r: Shield := create",
                "    run action getOrCreate#${f.at("shieldMap.getOrPut").first}", "    return cur_$at ?: $r",
            ),
            m.sectionShape,
        )
        assertEquals(ActionKind.PUT_IF_ABSENT, m.getOrCreate.action.kind)
    }

    @Test
    fun `getOrPut without the getOrPut model is no action, so the operation and the module are incomplete`() {
        val f = AspectFixture(op = "    fun getOrCreate(jp: Any): Shield =\n        shieldMap.getOrPut(target(jp)) { create(jp) }", uses = AspectFixture.DEFAULT_USES.replace("use kotlin.collections.getOrPut\n", "").removeSuffix("use kotlin.collections.getOrPut"))
        val m = f.lowered()
        assertTrue(m.incomplete)
        assertTrue(m.diagnostics.any { it.kind == DiagnosticKind.UNSUPPORTED && "0 action calls" in it.message }, m.diagnostics.toString())
        assertTrue(m.ledger.any { it.kind == DispositionKind.UNSUPPORTED && it.pos.line to it.pos.col == f.at("shieldMap.getOrPut") }, m.ledger.toString())
        assertEquals(emptyList(), checkWellFormed(m))
    }

    // ---- set, putIfAbsent, remove ----

    @Test
    fun `m(k) = v is a put action whose value the section computes (M2)`() {
        val f = AspectFixture(op = op("        val created = create(jp)\n        shieldMap[target(jp)] = created\n        return created"))
        val m = f.lowered()
        assertSupportedOp(m)
        val r = f.at("create(jp)", after = "fun getOrCreate").let { "r_${it.first}_${it.second}" }
        assertEquals(
            listOf("nondet thrown := Aspect.create", "if thrown", "  raise", "else", "  fresh $r: Shield := create") + q5Raise(f.targetCall()) +
                listOf("    run action getOrCreate#${f.at("shieldMap[target(jp)]").first}", "    return $r"),
            m.sectionShape,
        )
        assertEquals(ActionKind.PUT, m.getOrCreate.action.kind)
        assertEquals(null, m.getOrCreate.action.out, "the value of m[k] = v is Unit")
    }

    @Test
    fun `m(k) = v without the put model is no action, so the module is incomplete`() {
        val f = AspectFixture(op = op("        val created = create(jp)\n        shieldMap[target(jp)] = created\n        return created"), uses = AspectFixture.DEFAULT_USES.replace("use jdk.ConcurrentHashMap.put\n", ""))
        val m = f.lowered()
        assertTrue(m.incomplete)
        assertTrue(m.ledger.any { it.kind == DispositionKind.UNSUPPORTED && it.pos.line to it.pos.col == f.at("shieldMap[target(jp)]") }, m.ledger.toString())
    }

    @Test
    fun `putIfAbsent whose result is unused is an action without out (M7)`() {
        val f = AspectFixture(op = op("        val created = create(jp)\n        shieldMap.putIfAbsent(target(jp), created)\n        return created"))
        val m = f.lowered()
        assertSupportedOp(m)
        assertEquals(null, m.getOrCreate.action.out)
        assertEquals("    return ${f.at("create(jp)", after = "fun getOrCreate").let { "r_${it.first}_${it.second}" }}", m.sectionShape.last())
    }

    @Test
    fun `putIfAbsent of an object the section constructs is unsupported`() {
        val f = AspectFixture(op = op("        return shieldMap.putIfAbsent(target(jp), Shield(\"x\")) ?: shared"))
        assertUnsupportedOp(f.lowered(), "Shield constructor", f.at("Shield(\"x\")"))
    }

    @Test
    fun `remove outside every operation is a stray remove action (M6)`() {
        val f = AspectFixture(members = "    fun drop(jp: Any) {\n        shieldMap.remove(target(jp))\n    }")
        val m = f.lowered()
        assertSupportedOp(m)
        val stray = m.strayActions.single()
        assertEquals(ActionKind.REMOVE, stray.kind)
        // DESIGN-B.md 5.4 rule 5: a stray action keeps every set-once line; its map carries the declaration.
        assertEquals("P_stable", stray.map.setOnce)
        assertTrue(stray.map in m.state)
        assertEquals("drop#${f.at("shieldMap.remove").first}", stray.id)
        assertEquals(emptyList(), stray.body)
    }

    @Test
    fun `a stray putIfAbsent whose value constructs a Shield is unsupported, so P_once cannot miss it`() {
        val f = AspectFixture(members = "    fun stash(jp: Any) {\n        shieldMap.putIfAbsent(target(jp), Shield(prefix))\n    }")
        val m = f.lowered()
        // The value runs before the action, so the audit sees the constructor (DESIGN-B.md 3.3 rule 4).
        assertTrue(m.ledger.any { it.kind == DispositionKind.UNSUPPORTED && "Shield constructor outside" in it.what && it.pos.line to it.pos.col == f.at("Shield(prefix)") }, m.ledger.joinToString("\n"))
        assertTrue(m.incomplete)
        assertEquals(emptyList(), checkWellFormed(m))
    }

    @Test
    fun `a stray putIfAbsent of an instance val adds nothing to the ledger`() {
        val f = AspectFixture(members = "    fun stash(jp: Any) {\n        shieldMap.putIfAbsent(target(jp), shared)\n    }")
        val m = f.lowered()
        assertSupportedOp(m)
        assertEquals(ActionKind.PUT_IF_ABSENT, m.strayActions.single().kind)
        assertTrue(!m.incomplete, m.ledger.joinToString("\n"))
    }

    @Test
    fun `remove of a key and a value has no model and is unsupported outside an action`() {
        val f = AspectFixture(members = "    fun drop(jp: Any) {\n        shieldMap.remove(target(jp), shared)\n    }")
        val m = f.lowered()
        assertTrue(m.incomplete)
        assertEquals(emptyList(), m.strayActions)
        assertTrue(m.ledger.any { it.kind == DispositionKind.UNSUPPORTED && it.pos.line to it.pos.col == f.at("shieldMap.remove") }, m.ledger.toString())
    }

    // ---- fresh calls ----

    @Test
    fun `a value-class constructor inside an action is unsupported`() {
        val f = AspectFixture(op = "    fun getOrCreate(jp: Any): Shield =\n        shieldMap.computeIfAbsent(target(jp)) { Shield(\"x\") }")
        assertUnsupportedOp(f.lowered(), "Shield constructor", f.at("Shield(\"x\")"))
    }

    @Test
    fun `a fresh call the section does not translate is unsupported, so P_once cannot miss it`() {
        // In the receiver of an unknown call: the section lowers only the arguments of such a call.
        val receiver = AspectFixture(op = op("        create(jp).hashCode()\n        return shieldMap.computeIfAbsent(target(jp)) { create(jp) }"))
        assertUnsupportedOp(receiver.lowered(), "call create(jp)", receiver.at("create(jp).hashCode"))
        // In a lambda the section does not inline.
        val lambda = AspectFixture(op = op("        listOf(jp).forEach { create(it) }\n        return shieldMap.computeIfAbsent(target(jp)) { create(jp) }"))
        assertUnsupportedOp(lambda.lowered(), "nested lambda", lambda.at("{ create(it) }"))
    }

    @Test
    fun `a fresh call whose value is unused is still recorded`() {
        val f = AspectFixture(op = op("        create(jp)\n        return shieldMap.computeIfAbsent(target(jp)) { create(jp) }"))
        val m = f.lowered()
        assertSupportedOp(m)
        val r = f.at("create(jp)", after = "fun getOrCreate").let { "r_${it.first}_${it.second}" }
        assertEquals(
            listOf("nondet thrown := Aspect.create", "if thrown", "  raise", "else", "  fresh $r: Shield := create") + q5Raise(f.targetCall()) +
                "    run action getOrCreate#${f.at("shieldMap.computeIfAbsent").first}",
            m.sectionShape.take(10),
        )
        // The sidecar names the first call; the call inside the action has an unnamed flag.
        assertTrue(m.actionShape.first().startsWith("nondet cond_"), m.actionShape.toString())
    }

    // ---- throws ----

    @Test
    fun `a try around a throwing call inside an action is unsupported`() {
        val f = AspectFixture(op = "    fun getOrCreate(jp: Any): Shield =\n        shieldMap.computeIfAbsent(target(jp)) { try { create(jp) } catch (e: IllegalArgumentException) { shared } }")
        assertUnsupportedOp(f.lowered(), "try expression", f.at("try {"))
    }

    @Test
    fun `a try around a throwing call in the section is unsupported`() {
        val f = AspectFixture(op = op("        val created = try { create(jp) } catch (e: IllegalArgumentException) { shared }\n        return shieldMap.putIfAbsent(target(jp), created) ?: created"))
        assertUnsupportedOp(f.lowered(), "try expression", f.at("try {"))
    }

    // ---- racy reads ----

    @Test
    fun `a racy read has the set-once contract only for a set-once map and only before the action`() {
        val shape = op("        val method = target(jp)\n        val r = shieldMap.computeIfAbsent(method) { create(jp) }\n        val v = shieldMap[method]\n        return v ?: r")
        val before = op("        val method = target(jp)\n        return shieldMap[method] ?: shieldMap.computeIfAbsent(method) { create(jp) }")
        fun contracts(f: AspectFixture) = f.lowered().also(::assertSupportedOp).getOrCreate.body.walk().filterIsInstance<RacyGet>().map { it.contract }.toList()
        assertEquals(listOf(true), contracts(AspectFixture(op = before)))
        assertEquals(listOf(false), contracts(AspectFixture(op = before, setOnce = "")), "no set-once: an arbitrary value (DESIGN-v0.md 2.12)")
        assertEquals(listOf(false), contracts(AspectFixture(op = shape)), "after the action: an arbitrary value (DESIGN-B.md 2.4 rule 3)")
    }

    // ---- trusted bodies (DESIGN-B.md 3.3) ----

    private fun trustedErrors(factory: String): List<kv.sidecar.SidecarError> = AspectFixture(factory = factory).compile(File(dir, "t${n++}")).sidecarErrors

    @Test
    fun `a trusted body that reads module state, calls an operation or returns no new object is a sidecar error`() {
        val state = trustedErrors("    private fun create(jp: Any): Shield {\n        shieldMap.clear()\n        return Shield(\"x\")\n    }")
        assertEquals(listOf("trusted-body"), state.map { it.code }, state.toString())
        assertTrue("reads state m" in state.single().message, state.single().message)
        val op = trustedErrors("    private fun create(jp: Any): Shield {\n        getOrCreate(jp)\n        return Shield(\"x\")\n    }")
        assertTrue(op.single().code == "trusted-body" && "calls the operation function getOrCreate" in op.single().message, op.toString())
        val shared = trustedErrors("    private fun create(jp: Any): Shield {\n        return shared\n    }")
        assertTrue(shared.single().code == "trusted-body" && "returns shared, which is not a new Shield" in shared.single().message, shared.toString())
        val local = trustedErrors("    private fun create(jp: Any): Shield {\n        val s = Shield(\"x\")\n        return s\n    }")
        assertEquals(emptyList(), local, "a local val that only a constructor call initializes")
    }

    @Test
    fun `the trusted body is in the ledger as trusted`() {
        val f = AspectFixture()
        val m = f.lowered()
        assertTrue(m.ledger.any { it.kind == DispositionKind.TRUSTED && it.pos.line to it.pos.col == f.at("private fun create") }, m.ledger.toString())
    }

    // ---- value-class constructors outside the fresh function (DESIGN-B.md 3.3 rule 4) ----

    @Test
    fun `a value-class constructor outside the fresh function, in the module file or another audited file, is unsupported`() {
        val inModule = AspectFixture(members = "    fun spare(): Shield = Shield(\"spare\")")
        val m1 = inModule.lowered()
        assertTrue(m1.incomplete)
        assertTrue(m1.ledger.any { it.kind == DispositionKind.UNSUPPORTED && it.pos.line to it.pos.col == inModule.at("Shield(\"spare\")") }, m1.ledger.toString())
        val other = AspectFixture(otherFiles = mapOf("Other.kt" to "package fixture\n\nfun spare(): Shield = Shield(\"other\")\n"))
        val m2 = other.lowered()
        assertTrue(m2.incomplete)
        val u = m2.ledger.filter { it.kind == DispositionKind.UNSUPPORTED }
        assertTrue(u.isNotEmpty() && u.all { it.pos.file.endsWith("Other.kt") && it.pos.line == 3 && "Shield constructor outside" in it.what }, u.toString())
        assertEquals(emptyList(), checkWellFormed(m2))
    }

    // ---- the audit of an internal state field (DESIGN-B.md 2.1 rule 6) ----

    @Test
    fun `another source file that writes an internal state map makes the module incomplete, and a private map audits only its file`() {
        val writer = "package fixture\n\nfun wipe(a: Aspect) {\n    a.shieldMap.clear()\n}\n"
        val m = AspectFixture(otherFiles = mapOf("Writer.kt" to writer)).lowered()
        assertTrue(m.incomplete)
        val u = m.ledger.filter { it.kind == DispositionKind.UNSUPPORTED }
        assertTrue(u.isNotEmpty() && u.all { it.pos.file.endsWith("Writer.kt") }, u.toString())
        assertTrue(u.any { "state of another Aspect instance" in it.what }, u.toString())
        val private = AspectFixture(mapDecl = "private val shieldMap = ConcurrentHashMap<Method, Shield>()", otherFiles = mapOf("Other.kt" to "package fixture\n\nfun spare(): Int = 1\n")).lowered()
        assertTrue(private.ledger.none { it.pos.file.endsWith("Other.kt") }, "a private field: only the module file is audited")
        assertTrue(!private.incomplete)
    }

    // ---- two actions ----

    @Test
    fun `an operation with two action calls is unsupported`() {
        val f = AspectFixture(op = op("        val created = create(jp)\n        shieldMap.putIfAbsent(target(jp), created)\n        return shieldMap.computeIfAbsent(target(jp)) { create(jp) }"))
        val m = f.lowered()
        assertTrue(m.incomplete)
        assertTrue(m.diagnostics.any { it.kind == DiagnosticKind.UNSUPPORTED && "2 action calls" in it.message }, m.diagnostics.toString())
        assertEquals(emptyList(), checkWellFormed(m))
    }

    // ---- instance module values ----

    @Test
    fun `a var of the instance is a free value of an instance module (DESIGN-B 2_1 rule 4)`() {
        val f = AspectFixture(op = "    fun getOrCreate(jp: Any): Shield =\n        shieldMap.computeIfAbsent(target(jp)) { if (calls > 0L) shared else create(jp) }")
        val m = f.lowered()
        assertSupportedOp(m)
        assertTrue(m.getOrCreate.action.free.any { it.kotlin == "this.calls" && it.origin is kv.kvir.InstanceVal }, m.getOrCreateText)
    }

    @Test
    fun `an object of the value class from outside the action may be mapped, not reached into (M5)`() {
        val f = AspectFixture(op = "    fun getOrCreate(jp: Any): Shield =\n        shieldMap.computeIfAbsent(target(jp)) { shared }")
        val m = f.lowered()
        assertSupportedOp(m)
        assertEquals(listOf("exit put(shared__${f.at("{ shared }").first})"), m.actionShape)
        // No call of create: the named flag is a parameter the code does not use (DESIGN-B.md 5.3 rule 6).
        val info = m.diagnostics.single { it.kind == DiagnosticKind.INFO }
        assertTrue("makes no call to create" in info.message, info.message)
        assertTrue(m.getOrCreate.action.free.any { it.sidecarName == "thrown" })
    }
}
