"""감사 스키마 적용의 계약 — 실패해도 기동을 막지 않는다.

독스트링은 "실패해도 예외를 올리지 않는다" 라고 적혀 있었지만 코드는 그렇지 않았다.
``engine.raw_connection()`` 이 try 밖에 있어서 **SQL 실패만 삼키고 접속 실패는 그대로
올라갔다.** DB 가 잠깐 안 뜬 상태로 기동하면 감사 테이블 하나 때문에 서비스가 통째로
죽는다 — 감사는 fail-soft 여야 한다는 설계와 정반대다.

여기 테스트는 그 계약을 문장이 아니라 코드로 못박는다.
"""

from __future__ import annotations

import logging

import pytest

from harness_core.schema import apply_schema_sql


class _DeadEngine:
    """접속 자체가 안 되는 엔진. DB 가 안 뜬 상태를 흉내낸다."""

    def raw_connection(self):
        raise OSError("connection refused")


class _FailingCursor:
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def execute(self, sql):
        raise RuntimeError("permission denied for schema")


class _RollbackAlsoFailsConnection:
    """실행도 실패하고 정리도 실패하는 접속. 접속이 죽으면 실제로 이렇게 된다."""

    def cursor(self):
        return _FailingCursor()

    def rollback(self):
        raise OSError("connection already closed")

    def close(self):
        raise OSError("connection already closed")


class _Engine:
    def __init__(self, conn):
        self._conn = conn

    def raw_connection(self):
        return self._conn


@pytest.fixture()
def sql_file(tmp_path):
    path = tmp_path / "audit.sql"
    path.write_text("CREATE TABLE t (a int);", encoding="utf-8")
    return path


class TestFailSoft:
    def test_접속_실패가_기동을_막지_않는다(self, sql_file):
        # 이 자리가 뚫려 있었다. raw_connection() 이 try 밖이라 예외가 그대로 올라갔다.
        assert apply_schema_sql(_DeadEngine(), sql_file) is False

    def test_실행_실패도_삼킨다(self, sql_file):
        engine = _Engine(_RollbackAlsoFailsConnection())
        assert apply_schema_sql(engine, sql_file) is False

    def test_정리하다_난_예외가_원래_실패를_덮지_않는다(self, sql_file):
        # rollback·close 가 던져도 호출부는 False 만 본다. 정리 실패로 바꿔치기되면
        # 로그에 남은 원인이 "connection already closed" 가 되어 진짜 원인을 잃는다.
        engine = _Engine(_RollbackAlsoFailsConnection())
        assert apply_schema_sql(engine, sql_file) is False

    def test_파일이_없으면_로그를_남기고_False_다(self, sql_file, caplog):
        with caplog.at_level(logging.ERROR):
            assert apply_schema_sql(_DeadEngine(), sql_file.parent / "없는파일.sql") is False
        assert "감사 스키마 파일 없음" in caplog.text

    def test_실패는_조용히_넘어가지_않는다(self, sql_file, caplog):
        # fail-soft 의 대가는 침묵이다. 로그가 없으면 감사가 멈춘 걸 알 방법이 없다.
        with caplog.at_level(logging.ERROR):
            apply_schema_sql(_DeadEngine(), sql_file)
        assert "감사 스키마 적용 실패" in caplog.text
