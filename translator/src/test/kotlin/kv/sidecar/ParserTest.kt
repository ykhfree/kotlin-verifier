package kv.sidecar

import kv.kvir.GhostKind
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sidecar grammar of DESIGN-v0.md 4.2, on small texts. */
class ParserTest {
    private fun parse(text: String) = parseSidecar("t.kv", text.trimIndent())

    /** The single syntax error of [text], as `line:col message`. */
    private fun syntaxError(text: String): SidecarError {
        val e = assertFailsWith<SidecarException> { parse(text) }
        return e.errors.single().also { assertEquals("syntax", it.code) }
    }

    @Test
    fun `parses module, types, state, class and ghost record declarations`() {
        val s =
            parse(
                """
                module a.b.C.Companion   // the module object
                type Key = opaque
                type Token = template "local-${'$'}{Long}"
                state m: Map<Key, Info> = lockMap
                state seq: Counter = counter
                class Info = C.Info {
                  permits: Semaphore = semaphore
                  token:   Token?    = token
                }
                ghost record tok(Token) {
                  gIssued: Bool set-once
                  gKey:    Key  frozen
                  gHeld:   Bool ends
                }
                witness Info.token
                """,
            )
        assertEquals("a.b.C.Companion", s.modules.single().fqn.text)
        assertEquals(listOf(null, "local-\${Long}"), s.types.map { it.template })
        assertEquals(StateKind.MapOf(Name("Key", SPos("t.kv", 4, 14)), Name("Info", SPos("t.kv", 4, 19))), s.states[0].kind)
        assertEquals(StateKind.Counter, s.states[1].kind)
        val token = s.classes.single().fields[1]
        assertEquals(listOf("Token", "true", "token"), listOf(token.type.text, token.nullable.toString(), token.kname.text))
        assertEquals(listOf(GhostKind.SET_ONCE, GhostKind.FROZEN, GhostKind.ENDS), s.ghosts.single().fields.map { it.kind })
        val w = s.witnesses.single()
        assertEquals(SPos("t.kv", 15, 14), w.field.pos)
    }

    @Test
    fun `keeps a multi-line expression with its columns and stops at a line that is not indented deeper`() {
        val s =
            parse(
                """
                operation op = fun C.op {
                  ensures P1: res ==> a &&   // first line
                      b.c == k
                  ensures P2: true
                }
                invariant I: forall x: Int :: { f(x) } f(x) > 0
                lemma L: forall t: Int :: { g(t) }
                    g(t) ==> t >= 0
                """,
            )
        val p1 = s.operations.single().items[0] as EnsuresItem
        // The comment became spaces, so the continuation line keeps its columns.
        assertEquals(listOf("res ==> a &&", "      b.c == k"), p1.expr.text.lines().map { it.trimEnd() })
        assertEquals(SPos("t.kv", 2, 15), p1.expr.pos)
        // `k` sits on the continuation line: line 3, column 14.
        assertEquals(SPos("t.kv", 3, 14), p1.expr.posAt(p1.expr.text.lastIndexOf('k')))
        assertEquals("true", (s.operations.single().items[1] as EnsuresItem).expr.text)
        assertEquals("forall x: Int :: { f(x) } f(x) > 0", s.properties[0].expr.text)
        assertEquals(PropertyKind.LEMMA, s.properties[1].kind)
        assertTrue(s.properties[1].expr.text.endsWith("g(t) ==> t >= 0"))
    }

    @Test
    fun `parses free sources, issue fields and attaches reviewed notes`() {
        val op =
            parse(
                """
                operation tryLock = fun C.tryLock {
                  free now = local now
                  free timeout = this.lockTimeoutMillis
                  free t = param token
                  free rej, limit = call rejectsNewEntry
                  result res
                  issue res { gKey = k; gBacked = carried(S, k, res); gLease = now + timeout }
                  reviewed "issue read"
                  ends_only_if P3a: now > old(tok(qt).gLease)
                  reviewed "P3a read"
                  unbacked_only_if P3b: rej
                }
                """,
            ).operations.single()
        assertEquals(Selector.Fun(Name("C.tryLock", SPos("t.kv", 1, 25))), op.selector)
        assertEquals(
            listOf("Local:now", "This:lockTimeoutMillis", "Param:token", "Call:rejectsNewEntry"),
            op.free.map { "${it.source::class.simpleName}:${it.source.name.text}" },
        )
        assertEquals(listOf("rej", "limit"), op.free[3].names.map { it.text })
        val issue = op.items.filterIsInstance<IssueItem>().single()
        assertEquals(listOf("gKey" to "k", "gBacked" to "carried(S, k, res)", "gLease" to "now + timeout"), issue.fields.map { (n, v) -> n.text to v.text })
        assertEquals("issue read", issue.reviewed)
        val (p3a, p3b) = op.items.filterIsInstance<AllowanceItem>()
        assertEquals("P3a read", p3a.reviewed)
        assertNull(p3b.reviewed)
    }

