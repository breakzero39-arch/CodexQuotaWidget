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
