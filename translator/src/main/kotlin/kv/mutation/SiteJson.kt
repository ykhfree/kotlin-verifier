package kv.mutation

import kv.json.json
import kv.sidecar.AllowanceItem
import kv.sidecar.EnsuresItem
import kv.sidecar.IssueItem
import kv.sidecar.SPos
import kv.sidecar.Sidecar
import java.io.File

/**
 * A documented property of the sidecar, for the survivor judge (MUTATION.md 6): [kind] is `ensures`, `ends_only_if`,
 * `unbacked_only_if`, `issue`, `invariant`, `lemma`, `set-once`, `trusted fun` or `ghost record`; [reviewed] is the
 * note with its source quote, if the item has one.
 */
data class Property(
    val kind: String,
    val operation: String?,
    val id: String,
    val text: String,
    val reviewed: String?,
    val at: SPos,
)

/** The properties of [s] in sidecar order: per operation its items, then the state properties and declarations. */
fun properties(s: Sidecar): List<Property> =
    buildList {
        for (op in s.operations) {
            for (item in op.items) {
                when (item) {
                    is EnsuresItem -> add(Property("ensures", op.name.text, item.id.text, item.expr.text, item.reviewed, item.pos))
                    is AllowanceItem -> add(Property(item.kind.keyword, op.name.text, item.id.text, item.expr.text, item.reviewed, item.pos))
                    is IssueItem -> add(Property("issue", op.name.text, item.result.text, item.fields.joinToString("; ") { (n, e) -> "${n.text} = ${e.text}" }, item.reviewed, item.pos))
                    else -> Unit
                }
            }
        }
        s.properties.forEach { add(Property(it.kind.name.lowercase(), null, it.id.text, it.expr.text, it.reviewed, it.pos)) }
        s.setOnce.forEach { add(Property("set-once", null, it.id.text, it.map.text, null, it.pos)) }
        s.trusted.forEach { t ->
            val claims =
                listOfNotNull(
                    "clock".takeIf { t.clock },
                    t.returns.takeIf { it.isNotEmpty() }?.let { r -> "returns " + r.joinToString { "${it.name.text}: ${it.type.text}" } },
                    t.fresh?.let { "returns fresh ${it.text}" },
                    "throws".takeIf { t.throws },
                    t.ghosts.takeIf { it.isNotEmpty() }?.let { g -> "ghost " + g.joinToString { "${it.name.text}: ${it.type.text}" } },
                ) + t.ensures.map { "ensures ${it.text}" } + listOfNotNull("effects none".takeIf { t.effectsNone })
            add(Property("trusted fun", null, t.fn.text, claims.joinToString("; "), t.reviewed, t.pos))
        }
        s.ghosts.forEach { g -> add(Property("ghost record", null, g.name.text, g.fields.joinToString("; ") { "${it.name.text}: ${it.type.text} ${it.kind.name.lowercase().replace('_', '-')}" }, null, g.pos)) }
    }

/** The output of `kv mutation-sites`: the module, its sites with paths relative to [root], and its properties. */
fun sitesJson(
    module: String,
    sidecar: File,
    root: File,
    sites: List<MutationSite>,
    properties: List<Property>,
): String {
    val base = root.absoluteFile.normalize()
    val sb = StringBuilder()
    sb.append("{\"module\": ").append(json(module)).append(", \"sidecar\": ").append(json(sidecar.path)).append(",\n \"sites\": [")
    sites.forEachIndexed { i, s ->
        val file = s.file.absoluteFile.normalize().relativeTo(base).invariantSeparatorsPath
        sb.append(if (i == 0) "\n  " else ",\n  ")
        sb.append("{\"operator\": ").append(json(s.operator.name)).append(", \"scope\": ").append(json(s.scope))
        sb.append(", \"file\": ").append(json(file)).append(", \"line\": ${s.pos.line}, \"col\": ${s.pos.col}, \"endLine\": ${s.end.line}, \"endCol\": ${s.end.col}")
        sb.append(", \"ledger\": ").append(json(s.ledger)).append(", \"edits\": [")
        sb.append(s.edits.joinToString(", ") { "{\"start\": ${it.start}, \"end\": ${it.end}, \"original\": ${json(it.original)}, \"replacement\": ${json(it.replacement)}}" })
        sb.append("], \"facts\": {").append(s.facts.entries.joinToString(", ") { "${json(it.key)}: ${json(it.value)}" }).append("}}")
    }
    sb.append("\n ],\n \"properties\": [")
    properties.forEachIndexed { i, p ->
        sb.append(if (i == 0) "\n  " else ",\n  ")
        sb.append("{\"kind\": ").append(json(p.kind)).append(", \"operation\": ").append(p.operation?.let(::json) ?: "null")
        sb.append(", \"id\": ").append(json(p.id)).append(", \"text\": ").append(json(p.text)).append(", \"reviewed\": ").append(p.reviewed?.let(::json) ?: "null")
        sb.append(", \"at\": ").append(json("${p.at.file.substringAfterLast('/')}:${p.at.line}")).append("}")
    }
    sb.append("\n ]}\n")
    return sb.toString()
}
