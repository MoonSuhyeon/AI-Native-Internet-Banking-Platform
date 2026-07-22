@echo off
set CONSULTATION_KAFKA_ENABLED=true
set CONSULTATION_DATABASE_URL=postgresql+psycopg://deposit:deposit@localhost:5432/deposit_db
set CONSULTATION_KAFKA_BOOTSTRAP_SERVERS=localhost:9092
cd /d "C:\Users\green\Desktop\teamproject\internet_banking\services\consultation-service"
"C:\Users\green\Desktop\teamproject\internet_banking\services\consultation-service\.venv\Scripts\python.exe" -m uvicorn app.main:app --host 127.0.0.1 --port 8011 --log-level info
