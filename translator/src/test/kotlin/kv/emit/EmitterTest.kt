package kv.emit

import kv.lower.LockFixture
import kv.sidecar.SidecarException
import kv.sidecar.parseSidecar
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The emitter of DESIGN-v0.md 5.1-5.5 and its position table (5.7), on KeyLocalLock and on construct fixtures.
 * The whole-file comparison with target.vpr is the gate in GateTest.
 */
class EmitterTest {
    @TempDir
    lateinit var dir: File

    private fun emit(variant: String) = lowerReqShield(variant).let { (m, s) -> emitBound(m, s) }

    /** The Sidecar source of the position (line, column of [fragment] on [line]). */
    private fun Emitted.sidecarAt(
        line: Int,
        fragment: String,
    ): Pair<Source.Sidecar, kv.sidecar.SPos> {
        val col = colOf(line, fragment)
        val mapped = sourceAt(line, col) ?: error("no range at $line:$col")
        val src = mapped.source as? Source.Sidecar ?: error("$line:$col maps to ${mapped.source}")
        return src to (src.expr?.posInRange(mapped.range, line, col) ?: src.pos)
    }

    @Test
    fun `the coroutine model has the methods of section 5_1 in order, each with its Kotlin compute position`() {
        val e = emit("coroutine")
        assertEquals(listOf("init", "tryLock", "unLock", "sweep", "lemma_P3c", "probe"), e.methods.map { it.name })
        assertEquals(
            listOf(MethodKind.INIT, MethodKind.ACTION, MethodKind.ACTION, MethodKind.ACTION, MethodKind.LEMMA, MethodKind.PROBE),
            e.methods.map { it.kind },
        )
        assertEquals(listOf(null, "155:9", "209:9", "97:37", null, null), e.methods.map { m -> m.kotlin?.let { "${it.line}:${it.col}" } })
        val unLock = e.lineOf("method unLock(")
        assertEquals("unLock", e.methodAt(unLock)?.name)
        assertEquals("unLock", e.methodAt(e.lineOf("res := released__204"))?.name)
        assertEquals(null, e.methodAt(e.lineOf("define MI(S)")))
    }

    @Test
    fun `a sidecar ensures maps back to its property ID and sidecar position, also on a continuation line`() {
        val e = emit("coroutine")
        val ws = Workspace(dir)
        val line = e.lineOf("ensures res != NULL ==> !old(tok(res).gIssued)")
        val (src, pos) = e.sidecarAt(line, "res != NULL")
        assertEquals("P1a", src.id)
        assertEquals(ws.at("keylocallock.common.kv", "ensures P1a:", "res != NULL"), pos.line to pos.col)
        assertTrue(pos.file.endsWith("keylocallock.common.kv"), pos.file)
        val (_, cont) = e.sidecarAt(line + 1, "tok(res).gHeld")
        assertEquals(ws.at("keylocallock.common.kv", "tok(res).gKey == k && tok(res).gHeld", "tok(res).gHeld"), cont.line to cont.col)
    }

    @Test
    fun `each hole conjunct has its own ensures, named by its comment`() {
        val e = emit("coroutine")
        val ws = Workspace(dir)
        val line = e.lineOf("ensures MI_INV_3(S)")
        val (src, pos) = e.sidecarAt(line, "MI_INV_3")
        assertEquals("MI_INV_3", src.id)
        assertEquals("The counter is never negative, and every issued token is between 0 and the counter.", src.note)
        assertEquals(ws.at("keylocallock.common.kv", "(forall qt: Int :: { tok(qt).gIssued } tok(qt).gIssued"), pos.line to pos.col)
        assertEquals(5, e.text.lines().count { it.trim().startsWith("define MI_INV_") })
        assertTrue("define MI_INV(S) (MI_INV_1(S) && MI_INV_2(S) && MI_INV_3(S) && MI_INV_4(S) && MI_INV_5(S))" in e.text)
    }

    @Test
    fun `a body statement maps to its Kotlin position`() {
        val e = emit("coroutine")
        val line = e.lineOf("existing__155.expiresAt := now + timeout")
        val src = e.sourceAt(line, e.colOf(line, "existing__155"))?.source as Source.Kotlin
        assertEquals(172 to 21, src.pos.line to src.pos.col)
        val assert = e.lineOf("assert 0 <= n_80_52b")
        assertEquals(80 to 43, (e.sourceAt(assert, e.colOf(assert, "0 <="))?.source as Source.Kotlin).pos.let { it.line to it.col })
    }

