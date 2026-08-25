package com.codex.quota.data

import android.util.Log
import com.codex.quota.data.auth.CodexAuthStore
import com.codex.quota.data.auth.CodexOAuthClient

class ChatGptQuotaRepository(
    private val authStore: CodexAuthStore,
    private val oauthClient: CodexOAuthClient,
    private val usageClient: CodexUsageClient,
    private val store: AccountStore
) : QuotaRepository {

    override suspend fun getQuota(accountId: String): CodexQuota =
        store.currentQuota(accountId)?.toQuota()
            ?: throw QuotaException(QuotaError.NOT_LOGGED_IN, "no cached quota")

    override suspend fun refresh(accountId: String): CodexQuota {
        val tokens = authStore.tokens(accountId)
            ?: throw QuotaException(QuotaError.NOT_LOGGED_IN, "not logged in")
        Log.d(TAG, "refresh($accountId) fetchUsage start")
        val snapshot = try {
            usageClient.fetchUsage(tokens)
        } catch (e: QuotaException) {
            if (e.error != QuotaError.SESSION_EXPIRED) {
                Log.d(TAG, "refresh($accountId) fetchUsage failed: ${e.error} ${e.message}")
                throw e
            }
            Log.d(TAG, "refresh($accountId) 401 → trying refresh_token")
            // Access token expired: refresh once. If the refresh token is also dead,
            // mark this account RECONNECT_REQUIRED and propagate SESSION_EXPIRED.
            val fresh = try {
                oauthClient.refresh(tokens.refreshToken)
            } catch (e2: QuotaException) {
                Log.d(TAG, "refresh($accountId) refresh_token dead: ${e2.error}")
                store.setSessionExpired(accountId, true)
                throw e2
            }
            authStore.save(accountId, fresh)
            usageClient.fetchUsage(fresh)
        }
        val primary = snapshot.primary
            ?: throw QuotaException(QuotaError.PARSE, "no primary window")
        Log.d(TAG, "refresh($accountId) ok 5h=${snapshot.fiveHour?.remainingPercent}% 7d=${snapshot.sevenDay?.remainingPercent}%")
        store.saveQuota(accountId, snapshot)
        store.setSessionExpired(accountId, false)
        return primary
    }

    private companion object {
        const val TAG = "CodexQuota"
    }
}
