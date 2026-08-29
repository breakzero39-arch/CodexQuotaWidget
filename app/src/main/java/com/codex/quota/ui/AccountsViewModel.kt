package com.codex.quota.ui

import android.app.Application
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codex.quota.QuotaApp
import com.codex.quota.data.AccountData
import com.codex.quota.data.QuotaError
import com.codex.quota.data.QuotaException
import com.codex.quota.data.auth.PkceCode
import com.codex.quota.widget.CodexQuotaWidget
import com.codex.quota.work.WidgetSync
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class LoginState { CONNECTED, CONNECTING, WAITING_FOR_AUTH, LOGIN_FAILED, CODE_EXPIRED, DISCONNECTED }

/** One account as shown in the list UI. */
data class AccountListItem(val accountData: AccountData) {
    val account = accountData.account
    val quota = accountData.quota
    val sessionExpired = accountData.sessionExpired
}

data class LoginUiState(
    val state: LoginState = LoginState.DISCONNECTED,
    val accountId: String? = null,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val error: String? = null
) {
    /** A login attempt is in progress when any state other than idle DISCONNECTED. */
    val active: Boolean
        get() = state != LoginState.DISCONNECTED || userCode != null || verificationUrl != null || error != null

    val buttonLabel: String
        get() = when (state) {
            LoginState.CONNECTED -> "刷新额度"
            LoginState.DISCONNECTED -> "连接 ChatGPT"
            LoginState.CODE_EXPIRED -> "重新生成验证码"
            LoginState.LOGIN_FAILED -> "重试登录"
            LoginState.WAITING_FOR_AUTH -> "重新生成验证码"
            LoginState.CONNECTING -> "连接中…"
        }
}

/**
 * Owns the account list (metadata + quota + auth state) and the single in-flight
 * device-code login. Per-account: a failure or re-login for one account never
 * touches another's stored state.
 */
class AccountsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as QuotaApp).container

    private companion object {
        const val TAG = "CodexQuota"
    }

    private val _accounts = MutableStateFlow<List<AccountListItem>>(emptyList())
    val accounts: StateFlow<List<AccountListItem>> = _accounts

    private val _login = MutableStateFlow(LoginUiState())
    val login: StateFlow<LoginUiState> = _login

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar

    fun consumeSnackbar() {
        _snackbar.value = null
    }

    private var loginJob: Job? = null

    init {
        viewModelScope.launch {
            container.store.accountData.collect { list ->
                _accounts.value = list.map { AccountListItem(it) }
            }
        }
    }

    /** New account → brand-new UUID; never reuse tokens or codes across accounts. */
    fun addAccount() = startLogin(UUID.randomUUID().toString())

    fun reconnect(accountId: String) = startLogin(accountId)

    /** Single entry point for the login card's primary button. */
    fun onPrimaryAction() {
        when (_login.value.state) {
            LoginState.CONNECTED -> _login.value.accountId?.let { refresh(it) }
            LoginState.WAITING_FOR_AUTH -> _login.value.accountId?.let { startLogin(it) }
            LoginState.CONNECTING -> Unit // busy, ignore
            else -> {
                val id = _login.value.accountId
                if (id != null) startLogin(id) else addAccount()
            }
        }
    }

    fun refresh(accountId: String) {
        viewModelScope.launch {
            try {
                container.repository.refresh(accountId)
                WidgetSync.updateForAccount(getApplication(), accountId)
            } catch (e: QuotaException) {
                // SESSION_EXPIRED is persisted by the repository → the account row shows 重新连接.
                // NETWORK/PARSE keep last-good data silently.
            } catch (_: Exception) {
            }
        }
    }

    fun refreshAll() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                val ids = container.store.connectedAccountIds()
                var ok = 0
                for (accountId in ids) {
                    try {
                        container.repository.refresh(accountId)
                        ok++
                    } catch (_: Exception) {
                        // keep last-good data; never block the other accounts
                    }
                }
                CodexQuotaWidget().updateAll(getApplication())
                _snackbar.value = when {
                    ids.isEmpty() -> null
                    ok == ids.size -> "已刷新"
                    ok == 0 -> "刷新失败"
                    else -> "${ok} 个账号已刷新，${ids.size - ok} 个失败"
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Persists the user's drag-reordered account id sequence. */
    fun reorder(orderedIds: List<String>) {
        viewModelScope.launch { container.store.setAccountOrder(orderedIds) }
    }

    fun removeAccount(accountId: String) {
        viewModelScope.launch {
            container.authStore.clear(accountId)
            container.store.removeAccount(accountId) // also unbinds its widgets
            if (_login.value.accountId == accountId) _login.value = LoginUiState()
        }
    }

    /** Fresh device-code login for one account. Cancels any in-flight attempt first. */
    private fun startLogin(accountId: String) {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            _login.update { LoginUiState(state = LoginState.CONNECTING, accountId = accountId) }
            Log.d(TAG, "login start accountId=$accountId")
            try {
                val device = container.oauthClient.requestUserCode()
                Log.d(TAG, "usercode ok interval=${device.intervalSeconds}s expires=${device.expiresInSeconds}s")
                _login.update {
                    it.copy(
                        state = LoginState.WAITING_FOR_AUTH,
                        userCode = device.userCode,
                        verificationUrl = device.verificationUrl
                    )
                }
                val deadline = System.currentTimeMillis() + device.expiresInSeconds * 1000
                var pkce: PkceCode? = null
                while (pkce == null) {
                    if (System.currentTimeMillis() > deadline) {
                        Log.d(TAG, "poll deadline reached → CODE_EXPIRED")
                        _login.update { it.copy(state = LoginState.CODE_EXPIRED, error = "验证码已过期") }
                        return@launch
                    }
                    pkce = try {
                        container.oauthClient.pollOnce(device)
                    } catch (e: QuotaException) {
                        Log.d(TAG, "poll error ${e.error} — treated as pending")
                        // A transient network blip during polling must not kill the whole
                        // login — treat it as "still waiting", keep polling until the deadline.
                        if (e.error == QuotaError.NETWORK) null else throw e
                    }
                    if (pkce == null) {
                        Log.d(TAG, "poll → pending, retry in ${device.intervalSeconds}s")
                        delay(device.intervalSeconds * 1000L)
                    } else {
                        Log.d(TAG, "poll → authorized")
                    }
                }
                val tokens = container.oauthClient.exchange(pkce.code, pkce.verifier)
                Log.d(TAG, "exchange ok")
                container.authStore.save(accountId, tokens)
                // Register the account only if it doesn't exist yet. Retrying after a failed
                // first attempt keeps the same UUID; with a plain "setConnected" it would save
                // tokens for an account that was never registered → "connected" but no account row.
                val displayName = tokens.email?.substringBefore("@")?.takeIf { it.isNotEmpty() }
                val existed = container.store.accountNow(accountId) != null
                if (!existed) {
                    container.store.addAccount(accountId, displayName, tokens.email)
                } else {
                    container.store.setConnected(accountId, true)
                }
                Log.d(TAG, "account registered (${if (existed) "existing" else "new"}) displayName=$displayName")
                _login.value = LoginUiState() // hide the login card; the account row shows the result
                // Best-effort first fetch; a failure keeps the account visible with "等待首次同步".
                try {
                    container.repository.refresh(accountId)
                    WidgetSync.updateForAccount(getApplication(), accountId)
                } catch (e: Exception) {
                    Log.d(TAG, "first refresh failed: ${e.message}")
                }
            } catch (e: QuotaException) {
                Log.d(TAG, "login failed: ${e.error} ${e.message}")
                _login.update { it.copy(state = LoginState.LOGIN_FAILED, error = e.message ?: e.error.name) }
            } catch (e: Exception) {
                Log.d(TAG, "login crashed: ${e.message}")
                _login.update { it.copy(state = LoginState.LOGIN_FAILED, error = "登录失败: ${e.message}") }
            }
        }
    }
}
