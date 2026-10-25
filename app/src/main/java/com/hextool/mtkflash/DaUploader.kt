package com.hextool.mtkflash

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log

/**
 * BROM-level "send_da" / "jump_da" protocol.
 *
 * Unlike the earlier version of this file, these opcodes and the exact
 * byte sequence below are NOT guesses -- they are ported directly from
 * mtkclient's `mtk_preloader.py` (`send_da`, `jump_da`, `upload_data`,
 * `prepare_data`) and `Port.py` (`echo`). This part of the protocol is
 * shared across virtually all MediaTek chipsets (it lives in the common
 * Preloader/BROM code, not in chip-specific DA payloads), so it should
 * work as-is once you have a correct DA file and load address for your
 * chipset.
 */
object DaUploader {
    private const val TAG = "DaUploader"

    private const val CMD_SEND_DA: Byte = 0xD7.toByte()
    private const val CMD_JUMP_DA: Byte = 0xD5.toByte()

    data class DaUploadConfig(
        val loadAddress: Long,       // SRAM load address for the DA, from your DA's metadata
        val signatureLength: Int = 0 // trailing signature length inside the DA file (0 for most unsigned legacy DAs)
    )

    data class Result(val success: Boolean, val message: String)

    // ---- low level helpers mirroring Port.py's echo()/rword()/rdword() ----

    /** Sends [value] and expects the device to echo back the exact same bytes. */
    private fun echoBytes(connection: UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, value: ByteArray): Boolean {
        if (connection.bulkTransfer(epOut, value, value.size, 3000) != value.size) return false
        val back = ByteArray(value.size)
        val n = connection.bulkTransfer(epIn, back, value.size, 3000)
        return n == value.size && back.contentEquals(value)
    }

    private fun echoU32(connection: UsbDeviceConnection, epOut: UsbEndpoint, epIn: UsbEndpoint, value: Long): Boolean {
        val b = byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
        return echoBytes(connection, epOut, epIn, b)
    }

    /** Reads a 2-byte big-endian status word (Port.py's rword()). */
    private fun readWord(connection: UsbDeviceConnection, epIn: UsbEndpoint): Int? {
        val buf = ByteArray(2)
        val n = connection.bulkTransfer(epIn, buf, 2, 3000)
        if (n != 2) return null
        return ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
    }

    /** Reads a 4-byte big-endian word (Port.py's rdword()), used to read back the jump address. */
    private fun readDword(connection: UsbDeviceConnection, epIn: UsbEndpoint): Long? {
        val buf = ByteArray(4)
        val n = connection.bulkTransfer(epIn, buf, 4, 3000)
        if (n != 4) return null
        return ((buf[0].toLong() and 0xFF) shl 24) or ((buf[1].toLong() and 0xFF) shl 16) or
                ((buf[2].toLong() and 0xFF) shl 8) or (buf[3].toLong() and 0xFF)
    }

    /**
     * XOR-16 checksum exactly as mtkclient's `prepare_data` computes it:
     * XOR together all little-endian 16-bit words of the data, with the
     * final odd byte (if any) XORed in separately.
     */
    private fun xor16Checksum(data: ByteArray): Int {
        var chk = 0
        var i = 0
        while (i + 1 < data.size) {
            val word = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
            chk = chk xor word
            i += 2
        }
        if (data.size % 2 != 0) {
            chk = chk xor (data[data.size - 1].toInt() and 0xFF)
        }
        return chk and 0xFFFF
    }

