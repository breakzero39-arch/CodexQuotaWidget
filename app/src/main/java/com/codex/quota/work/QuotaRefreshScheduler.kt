package com.codex.quota.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

object QuotaRefreshScheduler {

    // distinct name so any stale single-account periodic work is replaced, not reused
    private const val PERIODIC_WORK = "codex_quota_periodic_v2"
    private const val ALL_WORK = "codex_refresh_all"
    private const val ACCOUNT_WORK_PREFIX = "codex_refresh_account_"

    fun start(context: Context) {
        // Backup to the system-driven updatePeriodMillis tick: a WorkManager periodic job is
        // the fallback on stock devices / Doze maintenance windows. Connected constraint means
        // an offline window just waits instead of burning battery on doomed fetches.
        val periodic = PeriodicWorkRequestBuilder<RefreshAllAccountsWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )
        refreshAll(context)
    }

    fun refreshAll(context: Context) {
        val one = OneTimeWorkRequestBuilder<RefreshAllAccountsWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(ALL_WORK, ExistingWorkPolicy.KEEP, one)
    }

    fun refreshAccount(context: Context, accountId: String) {
        val one = OneTimeWorkRequestBuilder<RefreshAccountWorker>()
            .setInputData(workDataOf(RefreshAccountWorker.KEY_ACCOUNT_ID to accountId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ACCOUNT_WORK_PREFIX + accountId,
            ExistingWorkPolicy.KEEP,
            one
        )
    }
}
