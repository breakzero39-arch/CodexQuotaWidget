package com.codex.quota.data.update

import org.json.JSONObject

/**
 * The remote update manifest (latest.json). Parsed defensively: a malformed or incomplete
 * manifest is treated as "no update", never a crash.
 */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val changelog: List<String> = emptyList()
) {
    companion object {
        fun parse(json: String): UpdateManifest? {
            return try {
                val root = JSONObject(json)
                val code = root.optInt("versionCode", -1)
                val name = root.optString("versionName")
                val url = root.optString("apkUrl")
                val sha = root.optString("sha256")
                if (code <= 0 || name.isEmpty() || url.isEmpty() || sha.length < 32) return null
                val log = root.optJSONArray("changelog")?.let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotBlank() } }
                } ?: emptyList()
                UpdateManifest(code, name, url, sha, log)
            } catch (_: Exception) {
                null
            }
        }
    }
}
