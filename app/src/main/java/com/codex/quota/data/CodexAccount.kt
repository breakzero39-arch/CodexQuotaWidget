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

/** Per-account quota snapshot. resetAt is nullable so a never-fetched account has a valid row. */
data class AccountQuota(
    val accountId: String,
    val remainingPercent: Float,
    val resetAt: Instant?,
    val updatedAt: Instant,
    val bonus: BonusQuota? = null
) {
    fun toQuota(): CodexQuota? = resetAt?.let {
        CodexQuota(remainingPercent, it, updatedAt, bonus)
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
