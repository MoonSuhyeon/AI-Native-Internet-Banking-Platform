$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Internet Banking - Start Local Dev Servers" -ForegroundColor Cyan
Write-Host "============================================================"
Write-Host ""

# Docker check
docker info 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Docker Desktop is not running." -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

# Set env vars for bootRun (inherited by child processes)
$env:SPRING_PROFILES_ACTIVE   = "local"
$env:CUSTOMER_DB_PORT         = "15432"
$env:DEPOSIT_DB_PORT          = "5433"
$env:DEPOSIT_DB_PASSWORD      = "deposit"
$env:LOAN_DB_PORT             = "5434"
$env:PAYMENT_DB_PORT          = "5435"
$env:PAYMENT_DB_B_PORT        = "5441"
$env:MASTER_DB_PORT           = "5436"
$env:AI_DB_PORT               = "5437"
$env:COMMON_DB_PORT           = "5438"
# doc-agent-db: common-db(5438)/langfuse-db(5439) 호스트 포트 충돌 회피용으로 5440 분리
$env:DOC_AGENT_DB_PORT        = "5440"
# 호스트 bootRun 모드: loan-service의 서비스 간 base-url 기본값이 도커 네트워크 호스트명이라
# 호스트 실행 시 이름이 풀리지 않는다. 두 서비스 모두 도커가 아닌 호스트 bootRun으로 뜨고
# 각자 server.port(auto-loan-review 8089, doc-agent 8087)로 listen 하므로 localhost로 오버라이드한다.
$env:AUTO_REVIEW_BASE_URL     = "http://localhost:8089"
$env:DOC_AGENT_BASE_URL       = "http://localhost:8087"
$env:VAULT_PORT               = "8200"
$env:MINIO_PORT               = "9000"
$env:REDIS_HOST               = "localhost"
$env:SCHEMA_REGISTRY_URL      = "http://localhost:18081"
# payment-service: 통합 단일 브로커(ib-kafka:9092)로 kftc/bok/internal 3 논리 클러스터 통합
$env:KFTC_KAFKA_BOOTSTRAP     = "localhost:9092"
$env:BOK_KAFKA_BOOTSTRAP      = "localhost:9092"
$env:INTERNAL_KAFKA_BOOTSTRAP = "localhost:9092"
$env:CRYPTO_KEY_BASE64        = "OhIN2XxxrFW6nzY6iIQ8sTfTr/L6NQJxgdCZfqJon+k="
$env:LANGFUSE_PORT            = "3002"

# Load secrets (OPENAI_API_KEY etc.) from .env
$envFile = Join-Path $root ".env"
if (Test-Path $envFile) {
    Get-Content $envFile | Where-Object { $_ -match '^[A-Za-z_][A-Za-z_0-9]*=' } | ForEach-Object {
        $idx = $_.IndexOf('=')
        $key = $_.Substring(0, $idx)
        $val = $_.Substring($idx + 1)
        [System.Environment]::SetEnvironmentVariable($key, $val, 'Process')
    }
}

# consultation-service (FastAPI 챗봇) 환경변수
# env_prefix="CONSULTATION_" 이므로 OpenAI 키는 prefix 형태로 브리지한다.
$env:CONSULTATION_DATABASE_URL   = "postgresql+psycopg://deposit:deposit@localhost:5433/deposit_db"
$env:CONSULTATION_KAFKA_ENABLED  = "false"
$env:CONSULTATION_OPENAI_API_KEY = $env:OPENAI_API_KEY
$env:CONSULTATION_OPENAI_MODEL   = "gpt-4o-mini"

# alertmanager.yml 은 gitignore 대상(.sample 복사 컨벤션). 없으면 Docker가 빈 디렉터리를
# 만들어 마운트가 깨지므로, bind-mount 전에 sample에서 실제 파일을 보장한다.
$amConf   = Join-Path $root "infra\alertmanager\alertmanager.yml"
$amSample = Join-Path $root "infra\alertmanager\alertmanager.yml.sample"
if ((Test-Path $amConf -PathType Container) ) { Remove-Item -Recurse -Force $amConf }
if (-not (Test-Path $amConf) -and (Test-Path $amSample)) { Copy-Item $amSample $amConf }

