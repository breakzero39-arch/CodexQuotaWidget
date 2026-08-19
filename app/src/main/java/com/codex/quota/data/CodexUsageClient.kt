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

    suspend fun fetchUsage(tokens: CodexTokens): CodexQuota = withContext(Dispatchers.IO) {
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

    private fun parse(body: String): CodexQuota {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw QuotaException(QuotaError.PARSE, e.message)
        }
        val rateLimit = root.optJSONObject("rate_limit")
        val primary = rateLimit?.optJSONObject("primary_window")
        val secondary = rateLimit?.optJSONObject("secondary_window")

        val pUsed = primary?.usedPercent()
        val sUsed = secondary?.usedPercent()
        if (pUsed == null && sUsed == null) throw QuotaException(QuotaError.PARSE, "no rate-limit windows")

        // Both windows are enforced simultaneously; the binding constraint is the more-used one.
        val used = listOfNotNull(pUsed, sUsed).max()
        val remaining = (100.0 - used).coerceIn(0.0, 100.0).toFloat()

        val binding = when {
            pUsed == null -> secondary!!
            sUsed == null -> primary!!
            pUsed >= sUsed -> primary!! else -> secondary!!
        }
        val resetAt = binding.resetAt()
            ?: (if (binding === primary) secondary else primary)?.resetAt()
            ?: Instant.now().plusSeconds(binding.limitWindowSeconds())

        return CodexQuota(
            remainingPercent = remaining,
            resetAt = resetAt,
            updatedAt = Instant.now(),
            bonus = root.bonus()
        )
    }

    private fun JSONObject.usedPercent(): Double? =
        if (has("used_percent")) optDouble("used_percent").takeIf { !it.isNaN() } else null

    private fun JSONObject.resetAt(): Instant? {
        if (!has("reset_at")) return null
        val v = optLong("reset_at", -1L)
        return if (v <= 0) null else epoch(v)
    }

    private fun JSONObject.limitWindowSeconds(): Long =
        if (has("limit_window_seconds")) optLong("limit_window_seconds", 18000L) else 18000L

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
}
