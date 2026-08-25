package com.codex.quota.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codex.quota.BuildConfig
import com.codex.quota.data.AccountStatus
import com.codex.quota.data.auth.CodexOAuthClient
import com.codex.quota.data.color
import com.codex.quota.data.label
import com.codex.quota.data.statusOf
import com.codex.quota.data.QuotaWindow
import com.codex.quota.ui.theme.QuotaTheme
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

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
    val refreshing by vm.refreshing.collectAsState()
    val snackbarMessage by vm.snackbar.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val updateVm: UpdateViewModel = viewModel()
    val updateState by updateVm.state.collectAsState()

    // Lightweight launch check — throttled to once/12h, never blocks or crashes startup.
    LaunchedEffect(Unit) { updateVm.autoCheck() }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeSnackbar()
        }
    }

    // The ↻ spins only while a refresh is running, then snaps back to 0.
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            while (true) {
                rotation.animateTo(rotation.value + 360f, tween(900, easing = LinearEasing))
            }
        } else {
            rotation.snapTo(0f)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Header(
                accountCount = accounts.size,
                refreshing = refreshing,
                rotationDegrees = rotation.value,
                onRefreshAll = vm::refreshAll
            )
            Spacer(Modifier.height(20.dp))

            if (login.active) {
                val loginName = accounts.firstOrNull { it.account.id == login.accountId }?.account?.displayName
                LoginCard(
                    login = login,
                    loginName = loginName,
                    onPrimary = vm::onPrimaryAction,
                    onOpenVerification = {
                        val url = login.verificationUrl ?: CodexOAuthClient.VERIFICATION_URL
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                )
                Spacer(Modifier.height(16.dp))
            }

            accounts.forEach { item ->
                AccountCard(
                    item = item,
                    onRefresh = { vm.refresh(item.account.id) },
                    onReconnect = { vm.reconnect(item.account.id) },
                    onRemove = { vm.removeAccount(item.account.id) }
                )
                Spacer(Modifier.height(12.dp))
            }

            if (accounts.isEmpty()) {
                Text(
                    "还没有账号，先添加一个",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }

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
private fun Header(
    accountCount: Int,
    refreshing: Boolean,
    rotationDegrees: Float,
    onRefreshAll: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Codex Quota Widget", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                "桌面小组件 · $accountCount 个账号",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRefreshAll, enabled = !refreshing) {
            Text("↻", fontSize = 24.sp, modifier = Modifier.graphicsLayer { rotationZ = rotationDegrees })
        }
    }
}

@Composable
private fun StatusBadge(status: AccountStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(status.color(), CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(status.label(), style = MaterialTheme.typography.labelSmall, color = status.color())
    }
}

@Composable
private fun AccountCard(
    item: AccountListItem,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onRemove: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val name = item.account.displayName ?: "Account"
    val pct = item.quota?.fiveHour?.remainingPercent?.roundToInt()
    val status = statusOf(item.sessionExpired, item.quota?.updatedAt)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                StatusBadge(status)
            }

            if (status == AccountStatus.RECONNECT) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (pct != null) "上次额度 $pct%" else "暂无额度数据",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "登录状态已失效",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onReconnect, modifier = Modifier.fillMaxWidth()) { Text("重新连接") }
            } else {
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    WindowBlock(
                        label = "5 小时",
                        window = item.quota?.fiveHour,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(16.dp))
                    WindowBlock(
                        label = "7 天",
                        window = item.quota?.sevenDay,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onRefresh) { Text("↻ 刷新") }
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("⋯", fontSize = 20.sp) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("重新连接") },
                                onClick = { menuOpen = false; onReconnect() }
                            )
                            DropdownMenuItem(
                                text = { Text("删除账号", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; confirmRemove = true }
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("删除 $name？") },
            text = { Text("将删除此设备上的登录状态和额度缓存。绑定该账号的桌面组件将停止更新。") },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun UpdateSection(state: UpdateUiState, vm: UpdateViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "App Version  v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            when (state) {
                UpdateUiState.Checking ->
                    Text("正在检查…", style = MaterialTheme.typography.bodyMedium)

                UpdateUiState.Idle, UpdateUiState.UpToDate -> {
                    if (state is UpdateUiState.UpToDate) {
                        Text("已是最新版本", style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = vm::check) { Text("检查更新") }
                }

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
private fun LoginCard(
    login: LoginUiState,
    loginName: String?,
    onPrimary: () -> Unit,
    onOpenVerification: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
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
                LoginState.CONNECTING -> Text(
                    "正在连接 ${loginName ?: "账号"}…",
                    style = MaterialTheme.typography.bodyMedium
                )
                LoginState.DISCONNECTED -> Text("未连接", style = MaterialTheme.typography.bodyMedium)
            }

            if (login.error != null && login.state != LoginState.CODE_EXPIRED) {
                Spacer(Modifier.height(8.dp))
                Text(login.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(12.dp))
            Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) {
                Text(login.buttonLabel)
            }
        }
    }
}

@Composable
private fun WindowBlock(label: String, window: QuotaWindow?, modifier: Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = window?.let { "${it.remainingPercent.roundToInt()}%" } ?: "—",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(2.dp))
        val reset = window?.resetAt
        if (reset != null) {
            Text(
                text = countdown(reset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "等待同步",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
