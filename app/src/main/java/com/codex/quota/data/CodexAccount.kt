package com.codex.quota.data

import java.time.Instant

/**
 * A single Codex/ChatGPT account. accountId is an app-generated UUID used as the
 * primary key everywhere (never the access token).
 */
data class CodexAccount(
    val id: String,
    val displayName: String?,
    val email: String?,
    val connected: Boolean,
    val createdAt: Instant,
    val lastSyncAt: Instant?
)

/** Per-account quota snapshot across both rolling windows (5-hour primary + 7-day). */
data class AccountQuota(
    val accountId: String,
    val fiveHour: QuotaWindow?,
    val sevenDay: QuotaWindow?,
    val updatedAt: Instant,
    val bonus: BonusQuota? = null
) {
    /** Primary (5-hour) window as the single CodexQuota the widget renders; null if not yet synced. */
    fun toQuota(): CodexQuota? = fiveHour?.let { w ->
        w.resetAt?.let { CodexQuota(w.remainingPercent, it, updatedAt, bonus) }
    }
}

/** Metadata + quota + auth state for one account, as shown in the account list. */
data class AccountData(
    val account: CodexAccount,
    val quota: AccountQuota?,
    val sessionExpired: Boolean
)

/** Everything a single widget needs to render, derived from one DataStore snapshot. */
data class WidgetState(
    val accountId: String?,
    val displayName: String?,
    val sessionExpired: Boolean,
    val quota: AccountQuota?
)
