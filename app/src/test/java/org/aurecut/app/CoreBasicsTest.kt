package org.aurecut.app

import org.aurecut.app.core.TimeFormat
import org.aurecut.app.core.ViewWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreBasicsTest {
    @Test
    fun timeFormat() {
        assertEquals("1:23.500", TimeFormat.format(83.5))
        assertEquals("1:02:03.250", TimeFormat.format(3723.25))
        assertEquals("0:00", TimeFormat.format(0.0, 0))
        assertEquals("0:05.0", TimeFormat.format(4.96, 1))
        assertEquals("0:00.000", TimeFormat.format(-3.0))
    }

    @Test
    fun timeParse() {
        assertEquals(83.5, TimeFormat.parse("1:23.5")!!, 1e-9)
        assertEquals(83.5, TimeFormat.parse("83,5")!!, 1e-9)
        assertEquals(3723.25, TimeFormat.parse("1:02:03.25")!!, 1e-9)
        assertEquals(12.0, TimeFormat.parse(" 12 ")!!, 1e-9)
        assertNull(TimeFormat.parse(""))
        assertNull(TimeFormat.parse("abc"))
        assertNull(TimeFormat.parse("-1"))
        assertNull(TimeFormat.parse("1:2:3:4"))
        assertNull(TimeFormat.parse("1.2.3"))
        assertNull(TimeFormat.parse("1.5:20"))
        assertNull(TimeFormat.parse("NaN"))
    }

    @Test
    fun zoomKeepsAnchorUnderFinger() {
        val total = 1_000_000L
        val v = ViewWindow.full(total)
        val anchor = 250_000.0
        val z = v.zoomed(4.0, anchor, total)
        assertEquals(250_000.0, z.span, 1e-6)
        // the anchor sits at the same relative position (25%) before and after
        assertEquals((anchor - v.start) / v.span, (anchor - z.start) / z.span, 1e-9)
    }

    @Test
    fun zoomIsClamped() {
        val total = 500_000L
        val tiny = ViewWindow.full(total).zoomed(1e9, 1000.0, total)
        assertEquals(ViewWindow.minSpan(total), tiny.span, 1e-9)
        val out = tiny.zoomed(1e-9, 1000.0, total)
        assertEquals(0.0, out.start, 1e-9)
        assertEquals(total.toDouble(), out.span, 1e-9)
    }

    @Test
    fun panStaysInsideTrack() {
        val total = 100_000L
        val v = ViewWindow(10_000.0, 20_000.0)
        assertEquals(0.0, v.panned(-1e9, total).start, 1e-9)
        assertEquals(total - 20_000.0, v.panned(1e9, total).start, 1e-9)
    }

    @Test
    fun shortTrackNeverBreaksTheWindow() {
        val w = ViewWindow.full(500).zoomed(10.0, 100.0, 500)
        assertTrue(w.span <= 500.0 && w.span >= 1.0)
        assertTrue(w.start >= 0.0 && w.end <= 500.0 + 1e-9)
    }

    @Test
    fun windowAroundSelection() {
        val total = 1_000_000L
        val w = ViewWindow.around(100_000, 200_000, total)
        assertTrue(w.start < 100_000 && w.end > 200_000)
        assertEquals(110_000.0, w.span, 1e-6)
        // selection at the very start is clamped, not shifted negative
        assertEquals(0.0, ViewWindow.around(0, 1000, total).start, 1e-9)
    }

    @Test
    fun coordinatesRoundTrip() {
        val v = ViewWindow(1000.0, 4000.0)
        val x = v.toX(3000.0, 800f)
        assertEquals(3000.0, v.toFrame(x, 800f), 1e-3)
    }
}
