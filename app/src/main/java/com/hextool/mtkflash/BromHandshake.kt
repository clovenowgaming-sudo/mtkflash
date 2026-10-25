package com.hextool.mtkflash

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.util.Log

/**
 * Basic MediaTek BROM handshake: the classic 4-byte handshake used to
 * confirm the chip is in BROM mode and ready to accept further commands
 * (loading a DA, etc). This is NOT a full flashing flow -- it's just an
 * initial connection/handshake test. The full DA/flashing protocol is
 * much larger and device-specific (different chipsets use different DA
 * payloads & authentication).
 */
object BromHandshake {
    private const val TAG = "BromHandshake"

    // 4 byte pairs: send -> device replies with the bitwise NOT
    private val HANDSHAKE_SEND = byteArrayOf(0xA0.toByte(), 0x0A, 0x50, 0x05)
    private val HANDSHAKE_EXPECT = byteArrayOf(0x5F, 0xF5.toByte(), 0xAF.toByte(), 0xFA.toByte())

    data class Result(val success: Boolean, val message: String)

    fun findInterfaceAndEndpoints(device: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT) epOut = ep
                }
            }
            if (epIn != null && epOut != null) {
                return Triple(intf, epIn, epOut)
            }
        }
        return null
    }

    /** Run the handshake. Call this from a background thread, not the main thread. */
    fun run(connection: UsbDeviceConnection, device: UsbDevice): Result {
        val found = findInterfaceAndEndpoints(device)
            ?: return Result(false, "No bulk IN/OUT interface found on this device")

        val (intf, epIn, epOut) = found

        if (!connection.claimInterface(intf, true)) {
            return Result(false, "Failed to claim the USB interface")
        }

        try {
            for (i in HANDSHAKE_SEND.indices) {
                val toSend = byteArrayOf(HANDSHAKE_SEND[i])
                val sent = connection.bulkTransfer(epOut, toSend, toSend.size, 1000)
                if (sent < 0) {
                    return Result(false, "Failed to send handshake byte #${i + 1}")
                }

                val buf = ByteArray(1)
                val recv = connection.bulkTransfer(epIn, buf, buf.size, 1000)
                if (recv < 0) {
                    return Result(false, "No reply for handshake byte #${i + 1}")
                }

                if (buf[0] != HANDSHAKE_EXPECT[i]) {
                    Log.w(TAG, "Byte #${i + 1}: expected=${HANDSHAKE_EXPECT[i]} got=${buf[0]}")
                    return Result(
                        false,
                        "Reply mismatch at byte #${i + 1} " +
                            "(the device may not be in BROM mode, or it already switched modes)"
                    )
                }
            }
            return Result(true, "BROM handshake succeeded - the chip responded correctly to the protocol")
        } finally {
            connection.releaseInterface(intf)
        }
    }
}
