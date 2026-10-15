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

    /**
     * When ownership can't be read (Android 10+ hides the clipboard from background apps), a wipe
     * this late may hit something the user copied since, so it is skipped.
     */
    const val BLIND_CLEAR_LIMIT_MS = 60_000L

    fun schedule(context: Context, marker: String, delayMs: Long) {
        val app = context.applicationContext
        val deadline = SystemClock.elapsedRealtime() + delayMs
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // One PendingIntent identity: a newer copy replaces the previous alarm.
        alarms.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pendingIntent(app, marker, deadline))
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        val intent = Intent(app, ClipboardClearReceiver::class.java).setAction(ACTION_CLEAR)
        val existing = PendingIntent.getBroadcast(
            app, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(existing)
        existing.cancel()
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
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val description = clipboard.primaryClipDescription
            val clear = if (description != null) {
                description.label?.toString() == marker
            } else {
                now - deadline <= BLIND_CLEAR_LIMIT_MS
            }
            if (clear) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
            clear
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
