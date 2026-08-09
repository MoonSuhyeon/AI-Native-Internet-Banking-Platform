-- pgvector 확장 활성화 — AbstractLoanIntegrationTest 컨테이너 시작 시 실행
-- AdvisoryDocumentChunk / AdvisoryCaseIndex 의 VECTOR(1536) 컬럼 DDL 생성에 필요
CREATE EXTENSION IF NOT EXISTS vector;
