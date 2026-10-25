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
 * [String] a string, a [Long] or an [Int] a number, a [Boolean] and null as they are. No fractions: [parseJson] reads
 * only whole numbers.
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
 * Parses one JSON value: an object is a [Map] (keys in file order), an array a [List], a number a [Long] (the tool
 * writes no fractions), and `null` null. Throws [JsonException] with the offset of the first error.
 */
fun parseJson(text: String): Any? = JsonReader(text).document()

private class JsonReader(
    private val t: String,
) {
    private var i = 0

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

    private fun obj(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        i++
        ws()
        if (t.startsWith("}", i)) return out.also { i++ }
        while (true) {
            ws()
            if (!t.startsWith("\"", i)) fail("a key")
            val key = string()
            ws()
            if (!t.startsWith(":", i)) fail("':'")
            i++
            out[key] = value()
            ws()
            when {
                t.startsWith(",", i) -> i++
                t.startsWith("}", i) -> return out.also { i++ }
                else -> fail("',' or '}'")
            }
        }
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
        while (i < t.length && (t[i].isLetterOrDigit() || t[i] == '-')) i++
        return when (val w = t.substring(start, i)) {
            "true" -> true
            "false" -> false
            "null" -> null
            else -> w.toLongOrNull() ?: run {
                i = start
                fail("a value")
            }
        }
    }
}
