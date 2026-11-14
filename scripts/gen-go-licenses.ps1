# Generates the license list for every Go module linked into core.aar
# (Tailscale pulls in many transitive deps: wireguard-go, gVisor, klauspost/compress, ...).
# Output:
#   app/src/main/assets/licenses/go-modules.csv     module, license URL, license type
#   app/src/main/assets/licenses/go/<module>/...     full license/notice texts
# Run after build-core.ps1 (needs go.sum populated). Re-run whenever Go deps change.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$lic  = Join-Path $root 'app\src\main\assets\licenses'
New-Item -ItemType Directory -Force $lic | Out-Null

$gopathBin = Join-Path (go env GOPATH) 'bin'
if (-not ($env:Path -split ';' | Where-Object { $_ -eq $gopathBin })) { $env:Path = "$gopathBin;$env:Path" }
if (-not (Get-Command go-licenses -ErrorAction SilentlyContinue)) {
    go install github.com/google/go-licenses/v2@latest
}

Push-Location (Join-Path $root 'core')
try {
    # Analyse the Android build, which is what actually ships.
    $env:GOOS = 'android'; $env:GOARCH = 'arm64'; $env:CGO_ENABLED = '1'
    go-licenses report . | Out-File -Encoding utf8 (Join-Path $lic 'go-modules.csv')
    $save = Join-Path $lic 'go'
    if (Test-Path $save) { Remove-Item -Recurse -Force $save }
    go-licenses save . --save_path $save
    # Fail loudly on licenses that would need source disclosure.
    go-licenses check . --disallowed_types=forbidden,restricted
    if ($LASTEXITCODE -ne 0) { throw 'go-licenses check found a forbidden/restricted license - review before publishing' }
}
finally {
    Remove-Item Env:GOOS, Env:GOARCH, Env:CGO_ENABLED -ErrorAction SilentlyContinue
    Pop-Location
}
Write-Host "Wrote $lic\go-modules.csv and $lic\go\"
