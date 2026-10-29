package kv.verify

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The Silicon budget (DESIGN-v0.md 8.3): a timeout per prover assertion and per prover check, and a wall clock limit
 * for the whole run. A run over the wall clock is killed and is ERROR, never PROVEN.
 */
data class SiliconBudget(
    val assertTimeoutMs: Int = 20_000,
    val checkTimeoutMs: Int = 20_000,
    val wallSeconds: Long = 300,
)

/** One Silicon run: [exitCode] is null if the run was killed at the wall clock limit. */
data class SiliconRun(
    val exitCode: Int?,
    val output: String,
    val seconds: Double,
)

class SiliconMissing(
    message: String,
) : Exception(message)

/** Runs Silicon from a Viper release (`$VIPER_HOME/backends/viperserver.jar` and `$VIPER_HOME/z3/bin/z3`). */
object Silicon {
    /** VIPER_HOME, else the default install of the spike (spike/silicon.sh). */
    fun defaultHome(): File = File(System.getenv("VIPER_HOME") ?: "${System.getProperty("user.home")}/.local/opt/viper/v.26.08-release/tools")

    /** Throws [SiliconMissing] with what is missing. */
    fun check(home: File) {
        val missing = listOf(jar(home), z3(home)).filter { !it.isFile }
        if (missing.isNotEmpty()) {
            throw SiliconMissing("Silicon is not installed: missing ${missing.joinToString()}. Set VIPER_HOME (or --viper-home) to a Viper release's tools directory.")
        }
    }

    private fun jar(home: File) = File(home, "backends/viperserver.jar")

    private fun z3(home: File) = File(home, "z3/bin/z3")

    /**
     * Runs Silicon once on [vpr] (DESIGN-v0.md 5.6): `-Xss128m`, `--numberOfErrorsToReport 0`, the [budget], and
     * `--reportReasonUnknown`, which names a prover timeout ("canceled") so that it is not taken for a failure.
     * [extra] goes before the file, e.g. `--excludeMethods` for the property check.
     */
    fun run(
        vpr: File,
        home: File = defaultHome(),
        budget: SiliconBudget = SiliconBudget(),
        extra: List<String> = emptyList(),
    ): SiliconRun {
        check(home)
        val java = File(System.getProperty("java.home"), "bin/java").path
        val command =
            listOf(
                java, "-Xss128m", "-cp", jar(home).path, "viper.silicon.SiliconRunner",
                "--logLevel", "ERROR", "--reportReasonUnknown", "--z3Exe", z3(home).path, "--numberOfErrorsToReport", "0",
                "--assertTimeout", "${budget.assertTimeoutMs}", "--checkTimeout", "${budget.checkTimeoutMs}",
            ) + extra + vpr.path
        val start = System.nanoTime()
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = StringBuilder()
        // Silicon can write much output; read it while it runs so that the pipe never blocks the process.
        val reader = thread(isDaemon = true) { p.inputStream.bufferedReader().forEachLine { synchronized(out) { out.append(it).append('\n') } } }
        val finished = p.waitFor(budget.wallSeconds, TimeUnit.SECONDS)
        if (!finished) {
            // Kill z3 too: it is a child of the Silicon JVM.
            p.descendants().forEach { it.destroyForcibly() }
            p.destroyForcibly()
            p.waitFor(10, TimeUnit.SECONDS)
        }
        reader.join(10_000)
        val seconds = (System.nanoTime() - start) / 1e9
        return SiliconRun(if (finished) p.exitValue() else null, synchronized(out) { out.toString() }, seconds)
    }
}

/** One error line of Silicon: `[i] message (file@L.C--L2.C2)`, with the prover's reason if it gave one. */
data class SiliconError(
    val message: String,
    val line: Int,
    val col: Int,
    val endLine: Int,
    val endCol: Int,
    val reason: String?,
) {
    /** A verification failure, as opposed to a parse, type or consistency error. */
    val isVerification: Boolean get() = VERIFICATION.containsMatchIn(message)

    /** A `refute` that held: "Refute holds in all cases or could not be reached". */
    val isRefute: Boolean get() = REFUTE.containsMatchIn(message)

    /** The prover gave up on the query (timeout), so the "failure" says nothing about the program. */
    val isTimeout: Boolean get() = reason != null && TIMEOUT.containsMatchIn(reason)

    private companion object {
        val VERIFICATION = Regex("""might not hold|might fail|might not be|might be null|insufficient permission""")
        val REFUTE = Regex("""^Refute holds in all cases""")
        val TIMEOUT = Regex("""canceled|timeout|resource""", RegexOption.IGNORE_CASE)
    }
}

