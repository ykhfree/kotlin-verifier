package kv.verify

import kv.emit.BranchProbe
import kv.emit.Emitted

/**
 * The result of the branch reachability check (DESIGN-v0.md 5.4 item 4, M9). It is INFO only: no verdict reads it.
 * [unreachable] are the branches whose `refute false` held, so no execution under MI reaches them. [notChecked]
 * says why the run gave no answer; [note] says what may make a branch look unreachable.
 */
data class Reachability(
    val probed: Int,
    val unreachable: List<BranchProbe>,
    val notChecked: String? = null,
    val note: String? = null,
)

private val REFUTE = Regex("""^Refute holds in all cases""")

/**
 * Reads the Silicon [run] of the reachability program [emitted] (5.7 table, M9 row): each "Refute holds in all
 * cases or could not be reached" error marks its probe unreachable. A killed or abnormal run, a prover timeout, or a
 * refute error that maps to no probe leaves the check without an answer. Other verification errors (failures that
 * the verified program reports too) are kept as a note: after one of them, a branch may look unreachable.
 */
fun reachability(
    emitted: Emitted,
    run: SiliconRun,
    budget: SiliconBudget,
): Reachability {
    val probed = emitted.branches.size

    fun none(reason: String) = Reachability(probed, emptyList(), notChecked = reason)
    val code = run.exitCode ?: return none("killed at the wall clock limit of ${budget.wallSeconds} s")
    if (JAVA_EXCEPTION.containsMatchIn(run.output) || ABORTED.containsMatchIn(run.output)) return none("Silicon stopped abnormally")
    val errors = parseErrors(run.output)
    when (code) {
        0 -> return if (SUCCESS.containsMatchIn(run.output) && errors.isEmpty()) Reachability(probed, emptyList()) else none("exit code 0 without the success line")
        1 -> if (SUMMARY.find(run.output)?.groupValues?.get(1)?.toInt() != errors.size) return none("the error summary does not match the error lines")
        else -> return none("exit code $code")
    }
    if (errors.any { it.isTimeout }) return none("a prover query was canceled")
    val (refutes, others) = errors.partition { REFUTE.containsMatchIn(it.message) }
    if (others.any { !it.isVerification }) return none("Silicon reported a parse, type or consistency error")
    val byLine = emitted.branches.associateBy { it.line }
    val unreachable = refutes.map { e -> byLine[e.line] ?: return none("a refute at Viper line ${e.line} is no branch probe") }
    val note = others.takeIf { it.isNotEmpty() }?.let { "the clone also reported ${it.size} other error(s); a branch after one of them may look unreachable" }
    return Reachability(probed, unreachable.sortedBy { it.line }, note = note)
}
