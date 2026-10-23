// Translator v0 for the Kotlin-subset verifier (DESIGN-v0.md).
// The `kv` CLI runs the K2 compiler in-process with this project's FIR plugin.
// The compiler plugin API is unstable, so every Kotlin artifact here is pinned to one compiler version.

plugins {
    kotlin("jvm") version "2.4.21"
    application
}

repositories {
    mavenCentral()
}

val kotlinCompilerVersion = "2.4.21"

// req-shield checkout that holds the KeyLocalLock sources. Read only.
val reqShieldDir = providers.gradleProperty("reqShieldDir").getOrElse("/Users/ykhfree/IdeaProjects/req-shield")

// Compile classpath of req-shield's core and core-kotlin-coroutine modules, with the versions from
// req-shield's libs.versions.toml. KeyLocalLock needs nothing else.
val reqShieldClasspath: Configuration by configurations.creating

dependencies {
    // The plugin part compiles against the compiler API and does not bundle it.
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinCompilerVersion")
    // The kv CLI runs that compiler in-process, so the CLI needs it at run time.
    runtimeOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:$kotlinCompilerVersion")

    testImplementation(kotlin("test"))

    reqShieldClasspath("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    reqShieldClasspath("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.1")
    reqShieldClasspath("org.slf4j:slf4j-api:1.7.36")
    reqShieldClasspath(files("$reqShieldDir/support/build/libs/support-1.0.0.jar"))
}

application {
    applicationName = "kv"
    mainClass.set("kv.cli.MainKt")
}

// req-shield's library modules compile with these flags.
val reqShieldKotlincArgs = "-language-version 2.0 -api-version 2.0 -jvm-target 17 -Xjsr305=strict"

tasks.test {
    useJUnitPlatform()
    systemProperty("kv.reqShieldDir", reqShieldDir)
    systemProperty("kv.reqShieldClasspath", reqShieldClasspath.asPath)
    systemProperty("kv.reqShieldKotlincArgs", reqShieldKotlincArgs)
    systemProperty("kv.specDir", rootProject.layout.projectDirectory.dir("../specs").asFile.absolutePath)
}

// `./gradlew discoverReqShield`, `resolveReqShield` and `lowerReqShield`: run `kv discover`, `kv resolve` and
// `kv lower` on both KeyLocalLock variants. The sidecar specs live in ../specs/reqshield.
val discoverReqShield by tasks.registering {
    group = "kv"
    description = "Lists the ConcurrentHashMap.compute sites of both req-shield KeyLocalLock variants."
}
val resolveReqShield by tasks.registering {
    group = "kv"
    description = "Binds the names of both KeyLocalLock sidecars to FIR symbols."
}
val lowerReqShield by tasks.registering {
    group = "kv"
    description = "Prints the KVIR of both KeyLocalLock variants and checks W1-W14."
}

/** Runs the kv CLI with [args]: the command first, then its arguments after the req-shield classpath and flags. */
fun JavaExec.kv(vararg args: String) {
    group = "kv"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("kv.cli.MainKt")
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(args[0], "--classpath", reqShieldClasspath.asPath, "--kotlinc", reqShieldKotlincArgs) + args.drop(1)
        },
    )
}

for ((variant, dir) in listOf(
    "Coroutine" to "core-kotlin-coroutine/src/main/kotlin/com/linecorp/cse/reqshield/kotlin/coroutine",
    "Core" to "core/src/main/kotlin/com/linecorp/cse/reqshield",
)) {
    val sidecar = layout.projectDirectory.file("../specs/reqshield/keylocallock.${variant.lowercase()}.kv").asFile.path
    val discover = tasks.register<JavaExec>("discover$variant") { kv("discover", "$reqShieldDir/$dir/KeyLock.kt", "$reqShieldDir/$dir/KeyLocalLock.kt") }
    val resolve = tasks.register<JavaExec>("resolve$variant") { kv("resolve", "--root", reqShieldDir, sidecar) }
    val lower = tasks.register<JavaExec>("lower$variant") { kv("lower", "--root", reqShieldDir, sidecar) }
    discoverReqShield { dependsOn(discover) }
    resolveReqShield { dependsOn(resolve) }
    lowerReqShield { dependsOn(lower) }
}
