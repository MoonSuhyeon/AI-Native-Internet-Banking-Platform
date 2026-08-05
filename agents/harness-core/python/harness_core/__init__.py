"""harness-core — Python 런타임용 계약.

Java 모듈과 같은 이름을 쓰는 것은 우연이 아니다. 두 런타임이 **같은 하네스**를
구현한다. 공유되는 것은 코드가 아니라 계약이다 (스키마·필드명·의미).

무엇이 여기 들어오는가의 기준은 Java 쪽과 같다 — 도메인 지식이 들어가는가.
대출 심사 의견도 사기 점수도 상담 의도도 여기 들어오지 않는다.
들어오는 순간 다른 도메인이 쓸 수 없게 되고, 그러면 하네스가 아니다.

배경: docs/decisions/agent-harness-consolidation.md
"""

from .audit import KIND_DECISION, AgentAuditEntry, AgentAuditLog, NoOpAgentAuditLog

__all__ = ["AgentAuditEntry", "AgentAuditLog", "NoOpAgentAuditLog", "KIND_DECISION"]
