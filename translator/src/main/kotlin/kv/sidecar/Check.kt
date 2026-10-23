package kv.sidecar

import kv.kvir.GhostKind
import kv.kvir.Reserved
import kv.model.TrustedModels

/**
 * Checks of a parsed sidecar that need no Kotlin code: required declarations, name clashes (DESIGN-v0.md 4.6),
 * references between sidecar names, trusted model names (4.3), R1 for issue values (3.5), and the `reviewed`
 * notes that U7 requires (8.3) on issue rules and allowance clauses, and decision 2 on trusted functions.
 * Kotlin names are resolved later, against FIR.
 */
fun checkSidecar(sidecar: Sidecar): List<SidecarError> = SidecarChecker(sidecar).run()

/** Types the sidecar may name without declaring them. */
private val VIPER_TYPES = setOf("Int", "Bool", "Ref")

/** Kotlin field types a sidecar `class` may name, besides its own types (DESIGN-v0.md 4.2, FTYPE). */
private val FIELD_TYPES = setOf("Semaphore", "AtomicBoolean", "Long")

private class SidecarChecker(
    private val s: Sidecar,
) {
    private val errors = mutableListOf<SidecarError>()
    private val anchor = s.modules.firstOrNull()?.pos ?: SPos(s.file, 1, 1)
    private val types = s.types.associateBy { it.name.text }
    private val classes = s.classes.associateBy { it.name.text }
    private val ghost = s.ghosts.firstOrNull()
    private val operations = s.operations.associateBy { it.name.text }

    /** Global Viper-level names: sidecar declarations plus reserved and tool names, each with what it is. */
    private val globals = mutableMapOf<String, String>()

    private fun err(
        pos: SPos,
        code: String,
        message: String,
    ) {
        errors += SidecarError(pos, code, message)
    }

    fun run(): List<SidecarError> {
        singletons()
        globalNames()
        typeReferences()
        s.uses.filter { it.model.text !in TrustedModels.builtIn }.forEach {
            err(it.model.pos, "unknown-model", "unknown trusted model '${it.model.text}'; built-in models: ${TrustedModels.builtIn.sorted().joinToString()}")
        }
        witness()
        // Decision 2 (2026-10-23): a trusted function contract counts only after a person reads the function body.
        s.trusted.filter { it.reviewed == null }.forEach {
            err(it.pos, "unreviewed", "trusted fun ${it.fn.text} needs a 'reviewed' note: its contract counts only after a person reads the body")
        }
        s.operations.forEach(::operation)
        s.probes.forEach(::probe)
        return errors
    }

    private fun singletons() {
        if (s.modules.isEmpty()) err(anchor, "missing-module", "the sidecar declares no module")
        if (s.sources.isEmpty()) err(anchor, "missing-source", "the sidecar names no source file")
        if (s.holes.isEmpty()) err(anchor, "missing-hole", "the sidecar has no 'hole MI_INV'; the inductive invariant goes there (DESIGN-v0.md 4.2)")
        fun once(
            decls: List<Decl>,
            what: String,
        ) = decls.drop(1).forEach { err(it.pos, "duplicate-decl", "a second $what; the first is at ${decls[0].pos}") }
        once(s.modules, "module")
        once(s.holes, "hole MI_INV")
        once(s.ghosts, "ghost record")
        once(s.witnesses, "witness")
    }

    private fun global(
        n: Name,
        what: String,
    ) {
        val previous = globals[n.text]
        if (previous != null) err(n.pos, "name-clash", "$what '${n.text}' clashes with $previous") else globals[n.text] = "$what at ${n.pos}"
    }

    private fun globalNames() {
        Reserved.names.forEach { globals[it] = "a reserved name" }
        Reserved.tool.forEach { globals[it] = "a name the tool generates" }
        VIPER_TYPES.forEach { globals[it] = "a Viper type" }
        s.types.forEach { global(it.name, "type") }
        s.classes.forEach { c ->
            global(c.name, "class")
            c.fields.forEach { global(it.name, "field of ${c.name.text}") }
        }
        s.states.forEach { global(it.name, "state field") }
        s.ghosts.forEach { g ->
            global(g.name, "ghost record")
            g.fields.forEach { global(it.name, "ghost field") }
        }
        s.operations.forEach { global(it.name, "operation") }
        s.properties.forEach { global(it.id, it.kind.name.lowercase()) }
    }

    private fun type(
        n: Name,
        allowed: Set<String>,
        what: String,
    ) {
        if (n.text !in allowed && n.text !in types) err(n.pos, "unknown-name", "$what '${n.text}' is neither a sidecar type nor one of ${allowed.sorted()}")
    }

    private fun typeReferences() {
        s.states.forEach { st ->
            val kind = st.kind
            if (kind is StateKind.MapOf) {
                type(kind.key, emptySet(), "map key type")
                if (kind.value.text !in classes) err(kind.value.pos, "unknown-name", "map value class '${kind.value.text}' is not a sidecar class")
            }
        }
        s.classes.forEach { c -> c.fields.forEach { type(it.type, FIELD_TYPES, "field type") } }
        s.ghosts.forEach { g ->
            type(g.index, emptySet(), "ghost index type")
            g.fields.forEach { type(it.type, VIPER_TYPES, "ghost field type") }
        }
        s.trusted.forEach { t -> (t.returns + t.ghosts).forEach { type(it.type, VIPER_TYPES, "result type") } }
        s.probes.forEach { p -> p.params.forEach { type(it.type, VIPER_TYPES, "parameter type") } }
    }

    private fun witness() {
        val w = s.witnesses.firstOrNull() ?: return
        val cls = classes[w.cls.text]
        if (cls == null) {
            err(w.cls.pos, "unknown-name", "witness class '${w.cls.text}' is not a sidecar class")
            return
        }
        val field = cls.fields.find { it.name.text == w.field.text }
        if (field == null) {
            err(w.field.pos, "unknown-name", "class ${cls.name.text} has no field '${w.field.text}'")
        } else if (types[field.type.text]?.template == null) {
            err(w.field.pos, "witness-type", "witness field ${cls.name.text}.${field.name.text} must hold a template type such as Token?")
        }
    }

    private fun operation(op: OperationDecl) {
        val locals = mutableMapOf<String, SPos>()

        fun local(
            n: Name,
            what: String,
        ) {
            globals[n.text]?.let { err(n.pos, "name-clash", "$what '${n.text}' clashes with $it") }
            val previous = locals.put(n.text, n.pos)
            if (previous != null) err(n.pos, "name-clash", "$what '${n.text}' is declared twice in operation ${op.name.text}; the first is at $previous")
        }
        op.free.forEach { item -> item.names.forEach { local(it, "free value") } }
        val results = op.items.filterIsInstance<ResultItem>()
        results.forEach { local(it.name, "result") }
        results.drop(1).forEach { err(it.pos, "duplicate-decl", "operation ${op.name.text} has a second result") }

        op.free.forEach { item -> (item.source as? FreeSource.Call)?.let { call(item, it) } }
        // 4.2 restriction 4: the annotation names a sidecar type. Call values take their types from the trusted fun.
        op.free.forEach { item ->
            val t = item.type ?: return@forEach
            if (item.source is FreeSource.Call) {
                err(t.pos, "free-type", "free ${item.names[0].text}: the values of call ${item.source.name.text} take their types from its 'returns' and 'ghost' clauses; remove ': ${t.text}'")
            } else {
                type(t, emptySet(), "abstract type of free ${item.names[0].text}")
            }
        }

        val ids = mutableMapOf<String, SPos>()
        op.items.forEach { item ->
            val id =
                when (item) {
                    is EnsuresItem -> item.id
                    is AllowanceItem -> item.id
                    else -> null
                } ?: return@forEach
            ids.put(id.text, id.pos)?.let { err(id.pos, "duplicate-decl", "property ID ${id.text} is used twice in operation ${op.name.text}; the first is at $it") }
        }

        op.items.filterIsInstance<AllowanceItem>().forEach {
            if (it.reviewed == null) err(it.pos, "unreviewed", "${it.kind.keyword} ${it.id.text} needs a 'reviewed' note: allowance clauses count only after a person reads them (U7)")
        }
        val issues = op.items.filterIsInstance<IssueItem>()
        issues.drop(1).forEach { err(it.pos, "duplicate-decl", "operation ${op.name.text} has a second issue rule") }
        issues.firstOrNull()?.let { issue(op, it, results.firstOrNull()) }
    }

    /** `free a, b = call f`: f is a trusted function, and the names bind its results and ghost values in order. */
    private fun call(
        item: FreeItem,
        source: FreeSource.Call,
    ) {
        val candidates = s.trusted.filter { it.shortName == source.name.text }
        when {
            candidates.isEmpty() -> err(source.name.pos, "unknown-name", "no 'trusted fun' named '${source.name.text}'")
            candidates.size > 1 -> err(source.name.pos, "ambiguous", "${candidates.size} trusted functions are named '${source.name.text}'")
            else -> {
                val t = candidates.single()
                val values = (t.returns + t.ghosts).map { it.name.text }
                if (values.size != item.names.size) {
                    err(item.pos, "free-arity", "call ${t.shortName} gives ${values.size} value(s) $values; the free item names ${item.names.size}")
                }
            }
        }
    }

    private fun issue(
        op: OperationDecl,
        issue: IssueItem,
        result: ResultItem?,
    ) {
        if (issue.reviewed == null) err(issue.pos, "unreviewed", "the issue rule of ${op.name.text} needs a 'reviewed' note: issue rules count only after a person reads them (U7)")
        if (result == null) {
            err(issue.result.pos, "unknown-name", "operation ${op.name.text} issues '${issue.result.text}' but declares no result")
        } else if (issue.result.text != result.name.text) {
            err(issue.result.pos, "unknown-name", "issue names '${issue.result.text}', but the result of ${op.name.text} is '${result.name.text}'")
        }
        if (ghost == null) {
            err(issue.pos, "unknown-name", "an issue rule needs a 'ghost record'")
            return
        }
        // The tool sets set-once and ends fields; the sidecar gives every frozen field its value (DESIGN-v0.md 3.5).
        val kinds = ghost.fields.associate { it.name.text to it.kind }
        val seen = mutableSetOf<String>()
        issue.fields.forEach { (f, _) ->
            when {
                f.text !in kinds -> err(f.pos, "issue-fields", "'${f.text}' is not a field of ghost record ${ghost.name.text}")
                kinds[f.text] != GhostKind.FROZEN -> err(f.pos, "issue-fields", "'${f.text}' is a ${kinds[f.text]!!.name.lowercase().replace('_', '-')} field; the tool sets it")
                !seen.add(f.text) -> err(f.pos, "issue-fields", "'${f.text}' is set twice")
            }
        }
        val missing = kinds.filterValues { it == GhostKind.FROZEN }.keys - seen
        if (missing.isNotEmpty()) err(issue.pos, "issue-fields", "the issue rule gives no value to frozen field(s) ${missing.sorted()}")

        val allowed = setOf("k", "carried", "NULL", "true", "false") + op.free.flatMap { i -> i.names.map { it.text } } + listOfNotNull(result?.name?.text)
        issue.fields.forEach { (_, value) -> r1(value, allowed) }
    }

    /**
     * R1 (DESIGN-v0.md 3.5): an issue value uses only the action key, free values, the result, constants, arithmetic
     * and `carried(S, k, t)`. Reading module state directly would hide SB3-style bugs from the invariant.
     */
    private fun r1(
        value: Vexpr,
        allowed: Set<String>,
    ) {
        val tokens = Regex("""[A-Za-z_][A-Za-z0-9_]*|\S""").findAll(value.text).toList()
        for ((index, token) in tokens.withIndex()) {
            val word = token.value
            val bad =
                when {
                    word == "." || word == "[" -> "a field or map read"
                    word == "S" -> if (index >= 2 && tokens[index - 1].value == "(" && tokens[index - 2].value == "carried") null else "module state 'S' outside carried(S, k, t)"
                    word[0].isLetter() || word[0] == '_' -> if (word in allowed) null else "'$word'"
                    else -> null
                } ?: continue
            err(value.posAt(token.range.first), "R1", "issue value reads $bad; an issue value may use only k, free values, the result, constants, arithmetic and carried(S, k, t) (R1)")
            return
        }
    }

    private fun probe(p: ProbeDecl) {
        // A probe is its own Viper method: its names may reuse k and the other bound names, not fields or tool names.
        val taken = globals.filterKeys { it !in Reserved.names }
        val locals = mutableMapOf<String, SPos>()

        fun local(n: Name) {
            taken[n.text]?.let { err(n.pos, "name-clash", "probe name '${n.text}' clashes with $it") }
            locals.put(n.text, n.pos)?.let { err(n.pos, "name-clash", "probe name '${n.text}' is declared twice; the first is at $it") }
        }
        p.params.forEach { local(it.name) }
        p.steps.filterIsInstance<ProbeCall>().forEach { call ->
            local(call.out)
            val op = operations[call.op.text]
            if (op == null) {
                err(call.op.pos, "unknown-name", "no operation '${call.op.text}'")
                return@forEach
            }
            val positional = call.args.filter { it.name == null }
            if (positional.size != 1 || call.args.first().name != null) {
                err(call.pos, "probe-args", "a probe call passes the key first and every other argument by name")
            }
            val free = op.free.flatMap { i -> i.names.map { it.text } }
            val named = call.args.mapNotNull { it.name }
            named.groupBy { it.text }.filterValues { it.size > 1 }.forEach { (n, ns) -> err(ns[1].pos, "probe-args", "argument '$n' is given twice") }
            named.filter { it.text !in free }.forEach { err(it.pos, "probe-args", "operation ${op.name.text} has no free value '${it.text}'; it has $free") }
            val missing = free - named.map { it.text }.toSet()
            if (missing.isNotEmpty()) err(call.pos, "probe-args", "the call to ${op.name.text} gives no value to $missing")
        }
    }
}
