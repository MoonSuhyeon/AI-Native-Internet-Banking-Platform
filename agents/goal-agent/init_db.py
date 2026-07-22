"""DB 초기화 스크립트.

사용법:
  python init_db.py           -- 테이블 생성만 (스키마 유지)
  python init_db.py --drop    -- 스키마 전체 드롭 후 재생성 (데이터 전부 삭제됨, 주의)

DATABASE_URL 환경변수가 없으면 config.py 기본값을 사용합니다.
"""
import sys
from sqlalchemy import text
from app.database import Base, engine
from app import models  # noqa: F401  모델 등록


def main(drop: bool = False) -> None:
    with engine.begin() as conn:
        if drop:
            print("WARNING: DROP SCHEMA public CASCADE 를 실행합니다. 데이터가 모두 삭제됩니다.")
            conn.execute(text("DROP SCHEMA public CASCADE"))
            conn.execute(text("CREATE SCHEMA public"))
            conn.execute(text("GRANT ALL ON SCHEMA public TO PUBLIC"))
            print("스키마 재생성 완료.")
        Base.metadata.create_all(engine)
    print("완료")


if __name__ == "__main__":
    drop_flag = "--drop" in sys.argv
    main(drop=drop_flag)
