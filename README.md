# Codex Quota Widget

Android 桌面小组件：在桌面上直接显示你的 **Codex / ChatGPT 账号额度**。支持多账号，每个小组件独立绑定一个账号，互不干扰。

## 功能

- **桌面实时额度** — 剩余百分比、电量柱式 Gauge、下次重置倒计时、Bonus 额度、Live / Disconnected 状态徽标；额度低时 Live 圆点会"呼吸"提示
- **多账号隔离** — 同一 App 登录多个账号，每个小组件只显示它绑定的账号；A 账号过期不影响 B
- **点击进入 App** — 点击小组件打开应用，App 内可一键刷新全部或单个账号
- **自动刷新** — WorkManager 每 30 分钟后台拉取一次真实额度
- **安全存储** — 每个账号的 access token 独立加密存放，绝不落盘明文、不打日志、不进 Git
- **内置更新** — App 内检查更新并覆盖安装，jsDelivr CDN 分发（国内可直连），数据/账号/绑定全程保留

## 界面预览

深色卡片风格，结构：标题行（`Codex · <账号名>` + 状态徽标）→ 大号百分比 → Gauge → 重置倒计时。

| 状态 | 显示 |
|---|---|
| 已连接 | 绿点 + **Live**，显示真实额度 |
| 额度低 | Live 圆点呼吸动画（host 侧 ViewFlipper，不耗小组件更新配额） |
| 未绑定 / 会话过期 | 红点 + **Disconnected**，`--` 占位 |

## 技术栈

| 组件 | 说明 |
|---|---|
| Kotlin + Jetpack Glance 1.1.1 | 小组件 UI（GlanceAppWidget + RemoteViews） |
| WorkManager 2.9 | 30 分钟周期刷新 + 账号级 one-shot 刷新 |
| DataStore (Preferences) | 账号元数据、widget ↔ 账号绑定 |
| EncryptedSharedPreferences | access token，每账号独立文件 `codex_auth_<accountId>` |
| OkHttp 4.12 | 登录 / 额度 / 更新网络请求 |
| Jetpack Compose | MainActivity 配置页 |
| AGP 8 · minSdk 24 · targetSdk 35 · JVM 17 | 构建（启用 core library desugaring） |

## 工作原理

- **登录**：OAuth **device code** 流程（`auth.openai.com`），App 显示验证码，用户在浏览器手动授权
- **额度**：`GET https://chatgpt.com/backend-api/wham/usage`（Bearer OAuth）。该接口**未公开**，代码做了防御式解析，接口变动时优雅降级
- **更新**：读取仓库 `release/latest.json`（经 jsDelivr CDN），校验 SHA-256 后调系统安装器覆盖安装

## 使用

1. 安装后打开 App → `+ 添加账号` → 按提示完成授权（每个账号各走一次）
2. 长按桌面空白处 → **小组件** → **Codex Quota**
3. 选择要绑定的账号 → 添加到桌面
4. 想看另一个账号？再拖一个小组件，绑另一个账号即可

## 构建

前置：JDK 17、Android SDK、Gradle 8.11.1（本项目未内置 wrapper）。

```bash
# local.properties 配置 sdk.dir（例如 D\:\\Android\\Sdk）
gradle assembleDebug      # 调试包
gradle assembleRelease    # 发布包（无签名密钥时为未签名）
```

发布签名密钥在 `keystore.properties`（已 gitignore），由 `release.ps1` 首次运行自动生成。**务必备份 `keystore/` 目录和 `keystore.properties`**——永久密钥一旦丢失，已安装用户将永远无法原地升级。

## 发布新版本

```powershell
.\release.ps1 -VersionName 1.3.0 -Changelog "修复 xxx","新增 xxx"
```

脚本自动完成：`versionCode` +1 → 签名构建 → apksigner 校验 → 生成 SHA-256 → 写 `release/latest.json` → 创建 GitHub Release。随后手动提交并推送（APK 用 `git add -f`，旧 APK 删掉保持仓库小）：

```bash
git add -f release/CodexQuota-v1.3.0.apk
git rm --ignore-unmatch release/CodexQuota-v<旧版本>.apk
git add release/latest.json app/build.gradle.kts && git commit && git push
```

jsDelivr 会缓存 `latest.json`，推送后需清缓存（被限流则约 15 分钟后重试）：

```bash
curl "https://purge.jsdelivr.net/gh/breakzero39-arch/CodexQuotaWidget@main/release/latest.json"
```

## 目录结构

```
app/src/main/java/com/codex/quota/
├── QuotaApp.kt                  # Application + 依赖容器（AppContainer）
├── data/
│   ├── AccountStore.kt          # DataStore：账号元数据 + widget 绑定
│   ├── ChatGptQuotaRepository.kt# 额度仓储：SESSION_EXPIRED 自动 refresh-token 重试
│   ├── CodexUsageClient.kt      # wham/usage 额度接口
│   ├── auth/                    # device flow 登录 + 加密 token 存储
│   └── update/                  # 内置更新（latest.json 校验 + 安装）
├── widget/
│   ├── CodexQuotaWidget.kt      # Glance 小组件渲染 + 点击动作
│   └── RefreshReceiver.kt       # 小组件点击刷新接收器
├── work/                        # WorkManager：周期/全量/单账号刷新 + 调度
└── ui/                          # MainActivity（Compose）、WidgetConfigActivity（绑定配置页）
```

## 安全说明

- access token 只存在每账号独立的加密文件里；不写日志、不进 UI、不进 Git
- 发布签名密钥、GitHub token 永不入库（`keystore.properties` 已 gitignore）
- 额度接口非官方，仅供个人学习使用
