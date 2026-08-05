param(
    [string] $Database  = "deposit_db",
    [string] $HostName  = "localhost",
    [int]    $Port      = 5432,
    [string] $User      = "deposit",
    [string] $PsqlPath  = "C:\Program Files\PostgreSQL\16\bin\psql.exe"
)

$ErrorActionPreference = "Stop"

$serviceRoot = Split-Path -Parent $PSScriptRoot
$seedSql = Join-Path $serviceRoot "src\main\resources\db\migration\V2__seed_postman_data.sql"

if (-not (Test-Path -LiteralPath $seedSql)) {
    throw "Seed SQL not found: $seedSql"
}

if (-not (Test-Path -LiteralPath $PsqlPath)) {
    throw "psql not found: $PsqlPath"
}

Write-Host "데모 데이터 적재 중 ($HostName`:$Port/$Database) ..."
Write-Host "  파일: $seedSql"

& $PsqlPath -h $HostName -p $Port -U $User -d $Database -v ON_ERROR_STOP=1 -f $seedSql
if ($LASTEXITCODE -ne 0) { throw "데모 데이터 적재 실패" }

Write-Host ""
Write-Host "DEPOSIT_DEMO_DATA_SEED_OK"
