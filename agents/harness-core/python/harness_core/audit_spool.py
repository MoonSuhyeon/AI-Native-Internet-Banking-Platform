"""감사 기록 유실을 되돌릴 수 있게 하는 자리.

**왜 필요한가.** 저장이 실패하면 fail-soft 로 넘어간다 — 고객 응답이나 조사를 막지
않기 위해서다. 그래서 DB 가 잠깐 흔들리면 그 사이의 판단 기록은 **영구 유실**이었다.
지표와 알람(다음 순서 2)은 유실을 *알려줄* 뿐 되돌려주지 못한다
(docs/decisions/agent-harness-consolidation.md 다음 순서 3).

**어떻게.** 실패한 기록을 한 줄 JSON 으로 파일에 덧붙여 두고, DB 가 돌아온 뒤
다시 밀어 넣는다. 실패 알림(``audit_failures``)에 그대로 얹히므로 저장 구현을
건드리지 않는다.

**되돌릴 때 시각을 다시 찍지 않는다.** ``recorded_at`` 은 원래 판단 시각 그대로
복원한다. 복구 시각으로 덮으면 기록은 남아도 타임라인이 거짓이 되고, 사후 조사에서
"언제 그렇게 판단했는가"에 답할 수 없다.

**한계 두 가지를 알고 쓴다.**
  - 파일은 프로세스가 도는 그 호스트에 있다. 컨테이너가 볼륨 없이 재시작하면 스풀도
    함께 사라진다. 볼륨을 붙이는 것은 배포 쪽 결정이라 여기서 강제하지 않는다.
  - 스풀에는 감사 본문이 그대로 들어간다. 감사 테이블과 같은 등급의 데이터가 파일로
    나가는 것이므로, 접근 권한을 테이블과 같은 수준으로 두어야 한다. 파일은 가능한
    환경에서 0600 으로 만든다.

크기 상한은 두지 않았다. 넘치면 버리는 장치를 넣는 순간 "조용한 유실"이 다시 생기고,
그것이 이 파일이 없애려는 것이다. 스풀이 커지는 것 자체가 DB 가 오래 죽어 있다는
신호이며, 그 신호는 알람(harness-audit 그룹)이 이미 잡는다.
"""

from __future__ import annotations

import json
import logging
import os
from datetime import datetime
from pathlib import Path

from .audit import AgentAuditEntry

logger = logging.getLogger(__name__)

#: 파일 한 줄에 담기는 필드. AgentAuditEntry 의 필드와 1:1 이며 순서는 무관하다.
_FIELDS = (
    "agent_name", "subject_type", "subject_id", "trace_id",
    "request_json", "output_json", "tool_calls_json",
    "raw_llm_response", "pii_masked", "fallback_reason",
    "recorded_at", "decision_kind", "actor_id", "actor_roles",
)


def _to_line(entry: AgentAuditEntry) -> str:
    data = {name: getattr(entry, name) for name in _FIELDS}
    data["recorded_at"] = entry.recorded_at.isoformat()
    return json.dumps(data, ensure_ascii=False)


def _from_line(line: str) -> AgentAuditEntry:
    data = json.loads(line)
    data["recorded_at"] = datetime.fromisoformat(data["recorded_at"])
    return AgentAuditEntry(**{name: data[name] for name in _FIELDS})


class FileAuditSpool:
    """실패한 감사 기록을 파일에 모아 두었다가 다시 넣는다.

    Args:
        path: 스풀 파일 경로. 없으면 만든다.
    """

    def __init__(self, path: str | os.PathLike[str]) -> None:
        self.path = Path(path)
        self._replaying = False

    # ── 쌓기 ──────────────────────────────────────────────────────────────────

    def on_failure(self, entry: AgentAuditEntry, exc: BaseException) -> None:
        """``add_audit_failure_listener`` 에 그대로 넘길 수 있는 리스너.

        되돌리는 중에 난 실패는 다시 쌓지 않는다 — 쌓으면 같은 기록이 파일에 두 번
        들어가고, 복구했을 때 감사 로그에 중복 행이 생긴다.
        """
        if self._replaying:
            return
        self.append(entry)

    def append(self, entry: AgentAuditEntry) -> None:
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with self.path.open("a", encoding="utf-8") as fp:
                fp.write(_to_line(entry) + "\n")
            self._restrict_permissions()
        except Exception:
            # 여기서 예외를 올리면 감사 실패가 본래 흐름을 무너뜨린다. 그것이
            # fail-soft 를 택한 이유였고, 복구 장치가 그 이유를 되돌리면 안 된다.
            logger.exception("감사 스풀 기록 실패 path=%s", self.path)

    def _restrict_permissions(self) -> None:
        """감사 본문이 든 파일이므로 주인만 읽게 한다. 불가능한 OS 면 넘어간다."""
        try:
            os.chmod(self.path, 0o600)
        except OSError:
            pass

    # ── 읽기·되돌리기 ─────────────────────────────────────────────────────────

    def pending(self) -> list[AgentAuditEntry]:
        """아직 되돌리지 못한 기록. 깨진 줄은 건너뛰고 로그로 알린다."""
        if not self.path.exists():
            return []
        entries: list[AgentAuditEntry] = []
        for number, line in enumerate(self.path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            try:
                entries.append(_from_line(line))
            except Exception:
                logger.exception("스풀 %s 의 %d 번째 줄을 읽지 못했다", self.path, number)
        return entries

    def replay(self, audit_log) -> tuple[int, int]:
        """모아 둔 기록을 저장소에 다시 넣는다.

        저장 구현은 실패해도 예외를 올리지 않는다(fail-soft). 그래서 성공 여부는
        실패 알림으로 판단한다 — 되돌리는 동안만 리스너를 붙여 실패한 것을 가려낸다.

        Returns:
            (되돌린 건수, 아직 남은 건수)
        """
        from .audit_failures import add_audit_failure_listener, remove_audit_failure_listener

        entries = self.pending()
        if not entries:
            return (0, 0)

        failed: list[AgentAuditEntry] = []

        def _mark_failed(entry: AgentAuditEntry, exc: BaseException) -> None:
            failed.append(entry)

        self._replaying = True
        add_audit_failure_listener(_mark_failed)
        try:
            for entry in entries:
                audit_log.record(entry)
        finally:
            remove_audit_failure_listener(_mark_failed)
            self._replaying = False

        self._rewrite(failed)
        return (len(entries) - len(failed), len(failed))

    def _rewrite(self, remaining: list[AgentAuditEntry]) -> None:
        """남은 것만 남기고 파일을 다시 쓴다.

        되돌리는 사이에 새로 쌓인 줄이 있으면 지워질 수 있다. 그래서 임시 파일에 쓰고
        바꿔치기하지 않고, 남은 것을 그대로 덮어쓴다 — 이 스풀은 한 프로세스가 쓰는
        것을 전제로 한다. 여러 프로세스가 같은 파일을 쓰려면 잠금이 필요하고, 그때는
        파일이 아니라 중앙 저장소(Kafka)로 가는 것이 맞다.
        """
        try:
            if not remaining:
                self.path.unlink(missing_ok=True)
                return
            self.path.write_text(
                "".join(_to_line(entry) + "\n" for entry in remaining), encoding="utf-8"
            )
            self._restrict_permissions()
        except Exception:
            logger.exception("감사 스풀 정리 실패 path=%s", self.path)
