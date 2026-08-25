package com.codex.quota.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.codexAccountsDataStore by preferencesDataStore(name = "codex_quota")

/**
 * DataStore for everything except tokens (which live in per-account encrypted prefs).
 * Every key is account-scoped; the widget→account mapping is keyed by appWidgetId.
 * Reacts to any change because every read is derived from the same Preferences snapshot.
 */
class AccountStore(private val context: Context) {

    private object Keys {
        val accountIds = stringSetPreferencesKey("account_ids")
        fun name(id: String) = stringPreferencesKey("accounts/$id/display_name")
        fun email(id: String) = stringPreferencesKey("accounts/$id/email")
        fun connected(id: String) = booleanPreferencesKey("accounts/$id/connected")
        fun createdAt(id: String) = longPreferencesKey("accounts/$id/created_at_millis")
        fun lastSync(id: String) = longPreferencesKey("accounts/$id/last_sync_millis")
        fun sessionExpired(id: String) = booleanPreferencesKey("accounts/$id/session_expired")
        fun qRemaining(id: String) = floatPreferencesKey("accounts/$id/quota_remaining")
        fun qReset(id: String) = longPreferencesKey("accounts/$id/quota_reset_millis")
        fun q7Remaining(id: String) = floatPreferencesKey("accounts/$id/quota7_remaining")
        fun q7Reset(id: String) = longPreferencesKey("accounts/$id/quota7_reset_millis")
        fun qUpdated(id: String) = longPreferencesKey("accounts/$id/quota_updated_millis")
        fun qHasBonus(id: String) = booleanPreferencesKey("accounts/$id/quota_has_bonus")
        fun qBonusLabel(id: String) = stringPreferencesKey("accounts/$id/quota_bonus_label")
        fun qBonusAmount(id: String) = floatPreferencesKey("accounts/$id/quota_bonus_amount")
        fun qBonusReset(id: String) = longPreferencesKey("accounts/$id/quota_bonus_reset_millis")
        fun widget(appWidgetId: Int) = stringPreferencesKey("widgets/$appWidgetId")
    }

    /** Full account list (metadata + quota + auth state), reactive to any key change. */
    val accountData: Flow<List<AccountData>> = context.codexAccountsDataStore.data.map { prefs ->
        (prefs[Keys.accountIds] ?: emptySet()).sorted().map { id ->
            AccountData(
                account = prefs.account(id),
                quota = prefs.quota(id),
                sessionExpired = prefs[Keys.sessionExpired(id)] ?: false
            )
        }
    }

    val accounts: Flow<List<CodexAccount>> = accountData.map { it.map { d -> d.account } }

    suspend fun currentQuota(accountId: String): AccountQuota? =
        context.codexAccountsDataStore.data.first().quota(accountId)

    suspend fun accountNow(accountId: String): CodexAccount? =
        context.codexAccountsDataStore.data.first().takeIf { it[Keys.accountIds]?.contains(accountId) == true }?.account(accountId)

    suspend fun sessionExpiredNow(accountId: String): Boolean =
        context.codexAccountsDataStore.data.first()[Keys.sessionExpired(accountId)] ?: false

    suspend fun connectedAccountIds(): List<String> {
        val prefs = context.codexAccountsDataStore.data.first()
        return (prefs[Keys.accountIds] ?: emptySet())
            .filter { prefs[Keys.connected(it)] != false }
            .sorted()
    }

    suspend fun addAccount(accountId: String, displayName: String?, email: String?) {
        context.codexAccountsDataStore.edit { prefs ->
            prefs[Keys.accountIds] = (prefs[Keys.accountIds] ?: emptySet()) + accountId
            if (displayName != null) prefs[Keys.name(accountId)] = displayName
            if (email != null) prefs[Keys.email(accountId)] = email
            prefs[Keys.createdAt(accountId)] = System.currentTimeMillis()
            prefs[Keys.connected(accountId)] = true
        }
    }

    suspend fun saveQuota(accountId: String, snapshot: QuotaSnapshot) {
        context.codexAccountsDataStore.edit { prefs ->
            snapshot.fiveHour?.let { w ->
                prefs[Keys.qRemaining(accountId)] = w.remainingPercent
                w.resetAt?.let { prefs[Keys.qReset(accountId)] = it.toEpochMilli() }
            }
            snapshot.sevenDay?.let { w ->
                prefs[Keys.q7Remaining(accountId)] = w.remainingPercent
                w.resetAt?.let { prefs[Keys.q7Reset(accountId)] = it.toEpochMilli() }
            }
            prefs[Keys.qUpdated(accountId)] = snapshot.updatedAt.toEpochMilli()
            prefs[Keys.lastSync(accountId)] = snapshot.updatedAt.toEpochMilli()
            prefs[Keys.connected(accountId)] = true
            prefs[Keys.sessionExpired(accountId)] = false
            val bonus = snapshot.bonus
            if (bonus != null) {
                prefs[Keys.qHasBonus(accountId)] = true
                prefs[Keys.qBonusLabel(accountId)] = bonus.label
                prefs[Keys.qBonusAmount(accountId)] = bonus.amountPercent ?: -1f
                prefs[Keys.qBonusReset(accountId)] = bonus.resetAt?.toEpochMilli() ?: 0L
            } else {
                prefs[Keys.qHasBonus(accountId)] = false
            }
        }
    }

