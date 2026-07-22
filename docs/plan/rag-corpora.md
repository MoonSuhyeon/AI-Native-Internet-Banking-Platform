# RAG 3 코퍼스 구축 — 큰 그림

> Last updated: 2026-05-22
> Status: design draft (Phase 1.7. LLM 파이프라인의 grounding 근거 공급원)
> 선행 문서: `docs/plan/banking-review-llm.md` §6, `docs/plan/llm-pipeline.md` §14

본 문서는 LLM 리포트의 인용·근거 공급원으로서 3개 코퍼스 (유사 케이스 / 정책·규제 / 사내
FAQ) 를 pgvector 위에 구축·서빙하는 계획. LLM 파이프라인 (Phase 1.5/1.6) 의 `Citation.id`
swap 만으로 인라인 policy text → RAG 전환 가능하도록 인터페이스 정렬.

---

## 0. 전제·원칙

1. **벡터 스토어 = PostgreSQL pgvector** (`ai_db` 에 얹기, 별도 Pinecone X). 운영·MRM 단순화.
2. **임베딩 = bge-m3** (1024 차원, 한국어 강점, multilingual). Vertex AI Embedding API PoC →
   자체 ONNX (inference-server) 운영 옵션 보유.
3. **하이브리드 검색** — 벡터 cosine + FTS BM25 가중합. Track 3 한정 rerank 추가.
4. **Grounding 강제** — LLM 리포트의 모든 인용은 RAG chunk id 참조 필수. 누락 시 리포트
   거절 → 템플릿 fallback (llm-pipeline §5.4 GroundingValidator).
5. **Track 별 호출 cap** — Track 1·2 는 코퍼스당 1회 (비용 통제), Track 3 는 자율 (LLM agent
   가 tool 선택).
6. **wire 호환** — LLM 파이프라인 (llm-pipeline §14) 의 `Citation.id` 가 곧 RAG chunk id.
   prompt YAML 의 `tools:` 섹션 추가만으로 LLM agent 가 자율 호출.
7. **메타 필터링** — `(product, segment)`, `effective_date`, `corpus` 로 검색 결과 좁힘.
8. **인덱스 버저닝** — 임베딩 모델 / 청킹 룰 변경 시 별도 버전 컬럼으로 무중단 교체.
9. **PII 안전** — 유사 케이스 코퍼스의 신청서 원문에서 이름·전화·계좌번호 마스킹 (인덱싱 전).
10. **운영 ON/OFF** — `ai.rag.enabled` kill switch (1.9.2 패턴). RAG OFF 시 인라인 policy
    text fallback (llm-pipeline §14.1).

---

## 1. 3 코퍼스 명세

| 코퍼스 | 용도 | 출처 | 청크 단위 | 갱신 주기 | 운영 크기 (예상) |
|--------|------|------|----------|----------|------------------|
| **kb_similar_cases** | Track 3 "유사 과거 5건" + 심사원 검색 | LOAN_REVIEW 누적 + 합성 데이터 (HMDA·Home Credit 변환 결과) | 신청 1건 = 1 청크 (페르소나 요약 + 결정 + 사유) | **일 1회** batch (전일 LOAN_REVIEW 증분) | 100K~1M chunks |
| **kb_policy_regulation** | 모든 트랙 리포트 인용 — 거절 사유·승인 근거의 법·정책 출처 | 금감원 가이드, 자행 여신정책서, 신용정보법, 정책 매트릭스 (RuleEngineProperties.yml), 가계대출규제 | 조항 단위 (제N조 정규식) | **분기 1회** + 정책 변경 시 hot-reload | 1K~5K chunks |
| **kb_internal_faq** | Track 3 심사원 Q&A | 사내 wiki, FAQ, 심사부 매뉴얼 | Q/A 쌍 = 1 청크 | **월 1회** | 500~2K chunks |

→ 우선순위: **kb_policy_regulation (P1) > kb_similar_cases (P2) > kb_internal_faq (P3)**.
   정책 인용 없는 거절 리포트는 법적 리스크 직결, FAQ 는 Track 3 한정이라 가장 후순위.

---

## 2. 벡터 스토어 — pgvector 스키마 *(historical — Phase D 계획, Phase E 에서 ES 로 대체됨)*

### 2.1 통합 테이블 (3 코퍼스 공통)

