package com.hextool.mtkflash

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocol used to talk to the DA AFTER it's active (no longer talking
 * to BROM). Just like DaUploader: the opcodes below follow the common
 * pattern of the legacy DA protocol (often called "legacy DA" in
 * mtkclient, as opposed to the xflash/XML protocol used by newer DAs).
 * VERIFY against the mtkclient source (`dalegacy.py` / `xflash.py`
 * depending on your DA) before using this on a real device.
 *
 * General flow:
 *   1. Read the GPT first (readLba) to know the target partition's offset.
 *   2. Send a WRITE command with the start sector + sector count + size.
 *   3. Send the image data in chunks.
 *   4. The device replies with a success/failure status.
 */
object PartitionWriter {

    private const val CMD_READ10: Byte = 0xD1.toByte()   // VERIFY
    private const val CMD_WRITE10: Byte = 0xD4.toByte()  // VERIFY
    private const val SECTOR_SIZE = 512

    data class Result(val success: Boolean, val message: String, val bytesWritten: Long = 0)

    private fun writeU32(connection: UsbDeviceConnection, epOut: UsbEndpoint, value: Long): Boolean {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(value.toInt())
        return connection.bulkTransfer(epOut, bb.array(), 4, 2000) == 4
    }

    private fun readByte(connection: UsbDeviceConnection, epIn: UsbEndpoint): Byte? {
        val buf = ByteArray(1)
        val n = connection.bulkTransfer(epIn, buf, 1, 2000)
        return if (n == 1) buf[0] else null
    }

    /** Read a number of raw sectors -- used to read the GPT header & entries. */
    fun readLba(
        connection: UsbDeviceConnection,
        epIn: UsbEndpoint,
        epOut: UsbEndpoint,
        startLba: Long,
        sectorCount: Int
    ): ByteArray? {
        if (connection.bulkTransfer(epOut, byteArrayOf(CMD_READ10), 1, 2000) != 1) return null
        if (!writeU32(connection, epOut, startLba)) return null
        if (!writeU32(connection, epOut, sectorCount.toLong())) return null

        val total = sectorCount * SECTOR_SIZE
        val out = ByteArray(total)
        var offset = 0
        val chunk = ByteArray(4096)
        while (offset < total) {
            val want = minOf(chunk.size, total - offset)
            val n = connection.bulkTransfer(epIn, chunk, want, 3000)
            if (n <= 0) return null
            System.arraycopy(chunk, 0, out, offset, n)
            offset += n
        }
        return out
    }

    /**
     * Write an image (boot.img, init_boot.img, vendor_boot.img, etc.) to a
     * partition. [partition] comes from a [GptParser] result so that
     * startLba is accurate.
     */
    fun writePartition(
        connection: UsbDeviceConnection,
        epIn: UsbEndpoint,
        epOut: UsbEndpoint,
        partition: GptParser.PartitionEntry,
        imageBytes: ByteArray,
        onProgress: (written: Long, total: Long) -> Unit
    ): Result {
        if (imageBytes.size.toLong() > partition.sizeBytes) {
            return Result(
                false,
                "Image size (${imageBytes.size} bytes) is larger than partition " +
                    "'${partition.name}' (${partition.sizeBytes} bytes) -- ABORTED for safety."
            )
        }

        val sectorCount = (imageBytes.size + SECTOR_SIZE - 1) / SECTOR_SIZE

        if (connection.bulkTransfer(epOut, byteArrayOf(CMD_WRITE10), 1, 2000) != 1) {
            return Result(false, "Failed to send the WRITE10 command")
        }
        if (!writeU32(connection, epOut, partition.startLba)) {
            return Result(false, "Failed to send the start LBA")
        }
        if (!writeU32(connection, epOut, sectorCount.toLong())) {
            return Result(false, "Failed to send the sector count")
        }

        val ackBeforeData = readByte(connection, epIn)
        if (ackBeforeData == null) {
            return Result(false, "Device did not respond before sending data (check the WRITE10 opcode)")
        }

        var offset = 0
        val chunkSize = 4096
        while (offset < imageBytes.size) {
            val len = minOf(chunkSize, imageBytes.size - offset)
            val sent = connection.bulkTransfer(epOut, imageBytes, offset, len, 5000)
            if (sent != len) {
                return Result(false, "Failed to send data at offset $offset", offset.toLong())
            }
            offset += len
            onProgress(offset.toLong(), imageBytes.size.toLong())
        }

        val finalAck = readByte(connection, epIn)
        return if (finalAck != null) {
            Result(true, "Finished writing '${partition.name}' (${imageBytes.size} bytes)", imageBytes.size.toLong())
        } else {
            Result(false, "No final confirmation from the device after the transfer", imageBytes.size.toLong())
        }
    }
}
