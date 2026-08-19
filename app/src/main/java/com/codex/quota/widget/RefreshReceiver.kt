package com.codex.quota.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.codex.quota.QuotaApp
import com.codex.quota.work.QuotaRefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Widget-tap handler. Refreshes only the account the tapped widget is bound to,
 * so tapping one instance never touches another account's state.
 */
class RefreshReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appWidgetId = intent.getIntExtra(EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = (context.applicationContext as QuotaApp).container.store
                val accountId = store.accountForWidget(appWidgetId)
                if (accountId != null) {
                    QuotaRefreshScheduler.refreshAccount(context.applicationContext, accountId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_APPWIDGET_ID = "codex_widget_app_widget_id"
    }
}