```sql
-- ai_db
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;   -- FTS 보조 (한국어 trigram fallback)
-- CREATE EXTENSION IF NOT EXISTS pgroonga;  -- 한국어 BM25 (운영 검토)

CREATE TABLE ai_embedding (
    id              bigserial PRIMARY KEY,
    corpus          text NOT NULL,                     -- 'similar_cases' / 'policy_regulation' / 'internal_faq'
    source_id       text NOT NULL,                     -- 코퍼스별 원본 키 (LOAN_REVIEW.rev_id, policy doc id, faq id)
    chunk_seq       smallint NOT NULL DEFAULT 0,       -- 한 source 가 여러 chunk 일 때
    chunk_text      text NOT NULL,                     -- 청크 본문 (검색 결과 표시용 raw)
    chunk_summary   text,                              -- LLM 입력용 짧은 요약 (선택)
    embedding       vector(1024) NOT NULL,             -- bge-m3
    embedding_model text NOT NULL DEFAULT 'bge-m3-v1', -- 모델 버전 (재인덱싱 추적)
    metadata        jsonb NOT NULL DEFAULT '{}'::jsonb,
    fts_tokens      tsvector,                          -- FTS 보조 (PostgreSQL ts_vector)
    effective_date  date,                              -- 정책: 발효일 / 케이스: 결정일 / FAQ: 최종 갱신일
    expiry_date     date,                              -- 정책: 폐지일 (있으면), 그 외 NULL
    is_active       boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (corpus, source_id, chunk_seq, embedding_model)
);

-- 벡터 인덱스 (IVFFlat — 100만 chunk 이하 충분, HNSW 는 운영 확장 시 검토)
CREATE INDEX ai_embedding_vec_idx ON ai_embedding
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- 메타 필터 인덱스
CREATE INDEX ai_embedding_corpus_idx ON ai_embedding (corpus) WHERE is_active;
CREATE INDEX ai_embedding_meta_gin_idx ON ai_embedding USING gin (metadata);
CREATE INDEX ai_embedding_fts_idx ON ai_embedding USING gin (fts_tokens);
CREATE INDEX ai_embedding_effective_idx ON ai_embedding (corpus, effective_date DESC)
    WHERE is_active;
```

### 2.2 metadata 스키마 (코퍼스별)

**similar_cases**:
```json
{
  "decision_label": "APPROVE | REJECT | TRACK_1 | TRACK_2 | TRACK_3",
  "product_code": "MORT_001",
  "segment": "regular | young | senior | precarious | self_employed",
  "requested_amount_kw": 50000000,
  "pd_score": 0.07,
  "denial_reason_primary": "DTI" /* REJECT 만 */,
  "review_round": 1
}
```

**policy_regulation**:
```json
{
  "source": "internal_credit_policy_2026q2 | fss_guideline_2024 | credit_info_act",
  "article_no": "§3.2.1",
  "matrix_coord": { "product": "MORT_001", "segment": "regular" } /* 매트릭스 셀 매핑 */,
  "tags": ["DSR", "한도"]
}
```

**internal_faq**:
```json
{
  "category": "심사절차 | 서류검증 | 정책해석 | 시스템사용",
  "author": "심사부",
  "version": 3
}
```

### 2.3 IVFFlat → HNSW 마이그레이션 trigger

- chunk 수 100만 초과 또는 p99 검색 latency > 200ms 시 HNSW (`vector_cosine_ops`) 로 교체
- 무중단: 신규 인덱스 CONCURRENTLY 생성 → ANALYZE → DROP 구 인덱스

---

## 3. 임베딩 모델 — bge-m3

### 3.1 선택 이유

- **다국어** (100+ 언어) — 한국어·영어 혼용 정책 문서 동시 처리
- **1024 차원** — 검색 품질·저장 비용 균형
- **multi-functionality** — dense (벡터) + sparse (BM25 가중) + multi-vector (rerank 베이스)
   동시 산출 가능. 본 phase MVP 는 dense 만 사용, sparse 는 §6 rerank 에서 검토
- **MIT 라이선스** — 상업 사용 자유

### 3.2 호스팅 옵션

| 옵션 | 장단점 | 단계 |
|------|--------|------|
| **A. Vertex AI Embedding API** (text-embedding-005 또는 bge-m3 endpoint) | 운영 부담 X, latency p99 < 300ms, 분당 RPM 제한 | PoC |
| **B. 자체 ONNX (inference-server)** | latency p99 < 50ms (CPU), 무제한 RPM, 모델 버전 통제 | 운영 (초기 트래픽 기준 검토) |
| **C. 별도 Embedding 마이크로서비스** (FastAPI + sentence-transformers) | B 와 유사, Python 친화 | B 가 적합 시 보류 |

**MVP**: A 로 시작, 운영 트래픽 → B 로 swap (`ai.rag.embedding.provider` 키).

### 3.3 임베딩 호출 추상

```java
public interface EmbeddingClient {
    float[] embed(String text);              // 단건
    List<float[]> embed(List<String> texts); // 배치 (인덱싱 시)
}

// 구현: VertexAiEmbeddingClient / OnnxBgeM3EmbeddingClient
```

`spring.ai.vertexai.embedding.options.model=text-embedding-005` (PoC) 또는 자체 클라이언트.

---

## 4. 청킹 전략

### 4.1 kb_similar_cases — 신청 1건 = 1 청크

