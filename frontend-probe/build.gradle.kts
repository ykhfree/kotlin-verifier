// Feasibility probe: a K2 (FIR) compiler plugin that dumps the resolved FIR facts a Kotlin -> Viper translator needs.
// The compiler plugin API is unstable, so every Kotlin artifact here is pinned to one compiler version.

plugins {
    kotlin("jvm") version "2.4.21"
}

repositories {
    mavenCentral()
}

val kotlinCompilerVersion = "2.4.21"

// req-shield checkout that holds the target sources. Read only.
val reqShieldDir = providers.gradleProperty("reqShieldDir").getOrElse("/Users/ykhfree/IdeaProjects/req-shield")
val targetSourceDir = "$reqShieldDir/core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine"

// The compiler that runs the plugin.
val compilerRuntime: Configuration by configurations.creating

// Compile classpath of req-shield core-kotlin-coroutine, with the versions from req-shield's libs.versions.toml.
val targetClasspath: Configuration by configurations.creating

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinCompilerVersion")
    compilerRuntime("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinCompilerVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinCompilerVersion")

    targetClasspath("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    targetClasspath("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.1")
    targetClasspath("org.slf4j:slf4j-api:1.7.36")
    targetClasspath(files("$reqShieldDir/support/build/libs/support-1.0.0.jar"))
}

// The tests compile fixtures in-process with the built plugin jar, as -Xplugin loads it.
tasks.test {
    useJUnitPlatform()
    dependsOn(tasks.jar)
    systemProperty("probe.pluginJar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}

// Compiles KeyLocalLock.kt (and KeyLock.kt, which declares the interface it implements) with the probe plugin.
// The compiler flags match req-shield's library modules.
val dumpKeyLocalLock by tasks.registering(JavaExec::class) {
    group = "probe"
    description = "Compiles KeyLocalLock.kt with the FIR dump plugin and writes out/KeyLocalLock.fir.txt."
    val pluginJar = tasks.jar.flatMap { it.archiveFile }
    val outFile = layout.projectDirectory.file("out/KeyLocalLock.fir.txt").asFile
    val classesDir = layout.buildDirectory.dir("target-classes").get().asFile
    dependsOn(tasks.jar)
    classpath = compilerRuntime
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Xplugin=${pluginJar.get().asFile.absolutePath}",
                "-P", "plugin:kv.firdump:include=$targetSourceDir/KeyLocalLock.kt",
                "-P", "plugin:kv.firdump:out=${outFile.absolutePath}",
                "-language-version", "2.0",
                "-api-version", "2.0",
                "-jvm-target", "17",
                "-Xjsr305=strict",
                "-no-stdlib",
                "-no-reflect",
                "-classpath", targetClasspath.asPath,
                "-d", classesDir.absolutePath,
                "$targetSourceDir/KeyLock.kt",
                "$targetSourceDir/KeyLocalLock.kt",
            )
        },
    )
}