/** What a Silicon run says about the program (DESIGN-v0.md 5.6). */
sealed interface SiliconOutcome {
    data object Verified : SiliconOutcome

    /** Exit code 1 with a summary and only verification errors. */
    data class Failed(
        val errors: List<SiliconError>,
    ) : SiliconOutcome

    /** Anything else: no method may be reported PROVEN. [errors] are the error lines Silicon printed, if any. */
    data class Abnormal(
        val reason: String,
        val errors: List<SiliconError>,
    ) : SiliconOutcome
}

internal val SUCCESS = Regex("""^Silicon finished verification successfully""", RegexOption.MULTILINE)
internal val SUMMARY = Regex("""^Silicon found (\d+) errors?""", RegexOption.MULTILINE)
// With 10 or more errors, Silicon pads the index: `[ 0]` ... `[ 9]`, `[10]`.
private val ERROR_LINE = Regex("""^\s*\[\s*(\d+)] (.*)\(([^()@]*)@(\d+)\.(\d+)--(\d+)\.(\d+)\)\s*$""")
private val REASON = Regex("""^Potential prover incompleteness: (.*)$""")
internal val JAVA_EXCEPTION = Regex("""^(Exception in thread .*|[a-z][\w$]*(\.[\w$]+)+(Exception|Error)\b.*|\s+at [\w$.<>]+\(.*\))$""", RegexOption.MULTILINE)
internal val ABORTED = Regex("""\baborted\b""", RegexOption.IGNORE_CASE)

/**
 * The outcome rules of DESIGN-v0.md 5.6: PROVEN only with exit code 0 and the success line. Exit code 1 with the
 * error summary is a failure. Everything else is ERROR: another exit code, a killed run, a missing summary line,
 * `aborted`, a Java exception, a parse, type or consistency error, or a prover timeout. With [refutes], a refute that
 * held is a failure too (the reach methods of `kv spec check`, DESIGN-C.md 3.2); the verified program has none.
 */
fun classify(
    run: SiliconRun,
    budget: SiliconBudget,
    refutes: Boolean = false,
): SiliconOutcome {
    val errors = parseErrors(run.output)
    val code = run.exitCode ?: return SiliconOutcome.Abnormal("timeout: killed at the wall clock limit of ${budget.wallSeconds} s", errors)
    if (JAVA_EXCEPTION.containsMatchIn(run.output)) return SiliconOutcome.Abnormal("Java exception in the Silicon output", errors)
    if (ABORTED.containsMatchIn(run.output)) return SiliconOutcome.Abnormal("Silicon aborted", errors)
    return when (code) {
        0 -> if (SUCCESS.containsMatchIn(run.output) && errors.isEmpty()) SiliconOutcome.Verified else SiliconOutcome.Abnormal("exit code 0 without 'Silicon finished verification successfully'", errors)
        1 -> {
            val summary = SUMMARY.find(run.output) ?: return SiliconOutcome.Abnormal("exit code 1 without the 'Silicon found N errors' summary", errors)
            when {
                summary.groupValues[1].toInt() != errors.size -> SiliconOutcome.Abnormal("Silicon reported ${summary.groupValues[1]} errors, ${errors.size} of them readable", errors)
                errors.any { !it.isVerification && !(refutes && it.isRefute) } -> SiliconOutcome.Abnormal("Silicon reported a parse, type or consistency error", errors)
                errors.any { it.isTimeout } -> SiliconOutcome.Abnormal("timeout: a prover query was canceled (assert/check timeout ${budget.assertTimeoutMs} ms)", errors)
                else -> SiliconOutcome.Failed(errors)
            }
        }
        else -> SiliconOutcome.Abnormal("exit code $code", errors)
    }
}

internal fun parseErrors(output: String): List<SiliconError> {
    val lines = output.lines()
    val out = mutableListOf<SiliconError>()
    for ((i, l) in lines.withIndex()) {
        val m = ERROR_LINE.matchEntire(l) ?: continue
        val reason = lines.drop(i + 1).firstOrNull { it.isNotBlank() }?.let { REASON.matchEntire(it.trim())?.groupValues?.get(1) }
        val (line, col, endLine, endCol) = m.groupValues.subList(4, 8).map { it.toInt() }
        out += SiliconError(m.groupValues[2].trim(), line, col, endLine, endCol, reason)
    }
    return out
}
