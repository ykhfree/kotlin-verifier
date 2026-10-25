package kv.spec

import kv.kvir.GhostKind
import kv.sidecar.EnsuresItem
import kv.sidecar.FreeSource
import kv.sidecar.IssueItem
import kv.sidecar.OperationDecl
import kv.sidecar.Sidecar
import kv.sidecar.SidecarError
import kv.sidecar.SidecarException
import kv.sidecar.StateKind
import kv.sidecar.TrustedDecl
import kv.sidecar.checkCells
import kv.sidecar.parseCells

/**
 * The constants of the cell rule (DESIGN-C.md 3.2). They are the protocol knobs: a protocol whose interesting values
 * differ needs other values here.
 */
object CellRule {
    /** The value of every Int instance value (`free x = this.X`), e.g. lockTimeoutMillis: "timeout 10". */
    const val INSTANCE_INT = 10L

    /** The values of an Int that a trusted call returns: the default and one non-zero value, e.g. no cap and a cap of 5. */
    val TRUSTED_INTS = listOf(0L, 5L)
}

/** One generated cell: its ID, title, boundary mark and text in the cell grammar (DESIGN-C.md 3.2). */
data class GeneratedCell(
    val id: String,
    val title: String,
    val boundary: Boolean,
    val text: String,
)

/** The cells of [generateCells], the vocabulary the rule read from the sidecar ([notes]) and the cell file [text]. */
data class GeneratedCells(
    val cells: List<GeneratedCell>,
    val notes: List<String>,
    val text: String,
)

/**
 * `kv spec cells` (DESIGN-C.md 3.2 "생성 규칙", milestone C4): the scenario cells of [sidecar], from the sidecar
 * alone. The rule reads the state map, the ghost record, the operations, their free values and types, the trusted
 * contracts and the issue rule. It knows no protocol names.
 *
 * Vocabulary:
 * - The issuing operation: the one with an issue rule (an object module), or the one whose free value calls a
 *   `returns fresh` function of the map's value class (an instance module, DESIGN-B.md). Its result is the issued value.
 * - The lease: a frozen Int ghost field that the issue rule computes from a clock input; L is its value at the set-up.
 *   Clock inputs are Int free values read per call (`local`, `param` or a `= clock` call), only if a lease exists;
 *   without a lease such a value is 0.
 * - Input values: a Bool takes false and true; an Int from a trusted call takes [CellRule.TRUSTED_INTS]; an Int instance
 *   value is [CellRule.INSTANCE_INT]; a clock takes the prefix's times. Combinations that break a trusted contract are
 *   left out. The default is the first combination: false, 0. An input of another type is a cell parameter.
 * - Token inputs (the ghost record's index type): the values the prefix names, and `x`, a token never issued.
 *
 * Prefixes (the steps before the final step), with the final steps they get:
 * - E: the empty module. Every operation with every input combination, at time 0.
 * - H: the set-up: the issuing operation once at time 0 with default inputs, returning t1, which k's entry holds.
 *   Every operation with every input combination, at L/2, L and L+1 (before, at, after the lease); tokens t1 and x.
 * - K: the set-up, then each operation once on another key k2 at L/2, default inputs; tokens t1 and x.
 * - O: the set-up, then one owner-changing step: each operation at L+1 with default inputs and token t1, after which
 *   k's entry no longer holds t1 (an issuing step returned t2). Then each operation at L+1 with default inputs; tokens
 *   t1 and t2.
 * - U (an object module with a backed field): the issuing operation at time 0 returned u, a token no entry backs, for
 *   input values Silicon may choose. Then each operation at time 0 with default inputs, and the same call again;
 *   token u.
 * Each scenario gives a result cell (ID ending in r) if the final operation has a result or may raise, and a state cell
 * (ID ending in s) on k: whether the entry is missing, holds a named value, holds the final step's new value, or holds
 * another.
 * A cell at H whose clock is L is a boundary cell.
 *
 * Throws [SidecarException] for a sidecar the rule cannot read; the generated text is parsed and checked against the
 * sidecar, so a cell the checks reject is a defect of this rule.
 */
fun generateCells(sidecar: Sidecar): GeneratedCells = CellGenerator(sidecar).run()

/** What the rule does with a free value: see [generateCells]. */
private sealed interface Role {
    data object Clock : Role

