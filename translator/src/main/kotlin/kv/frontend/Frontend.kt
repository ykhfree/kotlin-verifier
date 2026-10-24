package kv.frontend

import kv.kvir.Module
import kv.lower.lowerModule
import kv.sidecar.Sidecar
import kv.sidecar.SidecarError
import kv.sidecar.SidecarException
import kv.sidecar.SidecarLoader
import kv.sidecar.checkSidecar
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.JarURLConnection
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What one compiler run found. */
data class FrontendResult(
    /** True if the compiler reported no error. */
    val compiled: Boolean,
    /** Compiler messages (errors and warnings), as kotlinc prints them. */
    val messages: String,
    /** Map action calls ([MapCall]) in all compiled sources, in file and source order. */
    val sites: List<ActionSite>,
    /** The sidecar's Kotlin names bound to FIR symbols; null without a sidecar or if no source declares the module. */
    val resolution: Resolution?,
    /** Sidecar names that did not resolve. */
    val sidecarErrors: List<SidecarError>,
    /** The module in KVIR; null without a resolution or with sidecar errors. */
    val module: Module?,
)

/**
 * The FIR that the module lowering read, and the module it made. Valid only inside the [Frontend.run] callback that
 * receives it, while the compiler session runs (kv mutation-sites reads it there).
 */
class LoweredFir(
    val sidecar: Sidecar,
    val resolver: SidecarResolver,
    /** The module file. */
    val file: FirFile,
    /** Every source file of the run, the module file included. */
    val sources: List<FirFile>,
    val module: Module,
)

/** Input and output of one compiler run. The plugin finds it by id, so it must run in this JVM. */
class FrontendSession(
    /** A sidecar that passed [kv.sidecar.checkSidecar]; null to only discover map action calls. */
    private val sidecar: Sidecar?,
    /** Number of source files the compiler gets: the module is lowered once the checker has seen them all. */
    private val sourceCount: Int,
    /** The req-shield checkout, where the resolver finds the source of a library trusted fun (B6). */
    private val reviewRoot: File? = null,
    /** Called with each analyzed source file, while its FIR is valid. */
    private val onFile: ((FirFile) -> Unit)? = null,
    /** Called once after the module is lowered without errors, while the FIR is valid. */
    private val onLowered: ((LoweredFir) -> Unit)? = null,
) {
    private val sites = Collections.synchronizedList(mutableListOf<ActionSite>())
    private val errors = Collections.synchronizedList(mutableListOf<SidecarError>())

    /** Each analyzed source file with its map action calls, in checker order. */
    private val files = mutableListOf<Pair<FirFile, List<FoundAction>>>()

    @Volatile
    private var resolution: Resolution? = null

    @Volatile
    private var kvir: Module? = null

    /**
     * Called by the plugin once per source file, after full body resolution. The sidecar is resolved and the module
     * lowered when the last file arrives, so that the module audit can read every source file (DESIGN-B.md 2.1 rule 6).
     */
    internal fun analyze(
        file: FirFile,
        firSession: FirSession,
    ) {
        onFile?.invoke(file)
        val all = findActions(file)
        sites += all.map { it.site }
        if (sidecar == null) return
        val seen =
            synchronized(files) {
                files += file to all
                files.toList()
            }
        if (seen.size == sourceCount) lower(sidecar, seen, firSession)
    }

    private fun lower(
        sidecar: Sidecar,
        seen: List<Pair<FirFile, List<FoundAction>>>,
        firSession: FirSession,
    ) {
        val module = sidecar.modules.single()
        val (file, actions) = seen.firstOrNull { findClass(it.first, module.fqn.text) != null } ?: return
        val moduleClass = findClass(file, module.fqn.text)!!
        // A map call is an action candidate only if the sidecar uses its model (DESIGN-B.md 6.1 note 2).
        val uses = sidecar.uses.map { it.model.text }.toSet()
        val found = actions.filter { it.kind == MapCall.COMPUTE || it.kind.model in uses }
        val resolver = SidecarResolver(sidecar, file, firSession, found, moduleClass, reviewRoot)
        val (res, errs) = resolver.resolve()
        resolution = res
        errors += errs
        if (errs.isNotEmpty()) return
        try {
            kvir = lowerModule(sidecar, resolver, res, file, seen.map { it.first }, found, moduleClass)
        } catch (e: SidecarException) {
            errors += e.errors
            return
        }
        onLowered?.invoke(LoweredFir(sidecar, resolver, file, seen.map { it.first }, kvir!!))
    }

    internal fun result(
        compiled: Boolean,
        messages: String,
    ): FrontendResult {
        val module = sidecar?.modules?.single()
        if (module != null && compiled && resolution == null) {
            val message =
                if (files.size == sourceCount) {
                    "no source file declares module class ${module.fqn.text}"
                } else {
                    "the kv checker saw ${files.size} of $sourceCount source files, so it did not lower module ${module.fqn.text}"
                }
            errors += SidecarError(module.fqn.pos, "unresolved", message)
        }
        return FrontendResult(compiled, messages, sites.toList(), resolution, errors.toList(), kvir.takeIf { errors.isEmpty() })
    }
}

