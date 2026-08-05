"""DB 초기화 스크립트.

사용법:
  python init_db.py           -- 테이블 생성만 (스키마 유지)
  python init_db.py --drop    -- 스키마 전체 드롭 후 재생성 (데이터 전부 삭제됨, 주의)

DATABASE_URL 환경변수가 없으면 config.py 기본값을 사용합니다.
"""
import sys
from pathlib import Path

from sqlalchemy import text
from app.database import Base, engine
from app import models  # noqa: F401  모델 등록
from harness_core.schema import apply_schema_sql

#: 하네스 감사 로그. SQLAlchemy 모델로 만들지 않고 SQL 을 그대로 실행한다 —
#: INSERT-ONLY 를 강제하는 트리거가 계약의 일부인데 모델로는 표현되지 않기 때문이다.
#: 원본은 harness-core 이고 이 파일은 사본이다 (test_audit_contract.py 가 대조한다).
HARNESS_AUDIT_SQL = Path(__file__).resolve().parent / "sql" / "harness-audit.sql"


def main(drop: bool = False) -> None:
    with engine.begin() as conn:
        if drop:
            print("WARNING: DROP SCHEMA public CASCADE 를 실행합니다. 데이터가 모두 삭제됩니다.")
            conn.execute(text("DROP SCHEMA public CASCADE"))
            conn.execute(text("CREATE SCHEMA public"))
            conn.execute(text("GRANT ALL ON SCHEMA public TO PUBLIC"))
            print("스키마 재생성 완료.")
        Base.metadata.create_all(engine)

    # 감사 스키마는 harness_core 가 적용한다. 트리거 본문의 % 를 드라이버가
    # 파라미터로 오해하는 함정이 있어 raw 커서로 넣어야 한다 — 직접 실행하면 깨진다.
    if apply_schema_sql(engine, HARNESS_AUDIT_SQL):
        print("하네스 감사 로그 테이블 준비 완료.")
    else:
        print("경고: 감사 로그 테이블을 만들지 못했다 — 에이전트 권고가 남지 않는다.")
    print("완료")


if __name__ == "__main__":
    drop_flag = "--drop" in sys.argv
    main(drop=drop_flag)
