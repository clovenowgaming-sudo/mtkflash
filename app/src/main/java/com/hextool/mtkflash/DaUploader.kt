package com.hextool.mtkflash

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * =========================================================================
 *  READ THIS BEFORE USE
 * =========================================================================
 * The opcodes below (SEND_DA, JUMP_DA, etc.) are legacy BROM opcodes that
 * are COMMONLY used across many MediaTek chipset generations, BUT:
 *   - Some chipsets/BROM variants use different commands/checksums.
 *   - The load address MUST match the DA file you're using (it's written
 *     in the DA's metadata, usually obtained by extracting it from
 *     SP Flash Tool / your device's official firmware).
 *   - A wrong address or wrong checksum can make the device fail to boot,
 *     or in the worst case, brick it.
 *
 * BEFORE using this on a real device:
 *   1. Open the official mtkclient source (github.com/bkerler/mtkclient),
 *      check the file `mtkclient/Library/Hardware/brom.py` (or the latest
 *      version), and match the opcodes & command order against this file.
 *   2. Match the load address from your own DA file (the xml/config that
 *      usually comes with it, or from whichever tool you used to extract
 *      that DA).
 *   3. Test on a device that isn't your primary device first, or one
 *      you're prepared to lose.
 * =========================================================================
 */
object DaUploader {
    private const val TAG = "DaUploader"

    // --- BROM opcodes (VERIFY before using on a real device) ---
    private const val CMD_SEND_DA: Byte = 0xD7.toByte()
    private const val CMD_JUMP_DA: Byte = 0xD5.toByte()
    private const val CMD_ACK: Byte = 0x5A
    private const val CMD_NACK: Byte = 0xA5.toByte()

    data class DaUploadConfig(
        val loadAddress: Long,   // SRAM load address for the DA, from your DA's metadata
        val signatureLength: Int = 0 // DA signature length, if any (often 0 for an unsigned legacy DA)
    )

    data class Result(val success: Boolean, val message: String)

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

    /**
     * Upload the DA into the device's SRAM over BROM, then jump to its
     * load address. Must be called AFTER [BromHandshake.run] succeeds,
     * using the same interface/endpoints.
     */
    fun uploadAndJump(
        connection: UsbDeviceConnection,
        epIn: UsbEndpoint,
        epOut: UsbEndpoint,
        daBytes: ByteArray,
        config: DaUploadConfig
    ): Result {
        // 1. Send the SEND_DA command
        if (connection.bulkTransfer(epOut, byteArrayOf(CMD_SEND_DA), 1, 2000) != 1) {
            return Result(false, "Failed to send the SEND_DA command")
        }
        if (readByte(connection, epIn) != CMD_SEND_DA) {
            return Result(false, "Device did not echo the SEND_DA command (check the opcode!)")
        }

        // 2. Send load address, length, and signature length
        if (!writeU32(connection, epOut, config.loadAddress)) {
            return Result(false, "Failed to send the load address")
        }
        if (!writeU32(connection, epOut, daBytes.size.toLong())) {
            return Result(false, "Failed to send the DA length")
        }
        if (!writeU32(connection, epOut, config.signatureLength.toLong())) {
            return Result(false, "Failed to send the signature length")
        }

        // 3. The device usually replies with a 2-byte status before accepting
        //    data -- check it carefully, and ADAPT this to the actual BROM
        //    behavior of your target (see the warning above).
        val status1 = readByte(connection, epIn)
        val status2 = readByte(connection, epIn)
        Log.d(TAG, "Status before sending DA data: $status1 $status2")

        // 4. Send the DA data in chunks
        val chunkSize = 1024
        var offset = 0
        var checksum = 0
        while (offset < daBytes.size) {
            val len = minOf(chunkSize, daBytes.size - offset)
            val sent = connection.bulkTransfer(epOut, daBytes, offset, len, 3000)
            if (sent != len) {
                return Result(false, "Failed to send DA data at offset $offset")
            }
            for (i in offset until offset + len) {
                checksum = checksum xor (daBytes[i].toInt() and 0xFF)
            }
            offset += len
        }

        // 5. Send the checksum (checksum format MUST match the target -- this is
        //    a simple 16-bit XOR example)
        writeU32(connection, epOut, checksum.toLong())

        val ackAfterSend = readByte(connection, epIn)
        if (ackAfterSend != CMD_ACK) {
            return Result(false, "DA did not ACK after the transfer finished (checksum/opcode is likely wrong for this target)")
        }

        // 6. JUMP_DA
        if (connection.bulkTransfer(epOut, byteArrayOf(CMD_JUMP_DA), 1, 2000) != 1) {
            return Result(false, "Failed to send the JUMP_DA command")
        }
        if (!writeU32(connection, epOut, config.loadAddress)) {
            return Result(false, "Failed to send the JUMP_DA target address")
        }
        val jumpAck = readByte(connection, epIn)
        if (jumpAck != CMD_ACK) {
            return Result(false, "Device did not ACK JUMP_DA -- the DA may not have started")
        }

        return Result(true, "DA uploaded and started successfully at 0x${config.loadAddress.toString(16)}")
    }
}
