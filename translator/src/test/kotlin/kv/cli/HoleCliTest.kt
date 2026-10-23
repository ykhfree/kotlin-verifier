package kv.cli

import kv.ReqShield
import kv.verify.Silicon
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M7 acceptance (DESIGN-v0.md section 7, 8.3 "M7 edit surface") through `kv hole` and `kv verify`: (1) the skeleton
 * (hole `true`) fails verification; (2) the checker rejects an edit outside the hole or a forbidden word, with the
 * position; (3) the section 4.4 hole passes the checker and the module is then proven. The verify cases fail, not
 * skip, without Silicon.
 */
class HoleCliTest {
    @TempDir
    lateinit var dir: File

    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")
    private val common = File(specDir, "keylocallock.common.kv")

    private fun kv(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = runCli(args.toList(), PrintStream(out), PrintStream(err))
        return Triple(exit, out.toString(), err.toString())
    }

    /** A directory with the coroutine sidecar and [commonText] as the common file it includes. */
    private fun specs(
        name: String,
        commonText: String,
    ): File {
        val d = File(dir, name).apply { mkdirs() }
        File(specDir, "keylocallock.coroutine.kv").copyTo(File(d, "keylocallock.coroutine.kv"))
        File(d, "keylocallock.common.kv").writeText(commonText)
        return d
    }

    private fun verify(specs: File) =
        kv(
            "verify", "--root", ReqShield.dir.path, "--classpath", ReqShield.classpath.joinToString(File.pathSeparator),
            "--kotlinc", ReqShield.kotlincArgs.joinToString(" "), "--out", File(specs, "out.vpr").path, File(specs, "keylocallock.coroutine.kv").path,
        )

    /** Runs `kv hole skeleton` on the reviewed common file and returns the skeleton. */
    private fun skeleton(): File {
        val out = File(dir, "skeleton.kv")
        val (exit, _, err) = kv("hole", "skeleton", common.path, out.path)
        assertEquals(0, exit, err)
        return out
    }

    @Test
    fun `1 - the skeleton, with hole MI_INV true, fails verification`() {
        Silicon.check(Silicon.defaultHome())
        val (exit, out, err) = verify(specs("skeleton", skeleton().readText()))
        assertEquals(1, exit, out + err)
        assertTrue("module: FAILED" in out.lines().first(), out)
    }

    @Test
    fun `3 - the section 4_4 hole passes the checker, and kv verify proves the module`() {
        Silicon.check(Silicon.defaultHome())
        // The reviewed common file is the skeleton with the section 4.4 hole written in: the edit an LLM would make.
        val (check, checkOut, checkErr) = kv("hole", "check", skeleton().path, common.path)
        assertEquals(0, check, checkErr)
        assertTrue("hole check OK" in checkOut, checkOut)
        val (exit, out, err) = verify(specs("filled", common.readText()))
        assertEquals(0, exit, out + err)
        assertTrue("module: PROVEN" in out.lines().first(), out)
    }

    @Test
    fun `2 - an edit with inhale or assume in the hole, or outside the hole, is rejected with its position`() {
        val before = skeleton()
        val edits =
            mapOf(
                "inhale" to before.readText().replace("\n  true\n", "\n  true &&\n  (inhale MI_INV(S))\n"),
                "assume" to before.readText().replace("\n  true\n", "\n  true // all good\n  && assume(S)\n"),
                "outside" to before.readText().replace("ensures progress_free:", "ensures progress_free_x:"),
            )
        for ((what, text) in edits) {
            val after = File(dir, "$what.kv").apply { writeText(text) }
            val (exit, out, err) = kv("hole", "check", before.path, after.path)
            assertEquals(1, exit, "$what: $out$err")
            val lines = text.lines()
            val fragment = if (what == "outside") "_x:" else what
            val line = lines.indexOfFirst { fragment in it }
            val pos = "${after.path}:${line + 1}:${lines[line].indexOf(fragment) + 1}: error["
            assertTrue(pos in err && "REJECTED" in err, "$what: expected $pos in\n$err")
        }
    }

    @Test
    fun `hole needs a subcommand and two files`() {
        assertEquals(2, kv("hole", "skeleton", common.path).first)
        assertEquals(2, kv("hole", "fill", common.path, File(dir, "x.kv").path).first)
        assertEquals(2, kv("hole", "check", File(dir, "missing.kv").path, common.path).first)
    }
}
