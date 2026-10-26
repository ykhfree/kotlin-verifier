package kv.sidecar

import kv.client.ClientFixture
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DESIGN-E 6.2 and 6.10 E1: the client sidecar grammar (module client, enum, obligation, trusted value, named trusted
 * parameters, creates/consumes, throws kinds, trace, hole loop, assume, guard, `this.` paths) and its checks.
 */
class ClientGrammarTest {
    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")

    private fun parse(text: String) = parseSidecar("client.kv", text)

    /** The check errors of [text] read as a draft (DESIGN-C.md 7.1) or as signed. */
    private fun check(
        text: String,
        draft: Boolean = true,
    ): List<SidecarError> = checkSidecar(parse(text), draft)

    private fun codes(errors: List<SidecarError>) = errors.map { it.code }

    @Test
    fun `parses every client declaration of the fixture sidecar`() {
        val s =
            parse(
                ClientFixture.sidecar(
                    """
                    free mode = this.config.mode
                    free k2: Key = param key
                    result res
                    guard W: Config.set requires holds(Lease(k, CREATE, _)) || mode == ONLY_UPDATE
                      source none "fixture"
                    reviewed "fixture reviewer"
                    ensures R: exc == NONE ==> res != null
                    """,
                    """
                    trace {
                      first  = first Config.get
                      held   = Config.get while holds(Lease(k, CREATE, _))
                      reads  = count Config.get where !holds(Lease(k, CREATE, _))
                      last   = last Callable.call where true
                      fails  = trailing failures of reads
                    }
                    assume A1: errors-are-fatal
                      source none "fixture decision"
                    hole loop Client.run@14 ""${'"'}
                      true
                    ""${'"'}
                    """.trimIndent(),
                ),
            )
        assertTrue(s.client)
        assertEquals(listOf("Kind" to "Kind", "Mode" to "Mode"), s.enums.map { it.name.text to it.kclass.text })
        assertEquals(listOf("Key", "Kind", "Token"), s.obligations.single().types.map { it.text })
        val tryLock = s.trusted.single { it.shortName == "tryLock" }
        assertEquals(listOf("key" to "Key", "kind" to "Kind"), tryLock.named.map { it!!.name.text to it.type.text })
        assertEquals(listOf("t" to "Token?"), tryLock.returns.map { it.name.text to it.type.text })
        assertEquals(listOf("key", "kind", "t"), tryLock.creates!!.args.map { it.text })
        assertEquals("t", tryLock.creates!!.ifNotNull!!.text)
        assertEquals(listOf("RTE"), tryLock.throwKinds.map { it.text })
        assertTrue(tryLock.throws)
        assertEquals(listOf("key", "kind", "token"), s.trusted.single { it.shortName == "unLock" }.consumes!!.args.map { it.text })
        val set = s.trusted.single { it.shortName == "set" }
        assertTrue(set.value)
        // A bare Kotlin type is a parameter the model drops; a named one is passed.
        assertEquals(listOf("key", "data", null), set.named.map { it?.name?.text })
        assertEquals(listOf("Key", "Data", "kotlin.Long"), set.params.map { it.text })
        assertEquals(listOf("RTE", "CHK"), set.throwKinds.map { it.text })
        // `trusted fun ...decide(kotlin.Long, kotlin.Long)` is the object-module form: no named parameters.
        assertEquals(emptyList(), s.trusted.single { it.shortName == "decide" }.named)
        val events = s.traces.single().events
        assertEquals(EventForm.entries.toList(), events.map { it.form })
        assertEquals(listOf("Config.get", "Config.get", "Config.get", "Callable.call", null), events.map { it.call?.text })
        assertEquals(listOf("Lease", "k", "CREATE", "_"), events[1].holds!!.let { listOf(it.obligation.text) + it.args.map { a -> a.text } })
        assertEquals("!holds(Lease(k, CREATE, _))", events[2].where!!.text)
        assertEquals("reads", events[4].of!!.text)
        val hole = s.holes.single()
        assertEquals("Client.run" to 14, hole.loop!!.fn.text to hole.loop!!.line)
        val assume = s.assumes.single()
        assertEquals("A1" to "errors-are-fatal", assume.id.text to assume.name.text)
        assertEquals(listOf("source none \"fixture decision\""), assume.sources.map { it.text })
        val op = s.operations.single()
        assertEquals("config.mode", (op.free[0].source as FreeSource.This).name.text)
        val guard = op.items.filterIsInstance<GuardItem>().single()
        assertEquals("W" to "Config.set", guard.id.text to guard.call.text)
        assertEquals("fixture reviewer", guard.reviewed)
        assertEquals(listOf("source none \"fixture\""), guard.sources.map { it.text })
        assertEquals("guard W of run", s.intentClauses().map { it.first }.single { it.startsWith("guard") })
        assertEquals("assume A1", s.intentClauses().map { it.first }.single { it.startsWith("assume") })
    }

