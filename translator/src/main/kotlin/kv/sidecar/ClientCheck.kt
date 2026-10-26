package kv.sidecar

import kv.model.ClientModels

/**
 * The named assumptions of the built-in client models (DESIGN-E 4): `assume ID: NAME` turns one on. An assumption the
 * sidecar does not name is off. A2 is off in the canonical model (UE3); E4 turns it on as a toggle.
 */
val CLIENT_ASSUMPTIONS: Map<String, String> =
    linkedMapOf(
        "A1" to "errors-are-fatal",
        "A2" to "lock-calls-return",
        "A3" to "accepted-tasks-run",
        "A4" to "throwing-release-ends-obligation",
        "A5" to "throwing-acquire-takes-nothing",
    )

/** The exception kinds a trusted call may throw besides an Error, which A1 decides (DESIGN-E 3.5). */
val TRUSTED_THROW_KINDS = listOf("REE", "RTE", "CHK", "INTR")

/** `holds(NAME(` inside a Viper expression: the obligation a HOLDS test names (DESIGN-E 3.1, 6.2). */
private val HOLDS = Regex("""\bholds\s*\(\s*([A-Za-z_]\w*)\s*\(""")

/**
 * The trusted declaration or built-in client model a call reference names (trace events and guards, DESIGN-E 6.2):
 * the declaration's Kotlin name, or a suffix of it after a dot (`Callable.call`, `getCacheFunction`), or a client
 * model name or such a suffix (`CompletableFuture.runAsync`). Several matches or none: null.
 */
fun Sidecar.callTarget(ref: String): Any? {
    fun names(full: String) = full == ref || full.endsWith(".$ref")
    val found = trusted.filter { names(it.fn.text) } + ClientModels.builtIn.filter(::names)
    return found.singleOrNull()
}

/**
 * The sidecar checks of a client module (DESIGN-E 6.2, 6.10 E1) that need no Kotlin code: the declarations an object
 * or instance module has are not allowed; obligations, exception kinds, assumptions, trace events and guards name
 * what the sidecar declares. [err] reports an error.
 */
