# Builds the Go core (tsnet + SSH) into app/libs/core.aar with gomobile.
# Requires: Go 1.25+, Android SDK + NDK (Android Studio > SDK Manager > SDK Tools > NDK).
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent

if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path $env:ANDROID_HOME)) { throw "Android SDK not found at $env:ANDROID_HOME" }
if (-not $env:ANDROID_NDK_HOME) {
    $ndk = Get-ChildItem (Join-Path $env:ANDROID_HOME 'ndk') -Directory -ErrorAction SilentlyContinue |
        Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } | Select-Object -Last 1
    if (-not $ndk) { throw "NDK not found under $env:ANDROID_HOME\ndk (install it from the SDK Manager)" }
    $env:ANDROID_NDK_HOME = $ndk.FullName
}
Write-Host "SDK: $env:ANDROID_HOME"
Write-Host "NDK: $env:ANDROID_NDK_HOME"

$gopathBin = Join-Path (go env GOPATH) 'bin'
if (-not ($env:Path -split ';' | Where-Object { $_ -eq $gopathBin })) { $env:Path = "$gopathBin;$env:Path" }

if (-not (Get-Command gomobile -ErrorAction SilentlyContinue)) {
    go install golang.org/x/mobile/cmd/gomobile@latest
    go install golang.org/x/mobile/cmd/gobind@latest
}

Push-Location (Join-Path $root 'core')
try {
    go get tailscale.com@latest golang.org/x/crypto@latest golang.org/x/mobile@latest
    go mod tidy
    gomobile init
    New-Item -ItemType Directory -Force (Join-Path $root 'app\libs') | Out-Null
    gomobile bind -target=android/arm64 -androidapi 31 -ldflags='-s -w' -o (Join-Path $root 'app\libs\core.aar') .
    if ($LASTEXITCODE -ne 0) { throw "gomobile bind failed" }
}
finally { Pop-Location }
Write-Host "Built app\libs\core.aar"
