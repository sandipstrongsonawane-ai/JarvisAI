package com.jarvis.assistant.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log

/**
 * Feature 5 — proactive battery + calendar alerts.
 * Runs while the foreground service is alive; speaks via [onAlert].
 */
class ProactiveAlertManager(
    private val context: Context,
    private val onAlert: (String) -> Unit
) {
    private val settings = SettingsManager(context)
    private val calendar = CalendarHelper(context)
    private var batteryReceiver: BroadcastReceiver? = null
    private var lastBatteryAlertLevel = -1
    private val alertedEventKeys = mutableSetOf<String>()
    private val taskAlertPrefs = context.getSharedPreferences("jarvis_task_alerts", Context.MODE_PRIVATE)
    private val alertedTaskKeys = taskAlertPrefs.getStringSet("alerted_task_keys", emptySet())
        ?.toMutableSet() ?: mutableSetOf()

    fun start() {
        if (!settings.getProactiveAlertsEnabled()) {
            Log.i(TAG, "Proactive alerts disabled")
            return
        }
        registerBatteryReceiver()
        // Immediate calendar scan
        checkCalendar()
        checkTasks()
        Log.i(TAG, "Proactive alerts armed")
    }

    fun stop() {
        try {
            batteryReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) { }
        batteryReceiver = null
    }

    /** Call every ~5 minutes from the service tick. */
    fun tick() {
        try {
            if (com.jarvis.assistant.util.EveningDebrief(context).shouldSuppressProactive()) return
        } catch (_: Exception) {}

        if (!settings.getProactiveAlertsEnabled()) return
        checkBatteryLevel()
        checkCalendar()
        checkTasks()
    }

    /** Speak once when a pending task is due within 30 minutes or already overdue. */
    private fun checkTasks() {
        val now = System.currentTimeMillis()
        val dueTasks = TaskDueAlertPolicy.dueForAlert(
            TaskListManager(context).pending(), now, TASK_ALERT_WINDOW_MS
        )
        for (task in dueTasks) {
            val dueAt = task.dueAt ?: continue
            val key = "${task.id}:$dueAt"
            if (!alertedTaskKeys.add(key)) continue
            // Persist before speaking so service restarts do not repeat the same alert.
            if (alertedTaskKeys.size > MAX_TASK_ALERT_KEYS) {
                alertedTaskKeys.clear()
                alertedTaskKeys.add(key)
            }
            taskAlertPrefs.edit().putStringSet("alerted_task_keys", alertedTaskKeys.toSet()).apply()
            val overdue = dueAt < now
            val urgency = when (task.priority) {
                "high" -> "High-priority task. "
                "low" -> "Low-priority task. "
                else -> ""
            }
            val timing = if (overdue) "is overdue" else "is due soon"
            val message = "Sir, $urgency${task.title} $timing."
            Log.i(TAG, "Task alert: $message")
            onAlert(message)
        }
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiver != null) return
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_BATTERY_LOW -> {
                        speakBattery(getBatteryPct(), forced = true)
                    }
                    Intent.ACTION_BATTERY_CHANGED, Intent.ACTION_POWER_CONNECTED,
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        checkBatteryLevel()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(batteryReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerReceiver failed", e)
        }
    }

    private fun getBatteryPct(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun checkBatteryLevel() {
        val level = getBatteryPct()
        val threshold = settings.getBatteryAlertThreshold()
        if (isCharging()) {
            // Reset so next discharge can alert again
            if (level > threshold) lastBatteryAlertLevel = -1
            return
        }
        if (level in 1..threshold) {
            // Alert once per "band" (threshold, and again at half if still dropping)
            val band = when {
                level <= threshold / 2 -> 1
                else -> 2
            }
            if (lastBatteryAlertLevel != band) {
                lastBatteryAlertLevel = band
                speakBattery(level, forced = false)
            }
        } else if (level > threshold) {
            lastBatteryAlertLevel = -1
        }
    }

    private fun speakBattery(level: Int, forced: Boolean) {
        if (level <= 0) return
        val msg = when {
            level <= 10 -> "Sir, battery is critical at $level percent. Please connect a charger."
            level <= 20 -> "Sir, battery is low at $level percent."
            else -> "Sir, battery is at $level percent."
        }
        Log.i(TAG, "Battery alert: $msg forced=$forced")
        onAlert(msg)
    }

    private fun checkCalendar() {
        if (!calendar.hasPermission()) return
        val now = System.currentTimeMillis()
        val windowMs = settings.getCalendarAlertMinutes() * 60_000L
        val events = calendar.nextEvents(5)
        for (e in events) {
            val delta = e.startMs - now
            if (delta < 0 || delta > windowMs) continue
            val key = "${e.title}|${e.startMs}"
            if (key in alertedEventKeys) continue
            alertedEventKeys.add(key)
            // Cap memory
            if (alertedEventKeys.size > 40) {
                val drop = alertedEventKeys.take(15).toSet()
                alertedEventKeys.removeAll(drop)
            }
            val mins = (delta / 60_000L).toInt().coerceAtLeast(1)
            val loc = e.location?.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""
            val msg = "Sir, calendar reminder: ${e.title}$loc starts in about $mins minutes."
            Log.i(TAG, msg)
            onAlert(msg)
        }
    }

    companion object {
        private const val TAG = "ProactiveAlerts"
        private const val TASK_ALERT_WINDOW_MS = 30 * 60_000L
        private const val MAX_TASK_ALERT_KEYS = 200
    }
}
