package com.codex.quota

import android.app.Application
import com.codex.quota.data.AccountStore
import com.codex.quota.data.ChatGptQuotaRepository
import com.codex.quota.data.CodexUsageClient
import com.codex.quota.data.QuotaRepository
import com.codex.quota.data.auth.CodexAuthStore
import com.codex.quota.data.auth.CodexOAuthClient
import com.codex.quota.work.QuotaRefreshScheduler
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class QuotaApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        QuotaRefreshScheduler.start(this)
    }
}

class AppContainer(application: Application) {
    val store = AccountStore(application)
    val authStore = CodexAuthStore(application)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val oauthClient = CodexOAuthClient(http)
    val usageClient = CodexUsageClient(http)
    val repository: QuotaRepository = ChatGptQuotaRepository(authStore, oauthClient, usageClient, store)
}
