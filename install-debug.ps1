# このプロジェクト用 adb はポート 5038 を使用（他ツールの adb v40 と競合しない）
$env:ANDROID_ADB_SERVER_PORT = "5038"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    Write-Error "adb not found at $adb"
    exit 1
}

$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) {
    Write-Error "APK not found. Run: .\gradlew.bat assembleDebug"
    exit 1
}

Write-Host "Using adb on port 5038 (no conflict with other adb on 5037)..."
& $adb start-server
& $adb install $apk
exit $LASTEXITCODE
