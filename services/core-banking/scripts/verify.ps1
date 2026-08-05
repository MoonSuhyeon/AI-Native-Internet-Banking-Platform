param(
    [string] $BaseUrl       = "http://localhost:8082/api",
    [int]    $RetryCount    = 15,
    [int]    $RetryInterval = 3
)

$ErrorActionPreference = "Stop"

# ── STEP 1: 서비스 기동 대기 ─────────────────────────────
Write-Host "STEP 1/4: deposit-service 응답 대기 ($BaseUrl/) ..."

$ready = $false
for ($i = 1; $i -le $RetryCount; $i++) {
    try {
        $r = Invoke-WebRequest -Uri "$BaseUrl/" -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
        if ($r.StatusCode -eq 200) { $ready = $true; break }
    } catch {
        Write-Host "  시도 $i/$RetryCount 실패, ${RetryInterval}초 후 재시도 ..."
        Start-Sleep -Seconds $RetryInterval
    }
}
if (-not $ready) { throw "deposit-service 미응답 ($RetryCount 회 초과)" }
Write-Host "  -> OK (status=UP)"

# ── STEP 2: 상품 목록 ─────────────────────────────────────
Write-Host "STEP 2/4: GET /products ..."
$r = Invoke-WebRequest -Uri "$BaseUrl/products" -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
if ($r.StatusCode -ne 200) { throw "/products -> HTTP $($r.StatusCode)" }
Write-Host "  -> OK (HTTP $($r.StatusCode))"

# ── STEP 3: 계좌 조회 ─────────────────────────────────────
Write-Host "STEP 3/4: GET /accounts?customerId=CUST001 ..."
$r = Invoke-WebRequest -Uri "$BaseUrl/accounts?customerId=CUST001" -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
if ($r.StatusCode -ne 200) { throw "/accounts -> HTTP $($r.StatusCode)" }
Write-Host "  -> OK (HTTP $($r.StatusCode))"

# ── STEP 4: 거래 내역 조회 ────────────────────────────────
Write-Host "STEP 4/4: GET /transactions?accountId=1 ..."
$r = Invoke-WebRequest -Uri "$BaseUrl/transactions?accountId=1" -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
if ($r.StatusCode -ne 200) { throw "/transactions -> HTTP $($r.StatusCode)" }
Write-Host "  -> OK (HTTP $($r.StatusCode))"

Write-Host ""
Write-Host "DEPOSIT_SERVICE_OK"
