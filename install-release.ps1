# 本番（リリース）APK を端末にインストールする（adb はポート 5038 で競合回避）
$env:ANDROID_ADB_SERVER_PORT = "5038"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    Write-Error "adb not found at $adb"
    exit 1
}

$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) {
    Write-Error "Release APK not found. Run: .\build-release.ps1"
    exit 1
}

Write-Host "Using adb on port 5038. Installing release APK..."
& $adb start-server
& $adb install -r $apk
exit $LASTEXITCODE