**chunk_text 템플릿**:
```
[페르소나]
성별 {sex}, 연령 {age_band}, 직업 {occupation}, 소득분위 {income_quintile},
세그먼트 {applicant_segment}, 지역 {province}

[신청]
상품 {product_code}, 금액 {requested_amount_kw}만원, 기간 {requested_period_mo}개월,
용도 {purpose_cd} — {purpose_text_summary}

[지표]
DSR {dsr}, LTV {ltv}, NICE {credit_score_proxy}, 연체이력 {delinquency_history_24m},
PD {pd_score}

[결정]
{decision_label} ({reviewer_id: SYSTEM | human}), 사유: {denial_reason_primary or rationale_summary}
```

**chunk_summary**: 1줄 요약 (LLM 입력 토큰 절약용)
**embedding**: chunk_text 통째로 임베딩

**메타**: §2.2 similar_cases 참조.

**PII 마스킹** (인덱싱 전 필수):
- 이름·주민번호·계좌번호·전화번호 → `[MASKED]`
- 페르소나 요약은 segment / age_band 수준까지만 (raw age 도 노출 X)

### 4.2 kb_policy_regulation — 조항 단위

**파서**: 정규식 + 휴리스틱
- 조항 마커: `제\s*\d+\s*조`, `§\s*\d+(\.\d+)*`, `\d+\.\d+\.\d+`
- 부조항 결합: `제1조 ① ... ② ...` → 항 단위 별도 chunk
- 정책 매트릭스 (RuleEngineProperties.yml) 의 각 cell → 1 chunk (자동 생성)

**chunk_text 예시**:
```
[정책] 자행 여신정책서 §3.2.1 — 주담대 DSR 한도

주담대 정상 직장인 고객의 DSR 한도는 40% 이하로 설정한다.
근거: 금감원 가계대출 종합관리방안 (2024-03), 한국은행 금융안정보고서 2023H2.
적용 매트릭스: product=MORT_001, segment=regular, threshold=0.40.

[변경 이력]
2024-04: 50% → 40% (가계부채 안정화 정책 반영)
```

**chunk_summary**: 핵심 한 줄 ("주담대 DSR ≤ 40%")
**메타**: §2.2 policy_regulation, `matrix_coord` 채워두면 Track 분기 결과로 자동 인용 가능

### 4.3 kb_internal_faq — Q/A 쌍

**chunk_text 템플릿**:
```
Q. {question}
A. {answer}

(태그: {tags}, 작성: {author}, 갱신: {updated_at})
```

청크 1개 = Q/A 1쌍.
**embedding 대상**: question + answer 통째로 (질문만 임베딩하면 의미 부족).

### 4.4 청킹 공통 룰

- 최대 토큰: 청크당 512 tokens 권장 (bge-m3 컨텍스트 8K 이지만 검색 정확도 위해 짧게)
- 초과 시: 의미 단위 split (조항·항 등) → 다중 chunk_seq
- 메타에 원본 위치 (page, section) 기록 → 심사원 UI 가 원본 링크 가능

---

## 5. 하이브리드 검색

### 5.1 검색 SQL (개념)

```sql
WITH vec AS (
    SELECT id, source_id, chunk_text, chunk_summary, metadata,
           1 - (embedding <=> :query_vec) AS vec_score
    FROM ai_embedding
    WHERE corpus = :corpus
      AND is_active
      AND (:matrix_coord IS NULL
           OR metadata @> :matrix_coord)
    ORDER BY embedding <=> :query_vec
    LIMIT 50
),
fts AS (
    SELECT id, ts_rank_cd(fts_tokens, to_tsquery('simple', :query_tsq)) AS fts_score
    FROM ai_embedding
    WHERE corpus = :corpus AND is_active
      AND fts_tokens @@ to_tsquery('simple', :query_tsq)
    LIMIT 50
)
SELECT v.id, v.source_id, v.chunk_text, v.chunk_summary, v.metadata,
       (:alpha * v.vec_score + (1 - :alpha) * COALESCE(f.fts_score, 0)) AS hybrid_score
FROM vec v
LEFT JOIN fts f USING (id)
ORDER BY hybrid_score DESC
LIMIT :k;
```

`alpha = 0.7` (벡터 가중 — 의미 검색 우선), threshold 0.5 미만은 결과 제외.

### 5.2 한국어 토큰화 — 3 옵션

| 옵션 | 장단점 | 결정 |
|------|--------|------|
| A. PostgreSQL `simple` config + 공백/구두점 split | 설치 부담 X, 한국어 형태소 인식 X → 검색 정확도 ↓ | MVP 시작점 |
| B. `pg_trgm` trigram + simple FTS | 부분일치 강함, 의미 검색은 임베딩이 보완 | MVP 추천 (A + pg_trgm) |
| C. **pgroonga** 확장 (Groonga 기반 한국어 토크나이저) | 한국어 BM25 정확, 인덱스 빠름, 운영 안정 | 운영 확장 시 마이그레이션 |
| D. Mecab-ko / Nori 외부 토크나이저 (Java/Python preprocessor) | 정확도 최고, 인프라 복잡 | 보류 |

