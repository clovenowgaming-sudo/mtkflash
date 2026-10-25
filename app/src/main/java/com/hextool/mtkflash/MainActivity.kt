package com.hextool.mtkflash

import android.app.AlertDialog
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var scanner: UsbDeviceScanner
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var partitionSpinner: Spinner
    private lateinit var daStatusText: TextView
    private lateinit var imageStatusText: TextView
    private lateinit var writeButton: Button

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var currentDevice: UsbDevice? = null
    private var currentConnection: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null

    private var daBytes: ByteArray? = null
    private var imageBytes: ByteArray? = null
    private var gptEntries: List<GptParser.PartitionEntry> = emptyList()
    private var selectedPartition: GptParser.PartitionEntry? = null

    // DA load address: CHANGE this to match your DA's metadata
    // (see the warning in DaUploader.kt)
    private var daLoadAddress: Long = 0x200000

    private val pickDaLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { loadDaFile(it) }
    }
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { loadImageFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        scanner = UsbDeviceScanner(
            context = this,
            onDeviceFound = ::handleDeviceFound,
            onDeviceLost = ::handleDeviceLost,
            onPermissionGranted = ::handlePermissionGranted
        )

        log("MTK Flash Tool ready. Waiting for a MediaTek device (VID 0x0E8D)...")
        log("1) Pick a DA file  2) Plug in the phone in BROM/Preloader mode  3) Read GPT  4) Pick image & partition  5) Write")
    }

    override fun onStart() { super.onStart(); scanner.start() }
    override fun onStop() { super.onStop(); scanner.stop() }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }

        val daBtn = Button(this).apply { text = "Select DA file (.bin)" }
        daStatusText = TextView(this).apply { text = "DA: not selected" }
        daBtn.setOnClickListener { pickDaLauncher.launch("*/*") }

        val readGptBtn = Button(this).apply { text = "Read GPT from device" }
        readGptBtn.setOnClickListener { readGpt() }

        partitionSpinner = Spinner(this)

        val imgBtn = Button(this).apply { text = "Select image (boot.img / init_boot.img / etc.)" }
        imageStatusText = TextView(this).apply { text = "Image: not selected" }
        imgBtn.setOnClickListener { pickImageLauncher.launch("*/*") }

        writeButton = Button(this).apply {
            text = "WRITE to selected partition"
            isEnabled = false
        }
        writeButton.setOnClickListener { confirmAndWrite() }

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        logView = TextView(this).apply { textSize = 11f; setPadding(16, 16, 16, 16) }
        scrollView.addView(logView)

        root.addView(daBtn)
        root.addView(daStatusText)
        root.addView(readGptBtn)
        root.addView(TextView(this).apply { text = "Target partition:" })
        root.addView(partitionSpinner)
        root.addView(imgBtn)
        root.addView(imageStatusText)
        root.addView(writeButton)
        root.addView(scrollView)
        return root
    }

    // ---------- USB lifecycle ----------

    private fun handleDeviceFound(device: UsbDevice) {
        currentDevice = device
        log("Found device VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)} path=${device.deviceName}")
    }

    private fun handleDeviceLost() {
        log("Device disconnected.")
        currentConnection?.close()
        currentConnection = null
        epIn = null; epOut = null
        gptEntries = emptyList()
        updatePartitionSpinner()
    }

    private fun handlePermissionGranted(device: UsbDevice) {
        log("USB permission granted. Running BROM handshake...")
        thread {
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            val connection = usbManager.openDevice(device) ?: run {
                runOnUiThread { log("Failed to open a connection to the device.") }
                return@thread
            }
            val result = BromHandshake.run(connection, device)
            runOnUiThread {
                log(if (result.success) "✅ ${result.message}" else "⚠️ ${result.message}")
            }
            if (result.success) {
                val found = BromHandshake.findInterfaceAndEndpoints(device)
                if (found != null) {
                    currentConnection = connection
                    epIn = found.second
                    epOut = found.third
                    tryUploadDaIfReady()
                }
            } else {
                connection.close()
            }
        }
    }

    // ---------- DA ----------

    private fun loadDaFile(uri: Uri) {
        thread {
            contentResolver.openInputStream(uri)?.use { daBytes = it.readBytes() }
            runOnUiThread {
                daStatusText.text = "DA: ${daBytes?.size ?: 0} bytes loaded"
                log("DA file loaded (${daBytes?.size ?: 0} bytes). Remember to set daLoadAddress to match this DA's metadata.")
                tryUploadDaIfReady()
            }
        }
    }

    private fun tryUploadDaIfReady() {
        val conn = currentConnection ?: return
        val inEp = epIn ?: return
        val outEp = epOut ?: return
        val da = daBytes ?: run { log("Device is ready, but no DA file has been selected yet."); return }

        log("Uploading DA to 0x${daLoadAddress.toString(16)}...")
        thread {
            val result = DaUploader.uploadAndJump(
                conn, inEp, outEp, da,
                DaUploader.DaUploadConfig(loadAddress = daLoadAddress)
            )
            runOnUiThread {
                log(if (result.success) "✅ ${result.message}" else "⚠️ ${result.message}")
            }
        }
    }

    // ---------- GPT ----------

    private fun readGpt() {
        val conn = currentConnection; val inEp = epIn; val outEp = epOut
        if (conn == null || inEp == null || outEp == null) {
            log("Not connected to the DA yet. Make sure the handshake + DA upload succeeded first."); return
        }
        log("Reading GPT header (LBA1)...")
        thread {
            val headerSector = PartitionWriter.readLba(conn, inEp, outEp, 1, 1)
            if (headerSector == null) {
                runOnUiThread { log("Failed to read the GPT header.") }
                return@thread
            }
            val header = GptParser.parseHeader(headerSector)
            if (header == null) {
                runOnUiThread { log("Sector LBA1 is not a valid GPT (the 'EFI PART' signature was not found).") }
                return@thread
            }
            val entrySectors = (header.numPartitionEntries * header.sizeOfPartitionEntry + 511) / 512
            val entriesBuf = PartitionWriter.readLba(conn, inEp, outEp, header.partitionEntryLba, entrySectors)
            if (entriesBuf == null) {
                runOnUiThread { log("Failed to read the partition entries.") }
                return@thread
            }
            val entries = GptParser.parseEntries(header, entriesBuf)
            runOnUiThread {
                gptEntries = entries
                log("GPT read successfully: ${entries.size} partitions found.")
                entries.forEach { log("  - ${it.name} (${it.sizeBytes / 1024} KB, LBA ${it.startLba}-${it.endLba})") }
                updatePartitionSpinner()
            }
        }
    }

    private fun updatePartitionSpinner() {
        val names = gptEntries.map { it.name }
        partitionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        partitionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedPartition = gptEntries.getOrNull(pos)
                refreshWriteButtonState()
            }
            override fun onNothingSelected(p: AdapterView<*>?) { selectedPartition = null }
        }
    }

    // ---------- Image ----------

    private fun loadImageFile(uri: Uri) {
        thread {
            contentResolver.openInputStream(uri)?.use { imageBytes = it.readBytes() }
            runOnUiThread {
                imageStatusText.text = "Image: ${imageBytes?.size ?: 0} bytes loaded"
                log("Image file loaded (${imageBytes?.size ?: 0} bytes).")
                refreshWriteButtonState()
            }
        }
    }

    private fun refreshWriteButtonState() {
        writeButton.isEnabled = imageBytes != null && selectedPartition != null && currentConnection != null
    }

    // ---------- Write ----------

    private fun confirmAndWrite() {
        val partition = selectedPartition ?: return
        val img = imageBytes ?: return

        AlertDialog.Builder(this)
            .setTitle("Confirm partition write")
            .setMessage(
                "About to write ${img.size} bytes to partition '${partition.name}' " +
                    "(capacity ${partition.sizeBytes} bytes).\n\n" +
                    "Make sure this is the correct partition. Writing to the wrong " +
                    "target partition can make the device unable to boot.\n\nContinue?"
            )
            .setPositiveButton("Yes, write") { _, _ -> doWrite(partition, img) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doWrite(partition: GptParser.PartitionEntry, img: ByteArray) {
        val conn = currentConnection; val inEp = epIn; val outEp = epOut
        if (conn == null || inEp == null || outEp == null) { log("Lost connection to the device."); return }

        writeButton.isEnabled = false
        log("Writing to '${partition.name}'...")
        thread {
            val result = PartitionWriter.writePartition(conn, inEp, outEp, partition, img) { written, total ->
                if (written % (512 * 1024) < 4096) {
                    runOnUiThread { log("  progress: $written / $total bytes") }
                }
            }
            runOnUiThread {
                log(if (result.success) "✅ ${result.message}" else "⚠️ ${result.message}")
                writeButton.isEnabled = true
            }
        }
    }

    private fun log(msg: String) {
        logView.append("[${timeFmt.format(Date())}] $msg\n")
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
