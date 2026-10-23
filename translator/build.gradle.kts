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

// Compile classpath of req-shield's core, core-kotlin-coroutine and core-reactor modules, with the versions from
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
    reqShieldClasspath("io.projectreactor:reactor-core:3.7.19")
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
    // The M5 gate (vpr_normalize.py) and the hand-written target models.
    systemProperty("kv.spikeDir", rootProject.layout.projectDirectory.dir("../spike").asFile.absolutePath)
    // The M8 mutant script (scripts/mutants.py), tested with a fake kv.
    systemProperty("kv.scriptsDir", layout.projectDirectory.dir("scripts").asFile.absolutePath)
}

// `./gradlew discoverReqShield`, `resolveReqShield`, `lowerReqShield`, `emitReqShield` and `verifyReqShield`: run
// `kv discover`, `kv resolve`, `kv lower`, `kv emit` and `kv verify` on the three KeyLocalLock variants (verify needs
// Silicon: VIPER_HOME or the default install). The sidecar specs live in ../specs/reqshield.
val discoverReqShield by tasks.registering {
    group = "kv"
    description = "Lists the ConcurrentHashMap.compute sites of the three req-shield KeyLocalLock variants."
}
val resolveReqShield by tasks.registering {
    group = "kv"
    description = "Binds the names of the three KeyLocalLock sidecars to FIR symbols."
}
val lowerReqShield by tasks.registering {
    group = "kv"
    description = "Prints the KVIR of the three KeyLocalLock variants and checks W1-W14."
}
val emitReqShield by tasks.registering {
    group = "kv"
    description = "Writes the Viper programs of the three KeyLocalLock variants to build/kv/."
}
val verifyReqShield by tasks.registering {
    group = "kv"
    description = "Verifies the three KeyLocalLock variants with Silicon and prints the reports."
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
    "Reactor" to "core-reactor/src/main/kotlin/com/linecorp/cse/reqshield/reactor",
)) {
    val sidecar = layout.projectDirectory.file("../specs/reqshield/keylocallock.${variant.lowercase()}.kv").asFile.path
    val discover = tasks.register<JavaExec>("discover$variant") { kv("discover", "$reqShieldDir/$dir/KeyLock.kt", "$reqShieldDir/$dir/KeyLocalLock.kt") }
    val resolve = tasks.register<JavaExec>("resolve$variant") { kv("resolve", "--root", reqShieldDir, sidecar) }
    val lower = tasks.register<JavaExec>("lower$variant") { kv("lower", "--root", reqShieldDir, sidecar) }
    val vpr = layout.buildDirectory.file("kv/keylocallock.${variant.lowercase()}.vpr").get().asFile.path
    val emit = tasks.register<JavaExec>("emit$variant") { kv("emit", "--root", reqShieldDir, "--out", vpr, sidecar) }
    val verify = tasks.register<JavaExec>("verify$variant") { kv("verify", "--root", reqShieldDir, "--out", vpr, sidecar) }
    discoverReqShield { dependsOn(discover) }
    resolveReqShield { dependsOn(resolve) }
    lowerReqShield { dependsOn(lower) }
    emitReqShield { dependsOn(emit) }
    verifyReqShield { dependsOn(verify) }
}

// `./gradlew mutantsReqShield`: M8 (DESIGN-v0.md 6 and 7). Applies the Kotlin mutants to temp copies of both
// KeyLocalLock variants, runs the installed `kv verify` on each, and checks the verdicts and failing obligations
// against scripts/mutants-expected.txt. Needs python3 and Silicon. It takes about a minute, so `test` does not run it.
// Options: -PmutantJobs=N (parallel runs, default 3), -PmutantOnly=SB1,SB2 (a subset).
val mutantsReqShield by tasks.registering(Exec::class) {
    group = "kv"
    description = "Runs the M8 Kotlin mutant script on both KeyLocalLock variants."
    dependsOn(tasks.installDist)
    executable = "python3"
    val kv = layout.buildDirectory.file("install/kv/bin/kv").get().asFile.path
    val out = layout.buildDirectory.dir("kv/mutants").get().asFile.path
    val specs = layout.projectDirectory.dir("../specs/reqshield").asFile.path
    val jobs = providers.gradleProperty("mutantJobs").getOrElse("3")
    val only = providers.gradleProperty("mutantOnly").orNull
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-I", "scripts/mutants.py", "--kv", kv, "--req-shield", reqShieldDir, "--classpath", reqShieldClasspath.asPath,
                "--kotlinc", reqShieldKotlincArgs, "--specs", specs, "--expected", "scripts/mutants-expected.txt", "--out", out,
                "--jobs", jobs,
            ) + (only?.let { listOf("--only", it) } ?: emptyList())
        },
    )
}
