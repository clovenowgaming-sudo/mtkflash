package com.hextool.mtkflash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class CrashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashText = try {
            File(filesDir, "last_crash.txt").readText()
        } catch (e: Exception) {
            "No crash log found."
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }

        val title = TextView(this).apply {
            text = "MTK Flash Tool crashed"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }

        val copyBtn = Button(this).apply { text = "Copy log" }
        copyBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("crash log", crashText))
            Toast.makeText(this@CrashActivity, "Copied", Toast.LENGTH_SHORT).show()
        }

        val closeBtn = Button(this).apply { text = "Close" }
        closeBtn.setOnClickListener { finishAffinity() }

        val logView = TextView(this).apply {
            text = crashText
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, 24, 0, 0)
        }
        val scroll = ScrollView(this).apply { addView(logView) }

        root.addView(title)
        root.addView(copyBtn)
        root.addView(closeBtn)
        root.addView(scroll)
        setContentView(root)
    }
}
