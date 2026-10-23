package kv.emit

import kv.kvir.Pos
import kv.sidecar.SPos
import kv.sidecar.Vexpr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The position table lookup of DESIGN-v0.md 5.7: the innermost range that contains the error start wins. */
class PositionsTest {
    private val method = Mapped(VprRange(10, 1, 20, Int.MAX_VALUE), Source.Generated("method m"))
    private val line = Mapped(VprRange(12, 3, 13, 40), Source.Sidecar(SPos("s.kv", 5, 3), "P1"))
    private val inner = Mapped(VprRange(12, 20, 12, 30), Source.Kotlin(Pos("K.kt", 7, 9)))
    private val emitted = Emitted("", listOf(method, line, inner), emptyList(), emptyList())

    @Test
    fun `the innermost range that contains the position wins, in any table order`() {
        assertEquals(inner, emitted.sourceAt(12, 25))
        assertEquals(inner, emitted.copy(ranges = listOf(inner, line, method)).sourceAt(12, 25))
        assertEquals(line, emitted.sourceAt(12, 3))
        assertEquals(line, emitted.sourceAt(13, 40))
        assertEquals(method, emitted.sourceAt(13, 41))
        assertNull(emitted.sourceAt(21, 1))
    }

    @Test
    fun `of two ranges with the same start, the shorter one wins`() {
        val short = Mapped(VprRange(12, 3, 12, 9), Source.Generated("short"))
        assertEquals(short, emitted.copy(ranges = listOf(line, short)).sourceAt(12, 3))
        assertEquals(short, emitted.copy(ranges = listOf(short, line)).sourceAt(12, 3))
    }

    @Test
    fun `a column inside an emitted sidecar expression maps to the sidecar column, also on a continuation line`() {
        // Sidecar line 7 is `  ensures P: a &&`, line 8 is `      b == c`: the expression starts at 7:16.
        val e = Vexpr("a &&\n      b == c", SPos("s.kv", 7, 16))
        // Emitted as `  ensures a &&` (expression at Viper 30:11) and the continuation line unchanged.
        val range = VprRange(30, 11, 31, 12)
        assertEquals(SPos("s.kv", 7, 16), e.posInRange(range, 30, 11))
        assertEquals(SPos("s.kv", 7, 18), e.posInRange(range, 30, 13))
        assertEquals(SPos("s.kv", 8, 7), e.posInRange(range, 31, 7))
        assertEquals(SPos("s.kv", 8, 12), e.posInRange(range, 31, 12))
    }
}
