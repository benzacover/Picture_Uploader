# 端末内のアップロード失敗ログをプロジェクトルートに pull する（ポート 5038）
$env:ANDROID_ADB_SERVER_PORT = "5038"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$remote = "/sdcard/Android/data/com.pictureuploader/files/upload_failures.log"
$local = Join-Path $PSScriptRoot "upload_failures.log"
if (-not (Test-Path $adb)) { Write-Error "adb not found"; exit 1 }
& $adb pull $remote $local 2>&1
if (Test-Path $local) { Get-Content $local -Tail 150 }
