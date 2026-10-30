package kv.client

import kv.ReqShield
import kv.emit.spikeDir
import kv.frontend.Frontend
import kv.verify.ModuleVerdict
import kv.verify.render
import kv.verify.verifyClient
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DESIGN-E5 8 prediction P2: the coroutine ReqShield with spike/protocol2c/fixed-coroutine.patch is PROVEN under the
 * signed coroutine sidecar. It is also the check of DESIGN-E5 6.2 S3 deliberate break (1): a release inside
 * withContext(NonCancellable) emitted with cx = true may keep its obligation (cancelKeeps), so LD_noleak fails.
 *
 * TODO(DESIGN-E5 S7): disabled until the signed sidecar specs/reqshield/reqshield.coroutine.kv exists. The lowering of
 * withContext(NonCancellable) and launch (S4) and the patch in spike/protocol2c are in place.
 */
@Disabled("TODO DESIGN-E5 S7: needs the signed coroutine sidecar")
class CoroutineVerifyTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `the fixed coroutine copy is PROVEN`() {
        val root = Protocol2.fixedCopy(dir, "core-kotlin-coroutine", File(spikeDir, "protocol2c/fixed-coroutine.patch"))
        val o = Frontend.resolve(File(System.getProperty("kv.specDir"), "reqshield/reqshield.coroutine.kv"), root, ReqShield.classpath, ReqShield.kotlincArgs)
        assertNull(o.compileMessages)
        assertEquals(emptyList(), o.errors)
        val report = verifyClient(o.module!!, o.sidecar!!, File(dir, "fixed.vpr"))
        assertEquals(ModuleVerdict.PROVEN, report.verdict, report.render())
    }
}
