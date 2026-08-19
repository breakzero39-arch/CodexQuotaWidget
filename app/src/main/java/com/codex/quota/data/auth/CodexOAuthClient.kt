package com.codex.quota.data.auth

import android.util.Base64
import com.codex.quota.data.QuotaError
import com.codex.quota.data.QuotaException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

data class DeviceCode(
    val deviceAuthId: String,
    val userCode: String,
    val intervalSeconds: Long,
    val verificationUrl: String,
    val expiresInSeconds: Long
)

data class PkceCode(val code: String, val verifier: String)

/**
 * OpenAI "Sign in with ChatGPT" device-code flow, mirrored from the openai/codex CLI
 * (codex-rs/login). The official page does the login; we only exchange codes for tokens.
 */
class CodexOAuthClient(private val http: OkHttpClient) {

    suspend fun requestUserCode(): DeviceCode = withContext(Dispatchers.IO) {
        val body = JSONObject().put("client_id", CLIENT_ID).toString().toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url("$ISSUER/api/accounts/deviceauth/usercode")
            .post(body)
            .build()
        execute(req).use {
            if (!it.isSuccessful) throw QuotaException(QuotaError.NETWORK, "usercode http ${it.code}")
            val json = JSONObject(it.body!!.string())
            val userCode = json.optString("user_code").ifEmpty { json.optString("usercode") }
            DeviceCode(
                deviceAuthId = json.getString("device_auth_id"),
                userCode = userCode,
                intervalSeconds = json.optString("interval", "5").toLongOrNull() ?: 5L,
                verificationUrl = json.optString("verification_uri").ifEmpty { VERIFICATION_URL },
                expiresInSeconds = json.optLong("expires_in", 900L)
            )
        }
    }

    /** @return the PKCE code once authorized, or null while the user has not finished yet. */
    suspend fun pollOnce(device: DeviceCode): PkceCode? = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("device_auth_id", device.deviceAuthId)
            .put("user_code", device.userCode)
            .toString().toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url("$ISSUER/api/accounts/deviceauth/token")
            .post(body)
            .build()
        execute(req).use {
            when (it.code) {
                200 -> {
                    val json = JSONObject(it.body!!.string())
                    PkceCode(
                        code = json.getString("authorization_code"),
                        verifier = json.getString("code_verifier")
                    )
                }
                403, 404 -> null // still pending authorization
                else -> throw QuotaException(QuotaError.NETWORK, "poll http ${it.code}")
            }
        }
    }

    suspend fun exchange(code: String, verifier: String): CodexTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", CLIENT_ID)
            .add("code_verifier", verifier)
            .build()
        val req = Request.Builder()
            .url("$ISSUER/oauth/token")
            .post(form)
            .build()
        execute(req).use {
            if (!it.isSuccessful) throw QuotaException(QuotaError.NETWORK, "exchange http ${it.code}")
            parseTokens(it.body!!.string())
        }
    }

    suspend fun refresh(refreshToken: String): CodexTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
            .build()
        val req = Request.Builder()
            .url("$ISSUER/oauth/token")
            .post(form)
            .build()
        execute(req).use {
            if (!it.isSuccessful) throw QuotaException(QuotaError.SESSION_EXPIRED, "refresh http ${it.code}")
            val json = JSONObject(it.body!!.string())
            val (accountId, email) = decodeIdentity(json.optString("id_token"))
            CodexTokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").ifEmpty { refreshToken },
                accountId = accountId,
                email = email
            )
        }
    }

    private fun parseTokens(body: String): CodexTokens {
        val json = JSONObject(body)
        val (accountId, email) = decodeIdentity(json.optString("id_token"))
        return CodexTokens(
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            accountId = accountId,
            email = email
        )
    }

    private fun execute(req: Request): Response = try {
        http.newCall(req).execute()
    } catch (e: IOException) {
        throw QuotaException(QuotaError.NETWORK, e.message)
    }

    private fun decodeIdentity(idToken: String?): Pair<String?, String?> {
        if (idToken.isNullOrEmpty()) return null to null
        return try {
            val payload = idToken.split(".")[1]
            val bytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val json = JSONObject(String(bytes))
            json.optString("chatgpt_account_id").takeIf { it.isNotEmpty() } to
                json.optString("email").takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null to null
        }
    }

    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val VERIFICATION_URL = "$ISSUER/codex/device"
        const val REDIRECT_URI = "$ISSUER/deviceauth/callback"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
