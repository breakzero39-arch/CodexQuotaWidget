package com.codex.quota.work

import android.content.Context
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.codex.quota.QuotaApp
import com.codex.quota.widget.CodexQuotaWidget

/** Updates only the widget instances currently bound to [accountId]. */
object WidgetSync {
    suspend fun updateForAccount(context: Context, accountId: String) {
        val store = (context.applicationContext as QuotaApp).container.store
        val widget = CodexQuotaWidget()
        GlanceAppWidgetManager(context).getGlanceIds(CodexQuotaWidget::class.java).forEach { glanceId ->
            val appWidgetId = (glanceId as? AppWidgetId)?.appWidgetId ?: return@forEach
            if (store.accountForWidget(appWidgetId) == accountId) {
                widget.update(context, glanceId)
            }
        }
    }
}
