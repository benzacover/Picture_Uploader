# リリースAPKをビルドする（keystore.properties があればそのまま実行）
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

$keystoreProps = Join-Path $root "keystore.properties"
if (-not (Test-Path $keystoreProps)) {
    $example = Join-Path $root "keystore.properties.example"
    if (Test-Path $example) {
        Copy-Item $example $keystoreProps
        Write-Host "keystore.properties を例から作成しました。パスワードを編集してから再度 .\build-release.ps1 を実行してください。"
        exit 1
    }
    Write-Error "keystore.properties が見つかりません。keystore.properties.example をコピーして keystore.properties を作成し、パスワードを設定してください。"
    exit 1
}

Push-Location $root
try {
    & .\gradlew.bat assembleRelease
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
    Write-Host ""
    Write-Host "ビルド完了: $apk"
    Write-Host "インストール: .\install-release.ps1"
} finally {
    Pop-Location
}
