package com.janreins.vaultlock.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * Backs up the in-process clipboard timer with a system alarm, so a copied secret is still wiped
 * if Android kills the app in the background before the timer fires. The alarm carries only the
 * clip's random marker, never the copied text. Inexact alarms need no permission; they never fire
 * early but may run late while the device is idle.
 */
object ClipboardWipe {
    private const val ACTION_CLEAR = "com.janreins.vaultlock.action.CLEAR_CLIPBOARD"
    private const val EXTRA_MARKER = "marker"
    private const val EXTRA_DEADLINE = "deadline"
    private const val PREFS_NAME = "clipboard_wipe"

    internal data class PendingWipe(val marker: String, val deadline: Long)

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    internal fun pending(context: Context): PendingWipe? {
        val prefs = preferences(context)
        val marker = prefs.getString(EXTRA_MARKER, null) ?: return null
        return PendingWipe(marker, prefs.getLong(EXTRA_DEADLINE, 0L))
    }

    /**
     * When ownership can't be read (Android 10+ hides the clipboard from background apps), a wipe
     * this late may hit something the user copied since, so it waits for foreground recovery.
     */
    const val BLIND_CLEAR_LIMIT_MS = 60_000L

    fun schedule(context: Context, marker: String, delayMs: Long) {
        val app = context.applicationContext
        val deadline = SystemClock.elapsedRealtime() + delayMs
        // Replace the previous record atomically. Only ownership metadata is persisted, never text.
        preferences(app).edit().putString(EXTRA_MARKER, marker)
            .putLong(EXTRA_DEADLINE, deadline).apply()
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // One PendingIntent identity: a newer copy replaces the previous alarm.
        alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pendingIntent(app, marker, deadline))
    }

    /** A stale timer/receiver must never cancel a newer copy's cleanup. */
    internal fun complete(context: Context, record: PendingWipe) {
        if (pending(context) != record) return
        preferences(context).edit().remove(EXTRA_MARKER).remove(EXTRA_DEADLINE).apply()
        try {
            cancel(context)
        } catch (_: Exception) {
            // A leftover alarm is harmless: onAlarm requires a matching pending record.
        }
    }

    private fun cancel(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, ClipboardClearReceiver::class.java).setAction(ACTION_CLEAR)
        val existing = PendingIntent.getBroadcast(
            app, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(existing)
        existing.cancel()
    }

    /** Foreground reads must prove label ownership; never clear blind here. */
    fun recoverOnForeground(context: Context, now: Long = SystemClock.elapsedRealtime()) {
        val record = pending(context) ?: return
        if (now < record.deadline) return
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // Creation may precede window focus; null still cannot prove ownership either way.
            val description = clipboard.primaryClipDescription ?: return
            if (description.label?.toString() == record.marker) clear(clipboard)
            complete(context, record)
        } catch (_: Exception) {
            // Retain the record if clipboard access is denied; retry on the next foreground.
        }
    }

    private fun clear(clipboard: ClipboardManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            clipboard.clearPrimaryClip()
        } else {
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    private fun pendingIntent(context: Context, marker: String, deadline: Long): PendingIntent {
        val intent = Intent(context, ClipboardClearReceiver::class.java)
            .setAction(ACTION_CLEAR)
            .putExtra(EXTRA_MARKER, marker)
            .putExtra(EXTRA_DEADLINE, deadline)
        return PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Clears the clipboard if it still holds the copy named by [intent]. Returns whether it cleared. */
    fun onAlarm(context: Context, intent: Intent, now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (intent.action != ACTION_CLEAR) return false
        val marker = intent.getStringExtra(EXTRA_MARKER) ?: return false
        val deadline = intent.getLongExtra(EXTRA_DEADLINE, 0L)
        val record = pending(context) ?: return false
        if (record != PendingWipe(marker, deadline)) return false
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val description = clipboard.primaryClipDescription
            val shouldClear = if (description != null) {
                description.label?.toString() == marker
            } else {
                now - deadline <= BLIND_CLEAR_LIMIT_MS
            }
            if (shouldClear) clear(clipboard)
            // Hidden ownership on a late alarm keeps the record for a foreground retry.
            if (shouldClear || description != null) complete(context, record)
            shouldClear
        } catch (_: Exception) {
            false
        }
    }
}

/** Not exported: only VaultLock's own alarm can reach it. */
class ClipboardClearReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ClipboardWipe.onAlarm(context, intent)
    }
}
