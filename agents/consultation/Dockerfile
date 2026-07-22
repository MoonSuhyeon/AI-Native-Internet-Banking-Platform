FROM python:3.11-slim

WORKDIR /app

# 의존성 먼저 설치 (레이어 캐시 활용)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 소스 복사
COPY app/     ./app/
COPY static/  ./static/
COPY sql/     ./sql/

# 포트
EXPOSE 8087

# 실행
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8087"]