    suspend fun setConnected(accountId: String, value: Boolean) {
        context.codexAccountsDataStore.edit { it[Keys.connected(accountId)] = value }
    }

    suspend fun setSessionExpired(accountId: String, value: Boolean) {
        context.codexAccountsDataStore.edit { it[Keys.sessionExpired(accountId)] = value }
    }

    /** Drops the account, its keys, and unbinds every widget pointing at it. */
    suspend fun removeAccount(accountId: String) {
        context.codexAccountsDataStore.edit { prefs ->
            prefs[Keys.accountIds] = (prefs[Keys.accountIds] ?: emptySet()) - accountId
            prefs.asMap().keys
                .filter { it.name.startsWith("accounts/$accountId/") }
                .forEach { prefs.removeAny(it) }
            prefs.asMap().keys
                .filter { it.name.startsWith("widgets/") && prefs[it] == accountId }
                .forEach { prefs.removeAny(it) }
        }
    }

    // Key<*> cannot satisfy remove's generic T; the cast is safe because remove never touches the value.
    private fun MutablePreferences.removeAny(key: Preferences.Key<*>) {
        @Suppress("UNCHECKED_CAST")
        remove(key as Preferences.Key<Any>)
    }

    suspend fun bindWidget(appWidgetId: Int, accountId: String) {
        context.codexAccountsDataStore.edit { it[Keys.widget(appWidgetId)] = accountId }
    }

    suspend fun unbindWidget(appWidgetId: Int) {
        context.codexAccountsDataStore.edit { it.remove(Keys.widget(appWidgetId)) }
    }

    suspend fun accountForWidget(appWidgetId: Int): String? =
        context.codexAccountsDataStore.data.first()[Keys.widget(appWidgetId)]

    /**
     * Reactive snapshot of everything a widget needs to render, re-emitted on any DataStore change
     * (binding, quota refresh, session expiry). Collecting this inside the Glance composition lets
     * a just-bound widget recompose to account data without waiting for its session to restart.
     */
    fun widgetState(appWidgetId: Int): Flow<WidgetState> =
        context.codexAccountsDataStore.data.map { prefs ->
            val accountId = prefs[Keys.widget(appWidgetId)]
            val account = accountId?.let { prefs.account(it) }
            WidgetState(
                accountId = accountId,
                displayName = account?.displayName,
                sessionExpired = accountId?.let { prefs[Keys.sessionExpired(it)] } ?: true,
                quota = accountId?.let { prefs.quota(it) }
            )
        }

    // ---------- per-snapshot readers ----------

    private fun Preferences.account(id: String): CodexAccount = CodexAccount(
        id = id,
        displayName = this[Keys.name(id)],
        email = this[Keys.email(id)],
        connected = this[Keys.connected(id)] ?: true,
        createdAt = Instant.ofEpochMilli(this[Keys.createdAt(id)] ?: 0L),
        lastSyncAt = this[Keys.lastSync(id)]?.let(Instant::ofEpochMilli)
    )

    private fun Preferences.quota(id: String): AccountQuota? {
        val updated = this[Keys.qUpdated(id)] ?: return null
        val fiveHour = this[Keys.qRemaining(id)]?.let { remaining ->
            QuotaWindow(
                remainingPercent = remaining,
                resetAt = this[Keys.qReset(id)]?.takeIf { it > 0L }?.let(Instant::ofEpochMilli),
                windowType = WindowType.FIVE_HOUR
            )
        }
        val sevenDay = this[Keys.q7Remaining(id)]?.let { remaining ->
            QuotaWindow(
                remainingPercent = remaining,
                resetAt = this[Keys.q7Reset(id)]?.takeIf { it > 0L }?.let(Instant::ofEpochMilli),
                windowType = WindowType.SEVEN_DAY
            )
        }
        if (fiveHour == null && sevenDay == null) return null
        val bonus = if (this[Keys.qHasBonus(id)] == true) {
            BonusQuota(
                label = this[Keys.qBonusLabel(id)] ?: "特殊额度",
                amountPercent = this[Keys.qBonusAmount(id)]?.takeIf { it >= 0f },
                resetAt = this[Keys.qBonusReset(id)]?.takeIf { it > 0L }?.let(Instant::ofEpochMilli)
            )
        } else null
        return AccountQuota(
            accountId = id,
            fiveHour = fiveHour,
            sevenDay = sevenDay,
            updatedAt = Instant.ofEpochMilli(updated),
            bonus = bonus
        )
    }
}
