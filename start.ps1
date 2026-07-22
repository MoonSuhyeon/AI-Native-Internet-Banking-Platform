# =============================================================================
# Internet Banking — 로컬 개발 실행 진입점 (단일 디스패처)
#
#   .\start.ps1                → 전체 백엔드 + 에이전트 + web   (기본: full)
#   .\start.ps1 -Mode quick    → 빠른 서브셋(customer·gateway·deposit·consultation) + web
#   .\start.ps1 -Mode setup    → 초기 셋업(.env·로그디렉터리·npm install·사전점검)
#
# 실제 로직은 scripts/ 아래에 목적별로 분리:
#   scripts/dev-full.ps1  ·  scripts/dev-quick.ps1  ·  scripts/setup.ps1
# =============================================================================
param(
    [ValidateSet('full', 'quick', 'setup')]
    [string]$Mode = 'full'
)

$impl = @{
    full  = 'dev-full.ps1'
    quick = 'dev-quick.ps1'
    setup = 'setup.ps1'
}[$Mode]

$target = Join-Path $PSScriptRoot "scripts\$impl"
if (-not (Test-Path $target)) {
    Write-Host "[ERROR] not found: $target" -ForegroundColor Red
    exit 1
}
& $target
