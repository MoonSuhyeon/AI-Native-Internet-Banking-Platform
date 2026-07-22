param(
    [string] $HostName = "localhost",
    [int]    $Port = 5432,
    [string] $Database = "deposit_db",
    [string] $User = "deposit",
    [string] $PsqlPath = "C:\Program Files\PostgreSQL\16\bin\psql.exe"
)

$ErrorActionPreference = "Stop"
$serviceRoot = Split-Path -Parent $PSScriptRoot
$seed = Join-Path $serviceRoot "sql\demo-data.sql"

if (-not (Test-Path -LiteralPath $seed)) {
    throw "demo-data.sql not found: $seed"
}

if (-not (Test-Path -LiteralPath $PsqlPath)) {
    $PsqlPath = "psql"
}

Write-Host "Seeding consultation-service demo data on $HostName`:$Port/$Database..."
& $PsqlPath -h $HostName -p $Port -U $User -d $Database -v ON_ERROR_STOP=1 -f $seed
if ($LASTEXITCODE -ne 0) { throw "consultation demo data seed failed" }
