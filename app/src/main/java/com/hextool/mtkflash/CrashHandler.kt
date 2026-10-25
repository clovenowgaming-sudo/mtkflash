package com.hextool.mtkflash

import android.content.Context
import android.content.Intent
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Catches any uncaught exception, saves the full stack trace to a file,
 * then restarts the app straight into CrashActivity so the user can read
 * (and copy) the error without needing adb/logcat.
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))

            val file = File(context.filesDir, "last_crash.txt")
            file.writeText(sw.toString())

            val intent = Intent(context, CrashActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // fall through to default handler if even crash-handling crashes
        }

        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(1)
    }
}
