param(
    [string] $PythonExe = "python"
)

$ErrorActionPreference = "Stop"
$serviceRoot = Split-Path -Parent $PSScriptRoot
$venvPath = Join-Path $serviceRoot ".venv"
$pythonPath = Join-Path $venvPath "Scripts\python.exe"

if (-not (Test-Path -LiteralPath $pythonPath)) {
    Write-Host "Creating consultation-service virtual environment..."
    & $PythonExe -m venv $venvPath
    if ($LASTEXITCODE -ne 0) { throw "venv creation failed" }
}

Write-Host "Installing consultation-service dependencies..."
& $pythonPath -m pip install --upgrade pip
if ($LASTEXITCODE -ne 0) { throw "pip upgrade failed" }

& $pythonPath -m pip install -r (Join-Path $serviceRoot "requirements.txt")
if ($LASTEXITCODE -ne 0) { throw "dependency install failed" }

Write-Host "consultation-service venv ready"
