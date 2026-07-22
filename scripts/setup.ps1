# Internet Banking - 초기 셋업 (구 setup.bat)
# 사용: 루트에서  .\start.ps1 -Mode setup
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Internet Banking - Initial Setup" -ForegroundColor Cyan
Write-Host "============================================================`n"

# [1/4] 필수 도구 확인
Write-Host "[1/4] Checking prerequisites..." -ForegroundColor Yellow
foreach ($t in @("docker", "java", "node")) {
    if (-not (Get-Command $t -ErrorAction SilentlyContinue)) {
        Write-Host "[ERROR] $t not found. Install it first." -ForegroundColor Red
        return
    }
}
docker info 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Docker Desktop is not running. Please start it first." -ForegroundColor Red
    return
}
Write-Host "  OK: Docker / Java / Node.js"

# [2/4] .env
Write-Host "`n[2/4] Setting up .env ..." -ForegroundColor Yellow
$envFile = Join-Path $root ".env"
if (-not (Test-Path $envFile)) {
    Copy-Item (Join-Path $root ".env.sample") $envFile
    (Get-Content $envFile) -replace 'REDIS_HOST=redis', 'REDIS_HOST=localhost' | Set-Content $envFile
    Write-Host "  Copied .env.sample -> .env (REDIS_HOST=localhost for local bootRun)"
} else {
    Write-Host "  .env already exists - skipped"
}

# [3/4] 로그 디렉터리
Write-Host "`n[3/4] Creating log directory..." -ForegroundColor Yellow
if (-not (Test-Path "C:\logs\internet-banking")) {
    New-Item -ItemType Directory -Force "C:\logs\internet-banking" | Out-Null
    Write-Host "  Created C:\logs\internet-banking"
} else {
    Write-Host "  Log directory already exists - skipped"
}

# [4/4] web 의존성
Write-Host "`n[4/4] Installing web dependencies..." -ForegroundColor Yellow
if (-not (Test-Path (Join-Path $root "web\node_modules"))) {
    Push-Location (Join-Path $root "web")
    npm install
    $ok = $LASTEXITCODE -eq 0
    Pop-Location
    if (-not $ok) { Write-Host "[ERROR] npm install failed" -ForegroundColor Red; return }
    Write-Host "  npm install done"
} else {
    Write-Host "  node_modules already exists - skipped"
}

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host " Setup complete!  Run  .\start.ps1  to launch services." -ForegroundColor Green
Write-Host "============================================================"
