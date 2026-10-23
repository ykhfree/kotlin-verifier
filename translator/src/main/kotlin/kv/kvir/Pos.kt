package kv.kvir

/**
 * 1-based position in a Kotlin file. [fake] holds the KtFakeSourceElementKind name of a compiler-made node.
 * A node the tool makes carries the position of the Kotlin node that caused it (DESIGN-v0.md 1.1).
 */
data class Pos(
    val file: String,
    val line: Int,
    val col: Int,
    val fake: String? = null,
) {
    override fun toString(): String = "$file:$line:$col"
}
