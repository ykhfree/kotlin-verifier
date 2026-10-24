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

// Compile classpath of req-shield's three Spring aspect modules (DESIGN-B.md): core-spring, core-spring-webflux and
// core-spring-webflux-kotlin-coroutine. One classpath serves all three; their ReqShield packages differ.
val aspectClasspath: Configuration by configurations.creating

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

    // DESIGN-B.md 6.2 rule 4: the jars the three Spring aspect modules compile against, from the Gradle cache.
    // The ReqShield classes come from the jars req-shield built from the same commit (build/libs), as the support jar.
    for (coordinates in listOf(
        "org.jetbrains.kotlin:kotlin-stdlib:2.0.21",
        "org.jetbrains:annotations:13.0",
        "org.springframework:spring-context:6.2.19",
        "org.springframework:spring-aop:6.2.19",
        "org.springframework:spring-beans:6.2.19",
        "org.springframework:spring-core:6.2.19",
        "org.springframework:spring-expression:6.2.19",
        "org.springframework:spring-jcl:6.2.19",
        "org.aspectj:aspectjweaver:1.9.25.1",
        "org.slf4j:slf4j-api:1.7.36",
        "io.projectreactor:reactor-core:3.7.19",
        "org.reactivestreams:reactive-streams:1.0.4",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.1",
        "org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.8.1",
        "org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.8.1",
    )) {
        aspectClasspath(coordinates) { isTransitive = false }
    }
    aspectClasspath(
        files(
            "$reqShieldDir/support/build/libs/support-1.0.0.jar",
            "$reqShieldDir/core/build/libs/core-1.0.0.jar",
            "$reqShieldDir/core-reactor/build/libs/core-reactor-1.0.0.jar",
            "$reqShieldDir/core-kotlin-coroutine/build/libs/core-kotlin-coroutine-1.0.0.jar",
        ),
    )
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
    systemProperty("kv.aspectClasspath", aspectClasspath.asPath)
    systemProperty("kv.specDir", rootProject.layout.projectDirectory.dir("../specs").asFile.absolutePath)
    // The M5 gate (vpr_normalize.py) and the hand-written target models.
    systemProperty("kv.spikeDir", rootProject.layout.projectDirectory.dir("../spike").asFile.absolutePath)
    // The M8 mutant script (scripts/mutants.py), tested with a fake kv.
    systemProperty("kv.scriptsDir", layout.projectDirectory.dir("scripts").asFile.absolutePath)
    // The Kotlin compiler's own classpath: the sandbox compile.sh of scripts/aicode.py runs it as a plain kotlinc.
    systemProperty("kv.compilerClasspath", configurations.runtimeClasspath.get().asPath)
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

/**
 * Runs the kv CLI with [args]: the command first, then its arguments after the compile classpath [cp] (default: the
 * KeyLocalLock one) and the req-shield flags.
 */
