package kv.json

// The JSON the tool writes (kv mutation-sites, kv spec snapshot, kv spec check) and reads back (kv spec stale). No
// library: the files are small, and the CLI's classpath holds only the Kotlin compiler.

/**
 * [s] as a JSON string literal, escaped as Python's `json.dumps(s, ensure_ascii=False)` escapes it: non-ASCII
 * characters stay as they are.
 */
fun json(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\t' -> sb.append("\\t")
            c == '\r' -> sb.append("\\r")
            c == '\b' -> sb.append("\\b")
            c == '\u000c' -> sb.append("\\f")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
    }
    return sb.append('"').toString()
}

/**
 * [v] as JSON with one space of indent per level: a [Map] is an object (its keys in order), a [List] an array, a
 * [String] a string, a [Long] or an [Int] a number, a [Boolean] and null as they are. No fractions: the tool writes none.
 */
fun jsonOf(
    v: Any?,
    indent: String = "",
): String {
    val inner = "$indent "
    return when (v) {
        null -> "null"
        is String -> json(v)
        is Boolean, is Long, is Int -> "$v"
        is Map<*, *> -> if (v.isEmpty()) "{}" else v.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, x) -> "$inner${json(k as String)}: ${jsonOf(x, inner)}" }
        is List<*> -> if (v.isEmpty()) "[]" else v.joinToString(",\n", "[\n", "\n$indent]") { x -> "$inner${jsonOf(x, inner)}" }
        else -> throw IllegalArgumentException("no JSON form for ${v::class.simpleName}")
    }
}

class JsonException(
    message: String,
) : Exception(message)

/**
 * An object of a parsed text: a [Map] with its keys in file order that also knows its 1-based lines. [first] and [last]
 * are the lines of its braces. Two objects are equal if their maps are equal, so the lines do not matter there.
 */
class JsonObject(
    val first: Int,
) : LinkedHashMap<String, Any?>() {
    var last = first
        internal set
    private val keyLines = HashMap<String, Int>()

    /** The line of the key [key], or [first] if the object has no such key. */
    fun lineOf(key: String): Int = keyLines[key] ?: first

    internal fun put(
        key: String,
        line: Int,
        value: Any?,
    ) {
        keyLines[key] = line
        this[key] = value
    }
}

/**
 * Parses one JSON value: an object is a [JsonObject], an array a [List], a whole number that fits a [Long] a [Long],
 * any other number a [Double], and `null` null. Throws [JsonException] with the offset of the first error.
 */
fun parseJson(text: String): Any? = JsonReader(text).document()

/** The grammar of a JSON number. */
private val NUMBER = Regex("""-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?""")

private class JsonReader(
    private val t: String,
) {
    private var i = 0

    // The offset where each line starts: the line of an offset is a search here, not a scan of the text.
    private val lineStarts = listOf(0) + t.indices.filter { t[it] == '\n' }.map { it + 1 }

    private fun line(offset: Int): Int = lineStarts.binarySearch(offset).let { if (it >= 0) it + 1 else -it - 1 }

    private fun fail(what: String): Nothing = throw JsonException("JSON offset $i: expected $what")

    private fun ws() {
        while (i < t.length && t[i] in " \t\r\n") i++
    }

    fun document(): Any? {
        val v = value()
        ws()
        if (i != t.length) fail("the end of the text")
        return v
    }

    private fun value(): Any? {
        ws()
        if (i >= t.length) fail("a value")
        return when (t[i]) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            else -> word()
        }
    }

    private fun obj(): JsonObject {
        val out = JsonObject(line(i))
        i++
        ws()
        if (t.startsWith("}", i)) return out.close()
        while (true) {
            ws()
            if (!t.startsWith("\"", i)) fail("a key")
            val keyLine = line(i)
            val key = string()
            ws()
            if (!t.startsWith(":", i)) fail("':'")
            i++
            out.put(key, keyLine, value())
            ws()
            when {
                t.startsWith(",", i) -> i++
                t.startsWith("}", i) -> return out.close()
                else -> fail("',' or '}'")
            }
        }
    }

    /** Takes the closing brace at the offset and records its line in this object. */
    private fun JsonObject.close(): JsonObject {
        last = line(i)
        i++
        return this
    }

    private fun array(): List<Any?> {
        val out = mutableListOf<Any?>()
        i++
        ws()
        if (t.startsWith("]", i)) return out.also { i++ }
        while (true) {
            out += value()
            ws()
            when {
                t.startsWith(",", i) -> i++
                t.startsWith("]", i) -> return out.also { i++ }
                else -> fail("',' or ']'")
            }
        }
    }

    private fun string(): String {
        val sb = StringBuilder()
        i++
        while (true) {
            if (i >= t.length) fail("the end of a string")
            val c = t[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= t.length) fail("an escape")
                    when (val e = t[i++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'u' -> {
                            val hex = t.substring(i, minOf(i + 4, t.length))
                            sb.append(hex.toIntOrNull(16)?.takeIf { hex.length == 4 }?.toChar() ?: fail("four hex digits"))
                            i += 4
                        }
                        else -> fail("an escape character")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun word(): Any? {
        val start = i
        while (i < t.length && (t[i].isLetterOrDigit() || t[i] in "-+.")) i++
        return when (val w = t.substring(start, i)) {
            "true" -> true
            "false" -> false
            "null" -> null
            else -> {
                if (!NUMBER.matches(w)) {
                    i = start
                    fail("a value")
                }
                w.toLongOrNull() ?: w.toDouble()
            }
        }
    }
}