/** Outcome of resolving a sidecar file: sidecar errors, or compiler messages, or the resolution. */
data class ResolveOutcome(
    val errors: List<SidecarError>,
    /** Set when the sources did not compile. */
    val compileMessages: String?,
    val resolution: Resolution?,
    /** The module in KVIR, set with the resolution. */
    val module: Module?,
    /** The loaded sidecar, includes resolved; null if it did not parse. */
    val sidecar: Sidecar? = null,
)

/** Runs the K2 compiler in-process with the kv FIR plugin. */
object Frontend {
    private val sessions = ConcurrentHashMap<String, FrontendSession>()

    internal fun session(id: String): FrontendSession =
        sessions[id] ?: error("no kv frontend session '$id': the kv plugin runs only in-process, from Frontend.run")

    /**
     * Compiles [sources] against [classpath] and returns what the plugin found.
     * [classpath] must hold a Kotlin stdlib: the compiler does not add one.
     * [kotlincArgs] are extra compiler flags, e.g. `-language-version 2.0`. [reviewRoot] is where the source of a
     * library trusted fun is found, to check its reviewed fingerprint (B6). [onFile] and [onLowered] read the FIR
     * while it is valid (see [FrontendSession]).
     */
    fun run(
        sources: List<File>,
        classpath: List<File>,
        kotlincArgs: List<String> = emptyList(),
        sidecar: Sidecar? = null,
        reviewRoot: File? = null,
        onFile: ((FirFile) -> Unit)? = null,
        onLowered: ((LoweredFir) -> Unit)? = null,
    ): FrontendResult {
        val id = UUID.randomUUID().toString()
        val session = FrontendSession(sidecar, sources.size, reviewRoot, onFile, onLowered)
        sessions[id] = session
        val classesDir = Files.createTempDirectory("kv-classes").toFile()
        try {
            val messages = ByteArrayOutputStream()
            val args =
                buildList {
                    add("-Xplugin=$pluginRoot")
                    addAll(listOf("-P", "plugin:$PLUGIN_ID:session=$id"))
                    addAll(listOf("-no-stdlib", "-no-reflect"))
                    if (classpath.isNotEmpty()) addAll(listOf("-classpath", classpath.joinToString(File.pathSeparator)))
                    addAll(listOf("-d", classesDir.path))
                    addAll(kotlincArgs)
                    addAll(sources.map { it.path })
                }
            val exit = K2JVMCompiler().exec(PrintStream(messages), *args.toTypedArray())
            return session.result(exit == ExitCode.OK, messages.toString())
        } finally {
            sessions.remove(id)
            classesDir.deleteRecursively()
        }
    }

    /**
     * Loads and checks [sidecarFile], compiles the `source` files it names (relative to [root]), binds its Kotlin
     * names to FIR symbols, and lowers the module to KVIR. Stops at the first stage that reports errors.
     * [onLowered] reads the FIR after the lowering (see [FrontendSession]).
     */
    fun resolve(
        sidecarFile: File,
        root: File,
        classpath: List<File>,
        kotlincArgs: List<String> = emptyList(),
        onLowered: ((LoweredFir) -> Unit)? = null,
    ): ResolveOutcome {
        val sidecar =
            try {
                SidecarLoader.load(sidecarFile)
            } catch (e: SidecarException) {
                return ResolveOutcome(e.errors, null, null, null)
            }
        val errors = checkSidecar(sidecar)
        if (errors.isNotEmpty()) return ResolveOutcome(errors, null, null, null, sidecar)
        // DESIGN-B.md 5.1: a `source` directory means every .kt file under it.
        val files = sidecar.sources.associateWith { kotlinFiles(File(root, it.path)) }
        val missing = files.filterValues { it.isEmpty() }.keys
        if (missing.isNotEmpty()) return ResolveOutcome(missing.map { SidecarError(it.pos, "missing-source-file", "no file or .kt files under ${File(root, it.path)}") }, null, null, null, sidecar)
        val result = run(files.values.flatten().distinct(), classpath, kotlincArgs, sidecar, root, onLowered = onLowered)
        if (!result.compiled) return ResolveOutcome(emptyList(), result.messages, null, null, sidecar)
        val ok = result.sidecarErrors.isEmpty()
        return ResolveOutcome(result.sidecarErrors, null, result.resolution.takeIf { ok }, result.module.takeIf { ok }, sidecar)
    }

    /** [f] itself if it is a file, or every .kt file under the directory [f], sorted by path; empty if neither. */
    fun kotlinFiles(f: File): List<File> =
        when {
            f.isFile -> listOf(f)
            f.isDirectory -> f.walk().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
            else -> emptyList()
        }

    /**
     * The jar or directory that holds this plugin's service file, for `-Xplugin`.
     * The compiler reads service files only from `-Xplugin` paths, but loads the classes parent-first,
     * so the plugin classes are the ones already loaded here and share [sessions].
     */
    private val pluginRoot: String by lazy {
        val name = "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"
        val url =
            KvRegistrar::class.java.classLoader
                .getResources(name)
                .asSequence()
                .first { url -> url.readText().lines().any { it.trim() == KvRegistrar::class.java.name } }
        when (url.protocol) {
            "jar" -> File((url.openConnection() as JarURLConnection).jarFileURL.toURI()).path
            // <root>/META-INF/services/<name>
            else -> File(url.toURI()).parentFile.parentFile.parentFile.path
        }
    }
}