    @Test
    fun `parses the abstract type of a free value after its first name`() {
        val op =
            parse(
                """
                operation unLock = fun C.unLock {
                  free t: Token = param token
                  free now = local now
                }
                """,
            ).operations.single()
        assertEquals(Name("Token", SPos("t.kv", 2, 11)), op.free[0].type)
        assertEquals(listOf("t"), op.free[0].names.map { it.text })
        assertEquals(FreeSource.Param(Name("token", SPos("t.kv", 2, 25))), op.free[0].source)
        assertNull(op.free[1].type)
        assertEquals(SPos("t.kv", 2, 11), syntaxError("operation op = fun C.op {\n  free t: = param token\n}").pos)
    }

    @Test
    fun `parses trusted functions, a hole and a probe`() {
        val s =
            parse(
                """
                trusted fun a.b.now() = clock
                  reviewed "clock"
                trusted fun a.b.L.rejects(kotlin.Long, kotlin.String?)
                  returns rej: Bool
                  ghost   limit: Int
                  ensures rej ==> limit != 0
                  effects none
                ghost record tok(Token) {
                  gHeld: Bool ends
                }
                hole MI_INV ""${'"'}
                  a && b
                ""${'"'}
                probe(k: Key, now: Int) requires now > 0 {
                  t1 := tryLock(k, now = now, rej = false)
                  assert t1 != NULL
                }
                """,
            )
        val (clock, rejects) = s.trusted
        assertTrue(clock.clock)
        assertEquals("clock", clock.reviewed)
        assertEquals(listOf("kotlin.Long", "kotlin.String?"), rejects.params.map { it.text })
        assertEquals("rejects", rejects.shortName)
        assertEquals(listOf("rej", "limit"), (rejects.returns + rejects.ghosts).map { it.name.text })
        assertEquals("rej ==> limit != 0", rejects.ensures.single().text)
        assertTrue(rejects.effectsNone)
        // The `ghost record` after the clauses is a declaration, not a ghost clause.
        assertEquals("tok", s.ghosts.single().name.text)
        assertEquals("\n  a && b\n", s.holes.single().text)
        // The hole body starts right after the opening triple quote, so hole conjuncts map back to sidecar lines.
        assertEquals(SPos("t.kv", 11, 16), s.holes.single().textPos)
        val probe = s.probes.single()
        assertEquals("now > 0", probe.requires!!.text)
        val call = probe.steps[0] as ProbeCall
        assertEquals(listOf(null, "now", "rej"), call.args.map { it.name?.text })
        assertEquals(listOf("k", "now", "false"), call.args.map { it.value.text })
        assertEquals("t1 != NULL", (probe.steps[1] as ProbeAssert).expr.text)
    }

    @Test
    fun `rejects a free-form requires in an operation`() {
        val e = syntaxError("operation op = fun C.op {\n  requires x > 0\n}")
        assertEquals(SPos("t.kv", 2, 3), e.pos)
        assertTrue("free-form 'requires'" in e.message, e.message)
    }

    @Test
    fun `reports syntax errors with their position`() {
        assertEquals(SPos("t.kv", 2, 1), syntaxError("module a.B\nfrobnicate x").pos)
        assertEquals(SPos("t.kv", 1, 8), syntaxError("source \"abc\nmodule a.B").pos)
        assertEquals(SPos("t.kv", 2, 3), syntaxError("operation op = fun C.op {\n  reviewed \"x\"\n}").pos)
        assertEquals(SPos("t.kv", 1, 29), syntaxError("trusted fun a.now() = clock returns x: Int").pos)
        assertEquals(SPos("t.kv", 1, 6), syntaxError("hole OTHER \"\"\"x\"\"\"").pos)
    }

    @Test
    fun `includes one level of files`(
        @TempDir dir: File,
    ) {
        File(dir, "common.kv").writeText("type Key = opaque\nuse jdk.Semaphore\n")
        File(dir, "main.kv").writeText("module a.B\ninclude \"common.kv\"\nsource \"B.kt\"\n")
        val s = SidecarLoader.load(File(dir, "main.kv"))
        assertEquals(listOf("ModuleDecl", "TypeDecl", "UseDecl", "SourceDecl"), s.decls.map { it::class.simpleName })
        // Positions of included declarations name the included file.
        assertTrue(s.types.single().pos.file.endsWith("common.kv"), s.types.single().pos.toString())

        File(dir, "common.kv").writeText("include \"other.kv\"\n")
        val e = assertFailsWith<SidecarException> { SidecarLoader.load(File(dir, "main.kv")) }.errors.single()
        assertEquals("include", e.code)
        assertTrue(e.pos.file.endsWith("common.kv") && e.pos.line == 1, e.toString())
    }
}
