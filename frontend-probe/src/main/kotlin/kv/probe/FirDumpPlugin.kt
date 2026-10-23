package kv.probe

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import java.io.File

const val PLUGIN_ID = "kv.firdump"

private val INCLUDE = CompilerConfigurationKey.create<List<String>>("kv.firdump include paths")
private val OUT = CompilerConfigurationKey.create<String>("kv.firdump output file")

/** Plugin options: `-P plugin:kv.firdump:include=<path>` (repeatable) and `-P plugin:kv.firdump:out=<file>`. */
@OptIn(ExperimentalCompilerApi::class)
class FirDumpCommandLineProcessor : CommandLineProcessor {
    override val pluginId = PLUGIN_ID
    override val pluginOptions =
        listOf(
            CliOption("include", "<path>", "Dump the files whose path starts with this prefix", required = true, allowMultipleOccurrences = true),
            CliOption("out", "<file>", "File that receives the dump", required = true),
        )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {
            "include" -> configuration.appendList(INCLUDE, value)
            "out" -> configuration.put(OUT, value)
        }
    }
}

@OptIn(ExperimentalCompilerApi::class)
class FirDumpRegistrar : CompilerPluginRegistrar() {
    override val pluginId = PLUGIN_ID
    override val supportsK2 = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val includes = configuration.getList(INCLUDE)
        val out = File(configuration.get(OUT)!!)
        out.parentFile.mkdirs()
        out.writeText("")
        FirExtensionRegistrarAdapter.registerExtension(FirDumpExtensionRegistrar(includes, out))
    }
}

class FirDumpExtensionRegistrar(
    private val includes: List<String>,
    private val out: File,
) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +FirAdditionalCheckersExtension.Factory { session -> FirDumpCheckers(session, FileDumpChecker(includes, out)) }
    }
}

/** Runs [fileChecker] in the checker phase, after full body resolution. */
class FirDumpCheckers(
    session: FirSession,
    fileChecker: FileDumpChecker,
) : FirAdditionalCheckersExtension(session) {
    override val declarationCheckers =
        object : DeclarationCheckers() {
            override val fileCheckers = setOf(fileChecker)
        }
}

class FileDumpChecker(
    private val includes: List<String>,
    private val out: File,
) : FirDeclarationChecker<FirFile>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        val path = declaration.sourceFile?.path ?: return
        if (includes.none { path.startsWith(it) }) return
        out.appendText(FirDumper(declaration).dump())
    }
}
