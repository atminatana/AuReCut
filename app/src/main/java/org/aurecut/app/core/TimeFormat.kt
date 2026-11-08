package org.aurecut.app.core

import java.util.Locale
import kotlin.math.max
import kotlin.math.pow

object TimeFormat {
    /** "m:ss.mmm", or "h:mm:ss.mmm" from one hour on. [decimals] is 0..3. Always ASCII digits and a dot. */
    fun format(seconds: Double, decimals: Int = 3): String {
        val d = decimals.coerceIn(0, 3)
        val perSec = 10.0.pow(d).toLong()
        val units = Math.round(max(0.0, seconds) * perSec)
        val totalSec = units / perSec
        val frac = units % perSec
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val fracText = if (d > 0) "." + frac.toString().padStart(d, '0') else ""
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d%s", h, m, s, fracText)
        else String.format(Locale.ROOT, "%d:%02d%s", m, s, fracText)
    }

    /**
     * Accepts "83.5", "1:23.5" and "1:02:03.5"; a comma works as the decimal separator.
     * Returns null for anything else.
     */
    fun parse(text: String): Double? {
        val parts = text.trim().replace(',', '.').split(':')
        if (parts.isEmpty() || parts.size > 3) return null
        var total = 0.0
        for ((i, p) in parts.withIndex()) {
            if (p.isBlank()) return null
            val isLast = i == parts.lastIndex
            if (!isLast && !p.all { it.isDigit() }) return null
            if (isLast && !p.all { it.isDigit() || it == '.' }) return null
            val v = p.toDoubleOrNull() ?: return null
            if (!v.isFinite() || v < 0) return null
            total = total * 60 + v
        }
        return total
    }
}
