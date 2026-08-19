package com.codex.quota.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.codex.quota.QuotaApp
import com.codex.quota.widget.CodexQuotaWidget

/**
 * Refreshes every connected account sequentially; one account's failure keeps its
 * last-good data and never blocks the others. Used by the periodic worker and Refresh All.
 */
class RefreshAllAccountsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as QuotaApp).container
        return try {
            for (accountId in container.store.connectedAccountIds()) {
                try {
                    container.repository.refresh(accountId)
                } catch (_: Exception) {
                    // keep this account's cached quota; continue with the next account
                }
            }
            CodexQuotaWidget().updateAll(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }
}
