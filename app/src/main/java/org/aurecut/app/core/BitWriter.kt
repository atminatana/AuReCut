package org.aurecut.app.core

/** MSB-first bit writer into a growable byte array. Used by the FLAC encoder. */
class BitWriter(initialSize: Int = 1 shl 16) {
    var buf = ByteArray(initialSize)
        private set

    /** Number of completed bytes. */
    var length = 0
        private set

    private var acc = 0L
    private var accBits = 0

    val isAligned: Boolean get() = accBits == 0

    fun reset() {
        length = 0
        acc = 0L
        accBits = 0
    }

    private fun put(b: Int) {
        if (length == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[length++] = b.toByte()
    }

    /** Writes the low [n] bits of [value] (0 <= n <= 32). */
    fun writeBits(value: Long, n: Int) {
        if (n == 0) return
        acc = (acc shl n) or (value and ((1L shl n) - 1))
        accBits += n
        while (accBits >= 8) {
            accBits -= 8
            put(((acc ushr accBits) and 0xFF).toInt())
        }
        acc = acc and ((1L shl accBits) - 1)
    }

    fun writeBits(value: Int, n: Int) = writeBits(value.toLong(), n)

    /** [q] zero bits followed by a single one bit. */
    fun writeUnary(q: Int) {
        var left = q
        while (left >= 32) {
            writeBits(0L, 32)
            left -= 32
        }
        writeBits(1L, left + 1)
    }

    fun alignToByte() {
        if (accBits > 0) writeBits(0L, 8 - accBits)
    }

    /** UTF-8 style variable-length number as used for FLAC frame numbers. */
    fun writeUtf8(v: Long) {
        if (v < 0x80) {
            writeBits(v, 8)
            return
        }
        val n = when {
            v < 0x800L -> 2
            v < 0x10000L -> 3
            v < 0x200000L -> 4
            v < 0x4000000L -> 5
            v < 0x80000000L -> 6
            else -> 7
        }
        val prefix = (0xFF00 shr n) and 0xFF // n leading one bits
        writeBits(prefix.toLong() or (v ushr (6 * (n - 1))), 8)
        for (i in n - 2 downTo 0) {
            writeBits(0x80L or ((v ushr (6 * i)) and 0x3F), 8)
        }
    }
}

object Crc {
    private val table8 = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
            t[i] = c
        }
    }
    private val table16 = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i shl 8
            repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
            t[i] = c
        }
    }

    /** CRC-8, polynomial 0x07, initial value 0. */
    fun crc8(data: ByteArray, from: Int, to: Int): Int {
        var c = 0
        for (i in from until to) c = table8[c xor (data[i].toInt() and 0xFF)]
        return c
    }

    /** CRC-16, polynomial 0x8005, initial value 0. */
    fun crc16(data: ByteArray, from: Int, to: Int): Int {
        var c = 0
        for (i in from until to) c = ((c shl 8) and 0xFFFF) xor table16[(c ushr 8) xor (data[i].toInt() and 0xFF)]
        return c
    }
}
