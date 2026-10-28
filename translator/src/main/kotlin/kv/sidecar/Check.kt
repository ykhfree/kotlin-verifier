package kv.sidecar

import kv.kvir.GhostKind
import kv.kvir.Reserved
import kv.model.ClientModels
import kv.model.TrustedModels

/**
 * Checks of a parsed sidecar that need no Kotlin code: required declarations, name clashes (DESIGN-v0.md 4.6),
 * references between sidecar names, trusted model names (4.3), R1 for issue values (3.5), and the `reviewed`
 * notes that U7 requires (8.3) on issue rules and allowance clauses, and decision 2 on trusted functions. Under
 * `review intent` (DESIGN-C.md 10 UC4), every intent clause needs a note and a source line, and a quoted source line
 * needs its block fingerprint (10.1).
 * A [draft] (DESIGN-C.md 7.1, `kv spec check --draft`) is not signed yet: an intent clause without a note, or a note
 * without a fingerprint, is no error, and a quoted source line needs no block fingerprint. A trusted fun still needs
 * its note, and a note's fingerprint is still checked.
 * Kotlin names are resolved later, against FIR.
 */
fun checkSidecar(
    sidecar: Sidecar,
    draft: Boolean = false,
): List<SidecarError> = SidecarChecker(sidecar, draft).run()

/**
 * Checks of the scenario cells of a cell file against [sidecar] (DESIGN-C.md 3.2): unique cell names, parameter
 * types, the calls as in a probe (operation names, the key first, every free value by name, at most two outs), unique
 * step and parameter names, and at least one outcome per cell, with unique names.
 */
fun checkCells(
    sidecar: Sidecar,
    cells: List<CellDecl>,
): List<SidecarError> = SidecarChecker(sidecar, draft = false).cells(cells)

/** Types the sidecar may name without declaring them. */
private val VIPER_TYPES = setOf("Int", "Bool", "Ref")

/** Kotlin field types a sidecar `class` may name, besides its own types (DESIGN-v0.md 4.2, FTYPE). */
private val FIELD_TYPES = setOf("Semaphore", "AtomicBoolean", "Long")

/** Value types of a client sidecar besides its types, enums and classes (DESIGN-E 6.2). */
private val CLIENT_VALUE_TYPES = setOf("Int", "Bool")

