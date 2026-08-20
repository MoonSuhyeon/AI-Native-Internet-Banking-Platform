# 공통 규정 RAG 통합 + Elasticsearch 하이브리드 검색 계획 (doc-agent)

<!-- plan-status -->
> **상태: 🔨 진행 중** — `agents/auto-loan-review/.../rag/es/` 존재
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).


> 작성일: 2026-05-29
> 상태: design draft
> 배경: **doc-agent 에 Elasticsearch 를 도입**(목적 = 규정 검색 품질 향상)하는 시점에,
>       3개 에이전트가 각각 따로 임베딩하던 **공통 규정 코퍼스**를 어떻게 다룰지 결정.
> 선행/연관 문서: `docs/plan/phase-d-rag.md`(pgvector 안 — supersede), `docs/plan/rag-corpora.md`(코퍼스 정의),
>       `docs/plan/doc-agent.md`, `docs/plan/llm-pipeline.md`

본 문서는 두 가지를 묶는다.
1. **전략(§1~§3)** — 공통 규정 코퍼스를 단일 소스로 통합할지/분리할지의 의사결정.
2. **실행(§4~§12, Phase E)** — **doc-agent 에 Elasticsearch 8.x 네이티브 하이브리드 검색**
   (BM25 + dense_vector kNN + RRF)을 도입하는 구체 단계. 기존 Phase D(pgvector + FTS 가중합) 대체.

---

## 1. 목표

- 모든 에이전트가 인용하는 **공통 규정·정책 코퍼스**를 doc-agent 의 단일 인덱스(single source of truth)로 통합한다.
- 에이전트 **고유 데이터**(유사 케이스, 상품·약관)는 분리 유지한다.
- 통합 기준은 "어느 에이전트가 쓰냐"가 아니라 **"데이터의 소유권·생명주기가 누구냐"**로 잡는다.
- 검색 백엔드는 Elasticsearch 로 통일하여 한국어 형태소(nori) + 하이브리드(RRF) 검색 품질을 확보한다.

---

## 2. 현재 상태 (As-Is)

> 범위: **여신/심사 도메인**. `consultation-service` 는 **수신계 소관 — 본 계획 범위 외**.

**공통 규정을 각자 따로 임베딩해 쓰던 "3개 에이전트":**

| 에이전트 | 벡터 스토어 | 규정 임베딩 |
| --- | --- | --- |
| **doc-agent** | (Elasticsearch 도입 대상) | O |
| **auto-loan-review** (`com.bank.ai.rag`) | PostgreSQL pgvector | O |
| **advisory-service** (`com.bank.loan.advisory.rag`, `advisory_document_chunk`) | PostgreSQL pgvector | O |

**별도 위치:**
- **`ai-service`** — 위 3개의 RAG 를 **합쳐보려고 만든 통합 시도 서비스**. 즉 "규정 RAG 를 한 곳으로 모으자"는 방향은 이미 한 번 착수된 상태 (consolidation 후보).
- `consultation-service` — 수신계 소관(상품·약관 추천), 본 계획 범위 외.

### 2.1 핵심 진단
- **3개 에이전트(doc-agent / auto-loan-review / advisory)가 같은 규정을 각자 다른 스토어에 중복 임베딩** → 갱신 불일치·비용 중복·인용 출처 분산.
- **ai-service 의 존재 자체가 "통합하자"는 합의의 증거.** 따라서 남은 결정은 *통합 여부*가 아니라 **"통합을 어디로 모을지"**.
- ES 가 **doc-agent 에 확정 도입**되므로 통합 위치 후보:
  - **(가) doc-agent ES 를 규정 단일 인덱스 owner 로** — ai-service 의 통합 역할을 doc-agent ES 가 흡수.
  - (나) ai-service(통합 시도 서비스)를 유지하되 그 백엔드를 ES 로 — doc-agent 와의 역할 중복 발생.
  - → **(가) 권장** (ES 가 이미 doc-agent 에 있고, owner 가 둘이면 다시 분산). **ai-service 거취(흡수/폐기)는 결정 필요 — §3.5.**

---

## 3. 결정 (To-Be) — 전략

> **공통 규정은 나누지 말고 doc-agent 의 단일 ES 인덱스로 합친다. 에이전트 고유 데이터는 분리 유지.**

