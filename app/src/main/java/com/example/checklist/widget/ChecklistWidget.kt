package com.example.checklist.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import com.example.checklist.MainActivity
import com.example.checklist.R
import com.example.checklist.data.ChecklistRepository

class ChecklistWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_PIN_CONFIRM) {
            val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID)
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && instanceId != null) {
                saveWidgetInstanceId(context, appWidgetId, instanceId)
                updateAppWidget(context, AppWidgetManager.getInstance(context), appWidgetId)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        for (appWidgetId in appWidgetIds) {
            prefs.remove("widget_$appWidgetId")
        }
        prefs.apply()
    }

    companion object {
        const val ACTION_PIN_CONFIRM = "com.example.checklist.ACTION_PIN_WIDGET"
        const val EXTRA_INSTANCE_ID = "instanceId"
        private const val PREFS_NAME = "ChecklistWidgetPrefs"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, ChecklistWidget::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId)
            }
        }

        private fun saveWidgetInstanceId(context: Context, appWidgetId: Int, instanceId: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("widget_$appWidgetId", instanceId)
                .apply()
        }

        private fun getWidgetInstanceId(context: Context, appWidgetId: Int): String? {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("widget_$appWidgetId", null)
        }

        private fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            ChecklistRepository.initialize(context)
            
            val pinnedId = getWidgetInstanceId(context, appWidgetId)
            val targetList = if (pinnedId != null) {
                ChecklistRepository.instances.find { it.id == pinnedId }
            } else {
                ChecklistRepository.instances
                    .filter { !it.isArchived }
                    .maxByOrNull { it.lastModified }
            }

            val views = RemoteViews(context.packageName, R.layout.checklist_widget_layout)
            views.setTextViewText(R.id.widget_list_name, targetList?.name ?: "No active lists")

            if (targetList != null) {
                val uncheckedCount = targetList.items.count { !it.isChecked }
                val totalCount = targetList.items.size
                views.setTextViewText(R.id.widget_list_progress, "$uncheckedCount / $totalCount")
            } else {
                views.setTextViewText(R.id.widget_list_progress, "")
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                action = "com.example.checklist.ACTION_OPEN_LIST_$appWidgetId"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                targetList?.let { putExtra("instanceId", it.id) }
            }
            
            val pendingIntent = PendingIntent.getActivity(
                context, 
                appWidgetId,
                intent, 
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
