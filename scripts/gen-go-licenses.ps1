# Generates the license list for every Go module linked into core.aar
# (Tailscale pulls in many transitive deps: wireguard-go, gVisor, klauspost/compress, ...).
# Output:
#   app/src/main/assets/licenses/go-modules.csv     module, license URL, license type
#   app/src/main/assets/licenses/go/<module>/...     full license/notice texts
# Re-run whenever Go deps change.
$ErrorActionPreference = 'Continue'   # go writes progress to stderr; rely on exit codes
$root = Split-Path $PSScriptRoot -Parent
$lic  = Join-Path $root 'app\src\main\assets\licenses'
New-Item -ItemType Directory -Force $lic | Out-Null

function Invoke-Native([scriptblock]$cmd, [string]$what) {
    & $cmd
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit $LASTEXITCODE)" }
}

$gopathBin = Join-Path (go env GOPATH) 'bin'
if (-not ($env:Path -split ';' | Where-Object { $_ -eq $gopathBin })) { $env:Path = "$gopathBin;$env:Path" }
if (-not (Get-Command go-licenses -ErrorAction SilentlyContinue)) {
    Invoke-Native { go install github.com/google/go-licenses/v2@latest 2>$null } 'go install go-licenses'
}

Push-Location (Join-Path $root 'core')
try {
    # Analyse the Android build, which is what actually ships.
    $env:GOOS = 'android'; $env:GOARCH = 'arm64'; $env:CGO_ENABLED = '0'
    $csv = go-licenses report . --ignore panoterm/core 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'go-licenses report failed' }
    $csv | Sort-Object | Out-File -Encoding utf8 (Join-Path $lic 'go-modules.csv')
    $save = Join-Path $lic 'go'
    if (Test-Path $save) { Remove-Item -Recurse -Force $save }
    go-licenses save . --ignore panoterm/core --save_path $save --force 2>$null
    if ($LASTEXITCODE -ne 0) { Write-Warning 'go-licenses save reported problems (some modules may lack license files)' }
    # Fail loudly on licenses that would need source disclosure or are unknown.
    go-licenses check . --ignore panoterm/core --disallowed_types=forbidden,restricted,unknown 2>&1 | Select-Object -Last 5
    if ($LASTEXITCODE -ne 0) { throw 'go-licenses check found a forbidden/restricted/unknown license - review before publishing' }
}
finally {
    Remove-Item Env:GOOS, Env:GOARCH, Env:CGO_ENABLED -ErrorAction SilentlyContinue
    Pop-Location
}
Write-Host "Wrote $lic\go-modules.csv and $lic\go\"
