package kv.emit

import kv.kvir.Pos
import kv.sidecar.SPos
import kv.sidecar.Vexpr

// The position table of DESIGN-v0.md 5.7: each range of the emitted Viper text names where it comes from.

/** A range of the Viper text: 1-based lines and columns, both ends included (Silicon's `@L.C--L2.C2`). */
data class VprRange(
    val line: Int,
    val col: Int,
    val endLine: Int,
    val endCol: Int,
) {
    fun contains(
        l: Int,
        c: Int,
    ): Boolean = (l > line || l == line && c >= col) && (l < endLine || l == endLine && c <= endCol)

    override fun toString(): String = "$line.$col--$endLine.$endCol"
}

/** Where a range of the Viper text comes from. */
sealed interface Source {
    /** Kotlin code; [note] names a tool obligation the range holds, e.g. that a kotlinx Semaphore.release may over-release. */
    data class Kotlin(
        val pos: Pos,
        val note: String? = null,
    ) : Source {
        override fun toString(): String = "kotlin(${pos.file.substringAfterLast('/')}:${pos.line}:${pos.col}${note?.let { ", $it" } ?: ""})"
    }

    /**
     * A sidecar item: [id] names it (a property ID, `issue tryLock`, `probe`). [note] is the comment of a hole
     * conjunct. [expr] is set when the range holds exactly that expression's text, so that a column maps back.
     */
    data class Sidecar(
        val pos: SPos,
        val id: String,
        val note: String? = null,
        val expr: Vexpr? = null,
    ) : Source {
        override fun toString(): String = "sidecar(${pos.file.substringAfterLast('/')}:${pos.line}:${pos.col}, $id)"
    }

    /** Text the tool makes from no single source item, e.g. `MI_PERMS` or a default allowance clause. */
    data class Generated(
        val what: String,
    ) : Source {
        override fun toString(): String = "generated($what)"
    }
}

data class Mapped(
    val range: VprRange,
    val source: Source,
)

enum class MethodKind { ACTION, STRAY, INIT, LEMMA, PROBE }

/**
 * One emitted Viper method. [label] is the name in reports (operation name, stray action id, `init`, `lemma P3c`,
 * `probe`); [kotlin] is the compute call of an action.
 */
data class EmittedMethod(
    val name: String,
    val kind: MethodKind,
    val label: String,
    val kotlin: Pos?,
    val range: VprRange,
)

/**
 * One `refute false` of the branch reachability program (DESIGN-v0.md 5.4 item 4, M9) at Viper line [line]: the
 * branch [what] of the Kotlin construct at [pos] in action [action].
 */
data class BranchProbe(
    val line: Int,
    val action: String,
    val pos: Pos,
    val what: String,
)

/**
 * The Viper text, its position table and its methods. [warnings] are for the report (DESIGN-v0.md 5.4). [branches]
 * are the refute probes of a reachability program, empty in the verified program.
 */
data class Emitted(
    val text: String,
    val ranges: List<Mapped>,
    val methods: List<EmittedMethod>,
    val warnings: List<String>,
    val branches: List<BranchProbe> = emptyList(),
) {
    /** The innermost range that contains the Viper position (DESIGN-v0.md 5.7 step 2), or null. */
    fun sourceAt(
        line: Int,
        col: Int,
    ): Mapped? =
        ranges.filter { it.range.contains(line, col) }
            .maxWithOrNull(compareBy<Mapped>({ it.range.line }, { it.range.col }).thenByDescending { it.range.endLine }.thenByDescending { it.range.endCol })

    fun methodAt(line: Int): EmittedMethod? = methods.find { line in it.range.line..it.range.endLine }

    /** The position table as text, one range per line. */
    fun positionTable(): String = ranges.joinToString("") { "${it.range} ${it.source}\n" }
}

/** The sidecar position of Viper position ([line], [col]) inside [range], which holds exactly [expr]'s text. */
fun Vexpr.posInRange(
    range: VprRange,
    line: Int,
    col: Int,
): SPos {
    var l = range.line
    var c = range.col
    for ((offset, ch) in text.withIndex()) {
        if (l == line && c >= col || l > line) return posAt(offset)
        if (ch == '\n') {
            l++
            c = 1
        } else {
            c++
        }
    }
    return posAt(text.length)
}
