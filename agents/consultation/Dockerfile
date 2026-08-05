# 빌드 컨텍스트는 이 디렉터리가 아니라 상위 agents/ 다.
#   docker build -f consultation/Dockerfile agents/
# harness-core/python 의 공유 감사 계약을 이미지에 담기 위해서다
# (docs/decisions/agent-harness-consolidation.md 2-d).
# 그래서 아래 COPY 경로가 전부 consultation/ 으로 시작한다.

FROM python:3.11-slim

WORKDIR /app

# 의존성 먼저 설치 (레이어 캐시 활용)
COPY consultation/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 하네스 공유 계약 — 사본이 아니라 원본을 그대로 넣는다.
# 사본을 두면 테스트가 대조하는 파일과 이미지에 들어가는 파일이 달라진다.
COPY harness-core/python/harness_core/ ./harness_core/

# 소스 복사
COPY consultation/app/     ./app/
COPY consultation/static/  ./static/
COPY consultation/sql/     ./sql/

# 포트
EXPOSE 8087

# 실행
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8087"]
