package com.codex.quota.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codex.quota.QuotaApp
import com.codex.quota.data.update.UpdateCheck
import com.codex.quota.data.update.UpdateManifest
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** UI state of the in-app update flow. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val manifest: UpdateManifest, val fromAuto: Boolean = false) : UpdateUiState
    data class Downloading(val manifest: UpdateManifest, val progress: Float) : UpdateUiState
    data class Ready(
        val manifest: UpdateManifest,
        val file: File,
        val permissionHint: Boolean = false
    ) : UpdateUiState
    data class Error(val message: String, val manifest: UpdateManifest?) : UpdateUiState
}

/**
 * Owns check → download → verify → system install. Completely independent of the quota side:
 * a check or download failure only affects the update section, never the widget or accounts.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as QuotaApp).container
    private val repo = container.updateRepository
    private val installer = container.updateInstaller

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state

    private var downloadJob: Job? = null

    /** Manual check — always hits the network regardless of the 12h throttle. */
    fun check() {
        if (_state.value is UpdateUiState.Checking) return
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            val result = repo.check()
            repo.markChecked()
            _state.value = when (result) {
                is UpdateCheck.Available -> UpdateUiState.Available(result.manifest, fromAuto = false)
                UpdateCheck.UpToDate -> UpdateUiState.UpToDate
                null -> UpdateUiState.Error("无法连接更新服务器，请稍后重试", null)
            }
        }
    }

    /** Launch-time check, throttled to once per 12h, never blocks startup. */
    fun autoCheck() {
        if (!repo.shouldAutoCheck()) return
        if (_state.value !is UpdateUiState.Idle) return
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            val result = repo.check()
            repo.markChecked()
            when (result) {
                is UpdateCheck.Available -> _state.value = UpdateUiState.Available(result.manifest, fromAuto = true)
                UpdateCheck.UpToDate -> _state.value = UpdateUiState.UpToDate // show "已是最新版本"
                null -> _state.value = UpdateUiState.Idle // failed → no notice, leave the manual button
            }
        }
    }

    fun download() {
        val available = _state.value as? UpdateUiState.Available ?: return
        startDownload(available.manifest)
    }

    private fun startDownload(manifest: UpdateManifest) {
        downloadJob?.cancel()
        _state.value = UpdateUiState.Downloading(manifest, 0f)
        downloadJob = viewModelScope.launch {
            val file = installer.download(manifest) { p ->
                _state.value = UpdateUiState.Downloading(manifest, p.coerceIn(0f, 1f))
            }
            if (file == null) {
                _state.value = UpdateUiState.Error("下载失败，请重试", manifest)
                return@launch
            }
            val actual = installer.sha256(file)
            if (actual == null || !actual.equals(manifest.sha256, ignoreCase = true)) {
                file.delete()
                _state.value = UpdateUiState.Error("更新包校验失败，请重新下载", manifest)
                return@launch
            }
            _state.value = UpdateUiState.Ready(manifest, file)
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        val st = _state.value
        if (st is UpdateUiState.Downloading) {
            _state.value = UpdateUiState.Available(st.manifest, fromAuto = false)
        }
    }

    fun retry() {
        when (val st = _state.value) {
            is UpdateUiState.Error -> if (st.manifest != null) {
                _state.value = UpdateUiState.Available(st.manifest, fromAuto = false)
                download()
            } else {
                check()
            }
            is UpdateUiState.Ready -> if (st.permissionHint) install() else install()
            else -> Unit
        }
    }

    fun install() {
        val ready = _state.value as? UpdateUiState.Ready ?: return
        if (installer.canRequestInstall()) {
            installer.installApk(ready.file)
            // keep Ready so returning from the installer keeps the section actionable
        } else {
            _state.value = ready.copy(permissionHint = true)
            installer.openInstallSettings()
        }
    }
}
