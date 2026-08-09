-- ============================================================
-- Stage 4 — ivfflat lists 실측 rows 기반 재산정 (V30)
--
-- 목적: V27 이 소량 가정으로 lists=10 을 박아뒀으나,
--        Stage 2(정책 시드) + Stage 3(케이스 백필) 이후
--        실 rows 를 보고 lists 를 재산정해 재인덱싱.
--
-- 공식 (pgvector 권장):
--   rows ≤ 1,000,000 →  lists = max(10, ceil(rows / 1000))
--   rows >  1,000,000 →  lists = ceil(sqrt(rows))
--
-- rows < 10,000 이면 사실상 seq scan 이 더 빠르지만
-- ivfflat 인덱스를 유지해 운영 전환 시 즉시 사용 가능하도록 함.
-- probes 권장값: max(1, lists / 10) — 연결/트랜잭션 수준에서 설정.
-- ============================================================

DO $$
DECLARE
    chunk_count bigint;
    case_count  bigint;
    chunk_lists int;
    case_lists  int;
BEGIN
    SELECT count(*) INTO chunk_count FROM advisory_document_chunk;
    SELECT count(*) INTO case_count  FROM advisory_case_index;

    -- lists 산정
    IF chunk_count > 1_000_000 THEN
        chunk_lists := CEIL(SQRT(chunk_count::numeric))::int;
    ELSE
        chunk_lists := GREATEST(10, CEIL(chunk_count::numeric / 1000)::int);
    END IF;

    IF case_count > 1_000_000 THEN
        case_lists := CEIL(SQRT(case_count::numeric))::int;
    ELSE
        case_lists := GREATEST(10, CEIL(case_count::numeric / 1000)::int);
    END IF;

    RAISE NOTICE 'advisory_document_chunk : rows=%, target_lists=%', chunk_count, chunk_lists;
    RAISE NOTICE 'advisory_case_index     : rows=%, target_lists=%', case_count,  case_lists;

    -- chunk 임베딩 인덱스 재생성
    DROP INDEX IF EXISTS idx_advisory_document_chunk_embedding;
    EXECUTE format(
        'CREATE INDEX idx_advisory_document_chunk_embedding '
        'ON advisory_document_chunk USING ivfflat (embedding vector_cosine_ops) '
        'WITH (lists = %s)',
        chunk_lists
    );
    RAISE NOTICE 'idx_advisory_document_chunk_embedding 재생성 완료 (lists=%)', chunk_lists;

    -- case 임베딩 인덱스 재생성
    DROP INDEX IF EXISTS idx_advisory_case_index_embedding;
    EXECUTE format(
        'CREATE INDEX idx_advisory_case_index_embedding '
        'ON advisory_case_index USING ivfflat (embedding vector_cosine_ops) '
        'WITH (lists = %s)',
        case_lists
    );
    RAISE NOTICE 'idx_advisory_case_index_embedding 재생성 완료 (lists=%)', case_lists;
END $$;
