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
    /** `ConcurrentHashMap.compute` calls in all compiled sources, in file and source order. */
    val sites: List<ComputeSite>,
    /** The sidecar's Kotlin names bound to FIR symbols; null without a sidecar or if no source declares the module. */
    val resolution: Resolution?,
    /** Sidecar names that did not resolve. */
    val sidecarErrors: List<SidecarError>,
    /** The module in KVIR; null without a resolution or with sidecar errors. */
    val module: Module?,
)

/** Input and output of one compiler run. The plugin finds it by id, so it must run in this JVM. */
class FrontendSession(
    /** A sidecar that passed [kv.sidecar.checkSidecar]; null to only discover compute calls. */
    private val sidecar: Sidecar?,
) {
    private val sites = Collections.synchronizedList(mutableListOf<ComputeSite>())
    private val errors = Collections.synchronizedList(mutableListOf<SidecarError>())

    @Volatile
    private var resolution: Resolution? = null

    @Volatile
    private var kvir: Module? = null

    /** Called by the plugin once per source file, after full body resolution. */
    internal fun analyze(
        file: FirFile,
        firSession: FirSession,
    ) {
        val found = findComputes(file)
        sites += found.map { it.site }
        val module = sidecar?.modules?.single() ?: return
        val moduleClass = findClass(file, module.fqn.text) ?: return
        val resolver = SidecarResolver(sidecar, file, firSession, found, moduleClass)
        val (res, errs) = resolver.resolve()
        resolution = res
        errors += errs
        if (errs.isNotEmpty()) return
        try {
            kvir = lowerModule(sidecar, resolver, res, file, found, moduleClass)
        } catch (e: SidecarException) {
            errors += e.errors
        }
    }

    internal fun result(
        compiled: Boolean,
        messages: String,
    ): FrontendResult {
        val module = sidecar?.modules?.single()
        if (module != null && compiled && resolution == null) {
            errors += SidecarError(module.fqn.pos, "unresolved", "no source file declares module class ${module.fqn.text}")
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
     * [kotlincArgs] are extra compiler flags, e.g. `-language-version 2.0`.
     */
    fun run(
        sources: List<File>,
        classpath: List<File>,
        kotlincArgs: List<String> = emptyList(),
        sidecar: Sidecar? = null,
    ): FrontendResult {
        val id = UUID.randomUUID().toString()
        val session = FrontendSession(sidecar)
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
     */
    fun resolve(
        sidecarFile: File,
        root: File,
        classpath: List<File>,
        kotlincArgs: List<String> = emptyList(),
    ): ResolveOutcome {
        val sidecar =
            try {
                SidecarLoader.load(sidecarFile)
            } catch (e: SidecarException) {
                return ResolveOutcome(e.errors, null, null, null)
            }
        val errors = checkSidecar(sidecar)
        if (errors.isNotEmpty()) return ResolveOutcome(errors, null, null, null, sidecar)
        val missing = sidecar.sources.filter { !File(root, it.path).isFile }
        if (missing.isNotEmpty()) return ResolveOutcome(missing.map { SidecarError(it.pos, "missing-source-file", "no file ${File(root, it.path)}") }, null, null, null, sidecar)
        val result = run(sidecar.sources.map { File(root, it.path) }, classpath, kotlincArgs, sidecar)
        if (!result.compiled) return ResolveOutcome(emptyList(), result.messages, null, null, sidecar)
        val ok = result.sidecarErrors.isEmpty()
        return ResolveOutcome(result.sidecarErrors, null, result.resolution.takeIf { ok }, result.module.takeIf { ok }, sidecar)
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
