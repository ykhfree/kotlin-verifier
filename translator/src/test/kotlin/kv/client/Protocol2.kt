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
 * with spike/protocol2/fixed-core.patch applied (req-shield itself is never edited). The copy reads the signed core
 * sidecar as is: its `hole loop` names the polling loop by its function, so the patch, which moves the loop, needs no
 * sidecar edit (DESIGN-E 12.5).
 */
object Protocol2 {
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
        val o = Frontend.resolve(File(System.getProperty("kv.specDir"), "reqshield/reqshield.core.kv"), root, ReqShield.classpath, ReqShield.kotlincArgs)
        assertNull(o.compileMessages)
        assertEquals(emptyList(), o.errors)
        return o
    }
}
