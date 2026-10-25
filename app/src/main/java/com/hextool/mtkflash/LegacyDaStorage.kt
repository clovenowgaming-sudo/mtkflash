package com.hextool.mtkflash

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint

/**
 * Storage read/write protocol spoken to the LEGACY DA (after it has been
 * uploaded and jumped to via [DaUploader] -- NOT the BROM protocol
 * anymore). Ported from mtkclient's `dalegacy_lib.py`
 * (`sdmmc_switch_part`, `sdmmc_write_data`, `readflash`) and the opcode
 * table in `dalegacy_param.py`.
 *
 * This targets eMMC devices specifically (the common case for phones).
 * UFS-based devices use a different command set (UFS_* commands) that
 * isn't implemented here.
 *
 * Note: "newer" DAs (DA v6 / xflash, used by many recent chipsets) speak
 * a completely different XML-based protocol instead of this legacy one.
 * If your DA is xflash-based, this class will not work -- that protocol
 * isn't implemented in this scaffold.
 */
object LegacyDaStorage {

    // --- opcodes, verified against mtkclient's dalegacy_param.py ---
    private const val CMD_SDMMC_SWITCH_PART: Byte = 0x60
    private const val CMD_SDMMC_WRITE_DATA: Byte = 0x62
    private const val CMD_READ: Byte = 0xD6.toByte()

    private const val RSP_ACK: Byte = 0x5A
    private const val RSP_CONT: Byte = 0x69

    private const val STORAGE_EMMC: Byte = 0x1
    const val EMMC_PART_USER: Byte = 0x08 // the area that holds the GPT and Android partitions

    private const val SECTOR_SIZE = 512
    private const val PACKET_SIZE = 0x100000 // 1 MiB, matches mtkclient's chunking

    data class Result(val success: Boolean, val message: String, val bytesTransferred: Long = 0)

    private fun writeByte(c: UsbDeviceConnection, out: UsbEndpoint, b: Byte): Boolean =
        c.bulkTransfer(out, byteArrayOf(b), 1, 3000) == 1

    private fun readByte(c: UsbDeviceConnection, inEp: UsbEndpoint): Byte? {
        val buf = ByteArray(1)
        return if (c.bulkTransfer(inEp, buf, 1, 3000) == 1) buf[0] else null
    }

    private fun writeU64BE(c: UsbDeviceConnection, out: UsbEndpoint, value: Long): Boolean {
        val b = ByteArray(8)
        for (i in 0 until 8) b[7 - i] = ((value shr (i * 8)) and 0xFF).toByte()
        return c.bulkTransfer(out, b, 8, 3000) == 8
    }

    private fun writeU32BE(c: UsbDeviceConnection, out: UsbEndpoint, value: Long): Boolean {
        val b = byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
        return c.bulkTransfer(out, b, 4, 3000) == 4
    }

    private fun readU16BE(c: UsbDeviceConnection, inEp: UsbEndpoint): Int? {
        val buf = ByteArray(2)
        if (c.bulkTransfer(inEp, buf, 2, 3000) != 2) return null
        return ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
    }

    /** Switch the active eMMC partition/area. Must be called before read/write. */
    fun switchPartition(c: UsbDeviceConnection, inEp: UsbEndpoint, out: UsbEndpoint, part: Byte = EMMC_PART_USER): Boolean {
        if (!writeByte(c, out, CMD_SDMMC_SWITCH_PART)) return false
        if (readByte(c, inEp) != RSP_ACK) return false
        if (!writeByte(c, out, part)) return false
        return readByte(c, inEp) == RSP_ACK
    }

