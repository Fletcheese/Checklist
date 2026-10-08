package com.example.checklist.external

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Adds an item to an existing list without any UI, so it works with the screen off.
 *
 * Guarded by the signature permission com.fletcheese.checklist.permission.ADD_ITEM
 * (declared in the manifest), so only apps signed with the same key can deliver to it.
 *
 * Result, both ways (use whichever is easier to await):
 *  - ordered broadcast: resultCode RESULT_OK, resultData = result string, resultExtras = full Bundle
 *  - optional PendingIntent extra "reply": sent with the same Bundle as fill-in extras
 */
class AddItemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ExternalCommands.ACTION_ADD_ITEM) return
        val result = ExternalCommands.addItem(
            context,
            intent.getStringExtra(ExternalCommands.EXTRA_LIST),
            intent.getStringExtra(ExternalCommands.EXTRA_ITEM)
        )
        val code = result.getString(ExternalCommands.KEY_RESULT)
        Log.i(TAG, "ADD_ITEM list='${result.getString(ExternalCommands.KEY_LIST)}' item='${result.getString(ExternalCommands.KEY_ITEM)}' -> $code")

        if (isOrderedBroadcast) {
            setResult(Activity.RESULT_OK, code, result)
        }
        val reply: PendingIntent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(ExternalCommands.EXTRA_REPLY, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(ExternalCommands.EXTRA_REPLY)
        }
        try {
            reply?.send(context, Activity.RESULT_OK, Intent().putExtras(result))
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "Reply PendingIntent was cancelled", e)
        }
    }

    companion object {
        private const val TAG = "AddItemReceiver"
    }
}