**MVP**: B (`pg_trgm` + simple FTS) + 벡터 가중 0.7. 운영 chunk 수 5만 초과 또는 검색
정확도 부족 시 C 로 마이그레이션.

### 5.3 검색 함수 (Java)

```java
public interface RagSearchService {
    List<Chunk> search(
        String corpus,
        String query,
        Map<String, Object> metaFilter,   // 예: { matrix_coord: { product: "MORT_001", segment: "regular" } }
        int k,
        double similarityThreshold
    );
}

public record Chunk(
    long id,
    String corpus,
    String sourceId,
    String text,
    String summary,
    Map<String, Object> metadata,
    double hybridScore
) {}
```

---

## 6. 재순위 (Rerank) — Track 3 한정

### 6.1 모델

**bge-reranker-v2-m3** (BAAI, 한국어 지원, cross-encoder).
- 입력: (query, candidate) 쌍 → relevance score
- 1차 hybrid search top-50 → rerank → top-5

### 6.2 호스팅

- inference-server 에 `/rerank` endpoint 추가
- 입력: `{ query: string, candidates: string[] }` → 출력: `[{ index, score }]`
- ONNX export (sentence-transformers cross-encoder → ONNX)

### 6.3 적용 트랙

- **Track 3 한정**: cost 통제 + Track 1·2 는 검색 1회 cap
- Track 3 검색 시 `rerank=true` 플래그
- p99 latency 추가 < 500ms 목표 (50 후보 × cross-encoder)

---

## 7. 메타 필터링

### 7.1 자동 필터

| 코퍼스 | 필터 |
|--------|------|
| kb_similar_cases | `(product, segment)` 일치 우선 (검색 시 자동 부여) |
| kb_policy_regulation | `matrix_coord` (Track 분기 결과에서 자동 도출), `effective_date <= now() < expiry_date OR expiry_date IS NULL` |
| kb_internal_faq | `category` (질의 분류에서 도출, 선택) |

### 7.2 retrieve_policy 의 matrix_coord 자동 주입

Track 분기 결과의 `(product, segment)` 를 LLM agent 가 명시하지 않아도 `RagSearchService`
가 자동 필터 부여. 정책 인용이 매트릭스 셀과 자동 매칭 (banking-review-llm §6.5).

---

## 8. Spring AI Tool 명세

LLM agent (ReviewReportService) 가 system prompt 의 tool 카탈로그 보고 자율 호출.

```java
@Tool(name = "retrieve_similar_cases",
      description = "Track 3 심층 심사용 — 유사 과거 신청 사례 검색. 페르소나·상품·세그먼트 메타 자동 매칭.")
public List<Chunk> retrieveSimilarCases(
    @ToolParam("페르소나 요약 + 신청 정보") String applicantMeta,
    @ToolParam(value = "결과 개수", defaultValue = "5") int k
) { ... }

@Tool(name = "retrieve_policy",
      description = "거절/승인 근거의 법·정책 인용 검색. matrix_coord 자동 주입.")
public List<Chunk> retrievePolicy(
    @ToolParam("검색 질의 (한국어 자연어)") String question,
    @ToolParam(value = "결과 개수", defaultValue = "3") int k
) { ... }

@Tool(name = "retrieve_faq",
      description = "Track 3 심사원 Q&A — 정책 해석·시스템 사용 질문.")
public List<Chunk> retrieveFaq(
    @ToolParam("질문") String question,
    @ToolParam(value = "결과 개수", defaultValue = "3") int k
) { ... }
```

### 8.1 호출 cap (트랙별)

| Track | retrieve_similar_cases | retrieve_policy | retrieve_faq |
|-------|------------------------|-----------------|--------------|
| TRACK_1 | 0 (skip) | 1 | 0 |
| TRACK_2 | 0 | **최소 1, 권고 2** (법·정책 강제) | 0 |
| TRACK_3 | 1~3 (자율) | 1~3 (자율) | 0~2 (자율) |

cap 초과 시 `RagCallLimitException` → 마지막 호출 거절. 토큰 비용 cap (llm-pipeline §9) 과
별도 메트릭으로 추적.

---

## 9. Grounding 강제

### 9.1 ReviewReport.citations 검증

LLM 출력 후 `GroundingValidator` (llm-pipeline §5.4):

```java
boolean validate(ReviewReport report) {
    for (Citation c : report.citations()) {
        if (!ragSearchService.existsById(c.id())) return false; // RAG chunk 존재 검증
    }
    // 본문에 정책 키워드 (예: "DSR", "LTV", "신용정보법") 있는데 citations 빔 → fail
    if (hasPolicyClaimWithoutCitation(report)) return false;
    // Track 2 는 법·정책 인용 최소 2 강제
    if (report.track() == TRACK_2 && countPolicyCitations(report) < 2) return false;
    return true;
}
```

