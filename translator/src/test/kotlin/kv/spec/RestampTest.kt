package kv.spec

import kv.cli.runCli
import kv.sidecar.SidecarLoader
import kv.sidecar.checkSidecar
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `kv spec restamp` (DESIGN-C.md 10.15): re-signs the notes of a copy whose edits are declared elsewhere, such as the
 * binding adaptations of scripts/aicode.py. It names each note it changed and leaves trusted declarations alone.
 */
class RestampTest {
    @TempDir
    lateinit var dir: File

    private val specDir = File(System.getProperty("kv.specDir"), "reqshield")

    private fun kv(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = runCli(args.toList(), PrintStream(out), PrintStream(err))
        return Triple(exit, out.toString(), err.toString())
    }

    /** A copy of the signed specs in [dir], with [old] replaced once by [new] in keylocallock.common.kv. */
    private fun copy(
        old: String,
        new: String,
    ): File {
        specDir.listFiles()!!.forEach { it.copyTo(File(dir, it.name)) }
        val common = File(dir, "keylocallock.common.kv")
        val text = common.readText()
        require(text.split(old).size == 2) { "'$old' must occur once" }
        common.writeText(text.replace(old, new))
        return common
    }

    @Test
    fun `a class adaptation re-signs the vocabulary note only, and the copy then passes the sidecar checks`() {
        val common = copy("class LockInfo = KeyLocalLock.LockInfo", "class LockInfo = LockInfo")
        val coroutine = File(dir, "keylocallock.coroutine.kv")
        assertEquals(listOf("stale-review"), checkSidecar(SidecarLoader.load(coroutine)).map { it.code })
        val aspect = File(dir, "aspect.common.kv")
        val aspectBefore = aspect.readText()
        val (exit, out, err) = kv("spec", "restamp", common.path, aspect.path)
        assertEquals(0, exit, err)
        val line = common.readLines().indexOfFirst { it.startsWith("review vocabulary") } + 2
        val now = common.readLines()[line - 1].substringAfter("sha256 \"").substringBefore("\"")
        assertEquals(
            listOf(
                "${common.path}:$line: restamped the vocabulary: sha256 \"291a2e826122f824\" -> \"$now\"",
                "kv spec restamp: ${common.path}: 1 note(s) restamped",
                "kv spec restamp: ${aspect.path}: 0 note(s) restamped",
            ),
            out.lines().filter { it.isNotEmpty() },
        )
        assertEquals(aspectBefore, aspect.readText())
        assertEquals(emptyList(), checkSidecar(SidecarLoader.load(coroutine)))
        // A second run changes nothing.
        val before = common.readText()
        assertEquals("kv spec restamp: ${common.path}: 0 note(s) restamped\n", kv("spec", "restamp", common.path).second)
        assertEquals(before, common.readText())
    }

    @Test
    fun `a note without a fingerprint gets one, and a trusted note is left alone`() {
        val common =
            copy(
                "reviewed \"ykhfree 2026-10-28: an entry's semaphore holds 0 or 1 permits\" sha256 \"e253d83565837981\"",
                "reviewed \"ykhfree 2026-10-28: an entry's semaphore holds 0 or 1 permits\"",
            )
        // The trusted contract of rejectsNewEntry is part of its fingerprint under review intent (T-A), but only
        // kv resolve can compute it, so restamp must not touch that note.
        val trusted = common.readText().replace("  ensures rej ==> limit != 0", "  ensures rej ==> limit != 1")
        common.writeText(trusted)
        val (exit, out, _) = kv("spec", "restamp", common.path)
        assertEquals(0, exit, out)
        assertTrue(out.lines().single { ": restamped " in it }.endsWith("restamped invariant P2: sha256 none -> \"e253d83565837981\""), out)
        assertEquals(trusted.replace("0 or 1 permits\"\n", "0 or 1 permits\" sha256 \"e253d83565837981\"\n"), common.readText())
    }

    @Test
    fun `a syntax error writes no file, exit 4, and a usage error is exit 2`() {
        val common = copy("class LockInfo = KeyLocalLock.LockInfo", "class LockInfo = LockInfo")
        val before = common.readText()
        val bad = File(dir, "bad.kv").apply { writeText("invariant I: true\n  source none reason\n") }
        assertEquals(4, kv("spec", "restamp", common.path, bad.path).first)
        assertEquals(before, common.readText())
        assertEquals(2, kv("spec", "restamp").first)
        assertEquals(2, kv("spec", "restamp", File(dir, "missing.kv").path).first)
    }
}
