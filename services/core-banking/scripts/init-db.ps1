param(
    [string] $Database  = "deposit_db",
    [string] $HostName  = "localhost",
    [int]    $Port      = 5432,
    [string] $User      = "deposit",
    [string] $PsqlPath  = "C:\Program Files\PostgreSQL\16\bin\psql.exe"
)

$ErrorActionPreference = "Stop"

$serviceRoot = Split-Path -Parent $PSScriptRoot
$migDir      = Join-Path $serviceRoot "src\main\resources\db\migration"

$v1Sql = Join-Path $migDir "V1__initial_schema.sql"
$v5Sql = Join-Path $migDir "V5__full_erd_schema.sql"
$v6Sql = Join-Path $migDir "V6__term_application_management.sql"

foreach ($f in @($v1Sql, $v5Sql, $v6Sql)) {
    if (-not (Test-Path -LiteralPath $f)) { throw "SQL 파일 없음: $f" }
}

if (-not (Test-Path -LiteralPath $PsqlPath)) {
    throw "psql 없음: $PsqlPath"
}

function Invoke-Psql {
    param([string]$SqlFile)
    & $PsqlPath -h $HostName -p $Port -U $User -d $Database -v ON_ERROR_STOP=1 -f $SqlFile
    if ($LASTEXITCODE -ne 0) { throw "psql 실패: $SqlFile" }
}

function Table-Exists {
    param([string]$TableName)
    $count = & $PsqlPath -h $HostName -p $Port -U $User -d $Database -tAc `
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='$TableName';"
    if ($LASTEXITCODE -ne 0) { throw "DB 연결 실패 ($HostName`:$Port/$Database, user=$User)" }
    return $count.Trim() -eq "1"
}

Write-Host "DB 연결 확인 중 ($HostName`:$Port/$Database) ..."

# V1/V5 — 핵심 수신계 + 전체 ERD
if (Table-Exists "deposit_banking_products") {
    Write-Host "deposit_banking_products 이미 존재 — V1/V5 스킵"
} else {
    Write-Host ""
    Write-Host "STEP 1/2: V1__initial_schema.sql 적용 (수신계 핵심 테이블) ..."
    Invoke-Psql $v1Sql
    Write-Host "  -> V1 OK"

    Write-Host ""
    Write-Host "STEP 2/2: V5__full_erd_schema.sql 적용 (전체 ERD) ..."
    Invoke-Psql $v5Sql
    Write-Host "  -> V5 OK"
}

# V6 — IF NOT EXISTS 이므로 항상 안전하게 실행
Write-Host ""
Write-Host "V6__term_application_management.sql 적용 ..."
Invoke-Psql $v6Sql
Write-Host "  -> V6 OK"

Write-Host ""
Write-Host "DEPOSIT_SCHEMA_OK"
