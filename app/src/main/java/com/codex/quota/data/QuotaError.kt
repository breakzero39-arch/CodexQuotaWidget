package com.codex.quota.data

enum class QuotaError { NOT_LOGGED_IN, SESSION_EXPIRED, NETWORK, PARSE, UNSUPPORTED }

class QuotaException(val error: QuotaError, message: String? = null) : Exception(message)
