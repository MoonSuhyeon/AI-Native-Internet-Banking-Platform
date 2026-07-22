param(
    [string] $DbHost = "localhost",
    [int]    $DbPort = 5432,
    [string] $DbName = "deposit_db",
    [string] $DbUser = "deposit",
    [string] $DbPassword = "deposit",
    [int]    $ServerPort = 8087,
    [switch] $KafkaEnabled
)

$ErrorActionPreference = "Stop"
$serviceRoot = Split-Path -Parent $PSScriptRoot
$pythonPath = Join-Path $serviceRoot ".venv\Scripts\python.exe"

if (-not (Test-Path -LiteralPath $pythonPath)) {
    throw "venv python not found. Run services\consultation-service\scripts\setup-venv.ps1 first."
}

$env:CONSULTATION_DATABASE_URL = "postgresql+psycopg://${DbUser}:${DbPassword}@${DbHost}:${DbPort}/${DbName}"
$env:CONSULTATION_KAFKA_ENABLED = $(if ($KafkaEnabled) { "true" } else { "false" })

Write-Host ""
Write-Host "consultation-service starting..."
Write-Host "  URL   : http://localhost:$ServerPort"
Write-Host "  Chat  : http://localhost:$ServerPort/chat"
Write-Host "  DB    : $DbHost`:$DbPort/$DbName (user=$DbUser)"
Write-Host "  Kafka : $env:CONSULTATION_KAFKA_ENABLED"
Write-Host ""

Push-Location $serviceRoot
try {
    & $pythonPath -m uvicorn app.main:app --host 0.0.0.0 --port $ServerPort
    if ($LASTEXITCODE -ne 0) { throw "consultation-service failed (exit=$LASTEXITCODE)" }
} finally {
    Pop-Location
}
