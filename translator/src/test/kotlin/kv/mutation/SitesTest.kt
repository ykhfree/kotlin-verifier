package kv.mutation

import kv.Aspects
import kv.Harness
import kv.ReqShield
import kv.cli.runCli
import kv.frontend.Frontend
import kv.lower.LockFixture
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `kv mutation-sites` (MUTATION.md 2 and 3): which FIR elements each operator mutates, with what text, and where it
 * must not fire. The fixture has a `fun` operation `run`, a `compute in` operation `sweep`, a helper that `run`
 * calls, and code outside the scope: a helper nobody calls, a member function, and sweep's code around its action.
 */
class SitesTest {
    @TempDir
    lateinit var dir: File

    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")

    private val fixture =
        LockFixture(
            body =
                """
                if (e == null) return@compute null
                if (now > e.expiresAt && e.isHeld.compareAndSet(true, false)) {
                    e.token = null
                    e.semaphore.release()
                    return@compute null
                }
                if (e.semaphore.tryAcquire()) {
                    e.isHeld.set(true)
                    e.expiresAt = now + timeout
                    result.set(nextToken())
                }
                e
                """.trimIndent(),
            companion =
                """
                |        private fun nextToken(): String = "local-${'$'}{counter.incrementAndGet()}"
                |        private fun unused(): Boolean = counter.incrementAndGet() > 0L || false
                |        fun sweep(now: Long) {
                |            if (now > 0) {
                |                lockMap.keys.forEach { key ->
                |                    lockMap.compute(key) { _, info ->
                |                        if (info == null || now > info.expiresAt) null else info
                |                    }
                |                }
                |            }
                |        }
                """.trimMargin(),
            members = "    fun other(x: Long): Boolean = x > 1 && true\n",
            operations = LockFixture.DEFAULT_OPERATION + "\noperation sweep = compute in Lock.Companion {\n  free now = param now\n}",
        )

    private fun sites(f: LockFixture): List<MutationSite> {
        var found: SiteList? = null
        f.lower(dir) { found = moduleSites(it, dir).first }
        val list = found ?: fail("the module was not lowered")
        assertEquals(emptyList(), list.problems)
        return list.sites
    }

    /** `scope line:col original -> replacement` per edit, for one operator. */
    private fun List<MutationSite>.shown(op: Operator): List<String> =
        filter { it.operator == op }.map { s -> "${s.scope} ${s.pos.line}:${s.pos.col} " + s.edits.joinToString(" | ") { "${it.original} -> ${it.replacement}" } }

    /** `line:col` of [fragment] (see [LockFixture.at]), [shift] columns to the right. */
    private fun LockFixture.pos(
        fragment: String,
        after: String? = null,
        shift: Int = 0,
    ): String = at(fragment, after).let { (l, c) -> "$l:${c + shift}" }

    @Test
    fun `ROR and COR swap the operator token of every comparison and logic operator in scope, and nothing outside`() {
        val f = fixture
        val s = sites(f)
        assertEquals(
            listOf(
                "operation run ${f.pos("e == null")} == -> !=",
                "operation run ${f.pos("now > e.expiresAt")} > -> >=",
                "operation sweep ${f.pos("info == null")} == -> !=",
                "operation sweep ${f.pos("now > info.expiresAt")} > -> >=",
            ),
            s.shown(Operator.ROR),
        )
        // The token itself is replaced: its offsets hold the operator text.
        assertTrue(s.filter { it.operator == Operator.ROR || it.operator == Operator.COR }.all { it.edits.single().original in setOf("==", ">", "&&", "||") })
        val expiry = s.single { it.operator == Operator.ROR && it.scope == "operation run" && it.edits.single().original == ">" }
        assertEquals(mapOf("op" to ">", "fields" to "expiresAt", "boundary" to "true"), expiry.facts)
        assertEquals("translated", expiry.ledger)
        assertEquals(listOf("&& -> ||", "|| -> &&"), s.filter { it.operator == Operator.COR }.map { it.edits.single().let { e -> "${e.original} -> ${e.replacement}" } })
        assertEquals(listOf("operation run", "operation sweep"), s.filter { it.operator == Operator.COR }.map { it.scope })
    }

