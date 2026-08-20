package com.codex.quota.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Second launcher entry ("Codex Quota 2×2"). Renders the SAME [CodexQuotaWidget],
 * which picks the compact layout from LocalSize — so both providers share one
 * update path ([CodexQuotaWidget] is what WidgetSync.getGlanceIds enumerates).
 */
class CodexQuotaWidget2x2Receiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CodexQuotaWidget()
}
