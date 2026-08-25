package com.codex.quota.data

import com.codex.quota.data.auth.CodexTokens
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Fetches the real Codex quota from the same endpoint the official CLI uses:
 * GET https://chatgpt.com/backend-api/wham/usage (Bearer OAuth). Undocumented; parsed defensively.
 */
class CodexUsageClient(private val http: OkHttpClient) {

    suspend fun fetchUsage(tokens: CodexTokens): QuotaSnapshot = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://chatgpt.com/backend-api/wham/usage")
            .header("Authorization", "Bearer ${tokens.accessToken}")
            .header("Accept", "application/json")
            .apply { tokens.accountId?.let { header("ChatGPT-Account-Id", it) } }
            .get()
            .build()
        val resp = try {
            http.newCall(req).execute()
        } catch (e: IOException) {
            throw QuotaException(QuotaError.NETWORK, e.message)
        }
        resp.use {
            when (it.code) {
                200 -> parse(it.body?.string().orEmpty())
                401 -> throw QuotaException(QuotaError.SESSION_EXPIRED, "401")
                else -> throw QuotaException(QuotaError.UNSUPPORTED, "http ${it.code}")
            }
        }
    }

    private fun parse(body: String): QuotaSnapshot {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw QuotaException(QuotaError.PARSE, e.message)
        }
        val rateLimit = root.optJSONObject("rate_limit")
        // primary_window = the 5-hour window, secondary_window = the 7-day window.
        val fiveHour = rateLimit?.optJSONObject("primary_window").window(WindowType.FIVE_HOUR, FIVE_HOUR_SECONDS)
        val sevenDay = rateLimit?.optJSONObject("secondary_window").window(WindowType.SEVEN_DAY, SEVEN_DAY_SECONDS)
        if (fiveHour == null && sevenDay == null) throw QuotaException(QuotaError.PARSE, "no rate-limit windows")

        return QuotaSnapshot(
            fiveHour = fiveHour,
            sevenDay = sevenDay,
            bonus = root.bonus(),
            updatedAt = Instant.now()
        )
    }

    /** Builds one rolling window; null when the JSON object is absent or has no used_percent. */
    private fun JSONObject?.window(type: WindowType, defaultSeconds: Long): QuotaWindow? {
        this ?: return null
        val used = usedPercent() ?: return null
        val reset = resetAt() ?: Instant.now().plusSeconds(limitWindowSeconds(defaultSeconds))
        return QuotaWindow(
            remainingPercent = (100.0 - used).coerceIn(0.0, 100.0).toFloat(),
            resetAt = reset,
            usedPercent = used.toFloat(),
            windowType = type
        )
    }

    private fun JSONObject.usedPercent(): Double? =
        if (has("used_percent")) optDouble("used_percent").takeIf { !it.isNaN() } else null

    private fun JSONObject.resetAt(): Instant? {
        if (!has("reset_at")) return null
        val v = optLong("reset_at", -1L)
        return if (v <= 0) null else epoch(v)
    }

    private fun JSONObject.limitWindowSeconds(default: Long): Long =
        if (has("limit_window_seconds")) optLong("limit_window_seconds", default) else default

    private fun JSONObject.bonus(): BonusQuota? {
        val resetCredits = optJSONObject("rate_limit_reset_credits")
        val available = resetCredits?.optInt("available_count", 0) ?: 0
        if (available > 0) return BonusQuota(label = "重置额度 ×$available")
        val credits = optJSONObject("credits")
        if (credits != null && credits.optBoolean("has_credits", false)) return BonusQuota(label = "Credits")
        return null
    }

    private fun epoch(value: Long): Instant =
        if (value > 10_000_000_000L) Instant.ofEpochMilli(value) else Instant.ofEpochSecond(value)

    private companion object {
        const val FIVE_HOUR_SECONDS = 18_000L   // 5h
        const val SEVEN_DAY_SECONDS = 604_800L  // 7d
    }
}
