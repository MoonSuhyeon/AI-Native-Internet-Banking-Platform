param(
    [string] $Filter  = "",       # pytest -k 필터 (예: "TestMyAccounts")
    [switch] $Verbose,            # -v 상세 출력
    [switch] $Coverage            # coverage 리포트 출력
)

$ErrorActionPreference = "Stop"
$serviceRoot = Split-Path -Parent $PSScriptRoot
$pythonPath  = Join-Path $serviceRoot ".venv\Scripts\python.exe"

if (-not (Test-Path -LiteralPath $pythonPath)) {
    $pythonPath = "python"
    Write-Host "venv not found, using system python"
}

Push-Location $serviceRoot
try {
    $pytestArgs = @("tests/")

    if ($Verbose)  { $pytestArgs += "-v" }
    if ($Filter)   { $pytestArgs += "-k"; $pytestArgs += $Filter }
    if ($Coverage) { $pytestArgs += "--cov=app"; $pytestArgs += "--cov-report=term-missing" }

    Write-Host ""
    Write-Host "consultation-service 테스트 실행"
    Write-Host "  python : $pythonPath"
    Write-Host "  args   : $($pytestArgs -join ' ')"
    Write-Host ""

    & $pythonPath -m pytest @pytestArgs
    if ($LASTEXITCODE -ne 0) { throw "테스트 실패 (exit=$LASTEXITCODE)" }

    Write-Host ""
    Write-Host "모든 테스트 통과"
} finally {
    Pop-Location
}