실패 시 → `TemplateFallback` (결정론적 리포트 생성, 정책 매트릭스 cell 자동 인용).

### 9.2 인라인 fallback (llm-pipeline §14.1 와 통합)

RAG OFF 또는 검색 결과 empty 시 `application.yml` 인라인 policy text 로 fallback. wire 호환:
`Citation.id` 가 `inline:MORT_DSR_LIMIT_V1` 또는 `rag:42` 형식으로 prefix 구분.

---

## 10. 인덱스 구축 파이프라인

### 10.1 정책 코퍼스 (P1)

```
[정책 문서 원본]
  - agents/auto-loan-review/src/main/resources/policy/*.md  (자행 정책서, 수동 작성)
  - agents/auto-loan-review/src/main/resources/policy/*.yml (정책 매트릭스 export)
  - data/external/regulation/fss_guidelines_2024.pdf          (금감원 가이드)
        ↓
[ai-data-builder/scripts/build_policy_corpus.py]
  - 정규식 파서 (조항·항 단위 split)
  - 정책 매트릭스 cell → chunk 자동 생성
  - PDF → text (pdfplumber)
  - 청크당 metadata 생성 (source, article_no, matrix_coord, tags)
        ↓
[embedding 산출]
  - VertexAiEmbeddingClient.embed_batch(chunks)
        ↓
[ai_db.ai_embedding UPSERT]
  - corpus='policy_regulation', source_id=<doc_id+article>, embedding_model='bge-m3-v1'
  - is_active=true, 구버전 is_active=false (soft delete)
```

### 10.2 유사 케이스 코퍼스 (P2)

```
[loan_db.LOAN_REVIEW (review_round >= 0, reviewed_at >= yesterday)]
        ↓
[batch job: services/loan-service @Scheduled(cron 매일 02:00)]
  - 전일 review 증분 조회
  - PII 마스킹 (이름·주민·계좌·전화)
  - chunk_text 템플릿 생성 (§4.1)
  - LLM 로 사유 요약 (선택, chunk_summary 채움)
        ↓
[ai-data-builder REST: POST /api/ai/embeddings/batch]
  - 또는 loan-service 가 직접 ai_db 적재
        ↓
[ai_db.ai_embedding UPSERT (corpus='similar_cases')]
```

### 10.3 FAQ 코퍼스 (P3)

- 사내 wiki API 또는 markdown 파일 (`data/internal/faq/*.md`)
- 월 1회 수동 또는 스케줄 batch
- 구조: 파일당 Q/A 다수 → 정규식 split (`^### Q\.`)

### 10.4 재인덱싱 전략

- 임베딩 모델 버전 변경 시: 신규 `embedding_model` 컬럼 값으로 신규 row 적재 → 모두 적재 후
  구버전 `is_active=false` (zero-downtime)
- 청킹 룰 변경 시: 동일 패턴 (구버전 soft delete + 신규 적재)

---

## 11. 업데이트 주기 & 운영

| 코퍼스 | 자동 갱신 | 수동 갱신 | 갱신 트리거 |
|--------|----------|----------|------------|
| kb_policy_regulation | X (분기 수동) | 정책위 분기 리뷰 후 운영자 batch 실행 | 정책 매트릭스 YAML 변경 PR merge 시 CI 가 build_policy_corpus 자동 실행 |
| kb_similar_cases | ✓ (일 1회 02:00 KST) | — | @Scheduled cron |
| kb_internal_faq | X (월 수동) | wiki 변경 시 운영자 batch | — |

**인덱스 신선도 메트릭**:
- `rag.index.last_updated{corpus=}` (Micrometer gauge)
- `rag.index.chunk_count{corpus=}`
- `rag.search.latency{corpus=}`
- `rag.search.miss_rate{corpus=}` (similarity threshold 미만 비율)

---

## 12. LLM 파이프라인 (Phase 1.5/1.6) 와의 인터페이스

### 12.1 인터페이스 swap 지점

llm-pipeline §14 의 인라인 policy text → RAG chunk:

| 변경 전 (인라인) | 변경 후 (RAG) |
|----------------|---------------|
| `Citation.id = "inline:MORT_DSR_LIMIT_V1"` | `Citation.id = "rag:42"` |
| `GroundingValidator` 가 `application.yml` 인라인 맵 lookup | `RagSearchService.existsById(42)` |
| prompt YAML `tools:` 섹션 없음 | prompt YAML `tools: [retrieve_policy, retrieve_similar_cases, retrieve_faq]` |
| LLM 호출 1회 (입력에 policy text 직접 주입) | LLM 호출 N회 (tool calling 라운드) |

### 12.2 ReviewReport schema 비변경

`Citation.id` prefix (`inline:` / `rag:`) 만 의미 swap. record 구조 자체는 그대로 (wire 호환).
loan-service 의 LOAN_REVIEW.report_json 도 비변경.