private class SidecarChecker(
    private val s: Sidecar,
    private val draft: Boolean,
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
        // DESIGN-E 6.5: a client sidecar may also use the client models.
        val models = if (s.client) TrustedModels.builtIn + ClientModels.builtIn else TrustedModels.builtIn
        s.uses.filter { it.model.text !in models }.forEach {
            err(it.model.pos, "unknown-model", "unknown trusted model '${it.model.text}'; built-in models: ${models.sorted().joinToString()}")
        }
        witness()
        setOnce()
        keyClasses()
        s.trusted.forEach(::fresh)
        // Decision 2 (2026-10-23): a trusted function contract counts only after a person reads the function body.
        // DESIGN-E 2.5: the trusted declarations of a client module are signed with its intent clauses, so a client
        // draft may leave them without a note.
        s.trusted.filter { it.reviewed == null && !(draft && s.client) }.forEach {
            err(it.pos, "unreviewed", "trusted ${if (it.value) "value" else "fun"} ${it.fn.text} needs a 'reviewed' note: its contract counts only after a person reads the body")
        }
        s.operations.forEach(::operation)
        s.probes.forEach(::probe)
        probeNames()
        // The clauses of an operation are checked in operation(); here every top-level intent clause.
        s.intentClauses().filter { (_, c) -> c !is ReviewedItem }.forEach { (what, c) -> fingerprint(what, c) }
        if (s.client) ClientChecks(s, ::err).run() else l1Only()
        if (s.reviewIntent) reviewIntent()
        vocabulary()
        scopes()
        return errors
    }

    /** Each probe is its own method (DESIGN-C.md 7.2), so names are unique and there is at most one unnamed probe. */
    private fun probeNames() {
        val seen = mutableMapOf<String, SPos>()
        for (p in s.probes) {
            seen.put(p.label, p.pos)?.let { err(p.name?.pos ?: p.pos, "duplicate-decl", "a second ${if (p.name == null) "unnamed probe" else p.label}; the first is at $it") }
        }
    }

    /**
     * DESIGN-C.md 10 UC4, opted in with `review intent`: every intent clause needs a `reviewed` note (issue rules and
     * allowance clauses need one anyway, U7) and at least one source line; `source none "<reason>"` counts.
     */
    private fun reviewIntent() {
        for ((what, c) in s.intentClauses()) {
            if (c.reviewed == null && c !is IssueItem && c !is AllowanceItem && !draft) {
                err(c.pos, "unreviewed", "$what needs a 'reviewed' note: under 'review intent' every intent clause counts only after a person reads it (DESIGN-C.md 10 UC4)")
            }
            if (c.sources.isEmpty()) {
                err(c.pos, "unsourced", "$what has no source line: under 'review intent' every intent clause names its source, source \"<anchor>\" quote \"<quote>\" or source none \"<reason>\" (DESIGN-C.md 5.2)")
            }
            // DESIGN-C.md 10.1: without the block fingerprint, kv spec stale cannot see that the quote's context changed.
            if (!draft) {
                c.sources.filterIsInstance<SourceQuote>().filter { it.sha256 == null }.forEach {
                    err(it.pos, "missing-block-fingerprint", "$what: the source line of ${it.anchor} has no block fingerprint: under 'review intent' a quoted source line records sha256 \"<fp>\" of its block (sources.json of kv spec snapshot), so that kv spec stale sees a changed context (DESIGN-C.md 10.1)")
                }
            }
        }
    }

    /**
     * DESIGN-E 12.3 (V-C): under `review intent`, the vocabulary declarations count only after a person signs them with
     * the note of `review vocabulary`. That note's fingerprint covers their text without comments (12.3.2); without
     * `review intent` a note is optional, but its fingerprint is checked.
     */
    private fun vocabulary() {
        val notes = s.vocabularyReviews
        notes.drop(1).forEach { err(it.pos, "duplicate-decl", "a second 'review vocabulary'; the first is at ${notes[0].pos}") }
        val vocabulary = s.vocabulary
        val names = vocabulary.joinToString { it.name }
        val note = notes.firstOrNull()
        if (s.reviewIntent && !draft && vocabulary.isNotEmpty() && note?.reviewed == null) {
            err(
                note?.pos ?: vocabulary[0].pos,
                "unreviewed",
                "the vocabulary ($names) needs a 'review vocabulary' note: under 'review intent' it counts only after a person reads it (DESIGN-E 12.3)",
            )
        }
        if (note?.reviewed == null) return
        fingerprint("the vocabulary", note.sha256, note.reviewPos ?: note.pos, s.vocabularyText, "the vocabulary ($names) now has")
    }

    /**
     * DESIGN-C.md 10.10 (C7): each `scope` and each excluded anchor once. Under `review intent` both need a note, and an
     * `exclude` its block fingerprint; a note's fingerprint is checked as an intent clause's is.
     */
    private fun scopes() {
        fun twice(
            items: List<Pair<String, SPos>>,
            what: String,
        ) {
            val seen = mutableMapOf<String, SPos>()
            items.forEach { (key, pos) -> seen.put(key, pos)?.let { err(pos, "duplicate-decl", "a second $what \"$key\"; the first is at $it") } }
        }
        twice(s.scopes.map { it.target to it.pos }, "scope")
        twice(s.excludes.map { it.anchor to it.pos }, "exclude of")
        fun note(
            what: String,
            pos: SPos,
            reviewed: String?,
            sha256: String?,
            reviewPos: SPos?,
            unit: String,
        ) {
            if (reviewed != null) return fingerprint(what, sha256, reviewPos ?: pos, unit, "its text now has")
            if (s.reviewIntent && !draft) err(pos, "unreviewed", "$what needs a 'reviewed' note: under 'review intent' the scope of the spec counts only after a person reads it (DESIGN-C.md 10.10)")
        }
        s.scopes.forEach { note("scope \"${it.target}\"", it.pos, it.reviewed, it.sha256, it.reviewPos, it.unit) }
        s.excludes.forEach { note("exclude \"${it.anchor}\"", it.pos, it.reviewed, it.sha256, it.reviewPos, it.unit) }
        if (s.reviewIntent && !draft) {
            s.excludes.filter { it.blockSha256 == null }.forEach {
                err(it.pos, "missing-block-fingerprint", "exclude \"${it.anchor}\" has no block fingerprint: under 'review intent' it records sha256 \"<fp>\" of the block (sources.json of kv spec snapshot), so that kv spec stale sees a changed block (DESIGN-C.md 10.10)")
            }
        }
    }

    private fun singletons() {
        if (s.modules.isEmpty()) err(anchor, "missing-module", "the sidecar declares no module")
        if (s.sources.isEmpty()) err(anchor, "missing-source", "the sidecar names no source file")
        // DESIGN-E 6.1: a client module has no state, so no module invariant.
        val invariantHoles = s.holes.filter { it.loop == null }
        if (invariantHoles.isEmpty() && !s.client) err(anchor, "missing-hole", "the sidecar has no 'hole MI_INV'; the inductive invariant goes there (DESIGN-v0.md 4.2)")
        fun once(
            decls: List<Decl>,
            what: String,
        ) = decls.drop(1).forEach { err(it.pos, "duplicate-decl", "a second $what; the first is at ${decls[0].pos}") }
        once(s.modules, "module")
        once(invariantHoles, "hole MI_INV")
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
        s.enums.forEach { global(it.name, "enum") }
        s.obligations.forEach { global(it.name, "obligation") }
        s.traces.flatMap { it.events }.forEach { global(it.name, "trace field") }
        s.traces.flatMap { it.derived }.forEach { global(it.name, "derived value") }
        // DESIGN-D.md 6.6: the method of a reserve step is reserve_<operation>.
        s.operations.filter { it.name.text.startsWith(Reserved.RESERVE_PREFIX) }.forEach {
            err(it.name.pos, "name-clash", "operation '${it.name.text}' starts with '${Reserved.RESERVE_PREFIX}', the prefix of the tool's reserve step methods")
        }
        s.properties.forEach { global(it.id, it.kind.name.lowercase()) }
        s.setOnce.forEach { global(it.id, "set-once property") }
    }

    private fun type(
        n: Name,
        allowed: Set<String>,
        what: String,
    ) {
        // DESIGN-E 6.2: in a client sidecar a value type may also be an enum or a class, and nullable.
        val base = if (s.client) n.text.removeSuffix("?") else n.text
        val known = base in allowed || base in types || s.client && (s.enums.any { it.name.text == base } || base in classes)
        if (!known) err(n.pos, "unknown-name", "$what '${n.text}' is neither a sidecar type nor one of ${allowed.sorted()}")
    }

    /**
     * DESIGN-E 6.2: the declarations of a client module may not appear in an object or instance module sidecar. The
     * check reads only declarations those sidecars never had, so an existing sidecar meets no new error.
     */
    private fun l1Only() {
        val clientOnly =
            s.enums.map { it.pos to "enum" } + s.obligations.map { it.pos to "obligation" } + s.traces.map { it.pos to "trace" } +
                s.assumes.map { it.pos to "assume" } + s.holes.filter { it.loop != null }.map { it.pos to "hole loop" } +
                s.trusted.filter { it.value || it.named.isNotEmpty() || it.creates != null || it.consumes != null || it.throwKinds.isNotEmpty() }.map { it.pos to "trusted ${if (it.value) "value" else "fun with named parameters, an obligation or exception kinds"}" } +
                s.operations.flatMap { op -> op.items.filterIsInstance<GuardItem>().map { it.pos to "guard" } }
        clientOnly.forEach { (pos, what) -> err(pos, "client-only", "$what is a declaration of a client module (DESIGN-E 6.2); add 'client' to the module line") }
    }

    private fun typeReferences() {
        s.states.forEach { st ->
            val kind = st.kind
            if (kind is StateKind.MapOf) {
                type(kind.key, emptySet(), "map key type")
                if (kind.value.text !in classes) err(kind.value.pos, "unknown-name", "map value class '${kind.value.text}' is not a sidecar class")
            }
        }
        s.classes.forEach { c -> c.fields.forEach { type(it.type, if (s.client) CLIENT_VALUE_TYPES else FIELD_TYPES, "field type") } }
        s.ghosts.forEach { g ->
            type(g.index, emptySet(), "ghost index type")
            g.fields.forEach { type(it.type, VIPER_TYPES, "ghost field type") }
        }
        s.trusted.forEach { t -> (t.returns + t.ghosts).forEach { type(it.type, VIPER_TYPES, "result type") } }
        s.trusted.forEach { t -> t.named.filterNotNull().forEach { type(it.type, CLIENT_VALUE_TYPES, "parameter type") } }
        s.obligations.forEach { o -> o.types.forEach { type(it, CLIENT_VALUE_TYPES, "obligation argument type") } }
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

    /** DESIGN-B.md 2.4: `set-once ID: m` names a map state, once. */
    private fun setOnce() {
        val states = s.states.associateBy { it.name.text }
        val seen = mutableMapOf<String, SPos>()
        for (d in s.setOnce) {
            val st = states[d.map.text]
            when {
                st == null -> err(d.map.pos, "unknown-name", "set-once ${d.id.text} names '${d.map.text}', which is not a sidecar state")
                st.kind !is StateKind.MapOf -> err(d.map.pos, "set-once", "set-once ${d.id.text} names state ${d.map.text}, which is a Counter; set-once needs a Map state (DESIGN-B.md 2.4)")
                else -> seen.put(d.map.text, d.pos)?.let { err(d.pos, "duplicate-decl", "a second set-once for map ${d.map.text}; the first is at $it") }
            }
        }
    }

    /** DESIGN-B.md 2.2: an opaque type binds only a Kotlin class the trusted model table knows as a key class. */
    private fun keyClasses() {
        for (t in s.types) {
            val k = t.kclass ?: continue
            if (k.text !in TrustedModels.keyClasses) {
                err(k.pos, "unknown-key-class", "type ${t.name.text}: '${k.text}' is not a key class of the trusted model table; known: ${TrustedModels.keyClasses.keys.sorted().joinToString()}")
            }
        }
    }

    /** DESIGN-B.md 3.2: `returns fresh C` names a sidecar class, and a fresh function returns nothing else. */
    private fun fresh(t: TrustedDecl) {
        val cls = t.fresh ?: return
        if (cls.text !in classes) err(cls.pos, "unknown-name", "trusted fun ${t.shortName} returns fresh '${cls.text}', which is not a sidecar class")
        if (t.returns.isNotEmpty()) err(t.returns[0].name.pos, "fresh-returns", "trusted fun ${t.shortName} returns a fresh ${cls.text}; it cannot also return '${t.returns[0].name.text}'")
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
                    is GuardItem -> item.id
                    else -> null
                } ?: return@forEach
            ids.put(id.text, id.pos)?.let { err(id.pos, "duplicate-decl", "property ID ${id.text} is used twice in operation ${op.name.text}; the first is at $it") }
        }

        op.items.filterIsInstance<AllowanceItem>().forEach {
            if (it.reviewed == null && !draft) err(it.pos, "unreviewed", "${it.kind.keyword} ${it.id.text} needs a 'reviewed' note: allowance clauses count only after a person reads them (U7)")
        }
        val issues = op.items.filterIsInstance<IssueItem>()
        issues.drop(1).forEach { err(it.pos, "duplicate-decl", "operation ${op.name.text} has a second issue rule") }
        issues.firstOrNull()?.let { issue(op, it, results.firstOrNull()) }
        op.items.filterIsInstance<ReviewedItem>().forEach { fingerprint(describe(it, op), it) }
    }

    /**
     * B6 (DESIGN-v0.md 8.3 "reviewed 지문"): a reviewed clause's note records the fingerprint of the clause text and
     * its source lines (DESIGN-C.md 5.2 rule 4). A note without one is an error, because the policy is mandatory; a
     * different one means the text changed after the review (`stale-review`). Both messages carry the fingerprint of
     * the text as it is now.
     */
    private fun fingerprint(
        what: String,
        clause: IntentClause,
    ) {
        if (clause.reviewed == null) return // reported as unreviewed, or not required
        val text = if (clause.sources.isEmpty()) "the clause text now has" else "the clause text and its source lines now have"
        fingerprint(what, clause.sha256, clause.reviewPos ?: clause.pos, clause.reviewedText, text)
    }

    /** Compares the fingerprint of [reviewed] with [recorded], the one a note at [at] records; [now] names the text. */
    private fun fingerprint(
        what: String,
        recorded: String?,
        at: SPos,
        reviewed: String,
        now: String,
    ) {
        val fp = reviewFingerprint(reviewed)
        when (recorded) {
            null -> if (!draft) err(at, "missing-fingerprint", "the reviewed note of $what has no fingerprint: write sha256 \"$fp\" after it once a person has read it")
            fp -> Unit
            else -> err(at, "stale-review", "$what changed after its review: the note records sha256 \"$recorded\", $now sha256 \"$fp\"; a person must review it again")
        }
    }

    /**
     * `free a, b = call f`: f is a trusted function, and the names bind its results, its ghost values and, if it
     * `throws`, its throw flag (DESIGN-B.md 2.7 rule 1), in order.
     */
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
                val values = t.callValues
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
        if (issue.reviewed == null && !draft) err(issue.pos, "unreviewed", "the issue rule of ${op.name.text} needs a 'reviewed' note: issue rules count only after a person reads them (U7)")
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

    private fun probe(p: ProbeDecl) = scenario("probe", p.params, p.steps.filterIsInstance<ProbeCall>())

    /**
     * The cells of a cell file (DESIGN-C.md 3.2). Each cell is its own Viper method, as a probe is, so its names
     * follow the probe rules; the cell names are unique, and each cell has one or more outcomes with unique names.
     */
    fun cells(cells: List<CellDecl>): List<SidecarError> {
        globalNames()
        val ids = mutableMapOf<String, SPos>()
        for (c in cells) {
            ids.put(c.id.text, c.id.pos)?.let { err(c.id.pos, "duplicate-decl", "a second cell ${c.id.text}; the first is at $it") }
            c.params.forEach { type(it.type, VIPER_TYPES, "parameter type") }
            scenario("cell ${c.id.text}", c.params, c.steps.filterIsInstance<ProbeCall>())
            if (c.outcomes.isEmpty()) err(c.pos, "cell-outcomes", "cell ${c.id.text} has no outcome: write one 'outcome NAME: EXPR' per result a person can pick")
            val names = mutableMapOf<String, SPos>()
            c.outcomes.forEach { o -> names.put(o.name.text, o.name.pos)?.let { err(o.name.pos, "duplicate-decl", "cell ${c.id.text} has a second outcome ${o.name.text}; the first is at $it") } }
        }
        return errors
    }

    /**
     * The names and calls of a probe or a cell ([what] in messages): its parameters and outs, and each call against
     * the operation it names.
     */
    private fun scenario(
        what: String,
        params: List<Typed>,
        calls: List<ProbeCall>,
    ) {
        // A probe is its own Viper method: its names may reuse k and the other bound names, not fields or tool names.
        val taken = globals.filterKeys { it !in Reserved.names }
        val locals = mutableMapOf<String, SPos>()

        fun local(n: Name) {
            taken[n.text]?.let { err(n.pos, "name-clash", "$what name '${n.text}' clashes with $it") }
            locals.put(n.text, n.pos)?.let { err(n.pos, "name-clash", "$what name '${n.text}' is declared twice; the first is at $it") }
        }
        params.forEach { local(it.name) }
        calls.forEach { call ->
            call.outs.forEach(::local)
            // DESIGN-B.md 5.1: the result, then `exc` if the operation can throw (the emitter checks which).
            if (call.outs.size > 2) err(call.outs[2].pos, "probe-outs", "a probe call names at most two outs: the result and exc")
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