# 1. Start infra containers only (skip Docker-build app services)
Write-Host "[1/3] Starting infrastructure..." -ForegroundColor Yellow
$infra = @(
    # Databases
    "customer-db","deposit-db","loan-db","common-db",
    "payment-db","payment-db-b","master-db","ai-db",
    "doc-agent-db","langfuse-db",
    # Messaging / streaming (Kafka 전체: 브로커 + 레지스트리 + exporter)
    # payment-topic-init: kafka healthy 후 결제계 18개 토픽 선생성(원샷). 앱 bootRun 전에 토픽 보장.
    "kafka","payment-topic-init","schema-registry",
    "kafka-exporter-kftc","kafka-exporter-bok","kafka-exporter-internal",
    # Cache / storage / secrets
    "redis","minio","vault",
    # Monitoring / observability
    "prometheus","grafana","alertmanager","blackbox-exporter",
    "loki","promtail","langfuse","phoenix"
)
# doc-agent-db/minio/vault 는 profile "doc" 소속이라 프로파일을 활성화해야 떠다.
# rag 프로파일(elasticsearch/kibana/kafka-connect/kafka-connect-init)은 Phase E(ES 백엔드) 미도입이라 띄우지 않는다.
# kibana·kafka-connect 가 elasticsearch 에 depends_on(service_healthy)으로 묶여 있어 ES 만 빼도 의존성으로 끌려 올라오므로,
# rag 프로파일 자체를 끄고 4개 서비스를 $infra 에서 제외한다.
docker compose --profile doc up -d @infra
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] docker compose failed" -ForegroundColor Red
    Read-Host "Press Enter to exit"
    exit 1
}

# 2. Wait for health
Write-Host ""
Write-Host "[2/3] Waiting for health checks (up to 90s)..." -ForegroundColor Yellow
$waited = 0
while ($waited -lt 90) {
    Start-Sleep -Seconds 5
    $waited += 5
    $starting = docker compose ps --format "{{.Health}}" 2>$null | Where-Object { $_ -eq "starting" }
    if (-not $starting) { break }
    Write-Host "  waiting... ($waited`s)"
}
Write-Host "  Infrastructure ready ($waited`s)" -ForegroundColor Green

# 3. Start Spring Boot services + web
Write-Host ""
Write-Host "[3/3] Starting services..." -ForegroundColor Yellow