```
┌─────────────────────────────────────────────┐
│  doc-agent (Elasticsearch) = 공통 규정 인덱스   │  ← single source of truth
│  kb_policy (LAW / SUPERVISION_GUIDE / POLICY)   │     (소유: doc-agent, ai-service 통합역할 흡수)
│  + doc_type · effective_date · matrix_coord 필터 │
└─────────────────────────────────────────────┘
        ▲ 검색 API(텍스트 in)   ▲ 검색 API(텍스트 in)
        │                       │
  auto-loan-review        advisory-service       (consultation = 수신계, 범위 외)
        │                       │
        └── 고유 데이터는 각자 분리 보관 ──────┘
            (유사케이스 등)
※ ai-service: 통합 시도 서비스 → doc-agent ES 로 흡수 또는 폐기 (§3.5 결정)
```

### 3.1 통합 대상 (공통 규정)
- LAW(법령), SUPERVISION_GUIDE(감독규정), POLICY(정책), INTERNAL_RULE, 신용정보법 등
- 변경 주체 단일, 모든 에이전트가 동일 최신본을 봐야 함
- → **doc-agent 의 Elasticsearch `kb_policy` 인덱스로 이전, doc-agent 가 owner. 나머지는 검색 API 로 조회.**

### 3.2 분리 유지 대상 (에이전트 고유)
- advisory `advisory_case_index`(유사 케이스) → `kb_similar_cases` 인덱스로 흡수 가능하나 owner 는 심사 도메인
- consultation 상품·약관 → 상품 DB 종속, 추천 전용. **Phase E 범위 외, 그대로 유지**

### 3.3 ⚠️ 통합 vs Phase E 정합성 이슈 (반드시 결정)

| # | 이슈 | 내용 | 해소안 |
| --- | --- | --- | --- |
| **C1** | 임베딩 모델 불일치 | 현행 3서비스 OpenAI 3-small **1536d** ↔ Phase E Vertex 005 **768d**. 벡터 공간이 다르면 advisory(pgvector 1536)가 doc-agent ES(768) 인덱스를 직접 kNN 조회 불가. | **(권장) 조회는 "텍스트 in" 검색 API** — 호출측은 텍스트만 보내고 임베딩은 doc-agent 수행 → 호출측 임베딩 모델 무관, C1 소멸. / 대안: 전 서비스 768d 표준화 + 전체 재인덱싱. |
| **C2** | Phase E 범위가 단일 서비스 | Phase E 가 RAG 검색·인덱싱을 doc-agent 에 집중. advisory-service 를 공유 규정 인덱스에 연결하는 작업 필요. | §5 E5 단계 — advisory `PolicyCitationRetriever` → doc-agent `kb_policy` 검색 API 위임, 자체 규정 적재 중단. |
| **C3** | 인덱싱 소유 | LOAN_REVIEW(유사케이스 원천) 소유자 = loan-service. 규정 인덱스 owner = doc-agent. | 케이스 적재는 outbox→Kafka→doc-agent, 규정은 doc-agent 관리 API 직접. |

> **결론**: C1 은 **검색 API(텍스트 in)** 채택으로 해소, C2 는 **E5 단계 신설**로 메운다.

### 3.4 통합 근거

| 항목 | 분리 유지 시 문제 | 통합 시 이득 |
| --- | --- | --- |
| 갱신 일관성 | 규정 개정 시 재인덱싱 타이밍 어긋남 → 에이전트마다 다른 규정 인용(법적 리스크) | 단일 인덱스 → 항상 동일 최신본 |
| 비용 | 동일 문서 N중 임베딩 | 1회 임베딩 |
| 감사/grounding | 인용 ID 가 서비스별로 흩어짐 | `Citation.id` 단일 출처 → 검증·추적 단순 |
| 검색 품질 | pgvector `simple`/`pg_trgm` 형태소 인식 X | ES nori + BM25 + RRF 하이브리드 |

### 3.5 ⚠️ ai-service 거취 (결정 필요)

ai-service 가 이미 "규정 RAG 통합"을 목표로 만들어졌으므로, doc-agent ES 통합과 **역할이 겹친다.**
방치하면 통합 지점이 둘(ai-service + doc-agent ES)이 되어 또 분산된다. 셋 중 하나로 정리:

| 옵션 | 내용 | 비고 |
| --- | --- | --- |
| **(가) doc-agent 흡수** (권장) | ES·규정 인덱스·검색 API 를 doc-agent 가 소유. ai-service 의 RAG 자산을 doc-agent 로 이관 후 **ai-service 폐기/축소** | owner 단일화. ES 가 이미 doc-agent 에 있으니 자연스러움 |
| (나) ai-service = ES 게이트웨이 | doc-agent 는 ES 인프라만, ai-service 가 규정 검색 API 의 단일 진입점 | 통합 시도 자산 재활용. 단 doc-agent/ai-service 책임 경계 재정의 필요 |
| (다) ai-service 폐기, 각자 doc-agent 직접 | ai-service 제거, auto-loan-review·advisory 가 doc-agent API 직접 호출 | 가장 단순. ai-service 투자 매몰 |

→ **(가) 권장.** 결정에 따라 §5 E5·§10 영향 범위의 `ai-service` 항목을 확정한다.

---

# Phase E — Elasticsearch 기반 하이브리드 검색 실행 계획 (doc-agent)

> 기존 Phase D(pgvector + FTS 가중합) 대신 **Elasticsearch 8.x 네이티브 하이브리드 검색(BM25 + dense_vector kNN + RRF)**.
> 코퍼스 정의(P1 정책 / P2 유사 케이스 / P3 FAQ), `PolicyIndex` interface, LLM 호출 패턴(코드 오케스트레이션)은 Phase D 승계.

## 4. 결정 사항 요약

| 항목 | 결정 | 근거 |
|------|------|------|
| 검색 엔진 | **Elasticsearch 8.15+** | BM25·kNN(HNSW)·RRF 네이티브 단일 쿼리(`retriever`) |
| 클러스터 | dev: 단일 노드 docker / stg·prod: 3노드(replica=1) | 코퍼스 ≤ 5GB |
| 한국어 분석기 | **nori** (`nori_tokenizer` + `nori_part_of_speech`) | 정책·약관·심사 노트 한국어 |
| 임베딩 모델 | Vertex `text-embedding-005`(**768d**) | ES `dense_vector` dims=768, int8_hnsw — 단 §3.3 C1 결정 필요 |
| 인덱스 분리 | 코퍼스별 1 인덱스(`kb_policy_v1`, `kb_similar_cases_v1`, `kb_internal_faq_v1`) + alias | 매핑·보존·재인덱싱 주기 상이 |
| 인덱싱 | 정책 P1: 관리 API / 케이스 P2: outbox→Kafka→Connect / FAQ P3: 관리 API | LOAN_REVIEW 트랜잭션 일관성 |
| 하이브리드 | ES `retriever` `rrf`(k=60, rank_window_size=50) | 알파 튜닝 불필요 |
| Java 클라이언트 | `co.elastic.clients:elasticsearch-java` 8.15 | typed API + RRF/retriever DSL |
| Spring AI | 임베딩 호출에만 사용, 검색은 ES 클라이언트 직접 | VectorStore 는 hybrid/RRF 제어 부족 |
| `PolicyIndex` | `InlinePolicyIndex` / `EsPolicyIndex`, `@ConditionalOnProperty ai.rag.backend=es` | GroundingValidator·ReviewReportService 변경 0줄 |
| cutover | 기존 `ShadowModeService` — V1(인라인)/V2(ES RAG) | Phase D 승계 |
| 보안 | API key(서비스별), 인덱스 단위 권한, TLS | PII 마스킹 후에도 컴플라이언스 |
| 백업 | snapshot → MinIO/S3 (일1회 04시) | RPO 24h |
| 테스트 | testcontainers `elasticsearch:8.15.0` + `StubEmbeddingClient`(SHA-256→768d) | CI ≤ 90s |

---

## 5. 단계 표 (E0 ~ E5)

### E0 — 결정 + 사전 정비 (0.5주, 코드 0줄)
| # | 작업 | 산출 / 게이트 |
|---|------|--------------|
| E0-1 | 결정 합의 + Phase D supersede 표기 | docs PR |
| E0-2 | `next-phase-roadmap.md` Phase D → Phase E(E0~E5) | docs |
| E0-3 | `rag-corpora.md` §5 → ES 매핑 부록(기존 historical) | docs |
| E0-4 | infra 합의 — ES 클러스터 비용 + 백업 | 견적서 |
| E0-5 | §3.3 C1 결정 — 임베딩 모델/조회 방식 확정 | 결정 기록 |

