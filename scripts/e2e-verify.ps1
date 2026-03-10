# Picture Uploader 自動検証: 起動 → 撮影 → アップロード
# 前提: 端末はUSB接続・USBデバッグON・既にログイン済み・フォルダID設定済み
$ErrorActionPreference = "Stop"
$env:ANDROID_ADB_SERVER_PORT = "5038"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$ReportPath = Join-Path $PSScriptRoot "e2e-report.txt"

if (-not (Test-Path $adb)) { Write-Error "adb not found: $adb"; exit 1 }

function Write-Report { param($msg) $msg | Out-File -FilePath $ReportPath -Append -Encoding utf8; Write-Host $msg }
function Adb { & $adb @args }

# レポート初期化
@"
=== E2E Verification $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ===
"@ | Out-File -FilePath $ReportPath -Encoding utf8

# 1) 端末確認（複数回）
$deviceOk = $false
for ($i = 1; $i -le 10; $i++) {
    $out = Adb devices 2>&1 | Out-String
    if ($out -match "(\w+)\s+device\b") { $deviceOk = $true; Write-Report "Device: $($Matches[1])"; break }
    Start-Sleep -Milliseconds 800
}
if (-not $deviceOk) { Write-Report "FAIL: No device"; exit 1 }

# logcat をクリアしてこの実行分だけを判定する
Adb logcat -c 2>&1 | Out-Null
Write-Report "Logcat cleared."

# 2) アプリ起動
Adb shell am start -n com.pictureuploader/.MainActivity -a android.intent.action.MAIN | Out-Null
Write-Report "App launched."
Start-Sleep -Seconds 3

# 3) UIダンプで「撮影」ボタン座標取得
Adb shell "uiautomator dump /sdcard/window_dump.xml 2>/dev/null" | Out-Null
$dumpPath = Join-Path $env:TEMP "window_dump_$([Guid]::NewGuid().ToString('N').Substring(0,8)).xml"
Adb pull /sdcard/window_dump.xml $dumpPath 2>&1 | Out-Null

$tapX = $null
$tapY = $null
if (Test-Path $dumpPath) {
    $content = Get-Content $dumpPath -Raw
    if ($content) {
        if ($content -match 'resource-id="[^"]*btnCapture[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $tapX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
            $tapY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        }
        if (-not $tapX -and $content -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*resource-id="[^"]*btnCapture') {
            $tapX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
            $tapY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        }
    }
    Remove-Item $dumpPath -Force -ErrorAction SilentlyContinue
}

# フォールバック: 画面中央下（撮影ボタン付近）
if (-not $tapX) {
    $display = Adb shell "dumpsys display | grep 'mDisplayHeight\|mDisplayWidth'" 2>&1 | Out-String
    $w = 1080; $h = 1920
    if ($display -match 'mDisplayWidth.*?(\d+)') { $w = [int]$Matches[1] }
    if ($display -match 'mDisplayHeight.*?(\d+)') { $h = [int]$Matches[1] }
    $tapX = [int]($w / 2)
    $tapY = [int]($h * 0.88)
    Write-Report "Using fallback tap: $tapX,$tapY (display ${w}x${h})"
} else {
    Write-Report "Tap capture button at: $tapX,$tapY"
}

# 4) 撮影ボタンタップ
Adb shell "input tap $tapX $tapY" | Out-Null
Start-Sleep -Seconds 2

# 5) 待機してから logcat を取得（撮影・アップロードのログ）
Start-Sleep -Seconds 18
$logPath = Join-Path $env:TEMP "e2e_logcat_$([Guid]::NewGuid().ToString('N').Substring(0,8)).txt"
Adb logcat -d -s "MainActivity:*" "UploadWorker:*" "CameraHelper:*" "DriveUploader:*" "AndroidRuntime:E" 2>&1 | Out-File $logPath

# 6) 結果判定
$logContent = Get-Content $logPath -Raw -ErrorAction SilentlyContinue
$captureOk = $logContent -match "Photo captured and queued|Photo saved:"
$uploadSuccess = $logContent -match "Uploaded:.*->"
$uploadFail = $logContent -match "Upload failed \[|Result\.failure|doWork.*failure"
$runtimeError = $logContent -match "AndroidRuntime.*FATAL|Exception"

# アップロード失敗ログ（端末内）を pull
$pullLog = Join-Path $PSScriptRoot "upload_failures.log"
Adb pull /sdcard/Android/data/com.pictureuploader/files/upload_failures.log $pullLog 2>&1 | Out-Null
if (-not (Test-Path $pullLog)) { Adb pull /storage/emulated/0/Android/data/com.pictureuploader/files/upload_failures.log $pullLog 2>&1 | Out-Null }

Write-Report "--- Result ---"
Write-Report "Capture logged: $captureOk"
Write-Report "Upload success logged: $uploadSuccess"
Write-Report "Upload failure logged: $uploadFail"
Write-Report "Runtime error: $runtimeError"

$success = $captureOk -and ($uploadSuccess -or (-not $uploadFail -and -not $runtimeError))
if ($success) {
    Write-Report "PASS: Capture and upload flow completed."
    exit 0
}

# 失敗時はログ末尾をレポートに追記
Write-Report "FAIL: See log excerpts below."
$lines = Get-Content $logPath -ErrorAction SilentlyContinue | Select-Object -Last 80
if ($lines) { $lines | ForEach-Object { Write-Report $_ } }
if (Test-Path $pullLog) { Write-Report "--- upload_failures.log ---"; Get-Content $pullLog -Tail 30 | ForEach-Object { Write-Report $_ } }
exit 1
