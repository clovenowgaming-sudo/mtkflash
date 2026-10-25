package com.hextool.mtkflash

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * USB scanner that NEVER relies on the raw device path
 * (e.g. /dev/bus/usb/001/002), because that path changes every time
 * an MTK device reconnects or resets (the BROM -> Preloader -> DA
 * handoff can make the device "disappear and reappear" several times
 * with a new bus/address).
 *
 * Identity is based on VENDOR ID (+ optional PRODUCT ID) instead, and
 * we always re-query getDeviceList() on every event, plus a fallback
 * poll in case a broadcast gets missed.
 */
class UsbDeviceScanner(
    private val context: Context,
    private val onDeviceFound: (UsbDevice) -> Unit,
    private val onDeviceLost: () -> Unit,
    private val onPermissionGranted: (UsbDevice) -> Unit
) {
    companion object {
        private const val TAG = "UsbDeviceScanner"
        const val ACTION_USB_PERMISSION = "com.hextool.mtkflash.USB_PERMISSION"

        // MediaTek's official Vendor ID
        const val MTK_VID = 0x0E8D

        // PIDs commonly used by MTK devices in various modes (BROM, Preloader, etc).
        // This list is intentionally loose -- if a device has a PID outside this
        // list but its VID is still 0x0E8D, it will still be detected because the
        // primary check is VID-only.
        val KNOWN_MTK_PIDS = setOf(
            0x0003, // BROM
            0x2000, // Preloader (common)
            0x2001,
            0x2006,
            0x201D,
            0x2022
        )

        private const val POLL_INTERVAL_MS = 600L
    }

    private val usbManager: UsbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    private var lastSeenDeviceKey: String? = null // key = "vid:pid", never a path
    private var pollHandler: Handler? = null
    private var pollRunnable: Runnable? = null
    private var registered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(TAG, "Broadcast: USB device attached")
                    scanNow()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.d(TAG, "Broadcast: USB device detached")
                    scanNow()
                }
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? =
                            if (Build.VERSION.SDK_INT >= 33)
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                            else
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted && device != null) {
                            Log.d(TAG, "Permission granted for ${device.deviceName}")
                            onPermissionGranted(device)
                        } else {
                            Log.w(TAG, "Permission denied for device")
                        }
                    }
                }
            }
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(usbReceiver, filter)
        }
        registered = true

        // Fallback polling: on some ROMs / in some conditions (app in background
        // when the device first attaches) a broadcast can be missed. This poll
        // guarantees the device is still found even if its USB path changes on
        // every reconnect, because we always read a fresh device list from the
        // system instead of remembering an old path.
        pollHandler = Handler(Looper.getMainLooper())
        pollRunnable = object : Runnable {
            override fun run() {
                scanNow()
                pollHandler?.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
        pollHandler?.post(pollRunnable!!)
    }

    fun stop() {
        if (registered) {
            try { context.unregisterReceiver(usbReceiver) } catch (_: Exception) {}
            registered = false
        }
        pollRunnable?.let { pollHandler?.removeCallbacks(it) }
        pollHandler = null
        pollRunnable = null
    }

    /** Re-scan the device list right now, matching by VID (never by path). */
    fun scanNow() {
        val devices = usbManager.deviceList.values

        val match = devices.firstOrNull { it.vendorId == MTK_VID }

        if (match == null) {
            if (lastSeenDeviceKey != null) {
                Log.d(TAG, "MTK device disappeared from the list")
                lastSeenDeviceKey = null
                onDeviceLost()
            }
            return
        }

        val key = "${match.vendorId}:${match.productId}"
        val isNewSighting = key != lastSeenDeviceKey

        // The device is treated as "the same logical device" whenever the VID
        // matches, even if deviceName (the /dev/bus/usb/xxx/yyy path) changed
        // between scans. We deliberately never compare match.deviceName
        // against the previous path -- that's ignored on purpose because the
        // path is NOT stable.
        Log.d(TAG, "Found device VID=0x${match.vendorId.toString(16)} " +
                "PID=0x${match.productId.toString(16)} path=${match.deviceName} " +
                "(new=$isNewSighting)")

        lastSeenDeviceKey = key
        onDeviceFound(match)

        if (!usbManager.hasPermission(match)) {
            requestPermission(match)
        } else {
            onPermissionGranted(match)
        }
    }

    private fun requestPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
        )
        usbManager.requestPermission(device, pi)
    }

    fun isKnownMtkPid(pid: Int): Boolean = KNOWN_MTK_PIDS.contains(pid)
}
