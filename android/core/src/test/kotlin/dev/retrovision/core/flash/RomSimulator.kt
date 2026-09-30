package dev.retrovision.core.flash

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Inflater

/** A just-enough model of the ESP ROM serial bootloader, for testing [EspFlasher]. */
class RomSimulator(
    val chipMagic: Int = 0x9,
    flashSize: Int = 8 shl 20,
    private val statusLen: Int = 4,
    private val banner: ByteArray = ByteArray(0),
) : SerialLink {
    val flash = ByteArray(flashSize) { 0xFF.toByte() }
    var now = 0L

    /** Fail the checksum of the n-th DEFL_DATA packet (counted from 0) once. */
    var rejectBlockOnce = -1

    /** Flip a bit in the stored data after this many bytes were written (-1 = never). */
    var corruptAt = -1

    var syncsToIgnore = 0
    var erased = false
    val commands = ArrayList<Int>()

    private val out = java.io.ByteArrayOutputStream()
    private val decoder = SlipDecoder()
    private var inflater: Inflater? = null
    private var writePos = 0
    private var dataPackets = 0
    private var rejected = false

    init { if (banner.isNotEmpty()) out.write(banner) }

    override fun write(data: ByteArray) {
        for (frame in decoder.feed(data)) handle(frame)
    }

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        if (out.size() == 0) {
            now += timeoutMs
            return 0
        }
        val all = out.toByteArray()
        val n = minOf(buf.size, all.size)
        System.arraycopy(all, 0, buf, 0, n)
        out.reset()
        out.write(all, n, all.size - n)
        return n
    }

    override fun setLines(dtr: Boolean, rts: Boolean) {}
    override fun discardInput() { out.reset() }

    private fun reply(cmd: Int, value: Int, payload: ByteArray = ByteArray(0), status: Int = 0, err: Int = 0) {
        val st = ByteArray(statusLen).also { it[0] = status.toByte(); it[1] = err.toByte() }
        val data = payload + st
        val bb = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(1).put(cmd.toByte()).putShort(data.size.toShort()).putInt(value).put(data)
        out.write(Slip.encode(bb.array()))
    }

    private fun handle(frame: ByteArray) {
        if (frame.size < 8 || frame[0].toInt() != 0) return
        val bb = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = frame[1].toInt() and 0xFF
        val size = bb.getShort(2).toInt() and 0xFFFF
        val chk = bb.getInt(4)
        val data = frame.copyOfRange(8, 8 + size)
        commands += cmd
        when (cmd) {
            EspProtocol.CMD_SYNC -> {
                if (syncsToIgnore > 0) { syncsToIgnore--; return }
                repeat(8) { reply(cmd, 0) }
            }
            EspProtocol.CMD_READ_REG -> reply(cmd, chipMagic)
            EspProtocol.CMD_SPI_ATTACH, EspProtocol.CMD_SPI_SET_PARAMS -> reply(cmd, 0)
            EspProtocol.CMD_FLASH_DEFL_BEGIN -> {
                val w = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val total = w.getInt(0)
                val offset = w.getInt(12)
                java.util.Arrays.fill(flash, offset, offset + total, 0xFF.toByte())
                erased = true
                inflater = Inflater()
                writePos = offset
                dataPackets = 0
                reply(cmd, 0)
            }
            EspProtocol.CMD_FLASH_DEFL_DATA -> {
                val n = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(0)
                val payload = data.copyOfRange(16, 16 + n)
                val idx = dataPackets++
                if (idx == rejectBlockOnce && !rejected) {
                    rejected = true
                    dataPackets--
                    reply(cmd, 0, status = 1, err = 7)
                    return
                }
                if (EspProtocol.checksum(payload) != (chk and 0xFF)) {
                    dataPackets--
                    reply(cmd, 0, status = 1, err = 7)
                    return
                }
                val inf = inflater!!
                inf.setInput(payload)
                val tmp = ByteArray(4096)
                while (!inf.needsInput() && !inf.finished()) {
                    val k = inf.inflate(tmp)
                    if (k == 0) break
                    System.arraycopy(tmp, 0, flash, writePos, k)
                    if (corruptAt in writePos until writePos + k) flash[corruptAt] = (flash[corruptAt].toInt() xor 1).toByte()
                    writePos += k
                }
                reply(cmd, 0)
            }
            EspProtocol.CMD_FLASH_DEFL_END -> reply(cmd, 0)
            EspProtocol.CMD_SPI_FLASH_MD5 -> {
                val w = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val off = w.getInt(0)
                val len = w.getInt(4)
                val md5 = MessageDigest.getInstance("MD5").digest(flash.copyOfRange(off, off + len))
                reply(cmd, 0, md5.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII))
            }
            else -> reply(cmd, 0, status = 1, err = 5)
        }
    }
}
