package kv.frontend

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

const val PLUGIN_ID = "kv.translator"

private val SESSION = CompilerConfigurationKey.create<String>("kv frontend session id")

/** Plugin option `-P plugin:kv.translator:session=<id>`: the [FrontendSession] that receives the results. */
@OptIn(ExperimentalCompilerApi::class)
class KvCommandLineProcessor : CommandLineProcessor {
    override val pluginId = PLUGIN_ID
    override val pluginOptions = listOf(CliOption("session", "<id>", "kv frontend session id", required = true))

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        if (option.optionName == "session") configuration.put(SESSION, value)
    }
}

@OptIn(ExperimentalCompilerApi::class)
class KvRegistrar : CompilerPluginRegistrar() {
    override val pluginId = PLUGIN_ID
    override val supportsK2 = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val session = Frontend.session(configuration.get(SESSION)!!)
        FirExtensionRegistrarAdapter.registerExtension(KvFirRegistrar(session))
    }
}

class KvFirRegistrar(
    private val session: FrontendSession,
) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +FirAdditionalCheckersExtension.Factory { firSession -> KvCheckers(firSession, KvFileChecker(session)) }
    }
}

/** Runs [fileChecker] in the checker phase, after full body resolution. */
class KvCheckers(
    firSession: FirSession,
    fileChecker: KvFileChecker,
) : FirAdditionalCheckersExtension(firSession) {
    override val declarationCheckers =
        object : DeclarationCheckers() {
            override val fileCheckers = setOf(fileChecker)
        }
}

class KvFileChecker(
    private val session: FrontendSession,
) : FirDeclarationChecker<FirFile>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirFile) {
        session.analyze(declaration, context.session)
    }
}
