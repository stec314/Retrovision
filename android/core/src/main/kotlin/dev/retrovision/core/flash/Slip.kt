package dev.retrovision.core.flash

import java.io.ByteArrayOutputStream

/** SLIP framing used by the ESP ROM bootloader. */
object Slip {
    const val END = 0xC0
    const val ESC = 0xDB
    const val ESC_END = 0xDC
    const val ESC_ESC = 0xDD

    fun encode(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 16)
        out.write(END)
        for (b in payload) {
            when (b.toInt() and 0xFF) {
                END -> { out.write(ESC); out.write(ESC_END) }
                ESC -> { out.write(ESC); out.write(ESC_ESC) }
                else -> out.write(b.toInt())
            }
        }
        out.write(END)
        return out.toByteArray()
    }
}

/** Incremental SLIP decoder. Bytes outside a frame (boot-ROM banner text) are discarded. */
class SlipDecoder {
    private val buf = ByteArrayOutputStream()
    private var inFrame = false
    private var escaped = false

    /** Feeds bytes, returns every frame completed by them. */
    fun feed(data: ByteArray, len: Int = data.size): List<ByteArray> {
        var out: MutableList<ByteArray>? = null
        for (i in 0 until len) {
            val v = data[i].toInt() and 0xFF
            if (v == Slip.END) {
                if (inFrame && buf.size() > 0) {
                    (out ?: ArrayList<ByteArray>().also { out = it }) += buf.toByteArray()
                }
                buf.reset()
                inFrame = true
                escaped = false
                continue
            }
            if (!inFrame) continue
            if (escaped) {
                escaped = false
                buf.write(if (v == Slip.ESC_END) Slip.END else if (v == Slip.ESC_ESC) Slip.ESC else v)
            } else if (v == Slip.ESC) {
                escaped = true
            } else {
                buf.write(v)
            }
        }
        return out ?: emptyList()
    }
}