    /**
     * Upload the DA into SRAM via BROM and jump to it. Call this AFTER
     * [BromHandshake.run] succeeds, on the same interface/endpoints.
     *
     * [daBytes] should be the exact DA file as extracted from your
     * firmware. If its last [config.signatureLength] bytes are a
     * signature block, they are sent separately as required by the
     * protocol; for an unsigned legacy DA this is usually 0.
     */
    fun uploadAndJump(
        connection: UsbDeviceConnection,
        epIn: UsbEndpoint,
        epOut: UsbEndpoint,
        daBytes: ByteArray,
        config: DaUploadConfig
    ): Result {
        val sigLen = config.signatureLength
        val payload = if (sigLen > 0 && sigLen < daBytes.size) daBytes.copyOfRange(0, daBytes.size - sigLen) else daBytes
        val sigData = if (sigLen > 0 && sigLen < daBytes.size) daBytes.copyOfRange(daBytes.size - sigLen, daBytes.size) else ByteArray(0)

        var data = payload + sigData
        if (data.size % 2 != 0) data += byteArrayOf(0)
        val genChecksum = xor16Checksum(data)

        if (!echoBytes(connection, epOut, epIn, byteArrayOf(CMD_SEND_DA))) {
            return Result(false, "SEND_DA command was not echoed back by the device")
        }
        if (!echoU32(connection, epOut, epIn, config.loadAddress)) {
            return Result(false, "Load address was not echoed back")
        }
        if (!echoU32(connection, epOut, epIn, data.size.toLong())) {
            return Result(false, "Data length was not echoed back")
        }
        if (!echoU32(connection, epOut, epIn, sigLen.toLong())) {
            return Result(false, "Signature length was not echoed back")
        }

        val status = readWord(connection, epIn) ?: return Result(false, "No status word after SEND_DA header")
        if (status == 0x1D0D) {
            return Result(false, "Device is asking for SLA authentication (signed-DA challenge) -- not supported by this tool")
        }
        if (status !in 0..0xFF) {
            return Result(false, "SEND_DA rejected, status=0x${status.toString(16)}")
        }

        // Upload data in 512-byte chunks (safe default close to typical bulk wMaxPacketSize).
        val chunkSize = 512
        var offset = 0
        while (offset < data.size) {
            val len = minOf(chunkSize, data.size - offset)
            val sent = connection.bulkTransfer(epOut, data, offset, len, 5000)
            if (sent != len) return Result(false, "Failed to send DA data at offset $offset")
            offset += len
        }
        // Zero-length packet to signal end of transfer (as in upload_data()).
        connection.bulkTransfer(epOut, ByteArray(0), 0, 2000)

        val checksumWord = readWord(connection, epIn)
        val statusWord = readWord(connection, epIn)
        if (checksumWord == null || statusWord == null) {
            return Result(false, "No reply from DA loader after upload")
        }
        if (checksumWord != genChecksum && checksumWord != 0) {
            Log.w(TAG, "Checksum mismatch: device=0x${checksumWord.toString(16)} expected=0x${genChecksum.toString(16)}")
        }
        if (statusWord !in 0..0xFF) {
            return Result(false, "Upload failed, status=0x${statusWord.toString(16)}")
        }

        // JUMP_DA
        if (!echoBytes(connection, epOut, epIn, byteArrayOf(CMD_JUMP_DA))) {
            return Result(false, "JUMP_DA command was not echoed back")
        }
        if (connection.bulkTransfer(epOut, byteArrayOf(
                ((config.loadAddress shr 24) and 0xFF).toByte(),
                ((config.loadAddress shr 16) and 0xFF).toByte(),
                ((config.loadAddress shr 8) and 0xFF).toByte(),
                (config.loadAddress and 0xFF).toByte()
            ), 4, 3000) != 4) {
            return Result(false, "Failed to send JUMP_DA address")
        }
        val echoedAddr = readDword(connection, epIn)
        if (echoedAddr != config.loadAddress) {
            return Result(false, "JUMP_DA address mismatch (device replied 0x${echoedAddr?.toString(16)})")
        }
        val jumpStatus = readWord(connection, epIn)
        if (jumpStatus != 0) {
            return Result(false, "JUMP_DA failed, status=0x${jumpStatus?.toString(16) ?: "none"}")
        }

        return Result(true, "DA uploaded and running at 0x${config.loadAddress.toString(16)}")
    }
}