fun JavaExec.kv(
    vararg args: String,
    cp: Configuration = reqShieldClasspath,
) {
    group = "kv"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("kv.cli.MainKt")
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(args[0], "--classpath", cp.asPath, "--kotlinc", reqShieldKotlincArgs) + args.drop(1)
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

// `./gradlew discoverAspect`, `resolveAspect`, `lowerAspect`, `emitAspect` and `verifyAspect`: run `kv discover`,
// `kv resolve`, `kv lower`, `kv emit` and `kv verify` on the three req-shield Spring aspect modules (DESIGN-B.md 8,
// B0-B3; verify needs Silicon). Each module also has its own tasks, e.g. `discoverAspectCoreSpring`. emit and verify
// write build/kv/aspect.{core-spring,webflux,coroutine}.vpr.
// The sidecars are ../specs/reqshield/aspect.{core-spring,webflux,coroutine}.kv.
val discoverAspect by tasks.registering {
    group = "kv"
    description = "Lists the ConcurrentHashMap action calls of the three req-shield Spring aspect modules."
}
val resolveAspect by tasks.registering {
    group = "kv"
    description = "Binds the names of the three Spring aspect sidecars to FIR symbols."
}
val lowerAspect by tasks.registering {
    group = "kv"
    description = "Prints the KVIR of the three Spring aspect modules and checks W1-W16."
}
val emitAspect by tasks.registering {
    group = "kv"
    description = "Writes the Viper programs of the three Spring aspect modules to build/kv/."
}
val verifyAspect by tasks.registering {
    group = "kv"
    description = "Verifies the three Spring aspect modules with Silicon and prints the reports."
}

for ((module, dir, spec) in listOf(
    Triple("CoreSpring", "core-spring", "core-spring"),
    Triple("Webflux", "core-spring-webflux", "webflux"),
    Triple("Coroutine", "core-spring-webflux-kotlin-coroutine", "coroutine"),
)) {
    val sidecar = layout.projectDirectory.file("../specs/reqshield/aspect.$spec.kv").asFile.path
    val discover = tasks.register<JavaExec>("discoverAspect$module") { kv("discover", "$reqShieldDir/$dir/src/main/kotlin", cp = aspectClasspath) }
    val resolve = tasks.register<JavaExec>("resolveAspect$module") { kv("resolve", "--root", reqShieldDir, sidecar, cp = aspectClasspath) }
    val lower = tasks.register<JavaExec>("lowerAspect$module") { kv("lower", "--root", reqShieldDir, sidecar, cp = aspectClasspath) }
    val vpr = layout.buildDirectory.file("kv/aspect.$spec.vpr").get().asFile.path
    val emit = tasks.register<JavaExec>("emitAspect$module") { kv("emit", "--root", reqShieldDir, "--out", vpr, sidecar, cp = aspectClasspath) }
    val verify = tasks.register<JavaExec>("verifyAspect$module") { kv("verify", "--root", reqShieldDir, "--out", vpr, sidecar, cp = aspectClasspath) }
    discoverAspect { dependsOn(discover) }
    resolveAspect { dependsOn(resolve) }
    lowerAspect { dependsOn(lower) }
    emitAspect { dependsOn(emit) }
    verifyAspect { dependsOn(verify) }
}

// `./gradlew mutantsReqShield`: M8 (DESIGN-v0.md 6 and 7). Applies the Kotlin mutants to temp copies of the three
// KeyLocalLock variants, runs the installed `kv verify` on each, and checks the verdicts and failing obligations
// against scripts/mutants-expected.txt. Needs python3 and Silicon. It takes about a minute, so `test` does not run it.
// Options: -PmutantJobs=N (parallel runs, default 3), -PmutantOnly=SB1,SB2 (a subset).
val mutantsReqShield by tasks.registering(Exec::class) {
    group = "kv"
    description = "Runs the M8 Kotlin mutant script on the three KeyLocalLock variants."
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

// `./gradlew mutantsAspect`: B4 (DESIGN-B.md 7.2 and 8). The same script with `--suite aspect`: the Kotlin mutants
// M1-M7, R1 and R2 on temp copies of the three Spring aspect modules, checked against
// scripts/aspect-mutants-expected.txt. Needs python3 and Silicon. Options: -PmutantJobs=N, -PmutantOnly=M1,M2.
val mutantsAspect by tasks.registering(Exec::class) {
    group = "kv"
    description = "Runs the B4 Kotlin mutant script on the three Spring aspect modules."
    dependsOn(tasks.installDist)
    executable = "python3"
    val kv = layout.buildDirectory.file("install/kv/bin/kv").get().asFile.path
    val out = layout.buildDirectory.dir("kv/aspect-mutants").get().asFile.path
    val specs = layout.projectDirectory.dir("../specs/reqshield").asFile.path
    val jobs = providers.gradleProperty("mutantJobs").getOrElse("3")
    val only = providers.gradleProperty("mutantOnly").orNull
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-I", "scripts/mutants.py", "--suite", "aspect", "--kv", kv, "--req-shield", reqShieldDir, "--classpath", aspectClasspath.asPath,
                "--kotlinc", reqShieldKotlincArgs, "--specs", specs, "--expected", "scripts/aspect-mutants-expected.txt", "--out", out,
                "--jobs", jobs,
            ) + (only?.let { listOf("--only", it) } ?: emptyList())
        },
    )
}

