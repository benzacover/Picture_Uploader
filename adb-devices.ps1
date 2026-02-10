# このプロジェクト用 adb はポート 5038 を使用（他ツールの adb と競合しない）
$env:ANDROID_ADB_SERVER_PORT = "5038"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    Write-Error "adb not found at $adb"
    exit 1
}

Write-Host "Using adb on port 5038..."
& $adb start-server
& $adb devices
