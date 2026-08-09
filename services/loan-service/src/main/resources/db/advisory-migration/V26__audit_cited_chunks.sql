-- V8: ai_audit_opinion 에 LLM 인용 청크 ID 목록 컬럼 추가
-- JSON 배열 형식 (예: [123, 456]) — NULL 허용 (기존 레코드·인용 없는 경우)
ALTER TABLE ai_audit_opinion
    ADD COLUMN cited_chunk_ids TEXT;
