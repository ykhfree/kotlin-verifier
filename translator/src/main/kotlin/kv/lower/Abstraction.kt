package kv.lower

import kv.kvir.Pos

/**
 * Abstract types of String values (DESIGN-v0.md 2.13): a union-find over String-carrying slots. A slot is a FIR
 * variable or function symbol (a function stands for its return value) or a FIR expression. Each class collects its
 * fixed points (sidecar type names such as Key and Token); a class needs exactly one.
 */
internal class Abstraction {
    private val parent = HashMap<Any, Any>()
    private val fixed = HashMap<Any, MutableMap<String, Pos>>()

    fun find(x: Any): Any {
        var root = x
        while (true) root = parent[root] ?: break
        var y = x
        while (y != root) y = parent.put(y, root)!!
        return root
    }

    /** Puts [a] and [b] in one class. A null slot (the `null` literal) carries no abstraction. */
    fun union(
        a: Any?,
        b: Any?,
    ) {
        if (a == null || b == null) return
        val ra = find(a)
        val rb = find(b)
        if (ra == rb) return
        parent[ra] = rb
        fixed.remove(ra)?.forEach { (type, pos) -> fixed.getOrPut(rb) { linkedMapOf() }.putIfAbsent(type, pos) }
    }

    /** Records that [x] has the sidecar type [type]; [pos] is where that is first known. */
    fun fix(
        x: Any?,
        type: String,
        pos: Pos,
    ) {
        if (x == null) return
        fixed.getOrPut(find(x)) { linkedMapOf() }.putIfAbsent(type, pos)
    }

    /** The fixed points of [x]'s class, each with the position that fixed it. */
    fun types(x: Any): Map<String, Pos> = fixed[find(x)].orEmpty()
}