    @Test
    fun `NEG negates each if condition of the operations, not one outside the action of a compute-in operation`() {
        val f = fixture
        assertEquals(
            listOf(
                "operation run ${f.pos("e == null")} e == null -> !(e == null)",
                "operation run ${f.pos("now > e.expiresAt")} now > e.expiresAt && e.isHeld.compareAndSet(true, false) -> !(now > e.expiresAt && e.isHeld.compareAndSet(true, false))",
                "operation run ${f.pos("e.semaphore.tryAcquire()")} e.semaphore.tryAcquire() -> !(e.semaphore.tryAcquire())",
                "operation sweep ${f.pos("info == null")} info == null || now > info.expiresAt -> !(info == null || now > info.expiresAt)",
            ),
            sites(f).shown(Operator.NEG),
        )
    }

    @Test
    fun `BOOL flips the boolean constants in scope only`() {
        val f = fixture
        assertEquals(
            listOf(
                "operation run ${f.pos("true, false")} true -> false",
                "operation run ${f.pos("false)) {")} false -> true",
                "operation run ${f.pos("true)", "e.isHeld.set")} true -> false",
            ),
            sites(f).shown(Operator.BOOL),
        )
    }

    @Test
    fun `SDL deletes call and assignment statements, never a declaration, a return or a lambda result`() {
        val f = fixture
        val s = sites(f).filter { it.operator == Operator.SDL }
        assertEquals(
            listOf("lockMap.compute(key) { _, e ->", "e.token = null", "e.semaphore.release()", "e.isHeld.set(true)", "e.expiresAt = now + timeout", "result.set(nextToken())"),
            s.map { it.edits.single().original.lineSequence().first().trim() },
        )
        assertTrue(s.all { it.edits.single().replacement == "" && it.scope == "operation run" })
        // MUTATION.md 7.7: the entry is removed right after the first two, not after the others.
        assertEquals(listOf(null, "true", "true", "false", "false", "false"), s.map { it.facts["removalFollows"] })
        assertEquals(mapOf("field" to "token", "value" to "null", "removalFollows" to "true"), s[1].facts)
        assertEquals("java/util/concurrent/Semaphore.release", s[2].facts["callee"])
    }

    @Test
    fun `RET mutates the action lambdas' results and the operation's result, with null and the value parameter`() {
        val f = fixture
        assertEquals(
            listOf(
                "operation run ${f.pos("return@compute null", shift = 15)} null -> e",
                "operation run ${f.pos("return@compute null", "e.semaphore.release()", 15)} null -> e",
                // The lambda's last value `e`: two lines below `result.set`, in the column of the `if` above it.
                "operation run ${f.at("result.set(nextToken())").first + 2}:${f.at("if (e.semaphore").second} e -> null",
                "operation run ${f.pos("result.get()")} result.get() -> null",
                "operation sweep ${f.pos("null else info")} null -> info",
                "operation sweep ${f.pos("null else info", shift = 10)} info -> null",
            ),
            sites(f).shown(Operator.RET),
        )
    }

    @Test
    fun `AOR changes the operator of Long arithmetic with an ASSUMPTION, and drops its right operand`() {
        val f = fixture
        assertEquals(
            listOf("operation run ${f.pos("now + timeout")} + -> -", "operation run ${f.pos("now + timeout")} now + timeout -> now"),
            sites(f).shown(Operator.AOR).sorted(),
        )
    }

    @Test
    fun `MOP changes model calls in the operations and in the helper they call, not in a helper nobody calls`() {
        val f = fixture
        assertEquals(
            listOf(
                "operation run ${f.pos("e.isHeld.compareAndSet")} true, false -> false, true",
                "operation run ${f.pos("e.semaphore.tryAcquire()")} e.semaphore.tryAcquire() -> true",
                "helper nextToken ${f.pos("counter.incrementAndGet()")} incrementAndGet -> get",
            ),
            sites(f).shown(Operator.MOP),
        )
    }

    @Test
    fun `RACY reads the map right before the action and guards one condition, and the insertion compiles`() {
        val f = fixture
        val racy = sites(f).filter { it.operator == Operator.RACY }
        val runIndent = " ".repeat(f.at("lockMap.compute(key) { _, e").second - 1)
        val sweepIndent = " ".repeat(f.at("lockMap.compute(key) { _, info").second - 1)
        assertEquals(
            listOf(
                "racy_1 && e == null | val racy_1 = lockMap.containsKey(key)\n$runIndent",
                "racy_2 && now > e.expiresAt && e.isHeld.compareAndSet(true, false) | val racy_2 = lockMap.containsKey(key)\n$runIndent",
                "racy_3 && e.semaphore.tryAcquire() | val racy_3 = lockMap.containsKey(key)\n$runIndent",
                // A disjunction keeps its meaning under the guard.
                "racy_4 && (info == null || now > info.expiresAt) | val racy_4 = lockMap.containsKey(key)\n$sweepIndent",
            ),
            racy.map { s -> s.edits.joinToString(" | ") { it.replacement } },
        )
        assertTrue(racy.all { it.facts == mapOf("map" to "m", "setOnce" to "") })
        val insertAt = f.kotlin.indexOf("lockMap.compute(key) { _, e")
        assertTrue(racy.take(3).all { it.edits[1].start == insertAt && it.edits[1].end == insertAt })
        // The guard of an early `return@compute null` on a null check removes the smart cast after it, so that
        // mutant does not compile (stillborn); the others do, with the read in place before the action.
        val compiles =
            racy.map { s ->
                val mutated = apply(f.kotlin, s.edits)
                Harness.compile(File(dir, "racy-${s.edits[0].start}"), "Lock.kt" to mutated, mustCompile = false).compiled
            }
        assertEquals(listOf(false, true, true, true), compiles)
    }

    @Test
    fun `the coroutine KeyLocalLock has every site in an operation, its action, its helper or a trusted fun body`() {
        val (sites, library) = coroutineSites()
        // req-shield 2e792d2 core-kotlin-coroutine KeyLocalLock.kt: nextToken 80, the sweep lambda 97-112, tryLock
        // 145-196, unLock 198-228; support LocalLockLimit.kt: rejectsNewEntry 86-101 (parseMaxEntries, 109-123, also
        // compares, and nowToEpochTime has no site).
        val inScope = listOf(80..80, 97..112, 145..196, 198..228)
        val outside = sites.filter { s -> inScope.none { s.pos.line in it } }
        assertEquals(emptyList(), outside.map { "${it.operator} ${it.pos}" })
        assertEquals(setOf("operation tryLock", "operation unLock", "operation sweep", "helper nextToken"), sites.map { it.scope }.toSet())
        assertEquals(listOf("nowToEpochTime", "rejectsNewEntry"), library.map { it.name })
        val (now, lib) = library.map { librarySites(it, setOf("jdk.AtomicLong"), ReqShield.classpath, ReqShield.kotlincArgs) }
        assertEquals(emptyList(), now.problems + lib.problems)
        assertEquals(emptyList(), now.sites, "fun nowToEpochTime() = System.currentTimeMillis() has no site")
        assertTrue(lib.sites.isNotEmpty() && lib.sites.all { it.pos.line in 86..101 && it.scope == "trusted rejectsNewEntry" && it.ledger == "-" }, lib.sites.joinToString("\n"))
        // The operator counts of MUTATION.md 3 on this module, and no two sites make the same mutant.
        val all = sites + lib.sites
        assertEquals(
            mapOf("ROR" to 10, "COR" to 4, "NEG" to 12, "BOOL" to 12, "SDL" to 18, "RET" to 12, "AOR" to 4, "MOP" to 7, "RACY" to 10),
            Operator.entries.associate { op -> op.name to all.count { it.operator == op } },
        )
        assertEquals(all.size, all.map { it.file to it.edits }.distinct().size)
    }

    @Test
    fun `the sweep RACY of the coroutine KeyLocalLock goes inside the forEach lambda and compiles`() {
        val (sites, _) = coroutineSites()
        val sweep = sites.filter { it.operator == Operator.RACY && it.scope == "operation sweep" }.map { it.edits[0].replacement }
        assertEquals(listOf("racy_8 && lockInfo == null", "racy_9 && now > lockInfo.expiresAt", "racy_10 && lockInfo.isHeld.compareAndSet(true, false)"), sweep)
        val racy9 = sites.single { it.operator == Operator.RACY && it.edits[0].replacement.startsWith("racy_9 ") }
        val file = File(ReqShield.coroutineDir, "KeyLocalLock.kt")
        val mutated = apply(file.readText(), racy9.edits)
        val indent = " ".repeat(36)
        assertTrue("lockMap.keys.forEach { key ->\n${indent}val racy_9 = lockMap.containsKey(key)\n${indent}lockMap.compute(key) { _, lockInfo ->\n" in mutated, mutated.lines().subList(94, 101).joinToString("\n"))
        assertTrue("if (racy_9 && now > lockInfo.expiresAt) {" in mutated)
        val copy = File(dir, "racy/KeyLocalLock.kt").apply { parentFile.mkdirs(); writeText(mutated) }
        val result = Frontend.run(listOf(File(ReqShield.coroutineDir, "KeyLock.kt"), copy), ReqShield.classpath, ReqShield.kotlincArgs)
        assertTrue(result.compiled, result.messages)
    }

    @Test
    fun `in an aspect module the sites are the getOrCreate lambda result and the createReqShield body, nothing in the other map calls`() {
        var found: Pair<SiteList, List<LibraryTrusted>>? = null
        val outcome = Frontend.resolve(File(specDir, "aspect.core-spring.kv"), ReqShield.dir, Aspects.classpath, ReqShield.kotlincArgs) { found = moduleSites(it, ReqShield.dir) }
        assertEquals(emptyList(), outcome.errors)
        val (list, library) = found ?: fail("not lowered")
        assertEquals(emptyList(), list.problems)
        assertEquals(emptyList(), library.map { it.name })
        // core-spring ReqShieldAspect.kt: getOrCreateReqShield 111-114, createReqShield 116-151.
        assertEquals(
            listOf(
                "RET operation getOrCreate 113 translated createReqShield(joinPoint) -> null",
                "SDL trusted createReqShield 120 trusted require",
                "COR trusted createReqShield 120 trusted || -> &&",
                "ROR trusted createReqShield 120 trusted != -> ==",
                "SDL trusted createReqShield 128 trusted reqShieldCache.put(key, reqShieldData, timeToLiveMillis)",
                "BOOL trusted createReqShield 129 trusted true -> false",
            ),
            list.sites.map { s ->
                val e = s.edits.single()
                "${s.operator} ${s.scope} ${s.pos.line} ${s.ledger} " + if (s.operator == Operator.SDL) e.original.substringBefore('(').takeIf { it == "require" } ?: e.original else "${e.original} -> ${e.replacement}"
            },
        )
    }

    @Test
    fun `kv mutation-sites prints the sites and the sidecar properties as JSON`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val args = listOf("mutation-sites", "--root", ReqShield.dir.path, "--classpath", ReqShield.classpath.joinToString(File.pathSeparator), "--kotlinc", ReqShield.kotlincArgs.joinToString(" "), File(specDir, "keylocallock.reactor.kv").path)
        val exit = runCli(args, PrintStream(out), PrintStream(err))
        val json = out.toString()
        assertEquals(0, exit, err.toString())
        assertEquals(73 + 12, Regex("\"operator\": ").findAll(json).count(), "73 module sites and 12 in rejectsNewEntry")
        assertTrue("\"file\": \"core-reactor/src/main/kotlin/com/linecorp/cse/reqshield/reactor/KeyLocalLock.kt\", \"line\": 171, \"col\": 21" in json, json)
        assertTrue("\"replacement\": \"val racy_1 = lockMap.containsKey(completeKey)\\n${" ".repeat(12)}\"" in json, json)
        assertTrue("\"file\": \"support/src/main/kotlin/com/linecorp/cse/reqshield/support/config/LocalLockLimit.kt\"" in json, json)
        // The judge reads the properties with their review notes (MUTATION.md 6).
        assertTrue("{\"kind\": \"ends_only_if\", \"operation\": \"tryLock\", \"id\": \"P3a\", \"text\": \"now > old(tok(qt).gLease)\", \"reviewed\": \"ykhfree 2026-10-23: README: only an expired lock is taken over" in json, json)
        assertTrue("{\"kind\": \"lemma\", \"operation\": null, \"id\": \"P3c\"" in json, json)
    }

    private fun coroutineSites(): Pair<List<MutationSite>, List<LibraryTrusted>> {
        var found: Pair<SiteList, List<LibraryTrusted>>? = null
        val outcome = Frontend.resolve(File(specDir, "keylocallock.coroutine.kv"), ReqShield.dir, ReqShield.classpath, ReqShield.kotlincArgs) { found = moduleSites(it, ReqShield.dir) }
        assertEquals(emptyList(), outcome.errors)
        val (list, library) = found ?: fail("not lowered")
        assertEquals(emptyList(), list.problems)
        return list.sites to library
    }

    private fun apply(
        text: String,
        edits: List<Edit>,
    ): String =
        edits.sortedByDescending { it.start }.fold(text) { t, e ->
            assertEquals(e.original, t.substring(e.start, e.end))
            t.substring(0, e.start) + e.replacement + t.substring(e.end)
        }
}
