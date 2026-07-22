# =============================================================================
# data-tools — 공공데이터 수집·합성·RAG 시드 통합 유틸리티 이미지
#
# 24시간 상주 서비스 아님. 필요할 때 일회성으로 실행하는 batch 컨테이너.
#
# 사용 예:
#   # 공공데이터 일괄 수집
#   docker run --rm --env-file .env ghcr.io/<owner>/data-tools \
#     python scripts/fetch_all.py
#
#   # advisory-service RAG 문서 시드
#   docker run --rm ghcr.io/<owner>/data-tools \
#     python scripts/seed_hmda_rag.py --host http://api-gateway:8080
#
#   # 합성 데이터 빌드
#   docker run --rm --env-file .env -v $PWD/data:/app/data ghcr.io/<owner>/data-tools \
#     python scripts/build_synthetic.py
# =============================================================================
FROM python:3.11-slim

WORKDIR /app

# psycopg2-binary 의 런타임 의존성
RUN apt-get update && \
    apt-get install -y --no-install-recommends libpq5 && \
    rm -rf /var/lib/apt/lists/*

# 의존성 먼저 설치 (레이어 캐시)
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 소스 복사
COPY src/     ./src/
COPY scripts/ ./scripts/

# PYTHONPATH 에 /app 추가 → scripts 에서 src.* import 가능
ENV PYTHONPATH=/app

# 기본 진입점: bash. 실행시 명령으로 스크립트 지정.
ENTRYPOINT ["python"]
CMD ["--version"]
