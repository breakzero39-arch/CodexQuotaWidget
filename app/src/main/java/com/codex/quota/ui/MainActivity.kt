package com.codex.quota.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codex.quota.BuildConfig
import com.codex.quota.data.auth.CodexOAuthClient
import com.codex.quota.ui.theme.QuotaTheme
import java.time.Duration
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            QuotaTheme {
                ConfigScreen()
            }
        }
    }
}

@Composable
private fun ConfigScreen() {
    val context = LocalContext.current
    val vm: AccountsViewModel = viewModel()
    val accounts by vm.accounts.collectAsState()
    val login by vm.login.collectAsState()

    val updateVm: UpdateViewModel = viewModel()
    val updateState by updateVm.state.collectAsState()

    // Lightweight launch check — throttled to once/12h, never blocks or crashes startup.
    LaunchedEffect(Unit) { updateVm.autoCheck() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Text("Codex Quota Widget", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("桌面小组件 · 多账号真实额度", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(24.dp))

            if (login.active) {
                LoginCard(
                    login = login,
                    onPrimary = vm::onPrimaryAction,
                    onOpenVerification = {
                        val url = login.verificationUrl ?: CodexOAuthClient.VERIFICATION_URL
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                )
                Spacer(Modifier.height(16.dp))
            }

            Button(onClick = vm::refreshAll, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh All")
            }

            accounts.forEach { item ->
                Spacer(Modifier.height(12.dp))
                AccountRow(
                    item = item,
                    onRefresh = { vm.refresh(item.account.id) },
                    onReconnect = { vm.reconnect(item.account.id) },
                    onRemove = { vm.removeAccount(item.account.id) }
                )
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = vm::addAccount, modifier = Modifier.fillMaxWidth()) {
                Text("+ 添加账号")
            }

            Spacer(Modifier.height(32.dp))
            Text("如何添加小组件", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "长按主屏幕空白处 → 小组件 → Codex Quota → 选择要绑定的账号，再添加到桌面。",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))
            UpdateSection(state = updateState, vm = updateVm)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun UpdateSection(state: UpdateUiState, vm: UpdateViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "App Version  v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            when (state) {
                UpdateUiState.Idle, UpdateUiState.Checking ->
                    Text("正在检查…", style = MaterialTheme.typography.bodyMedium)

                UpdateUiState.UpToDate ->
                    Text("已是最新版本", style = MaterialTheme.typography.bodyMedium)

                is UpdateUiState.Available -> {
                    val m = state.manifest
                    if (state.fromAuto && !expanded) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("发现新版本 v${m.versionName}", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { expanded = true }) { Text("查看") }
                        }
                    } else {
                        Text(
                            "发现新版本 v${m.versionName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        m.changelog.forEach { line ->
                            Text("• $line", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = vm::download, modifier = Modifier.fillMaxWidth()) { Text("下载并更新") }
                    }
                }

                is UpdateUiState.Downloading -> {
                    Text(
                        "Downloading ${(state.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = vm::cancelDownload) { Text("取消") }
                }

                is UpdateUiState.Ready -> {
                    Text("更新包已就绪 v${state.manifest.versionName}", style = MaterialTheme.typography.bodyMedium)
                    if (state.permissionHint) {
                        Text(
                            "请允许“Codex Quota”安装未知应用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = vm::install,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.permissionHint) "去授权后继续" else "安装更新") }
                }

                is UpdateUiState.Error -> {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::retry) { Text("重试") }
                }
            }
        }
    }
}

@Composable
private fun LoginCard(login: LoginUiState, onPrimary: () -> Unit, onOpenVerification: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Codex Login", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))

            when (login.state) {
                LoginState.WAITING_FOR_AUTH -> {
                    Text("连接 ChatGPT", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(login.userCode.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onOpenVerification, modifier = Modifier.fillMaxWidth()) {
                        Text("打开授权页面")
                    }
                }
                LoginState.CODE_EXPIRED -> Text("验证码已过期", style = MaterialTheme.typography.bodyMedium)
                LoginState.LOGIN_FAILED -> Text("登录失败", style = MaterialTheme.typography.bodyMedium)
                LoginState.CONNECTED -> Text("已连接", style = MaterialTheme.typography.bodyMedium)
                LoginState.CONNECTING -> Text("正在获取验证码…", style = MaterialTheme.typography.bodyMedium)
                LoginState.DISCONNECTED -> Text("未连接", style = MaterialTheme.typography.bodyMedium)
            }

            login.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(12.dp))
            Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) {
                Text(login.buttonLabel)
            }
        }
    }
}

@Composable
private fun AccountRow(
    item: AccountListItem,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.account.displayName ?: "Account",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.width(8.dp))
                if (item.sessionExpired) {
                    Text(
                        text = "Session expired",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            val quota = item.quota
            if (quota != null) {
                Text("剩余 ${quota.remainingPercent.toInt()}%", style = MaterialTheme.typography.bodyMedium)
                quota.resetAt?.let {
                    Text(
                        "距离下次重置 ${countdown(it)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = "等待首次同步",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRefresh) { Text("刷新") }
                TextButton(onClick = onReconnect) { Text("重新连接") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRemove) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun countdown(resetAt: Instant): String {
    val d = Duration.between(Instant.now(), resetAt)
    if (d.isNegative) return "已重置"
    val totalHours = d.toHours()
    val days = totalHours / 24
    val hours = totalHours % 24
    return if (days > 0) "${days}d ${hours}h" else "${totalHours}h ${d.toMinutesPart()}m"
}