### 12.3 prompt 업데이트

기존 `review_report_track1/2/3_v1.yml` → `_v2.yml` 신규:
- `tools:` 섹션 추가
- system prompt 에 "정책 인용은 retrieve_policy 호출 결과에서만, 인용 없는 주장 금지" 명시
- 인라인 policy text 제거

`PromptRegistry` 가 version 별 카탈로그 관리 (llm-pipeline §6) — RAG OFF 환경은 v1, ON 환경은 v2.

---

## 13. 진행 단계 — Phase D0 ~ D4

> 본 절은 실행 계획의 요약. 상세 단계·산출물·게이트는 **[`docs/plan/phase-d-rag.md`](phase-d-rag.md)** 참조.
> 본 문서(`rag-corpora.md`)는 design source-of-truth, `phase-d-rag.md` 는 실행 source-of-truth.

| Phase | 내용 | 기간 |
|-------|------|------|
| **D0** — 아키텍처 결정 + 사전 정비 | 코드 오케스트레이션 vs 자율 tool calling 결정, Spring AI 도입 컨센서스, `PolicyIndex` interface 추출 합의, 본 문서 갱신 | 0.5 주 |
| **D1** — 인프라 + 정책 코퍼스 P1 | Spring AI 1.x BOM + Vertex embedding starter, V4 마이그레이션 (pgvector + pg_trgm), `EmbeddingClient` + `SpringAiEmbeddingClient` + `StubEmbeddingClient`, `RagSearchService` 하이브리드 검색, 정책 코퍼스 13 chunks seed (`ai.policy.inline` 8 + 매트릭스 5 셀) | 1.5 주 |
| **D2** — Tool + Grounding 통합 | `PolicyIndex` interface 분리 (Inline/Rag 두 구현체), `application.yml` 의 `ai.rag.*` 섹션, `RagRetrievalService` 코드 오케스트레이션 + `AgentLoopGuard` 통합, `ReviewReportInput.ragContext` + 프롬프트 v2 4 종, `GroundingValidator` Citation prefix 분기 | 1 주 |
| **D3** — 유사 케이스 코퍼스 P2 | `auto-loan-review` `/api/internal/embeddings/batch` endpoint, `loan-service` `SimilarCaseExporter` + 일배치 `@Scheduled`, PII 이중 마스킹 smoke, 합성 1 만 건 cold-start seed | 0.5 주 |
| **D4** — 검증 + 운영 cutover | `AgentMetricsRecorder` RAG 메트릭 4 종 추가, V5 마이그레이션 (`shadow_run_result.rag_enabled`), Shadow Mode 가 V1(인라인)/V2(RAG) 동시 산출, E2E smoke 5 케이스, canary 단계적 전환 (shadow → 5% → 25% → 100%) | 1 주 |

**예상 기간**: **4 ~ 4.5 주** (D3·D4 일부 병렬 가능).

### 13.1 본 문서 §8 ~ §12 의 변경점

- **§8 Spring AI Tool 명세** — D Phase 에서는 LLM 자율 호출 없이 **코드 오케스트레이션**. `@Tool` 어노테이션 기반 자율 호출은 후속 옵션 phase **D-F3 Agentic RAG** 로 분리.
- **§10 인덱스 구축 파이프라인** — `scripts/build_policy_corpus.py` Python 빌더 대신 **Java seed loader** (`com.bank.ai.rag.seed.*`) 로 출발. 외부 PDF 파서 (금감원 가이드) 는 **D-F4** 로 분리.
- **§12 LLM 파이프라인 인터페이스** — `LlmClient` 인터페이스 유지, 내부 구현체만 `SpringAiLlmClient` 로 점진 교체. 단일 PR 로 전면 swap 하지 않음.

### 13.2 옵션 phase (D 종료 후)

| Phase | 트리거 | 본 문서 참조 |
|-------|--------|--------------|
| D-F1 — FAQ 코퍼스 P3 | 심사원 요청 누적 | §1, §4.3 |
| D-F2 — bge-reranker-v2-m3 | Track 3 검색 정확도 부족 | §6 |
| D-F3 — Agentic RAG | 운영 6 개월 후 | §8 (자율 호출 패턴) |
| D-F4 — 외부 정책 PDF 적재 | 정책위 요청 | §10.1 |
| D-F5 — bge-m3 자체 ONNX | Vertex 비용·latency 부담 | §3.2 옵션 B |

---

## 14. 영향받는 코드·데이터·문서

### 14.1 신규

