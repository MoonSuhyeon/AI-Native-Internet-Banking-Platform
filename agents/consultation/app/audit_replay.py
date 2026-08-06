"""감사 스풀 되돌리기.

DB 가 흔들리는 동안 저장에 실패한 감사 기록은 스풀 파일에 쌓인다
(app/audit.py 의 get_audit_spool). DB 가 돌아오면 이것으로 다시 넣는다.

    python -m app.audit_replay              # 설정된 스풀을 되돌린다
    python -m app.audit_replay --dry-run    # 몇 건 남아 있는지만 본다
    python -m app.audit_replay --path ...   # 다른 스풀 파일을 지정

되돌릴 때 ``recorded_at`` 은 원래 판단 시각 그대로 들어간다. 복구 시각으로 덮으면
기록은 남아도 타임라인이 거짓이 되고, "언제 그렇게 판단했는가"에 답할 수 없다.

되돌리지 못한 것은 스풀에 남는다. 여러 번 돌려도 안전하다 — 성공한 것만 지운다.
"""

from __future__ import annotations

import argparse
import logging
import sys

from app.audit import get_audit_log, get_audit_spool
from harness_core import FileAuditSpool

logger = logging.getLogger(__name__)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="감사 스풀을 DB 에 다시 넣는다")
    parser.add_argument("--path", default=None, help="스풀 파일 경로 (기본: 설정값)")
    parser.add_argument(
        "--dry-run", action="store_true", help="넣지 않고 남은 건수만 본다"
    )
    args = parser.parse_args(argv)

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")

    spool = FileAuditSpool(args.path) if args.path else get_audit_spool()
    if spool is None:
        print("스풀 경로가 설정돼 있지 않다 (CONSULTATION_HARNESS_AUDIT_SPOOL_PATH).")
        return 2

    pending = spool.pending()
    if not pending:
        print(f"되돌릴 기록이 없다: {spool.path}")
        return 0

    if args.dry_run:
        print(f"{len(pending)}건이 스풀에 남아 있다: {spool.path}")
        oldest = min(entry.recorded_at for entry in pending)
        newest = max(entry.recorded_at for entry in pending)
        print(f"기간: {oldest.isoformat()} ~ {newest.isoformat()}")
        return 0

    restored, remaining = spool.replay(get_audit_log())
    print(f"되돌림 {restored}건, 남음 {remaining}건 ({spool.path})")
    # 남은 것이 있으면 아직 DB 가 받지 못하는 상태다. 종료 코드로 드러낸다 —
    # 사람이 눈으로 읽는 출력만으로는 자동화가 성공으로 오해한다.
    return 1 if remaining else 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
