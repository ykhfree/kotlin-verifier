package kv.frontend

import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.com.intellij.lang.LighterASTNode
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref
import org.jetbrains.kotlin.com.intellij.util.diff.FlyweightCapableTreeStructure
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.parsing.KotlinLightParser
import java.io.File

/**
 * A function declaration of a Kotlin file, found by the light-tree parser. [classes] are the names of the classes
 * and objects around it (empty for a top-level function); [start] and [end] bound the whole declaration, KDoc and
 * modifiers included; [funKeyword] is where the keyword `fun` starts; [reviewStart] is where the first modifier
 * keyword starts, or [funKeyword] if the declaration has no modifier keyword.
 */
internal data class FunDeclaration(
    val file: File,
    val classes: List<String>,
    val name: String,
    val params: Int,
    val start: Int,
    val funKeyword: Int,
    val reviewStart: Int,
    val end: Int,
    val source: String,
) {
    /** The reviewed unit of a trusted fun (B6): the declaration from its first modifier keyword (else `fun`) to its end, signature included. KDoc and annotations are outside it. */
    val reviewedText: String get() = source.substring(reviewStart, end)

    /** `File.kt:line` of the `fun` keyword, for messages. */
    val where: String get() = "${file.name}:${source.substring(0, funKeyword).count { it == '\n' } + 1}"
}

/** Finds the source text of a trusted fun (DESIGN-v0.md 8.3 "reviewed 지문"). */
internal object ReviewedSource {
    /** Every function declaration of [file], nested ones in classes and objects included. */
    fun declarations(file: File): List<FunDeclaration> {
        val text = file.readText()
        val tree = KotlinLightParser.buildLightTree(text, null, null)
        val out = mutableListOf<FunDeclaration>()

        fun visit(
            node: LighterASTNode,
            classes: List<String>,
        ) {
            for (child in tree.children(node)) {
                when (child.tokenType) {
                    KtNodeTypes.FUN -> {
                        val parts = tree.children(child)
                        val name = parts.firstOrNull { it.tokenType == KtTokens.IDENTIFIER } ?: continue
                        val keyword = parts.firstOrNull { it.tokenType == KtTokens.FUN_KEYWORD } ?: continue
                        val params = parts.firstOrNull { it.tokenType == KtNodeTypes.VALUE_PARAMETER_LIST }?.let { list -> tree.children(list).count { it.tokenType == KtNodeTypes.VALUE_PARAMETER } } ?: 0
                        val firstModifier =
                            parts.firstOrNull { it.tokenType == KtNodeTypes.MODIFIER_LIST }
                                ?.let { list -> tree.children(list).firstOrNull { it.tokenType != KtNodeTypes.ANNOTATION_ENTRY && it.tokenType != KtNodeTypes.ANNOTATION } }
                        out += FunDeclaration(file, classes, text.substring(name.startOffset, name.endOffset), params, child.startOffset, keyword.startOffset, firstModifier?.startOffset ?: keyword.startOffset, child.endOffset, text)
                    }
                    KtNodeTypes.CLASS, KtNodeTypes.OBJECT_DECLARATION -> {
                        val parts = tree.children(child)
                        val name = parts.firstOrNull { it.tokenType == KtTokens.IDENTIFIER }?.let { text.substring(it.startOffset, it.endOffset) } ?: "Companion"
                        parts.filter { it.tokenType == KtNodeTypes.CLASS_BODY }.forEach { visit(it, classes + name) }
                    }
                }
            }
        }
        visit(tree.root, emptyList())
        return out
    }

    /**
     * The declaration of a library function in the req-shield checkout [root]: the source files of package [pkg] are
     * the `.kt` files directly in a `src/main/kotlin/<package path>` directory under [root], outside `build`
     * directories. A declaration matches by its classes, name and parameter count.
     */
    fun library(
        root: File,
        pkg: String,
        classes: List<String>,
        name: String,
        params: Int,
    ): List<FunDeclaration> {
        val dir = "src/main/kotlin" + (if (pkg.isEmpty()) "" else "/" + pkg.replace('.', '/'))
        val files =
            root.walk().onEnter { it.name != "build" && !it.name.startsWith(".") }
                .filter { it.isFile && it.extension == "kt" && it.parentFile.invariantSeparatorsPath.endsWith("/$dir") }
                .sortedBy { it.path }
        return files.flatMap(::declarations).filter { it.classes == classes && it.name == name && it.params == params }.toList()
    }

    private fun FlyweightCapableTreeStructure<LighterASTNode>.children(node: LighterASTNode): List<LighterASTNode> {
        val ref = Ref<Array<LighterASTNode?>>()
        val n = getChildren(node, ref)
        return (0 until n).mapNotNull { ref.get()[it] }
    }
}