    /**
     * Read [length] raw bytes starting at byte offset [addr] inside the
     * user data area (addr = startLba * 512 for a GPT-listed partition).
     * Used both for dumping a partition and for reading the GPT itself.
     */
    fun readData(
        c: UsbDeviceConnection,
        inEp: UsbEndpoint,
        out: UsbEndpoint,
        addr: Long,
        length: Long
    ): ByteArray? {
        if (!switchPartition(c, inEp, out)) return null

        if (!writeByte(c, out, CMD_READ)) return null
        if (!writeByte(c, out, 0x0C)) return null // host OS id: Linux (matches mtkclient default)
        if (!writeByte(c, out, 0x02)) return null // storage type: EMMC
        if (!writeU64BE(c, out, addr)) return null
        if (!writeU64BE(c, out, length)) return null
        if (!writeU32BE(c, out, PACKET_SIZE.toLong())) return null

        if (readByte(c, inEp) != RSP_ACK) return null

        val result = ByteArray(length.toInt())
        var offset = 0L
        val chunk = ByteArray(PACKET_SIZE)
        while (offset < length) {
            val want = minOf(PACKET_SIZE.toLong(), length - offset).toInt()
            var got = 0
            while (got < want) {
                val n = c.bulkTransfer(inEp, chunk, got, want - got, 8000)
                if (n <= 0) return null
                got += n
            }
            System.arraycopy(chunk, 0, result, offset.toInt(), want)
            readU16BE(c, inEp) ?: return null // per-chunk checksum (not independently verified here)
            if (!writeByte(c, out, RSP_ACK)) return null
            offset += want
        }
        return result
    }

    /**
     * Write [imageBytes] to byte offset [addr] inside the user data area.
     * [addr] and the partition's capacity come from [GptParser] so the
     * target offset matches the real partition layout.
     */
    fun writePartitionImage(
        c: UsbDeviceConnection,
        inEp: UsbEndpoint,
        out: UsbEndpoint,
        partition: GptParser.PartitionEntry,
        imageBytes: ByteArray,
        onProgress: (written: Long, total: Long) -> Unit
    ): Result {
        if (imageBytes.size.toLong() > partition.sizeBytes) {
            return Result(
                false,
                "Image size (${imageBytes.size} bytes) exceeds partition '${partition.name}' " +
                    "capacity (${partition.sizeBytes} bytes) -- ABORTED for safety."
            )
        }

        if (!switchPartition(c, inEp, out)) {
            return Result(false, "Failed to switch to the EMMC user data area")
        }

        val addr = partition.startLba * 512
        var padded = imageBytes
        if (padded.size % SECTOR_SIZE != 0) {
            val pad = SECTOR_SIZE - (padded.size % SECTOR_SIZE)
            padded = padded + ByteArray(pad)
        }
        val length = padded.size.toLong()

        if (!writeByte(c, out, CMD_SDMMC_WRITE_DATA)) return Result(false, "Failed to send SDMMC_WRITE_DATA_CMD")
        if (!writeByte(c, out, STORAGE_EMMC)) return Result(false, "Failed to send storage type")
        if (!writeByte(c, out, EMMC_PART_USER)) return Result(false, "Failed to send partition type")
        if (!writeU64BE(c, out, addr)) return Result(false, "Failed to send target address")
        if (!writeU64BE(c, out, length)) return Result(false, "Failed to send length")
        if (!writeU32BE(c, out, PACKET_SIZE.toLong())) return Result(false, "Failed to send packet size")

        if (readByte(c, inEp) != RSP_ACK) {
            return Result(false, "Device did not ACK the write header")
        }

        var offset = 0
        while (offset < padded.size) {
            if (!writeByte(c, out, RSP_ACK)) return Result(false, "Failed to send chunk-ready ACK", offset.toLong())

            val count = minOf(PACKET_SIZE, padded.size - offset)
            val sent = c.bulkTransfer(out, padded, offset, count, 8000)
            if (sent != count) return Result(false, "Failed to send data at offset $offset", offset.toLong())

            var checksum = 0
            for (i in offset until offset + count) checksum += (padded[i].toInt() and 0xFF)
            checksum = checksum and 0xFFFF
            val chkBuf = byteArrayOf(((checksum shr 8) and 0xFF).toByte(), (checksum and 0xFF).toByte())
            if (c.bulkTransfer(out, chkBuf, 2, 3000) != 2) return Result(false, "Failed to send chunk checksum", offset.toLong())

            if (readByte(c, inEp) != RSP_CONT) {
                return Result(false, "Device did not acknowledge chunk at offset $offset", offset.toLong())
            }

            offset += count
            onProgress(offset.toLong(), padded.size.toLong())
        }

        return Result(true, "Finished writing '${partition.name}' (${imageBytes.size} bytes, ${padded.size} with padding)", padded.size.toLong())
    }
}