internal class ClientChecks(
    private val s: Sidecar,
    private val err: (SPos, String, String) -> Unit,
) {
    private val obligations = s.obligations.associateBy { it.name.text }

    fun run() {
        l1Declarations()
        s.trusted.forEach(::trusted)
        assumptions()
        trace()
        s.operations.forEach(::operation)
        loops()
    }

    private fun l1Declarations() {
        val l1 =
            s.states.map { it.pos to "state" } + s.ghosts.map { it.pos to "ghost record" } + s.witnesses.map { it.pos to "witness" } +
                s.setOnce.map { it.pos to "set-once" } + s.properties.map { it.pos to it.kind.name.lowercase() } + s.probes.map { it.pos to "probe" } +
                s.holes.filter { it.loop == null }.map { it.pos to "hole MI_INV" }
        l1.forEach { (pos, what) -> err(pos, "client-decl", "a client module has no module state, so no $what (DESIGN-E 6.1)") }
    }

    /** The obligation [use] names, with its arity checked; null after an error. */
    private fun obligation(
        name: Name,
        arity: Int,
        what: String,
    ): ObligationDecl? {
        val o = obligations[name.text] ?: return null.also { err(name.pos, "unknown-obligation", "$what names '${name.text}', which is not an obligation of the sidecar; obligations: ${obligations.keys.sorted()}") }
        if (o.types.size != arity) err(name.pos, "obligation-arity", "$what gives ${o.name.text} $arity argument(s); it takes ${o.types.size}")
        return o
    }

    private fun trusted(t: TrustedDecl) {
        val what = "trusted ${if (t.value) "value" else "fun"} ${t.fn.text}"
        val params = t.named.filterNotNull().map { it.name.text }
        val results = t.returns.map { it.name.text }
        for ((kind, use) in listOfNotNull(t.creates?.let { "creates" to it }, t.consumes?.let { "consumes" to it })) {
            obligation(use.obligation, use.args.size, "$kind of $what")
            use.args.filter { it.text !in params + results }.forEach {
                err(it.pos, "unknown-name", "$kind of $what names '${it.text}', which is neither a named parameter $params nor a result $results")
            }
            use.ifNotNull?.let { r ->
                val result = t.returns.find { it.name.text == r.text }
                if (result == null || !result.type.text.endsWith("?")) err(r.pos, "creates-condition", "$what creates only if '${r.text}' != null, so '${r.text}' must be a nullable result")
            }
        }
        t.throwKinds.filter { it.text !in TRUSTED_THROW_KINDS }.forEach {
            err(it.pos, "unknown-exception-kind", "$what throws '${it.text}', which is not an exception kind a trusted call may throw; kinds: ${TRUSTED_THROW_KINDS.joinToString()} (DESIGN-E 3.5; Errors are A1's)")
        }
    }

    private fun assumptions() {
        val seen = mutableMapOf<String, SPos>()
        for (a in s.assumes) {
            val name = CLIENT_ASSUMPTIONS[a.id.text]
            when {
                name == null -> err(a.id.pos, "unknown-assumption", "no built-in assumption ${a.id.text}; the tool knows ${CLIENT_ASSUMPTIONS.entries.joinToString { "${it.key}: ${it.value}" }} (DESIGN-E 4)")
                name != a.name.text -> err(a.name.pos, "unknown-assumption", "assumption ${a.id.text} is '$name', not '${a.name.text}'")
            }
            seen.put(a.id.text, a.pos)?.let { err(a.pos, "duplicate-decl", "a second assume ${a.id.text}; the first is at $it") }
        }
    }

    private fun callRef(
        ref: Name,
        what: String,
    ) {
        if (s.callTarget(ref.text) == null) {
            err(ref.pos, "unknown-call", "$what names '${ref.text}', which names no single trusted declaration or client model (DESIGN-E 6.2)")
        }
    }

    /** Each `holds(NAME(` of [e] names an obligation. */
    private fun holds(
        e: Vexpr,
        what: String,
    ) {
        HOLDS.findAll(e.text).forEach { m ->
            val name = m.groupValues[1]
            if (name !in obligations) err(e.posAt(m.groups[1]!!.range.first), "unknown-obligation", "$what tests holds($name(...)), but '$name' is not an obligation of the sidecar")
        }
    }

    private fun trace() {
        s.traces.drop(1).forEach { err(it.pos, "duplicate-decl", "a second trace; the first is at ${s.traces[0].pos}") }
        val earlier = mutableMapOf<String, EventForm>()
        for (e in s.traces.flatMap { it.events }) {
            val what = "trace field ${e.name.text}"
            e.call?.let { callRef(it, what) }
            e.where?.let { holds(it, what) }
            e.holds?.let { obligation(it.obligation, it.args.size, what) }
            e.of?.let {
                if (earlier[it.text] != EventForm.COUNT) err(it.pos, "unknown-name", "$what counts the trailing failures of '${it.text}', which is not an earlier count field")
            }
            earlier[e.name.text] = e.form
        }
    }

    private fun operation(op: OperationDecl) {
        for (item in op.items) {
            when (item) {
                is IssueItem, is AllowanceItem -> err(item.pos, "client-item", "a client operation has no ghost record, so no issue rule or allowance clause (DESIGN-E 6.2)")
                is FreeItem ->
                    if (item.source is FreeSource.Call || item.source is FreeSource.Local) {
                        err(item.pos, "client-item", "a client operation reads its free values from a parameter or from 'this.' properties (DESIGN-E 3.6)")
                    }
                is GuardItem -> {
                    callRef(item.call, "guard ${item.id.text}")
                    holds(item.expr, "guard ${item.id.text}")
                }
                is EnsuresItem -> holds(item.expr, "ensures ${item.id.text}")
                is ResultItem -> Unit
            }
        }
    }

    private fun loops() {
        val seen = mutableMapOf<Pair<String, Int>, SPos>()
        for (h in s.holes) {
            val loop = h.loop ?: continue
            seen.put(loop.fn.text to loop.line, h.pos)?.let { err(h.pos, "duplicate-decl", "a second hole for the loop ${loop.fn.text}@${loop.line}; the first is at $it") }
        }
    }
}
