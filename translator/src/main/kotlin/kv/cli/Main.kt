package kv.cli

import kv.frontend.ActionSite
import kv.frontend.Frontend
import kv.emit.Emitted
import kv.emit.emitViper
import kv.frontend.LoweredFir
import kv.frontend.ResolveOutcome
import kv.kvir.checkWellFormed
import kv.kvir.incomplete
import kv.kvir.printModule
import kv.lower.bindGhosts
import kv.mutation.LibraryTrusted
import kv.mutation.SiteList
import kv.mutation.librarySites
import kv.mutation.moduleSites
import kv.mutation.properties
import kv.mutation.sitesJson
import kv.sidecar.SidecarException
import kv.sidecar.checkHoleEdit
import kv.sidecar.holeSkeleton
import kv.verify.ModuleVerdict
import kv.verify.Silicon
import kv.verify.SiliconBudget
import kv.verify.SiliconMissing
import kv.verify.render
import kv.verify.verify
import java.io.File
import java.io.PrintStream
import kotlin.system.exitProcess

private const val USAGE = """usage:
  kv discover [--classpath CP] [--kotlinc "ARGS"] FILE.kt|DIR...
      Compiles the files (a directory means every .kt file under it) and lists every ConcurrentHashMap call that
      can be an action: compute, computeIfAbsent, putIfAbsent, put, remove, m[k] = v and getOrPut.
  kv resolve --root DIR [--classpath CP] [--kotlinc "ARGS"] SIDECAR.kv
      Checks the sidecar, compiles its source files (relative to DIR) and binds its Kotlin names to FIR symbols.
  kv lower --root DIR [--classpath CP] [--kotlinc "ARGS"] SIDECAR.kv
      As resolve, then prints the module in KVIR and checks W1-W16. Exit 1 if a rule is broken.
  kv emit --root DIR [--classpath CP] [--kotlinc "ARGS"] [--out FILE.vpr] SIDECAR.kv
      As lower, then writes the Viper program (to stdout without --out). With --out, also writes the position
      table to FILE.vpr.positions. Exit 1 if a rule is broken or the sidecar cannot be emitted.
  kv verify --root DIR [--classpath CP] [--kotlinc "ARGS"] [--out FILE.vpr] [--viper-home DIR]
            [--assert-timeout MS] [--wall-seconds N] SIDECAR.kv
      As emit, then runs Silicon once and prints the report (DESIGN-v0.md 5.8). If a FAILED action fails only at
      hole conjuncts (MI_INV_i), a second Silicon run checks its other ensures without them (the property check):
      the action is FAILED (property) or FAILED (invariant only). VIPER_HOME or --viper-home names a
      Viper release's tools directory. Budget: --assert-timeout (assert and check timeout, default 20000 ms) and
      --wall-seconds (default 300). Exit 0 PROVEN, 1 FAILED, 2 usage error, 3 INCOMPLETE, 4 ERROR (a compile
      error, a sidecar error, a W1-W16 violation, a missing Silicon or an abnormal Silicon run).
  kv mutation-sites --root DIR [--classpath CP] [--kotlinc "ARGS"] SIDECAR.kv
      As lower, then prints as JSON every mutation site of MUTATION.md 2 and 3 (the module's operations, their
      action lambdas, the helpers they call, the reviewed trusted fun bodies): the operator, the source range, the
      exact edits, the ledger kind of the mutated node, and the sidecar properties. Exit 1 on an error.
  kv hole skeleton SIDECAR.kv OUT.kv
      Writes a copy of SIDECAR.kv (the file that holds `hole MI_INV`) whose hole is `true` (M7).
  kv hole check BEFORE.kv AFTER.kv
      Accepts AFTER only if it equals BEFORE outside the hole text and its hole has no forbidden word (assume,
      inhale, exhale, refute, method, function, predicate, axiom, domain, field, import). Exit 0 accepted,
      1 rejected (each reason with its AFTER.kv line and column).
  CP must hold a Kotlin stdlib. ARGS are extra kotlinc flags, separated by spaces."""

fun main(args: Array<String>) {
    exitProcess(runCli(args.toList(), System.out, System.err))
}