### E1 — ES 인프라 + 인덱스 + 임베딩 (1.5주)
| # | 작업 | 산출 / 게이트 |
|---|------|--------------|
| E1-1 | `docker-compose.yml` — `elasticsearch:8.15.0` + `kibana:8.15.0`(profile `rag`), 2g, security 활성, 패스워드→`.env` | 기동 PASS |
| E1-2 | `build.gradle`(doc-agent) — ES 클라이언트 + Spring AI BOM + Vertex embedding starter | 빌드 PASS |
| E1-3 | 인덱스 템플릿 3종(§7) + alias 생성 스크립트 | 매핑 PASS |
| E1-4 | `EmbeddingClient` + `SpringAiEmbeddingClient` + `StubEmbeddingClient`(768d) | 동일텍스트→동일벡터 |
| E1-5 | `EsClientConfig` — `ElasticsearchClient` 빈(API key + timeout), `@ConditionalOnProperty ai.rag.backend=es` | 헬스체크 |
| E1-6 | `EsIndexAdminService` — 생성/존재확인/alias swap(zero-downtime) | testcontainers |

### E2 — 하이브리드 검색 통합 (1주)
| # | 작업 | 산출 / 게이트 |
|---|------|--------------|
| E2-1 | `EsHybridSearchService` — `retriever` DSL(BM25 multi_match + kNN → rrf), `metaFilter`→`bool.filter` 양쪽 주입 | BM25/kNN/RRF 비교 테스트 |
| E2-2 | `PolicyIndex` 추출 — `InlinePolicyIndex`/`EsPolicyIndex`, `@ConditionalOnProperty ai.rag.backend` | `GroundingValidatorTest` 7TC 회귀 |
| E2-3 | `application.yml` — `ai.rag.*`(§9) | 바인딩 테스트 |
| E2-4 | `RagRetrievalService` — ES 백엔드 swap, `AgentLoopGuard.acquireTool()` 유지 | LoopGuard fallback |
| E2-5 | `ReviewReportInput.ragContext` + `{{rag_chunks}}` | Stub LLM 결정론 + grounding |
| E2-6 | `GroundingValidator` `Citation.id` prefix(`inline:`/`rag:`) | 5TC |
| E2-7 | 프롬프트 v2 4종 | `PromptRegistryTest` 4TC |

### E3 — 코퍼스 적재 파이프라인 (1.5주)
| # | 작업 | 산출 / 게이트 |
|---|------|--------------|
| E3-1 | P1 정책 — `PolicySeedLoader`: inline 8 + 매트릭스 5셀 = 13 chunks, `matrix_coord` 자동 | `retrieve_policy("주담대 DSR 한도")`→top-1 |
| E3-2 | P3 FAQ — `/api/internal/rag/faq` PUT + wiki dump CSV 1회 적재 | sanity 5쿼리 |
| E3-3 | P2 케이스 outbox — `loan_review_outbox` + V6 Flyway | 단위 테스트 |
| E3-4 | `SimilarCaseOutboxPublisher` — COMPLETED 시 `PiiMaskingFilter` 후 outbox INSERT(동일 트랜잭션), 5초 polling → Kafka `loan-review.case-indexed.v1` | 통합 테스트(시간격리 2030/2040) |
| E3-5 | Kafka Connect ES sink — topic→`kb_similar_cases_v1`, DLQ | docker-compose `cp-kafka-connect`, smoke |
| E3-6 | `CaseEmbeddingEnricher` consumer — raw→임베딩→enriched→Connect 색인 | 1메시지→ES doc 확인 |
| E3-7 | PII smoke — 1000건 적재 후 ES scan 정규식 0건 | smoke PASS |
| E3-8 | 합성 1만건 seed(HMDA + Home Credit→outbox) | kNN sanity |

### E4 — 검증 + 운영 cutover (0.5주)
| # | 작업 | 산출 / 게이트 |
|---|------|--------------|
| E4-1 | RAG 메트릭 5종 — `rag_search_latency_seconds{corpus,phase}`, `rag_search_miss_rate`, `rag_chunk_count`, `rag_citation_count_per_report`, `rag_index_lag_seconds` | Grafana 5패널 |
| E4-2 | Shadow Mode — `shadow_run_result.rag_enabled` V7 | SQL PASS |
| E4-3 | E2E smoke 6케이스 — Track 1/2/3 + empty + `rag.enabled=false` + ES down fallback | 모두 PASS |
| E4-4 | canary — shadow 100%→5%→25%→100%, 단계당 48h. 게이트: 일치율≥95%, 누락률≤5%, p99 검색<500ms, p99 report<8s | 게이트 PASS |

