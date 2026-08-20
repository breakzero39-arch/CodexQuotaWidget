package com.codex.quota.data.update

import android.content.Context
import android.content.SharedPreferences
import com.codex.quota.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Result of a version check. */
sealed interface UpdateCheck {
    data class Available(val manifest: UpdateManifest) : UpdateCheck
    data object UpToDate : UpdateCheck
}

/**
 * Checks the update manifest over HTTPS and compares against the installed versionCode.
 * Fully decoupled from the quota system: any network/parse failure returns null (never an
 * exception), and the 12h throttle lives in a private SharedPreferences file — no DataStore
 * involved.
 */
class UpdateRepository(
    private val context: Context,
    private val http: OkHttpClient
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)

    /** null on any network/parse failure — never propagate an exception to the quota side. */
    suspend fun check(): UpdateCheck? = withContext(Dispatchers.IO) {
        // jsDelivr caches @main branch refs for days and its branch-ref purge is unreliable
        // (it served a stale latest.json today even right after purging), so hit the uncached
        // raw GitHub source first — it always reflects the true pushed repo state. Only when
        // raw is unreachable (some mainland-CN networks block it) fall back to the CDN.
        for (url in listOf(RAW_UPDATE_URL, UPDATE_URL)) {
            val manifest = try {
                val body = http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                } ?: continue
                UpdateManifest.parse(body)
            } catch (_: Exception) {
                continue
            }
            if (manifest != null) {
                return@withContext when {
                    manifest.versionCode > BuildConfig.VERSION_CODE -> UpdateCheck.Available(manifest)
                    else -> UpdateCheck.UpToDate
                }
            }
        }
        null
    }

    fun lastCheckMillis(): Long = prefs.getLong(KEY_LAST_CHECK, 0L)

    fun markChecked() {
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    /** Auto-check is throttled: at most once per interval. Manual "检查更新" always runs. */
    fun shouldAutoCheck(): Boolean =
        System.currentTimeMillis() - lastCheckMillis() >= AUTO_CHECK_INTERVAL_MS

    companion object {
        // Raw GitHub is uncached and always reflects the pushed repo state — no CDN to go stale.
        // jsDelivr ignores query-string cache-busting, so this is the only reliable source for a
        // manifest that changes at a fixed path.
        const val RAW_UPDATE_URL =
            "https://raw.githubusercontent.com/breakzero39-arch/CodexQuotaWidget/main/release/latest.json"
        // China-reachable fallback. NOTE: @main branch refs can serve a stale manifest for days
        // (purge unreliable, 7-day TTL); used only when raw is unreachable.
        const val UPDATE_URL =
            "https://cdn.jsdelivr.net/gh/breakzero39-arch/CodexQuotaWidget@main/release/latest.json"
        const val AUTO_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
        private const val KEY_LAST_CHECK = "last_check"
    }
}
