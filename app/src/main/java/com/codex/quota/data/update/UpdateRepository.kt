package com.codex.quota.data.update

import android.content.Context
import android.content.SharedPreferences
import com.codex.quota.BuildConfig
import java.io.IOException
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
        try {
            val req = Request.Builder().url(UPDATE_URL).build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            } ?: return@withContext null
            val manifest = UpdateManifest.parse(body) ?: return@withContext null
            if (manifest.versionCode > BuildConfig.VERSION_CODE) UpdateCheck.Available(manifest)
            else UpdateCheck.UpToDate
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    fun lastCheckMillis(): Long = prefs.getLong(KEY_LAST_CHECK, 0L)

    fun markChecked() {
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    /** Auto-check is throttled: at most once per interval. Manual "检查更新" always runs. */
    fun shouldAutoCheck(): Boolean =
        System.currentTimeMillis() - lastCheckMillis() >= AUTO_CHECK_INTERVAL_MS

    companion object {
        // The single fixed manifest URL. latest.json lives in the public repo at this exact path,
        // so the app always requests the same address and the apkUrl inside carries the new build.
        // Served via jsDelivr CDN: raw.githubusercontent.com is unreachable from mainland China,
        // but jsDelivr mirrors the repo and is reachable.
        const val UPDATE_URL =
            "https://cdn.jsdelivr.net/gh/breakzero39-arch/CodexQuotaWidget@main/release/latest.json"
        const val AUTO_CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000
        private const val KEY_LAST_CHECK = "last_check"
    }
}
