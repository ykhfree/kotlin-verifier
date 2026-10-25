package kv.spec

import kv.emit.specDir
import kv.sidecar.SidecarLoader
import kv.sidecar.parseSidecar
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The field-fixing warning of DESIGN-C.md 4.1 (C3 acceptance 4): an ensures premise that reads an entry field other
 * than the witness needs an intent clause that ties the field to a ghost value or an issue value.
 */
class FieldFixingTest {
    private fun warnings(dir: File) = fieldFixing(SidecarLoader.load(File(dir, "keylocallock.coroutine.kv"))).map { it.clause to it.fields }

    @Test
    fun `the pilot's signed sidecar - progress_live and expired_takeover read expiresAt, and nothing ties it`() {
        assertEquals(
            listOf("ensures progress_live of tryLock" to listOf("expiresAt", "isHeld"), "ensures expired_takeover of tryLock" to listOf("expiresAt")),
            warnings(File(pilotDir, "specs-signed")),
        )
    }

    @Test
    fun `the aligned drafts and the pre-decision drafts tie expiresAt to the issue value or gLease - no warning`() {
        for (name in listOf("aligned-haiku", "aligned-sonnet", "aligned-opus", "final-haiku", "final-sonnet", "final-opus")) {
            assertEquals(emptyList(), warnings(File(pilotDir, "checks/$name")), name)
        }
    }

    /**
     * The current sidecar: entry_lease ties expiresAt to gLease, so expired_takeover no longer warns. progress_live
     * still reads isHeld, which only the hole (MI_INV_1) ties: the rule of 4.1 warns. C3 reports this to the main
     * session (the task expected 0).
     */
    @Test
    fun `the current sidecar - entry_lease ties expiresAt and entry_held ties isHeld, so no warning`() {
        assertEquals(emptyList(), warnings(specDir))
    }

    private val head =
        """
        type Key = opaque
        type Token = template "t-${'$'}{Long}"
        state m: Map<Key, Info> = map
        class Info = A.Info {
          token: Token? = token
          until: Long = until
          flag: AtomicBoolean = flag
        }
        ghost record tok(Token) {
          gIssued: Bool set-once
          gKey: Key frozen
          gHeld: Bool ends
          gLease: Int frozen
        }
        witness Info.token
        """.trimIndent()

    private fun fields(body: String) = fieldFixing(parseSidecar("t.kv", "$head\n${body.trimIndent()}")).map { it.clause to it.fields }

    @Test
    fun `what ties a field and what does not`() {
        val reads = "  ensures R: old(has(S, k)) && now > old(S.m[k].until) && old(S.m[k].flag) ==> res != NULL"
        val op = { extra: String -> "operation get = fun A.get {\n  free now = local now\n  result res\n  issue res { gKey = k; gLease = now + 7 }\n$reads\n$extra\n}" }
        // Nothing ties until or flag; the witness field token is never a finding.
        assertEquals(listOf("ensures R of get" to listOf("flag", "until")), fields(op("  ensures W: old(S.m[k].token) == res ==> true")))
        // A ghost value ties until (in an invariant, the operands in either order).
        assertEquals(listOf("ensures R of get" to listOf("flag")), fields(op("") + "\ninvariant I: forall q: Key :: { S.m[q] } has(S, q) ==> tok(S.m[q].token).gLease == S.m[q].until"))
        // The issue value `now + 7` of the same operation ties until.
        assertEquals(listOf("ensures R of get" to listOf("flag")), fields(op("  ensures T: res != NULL ==> S.m[k].until == now + 7")))
        // A frame equality is no tie.
        assertEquals(listOf("ensures R of get" to listOf("flag", "until")), fields(op("  ensures F: res == NULL ==> S.m[k].until == old(S.m[k].until)")))
        // The issue value of another operation is no tie: its names mean other values there.
        val other = "operation put = fun A.put {\n  free now = local now\n  ensures T: S.m[k].until == now + 7\n}"
        assertEquals(listOf("ensures R of get" to listOf("flag", "until")), fields(op("") + "\n" + other))
        // A read after `==>` is no premise read.
        assertEquals(emptyList(), fields("operation get = fun A.get {\n  result res\n  ensures C: res ==> S.m[k].until > 0\n}"))
    }

    @Test
    fun `premise and equality operands`() {
        assertEquals("a && (b ==> c) ", premise("a && (b ==> c) ==> d"))
        assertEquals("a ==> b ", premise("a ==> b ==> c"))
        assertNull(premise("(a ==> b)"))
        assertEquals(
            listOf("r" to "NULL", "S.m[k].x" to "old(S.m[k].x)", "tok(S.m[q].token).g" to "S.m[q].y"),
            equalities("r == NULL ==> S.m[k].x == old(S.m[k].x) && forall q: K :: { S.m[q] } tok(S.m[q].token).g == S.m[q].y"),
        )
        assertEquals(listOf("x", "y"), entryFields("S.m[tok(S.m[k].z).a].x + S.m[k].y + S.n[k].x", mapOf("m" to setOf("x", "y"))))
    }
}