// `./gradlew mechanicalMutants`: the mechanical mutation measurement of MUTATION.md. scripts/mechanical.py lists the
// mutation sites of the six modules with `kv mutation-sites`, applies each to a temp copy, runs `kv emit`, and runs
// `kv verify` (Silicon, with its budget) once per normalized model hash. It writes results.jsonl, summary.md and
// judge/ to ../mutation; the copies, models and reports go to build/kv/mechanical. Needs python3 and Silicon.
// Options: -PmutantJobs=N (parallel runs, default 3), -PmutantModules=keylocallock.core,aspect.webflux (a subset),
// -PmutantResults=DIR (another results directory, relative to this project, e.g. for a re-measurement).
val mechanicalMutants by tasks.registering(Exec::class) {
    group = "kv"
    description = "Runs the MUTATION.md mechanical mutation measurement on the six req-shield modules."
    dependsOn(tasks.installDist)
    executable = "python3"
    val kv = layout.buildDirectory.file("install/kv/bin/kv").get().asFile.path
    val work = layout.buildDirectory.dir("kv/mechanical").get().asFile.path
    val results = layout.projectDirectory.dir(providers.gradleProperty("mutantResults").getOrElse("../mutation")).asFile.path
    val specs = layout.projectDirectory.dir("../specs/reqshield").asFile.path
    val normalizer = layout.projectDirectory.file("../spike/vpr_normalize.py").asFile.path
    val jobs = providers.gradleProperty("mutantJobs").getOrElse("3")
    val modules = providers.gradleProperty("mutantModules").orNull
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-I", "scripts/mechanical.py", "--kv", kv, "--req-shield", reqShieldDir, "--classpath", reqShieldClasspath.asPath,
                "--aspect-classpath", aspectClasspath.asPath, "--kotlinc", reqShieldKotlincArgs, "--specs", specs, "--normalizer", normalizer,
                "--work", work, "--results", results, "--jobs", jobs,
            ) + (modules?.let { listOf("--modules", it) } ?: emptyList())
        },
    )
}

// `./gradlew aicodeEnv`: the AI-written-code measurement of ../AI-CODE.md. Writes build/kv/aicode-env.json, which
// scripts/aicode.py reads: the installed kv, the req-shield checkout, the KeyLocalLock compile classpath and flags, the
// compiler's own classpath (for the sandbox compile.sh), the specs, AI-CODE.md, the results directory (../aicode) and
// the work directory for sandboxes and copies (-PaicodeWork=DIR, default build/kv/aicode-work).
val aicodeEnv by tasks.registering {
    group = "kv"
    description = "Writes build/kv/aicode-env.json for scripts/aicode.py (AI-CODE.md)."
    dependsOn(tasks.installDist)
    val env = layout.buildDirectory.file("kv/aicode-env.json")
    val work = providers.gradleProperty("aicodeWork").getOrElse(layout.buildDirectory.dir("kv/aicode-work").get().asFile.path)
    outputs.upToDateWhen { false }
    doLast {
        val values =
            mapOf(
                "kv" to layout.buildDirectory.file("install/kv/bin/kv").get().asFile.path,
                "kvInstall" to layout.buildDirectory.dir("install/kv").get().asFile.path,
                "reqShield" to reqShieldDir,
                "classpath" to reqShieldClasspath.asPath,
                "kotlinc" to reqShieldKotlincArgs,
                "compilerClasspath" to configurations.runtimeClasspath.get().asPath,
                "specs" to layout.projectDirectory.dir("../specs/reqshield").asFile.canonicalPath,
                "aiCode" to layout.projectDirectory.file("../AI-CODE.md").asFile.canonicalPath,
                "results" to layout.projectDirectory.dir("../aicode").asFile.canonicalPath,
                "work" to File(work).absolutePath,
            )
        env.get().asFile.apply {
            parentFile.mkdirs()
            writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(values)) + "\n")
        }
        println("wrote ${env.get().asFile.path}")
    }
}