/**
 * Runs one `kv` command. Returns the exit code: 0 success, 1 failure, 2 usage error. `kv verify` returns the
 * module verdict instead (DESIGN-v0.md 8.3, Q7): see [verdictExit].
 */
fun runCli(
    args: List<String>,
    out: PrintStream,
    err: PrintStream,
): Int {
    val command = args.firstOrNull()
    if (command == "hole") return hole(args.drop(1), out, err)
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
        "verify" -> verify(options, out, err)
        "mutation-sites" -> mutationSites(options, out, err)
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
    val result = Frontend.run(options.files.flatMap { if (it.isDirectory) Frontend.kotlinFiles(it) else listOf(it) }, options.classpath, options.kotlincArgs)
    if (!result.compiled) {
        err.print(result.messages)
        err.println("kv: compilation failed")
        return 1
    }
    result.sites.forEach { out.println(format(it)) }
    return 0
}

/**
 * Runs [Frontend.resolve] for the commands that take a sidecar; null after reporting a usage error, a compile error or
 * sidecar errors. [onLowered] reads the FIR after the lowering.
 */
private fun resolved(
    command: String,
    options: Options,
    err: PrintStream,
    onLowered: ((LoweredFir) -> Unit)? = null,
): Pair<ResolveOutcome?, Int> {
    val root = options.root
    if (root == null || options.files.size != 1) {
        err.println("kv: $command needs --root DIR and one sidecar file")
        return null to 2
    }
    val outcome = Frontend.resolve(options.files.single(), root, options.classpath, options.kotlincArgs, onLowered)
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
    out.println("module ${if (module.incomplete) "INCOMPLETE (unsupported parts above)" else "complete"}; W1-W16: ${violations.size} violation(s)")
    return if (violations.isEmpty()) 0 else 1
}

