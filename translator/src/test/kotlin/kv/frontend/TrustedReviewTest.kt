package kv.frontend

import kv.Aspects
import kv.ReqShield
import kv.cli.runCli
import kv.emit.SUPPORT
import kv.emit.Workspace
import kv.sidecar.SidecarError
import kv.sidecar.sha16
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B6 (DESIGN-v0.md 8.3 "reviewed 지문"): the `reviewed` note of a trusted fun is bound to the Kotlin declaration from
 * its first modifier keyword (else `fun`) to its end, in the module's sources or, for a library function, in the req-shield support sources. A
 * mismatch is the sidecar error stale-review, which `kv verify` reports as ERROR (exit 4). Copies of the specs and
 * sources only; no Silicon run.
 */
class TrustedReviewTest {
    @TempDir
    lateinit var dir: File

    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")
    private val aspectFile = Aspects.aspectFile("core-spring")

    /** A copy of the specs, and of core-spring's main sources with [old] replaced (once) by [new] in its aspect file. */
    private fun aspectRoot(
        old: String,
        new: String,
    ): File {
        copySpecs()
        val root = File(dir, "root")
        val src = Aspects.roots.getValue("core-spring")
        File(ReqShield.dir, src).copyRecursively(File(root, src))
        val f = File(root, aspectFile)
        val text = f.readText()
        check(text.split(old).size == 2) { "'$old' occurs ${text.split(old).size - 1} times in $aspectFile" }
        f.writeText(text.replace(old, new))
        return root
    }

    private fun copySpecs() = specDir.listFiles()!!.forEach { it.copyTo(File(dir, "specs/${it.name}"), overwrite = true) }

    private fun resolveAspect(root: File) = Frontend.resolve(File(dir, "specs/aspect.core-spring.kv"), root, Aspects.classpath, ReqShield.kotlincArgs)

    /**
     * The fingerprint of createReqShield in the edited copy, computed here. The sidecar is under `review intent`, so the
     * unit is the contract without comments and its note, then the Kotlin text from [modifier] to the closing brace
     * (DESIGN-E 12.4, T-A).
     */
    private fun createReqShieldSha(
        root: File,
        modifier: String = "private",
    ): String {
        val text = File(root, aspectFile).readText()
        val start = text.indexOf("$modifier fun createReqShield(")
        val end = text.indexOf("\n    }\n", start) + "\n    }".length
        val contract = "trusted fun ReqShieldAspect.createReqShield(org.aspectj.lang.ProceedingJoinPoint) returns fresh Shield throws effects none"
        return sha16("$contract ${text.substring(start, end)}".replace(Regex("""\s+"""), " ").trim())
    }

    private fun single(
        outcome: ResolveOutcome,
        code: String,
    ): SidecarError {
        assertNull(outcome.compileMessages)
        val e = outcome.errors.singleOrNull() ?: error("expected one error, got:\n${outcome.errors.joinToString("\n")}")
        assertEquals(code, e.code, e.toString())
        return e
    }

    @Test
    fun `a changed createReqShield body is stale-review at the reviewed note, with the new fingerprint`() {
        val root = aspectRoot("        return ReqShield(reqShieldConfiguration)\n", "        return ReqShield(reqShieldConfiguration.copy())\n")
        val e = single(resolveAspect(root), "stale-review")
        assertTrue(e.pos.file.endsWith("aspect.core-spring.kv") && e.pos.line == 13 && e.pos.col == 3, e.toString())
        assertTrue("sha256 \"7a7291901e9799d2\"" in e.message && "sha256 \"${createReqShieldSha(root)}\"" in e.message && "ReqShieldAspect.kt:116" in e.message, e.message)
    }

    @Test
    fun `the signature is part of the reviewed text`() {
        // The same parameter type, written another way: the declaration text changed, so the review is stale.
        val root = aspectRoot("fun createReqShield(joinPoint: ProceedingJoinPoint)", "fun createReqShield(joinPoint: org.aspectj.lang.ProceedingJoinPoint)")
        assertEquals("stale-review", single(resolveAspect(root), "stale-review").code)
    }

