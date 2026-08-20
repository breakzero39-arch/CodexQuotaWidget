package com.codex.quota.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.codex.quota.work.QuotaRefreshScheduler

class CodexQuotaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CodexQuotaWidget()

    // The provider's updatePeriodMillis makes the SYSTEM (SystemUI) broadcast APPWIDGET_UPDATE
    // every 30 min while the device is awake — delivered even when the app process is dead,
    // unlike WorkManager periodic jobs that ColorOS suppresses. Each such repaint also kicks a
    // quota refresh, so the widget shows fresh data instead of re-rendering stale DataStore.
    // refreshAll is unique-work KEEP-throttled, so bursts (add / resize / periodic tick)
    // collapse into one worker; super renders current state immediately.
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        QuotaRefreshScheduler.refreshAll(context.applicationContext)
    }
}
