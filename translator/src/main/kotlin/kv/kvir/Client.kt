package kv.kvir

// KVIR additions for client modules (DESIGN-E 6.3): entries, tasks, trusted calls with exception outcomes, try,
// throw, spawn, loops and inlined helpers. Object and instance modules never hold these nodes.

/** An exception kind (DESIGN-E 3.5): a library kind ([LibExc]) or a kind the code throws itself ([Thrown]). */
sealed interface ExcKind {
    val label: String
}

/**
 * The library kinds: RejectedExecutionException, another RuntimeException, a checked exception other than
 * InterruptedException, InterruptedException, and java.lang.Error.
 */
enum class LibExc : ExcKind {
    REE,
    RTE,
    CHK,
    INTR,
    ERR,
    ;

    override val label: String get() = name
}

/**
 * `throw C(E.X, ...)`: an exception of class [cls] (simple name) whose first argument is the enum entry [code], or
 * no code. [runtime] says C is a RuntimeException (a `catch (e: RuntimeException)` catches it).
 */
data class Thrown(
    val cls: String,
    val code: String?,
    val runtime: Boolean,
) : ExcKind {
    override val label: String get() = "$cls${code?.let { "($it)" } ?: ""}"
}

/** A sidecar `enum` (DESIGN-E 6.2): [entries] are Int constants in declaration order. */
data class EnumDef(
    val name: String,
    val kotlin: String,
    val entries: List<String>,
)

/** A sidecar `obligation` (DESIGN-E 3.1): a linear obligation over [types]. */
data class ObligationDef(
    val name: String,
    val types: List<KType>,
)

/**
 * A public function of a client module (DESIGN-E 6.1): one thread's run. [params] are the parameters it reads as
 * values; [free] its instance values; [result] the sidecar `result`. A Return in [body] outside every [Inline] ends it.
 */
data class EntryFun(
    val name: String,
    val kotlin: String,
    val pos: Pos,
    val params: List<Local>,
    val free: List<FreeValue>,
    val result: Local?,
    val body: List<Stmt>,
    val status: Status,
    /** Ids of the FIR nodes the entry and the helpers it inlined translated (W14). */
    val translated: Set<Int>,
)

/**
 * `cond ==> NAME(args)` of a task precondition (DESIGN-E 3.4 part 1): the obligation a release call of the task names,
 * over the task's parameters; [cond] is the non-null test of its nullable arguments (null: none).
 */
data class Holds(
    val obligation: String,
    val args: List<Expr>,
    val cond: Expr?,
    val pos: Pos,
)

/**
 * A lambda handed to an executor (DESIGN-E 3.4): it runs later, on another thread, as its own unit. [params] are the
 * captured values it reads, [free] the instance values; its precondition is [requires] (captured constants and the
 * conditions around the spawn over captured values, parts 2 and 3) and [holds] (part 1). [via] lists the helpers whose
 * function-typed parameters carried the lambda to the spawn.
 */
data class Task(
    val id: String,
    val pos: Pos,
    val inFunction: String,
    val via: List<String>,
    val spawnedBy: String,
    val params: List<Local>,
    val free: List<FreeValue>,
    val requires: List<Expr>,
    val holds: List<Holds>,
    val body: List<Stmt>,
    val status: Status,
    val translated: Set<Int>,
)

/** The obligation a [Call] creates or consumes (DESIGN-E 3.1), over the call's arguments and results. */
data class ObligationOp(
    val creates: Boolean,
    val obligation: String,
    val args: List<Expr>,
    /** A creation happens only if this result is not null (`creates ... if t != null`). */
    val ifNotNull: Local?,
)

/**
 * A call of a trusted declaration or a built-in client model (DESIGN-E 3.3): it returns with its results in [outs]
 * (each declares a new local or assigns a mutable one in scope, W4), or throws one of [throws]; an Error is A1's.
 */
data class Call(
    val outs: List<Local>,
    val fn: String,
    val args: List<Expr>,
    val throws: List<ExcKind>,
    val obligation: ObligationOp?,
    override val pos: Pos,
) : Stmt

/** `catch (e: [type])`: catches the kinds [kinds] (DESIGN-E 3.5). */
data class Catch(
    val type: String,
    val kinds: List<ExcKind>,
    val body: List<Stmt>,
    val pos: Pos,
)

/**
 * `try { body } catch ... finally { fin }`: an exception of [body] goes to the first catch of its kind, else past the
 * try; [fin] runs on every path, and an exception or a Return in it replaces the pending one (Kotlin semantics).
 */
data class Try(
    val body: List<Stmt>,
    val catches: List<Catch>,
    val fin: List<Stmt>?,
    override val pos: Pos,
) : Stmt

/** Throws an exception of [kind]; null rethrows the exception its catch caught. */
data class Throw(
    val kind: ExcKind?,
    override val pos: Pos,
) : Stmt

/**
 * Hands task [task] to an executor with [model] (DESIGN-E 3.4): the call may throw [throws] (then the task never
 * runs); otherwise the task runs later with [args] as its parameters, and the caller gives up its precondition.
 */
data class Spawn(
    val task: String,
    val args: List<Expr>,
    val model: String,
    val throws: List<ExcKind>,
    override val pos: Pos,
) : Stmt

/**
 * `while (cond) body`. [hole] names the sidecar `hole loop` of this loop (DESIGN-E 6.8); [measure] is the termination
 * measure the tool derives from a condition `v < bound` (`bound - v`), null for another condition.
 */
data class While(
    val cond: Expr,
    val body: List<Stmt>,
    val hole: String?,
    val measure: Expr?,
    override val pos: Pos,
) : Stmt

/** Leaves the innermost While. */
data class Break(
    override val pos: Pos,
) : Stmt

/**
 * An inlined call of private helper [fn] (DESIGN-E 6.4, JE2): a Return in [body] that no inner Inline holds leaves
 * this block, and its value goes to [out] (null for a helper without a value). [out] is declared by the block.
 */
data class Inline(
    val fn: String,
    val out: Local?,
    val body: List<Stmt>,
    override val pos: Pos,
) : Stmt
