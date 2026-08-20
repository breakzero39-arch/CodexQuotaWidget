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
    private const val KEY_LAST_ALL = "last_refresh_all_ms"
    private const val MIN_FETCH_GAP_MS = 5L * 60 * 1000

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

    /**
     * Refresh every account, but never more than once per [MIN_FETCH_GAP_MS].
     *
     * The system fires APPWIDGET_UPDATE on its own schedule and launchers repaint widgets far
     * more often than quota actually changes; without a floor, every such tick would hit the
     * Codex API (observed as the card updating every ~10s). Rendering is unaffected — the widget
     * receivers render the current DataStore first, so a skipped fetch just repaints the latest
     * known value. Explicit widget-tap refreshes bypass this (RefreshAccountWorker).
     */
    fun refreshAll(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("refresh_throttle", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_ALL, 0L) < MIN_FETCH_GAP_MS) return
        prefs.edit().putLong(KEY_LAST_ALL, now).apply()
        val one = OneTimeWorkRequestBuilder<RefreshAllAccountsWorker>().build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(ALL_WORK, ExistingWorkPolicy.KEEP, one)
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
