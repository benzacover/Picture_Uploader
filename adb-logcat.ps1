# このプロジェクト用 adb（ポート 5038）で logcat を表示する
$env:ANDROID_ADB_SERVER_PORT = "5038"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    Write-Error "adb not found at $adb"
    exit 1
}

Write-Host "Using adb on port 5038. Connect device via USB (USB debugging ON), then run this script."
Write-Host "To capture upload errors: trigger upload in the app, then check DriveUploader / UploadWorker lines below."
& $adb logcat -s "DriveUploader:*" "UploadWorker:*" "AuthManager:*" "AndroidRuntime:E"