    @Test
    fun `init ensures one initial value per state field, in the three variants`() {
        for (variant in listOf("coroutine", "core", "reactor")) {
            val init = emit(variant).text.substringAfter("method init() returns (S: Ref)").substringBefore("{")
            assertEquals(listOf("ensures MI(S)", "ensures S.m == Map[Key, Ref]()", "ensures S.seq == 0"), init.lines().map { it.trim() }.filter { it.isNotEmpty() }, variant)
        }
    }

    @Test
    fun `named trusted values are parameters with the trusted contract, and a non-null token parameter gets a type fact`() {
        val e = emit("coroutine")
        assertTrue("method tryLock(S: Ref, k: Key, now: Int, timeout: Int, rej: Bool, limit: Int) returns (res: Int)" in e.text)
        val line = e.lineOf("requires rej ==> limit != 0")
        val (src, pos) = e.sidecarAt(line, "rej ==>")
        assertEquals("trusted rejectsNewEntry", src.id)
        assertEquals(Workspace(dir).at("keylocallock.common.kv", "ensures rej ==> limit != 0", "rej"), pos.line to pos.col)
        assertTrue("method unLock(S: Ref, k: Key, t: Int) returns (res: Bool)\n  requires MI(S)\n  requires t != NULL" in e.text, e.text)
        assertTrue("method sweep(S: Ref, k: Key, now: Int)\n  requires MI(S)\n  ensures MI_PERMS(S)" in e.text, e.text)
    }

    // ---- construct fixtures ----

    private fun emit(f: LockFixture): Emitted = emitBound(f.lower(dir), parseSidecar(File(dir, "Lock.kv").path, f.sidecar))

    private fun LockFixture.line(fragment: String) = at(fragment).first

    private fun body(e: Emitted): List<String> {
        check("method run(" in e.text) { "run is not emitted:\n${e.text}" }
        return e.text.substringAfter("method run(").substringAfter("\n{\n").substringBefore("\n}").lines().map { it.substringBefore("  //").trim() }
    }

    @Test
    fun `an unnamed trusted call is an arbitrary local with its contract inhaled`() {
        val call = "com.linecorp.cse.reqshield.support.config.LocalLockLimit.rejectsNewEntry(lockMap.mappingCount())"
        val f = LockFixture("if (e != null && $call) e.expiresAt = now\ne", operations = GHOST + REJECTS + LockFixture.DEFAULT_OPERATION)
        val (line, col) = f.at("com.linecorp")
        val (_, mcCol) = f.at("lockMap.mappingCount()")
        val e = emit(f)
        val body = body(e)
        val n = "n_${line}_$mcCol"
        val rej = "rej_${line}_$col"
        val limit = "limit_${line}_$col"
        assertTrue(listOf("var $n: Int", "var $rej: Bool", "var $limit: Int", "inhale $rej ==> $limit != 0") in body.windowed(4), body.joinToString("\n"))
        assertTrue("method run(S: Ref, k: Key, now: Int, timeout: Int)\n  requires MI(S)\n  ensures" in e.text, e.text)
    }

    @Test
    fun `a named trusted call under other names renames the contract`() {
        val call = "com.linecorp.cse.reqshield.support.config.LocalLockLimit.rejectsNewEntry(lockMap.mappingCount())"
        val op = "operation run = fun Lock.run {\n  free now     = param now\n  free timeout = this.timeout\n  free r, l    = call rejectsNewEntry\n}"
        val f = LockFixture("if (e != null && $call) e.expiresAt = now\ne", operations = GHOST + REJECTS + op)
        val e = emit(f)
        assertTrue("method run(S: Ref, k: Key, now: Int, timeout: Int, r: Bool, l: Int)\n  requires MI(S)\n  requires r ==> l != 0  // trusted contract" in e.text, e.text)
        assertFalse(body(e).any { it.startsWith("var r") || it.startsWith("inhale") }, body(e).joinToString("\n"))
        // The renamed text is not the sidecar text, so the range maps to the clause start, not to a column inside it.
        val line = e.lineOf("requires r ==> l != 0")
        val src = e.sourceAt(line, e.colOf(line, "r ==>"))?.source as Source.Sidecar
        assertEquals(null, src.expr)
    }