    @Test
    fun `a whitespace-only change, or a comment before fun, keeps the review`() {
        val root =
            aspectRoot(
                "    private fun createReqShield(joinPoint: ProceedingJoinPoint): ReqShield<T> {\n        val method = getTargetMethod(joinPoint)\n",
                "    /** A comment a person did not review. */\n    private fun createReqShield(joinPoint: ProceedingJoinPoint): ReqShield<T> {\n\n\t        val method =   getTargetMethod(joinPoint)\n",
            )
        val outcome = resolveAspect(root)
        assertNull(outcome.compileMessages)
        assertEquals(emptyList(), outcome.errors)
    }

    @Test
    fun `a changed modifier is stale-review, because the reviewed text starts at the first modifier`() {
        val root = aspectRoot("    private fun createReqShield(", "    internal fun createReqShield(")
        val e = single(resolveAspect(root), "stale-review")
        assertTrue("sha256 \"7a7291901e9799d2\"" in e.message && "sha256 \"${createReqShieldSha(root, "internal")}\"" in e.message, e.message)
    }

    @Test
    fun `an annotation before the first modifier is outside the reviewed text`() {
        val root = aspectRoot("    private fun createReqShield(", "    @Suppress(\"UNCHECKED_CAST\")\n    private fun createReqShield(")
        assertEquals(emptyList(), resolveAspect(root).errors)
    }

    @Test
    fun `a trusted fun note without a fingerprint is an error that names the fingerprint to record`() {
        copySpecs()
        val spec = File(dir, "specs/aspect.core-spring.kv")
        spec.writeText(spec.readText().replace(" sha256 \"7a7291901e9799d2\"", ""))
        val e = single(resolveAspect(ReqShield.dir), "missing-fingerprint")
        assertTrue("sha256 \"7a7291901e9799d2\"" in e.message, e.message)
    }

    @Test
    fun `a library trusted fun is read from the support sources under the root`() {
        val ws = Workspace(dir)
        ws.editSource("coroutine", "$SUPPORT/com/linecorp/cse/reqshield/support/config/LocalLockLimit.kt", "currentSize < limit) return false", "currentSize <= limit) return false")
        val e = single(ws.resolve("coroutine"), "stale-review")
        assertTrue("trusted fun rejectsNewEntry" in e.message && "sha256 \"d00c8149a8a320cb\"" in e.message && "LocalLockLimit.kt:86" in e.message, e.message)
        // Without the support sources the review cannot be checked.
        File(ws.root, SUPPORT).deleteRecursively()
        val missing = ws.resolve("coroutine")
        assertEquals(listOf("review-source", "review-source"), missing.errors.map { it.code }, missing.errors.joinToString("\n"))
    }

    private fun kv(args: List<String>): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = runCli(args, PrintStream(out), PrintStream(err))
        return Triple(exit, out.toString(), err.toString())
    }

    @Test
    fun `kv verify reports a stale createReqShield review as ERROR, exit 4`() {
        val root = aspectRoot("        return ReqShield(reqShieldConfiguration)\n", "        return ReqShield(reqShieldConfiguration.copy())\n")
        val (exit, out, err) =
            kv(
                listOf(
                    "verify", "--root", root.path, "--classpath", Aspects.classpath.joinToString(File.pathSeparator),
                    "--kotlinc", ReqShield.kotlincArgs.joinToString(" "), "--out", File(dir, "a.vpr").path, File(dir, "specs/aspect.core-spring.kv").path,
                ),
            )
        assertEquals(4, exit, out + err)
        assertTrue("error[stale-review]: trusted fun createReqShield changed after its review" in err, err)
    }

    @Test
    fun `kv verify reports a stale allowance clause review as ERROR, exit 4`() {
        val ws = Workspace(dir)
        ws.editSpec("keylocallock.common.kv", "unbacked_only_if P3b: !old(has(S, k)) && rej && limit != 0", "unbacked_only_if P3b: rej && limit != 0")
        val (exit, out, err) =
            kv(
                listOf(
                    "verify", "--root", ws.root.path, "--classpath", ReqShield.classpath.joinToString(File.pathSeparator),
                    "--kotlinc", ReqShield.kotlincArgs.joinToString(" "), "--out", File(dir, "c.vpr").path, File(ws.specs, "keylocallock.core.kv").path,
                ),
            )
        assertEquals(4, exit, out + err)
        assertTrue("error[stale-review]: unbacked_only_if P3b of tryLock changed after its review" in err, err)
    }
}