### E5 — 공통 규정 통합 델타 (cross-service, 0.5주) — §3.3 C2 해소
| # | 작업 | 산출 / 게이트 |
|---|------|--------------|
| E5-1 | doc-agent **규정 검색 API 공개** — `POST /api/internal/rag/policy/search`(텍스트 in, topK, metaFilter) — 내부 임베딩+RRF → 호출측 임베딩 모델 무관(C1 해소) | 계약 테스트 |
| E5-2 | advisory `PolicyCitationRetriever` → doc-agent API 위임, 자체 정책문서 임베딩 적재 중단(deprecate) | advisory 회귀 PASS |
| E5-3 | advisory `advisory_document_chunk` 규정 데이터 정리(유사케이스는 유지/통합 검토) | 데이터 정합 |
| E5-4 | 인용 ID 통일 — `rag:kb_policy:<doc>:<seq>` | grounding 교차 검증 |
| E5-5 | consultation-service — 변경 없음 확인(규정 미사용) | n/a |

---

## 6. Phase D 와의 차이 요약

| 항목 | Phase D (pgvector) | Phase E (Elasticsearch) |
|------|--------------------|--------------------------|
| 하이브리드 | cosine α=0.7 + FTS 0.3 가중합(직접 SQL) | RRF(`retriever`) — 정규화 불필요 |
| FTS 한국어 | `pg_trgm`(n-gram) 약함 | `nori` 형태소 |
| HNSW | pgvector hnsw/IVFFlat | ES 네이티브 HNSW |
| 운영 비용 | DB 통합(저비용) | 별도 클러스터 + Kafka Connect(중비용) |
| 인덱싱 | `@Scheduled` 직접 | outbox + Kafka + Connect |
| Reranker | D-F2 별도 구현 | ES `text_similarity_reranker` 빌트인(향후) |

---

## 7. ES 인덱스 매핑 (E1-3)

### 7.1 `kb_policy_v1` (정책·규제 — 소량, refresh_interval=1s)
```json
{
  "settings": {
    "number_of_shards": 1, "number_of_replicas": 1, "refresh_interval": "1s",
    "analysis": { "analyzer": { "korean_nori": {
      "type": "custom", "tokenizer": "nori_tokenizer",
      "filter": ["nori_part_of_speech", "lowercase"] } } }
  },
  "mappings": { "properties": {
    "corpus":        { "type": "keyword" },
    "source_id":     { "type": "keyword" },
    "chunk_seq":     { "type": "integer" },
    "chunk_text":    { "type": "text", "analyzer": "korean_nori" },
    "chunk_summary": { "type": "text", "analyzer": "korean_nori" },
    "metadata": { "properties": {
      "product_code":   { "type": "keyword" },
      "segment":        { "type": "keyword" },
      "effective_date": { "type": "date" },
      "policy_id":      { "type": "keyword" },
      "matrix_coord":   { "type": "keyword" } } },
    "embedding": { "type": "dense_vector", "dims": 768, "index": true,
      "similarity": "cosine", "index_options": { "type": "hnsw", "m": 16, "ef_construction": 100 } },
    "embedding_model": { "type": "keyword" },
    "created_at":      { "type": "date" }
  } }
}
```

### 7.2 `kb_similar_cases_v1` (대량 — refresh_interval=30s)
- 동일 구조, `metadata` 에 `decision_label`, `loan_type`, `pd_band`, `dsr_band`, `region_code` 추가. shards=3.

### 7.3 `kb_internal_faq_v1` (소량) — `kb_policy_v1` 동일, shards=1.

---

## 8. 하이브리드 검색 쿼리 (E2-1)

```java
SearchRequest req = SearchRequest.of(s -> s
    .index("kb_policy")
    .retriever(r -> r.rrf(rrf -> rrf
        .retrievers(List.of(
            Retriever.of(ret -> ret.standard(std -> std
                .query(q -> q.bool(b -> b
                    .must(m -> m.multiMatch(mm -> mm
                        .query(queryText).fields("chunk_text^2", "chunk_summary")))
                    .filter(buildMetaFilter(metaFilter)))))),
            Retriever.of(ret -> ret.knn(knn -> knn
                .field("embedding").queryVector(toFloatList(queryVector))
                .k(20).numCandidates(100).filter(buildMetaFilter(metaFilter))))
        ))
        .rankWindowSize(50).rankConstant(60)))
    .size(topK));
```
RRF score = `Σ 1/(rank_constant + rank_i)` — BM25/cosine 스케일 정규화 불필요.

---

## 9. application.yml 추가 (E2-3)