    @Test
    fun `the draft core sidecar parses and passes the checks only as a draft`() {
        val s = SidecarLoader.load(File(specDir, "reqshield.core.kv"))
        assertEquals(emptyList(), checkSidecar(s, draft = true))
        // DESIGN-E 2.5: 12 intent clauses and 4 assumptions to sign, each with a source line, none signed yet.
        val clauses = s.intentClauses()
        assertEquals(16, clauses.size, clauses.map { it.first }.toString())
        assertEquals(12, clauses.count { it.second !is AssumeDecl })
        assertTrue(clauses.all { it.second.sources.isNotEmpty() && it.second.reviewed == null })
        // Signed reading: each of the 16 clauses and each of the 6 trusted declarations needs its note.
        val signed = checkSidecar(s, draft = false).filter { it.code == "unreviewed" }
        assertEquals(22, signed.size, signed.joinToString("\n"))
        assertEquals(6, signed.count { it.message.startsWith("trusted") })
    }

    @Test
    fun `a consumes or creates clause names a declared obligation with its arity`() {
        val errors =
            check(
                ClientFixture.sidecar().replace("consumes Lease(key, kind, token)", "consumes Lock(key, kind, token)")
                    .replace("creates Lease(key, kind, t)", "creates Lease(key, t)"),
            )
        assertEquals(listOf("obligation-arity", "unknown-obligation"), codes(errors), errors.joinToString("\n"))
        assertTrue("'Lock', which is not an obligation" in errors[1].message, errors[1].message)
        assertTrue("gives Lease 2 argument(s); it takes 3" in errors[0].message, errors[0].message)
    }

    @Test
    fun `an obligation argument names a parameter or a result, and a creation condition a nullable result`() {
        val errors =
            check(
                ClientFixture.sidecar().replace("consumes Lease(key, kind, token)", "consumes Lease(key, kind, other)")
                    .replace("returns t: Token? creates Lease(key, kind, t) if t != null", "returns t: Token creates Lease(key, kind, t) if t != null"),
            )
        assertEquals(listOf("creates-condition", "unknown-name"), codes(errors), errors.joinToString("\n"))
        assertTrue("'other', which is neither a named parameter" in errors[1].message, errors[1].message)
    }

    @Test
    fun `an unknown exception kind is an error`() {
        val errors = check(ClientFixture.sidecar().replace("returns d: Data? throws RTE, CHK", "returns d: Data? throws RTE, IOException"))
        assertEquals(listOf("unknown-exception-kind"), codes(errors), errors.joinToString("\n"))
        assertTrue("throws 'IOException'" in errors.single().message && "REE, RTE, CHK, INTR" in errors.single().message, errors.single().message)
        // An Error is A1's, not a kind a trusted call lists.
        assertEquals(listOf("unknown-exception-kind"), codes(check(ClientFixture.sidecar().replace("throws RTE, CHK, INTR", "throws RTE, ERR"))))
    }

    @Test
    fun `an unknown assumption name or ID is an error, and a known one needs its own name`() {
        val sidecar = ClientFixture.sidecar(extra = "assume A3: accepted-tasks-run\n  source none \"x\"\nassume A9: tasks-are-fast\n  source none \"x\"\nassume A4: errors-are-fatal\n  source none \"x\"\n")
        val errors = check(sidecar)
        assertEquals(listOf("unknown-assumption", "unknown-assumption"), codes(errors), errors.joinToString("\n"))
        assertTrue("no built-in assumption A9" in errors[0].message, errors[0].message)
        assertTrue("A4 is 'throwing-release-ends-obligation', not 'errors-are-fatal'" in errors[1].message, errors[1].message)
    }

