param(
    [string] $HostName = "localhost",
    [int]    $Port = 5432,
    [string] $Database = "deposit_db",
    [string] $User = "deposit",
    [string] $PsqlPath = "C:\Program Files\PostgreSQL\16\bin\psql.exe"
)

$ErrorActionPreference = "Stop"
$serviceRoot = Split-Path -Parent $PSScriptRoot
$ddl = Join-Path $serviceRoot "sql\ddl.sql"

if (-not (Test-Path -LiteralPath $ddl)) {
    throw "ddl.sql not found: $ddl"
}

if (-not (Test-Path -LiteralPath $PsqlPath)) {
    $PsqlPath = "psql"
}

Write-Host "Initializing consultation-service schema on $HostName`:$Port/$Database..."
& $PsqlPath -h $HostName -p $Port -U $User -d $Database -v ON_ERROR_STOP=1 -f $ddl
if ($LASTEXITCODE -ne 0) { throw "consultation schema initialization failed" }