    data object Instance : Role

    data class Choice(
        val type: String,
        val values: List<String>,
    ) : Role

    data object Token : Role

    data class Param(
        val type: String,
    ) : Role
}

private data class Input(
    val name: String,
    val role: Role,
)

private enum class Result { BOOL, ISSUED }

/** An operation as the rule sees it. [contracts] are its trusted contracts over its free value names. */
private class Op(
    val name: String,
    val inputs: List<Input>,
    val result: Result?,
    val raises: Boolean,
    val contracts: List<String>,
)

/** A call in a cell: [args] maps every free value name of [op] to its value text. */
private class Call(
    val op: Op,
    val key: String,
    val args: Map<String, String>,
) {
    val text: String get() = "${op.name}($key${op.inputs.joinToString("") { ", ${it.name} = ${args.getValue(it.name)}" }})"

    fun step(outs: List<String>) = (if (outs.isEmpty()) "" else "${outs.joinToString()} := ") + text
}

/** The steps before the final step of a cell, and what the final steps of [CellGenerator.prefixCells] may use. */
private class Prefix(
    val letter: Char,
    val words: String,
    val params: List<Pair<String, String>>,
    val steps: List<String>,
    val held: List<String>,
    val tokens: List<String>,
    val times: List<Long>,
    val key: String,
    val allChoices: Boolean,
    val repeat: Call? = null,
)

