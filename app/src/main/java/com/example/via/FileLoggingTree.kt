package com.example.via

import android.content.Context
import android.util.Log
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileLoggingTree(private val context: Context) : Timber.Tree() {

    // Master interceptor.
    // Everytime we call Timber.xyz() it gets funneled through here.
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {

        // Format the time
        val timeFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val timestamp = timeFormat.format(Date())

        // Convert the "priority" Int (like Log.DEBUG or Log.ERROR) into a readable string
        val priorityString = when (priority) {
            Log.DEBUG -> "DEBUG"
            Log.ERROR -> "ERROR"
            Log.INFO -> "INFO"
            Log.WARN -> "WARN"
            else -> "LOG"
        }

        // Build the string we want to save to a file
        val finalLogLine = "[$timestamp] [$priorityString] $tag: $message\n"

        // Get the file we want to write to
        val logFile = File(context.filesDir, "app_logs.txt")

        // This line opens the stream, converts the string into bytes, appends it and finally closes.
        logFile.appendText(finalLogLine)
    }
}