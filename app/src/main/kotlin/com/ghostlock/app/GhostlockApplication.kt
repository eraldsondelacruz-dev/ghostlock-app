package com.ghostlock.app

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.ghostlock.app.data.AndroidGhostlockRepository
import com.ghostlock.app.domain.repository.GhostlockRepository
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** DIAGNOSTIC BUILD: writes crash info to Download/ghostlock-crash.txt */
class GhostlockApplication : Application() {
    fun createRepository(): GhostlockRepository {
        CrashDiag.mark("createRepository.begin")
        val repository = AndroidGhostlockRepository(this)
        CrashDiag.mark("createRepository.done")
        return repository
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        CrashDiag.install(this)
        CrashDiag.mark("attachBaseContext done")
    }

    override fun onCreate() {
        super.onCreate()
        CrashDiag.mark("Application.onCreate done")
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                CrashDiag.mark("Activity.created ${activity.javaClass.name}")
            }

            override fun onActivityStarted(activity: Activity) {
                CrashDiag.mark("Activity.started ${activity.javaClass.name}")
            }

            override fun onActivityResumed(activity: Activity) {
                CrashDiag.mark("Activity.resumed ${activity.javaClass.name}")
            }

            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

object CrashDiag {
    private const val TAG = "GhostLockDiag"
    private const val FILE_NAME = "ghostlock-crash.txt"

    private var app: Application? = null
    private val marks = StringBuilder()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun install(application: Application) {
        app = application
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                PrintWriter(sw).use { pw ->
                    pw.println("time=" + fmt.format(Date()))
                    pw.println("thread=" + thread.name)
                    pw.println("model=" + Build.MANUFACTURER + " " + Build.MODEL)
                    pw.println("android=" + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")")
                    pw.println("kernel=" + System.getProperty("os.version", "?"))
                    pw.println("appVersion=" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                    pw.println()
                    pw.println("---- startup marks ----")
                    pw.println(marks.toString())
                    pw.println("---- exception ----")
                    error.printStackTrace(pw)
                }
                dump(sw.toString())
            }
            previous?.uncaughtException(thread, error)
        }
    }

    @Synchronized
    fun mark(text: String) {
        marks.append(fmt.format(Date())).append(' ').append(text).append('\n')
        Log.i(TAG, text)
    }

    private fun dump(text: String) {
        val context = app ?: return
        runCatching { File(context.filesDir, FILE_NAME).writeText(text) }
        runCatching {
            val resolver = context.contentResolver
            runCatching {
                resolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    arrayOf(FILE_NAME),
                )
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }
        runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("ghostlock-crash", text))
        }
    }
}
