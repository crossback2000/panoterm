# Builds the Go core (tsnet + SSH + SFTP) into app/libs/core.aar with gomobile.
# Targets: arm64 (phones) + amd64 (emulator; only packaged into debug builds).
# Requires: Go 1.25+, Android SDK + NDK, JDK 17.
$ErrorActionPreference = 'Continue'   # go writes progress to stderr; rely on exit codes
$root = Split-Path $PSScriptRoot -Parent

function Invoke-Native([scriptblock]$cmd, [string]$what) {
    & $cmd
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit $LASTEXITCODE)" }
}

if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path $env:ANDROID_HOME)) { throw "Android SDK not found at $env:ANDROID_HOME" }
if (-not $env:ANDROID_NDK_HOME) {
    $ndk = Get-ChildItem (Join-Path $env:ANDROID_HOME 'ndk') -Directory -ErrorAction SilentlyContinue |
        Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } | Select-Object -Last 1
    if (-not $ndk) { throw "NDK not found under $env:ANDROID_HOME\ndk (install it with sdkmanager)" }
    $env:ANDROID_NDK_HOME = $ndk.FullName
}
Write-Host "SDK: $env:ANDROID_HOME"
Write-Host "NDK: $env:ANDROID_NDK_HOME"

$gopathBin = Join-Path (go env GOPATH) 'bin'
if (-not ($env:Path -split ';' | Where-Object { $_ -eq $gopathBin })) { $env:Path = "$gopathBin;$env:Path" }

if (-not (Get-Command gomobile -ErrorAction SilentlyContinue)) {
    Invoke-Native { go install golang.org/x/mobile/cmd/gomobile@latest 2>$null } 'install gomobile'
    Invoke-Native { go install golang.org/x/mobile/cmd/gobind@latest 2>$null } 'install gobind'
}

Push-Location (Join-Path $root 'core')
try {
    Invoke-Native { go mod download 2>$null } 'go mod download'
    Invoke-Native { go test -count=1 . } 'go test'
    Invoke-Native { gomobile init } 'gomobile init'
    New-Item -ItemType Directory -Force (Join-Path $root 'app\libs') | Out-Null
    $out = Join-Path $root 'app\libs\core.aar'
    Invoke-Native { gomobile bind '-target=android/arm64,android/amd64' -androidapi 31 '-ldflags=-s -w' -o $out . } 'gomobile bind'
}
finally { Pop-Location }
Write-Host "Built app\libs\core.aar"