    @Test
    fun `under review intent an assumption needs a source line, and a signed one a note`() {
        val sidecar = "review intent\n" + ClientFixture.sidecar(extra = "assume A1: errors-are-fatal\n")
        assertEquals(listOf("unsourced"), codes(check(sidecar)))
        assertEquals(setOf("unsourced", "unreviewed"), codes(check(sidecar, draft = false)).toSet())
    }

    @Test
    fun `trace events and guards name a trusted declaration or a client model`() {
        val errors =
            check(
                ClientFixture.sidecar(
                    "result res\nguard G: Config.put requires true",
                    "trace {\n  n = count Lock.tryLock\n  m = count nowhere\n  s = count CompletableFuture.runAsync\n  f = trailing failures of s2\n}\n",
                ),
            )
        assertEquals(listOf("unknown-call", "unknown-name", "unknown-call"), codes(errors), errors.joinToString("\n"))
        assertTrue("'nowhere'" in errors[0].message && "'s2', which is not an earlier count field" in errors[1].message, errors.joinToString("\n"))
        assertTrue("guard G names 'Config.put'" in errors[2].message, errors[2].message)
    }

    @Test
    fun `holds names an obligation in a guard, an ensures and a trace condition`() {
        val errors = check(ClientFixture.sidecar("result res\nensures E: holds(Lock(k, CREATE, _))", "trace {\n  r = count Config.get where holds(Leash(k, CREATE, _))\n}\n"))
        assertEquals(listOf("unknown-obligation", "unknown-obligation"), codes(errors), errors.joinToString("\n"))
    }

    @Test
    fun `a client draft may leave trusted declarations unsigned, and a signed reading may not`() {
        assertEquals(emptyList(), check(ClientFixture.sidecar()))
        val signed = check(ClientFixture.sidecar(), draft = false)
        assertEquals(List(6) { "unreviewed" }, codes(signed), signed.joinToString("\n"))
        assertTrue(signed.any { it.message.startsWith("trusted value fx") || "trusted value Config.get" in it.message }, signed.joinToString("\n"))
    }

    @Test
    fun `object-module declarations are errors in a client sidecar and client ones in an object-module sidecar`() {
        val client = check(ClientFixture.sidecar(extra = "state m: Map<Key, Data> = table\nhole MI_INV \"\"\"\n  true\n\"\"\"\n"))
        assertEquals(listOf("client-decl", "client-decl"), codes(client), client.joinToString("\n"))
        val l1 =
            checkSidecar(
                parse(
                    """
                    module fx.Box
                    source "Box.kt"
                    enum Kind = Kind
                    trusted fun Lock.tryLock(key: Key) returns t: Int
                      reviewed "r" sha256 "x"
                    hole MI_INV ""${'"'}
                      true
                    ""${'"'}
                    """.trimIndent(),
                ),
            )
        assertEquals(listOf("client-only", "client-only"), codes(l1).filter { it == "client-only" }, l1.joinToString("\n"))
    }

    @Test
    fun `a client operation has no issue rule and reads no call or local free value`() {
        val errors = check(ClientFixture.sidecar("free a = local x\nresult res"))
        assertEquals(listOf("client-item"), codes(errors), errors.joinToString("\n"))
    }

    @Test
    fun `malformed client syntax fails at its position`() {
        fun syntax(text: String) = assertFailsWith<SidecarException> { parse(text) }.errors.single()
        assertTrue("expected '@LINE'" in syntax("module m client\nhole loop C.f \"\"\"x\"\"\"").message)
        assertTrue("a creation condition is `RESULT != null`" in syntax("trusted fun L.t(k: Key) returns t: Token? creates O(k, t) if t != nil").message)
        assertTrue("expected 'while'" in syntax("trace {\n  x = Config.get\n}").message)
        assertTrue("expected 'fun'" in syntax("trusted val X.y").message)
        // B's bare `throws` still ends its line.
        val bare = parse("trusted fun F.g(kotlin.Long) returns r: Bool\n  throws // it may throw\n  effects none\n").trusted.single()
        assertTrue(bare.throws && bare.throwKinds.isEmpty() && bare.effectsNone)
        assertNull(parse("module a.B").modules.single().client.takeIf { it })
    }
}
