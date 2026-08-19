<#
.SYNOPSIS
  Cut a signed release of Codex Quota and generate release/latest.json.

.DESCRIPTION
  - versionCode is auto-incremented from the current value; versionName comes from -VersionName.
  - Uses the permanent release keystore (generated here on first run, kept OUT of git).
  - Writes release/CodexQuota-v<versionName>.apk + release/latest.json.
  - Uploading to GitHub is OPTIONAL and never blocks the build (requires the gh CLI).

.EXAMPLE
  .\release.ps1 -VersionName 1.2.0 -Changelog "修复 Widget 布局","改进多账号刷新"
#>
param(
    [Parameter(Mandatory = $true)][string]$VersionName,
    [string[]]$Changelog = @()
)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$BuildFile = Join-Path $Root 'app\build.gradle.kts'
$ReleaseDir = Join-Path $Root 'release'
$Repo = 'breakzero39-arch/CodexQuotaWidget'
# APK is served from the repo via jsDelivr CDN (China-reachable). Must be committed with
# `git add -f release/CodexQuota-v<ver>.apk` before the manifest is fetched by installed apps.
$apkUrl = "https://cdn.jsdelivr.net/gh/$Repo@main/release/CodexQuota-v$VersionName.apk"

# ---------- 1. read + bump version ----------
$content = [System.IO.File]::ReadAllText($BuildFile)
$m = [regex]::Match($content, 'versionCode = (\d+)')
if (-not $m.Success) { throw "versionCode not found in $BuildFile" }
$newCode = [int]$m.Groups[1].Value + 1
Write-Host "versionCode -> $newCode   versionName -> $VersionName"
$content = $content -replace 'versionCode = \d+', "versionCode = $newCode"
$content = $content -replace 'versionName = "[^"]*"', "versionName = `"$VersionName`""
[System.IO.File]::WriteAllText($BuildFile, $content, (New-Object System.Text.UTF8Encoding($false)))

# ---------- 2. signing (generate the permanent keystore on first run) ----------
$propsFile = Join-Path $Root 'keystore.properties'
if (-not (Test-Path $propsFile)) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\keytool.exe'))) {
        $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    } elseif (Get-Command keytool -ErrorAction SilentlyContinue) {
        $keytool = (Get-Command keytool).Source
    } else {
        throw "keytool not found: set JAVA_HOME or add a JDK to PATH"
    }
    $ksDir = Join-Path $Root 'keystore'
    New-Item -ItemType Directory -Force $ksDir | Out-Null
    $store = Join-Path $ksDir 'codex-release.jks'
    $pw = -join ((48..57) + (97..122) | Get-Random -Count 32 | ForEach-Object { [char]$_ })
    & $keytool -genkeypair -v -keystore $store -alias codex -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass $pw -keypass $pw `
        -dname "CN=Codex Quota, OU=Dev, O=breakzero39, C=CN" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "keytool failed to create keystore" }
    @(
        # forward slashes — Properties.load() would eat the backslash as an escape
        "storeFile=keystore/codex-release.jks",
        "storePassword=$pw",
        "keyAlias=codex",
        "keyPassword=$pw"
    ) | Set-Content -Path $propsFile -Encoding ascii
    Write-Host "Generated permanent release keystore: $store"
    Write-Host "WARNING: back up keystore\ + keystore.properties. Losing them means the installed"
    Write-Host "         app can never be updated in place again."
}

# ---------- 3. build signed release APK ----------
$gradle = $null
if ($env:GRADLE_HOME -and (Test-Path (Join-Path $env:GRADLE_HOME 'bin\gradle.bat'))) {
    $gradle = Join-Path $env:GRADLE_HOME 'bin\gradle.bat'
} elseif (Test-Path 'D:\tools\gradle-8.11.1\bin\gradle.bat') {
    $gradle = 'D:\tools\gradle-8.11.1\bin\gradle.bat'
} else {
    $cmd = Get-Command gradle -ErrorAction SilentlyContinue
    if ($cmd) { $gradle = $cmd.Source }
}
if (-not $gradle) { throw "gradle not found: set GRADLE_HOME or add gradle to PATH" }
Write-Host "Building with $gradle ..."
Push-Location $Root
& $gradle assembleRelease
$rc = $LASTEXITCODE
Pop-Location
if ($rc -ne 0) { throw "assembleRelease failed (exit $rc)" }

$apk = Join-Path $Root 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw "release APK not found: $apk" }

# ---------- 4. verify APK signature ----------
$sdk = $null
if ($env:ANDROID_HOME) { $sdk = $env:ANDROID_HOME }
elseif (Test-Path 'D:\Android\Sdk') { $sdk = 'D:\Android\Sdk' }
$apksigner = $null
if ($sdk) {
    $bt = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($bt) { $apksigner = Join-Path $bt.FullName 'apksigner.bat' }
}
if ($apksigner -and (Test-Path $apksigner)) {
    & $apksigner verify --print-certs $apk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification FAILED" }
    Write-Host "APK signature verified (permanent release key)"
} else {
    Write-Host "WARNING: apksigner not found — signature not verified"
}

# ---------- 5. sha256 + rename into release/ ----------
New-Item -ItemType Directory -Force $ReleaseDir | Out-Null
$renamed = Join-Path $ReleaseDir "CodexQuota-v$VersionName.apk"
Copy-Item $apk $renamed -Force
$sha = (Get-FileHash $renamed -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "SHA-256: $sha"

# ---------- 6. latest.json ----------
$manifest = [ordered]@{
    versionCode = $newCode
    versionName = $VersionName
    apkUrl      = $apkUrl
    sha256      = $sha
    changelog   = @($Changelog)
}
$json = $manifest | ConvertTo-Json -Depth 3
[System.IO.File]::WriteAllText((Join-Path $ReleaseDir 'latest.json'), $json, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "latest.json written to $ReleaseDir\latest.json"

# ---------- 7. optional GitHub Release ----------
if (Get-Command gh -ErrorAction SilentlyContinue) {
    $tag = "v$VersionName"
    $notes = if ($Changelog.Count -gt 0) { $Changelog -join "`n" } else { "Release v$VersionName" }
    Write-Host "Creating GitHub Release $tag ..."
    gh release create $tag $renamed --repo $Repo --title "v$VersionName" --notes $notes
    if ($LASTEXITCODE -ne 0) {
        Write-Host "WARNING: gh release create failed — the APK build succeeded; upload manually."
    }
} else {
    Write-Host "gh CLI not installed — skipped GitHub upload. APK ready at: $renamed"
}

Write-Host ""
Write-Host "Done. Commit these BEFORE installed apps can fetch the update:"
Write-Host "  git add -f release/CodexQuota-v$VersionName.apk"
Write-Host "  git rm --ignore-unmatch release/CodexQuota-v*.apk (previous release, keeps repo small)"
Write-Host "  git add release/latest.json && git commit && git push"
Write-Host "Release:  $renamed"
Write-Host "Manifest: $(Join-Path $ReleaseDir 'latest.json')"
