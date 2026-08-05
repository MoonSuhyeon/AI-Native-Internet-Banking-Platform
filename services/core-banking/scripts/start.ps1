param(
    [string] $DbHost     = "localhost",
    [int]    $DbPort     = 5432,
    [string] $DbName     = "deposit_db",
    [string] $DbUser     = "deposit",
    [string] $DbPassword = "deposit",
    [int]    $ServerPort = 8082,
    # -UseFlyway: 빈 DB 에서 시작할 때 (Flyway 가 V1→V2→V5 자동 적용)
    # 기본값: Flyway 비활성 — init-db.ps1 으로 스키마를 이미 만든 경우
    [switch] $UseFlyway
)

$ErrorActionPreference = "Stop"

$serviceRoot = Split-Path -Parent $PSScriptRoot
$repoRoot    = Split-Path -Parent (Split-Path -Parent $serviceRoot)
$gradlew     = Join-Path $repoRoot "gradlew.bat"

if (-not (Test-Path -LiteralPath $gradlew)) {
    throw "gradlew.bat 없음: $gradlew"
}

# Spring Boot application arguments (--key=value 형식)
$springArgs = @(
    "--server.port=$ServerPort",
    "--spring.datasource.url=jdbc:postgresql://${DbHost}:${DbPort}/${DbName}",
    "--spring.datasource.username=$DbUser",
    "--spring.datasource.password=$DbPassword"
)

if ($UseFlyway) {
    Write-Host "Flyway 활성 모드 — 마이그레이션 자동 적용 (빈 DB 시작용)"
} else {
    $springArgs += "--spring.flyway.enabled=false"
    Write-Host "Flyway 비활성 모드 — init-db.ps1 으로 스키마가 이미 생성된 경우"
}

$argsString = $springArgs -join " "

Write-Host ""
Write-Host "deposit-service 시작 중 ..."
Write-Host "  URL : http://localhost:$ServerPort/api/"
Write-Host "  DB  : $DbHost`:$DbPort/$DbName (user=$DbUser)"
Write-Host ""

& $gradlew -p $repoRoot ":services:deposit-service:bootRun" "--args=$argsString"

if ($LASTEXITCODE -ne 0) { throw "deposit-service 시작 실패 (exit=$LASTEXITCODE)" }
