package com.vicuna.clicker.root.input

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 用 UsageStats 代替 AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED。
 * PACKAGE_USAGE_STATS 不是普通 runtime permission，因此由 ROOT 通过 appops 授权。
 */
object ForegroundAppMonitor {
    private const val TAG = "ForegroundAppMonitor"

    val packageNameLiveData = MutableLiveData<String>()

    @Volatile
    private var started = false
    private var appContext: Context? = null
    private var scheduler: ScheduledExecutorService? = null
    private var lastQueryTime = 0L
    private var lastPackage = ""

    @Synchronized
    fun start(context: Context) {
        if (started) return
        val ctx = context.applicationContext
        appContext = ctx

        // ROOT 模式下自动打开 Usage Access app-op。
        val grant = RootShell.exec(
            "appops set ${shellQuote(ctx.packageName)} GET_USAGE_STATS allow",
            5000
        )
        if (!grant.ok) {
            Log.w(TAG, "grant GET_USAGE_STATS failed: ${grant.output}")
        }

        lastQueryTime = System.currentTimeMillis() - 60L * 60L * 1000L
        scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "foreground-app-monitor").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleWithFixedDelay(
                { pollOnce() },
                0,
                500,
                TimeUnit.MILLISECONDS
            )
        }
        started = true
    }

    @Synchronized
    fun stop() {
        started = false
        scheduler?.shutdownNow()
        scheduler = null
        appContext = null
    }

    private fun pollOnce() {
        val context = appContext ?: return
        try {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val begin = (lastQueryTime - 1000L).coerceAtLeast(0L)
            val events = manager.queryEvents(begin, now)
            val event = UsageEvents.Event()
            var candidate: String? = null

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val isForeground = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                } else {
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                }
                if (isForeground && !event.packageName.isNullOrBlank()) {
                    candidate = event.packageName
                }
            }

            lastQueryTime = now
            if (!candidate.isNullOrBlank() && candidate != lastPackage) {
                lastPackage = candidate
                packageNameLiveData.postValue(candidate)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "poll foreground package failed", t)
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
