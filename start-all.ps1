$root = $PSScriptRoot

Write-Host "=== Internet Banking services start ===" -ForegroundColor Cyan

Write-Host "[1/5] Consultation Service (8087) starting..." -ForegroundColor Yellow
$consultDir = Join-Path $root "agents\consultation"
Start-Process powershell -ArgumentList "-NoProfile -Command `"cd '$consultDir'; python -m uvicorn app.main:app --host 0.0.0.0 --port 8087 --reload`"" -WindowStyle Normal

Start-Sleep -Seconds 2

Write-Host "[2/5] Customer Service (8081) starting..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoProfile -Command `"cd '$root'; .\gradlew.bat :services:customer-service:bootRun`"" -WindowStyle Normal

Start-Sleep -Seconds 2

Write-Host "[3/5] API Gateway (8080) starting..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoProfile -Command `"cd '$root'; .\gradlew.bat :services:api-gateway:bootRun`"" -WindowStyle Normal

Start-Sleep -Seconds 2

Write-Host "[4/5] Deposit Service (8082) starting..." -ForegroundColor Yellow
Start-Process powershell -ArgumentList "-NoProfile -Command `"cd '$root'; .\gradlew.bat :services:deposit-service:bootRun`"" -WindowStyle Normal

Start-Sleep -Seconds 2

Write-Host "[5/5] Web (3001) starting..." -ForegroundColor Yellow
$webDir = Join-Path $root "web"
Start-Process powershell -ArgumentList "-NoProfile -Command `"cd '$webDir'; npm run dev`"" -WindowStyle Normal

Write-Host ""
Write-Host "Services are starting. Readiness can take 30-60 seconds." -ForegroundColor Green
Write-Host "  Web:          http://localhost:3001" -ForegroundColor White
Write-Host "  API Gateway:  http://localhost:8080" -ForegroundColor White
Write-Host "  Customer API: http://localhost:8081" -ForegroundColor White
Write-Host "  Deposit API:  http://localhost:8082" -ForegroundColor White
Write-Host "  Consultation: http://localhost:8087" -ForegroundColor White
