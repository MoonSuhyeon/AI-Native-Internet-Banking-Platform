# harness-core (Python)

Java 모듈 `agents/harness-core` 와 **같은 하네스**의 Python 런타임 쪽이다.
배경과 통합 기준은 [`docs/decisions/agent-harness-consolidation.md`](../../../docs/decisions/agent-harness-consolidation.md).

## 공유되는 것은 코드가 아니라 계약이다

JVM 라이브러리를 CPython 에서 쓸 수 없으므로 구현은 두 벌이다. 대신 셋을 맞춘다.

| 공유 | 정본 |
|---|---|
| 테이블 스키마 | `../src/main/resources/db/harness/V001__harness_audit_log.sql` |
| 필드 이름·순서 | 위 SQL 의 컬럼 |
| 필드 의미 | 위 SQL 의 주석 |

정본이 Java 도 Python 도 아니라 **SQL** 인 것이 요점이다. 한쪽 언어를 정본으로 삼으면
다른 쪽이 따라가는 관계가 되고, 따라가는 쪽은 조용히 뒤처진다.

`tests/test_audit_contract.py` 가 실제로 셋이 맞는지 확인한다. Java record·Python
dataclass·SQL 컬럼을 파일에서 직접 읽어 대조하므로, 한쪽만 고치면 테스트가 먼저 깨진다.

```bash
cd agents/harness-core/python
pip install -e .        # 이 패키지를 venv 에 넣는다 (아래 "어떻게 전달되는가")
python -m pytest tests/ -q
```

## 어떻게 전달되는가

**venv 에 설치해서 쓴다. sys.path 를 만지지 않는다.**

각 에이전트의 `requirements-dev.txt` 가 `-e ../harness-core/python` 으로 선언한다.

```bash
cd agents/consultation && pip install -r requirements-dev.txt
```

pip 은 requirements 안의 상대 경로를 **현재 작업 디렉터리** 기준으로 푼다.
그래서 반드시 에이전트 디렉터리에서 실행해야 한다 (CI 도 `working-directory` 로 맞춘다).

예전에는 세 에이전트가 세 가지 방법으로 이 패키지를 봤다 — conftest 의 `sys.path`,
`pytest.ini` 의 `pythonpath`, README 안내. 방법이 갈리면 한 곳을 고칠 때 나머지가
조용히 어긋난다. 전역 `pip install -e` 도 답이 아니었다 — CI·다른 사람 PC 에서
재현되지 않는 네 번째 방법을 늘리는 것이고, 내 PC 에서만 초록인 상태를 만든다
(docs/decisions/agent-harness-consolidation.md 다음 순서 6).

이미지에는 설치하지 않고 `COPY harness-core/python/harness_core/ ./harness_core/` 로
원본을 그대로 넣는다. 운영 이미지에 개발용 경로 의존을 남기지 않기 위해서다.

## 구조

```
harness_core/
  audit.py             계약 — 표준 라이브러리만 쓴다
  audit_sqlalchemy.py  저장 구현 — SQLAlchemy 를 쓰는 에이전트만 가져간다
```

**계약과 저장 구현을 나눈 이유**: `fraud-investigation-agent` 에는 DB 의존성이 아예 없다
(`langgraph`·`httpx` 뿐). `audit.py` 에 SQLAlchemy 를 넣으면 그 에이전트는 계약을 쓰기 위해
DB 드라이버를 얹어야 하고, 그러면 "가벼운 에이전트는 감사를 안 붙인다"가 된다.
감사가 없는 에이전트가 생기는 것이 애초에 이 작업의 문제였다.

## 쓰는 쪽

```python
from harness_core import AgentAuditEntry
from harness_core.audit_sqlalchemy import SqlAlchemyAgentAuditLog

audit = SqlAlchemyAgentAuditLog(SessionLocal, agent_name="consultation")

audit.record(AgentAuditEntry(
    agent_name="consultation",
    subject_type="CONSULT_SESSION",
    subject_id=session_id,
    trace_id=current_trace_id(),
    request_json=AgentAuditEntry.json_of({"message": user_text}),
    output_json=AgentAuditEntry.json_of({"answer": answer_text}),
))
```

감사를 끌 때는 `NoOpAgentAuditLog` 로 바꿔 끼운다. **빈을 없애지 않는다** —
Java 쪽에서 감사를 끄자 의존하는 쪽이 통째로 깨진 적이 있다.

## 각 에이전트에 어떻게 전달되는가 — 빌드 컨텍스트를 올렸다

Python 에이전트의 Docker 빌드 컨텍스트가 원래 **각자의 디렉터리**였고
(`context: agents/consultation`, `COPY app/ ./app/`), 그래서 이 패키지는 이미지에
들어가지 않았다. 컨텍스트를 `agents/` 로 올려 해결했다.

```
docker build -f consultation/Dockerfile agents/
docker build -f goal-agent/Dockerfile   agents/
```

복사본을 두는 안(에이전트마다 `harness_core` 를 복제)을 쓰지 않은 이유는,
**테스트가 대조하는 파일과 이미지에 들어가는 파일이 달라지기 때문**이다.
`test_audit_contract.py` 는 레포의 파일을 읽는다. 사본을 두면 그 대조가 초록인데
컨테이너 안의 계약은 다를 수 있고, 감사 로그에서 그 어긋남은 사후 조사하려고
기록을 꺼낼 때에야 드러난다.

### 대가 — `.dockerignore` 가 반드시 필요하다

`agents/` 는 461MB 이고 그중 451MB 가 Java 빌드 산출물이다.
`agents/.dockerignore` 가 이것을 막는다. **실측: 컨텍스트 전송 514KB.**

Docker 는 컨텍스트 루트의 `.dockerignore` 만 읽으므로
`agents/consultation/.dockerignore` 는 더 이상 읽히지 않는다. 제외 규칙은
`agents/.dockerignore` 에 적어야 한다.

### 로컬 실행

이미지 안에서는 `/app/harness_core` 지만, 로컬에서는 경로를 얹어야 한다.
consultation 테스트는 `tests/conftest.py` 가 처리한다. 직접 띄울 때는:

```bash
PYTHONPATH=../harness-core/python uvicorn app.main:app
```
