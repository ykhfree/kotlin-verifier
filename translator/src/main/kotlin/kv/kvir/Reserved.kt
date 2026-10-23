package kv.kvir

/** Names the tool uses in the Viper output (DESIGN-v0.md 4.6, 5.1, 5.5). Sidecar names must not reuse them. */
object Reserved {
    /** Bound variables and predefined names: module object, action key, quantified variables, the null token. */
    val names = setOf("S", "k", "qt", "qk", "qr", "NULL")

    /** Macros, functions, fields, locals and methods the tool generates. */
    val tool = setOf("has", "carried", "created", "pre_tok", "GHOST_PERMS", "MI_PERMS", "MI_OWN", "MI_INV", "MI", "HIST", "init", "probe")
}
