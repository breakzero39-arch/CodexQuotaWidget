package com.codex.quota.data

interface QuotaRepository {
    suspend fun getQuota(accountId: String): CodexQuota
    suspend fun refresh(accountId: String): CodexQuota
}
