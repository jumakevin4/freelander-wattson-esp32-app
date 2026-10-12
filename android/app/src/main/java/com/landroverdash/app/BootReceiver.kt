package com.landroverdash.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Opens the dashboard when the head unit starts, so the car link is up before
 * anyone looks at the screen (Settings → "Open automatically when the car
 * starts").
 *
 * Android 10 only lets an app open a screen from the background if it holds
 * "Display over other apps" — Settings offers a button for it. Without that
 * grant, startActivity() here is silently ignored on stock Android; many
 * head-unit ROMs relax the rule, so it is always attempted.
 *
 * Head units that SLEEP instead of rebooting send no boot broadcast at all.
 * Back on the dashboard no longer closes the app, so in that case the app is
 * simply still running when the unit wakes.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in BOOT_ACTIONS) return
        if (!SettingsActivity.autoStartEnabled(ctx)) return
        try {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_FROM_BOOT, true))
            Log.i(TAG, "auto-start after $action")
        } catch (e: Exception) {
            Log.w(TAG, "auto-start after $action failed", e)
        }
    }

    companion object {
        private const val TAG = "EvBoot"
        /** Stock boot, plus the "fast boot" variants several head-unit ROMs send. */
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }
}