private class CellGenerator(
    private val s: Sidecar,
) {
    private fun fail(message: String): Nothing = throw SidecarException(listOf(SidecarError(s.modules.firstOrNull()?.pos ?: kv.sidecar.SPos(s.file, 1, 1), "cells", message)))

    private val state = s.states.filter { it.kind is StateKind.MapOf }.singleOrNull() ?: fail("the cell rule needs exactly one state map")
    private val ms = state.name.text
    private val mapKind = state.kind as StateKind.MapOf
    private val keyType = mapKind.key.text
    private val ghost = s.ghosts.singleOrNull()
    private val obj = ghost != null
    private val index = ghost?.index?.text
    private val g = ghost?.name?.text
    private val issuedFlag = ghost?.fields?.singleOrNull { it.kind == GhostKind.SET_ONCE }?.name?.text
    private val template = s.types.any { it.name.text == index && it.template != null }
    private val nullText = if (obj) "NULL" else "null"

    private fun trusted(name: String): TrustedDecl = s.trusted.singleOrNull { it.shortName == name } ?: fail("no trusted fun $name")

    private fun issueRule(op: OperationDecl): IssueItem? = op.items.filterIsInstance<IssueItem>().singleOrNull()?.takeIf { it.result.text == op.result?.name?.text }

    /** An instance module's issuing operation calls a `returns fresh` function of the map's value class. */
    private fun makesFresh(op: OperationDecl) = op.free.any { (it.source as? FreeSource.Call)?.let { c -> trusted(c.name.text).fresh?.text == mapKind.value.text } == true }

    private val issuerDecl: OperationDecl? = s.operations.firstOrNull { if (obj) issueRule(it) != null else makesFresh(it) }

    /** The frozen Bool ghost field the issue rule sets to `carried(S, k, result)` (the emitter's backed field). */
    private val backed: String? =
        issuerDecl?.let { op ->
            val res = Regex.escape(op.result!!.name.text)
            issueRule(op)?.fields?.firstOrNull { (_, v) -> Regex("""carried\(\s*S\s*,\s*k\s*,\s*$res\s*\)""").matches(v.text.trim()) }?.first?.text
        }

    /** Int free values read per call: local, param, or a `= clock` trusted call. */
    private fun clockCandidate(
        source: FreeSource,
        type: String,
    ) = type == "Int" && (source is FreeSource.Local || source is FreeSource.Param || (source is FreeSource.Call && trusted(source.name.text).clock))

    /** The lease field and its issue value, if the issue rule computes a frozen Int field from a clock input. */
    private val leaseRule: Pair<String, String>? =
        issuerDecl?.let { op ->
            val clocks = op.free.filter { clockCandidate(it.source, it.type?.text ?: "Int") }.flatMap { i -> i.names.map { it.text } }.toSet()
            issueRule(op)?.fields?.firstOrNull { (f, v) ->
                ghost!!.fields.any { it.name.text == f.text && it.kind == GhostKind.FROZEN && it.type.text == "Int" } &&
                    Regex("""[A-Za-z_]\w*""").findAll(v.text).any { it.value in clocks }
            }?.let { (f, v) -> f.text to v.text.trim() }
        }

    private fun choice(type: String): Role =
        when (type) {
            "Bool" -> Role.Choice(type, listOf("false", "true"))
            "Int" -> Role.Choice(type, CellRule.TRUSTED_INTS.map { it.toString() })
            index -> Role.Token
            else -> Role.Param(type)
        }

    private fun role(
        source: FreeSource,
        type: String,
    ): Role =
        when {
            clockCandidate(source, type) -> if (leaseRule != null) Role.Clock else Role.Choice(type, listOf("0"))
            type == "Int" && source is FreeSource.This -> Role.Instance
            else -> choice(type)
        }

    private val ops: List<Op> =
        s.operations.map { op ->
            val inputs = mutableListOf<Input>()
            val contracts = mutableListOf<String>()
            for (item in op.free) {
                val src = item.source
                if (src is FreeSource.Call && !trusted(src.name.text).clock) {
                    val t = trusted(src.name.text)
                    val types = (t.returns + t.ghosts).map { it.type.text } + listOfNotNull("Bool".takeIf { t.throws })
                    item.names.forEachIndexed { i, n -> inputs += Input(n.text, choice(types.getOrElse(i) { fail("${op.name.text}: free value ${n.text} has no value in ${t.fn.text}") })) }
                    val renaming = t.callValues.zip(item.names.map { it.text }).toMap()
                    t.ensures.forEach { e -> contracts += rename(e.text, renaming) }
                } else {
                    item.names.forEach { n -> inputs += Input(n.text, role(src, item.type?.text ?: "Int")) }
                }
            }
            val result = op.result?.let { if (if (obj) issueRule(op) != null else makesFresh(op)) Result.ISSUED else Result.BOOL }
            // DESIGN-B.md 5.1: only an instance module has `exc`; a call to a throwing trusted fun or an ensures names it.
            val raises =
                !obj &&
                    (
                        op.free.any { (it.source as? FreeSource.Call)?.let { c -> trusted(c.name.text).throws } == true } ||
                            op.items.filterIsInstance<EnsuresItem>().any { Regex("""(?<![.\w])exc(?!\w)""").containsMatchIn(it.expr.text) }
                    )
            Op(op.name.text, inputs, result, raises, contracts)
        }
    private val issuer: Op? = issuerDecl?.let { d -> ops.single { it.name == d.name.text } }

    /** The input combinations of [op]'s Choice inputs that keep its trusted contracts, the default first. */
    private fun choices(op: Op): List<Map<String, String>> {
        val inputs = op.inputs.filter { it.role is Role.Choice }
        var combos = listOf(emptyMap<String, String>())
        for (i in inputs) combos = combos.flatMap { c -> (i.role as Role.Choice).values.map { v -> c + (i.name to v) } }
        return combos.filter { c -> op.contracts.all { evalExpr(it, c.mapValues { (_, v) -> literal(v) }) != false } }
    }

    private fun literal(v: String): Any = v.toLongOrNull() ?: (v == "true")

    /** The arguments of [op]: [choice] for its Choice inputs, clock [time], the instance values, [token], and [params] for others. */
    private fun args(
        op: Op,
        choice: Map<String, String>,
        time: Long,
        token: String?,
        params: (Input) -> String,
    ): Map<String, String> =
        op.inputs.associate { i ->
            i.name to
                when (val r = i.role) {
                    Role.Clock -> "$time"
                    Role.Instance -> "${CellRule.INSTANCE_INT}"
                    is Role.Choice -> choice.getValue(i.name)
                    Role.Token -> token ?: fail("${op.name}: the cell rule has no token for input ${i.name} here")
                    is Role.Param -> params(i)
                }
        }

    private fun Op.has(role: (Role) -> Boolean) = inputs.any { role(it.role) }

    /** Parameters for inputs of other types: one per input, named after it and the step [n]. */
    private fun paramArgs(
        n: Int,
        params: MutableList<Pair<String, String>>,
    ): (Input) -> String =
        { i ->
            val p = "${i.name}$n"
            params += p to (i.role as Role.Param).type
            p
        }

    private fun holds(
        key: String,
        v: String,
    ) = if (obj) "carried(S, $key, $v)" else "has(S, $key) && S.$ms[$key] == $v"

    private fun notHolds(
        key: String,
        v: String,
    ) = if (obj) "!carried(S, $key, $v)" else "!(has(S, $key) && S.$ms[$key] == $v)"

    /** The value a backed issued value satisfies right after the issuing call on [key]. */
    private fun backedText(
        key: String,
        v: String,
    ) = if (backed != null) "$g($v).$backed" else holds(key, v)

    // ---- the vocabulary and the set-up ----

    private val lease: Long? =
        leaseRule?.let { (_, value) ->
            val env = issuer!!.inputs.associate { i -> i.name to setupValue(i) }
            (evalExpr(value, env) as? Long) ?: fail("the lease value '$value' is not an Int at the set-up")
        }

    private fun setupValue(i: Input): Any? =
        when (val r = i.role) {
            Role.Clock -> 0L
            Role.Instance -> CellRule.INSTANCE_INT
            is Role.Choice -> literal(r.values.first())
            else -> null
        }

    private val times: List<Long> = lease?.let { listOf(it / 2, it, it + 1) } ?: listOf(0L)
    private val before = times.first()
    private val after = times.last()

    /** The set-up call: the issuing operation on k at time 0 with default inputs. */
    private val setup: Call? =
        issuer?.let { op ->
            if (op.has { it == Role.Token }) fail("the issuing operation ${op.name} takes a token: the cell rule has none for the set-up")
            if (op.has { it is Role.Param }) fail("the issuing operation ${op.name} takes a value of another type: the cell rule has none for the set-up")
            Call(op, "k", args(op, choices(op).firstOrNull() ?: fail("no input combination of ${op.name} keeps its trusted contracts"), 0L, null) { error("no param") })
        }

    private fun excOut(
        op: Op,
        name: String,
    ) = listOfNotNull(name.takeIf { op.raises })

    private fun normal(
        op: Op,
        e: String,
    ) = if (op.raises) "!$e && " else ""

    private val setupSteps: List<String> =
        setup?.let { c -> listOf(c.step(listOf("t1") + excOut(c.op, "e1")), "assume ${normal(c.op, "e1")}t1 != $nullText && ${backedText("k", "t1")}") }.orEmpty()

    private val setupWords: String =
        setup?.let { c -> "Set-up: t1 := ${c.text} returned " + (if (backed != null) "a token k's entry backs" else "a value k's entry holds") + (lease?.let { " (lease $it)" } ?: "") }.orEmpty()

    // ---- prefixes ----

    private fun prefixes(): List<Prefix> {
        val out = mutableListOf(Prefix('E', "Empty module", emptyList(), emptyList(), emptyList(), listOf("x"), listOf(0L), "k", allChoices = true))
        if (setup == null) return out
        out += Prefix('H', setupWords, emptyList(), setupSteps, listOf("t1"), listOf("t1", "x"), times, "k", allChoices = true)
        out += Prefix('K', "$setupWords; k2 is another key", listOf("k2" to keyType), setupSteps + "assume k2 != k", listOf("t1"), listOf("t1", "x"), listOf(before), "k2", allChoices = false)
        for (op in ops) {
            val params = mutableListOf<Pair<String, String>>()
            val call = Call(op, "k", args(op, choices(op).first(), after, "t1", paramArgs(1, params)))
            val issues = op.result == Result.ISSUED
            val outs = listOfNotNull(if (issues) "t2" else if (op.result != null) "o1" else null) + excOut(op, "e2")
            val assume = "assume ${normal(op, "e2")}" + (if (issues) "t2 != $nullText && " else "") + notHolds("k", "t1")
            val words = "$setupWords; then ${call.text}" + (if (issues) " returned t2" else "") + " and k's entry no longer holds t1"
            out += Prefix('O', words, params, setupSteps + call.step(outs) + assume, listOfNotNull("t1", "t2".takeIf { issues }), listOfNotNull("t1", "t2".takeIf { issues }), listOf(after), "k", allChoices = false)
        }
        val b = backed
        if (obj && b != null) {
            val op = issuer!!
            val params = mutableListOf<Pair<String, String>>()
            // The Choice inputs are parameters: Silicon may pick any values that keep the trusted contracts.
            val free = op.inputs.filter { it.role is Role.Choice }.associate { it.name to "${it.name}0" }
            op.inputs.filter { it.name in free }.forEach { params += free.getValue(it.name) to (it.role as Role.Choice).type }
            val call = Call(op, "k", args(op, free, 0L, null, paramArgs(1, params)))
            val contracts = op.contracts.map { "assume ${rename(it, free)}" }
            val steps = contracts + call.step(listOf("u")) + "assume u != $nullText && !$g(u).$b"
            val some = if (free.isEmpty()) "" else " (for some ${free.values.joinToString()})"
            out += Prefix('U', "u := ${call.text} returned a token no entry backs$some", params, steps, emptyList(), listOf("u"), listOf(0L), "k", allChoices = false, repeat = call)
        }
        return out
    }

    // ---- cells ----

    private val cells = mutableListOf<GeneratedCell>()

    /** The last scenario number of each prefix letter: the O prefixes (one per operation) share one numbering. */
    private val numbers = mutableMapOf<Char, Int>()

    fun run(): GeneratedCells {
        val all = prefixes()
        for (p in all) prefixCells(p)
        val text = (header(all.map { it.letter }.distinct()) + cells.map { it.text }).joinToString("\n") + "\n"
        val parsed =
            try {
                parseCells("generated.cells", text)
            } catch (e: SidecarException) {
                throw IllegalStateException("kv spec cells wrote a cell file it cannot read (a tool defect): ${e.message}\n$text", e)
            }
        val errors = checkCells(s, parsed)
        check(errors.isEmpty()) { "kv spec cells wrote cells the checks reject (a tool defect):\n${errors.joinToString("\n")}" }
        return GeneratedCells(cells.toList(), notes(), text)
    }

    /** The final steps of prefix [p], numbered on from its letter's last number, each with a result and a state cell. */
    private fun prefixCells(p: Prefix) {
        val finals = mutableListOf<Triple<Call, List<Pair<String, String>>, Long?>>()
        for (op in ops) {
            val combos = if (p.allChoices) choices(op) else choices(op).take(1)
            val clocked = op.has { it == Role.Clock }
            for (c in combos) {
                for (time in if (clocked) p.times else listOf(p.times.first())) {
                    for (token in if (op.has { it == Role.Token }) p.tokens else listOf(null)) {
                        val params = mutableListOf<Pair<String, String>>()
                        finals += Triple(Call(op, p.key, args(op, c, time, token, paramArgs(2, params))), params, time.takeIf { clocked })
                    }
                }
            }
        }
        p.repeat?.let { r -> if (finals.none { it.first.text == r.text }) finals += Triple(r, emptyList(), null) }
        for ((call, params, time) in finals) scenario(p, numbers.merge(p.letter, 1, Int::plus)!!, call, params, time)
    }

    private fun scenario(
        p: Prefix,
        n: Int,
        call: Call,
        extra: List<Pair<String, String>>,
        time: Long?,
    ) {
        val op = call.op
        val usesX = op.inputs.any { it.role == Role.Token && call.args[it.name] == "x" }
        val x = if (usesX) listOf("x" to index!!) else emptyList()
        val params = listOf("k" to keyType) + p.params + extra + x
        val steps = p.steps + x.map { "assume " + (if (template) "x >= 0" else "x != NULL") + " && !$g(x).$issuedFlag" }
        val outs = listOfNotNull("r".takeIf { op.result != null }) + excOut(op, "e")
        val boundary = p.letter == 'H' && lease != null && time == lease
        val timeWords =
            if (time == null || lease == null || p.letter == 'E' || p.letter == 'U') {
                ""
            } else {
                when {
                    time < lease -> " (before the lease)"
                    time == lease -> " (at the lease)"
                    else -> " (after the lease)"
                }
            }
        val step = call.text + timeWords + (if (usesX) " with x, a token never issued" else "")
        val body = steps + call.step(outs)
        if (op.result != null || op.raises) {
            cell("${p.letter}${n}r", "${p.words}. $step: the result", boundary, params, body, resultOutcomes(op, call.key, p.held))
        }
        val newValue = op.result == Result.ISSUED && call.key == "k"
        cell("${p.letter}${n}s", "${p.words}. $step: k's entry", boundary, params, body, stateOutcomes(op, p.held, newValue))
    }

    private fun resultOutcomes(
        op: Op,
        key: String,
        held: List<String>,
    ): List<Pair<String, String>> {
        val n = normal(op, "e")
        val exc = listOfNotNull(("exc" to "e").takeIf { op.raises })
        return exc +
            when (op.result) {
                null -> listOf("returns" to "!e")
                Result.BOOL -> listOf("true" to "${n}r", "false" to "$n!r")
                Result.ISSUED ->
                    if (obj) {
                        // An issued token is new (the set-once issued flag), so it equals no token the prefix names.
                        listOf("null" to "${n}r == NULL") +
                            if (backed != null) {
                                listOf("backed" to "${n}r != NULL && $g(r).$backed", "unbacked" to "${n}r != NULL && !$g(r).$backed")
                            } else {
                                listOf("token" to "${n}r != NULL")
                            }
                    } else {
                        val fresh = "r != null" + held.joinToString("") { " && r != $it" }
                        listOf("null" to "${n}r == null") + held.map { "same_$it" to "${n}r == $it" } +
                            listOf("backed" to "$n$fresh && ${holds(key, "r")}", "unbacked" to "$n$fresh && ${notHolds(key, "r")}")
                    }
            }
    }

    /** The state of k's entry after the final step: missing, holding a value the prefix named, its new value [newValue], other. */
    private fun stateOutcomes(
        op: Op,
        held: List<String>,
        newValue: Boolean,
    ): List<Pair<String, String>> {
        val r = if (newValue) "${normal(op, "e")}r != $nullText" + held.joinToString("") { " && r != $it" } + " && " + holds("k", "r") else null
        return listOf("no_entry" to "!has(S, k)") + held.map { "holds_$it" to holds("k", it) } + listOfNotNull(r?.let { "holds_r" to it }) +
            ("other" to (listOf("has(S, k)") + held.map { notHolds("k", it) } + listOfNotNull(r?.let { "!($it)" })).joinToString(" && "))
    }

    private fun cell(
        id: String,
        title: String,
        boundary: Boolean,
        params: List<Pair<String, String>>,
        steps: List<String>,
        outcomes: List<Pair<String, String>>,
    ) {
        val text =
            buildString {
                append("cell $id(${params.joinToString { "${it.first}: ${it.second}" }}) \"$title\"${if (boundary) " boundary" else ""} {\n")
                steps.forEach { append("  $it\n") }
                outcomes.forEach { (name, e) -> append("  outcome $name: $e\n") }
                append("}\n")
            }
        cells += GeneratedCell(id, title, boundary, text)
    }

    // ---- the header ----

    private fun notes(): List<String> =
        buildList {
            add("state map $ms: Map<$keyType, ${mapKind.value.text}>; " + if (obj) "object module (ghost record ${ghost!!.name.text}($index)${backed?.let { ", backed field $it" } ?: ""})" else "instance module (no ghost record)")
            add("issuing operation: " + (issuer?.let { "${it.name} (set-up: ${setup!!.text})" } ?: "none, so no set-up: only prefix E"))
            add("lease: " + (leaseRule?.let { (f, v) -> "$f = $v, $lease at the set-up; times ${times.joinToString()} (before, at, after)" } ?: "none, so clock inputs are not varied"))
            for (op in ops) {
                val roles =
                    op.inputs.joinToString { i ->
                        i.name + " " +
                            when (val r = i.role) {
                                Role.Clock -> "clock"
                                Role.Instance -> "instance value ${CellRule.INSTANCE_INT}"
                                is Role.Choice -> r.values.joinToString(prefix = "{", postfix = "}")
                                Role.Token -> "token"
                                is Role.Param -> "parameter of type ${r.type}"
                            }
                    }
                val res = op.result?.let { if (it == Result.ISSUED) "issued value" else "Bool" } ?: "no result"
                add("operation ${op.name}($roles) -> $res${if (op.raises) ", may raise" else ""}" + op.contracts.joinToString(prefix = "; trusted contracts: ").takeIf { op.contracts.isNotEmpty() }.orEmpty())
            }
        }

    /** The header of the cell file: the vocabulary and the prefixes with [letters]. */
    private fun header(letters: List<Char>): List<String> {
        val words =
            mapOf(
                'E' to "E the empty module", 'H' to "H the set-up", 'K' to "K the set-up, then a step on another key k2",
                'O' to "O the set-up, then a step after which k's entry no longer holds t1", 'U' to "U a token no entry backs",
            )
        return listOf(
            "// Scenario cells generated by `kv spec cells` from ${s.file.substringAfterLast('/')} (DESIGN-C.md 3.2, C4). Generate them again; do not edit.",
            "// Vocabulary the rule read from the sidecar:",
        ) + notes().map { "//   $it" } +
            listOf(
                "// Prefixes: ${letters.joinToString("; ") { words.getValue(it) }}.",
                "// IDs end in r (the result) or s (k's entry). A cell marked boundary has a clock reading equal to the lease",
                "// (DESIGN-C.md 3.3 rule 5).",
                "",
            )
    }
}