/** Lowers, binds the ghost rules, checks W1-W16 and emits Viper; null after reporting an error, with the exit code. */
private fun emitted(
    command: String,
    options: Options,
    err: PrintStream,
): Pair<Emitted?, Int> {
    val (outcome, code) = resolved(command, options, err)
    val module = outcome?.module ?: return null to code
    val sidecar = outcome.sidecar!!
    return try {
        val bound = bindGhosts(module, sidecar)
        val violations = checkWellFormed(bound)
        if (violations.isNotEmpty()) {
            violations.forEach { err.println("kv: $it") }
            return null to 1
        }
        emitViper(bound, sidecar) to 0
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

/** `kv verify` exit codes (DESIGN-v0.md 8.3, Q7), so that CI and the M8 script need not read the report. */
private fun verdictExit(verdict: ModuleVerdict): Int =
    when (verdict) {
        ModuleVerdict.PROVEN -> 0
        ModuleVerdict.FAILED -> 1
        ModuleVerdict.INCOMPLETE -> 3
        ModuleVerdict.ERROR -> 4
    }

private fun verify(
    options: Options,
    out: PrintStream,
    err: PrintStream,
): Int {
    val (outcome, code) = resolved("verify", options, err)
    // A compile error or a sidecar error is ERROR; only a usage error keeps its own code.
    val module = outcome?.module ?: return if (code == 2) 2 else verdictExit(ModuleVerdict.ERROR)
    val vpr = options.out ?: File.createTempFile("kv-", ".vpr")
    val budget = SiliconBudget(options.assertTimeoutMs ?: 20_000, options.assertTimeoutMs ?: 20_000, options.wallSeconds ?: 300)
    val report =
        try {
            verify(module, outcome.sidecar!!, vpr, options.viperHome ?: Silicon.defaultHome(), budget)
        } catch (e: SiliconMissing) {
            err.println("kv: ${e.message}")
            return verdictExit(ModuleVerdict.ERROR)
        }
    out.print(report.render())
    out.println("Viper program: ${vpr.path}")
    return verdictExit(report.verdict)
}

/** `kv mutation-sites`: the sites of the module file, then those of each library trusted fun, compiled on its own. */
private fun mutationSites(
    options: Options,
    out: PrintStream,
    err: PrintStream,
): Int {
    var found: Pair<SiteList, List<LibraryTrusted>>? = null
    val (outcome, code) = resolved("mutation-sites", options, err) { found = moduleSites(it, options.root!!) }
    outcome ?: return code
    val (module, library) = found ?: run {
        err.println("kv: the module was not lowered")
        return 1
    }
    val uses = outcome.sidecar!!.uses.map { it.model.text }.toSet()
    val lists = listOf(module) + library.map { librarySites(it, uses, options.classpath, options.kotlincArgs) }
    val problems = lists.flatMap { it.problems }
    if (problems.isNotEmpty()) {
        problems.forEach { err.println("kv: mutation site: $it") }
        return 1
    }
    out.print(sitesJson(outcome.module!!.id, options.files.single(), options.root!!, lists.flatMap { it.sites }, properties(outcome.sidecar)))
    return 0
}

/** `kv hole skeleton SIDECAR.kv OUT.kv` and `kv hole check BEFORE.kv AFTER.kv` (DESIGN-v0.md 7 M7, 8.3). */
private fun hole(
    args: List<String>,
    out: PrintStream,
    err: PrintStream,
): Int {
    val sub = args.firstOrNull()
    val files = args.drop(1).map(::File)
    if (sub !in setOf("skeleton", "check") || files.size != 2) {
        err.println("kv: hole needs 'skeleton SIDECAR.kv OUT.kv' or 'check BEFORE.kv AFTER.kv'")
        err.println(USAGE)
        return 2
    }
    val (a, b) = files
    val inputs = if (sub == "skeleton") listOf(a) else files
    inputs.firstOrNull { !it.isFile }?.let {
        err.println("kv: no file ${it.path}")
        return 2
    }
    return try {
        if (sub == "skeleton") {
            b.absoluteFile.parentFile.mkdirs()
            b.writeText(holeSkeleton(a.path, a.readText()))
            out.println("kv: wrote ${b.path}: ${a.path} with hole MI_INV = true")
            0
        } else {
            val errors = checkHoleEdit(a.path, a.readText(), b.path, b.readText())
            errors.forEach { err.println(it) }
            if (errors.isEmpty()) out.println("kv: hole check OK: ${b.path} changes only the hole MI_INV of ${a.path}") else err.println("kv: hole check REJECTED: ${b.path}")
            if (errors.isEmpty()) 0 else 1
        }
    } catch (e: SidecarException) {
        e.errors.forEach { err.println(it) }
        1
    }
}

private fun format(site: ActionSite): String {
    val lambda = site.lambda?.let { "${it.line}:${it.col}" } ?: "none"
    return "${site.pos} ${site.receiver ?: "?"}.${site.kind.method} class=${site.inClass ?: "-"} fun=${site.inFunction ?: "-"} lambda=$lambda"
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
    val viperHome: File?,
    val assertTimeoutMs: Int?,
    val wallSeconds: Long?,
) {
    companion object {
        fun parse(args: List<String>): Options {
            var root: File? = null
            var classpath = emptyList<File>()
            val kotlincArgs = mutableListOf<String>()
            val files = mutableListOf<File>()
            var out: File? = null
            var viperHome: File? = null
            var assertTimeoutMs: Int? = null
            var wallSeconds: Long? = null
            val it = args.iterator()
            while (it.hasNext()) {
                when (val arg = it.next()) {
                    "--root" -> root = File(value(it, arg))
                    "--classpath" -> classpath = value(it, arg).split(File.pathSeparator).filter { p -> p.isNotEmpty() }.map(::File)
                    "--kotlinc" -> kotlincArgs += value(it, arg).split(Regex("\\s+")).filter { a -> a.isNotEmpty() }
                    "--out" -> out = File(value(it, arg))
                    "--viper-home" -> viperHome = File(value(it, arg))
                    "--assert-timeout" -> assertTimeoutMs = value(it, arg).toIntOrNull() ?: throw UsageError("--assert-timeout needs a number of milliseconds")
                    "--wall-seconds" -> wallSeconds = value(it, arg).toLongOrNull() ?: throw UsageError("--wall-seconds needs a number of seconds")
                    else -> if (arg.startsWith("--")) throw UsageError("unknown option $arg") else files += File(arg)
                }
            }
            return Options(root, classpath, kotlincArgs, files, out, viperHome, assertTimeoutMs, wallSeconds)
        }

        private fun value(
            it: Iterator<String>,
            option: String,
        ): String = if (it.hasNext()) it.next() else throw UsageError("$option needs a value")
    }
}
