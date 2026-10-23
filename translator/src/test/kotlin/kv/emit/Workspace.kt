package kv.emit

import kv.ReqShield
import kv.frontend.Frontend
import kv.frontend.ResolveOutcome
import kv.kvir.Module
import kv.kvir.checkWellFormed
import kv.sidecar.Sidecar
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

/** The reviewed KeyLocalLock specs. Read only: tests that change a spec edit a [Workspace] copy. */
val specDir = File(System.getProperty("kv.specDir"), "reqshield")

/** Relative paths of the KeyLocalLock sources in the req-shield checkout, by variant. */
val variantDirs =
    mapOf(
        "coroutine" to "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine",
        "core" to "core/src/main/kotlin/com/linecorp/cse/reqshield",
    )

/**
 * A temp copy of the KeyLocalLock specs under [dir]/specs. [root] is the req-shield checkout until a test edits a
 * Kotlin source: then the variant's sources are copied under [dir]/root, with the same relative paths, and edited
 * there. The req-shield checkout and specs/ stay unchanged.
 */
class Workspace(
    private val dir: File,
) {
    val specs = File(dir, "specs").apply { specDir.listFiles()!!.forEach { it.copyTo(File(this, it.name)) } }
    var root: File = ReqShield.dir
        private set

    /** Replaces [old] (which must occur exactly once) in the copied spec [file]. */
    fun editSpec(
        file: String,
        old: String,
        new: String,
    ) = replaceOnce(File(specs, file), old, new)

    /** Replaces [old] (which must occur exactly once) in the variant's KeyLocalLock.kt, in a copy of the sources. */
    fun editKotlin(
        variant: String,
        old: String,
        new: String,
    ) {
        val rel = variantDirs.getValue(variant)
        val copy = File(dir, "root")
        if (root != copy) {
            for (name in listOf("KeyLock.kt", "KeyLocalLock.kt")) File(ReqShield.dir, "$rel/$name").copyTo(File(copy, "$rel/$name"))
            root = copy
        }
        replaceOnce(File(copy, "$rel/KeyLocalLock.kt"), old, new)
    }

    /** 1-based line and column of [fragment] in the copied spec [file], within the first line that contains [line]. */
    fun at(
        file: String,
        line: String,
        fragment: String = line,
    ): Pair<Int, Int> {
        val lines = File(specs, file).readLines()
        val n = lines.indexOfFirst { line in it }
        check(n >= 0) { "no '$line' in $file" }
        return n + 1 to lines[n].indexOf(fragment, lines[n].indexOf(line)) + 1
    }

    /** 1-based line of the first line that contains [fragment] in the variant's KeyLocalLock.kt under [root]. */
    fun kotlinLine(
        variant: String,
        fragment: String,
    ): Int {
        val lines = File(root, "${variantDirs.getValue(variant)}/KeyLocalLock.kt").readLines()
        return lines.indexOfFirst { fragment in it }.also { check(it >= 0) { "no '$fragment' in KeyLocalLock.kt" } } + 1
    }

    fun resolve(variant: String): ResolveOutcome = Frontend.resolve(File(specs, "keylocallock.$variant.kv"), root, ReqShield.classpath, ReqShield.kotlincArgs)

    /** The lowered module and its sidecar; the sidecar must resolve and the module pass W1-W14. */
    fun lower(variant: String): Pair<Module, Sidecar> = lowerChecked(resolve(variant))

    fun emit(variant: String): Emitted = lower(variant).let { (m, s) -> emitViper(m, s) }

    private fun replaceOnce(
        f: File,
        old: String,
        new: String,
    ) {
        val text = f.readText()
        check(text.split(old).size == 2) { "'$old' occurs ${text.split(old).size - 1} times in ${f.name}" }
        f.writeText(text.replace(old, new))
    }
}

/** Lowers the unchanged specs of [variant] against the req-shield checkout. */
fun lowerReqShield(variant: String): Pair<Module, Sidecar> =
    lowerChecked(Frontend.resolve(File(specDir, "keylocallock.$variant.kv"), ReqShield.dir, ReqShield.classpath, ReqShield.kotlincArgs))

private fun lowerChecked(outcome: ResolveOutcome): Pair<Module, Sidecar> {
    assertNull(outcome.compileMessages)
    assertEquals(emptyList(), outcome.errors)
    val m = outcome.module ?: fail("no module")
    assertEquals(emptyList(), checkWellFormed(m), "W1-W14")
    return m to outcome.sidecar!!
}

/** The 1-based line of the first line of [e]'s text that contains [fragment]. */
fun Emitted.lineOf(fragment: String): Int = text.lines().indexOfFirst { fragment in it }.also { check(it >= 0) { "no '$fragment' in:\n$text" } } + 1

/** The 1-based column of [fragment] in Viper line [line]. */
fun Emitted.colOf(
    line: Int,
    fragment: String,
): Int = text.lines()[line - 1].indexOf(fragment).also { check(it >= 0) { "no '$fragment' on line $line" } } + 1