- `agents/auto-loan-review/src/main/java/com/bank/ai/rag/**` (embedding, search, tool, support)
- `agents/auto-loan-review/src/main/resources/policy/*.md`, `*.yml` (정책 원문)
- `agents/auto-loan-review/src/main/resources/prompts/review_report_track{1,2,3}_v2.yml`
- `services/loan-service/src/main/java/com/bank/loan/rag/SimilarCaseExporter.java` + `@Scheduled` job (또는 별도 batch 모듈)
- `db/migration/ai_db/V20260701__ai_embedding.sql` + GIN/IVFFlat 인덱스
- `scripts/build_policy_corpus.py` (Python 빌더) 또는 Java equivalent
- `data/external/regulation/` (금감원 가이드 PDF 등 원본 보관)
- `inference-server/rerank/` (옵션, bge-reranker-v2-m3 ONNX)
- `docs/ai/RAG_RUNBOOK.md` (인덱스 갱신·재인덱싱·장애 대응)

### 14.2 갱신

- `agents/auto-loan-review/src/main/resources/application.yml` — `ai.rag.*` 섹션 (enabled, embedding.provider, search.alpha, similarityThreshold, callCaps per track)
- `com.bank.ai.llm.report.ReviewReportService` — tool calling 라운드 지원 (Spring AI agent loop)
- `com.bank.ai.llm.report.GroundingValidator` — RAG chunk lookup 추가, `Citation.id` prefix 분기
- `com.bank.ai.llm.prompt.PromptRegistry` — v1 (inline) / v2 (rag) 분기, `ai.rag.enabled` 에 따라 선택
- `services/loan-service` `LoanReview` — `report_json` 의 `citations` 필드 검증 룰 강화
- `docs/plan/banking-review-llm.md` — §1.7 진행 상태, §6 RAG 구축 완료 사항 갱신
- `docs/plan/llm-pipeline.md` — §14 swap 완료 표기

### 14.3 보존 / 미변경

- `com.bank.ai.privacy.PiiMaskingFilter` (PII 마스킹 재사용)
- `com.bank.ai.rule.*` RuleEngine 그대로 — RAG 는 LLM 입력 보강만, 결정 로직 비변경
- `inference-server` PD/decision 모델 그대로 (rerank 만 옵션 추가)
- 1.9.1~1.9.3 모니터링 인프라 그대로 (메트릭 종류만 RAG 추가)

---

## 15. 한계·리스크

- **한국어 BM25 정확도** — PostgreSQL FTS `simple` config + pg_trgm 은 형태소 인식 X. 검색
  miss rate 메트릭 (`rag.search.miss_rate`) > 20% 지속 시 pgroonga 마이그레이션 강제.
- **bge-m3 한국어 도메인 특화 부족** — 일반 다국어 학습이라 금융·법률 한국어 어휘 약함.
  검색 정확도 부족 시 (a) Vertex `text-embedding-005` 비교 평가, (b) bge-m3 fine-tuning
  (LOAN_REVIEW + 정책 쌍 InfoNCE) 검토.
- **유사 케이스 cold start** — 운영 초기 LOAN_REVIEW 누적 부족 → similar_cases 코퍼스 비어
  Track 3 검색 무의미. 합성 데이터 (HMDA/Home Credit 변환 결과) 로 1만 건 seed 적재 고려.
- **정책 변경 시 인덱스 stale** — 분기 1회 수동이라 hot fix 정책 (예: 금감원 긴급 가이드)
  반영 지연. CI 트리거 (정책 YAML PR merge 시 자동 reindex) 로 완화.
- **rerank latency** — Track 3 p99 < 8s SLA 에 cross-encoder 추가가 위협. CPU 50 후보 rerank
  ~300~500ms 예상 — GPU 또는 ONNX-Runtime quantization 검토.
- **PII 마스킹 누락 위험** — 케이스 코퍼스의 raw 텍스트가 LLM 으로 전달되므로, 마스킹 룰
  커버리지 부족 시 PII leak. 인덱싱 전 + 검색 결과 LLM 전달 전 이중 검증.
- **tool calling 비용** — LLM 이 retrieve_* 를 N회 호출하면 토큰 비용 N배. 트랙별 cap (§8.1)
  로 통제하되, 토큰 cap (llm-pipeline §9) 과 별도 메트릭 관리 필요.
- **벡터 인덱스 IVFFlat 정확도** — `lists=100` 으로 시작, recall@10 < 0.9 면 lists 증가 또는
  HNSW 로 마이그레이션. 운영 후 측정.
- **embedding 모델 swap 부담** — bge-m3 → 다른 모델 (text-embedding-005 등) 변경 시 전체
  재인덱싱 필요 (수 시간). soft delete + 신규 적재 패턴 (§10.4) 으로 무중단 보장.

---

## 16. 부록 — 참고 자료·라이선스