    @Test
    fun `get on a counter and on an AtomicBoolean field read the value`() {
        val f = LockFixture("if (e != null && e.isHeld.get()) e.expiresAt = counter.get()\ne", operations = GHOST + LockFixture.DEFAULT_OPERATION)
        val e = emit(f)
        val body = body(e)
        val entry = "e__${f.line("{ _, e ->")}"
        val isHeld = body.single { it.endsWith(":= $entry.isHeld") }
        assertTrue(isHeld.startsWith("var ") || isHeld.startsWith("cond_"), isHeld)
        val get = body.single { it.endsWith(": Int := S.seq") }
        val n = get.removePrefix("var ").substringBefore(":")
        assertTrue("$entry.expiresAt := $n" in body, body.joinToString("\n"))
    }

    @Test
    fun `an unsupported action is not emitted`() {
        val f = LockFixture("while (now < 0) {\n}\ne", operations = GHOST + LockFixture.DEFAULT_OPERATION)
        val e = emit(f)
        assertFalse("method run(" in e.text, e.text)
        assertEquals(listOf("init"), e.methods.map { it.name })
    }

    @Test
    fun `a module without a ghost record cannot be emitted`() {
        val f = LockFixture("e", operations = LockFixture.DEFAULT_OPERATION)
        val err = assertFailsWith<SidecarException> { emit(f) }.errors.single()
        assertEquals("emit", err.code)
        assertEquals(1 to 1, err.pos.line to err.pos.col)
        assertTrue("ghost record" in err.message, err.message)
    }

    // ---- ghost parts (M6) ----

    @Test
    fun `an allowance clause maps to its sidecar clause, and its condition column by column`() {
        val e = emit("coroutine")
        val ws = Workspace(dir)
        val tryLock = e.methods.single { it.name == "tryLock" }.range
        val line = (tryLock.line..tryLock.endLine).first { "ensures forall qt: Int :: { tok(qt).gHeld }" in e.text.lines()[it - 1] }
        val (whole, wholePos) = e.sidecarAt(line, "forall")
        assertEquals("ends_only_if P3a", whole.id)
        assertEquals(ws.at("keylocallock.common.kv", "ends_only_if P3a: now > old(tok(qt).gLease)", "ends_only_if"), wholePos.line to wholePos.col)
        val (cond, condPos) = e.sidecarAt(line + 2, "old(tok(qt).gLease)")
        assertEquals("ends_only_if P3a", cond.id)
        assertEquals(ws.at("keylocallock.common.kv", "ends_only_if P3a: now > old(tok(qt).gLease)", "old(tok(qt).gLease)"), condPos.line to condPos.col)
    }

    @Test
    fun `the set-once assert maps to the sidecar issue rule`() {
        val e = emit("coroutine")
        val line = e.lineOf("assert !tok(res).gIssued")
        val (src, pos) = e.sidecarAt(line, "!tok")
        assertEquals("issue tryLock: set-once gIssued", src.id)
        assertEquals(Workspace(dir).at("keylocallock.common.kv", "issue res {", "issue"), pos.line to pos.col)
        assertEquals("tryLock", e.methodAt(line)?.name)
    }

    @Test
    fun `an operation without allowance clauses gets the false defaults, and a stray action gets the default contract`() {
        val f = LockFixture("if (e != null) result.set(e.token)\ne", members = "    fun drop(k: String) {\n        lockMap.compute(k) { _, _ -> null }\n    }", operations = ISSUING)
        val e = emit(f)
        val ends = "ensures forall qt: Int :: { tok(qt).gHeld }\n    old(tok(qt).gIssued) && old(tok(qt).gBacked) && old(tok(qt).gHeld) && !tok(qt).gHeld ==>\n      (false)"
        val run = e.text.substringAfter("method run(").substringBefore("\n{\n")
        assertTrue(ends in run, run)
        assertTrue("ensures res != NULL && !tok(res).gBacked ==> (false)" in run, run)
        val endsLine = e.lineOf("ensures forall qt: Int :: { tok(qt).gHeld }")
        assertEquals(Source.Generated("ends_only_if default false"), e.sourceAt(endsLine, e.colOf(endsLine, "forall"))?.source)

        val strayLine = f.at("lockMap.compute(k)").first
        val stray = e.methods.single { it.kind == MethodKind.STRAY }
        assertEquals("stray_drop_$strayLine", stray.name)
        assertEquals("stray action drop#$strayLine", stray.label)
        val text = e.text.substringAfter("method ${stray.name}(").substringBefore("\n}\n")
        assertTrue(text.startsWith("S: Ref, k: Key)\n  requires MI(S)\n"), text)
        assertTrue(ends in text && "ensures HIST" in text, text)
        assertFalse("gBacked ==>" in text, "a stray action issues nothing, so it has no unbacked_only_if:\n$text")
        val body = text.substringAfter("\n{\n").lines().map { it.substringBefore("  //").trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        assertEquals(
            listOf(
                "var entry__$strayLine: Ref := has(S, k) ? S.m[k] : null",
                "var pre_tok: Int := entry__$strayLine != null ? entry__$strayLine.token : NULL",
                "S.m := S.m[k := null]",
                "if (pre_tok != NULL && !carried(S, k, pre_tok)) {",
                "tok(pre_tok).gHeld := false",
                "}",
            ),
            body,
        )
    }

    @Test
    fun `HIST and the issue assignments follow the ghost field declaration order`() {
        val ghost = "ghost record tok(Token) {\n  gKey: Key frozen\n  gHeld: Bool ends\n  gIssued: Bool set-once\n}\nwitness Info.token\n"
        val op = "operation run = fun Lock.run {\n  free now = param now\n  free timeout = this.timeout\n  result res\n  issue res { gKey = k }\n  reviewed \"fixture\"\n}"
        val e = emit(LockFixture("if (e != null) result.set(e.token)\ne", operations = ghost + op))
        assertTrue(
            "define HIST (\n  forall qt: Int :: { tok(qt).gIssued } old(tok(qt).gIssued) ==>\n    tok(qt).gKey == old(tok(qt).gKey) && (!old(tok(qt).gHeld) ==> !tok(qt).gHeld) && tok(qt).gIssued\n)" in e.text,
            e.text,
        )
        val issue = e.text.substringAfter("if (res != NULL) {\n").substringBefore("  }").lines().map { it.substringBefore("  //").trim() }.filter { it.isNotEmpty() }
        assertEquals(listOf("assert !tok(res).gIssued", "tok(res).gKey := k", "tok(res).gHeld := true", "tok(res).gIssued := true"), issue)
        // Without a field set from carried(S, k, res) there is no backed field, so ends_only_if has no backed conjunct.
        assertTrue("old(tok(qt).gIssued) && old(tok(qt).gHeld) && !tok(qt).gHeld ==>" in e.text, e.text)
    }

    @Test
    fun `unbacked_only_if without a backed field is a sidecar error`() {
        val ghost = "ghost record tok(Token) {\n  gIssued: Bool set-once\n  gKey: Key frozen\n  gHeld: Bool ends\n}\nwitness Info.token\n"
        val op = "operation run = fun Lock.run {\n  free now = param now\n  free timeout = this.timeout\n  result res\n  issue res { gKey = k }\n  reviewed \"fixture\"\n" +
            "  unbacked_only_if P3b: false\n  reviewed \"fixture\"\n}"
        val f = LockFixture("if (e != null) result.set(e.token)\ne", operations = ghost + op)
        val err = assertFailsWith<SidecarException> { emit(f) }.errors.single()
        assertEquals("emit", err.code)
        assertTrue("unbacked_only_if P3b" in err.message, err.message)
    }

    private companion object {
        const val ISSUING =
            "ghost record tok(Token) {\n  gIssued: Bool set-once\n  gKey: Key frozen\n  gBacked: Bool frozen\n  gHeld: Bool ends\n}\nwitness Info.token\n" +
                "operation run = fun Lock.run {\n  free now = param now\n  free timeout = this.timeout\n  result res\n" +
                "  issue res { gKey = k; gBacked = carried(S, k, res) }\n  reviewed \"fixture\"\n}"

        const val GHOST = "ghost record tok(Token) {\n  gIssued: Bool set-once\n  gHeld:   Bool ends\n}\nwitness Info.token\n"
        const val REJECTS =
            "trusted fun com.linecorp.cse.reqshield.support.config.LocalLockLimit.rejectsNewEntry(kotlin.Long)\n" +
                "  returns rej: Bool\n  ghost   limit: Int\n  ensures rej ==> limit != 0\n  effects none\n  reviewed \"test fixture\"\n"
    }
}
