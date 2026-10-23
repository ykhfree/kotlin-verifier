package kv

import kv.frontend.Frontend
import kv.frontend.FrontendResult
import java.io.File

/** In-process compile harness: compiles Kotlin snippets with the kv plugin and returns what it found. */
object Harness {
    /** The stdlib this test JVM runs on. Snippets compile against it. */
    val stdlib: File = File(KotlinVersion::class.java.protectionDomain.codeSource.location.toURI())

    /**
     * Writes [sources] (relative path to text) under [dir], compiles them, and returns the frontend result.
     * Fails the test with the compiler messages if [mustCompile] and the compiler reports an error.
     */
    fun compile(
        dir: File,
        vararg sources: Pair<String, String>,
        classpath: List<File> = emptyList(),
        mustCompile: Boolean = true,
    ): FrontendResult {
        val files =
            sources.map { (path, text) ->
                File(dir, path).apply {
                    parentFile.mkdirs()
                    writeText(text)
                }
            }
        val result = Frontend.run(files, listOf(stdlib) + classpath)
        if (mustCompile) check(result.compiled) { "fixture did not compile:\n${result.messages}" }
        return result
    }
}

/** req-shield inputs, passed in by Gradle (see build.gradle.kts). Read only. */
object ReqShield {
    val dir = File(System.getProperty("kv.reqShieldDir"))
    val classpath: List<File> = System.getProperty("kv.reqShieldClasspath").split(File.pathSeparator).map(::File)
    val kotlincArgs: List<String> = System.getProperty("kv.reqShieldKotlincArgs").split(" ")
    val coroutineDir = File(dir, "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine")
    val coreDir = File(dir, "core/src/main/kotlin/com/linecorp/cse/reqshield")
    val reactorDir = File(dir, "core-reactor/src/main/kotlin/com/linecorp/cse/reqshield/reactor")

    /** The files a variant compiles: KeyLock.kt declares the interface KeyLocalLock.kt implements. */
    fun sources(variantDir: File) = listOf(File(variantDir, "KeyLock.kt"), File(variantDir, "KeyLocalLock.kt"))
}

/** req-shield's three Spring aspect modules (DESIGN-B.md), passed in by Gradle (see build.gradle.kts). Read only. */
object Aspects {
    val classpath: List<File> = System.getProperty("kv.aspectClasspath").split(File.pathSeparator).map(::File)

    /** Module name to the main source root, relative to the req-shield checkout. */
    val roots =
        mapOf(
            "core-spring" to "core-spring/src/main/kotlin",
            "webflux" to "core-spring-webflux/src/main/kotlin",
            "coroutine" to "core-spring-webflux-kotlin-coroutine/src/main/kotlin",
        )

    /** The aspect file of [module], relative to the req-shield checkout. */
    fun aspectFile(module: String): String =
        roots.getValue(module) + "/com/linecorp/cse/reqshield/spring/" +
            mapOf("core-spring" to "", "webflux" to "webflux/", "coroutine" to "webflux/kotlin/coroutine/").getValue(module) + "aspect/ReqShieldAspect.kt"
}
