package com.codex.quota.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.codex.quota.work.QuotaRefreshScheduler

/**
 * Second launcher entry ("Codex Quota 2×2"). Renders the SAME [CodexQuotaWidget],
 * which picks the compact layout from LocalSize — so both providers share one
 * update path ([CodexQuotaWidget] is what WidgetSync.getGlanceIds enumerates).
 */
class CodexQuotaWidget2x2Receiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CodexQuotaWidget()

    // Same as the 4x2 receiver: the system's updatePeriodMillis tick becomes a quota refresh.
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        QuotaRefreshScheduler.refreshAll(context.applicationContext)
    }
}