/** [text] with each identifier that is a key of [renaming] replaced; a field name after `.` stays. */
private fun rename(
    text: String,
    renaming: Map<String, String>,
): String = Regex("""(?<![.\w])[A-Za-z_]\w*""").replace(text) { renaming[it.value] ?: it.value }

/** Thrown by [evalExpr] for text it does not read. */
private class Unknown : Exception()

/**
 * The value of the Viper expression [text] over the constants [env] (Long or Boolean), or null if the text has
 * anything else: a function, a field, a name not in [env]. It reads integers, true, false, names, parentheses,
 * `! -` (unary), `* / %`, `+ -`, `== != < <= > >=`, `&&`, `||` and `==>`.
 */
internal fun evalExpr(
    text: String,
    env: Map<String, Any?>,
): Any? {
    val tokens = Regex("""==>|==|!=|<=|>=|&&|\|\||[()!<>+\-*/%]|\d+|[A-Za-z_]\w*|\S""").findAll(text).map { it.value }.toList()
    var i = 0

    fun peek() = tokens.getOrNull(i)

    fun next() = tokens.getOrNull(i++) ?: throw Unknown()

    fun long(v: Any?) = v as? Long ?: throw Unknown()

    fun bool(v: Any?) = v as? Boolean ?: throw Unknown()

    lateinit var impl: () -> Any?

    fun atom(): Any? {
        val t = next()
        return when {
            t == "(" -> impl().also { if (next() != ")") throw Unknown() }
            t[0].isDigit() -> t.toLong()
            t == "true" || t == "false" -> t == "true"
            t[0].isLetter() || t[0] == '_' -> if (peek() == "(" || t !in env) throw Unknown() else env[t] ?: throw Unknown()
            else -> throw Unknown()
        }
    }

    fun unary(): Any? =
        when (peek()) {
            "!" -> next().let { !bool(unary()) }
            "-" -> next().let { -long(unary()) }
            else -> atom()
        }

    fun mul(): Any? {
        var v = unary()
        while (peek() in setOf("*", "/", "%")) {
            val op = next()
            val b = long(unary())
            v = if (op == "*") long(v) * b else if (b == 0L) throw Unknown() else if (op == "/") long(v) / b else long(v) % b
        }
        return v
    }

    fun add(): Any? {
        var v = mul()
        while (peek() == "+" || peek() == "-") v = if (next() == "+") long(v) + long(mul()) else long(v) - long(mul())
        return v
    }

    fun cmp(): Any? {
        val a = add()
        val op = peek()
        if (op !in setOf("==", "!=", "<", "<=", ">", ">=")) return a
        next()
        val b = add()
        return when (op) {
            "==" -> a == b
            "!=" -> a != b
            "<" -> long(a) < long(b)
            "<=" -> long(a) <= long(b)
            ">" -> long(a) > long(b)
            else -> long(a) >= long(b)
        }
    }

    fun and(): Any? {
        var v = cmp()
        while (peek() == "&&") v = next().let { bool(v) and bool(cmp()) }
        return v
    }

    fun or(): Any? {
        var v = and()
        while (peek() == "||") v = next().let { bool(v) or bool(and()) }
        return v
    }
    impl = {
        val a = or()
        // Read the right side before the test: a short-circuit would leave its tokens unread.
        if (peek() == "==>") next().let { impl().let { b -> !bool(a) || bool(b) } } else a
    }
    return try {
        impl().also { if (i != tokens.size) throw Unknown() }
    } catch (e: Unknown) {
        null
    }
}
