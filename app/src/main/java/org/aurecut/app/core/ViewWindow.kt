package org.aurecut.app.core

import kotlin.math.max
import kotlin.math.min

/** The visible part of the track, in frames. Immutable; every operation returns a clamped copy. */
data class ViewWindow(val start: Double, val span: Double) {
    val end: Double get() = start + span

    fun toX(frame: Double, width: Float): Float = ((frame - start) / span * width).toFloat()

    fun toFrame(x: Float, width: Float): Double = start + x / width * span

    fun coerced(total: Long): ViewWindow {
        val t = max(total, 1L).toDouble()
        val s = span.coerceIn(min(minSpan(total), t), t)
        return ViewWindow(start.coerceIn(0.0, t - s), s)
    }

    /** [factor] > 1 zooms in. The frame under [anchor] stays at the same screen position. */
    fun zoomed(factor: Double, anchor: Double, total: Long): ViewWindow {
        val t = max(total, 1L).toDouble()
        val rel = (anchor - start) / span
        val ns = (span / factor).coerceIn(min(minSpan(total), t), t)
        return ViewWindow(anchor - rel * ns, ns).coerced(total)
    }

    fun panned(deltaFrames: Double, total: Long): ViewWindow = ViewWindow(start + deltaFrames, span).coerced(total)

    companion object {
        /** Zooming stops at about 2000 frames across the screen. */
        fun minSpan(total: Long): Double = min(max(total, 1L).toDouble(), 2000.0)

        fun full(total: Long): ViewWindow = ViewWindow(0.0, max(total, 1L).toDouble())

        /** A window showing [selStart, selEnd] with a 5% margin on both sides. */
        fun around(selStart: Long, selEnd: Long, total: Long): ViewWindow {
            val len = max(selEnd - selStart, 1L).toDouble()
            val margin = len * 0.05
            return ViewWindow(selStart - margin, len + 2 * margin).coerced(total)
        }
    }
}
