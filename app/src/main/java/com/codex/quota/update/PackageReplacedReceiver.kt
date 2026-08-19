package com.codex.quota.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.codex.quota.work.QuotaRefreshScheduler

/**
 * Fired when this app is updated in place. Accounts, quota DataStore, and widget→account
 * bindings are preserved by the system (nothing is cleared); we only re-arm the periodic
 * refresh and push a fresh widget render for every account.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        // start() = re-enqueue periodic work + run RefreshAllAccountsWorker once,
        // which refreshes every account and updateAll()s the widgets.
        QuotaRefreshScheduler.start(context.applicationContext)
    }
}
