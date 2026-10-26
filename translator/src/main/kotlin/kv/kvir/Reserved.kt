package kv.kvir

/** Names the tool uses in the Viper output (DESIGN-v0.md 4.6, 5.1, 5.5). Sidecar names must not reuse them. */
object Reserved {
    /**
     * Bound variables and predefined names: module object, action key, quantified variables, the null token, and
     * (DESIGN-B.md 5.1) the exception outcome `exc` and the Viper `null`.
     */
    val names = setOf("S", "k", "qt", "qk", "qr", "NULL", "exc", "null")

    /**
     * Macros, functions, fields, locals and methods the tool generates; `gFor` is the creation ledger (DESIGN-B.md
     * 4.1), `gPend` and `MI_PEND` the reservations of reserve steps (DESIGN-D.md 6.6).
     */
    val tool = setOf("has", "carried", "created", "gFor", "pre_tok", "GHOST_PERMS", "MI_PERMS", "MI_OWN", "MI_INV", "MI", "HIST", "init", "probe", "gPend", "MI_PEND")

    /** The prefix of the method of a reserve step (DESIGN-D.md 6.6): `reserve_<operation>`. No operation name starts with it. */
    const val RESERVE_PREFIX = "reserve_"
}