Start-Process cmd -ArgumentList "/k gradlew.bat :services:customer-service:bootRun" -WorkingDirectory $root -WindowStyle Normal
Start-Sleep -Seconds 2
# core-banking (8082) — deposit-service + payment-service 가 합쳐진 모듈이다.
# 예전에는 두 줄이었다. 예전 모듈명으로는 gradle 이 "project not found" 로 죽는다.
Start-Process cmd -ArgumentList "/k gradlew.bat :services:core-banking:bootRun" -WorkingDirectory $root -WindowStyle Normal
Start-Sleep -Seconds 2
Start-Process cmd -ArgumentList "/k gradlew.bat :services:loan-service:bootRun" -WorkingDirectory $root -WindowStyle Normal
Start-Sleep -Seconds 2
# core-banking-B (다온/타행 수신측, 8180) — 같은 모듈을 다른 env로 한 번 더 기동.
#   BANK_CODE=B, mock 프로파일, payment_b DB(5441), 포트 8180.
#   env는 'set "..." &&'로 이 cmd 창에만 적용 → A 인스턴스(8080)에 영향 없음.
#   Kafka(localhost:9092)는 A와 동일 브로커 공유. P-029 bank_code 필터만으로는 부족하다:
#   A·B가 같은 컨슈머 그룹(payment-kftc/bok/internal)을 쓰면 B로 가야 할 요청을 A가 먼저
#   소비→필터로 버려 타행이체 수신이 안 된다. 그래서 B 전용 그룹(-B 접미사)으로 분리한다.
#   또한 --no-daemon: gradle 데몬을 재사용하면 위 'set' env(mock·8180·그룹)가 무시되고
#   A 데몬의 환경(local·8084)으로 떠 포트 충돌로 죽는다. 데몬 재사용 차단을 위해 필수.
Start-Process cmd -ArgumentList '/k set "PAYMENT_APP_PORT=8180" && set "BANK_CODE=B" && set "SPRING_PROFILES_ACTIVE=mock" && set "PAYMENT_DB_PORT=5441" && set "PAYMENT_DB_NAME=payment_b" && set "KFTC_CONSUMER_GROUP=payment-kftc-B" && set "BOK_CONSUMER_GROUP=payment-bok-B" && set "INTERNAL_CONSUMER_GROUP=payment-internal-B" && gradlew.bat --no-daemon :services:core-banking:bootRun' -WorkingDirectory $root -WindowStyle Normal
Start-Sleep -Seconds 2
# master-service 는 레포에 존재한 적이 없다(gradle 모듈도, 컨트롤러도 없다).
# 공통코드는 code_master 테이블과 시드로만 있고 검증에 쓰이지 않는다.
Start-Process cmd -ArgumentList "/k gradlew.bat :services:api-gateway:bootRun" -WorkingDirectory $root -WindowStyle Normal
Start-Sleep -Seconds 2
Start-Process cmd -ArgumentList "/k gradlew.bat :agents:auto-loan-review:bootRun" -WorkingDirectory $root -WindowStyle Normal
Start-Sleep -Seconds 2
Start-Process cmd -ArgumentList "/k gradlew.bat :agents:review-ai-gateway:bootRun" -WorkingDirectory $root -WindowStyle Normal
Start-Sleep -Seconds 2
Start-Process cmd -ArgumentList "/k gradlew.bat :agents:doc-agent:bootRun" -WorkingDirectory $root -WindowStyle Normal
Start-Sleep -Seconds 2

# consultation-service (FastAPI, 8090) — venv 자동 구성 후 기동
$consultDir    = Join-Path $root "agents\consultation"
$consultVenvPy = Join-Path $consultDir ".venv\Scripts\python.exe"
if (-not (Test-Path $consultVenvPy)) {
    Write-Host "  consultation-service: venv 생성 + 의존성 설치 (최초 1회, 수 분 소요)..." -ForegroundColor Yellow
    python -m venv (Join-Path $consultDir ".venv")
    & $consultVenvPy -m pip install --upgrade pip -q
    & $consultVenvPy -m pip install -r (Join-Path $consultDir "requirements.txt") -q
}
Start-Process cmd -ArgumentList "/k `"$consultVenvPy`" -m uvicorn app.main:app --host 0.0.0.0 --port 8087 --log-level info" -WorkingDirectory $consultDir -WindowStyle Normal
Start-Sleep -Seconds 2

Start-Process cmd -ArgumentList "/k npm run dev" -WorkingDirectory "$root\web" -WindowStyle Normal

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " 12 service windows opened (ready in ~30-90s)"
Write-Host ""
Write-Host " Web UI      http://localhost:3001" -ForegroundColor Green
Write-Host ""
Write-Host " Swagger:"
Write-Host "   customer   http://localhost:8081/swagger-ui.html"
Write-Host "   deposit    http://localhost:8082/swagger-ui.html"
Write-Host "   loan       http://localhost:8083/swagger-ui.html"
Write-Host "   payment    http://localhost:8084/swagger-ui.html"
Write-Host "   payment-B  http://localhost:8180/swagger-ui.html  (다온/타행 수신측)"
Write-Host "   master     http://localhost:8085/swagger-ui.html"
Write-Host "   api-gw     http://localhost:8080/actuator/health"
Write-Host "   auto-loan  http://localhost:8089/swagger-ui.html"
Write-Host "   review-ai  http://localhost:8088/swagger-ui.html"
Write-Host "   doc-agent  http://localhost:8087/swagger-ui.html"
Write-Host "   chatbot    http://localhost:8090/docs  (consultation-service)"
Write-Host ""
Write-Host " Grafana     http://localhost:3000  (admin/admin)" -ForegroundColor Green
Write-Host " Langfuse    http://localhost:3002" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""
Read-Host "Press Enter to exit"