| 자산 | 출처 | 라이선스 | 비고 |
|------|------|---------|------|
| bge-m3 모델 | BAAI HuggingFace | MIT | 상업 사용 자유 |
| bge-reranker-v2-m3 | BAAI HuggingFace | Apache 2.0 | 상업 사용 자유 |
| pgvector | github.com/pgvector/pgvector | PostgreSQL License | PostgreSQL 확장 |
| pg_trgm | PostgreSQL contrib | PostgreSQL License | 기본 포함 |
| pgroonga | github.com/pgroonga/pgroonga | PostgreSQL License | 한국어 BM25 (운영 검토) |
| 금감원 가이드 | fss.or.kr 공개 | 공공저작물 (자유이용) | PDF 다운로드 |
| 신용정보법 | law.go.kr | 공공저작물 | API 또는 PDF |
| Vertex AI text-embedding-005 | Google Vertex | Vertex AI ToS | 호스팅 옵션 A |
| Spring AI Reference (RAG) | spring.io/projects/spring-ai | Apache 2.0 | 구현 가이드 |

---

## 17. 의존성 — 본 phase 시작 전 점검

- [ ] ai_db PostgreSQL 16+ (pgvector 0.7+ 호환), pgvector·pg_trgm 확장 권한
- [ ] Vertex AI Embedding API 쿼터 (text-embedding-005 분당 RPM)
- [ ] 정책 원문 작성 책임자 (정책위 또는 본 팀) — 자행 여신정책서 마크다운화
- [ ] LOAN_REVIEW 누적 데이터 또는 합성 seed 데이터 1만 건+
- [ ] PII 마스킹 룰 한국어 패턴 점검 (이름·주민·전화·계좌 정규식 커버리지)
- [ ] Phase 1.5/1.6 (llm-pipeline) 의 `Citation`, `GroundingValidator`, `PromptRegistry` 인터페이스
      확정 — RAG 가 swap 들어갈 자리
- [ ] inference-server 가 rerank endpoint 추가 가능한지 (옵션)
- [ ] 운영 ai_db 디스크 여유 — 100만 chunks × (1024 dim × 4 bytes + 평균 chunk_text 2KB) ≈ 6GB+

---

## 부록 A — Elasticsearch 인덱스 매핑 (Phase E 기준, 2026-05-29 추가)

> Phase E ([`phase-e-elasticsearch.md`](phase-e-elasticsearch.md)) 에서 §2 pgvector 스키마를 대체.
> 코퍼스 구조·메타데이터 필드는 §2.2 와 동일하게 승계.

### A.1 공통 설정 (3 인덱스 공통)

| 항목 | 값 |
|------|-----|
| ES 버전 | 8.15+ |
| 한국어 분석기 | `korean_nori` (`nori_tokenizer` + `nori_part_of_speech` + `lowercase`) |
| 임베딩 | `dense_vector` dims=768, similarity=cosine, `int8_hnsw` (m=16, ef_construction=100) |
| 인덱스 명명 | `kb_{corpus}_v{n}` (versioned) + alias `kb_{corpus}` (zero-downtime swap) |

### A.2 `kb_policy_v1` 매핑 (shards=1, refresh_interval=1s)

```json
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 1,
    "refresh_interval": "1s",
    "analysis": {
      "analyzer": {
        "korean_nori": {
          "type": "custom",
          "tokenizer": "nori_tokenizer",
          "filter": ["nori_part_of_speech", "lowercase"]
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "corpus":        { "type": "keyword" },
      "source_id":     { "type": "keyword" },
      "chunk_seq":     { "type": "integer" },
      "chunk_text":    { "type": "text", "analyzer": "korean_nori" },
      "chunk_summary": { "type": "text", "analyzer": "korean_nori" },
      "metadata": {
        "properties": {
          "product_code":   { "type": "keyword" },
          "segment":        { "type": "keyword" },
          "effective_date": { "type": "date" },
          "policy_id":      { "type": "keyword" },
          "matrix_coord":   { "type": "keyword" }
        }
      },
      "embedding": {
        "type": "dense_vector",
        "dims": 768,
        "index": true,
        "similarity": "cosine",
        "index_options": { "type": "int8_hnsw", "m": 16, "ef_construction": 100 }
      },
      "embedding_model": { "type": "keyword" },
      "created_at":      { "type": "date" }
    }
  }
}
```

### A.3 `kb_similar_cases_v1` (shards=3, refresh_interval=30s)

§A.2 와 동일 구조. `metadata` 에 추가 필드:
`decision_label`, `loan_type`, `pd_band`, `dsr_band`, `region_code` (모두 `keyword`).

### A.4 `kb_internal_faq_v1` (shards=1)

§A.2 와 동일.

### A.5 pgvector → ES 필드 대응표

| pgvector 컬럼 | ES 필드 | 비고 |
|---------------|---------|------|
| `embedding vector(1024)` | `embedding dense_vector dims=768` | 모델 변경 (text-embedding-005 기본 출력 768d) |
| `fts_tokens tsvector` | `chunk_text` (nori 분석기) | ES 가 자동 역인덱스 생성 |
| `is_active boolean` | 삭제 API (`DELETE /{index}/_doc/{id}`) | 소프트 삭제 → ES 하드 삭제로 전환 |
| `effective_date / expiry_date` | `metadata.effective_date` | `date` 타입 유지 |
