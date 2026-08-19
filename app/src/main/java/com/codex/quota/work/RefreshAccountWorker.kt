package com.codex.quota.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.codex.quota.QuotaApp

/** One-shot refresh of a single account (widget tap / account-list refresh). */
class RefreshAccountWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return Result.success()
        val container = (applicationContext as QuotaApp).container
        return try {
            container.repository.refresh(accountId)
            WidgetSync.updateForAccount(applicationContext, accountId)
            Result.success()
        } catch (_: Exception) {
            // keep last-good data; SESSION_EXPIRED/NETWORK are surfaced in the app UI
            Result.success()
        }
    }

    companion object {
        const val KEY_ACCOUNT_ID = "account_id"
    }
}
