package com.codex.quota.data.auth

data class CodexTokens(
    val accessToken: String,
    val refreshToken: String,
    val accountId: String? = null,
    val email: String? = null
)
