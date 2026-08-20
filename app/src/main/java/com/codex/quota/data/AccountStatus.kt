package com.codex.quota.data

import androidx.compose.ui.graphics.Color
import java.time.Duration
import java.time.Instant

/**
 * One shared status vocabulary for both the main app's account cards and the
 * home-screen widgets, so "Live / Stale / Reconnect" mean the same thing in both.
 */
enum class AccountStatus { LIVE, STALE, RECONNECT, NONE }

/** A quota older than this is no longer "Live". Kept in one place (was widget-only). */
val STALE_AFTER: Duration = Duration.ofHours(2)

fun statusOf(sessionExpired: Boolean, updatedAt: Instant?): AccountStatus = when {
    sessionExpired -> AccountStatus.RECONNECT          // session truly dead → red, primary Reconnect action
    updatedAt == null -> AccountStatus.NONE            // never synced
    Duration.between(updatedAt, Instant.now()) > STALE_AFTER -> AccountStatus.STALE
    else -> AccountStatus.LIVE
}

fun AccountStatus.color(): Color = when (this) {
    AccountStatus.LIVE -> Color(0xFF30D158)
    AccountStatus.STALE -> Color(0xFF8E8E93)
    AccountStatus.RECONNECT -> Color(0xFFFF453A)
    AccountStatus.NONE -> Color(0xFFFF453A)
}

fun AccountStatus.label(): String = when (this) {
    AccountStatus.LIVE -> "Live"
    AccountStatus.STALE -> "Stale"
    AccountStatus.RECONNECT -> "Reconnect"
    AccountStatus.NONE -> "Disconnected"
}
