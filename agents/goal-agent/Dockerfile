# 빌드 컨텍스트는 이 디렉터리가 아니라 상위 agents/ 다.
#   docker build -f goal-agent/Dockerfile agents/
# consultation 과 같은 이유 — harness-core/python 의 공유 감사 계약을 담는다
# (docs/decisions/agent-harness-consolidation.md 2-d).
#
# goal-agent 는 compose 에 없고 손으로 빌드한다. 컨텍스트가 바뀌었으므로
# 예전 명령(`docker build agents/goal-agent`)은 더 이상 동작하지 않는다.

FROM python:3.11-slim

WORKDIR /app

COPY goal-agent/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 하네스 공유 계약 — 사본이 아니라 원본
COPY harness-core/python/harness_core/ ./harness_core/

COPY goal-agent/app/ ./app/

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
