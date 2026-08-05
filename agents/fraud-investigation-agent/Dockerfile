# 빌드 컨텍스트는 이 디렉터리가 아니라 상위 agents/ 다.
#   docker build -f fraud-investigation-agent/Dockerfile agents/
# consultation·goal-agent 와 같은 이유 — harness-core/python 의 공유 감사 계약을 담는다
# (docs/decisions/agent-harness-consolidation.md 2-d).
#
# 이 에이전트에 Dockerfile 이 생긴 이유는 배포 편의가 아니라 **격리**다.
# 조사 승인은 지급정지·STR 을 발동시키는 지점이라 게이트웨이를 거친 요청만 받아야 하는데,
# 호스트에서 uvicorn 으로 직접 띄우면 8090 이 그대로 열려 우회가 가능하다.
# 컨테이너로 넣어야 compose 의 internal 네트워크에 가둘 수 있다.

FROM python:3.11-slim

WORKDIR /app

COPY fraud-investigation-agent/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 하네스 공유 계약 — 사본이 아니라 원본
COPY harness-core/python/harness_core/ ./harness_core/

COPY fraud-investigation-agent/src/  ./src/
COPY fraud-investigation-agent/data/ ./data/

# src 레이아웃 — 로컬 실행(pytest.ini pythonpath)과 같은 경로 규약
ENV PYTHONPATH=/app/src

EXPOSE 8090

# ports 를 compose 에서 노출하지 않는다. 게이트웨이만 이 포트에 닿는다.
CMD ["uvicorn", "agent.api:app", "--host", "0.0.0.0", "--port", "8090"]