```yaml
spring:
  ai:
    vertex:
      ai:
        embedding:
          options: { model: text-embedding-005 }
        project-id: ${VERTEX_PROJECT_ID:}
        location:   ${VERTEX_LOCATION:us-central1}
ai:
  rag:
    enabled: ${AI_RAG_ENABLED:false}
    backend: ${AI_RAG_BACKEND:inline}            # inline / es
    embedding:
      provider:   ${AI_RAG_EMB_PROVIDER:stub}    # stub / vertex
      dimensions: 768
    es:
      uris:            ${ES_URIS:https://localhost:9200}
      api-key:         ${ES_API_KEY:}
      connect-timeout: 2s
      read-timeout:    5s
      indexes: { policy: kb_policy, cases: kb_similar_cases, faq: kb_internal_faq }
    search:
      num-candidates: 100
      default-k:      5
      rrf: { rank-window-size: 50, rank-constant: 60 }
    call-caps-per-track: { TRACK_1: 1, TRACK_2: 2, TRACK_3: 5 }
```

---

## 10. 영향 범위

| 모듈 | 변경 |
|------|------|
| `doc-agent` | ES 클라이언트 + `EsHybridSearchService` + `EsPolicyIndex` 신규, 임베딩·outbox enricher consumer, 규정 검색 API 공개(E5-1) |
| `loan-service` | `loan_review_outbox` 테이블 + `SimilarCaseOutboxPublisher` 신규 |
| `advisory-service` | E5: 규정 검색을 doc-agent API 위임, 자체 규정 임베딩 적재 중단. 유사케이스 유지/통합 검토 |
| `auto-loan-review` | 규정 검색을 doc-agent API 위임, 자체 규정 임베딩 적재 중단 |
| `ai-service` | **§3.5 결정 따라** — (가) doc-agent 로 자산 이관 후 폐기/축소 / (나) ES 게이트웨이로 재배치 / (다) 폐기 |
| `consultation-service` | **수신계 소관 — 본 계획 범위 외, 변경 없음** |
| `docker-compose.yml` | `elasticsearch`, `kibana`, `cp-kafka-connect` 추가(profile `rag`) |
| `GroundingValidator` / `ReviewReportService` | 변경 0줄(interface 추출 효과) |
| 마이그레이션 | V6(outbox), V7(`shadow_run_result.rag_enabled`) |

---

## 11. 게이트 / 롤백
- `ai.rag.enabled=false` kill switch → `InlinePolicyIndex` fallback
- `ai.rag.backend=inline` 전환 → ES 장애 시 즉시 인라인 복귀, 재배포 불필요
- ES 헬스 yellow/red → `RagSearchException` → 빈 `List<Chunk>` 반환 → grounding 부족 케이스로 재 LLM 호출
- E5(advisory 위임) 실패 → advisory 자체 `advisory_document_chunk` 일시 복귀 가능하도록 deprecate 단계 데이터 보존

---

## 12. 후속 옵션 (Phase E 완료 후)
- **E-F1**: ES `text_similarity_reranker` 활성(cross-encoder)
- **E-F2**: ELSER(sparse) 추가 → 3 retriever RRF
- **E-F3**: Agentic RAG — Spring AI `ChatClient.tools(...)` LLM 자율 retrieve
- **E-F4**: 코퍼스 P4(외부 PDF — 금감원 가이드) `kb_external_regulation_v1`

---

## 13. 작업 체크리스트 (전체)

| 단계 | 상태 | 내용 |
| --- | --- | --- |
| §3.3 | 대기 | 정합성 이슈 C1/C2/C3 결정(특히 임베딩 모델·조회 방식) |
| E0 | 대기 | 결정 합의 + Phase D supersede + 임베딩 모델 확정 |
| E1 | 대기 | ES 인프라 + 인덱스 매핑 + 임베딩 클라이언트 |
| E2 | 대기 | 하이브리드 검색 + PolicyIndex 분기 + 프롬프트 v2 |
| E3 | 대기 | P1 정책 / P2 케이스 outbox→Kafka→Connect / P3 FAQ |
| E4 | 대기 | 메트릭 + Shadow Mode + E2E + canary |
| E5 | 대기 | 공통 규정 통합 델타 — advisory 위임, 인용 ID 통일 |

---

## 14. 참고
- 선행 설계: `docs/plan/rag-corpora.md`, `docs/plan/phase-d-rag.md`(supersede), `docs/plan/doc-agent.md`, `docs/plan/llm-pipeline.md`
- 본 계획 = 규정 통합 전략(§1~§3) + Phase E ES 실행 계획(§4~§12).
