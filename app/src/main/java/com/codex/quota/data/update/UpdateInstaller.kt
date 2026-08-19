package com.codex.quota.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads the release APK into app-internal storage, verifies SHA-256, then hands the file
 * to the system Package Installer via a FileProvider content:// URI — no file://, and no extra
 * storage permission. The install confirmation is always the system dialog, never bypassed.
 */
class UpdateInstaller(
    private val context: Context,
    private val http: OkHttpClient
) {
    private val updateDir: File
        get() = File(context.filesDir, "updates").apply { mkdirs() }

    /** Streams the APK into internal storage, reporting 0f..1f progress. null on failure. */
    suspend fun download(manifest: UpdateManifest, onProgress: (Float) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val target = File(updateDir, "CodexQuota-v${manifest.versionName}.apk")
            try {
                http.newCall(Request.Builder().url(manifest.apkUrl).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body ?: return@withContext null
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        target.outputStream().buffered().use { out ->
                            val buf = ByteArray(8192)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) onProgress(done.toFloat() / total)
                            }
                        }
                    }
                }
                target
            } catch (_: Exception) {
                target.delete()
                null
            }
        }

    fun sha256(file: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    /** Below Android 8 there is no per-app toggle; treat those as installable. */
    fun canRequestInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system "install unknown apps" screen for this app. */
    fun openInstallSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(fallback) } catch (_: Exception) { }
        }
    }

    /** Launches the system Package Installer for the verified APK. */
    fun installApk(file: File): Boolean = try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
