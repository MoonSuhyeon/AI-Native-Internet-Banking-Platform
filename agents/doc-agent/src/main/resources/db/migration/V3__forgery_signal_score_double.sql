-- loan_forgery_signal.score: 엔티티(ForgerySignalEntity.score=double)와 타입 일치
-- 기존 NUMERIC(3,2) → DOUBLE PRECISION 으로 변경하여 Hibernate 스키마 검증 통과
ALTER TABLE loan_forgery_signal
    ALTER COLUMN score TYPE DOUBLE PRECISION;
