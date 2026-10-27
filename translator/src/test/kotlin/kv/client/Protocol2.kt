package kv.client

import kv.ReqShield
import kv.emit.spikeDir
import kv.frontend.Frontend
import kv.frontend.ResolveOutcome
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The fixed core of DESIGN-E 5.6 for the E3 gate and the E4 checks: a copy of req-shield's core and support sources
 * with spike/protocol2/fixed-core.patch applied (req-shield itself is never edited), and the core sidecar with the
 * line of the polling loop in that copy (the patch moves it).
 */
object Protocol2 {
    private const val FILE = "core/src/main/kotlin/com/linecorp/cse/reqshield/ReqShield.kt"
    private const val LOOP = "        while (attempts < reqShieldConfig.maxAttemptGetCache) {"

    /** The core sidecar of the copy under [root]: its hole loop names the loop's line there. */
    fun sidecarFor(
        root: File,
        out: File,
    ): File {
        val line = File(root, FILE).readLines().indexOf(LOOP) + 1
        check(line > 0) { "no polling loop in ${File(root, FILE)}" }
        val text = File(System.getProperty("kv.specDir"), "reqshield/reqshield.core.kv").readText()
        check("handleLockFailure@181" in text)
        return out.apply { writeText(text.replace("handleLockFailure@181", "handleLockFailure@$line")) }
    }

    /** A copy of req-shield's core and support main sources under [dir] with the fix applied. */
    fun fixedCopy(dir: File): File {
        val root = File(dir, "fixed")
        for (d in listOf("core/src/main/kotlin", "support/src/main/kotlin")) File(ReqShield.dir, d).copyRecursively(File(root, d))
        val patch = File(spikeDir, "protocol2/fixed-core.patch")
        val p = ProcessBuilder("patch", "-p1", "-s", "-d", root.path, "-i", patch.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { "the patch does not apply: $out" }
        return root
    }

    /** The fixed core resolved with the signed sidecar (the fix changes no reviewed declaration). */
    fun resolveFixed(dir: File): ResolveOutcome {
        val root = fixedCopy(dir)
        val o = Frontend.resolve(sidecarFor(root, File(dir, "fixed.kv")), root, ReqShield.classpath, ReqShield.kotlincArgs)
        assertNull(o.compileMessages)
        assertEquals(emptyList(), o.errors)
        return o
    }
}
