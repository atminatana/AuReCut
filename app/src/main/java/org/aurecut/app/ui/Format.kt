package org.aurecut.app.ui

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** 44100 -> "44.1" (or "44,1" in locales that use a comma), 48000 -> "48". */
fun formatKhz(rate: Int): String =
    DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.getDefault())).format(rate / 1000.0)

/** A level or gain in dB with one decimal, e.g. "-1" or "+3.2". */
fun formatDb(db: Double, signed: Boolean = false): String {
    val pattern = if (signed) "+0.#;-0.#" else "0.#"
    return DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.getDefault())).format(db)
}

/** Parses "-1", "-1.5", "−1,5" (typographic minus) and the like. Returns null when it is not a number. */
fun parseDb(text: String): Double? =
    text.trim().replace('−', '-').replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
