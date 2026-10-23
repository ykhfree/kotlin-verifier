package kv.frontend

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
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
)

/** Input and output of one compiler run. The plugin finds it by id, so it must run in this JVM. */
class FrontendSession {
    private val sites = Collections.synchronizedList(mutableListOf<ComputeSite>())

    /** Called by the plugin once per source file, after full body resolution. */
    internal fun analyze(file: FirFile) {
        sites += findComputes(file).map { it.site }
    }

    internal fun result(
        compiled: Boolean,
        messages: String,
    ) = FrontendResult(compiled, messages, sites.toList())
}

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
    ): FrontendResult {
        val id = UUID.randomUUID().toString()
        val session = FrontendSession()
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
