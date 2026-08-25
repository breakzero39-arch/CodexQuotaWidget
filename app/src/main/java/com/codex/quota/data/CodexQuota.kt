package com.codex.quota.data

import java.time.Instant

data class CodexQuota(
    val remainingPercent: Float,
    val resetAt: Instant,
    val updatedAt: Instant,
    val bonus: BonusQuota? = null
)

data class BonusQuota(
    val label: String,
    val amountPercent: Float? = null,
    val resetAt: Instant? = null
)

enum class QuotaState { NORMAL, LOW, CRITICAL, STALE, ERROR }

fun CodexQuota.state(): QuotaState = when {
    remainingPercent < 10f -> QuotaState.CRITICAL
    remainingPercent < 25f -> QuotaState.LOW
    else -> QuotaState.NORMAL
}

/** Which rolling window a quota number belongs to. */
enum class WindowType { FIVE_HOUR, SEVEN_DAY }

/** One rolling quota window (5-hour or 7-day) reported by the Codex usage endpoint. */
data class QuotaWindow(
    val remainingPercent: Float,
    val resetAt: Instant?,
    val usedPercent: Float? = null,
    val windowType: WindowType
)

/**
 * Both rolling windows the endpoint reports, plus the credits line and fetch time. [primary]
 * is the 5-hour window collapsed into the single [CodexQuota] the home-screen widget renders.
 */
data class QuotaSnapshot(
    val fiveHour: QuotaWindow?,
    val sevenDay: QuotaWindow?,
    val bonus: BonusQuota?,
    val updatedAt: Instant
) {
    val primary: CodexQuota? = fiveHour?.let { w ->
        w.resetAt?.let { CodexQuota(w.remainingPercent, it, updatedAt, bonus) }
    }
}
