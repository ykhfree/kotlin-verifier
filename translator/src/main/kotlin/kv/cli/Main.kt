package kv.cli

import kv.frontend.ComputeSite
import kv.frontend.Frontend
import kv.emit.Emitted
import kv.emit.emitViper
import kv.frontend.ResolveOutcome
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import kv.kvir.printModule
import kv.sidecar.SidecarException
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

private const val USAGE = """usage:
  kv discover [--classpath CP] [--kotlinc "ARGS"] FILE.kt...
      Compiles the files and lists every ConcurrentHashMap.compute call (one candidate action each).
  kv resolve --root DIR [--classpath CP] [--kotlinc "ARGS"] SIDECAR.kv
      Checks the sidecar, compiles its source files (relative to DIR) and binds its Kotlin names to FIR symbols.
  kv lower --root DIR [--classpath CP] [--kotlinc "ARGS"] SIDECAR.kv
      As resolve, then prints the module in KVIR and checks W1-W14. Exit 1 if a rule is broken.
  kv emit --root DIR [--classpath CP] [--kotlinc "ARGS"] [--out FILE.vpr] SIDECAR.kv
      As lower, then writes the Viper program (to stdout without --out). With --out, also writes the position
      table to FILE.vpr.positions. Exit 1 if a rule is broken or the sidecar cannot be emitted.
  CP must hold a Kotlin stdlib. ARGS are extra kotlinc flags, separated by spaces."""

fun main(args: Array<String>) {
    exitProcess(runCli(args.toList(), System.out, System.err))
}

/** Runs one `kv` command. Returns the exit code: 0 success, 1 failure, 2 usage error. */
fun runCli(
    args: List<String>,
    out: PrintStream,
    err: PrintStream,
): Int {
    val command = args.firstOrNull()
    val options =
        try {
            Options.parse(args.drop(1))
        } catch (e: UsageError) {
            err.println("kv: ${e.message}")
            err.println(USAGE)
            return 2
        }
    return when (command) {
        "discover" -> discover(options, out, err)
        "resolve" -> resolve(options, out, err)
        "lower" -> lower(options, out, err)
        "emit" -> emit(options, out, err)
        else -> {
            err.println(USAGE)
            2
        }
    }
}

private fun discover(
    options: Options,
    out: PrintStream,
    err: PrintStream,
): Int {
    if (options.files.isEmpty()) {
        err.println("kv: discover needs at least one .kt file")
        return 2
    }
    val result = Frontend.run(options.files, options.classpath, options.kotlincArgs)
    if (!result.compiled) {
        err.print(result.messages)
        err.println("kv: compilation failed")
        return 1
    }
    result.sites.forEach { out.println(format(it)) }
    return 0
}

/** Runs [Frontend.resolve] for `resolve` and `lower`; null after reporting a usage error, a compile error or sidecar errors. */
private fun resolved(
    command: String,
    options: Options,
    err: PrintStream,
): Pair<ResolveOutcome?, Int> {
    val root = options.root
    if (root == null || options.files.size != 1) {
        err.println("kv: $command needs --root DIR and one sidecar file")
        return null to 2
    }
    val outcome = Frontend.resolve(options.files.single(), root, options.classpath, options.kotlincArgs)
    outcome.compileMessages?.let {
        err.print(it)
        err.println("kv: compilation failed")
        return null to 1
    }
    if (outcome.errors.isNotEmpty()) {
        outcome.errors.forEach { err.println(it) }
        return null to 1
    }
    return outcome to 0
}

private fun resolve(
    options: Options,
    out: PrintStream,
    err: PrintStream,
): Int {
    val (outcome, code) = resolved("resolve", options, err)
    outcome ?: return code
    val resolution = outcome.resolution!!
    resolution.bindings.forEach { b ->
        val where = b.kotlinPos?.let { "@${it.file.substringAfterLast('/')}:${it.line}:${it.col}" } ?: "(library)"
        out.println("${b.sidecar} -> ${b.kotlin} $where")
    }
    resolution.unsupported.forEach { out.println("unsupported operation ${it.operation} (${it.at}): ${it.reason}") }
    return 0
}

private fun lower(
    options: Options,
    out: PrintStream,
    err: PrintStream,
): Int {
    val (outcome, code) = resolved("lower", options, err)
    val module = outcome?.module ?: return code
    out.print(printModule(module))
    val violations = checkWellFormed(module)
    violations.forEach { err.println("kv: $it") }
    out.println("module ${if (module.incomplete) "INCOMPLETE (unsupported parts above)" else "complete"}; W1-W14: ${violations.size} violation(s)")
    return if (violations.isEmpty()) 0 else 1
}

/** Lowers and checks W1-W14, then emits Viper; null after reporting an error, with the exit code. */
private fun emitted(
    command: String,
    options: Options,
    err: PrintStream,
): Pair<Emitted?, Int> {
    val (outcome, code) = resolved(command, options, err)
    val module = outcome?.module ?: return null to code
    val violations = checkWellFormed(module)
    if (violations.isNotEmpty()) {
        violations.forEach { err.println("kv: $it") }
        return null to 1
    }
    return try {
        emitViper(module, outcome.sidecar!!) to 0
    } catch (e: SidecarException) {
        e.errors.forEach { err.println(it) }
        null to 1
    }
}

private fun emit(
    options: Options,
    out: PrintStream,
    err: PrintStream,
): Int {
    val (emitted, code) = emitted("emit", options, err)
    emitted ?: return code
    val file = options.out
    if (file == null) {
        out.print(emitted.text)
    } else {
        file.absoluteFile.parentFile.mkdirs()
        file.writeText(emitted.text)
        File(file.path + ".positions").writeText(emitted.positionTable())
        out.println("kv: wrote ${file.path} (${emitted.methods.size} methods) and ${file.path}.positions")
    }
    emitted.warnings.forEach { err.println("kv: warning: $it") }
    return 0
}

private fun format(site: ComputeSite): String {
    val lambda = site.lambda?.let { "${it.line}:${it.col}" } ?: "none"
    return "${site.pos} ${site.receiver ?: "?"}.compute class=${site.inClass ?: "-"} fun=${site.inFunction ?: "-"} lambda=$lambda"
}

private class UsageError(
    message: String,
) : Exception(message)

private class Options(
    val root: File?,
    val classpath: List<File>,
    val kotlincArgs: List<String>,
    val files: List<File>,
    val out: File?,
) {
    companion object {
        fun parse(args: List<String>): Options {
            var root: File? = null
            var classpath = emptyList<File>()
            val kotlincArgs = mutableListOf<String>()
            val files = mutableListOf<File>()
            var out: File? = null
            val it = args.iterator()
            while (it.hasNext()) {
                when (val arg = it.next()) {
                    "--root" -> root = File(value(it, arg))
                    "--classpath" -> classpath = value(it, arg).split(File.pathSeparator).filter { p -> p.isNotEmpty() }.map(::File)
                    "--kotlinc" -> kotlincArgs += value(it, arg).split(Regex("\\s+")).filter { a -> a.isNotEmpty() }
                    "--out" -> out = File(value(it, arg))
                    else -> if (arg.startsWith("--")) throw UsageError("unknown option $arg") else files += File(arg)
                }
            }
            return Options(root, classpath, kotlincArgs, files, out)
        }

        private fun value(
            it: Iterator<String>,
            option: String,
        ): String = if (it.hasNext()) it.next() else throw UsageError("$option needs a value")
    }
}
