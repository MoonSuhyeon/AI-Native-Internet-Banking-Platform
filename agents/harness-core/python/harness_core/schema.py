"""감사 스키마를 적용한다.

한 줄짜리로 보이지만 그렇지 않아서 여기 둔다. 감사 테이블 정의에는
INSERT-ONLY 를 강제하는 PL/pgSQL 트리거가 들어 있고, 그 본문에 이런 줄이 있다.

    RAISE EXCEPTION 'harness_audit_log 는 추가만 가능합니다 (시도: %)', TG_OP;

여기의 ``%)`` 를 psycopg 가 **파라미터 플레이스홀더로 해석해서** 실행이 실패한다
(``only '%s', '%b', '%t' are allowed as placeholders, got '%)'``).
파라미터를 안 넘겨도 드라이버가 문자열을 훑기 때문에 생긴다.

그래서 raw DBAPI 커서로 파라미터 없이 실행한다. 에이전트마다 이 사실을 다시
알아내게 두면 두 번째·세 번째 에이전트가 같은 자리에서 막힌다 — 실제로
consultation 과 goal-agent 가 같은 방식으로 짜여 둘 다 깨져 있었다.
"""

from __future__ import annotations

import logging
from contextlib import suppress
from pathlib import Path

logger = logging.getLogger(__name__)


def apply_schema_sql(engine, sql_path: str | Path) -> bool:
    """SQL 파일을 통째로 실행한다.

    문장 단위로 쪼개지 않는다. PL/pgSQL 함수 본문에 세미콜론이 있어
    쪼개면 함수 정의가 두 동강 난다.

    Args:
        engine: SQLAlchemy Engine
        sql_path: 실행할 SQL 파일

    Returns:
        적용됐으면 True. 실패해도 예외를 올리지 않는다 —
        감사 테이블이 없다고 서비스 기동이 막히면 안 된다. 다만 조용히
        넘어가지 않도록 반드시 로그를 남긴다.
    """
    path = Path(sql_path)
    if not path.exists():
        logger.error("감사 스키마 파일 없음: %s — 감사 기록이 남지 않는다", path)
        return False

    sql = path.read_text(encoding="utf-8")

    # 접속 획득도 try 안에 둔다. 밖에 두면 SQL 실패만 삼키고 **접속 실패는 그대로
    # 올라간다** — DB 가 잠깐 안 뜬 상태에서 기동하면 서비스가 통째로 죽는다.
    # 위 계약("실패해도 예외를 올리지 않는다")과 어긋나던 자리다.
    raw = None
    try:
        raw = engine.raw_connection()
        with raw.cursor() as cur:
            # 파라미터를 넘기지 않는다. 넘기는 순간 트리거 본문의 % 가 해석된다.
            cur.execute(sql)
        raw.commit()
        logger.info("감사 스키마 적용 완료: %s", path.name)
        return True
    except Exception:
        # 접속이 죽은 상태면 rollback 자체도 던진다. 정리하다 원래 실패를 덮지 않는다.
        if raw is not None:
            with suppress(Exception):
                raw.rollback()
        logger.exception("감사 스키마 적용 실패: %s — 기록이 남지 않는다", path)
        return False
    finally:
        if raw is not None:
            with suppress(Exception):
                raw.close()
