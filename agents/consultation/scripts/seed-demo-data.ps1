# demo-data.sql 은 **수신(deposit) 표**에 붓는 데모 데이터다.
#
# 상담 DB 가 아니다. 상담은 수신 데이터를 core-banking 내부 API 로만 읽으므로(A2),
# 이 시드는 core-banking 의 DB 를 향해야 한다. 상담 전용 DB 에 부으면 표가 없어 실패한다.
param(
    [string] $HostName = "localhost",
    [int]    $Port = 5433,
    [string] $Database = "core_banking",
    [string] $User = "core",
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
