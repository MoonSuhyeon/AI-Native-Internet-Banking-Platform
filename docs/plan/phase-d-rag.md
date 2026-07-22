# Phase D — RAG 도입 계획

> Last updated: 2026-05-29
> **Status: ⚠️ SUPERSEDED — [`phase-e-elasticsearch.md`](phase-e-elasticsearch.md) 로 대체됨 (2026-05-29)**
> 본 문서의 pgvector + PostgreSQL FTS 가중합 방안은 Phase E (Elasticsearch 하이브리드 검색) 로 교체.
> 코퍼스 정의 (`rag-corpora.md`), `PolicyIndex` interface, LLM 호출 패턴은 Phase E 에 승계.
> 선행 문서: `docs/plan/rag-corpora.md` (상세 설계), `docs/plan/llm-pipeline.md` (인터페이스 swap 지점), `docs/plan/next-phase-roadmap.md` (Phase D 위치)

본 문서는 `rag-corpora.md` 설계를 현재 코드 상태(2026-05-27 기준 audit/metrics/shadow/drift 완료)에 맞춰 단계별 실행 계획으로 옮긴 것. `rag-corpora.md`는 design source-of-truth, 본 문서는 실행 source-of-truth.

---

## 0. 결정 사항 요약

| 항목 | 결정 | 근거 |
|------|------|------|
| LLM 호출 패턴 | **MVP — 코드 오케스트레이션** (Java 코드가 retrieve_* 순차 호출 후 prompt 주입). LLM 자율 tool calling 은 D-F3 옵션 phase | 현 `PreReviewAgentService` 가 같은 패턴 — 일관성·재현성·audit 측면 우월 |
| LLM·임베딩 인프라 | **Spring AI 1.x 도입** — `spring-ai-vertex-ai-embedding-spring-boot-starter` + `spring-ai-vertex-ai-gemini-spring-boot-starter` | provider swap 을 starter property 로 처리, 향후 ChatClient tool calling 으로 확장 여지 확보 |
| `LlmClient` 인터페이스 | 유지. 내부 구현체를 점진적으로 ChatClient 위임으로 교체 (`SpringAiLlmClient` 신규) | 기존 `StubLlmClient` / `GeminiOpenAiCompatLlmClient` 테스트·smoke 호환 |
| `PolicyIndex` | **interface 추출** — `InlinePolicyIndex` (record 기존) + `RagPolicyIndex` (신규) 두 구현 | GroundingValidator·ReviewReportService 변경 0 줄 |
| 운영 cutover | **기존 Shadow Mode 재사용** — V1 (인라인) control / V2 (RAG) shadow | `ShadowModeService` + `ShadowComparisonEvaluator` 이미 존재 (커밋 `1c86929`, `34b0b33`) |
| 정책 코퍼스 seed | `application.yml` 의 `ai.policy.inline` 8 정책 + `rule-engine.pd-threshold-matrix` 5 셀 = **13 chunks 로 출발** | 외부 PDF (금감원 가이드 등) 적재는 D 종료 후 별도 작업 |
| 배치 위치 | `loan-service @Scheduled` + `auto-loan-review` 측 `/api/internal/embeddings/batch` REST 위임 | LOAN_REVIEW 소유자가 loan-service, ai_embedding 소유자가 auto-loan-review |
| Tool 호출 가드 | **기존 `AgentLoopGuard.acquireTool()` 카운트에 통합** | 가드 우회 차단, max-tool-calls=6 안에서 RAG 검색도 카운트 |
| PII 마스킹 | **이중 적용** — (1) 케이스 코퍼스 인덱싱 전 (2) RAG 검색 결과 prompt 주입 전 | 기존 `PiiMaskingFilter` 재사용 |
| 테스트 결정론 | `StubEmbeddingClient` (텍스트 SHA-256 → 1024 차원 고정 벡터) + testcontainers + pgvector | 임베딩 비결정성 회피, CI 빠른 실행 |
| 메트릭 | **`AgentMetricsRecorder` 에 통합** — 별도 `RagMetricsRecorder` 분리 X | 14 종 + RAG 4 종 = 18 종 단일 Recorder |

---

## 1. 단계 표 (D0 ~ D4)

### D0 — 아키텍처 결정 + 사전 정비 (0.5 주, 코드 0 줄)

| # | 작업 | 산출 / 게이트 |
|---|------|--------------|
| D0-1 | 본 문서 컨센서스 (위 결정 사항) 합의 | docs PR merge |
| D0-2 | `next-phase-roadmap.md` Phase D 항목을 D0~D4 5 단계로 갱신 | docs |
| D0-3 | `rag-corpora.md` §13 표를 본 D0~D4 로 교체 | docs |

### D1 — 인프라 + 정책 코퍼스 P1 (1.5 주)

| # | 작업 | 산출 / 게이트 |
|---|------|--------------|
| D1-1 | `build.gradle` — Spring AI 1.x BOM + `spring-ai-vertex-ai-embedding-spring-boot-starter` 의존 추가 | 빌드 PASS |
| D1-2 | V4 마이그레이션 — `CREATE EXTENSION vector, pg_trgm` + `ai_embedding` 테이블 + IVFFlat (`lists=100`) + GIN(metadata, fts_tokens) + UNIQUE(corpus, source_id, chunk_seq, embedding_model) | Flyway PASS, H2 stub V4 마이그레이션 별도 |
| D1-3 | `EmbeddingClient` interface (`embed(String)`, `embed(List<String>)`) + `SpringAiEmbeddingClient` (Spring AI `EmbeddingModel` 래핑) + `StubEmbeddingClient` (SHA-256 결정론) | 단위 테스트: 동일 텍스트 → 동일 벡터 |
| D1-4 | `RagSearchService` + `Chunk` record — 하이브리드 검색 SQL (벡터 cosine α=0.7 + FTS BM25 0.3), `metaFilter`, `similarityThreshold=0.5` | testcontainers + pgvector 통합 테스트 |
| D1-5 | 정책 코퍼스 P1 seed loader (`@PostConstruct` 또는 Flyway-Java) — `ai.policy.inline` 8 정책 + `rule-engine.pd-threshold-matrix` 5 셀 = 13 chunks 적재, metadata 의 `matrix_coord` 자동 부여 | sanity 검색: `retrieve_policy("주담대 DSR 한도")` → MORT_DSR_LIMIT_V1 top-1 |

### D2 — Tool + Grounding 통합 (1 주)

| # | 작업 | 산출 / 게이트 |
|---|------|--------------|
| D2-1 | `PolicyIndex` interface 추출 — 기존 record → `InlinePolicyIndex` + `RagPolicyIndex` 신규, `@ConditionalOnProperty ai.rag.enabled` 분기 | 기존 `GroundingValidatorTest` 7TC 회귀 PASS |
| D2-2 | `application.yml` — `ai.rag.*` 섹션 (enabled kill switch, embedding.provider, search.alpha, similarityThreshold, callCapsPerTrack {TRACK_1:1, TRACK_2:2, TRACK_3:5}) | 설정 바인딩 테스트 |
| D2-3 | `RagRetrievalService` — 코드 오케스트레이션. `PreReviewAgentService` 안에서 트랙별 검색 + `AgentLoopGuard.acquireTool()` 통합 | LoopGuard 발동 단위 테스트, cap 초과 → fallback |
| D2-4 | `ReviewReportInput` 에 `List<Chunk> ragContext` 필드 추가 + `ReviewReportService` 가 `{{rag_chunks}}` 변수에 chunk_summary 주입 | Stub LLM 결정론 응답 + grounding 검증 PASS |
| D2-5 | 프롬프트 v2 4 종 — `review_report_track1_v2.yml`, `_track2_v2.yml`, `_track3_v2.yml`, `rejection_reason_draft_v2.yml`. `_v1` 파일과 공존 (`PromptRegistry` 가 둘 다 로드) | `PromptRegistryTest` 신규 4 TC |
| D2-6 | `GroundingValidator` 변경 — `Citation.id` prefix 분기 (`inline:` 기존 / `rag:` → `RagPolicyIndex`), Track 2 인용 ≥ 2 강제 유지 | 신규 5 TC (inline-only / rag-only / mixed / Track2-부족 / chunk-미존재) |

### D3 — 유사 케이스 코퍼스 P2 (0.5 주)

| # | 작업 | 산출 / 게이트 |
|---|------|--------------|
| D3-1 | `auto-loan-review` 측 `/api/internal/embeddings/batch` endpoint — chunk 배치 적재 (corpus, source_id, chunk_text, metadata 입력) | 단위 테스트 + 권한: 내부 호출 한정 (header `X-Internal-Token`) |
| D3-2 | `loan-service` 측 `SimilarCaseExporter` + `@Scheduled(cron "0 0 2 * * *")` — 전일 LOAN_REVIEW 증분 → `PiiMaskingFilter` 적용 → chunk 템플릿 (페르소나·신청·지표·결정) → REST 위임 | 통합 테스트: 격리된 연도 사용 (memory `feedback_test_isolation_dates` 준수) |
| D3-3 | PII smoke — 1000 건 적재 후 정규식 스캔으로 이름·주민·전화·계좌 0 건 | smoke PASS |
| D3-4 | 합성 데이터 1 만 건 seed (cold start 대비) — HMDA + Home Credit 변환 결과를 LOAN_REVIEW 형식으로 1 회 적재 | seed 적재 완료 |

### D4 — 검증 + 운영 cutover (1 주)

| # | 작업 | 산출 / 게이트 |
|---|------|--------------|
| D4-1 | `AgentMetricsRecorder` 에 RAG 메트릭 4 종 추가 — `rag_search_latency_seconds{corpus}`, `rag_search_miss_rate{corpus}`, `rag_chunk_count{corpus}` (gauge), `rag_citation_count_per_report{track}` | Grafana 대시보드 패널 4 개 추가 |
| D4-2 | Shadow Mode 확장 — `ShadowModeService` 가 V1 (인라인) 과 V2 (RAG) 양쪽 결과 동시 산출, `ShadowComparisonEvaluator` 가 결정 일치율 + citation 차이 측정 | 신규 SQL: `shadow_run_result` 에 `rag_enabled BOOLEAN` 컬럼 추가 (V5 마이그레이션) |
| D4-3 | E2E smoke 5 케이스 — Track 1/2/3 각 1 + RAG 검색 empty 1 + `ai.rag.enabled=false` fallback 1 | 모두 PASS |
| D4-4 | 운영 단계 전환 — shadow 100% → canary 5% → 25% → 100%. 각 단계 48h 메트릭 모니터링 (Shadow 일치율 ≥ 95%, citation 누락률 ≤ 5%, p99 latency < 8s 유지) | 메트릭 게이트 PASS 시 다음 단계 |

---

## 2. Spring AI 도입 상세

### 2.1 의존성

```gradle
dependencyManagement {
    imports {
        mavenBom "org.springframework.ai:spring-ai-bom:1.0.x"   // 1.x 안정 버전 핀
    }
}
dependencies {
    implementation 'org.springframework.ai:spring-ai-vertex-ai-embedding-spring-boot-starter'
    // ChatClient 옵션 (D-F3 Agentic RAG 시 활성)
    // implementation 'org.springframework.ai:spring-ai-vertex-ai-gemini-spring-boot-starter'
}
```

### 2.2 application.yml 추가

```yaml
spring:
  ai:
    vertex:
      ai:
        embedding:
          options:
            model: text-embedding-005           # PoC: Vertex 호스팅
        project-id: ${VERTEX_PROJECT_ID:}
        location: ${VERTEX_LOCATION:us-central1}
ai:
  rag:
    enabled: ${AI_RAG_ENABLED:false}            # kill switch
    embedding:
      provider: ${AI_RAG_EMB_PROVIDER:stub}     # stub / vertex / onnx-bge-m3
      dimensions: 1024
    search:
      alpha: 0.7                                # 벡터 가중치 (FTS = 1 - alpha)
      similarity-threshold: 0.5
      default-k: 5
    call-caps-per-track:
      TRACK_1: 1
      TRACK_2: 2
      TRACK_3: 5
    rerank:
      enabled: false                            # D-F2 옵션
```

### 2.3 `SpringAiEmbeddingClient` (D1-3 산출)

```java
@Component
@ConditionalOnProperty(prefix = "ai.rag.embedding", name = "provider", havingValue = "vertex")
@RequiredArgsConstructor
public class SpringAiEmbeddingClient implements EmbeddingClient {

    private final org.springframework.ai.embedding.EmbeddingModel embeddingModel;

    @Override
    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return embeddingModel.embed(texts).stream()
                .map(EmbeddingResponse::getOutput)
                .toList();
    }
}
```

provider swap (`vertex` → `onnx-bge-m3` 등) 은 `application.yml` 한 키 변경 + 재배포.

### 2.4 ChatClient tool calling — D-F3 옵션

본 Phase D 는 LLM 자율 호출 없이 코드 오케스트레이션. Spring AI ChatClient 의 `.tools(retrievePolicyTool, retrieveSimilarCasesTool)` 기능은 D-F3 (Agentic RAG) 에서 도입. 본 phase 는 starter 만 의존성 추가 (Gemini ChatClient 는 주석 처리, 임베딩 starter 만 활성).

---

## 3. 영향 범위

### 3.1 신규

- `agents/auto-loan-review/src/main/java/com/bank/ai/rag/embedding/` — `EmbeddingClient`, `SpringAiEmbeddingClient`, `StubEmbeddingClient`
- `agents/auto-loan-review/src/main/java/com/bank/ai/rag/search/` — `RagSearchService`, `Chunk`, `RagSearchProperties`
- `agents/auto-loan-review/src/main/java/com/bank/ai/rag/retrieval/` — `RagRetrievalService` (코드 오케스트레이션 진입점)
- `agents/auto-loan-review/src/main/java/com/bank/ai/rag/policy/` — `RagPolicyIndex` (PolicyIndex 구현체)
- `agents/auto-loan-review/src/main/java/com/bank/ai/rag/seed/` — 정책 코퍼스 seed loader
- `agents/auto-loan-review/src/main/java/com/bank/ai/rag/api/` — `/api/internal/embeddings/batch` 컨트롤러
- `agents/auto-loan-review/src/main/resources/db/migration/V4__create_ai_embedding.sql` + H2 stub
- `agents/auto-loan-review/src/main/resources/db/migration/V5__shadow_run_rag_flag.sql` + H2 stub
- `agents/auto-loan-review/src/main/resources/prompts/review_report_track{1,2,3}_v2.yml`, `rejection_reason_draft_v2.yml`
- `services/loan-service/src/main/java/com/bank/loan/rag/SimilarCaseExporter.java` + 일배치 job

### 3.2 갱신

- `agents/auto-loan-review/build.gradle` — Spring AI BOM + embedding starter
- `agents/auto-loan-review/src/main/resources/application.yml` — `ai.rag.*` + `spring.ai.vertex.*` 섹션
- `com.bank.ai.llm.policy.PolicyIndex` — record → interface 추출
- `com.bank.ai.llm.report.GroundingValidator` — `Citation.id` prefix 분기
- `com.bank.ai.llm.report.ReviewReportInput` — `ragContext` 필드 추가
- `com.bank.ai.llm.report.ReviewReportService` — prompt 에 chunk 주입
- `com.bank.ai.agent.PreReviewAgentService` — `RagRetrievalService` 호출 통합
- `com.bank.ai.shadow.ShadowModeService` + `ShadowComparisonEvaluator` — V1/V2 rag_enabled 분기
- `com.bank.ai.metrics.AgentMetricsRecorder` — RAG 메트릭 4 종 추가
- `docs/plan/rag-corpora.md` §13 — 본 D0~D4 로 교체 (D0-3 작업)
- `docs/plan/next-phase-roadmap.md` Phase D — D0~D4 5 단계로 갱신 (D0-2 작업)
- `docs/plan/banking-review-llm.md` — Phase 1.7 RAG 진행 상태 갱신

### 3.3 무변경

- `com.bank.ai.rule.*` RuleEngine — RAG 는 LLM 입력 보강만, 결정 로직 비변경
- `com.bank.ai.audit.*` AuditLogService — `output_json` 안의 `citations` 에 chunk id 자연 포함, 스키마 변경 불필요
- `com.bank.ai.drift.*` Drift 감지 — RAG 도입 후 정책 chunk 변경이 모델 입력에 영향 시 별도 phase 검토
- 기존 1.9.1~1.9.3 모니터링 인프라 — 메트릭 4 종 추가만

---

## 4. 일정 요약

| Phase | 기간 |
|-------|------|
| D0 | 0.5 주 |
| D1 | 1.5 주 |
| D2 | 1 주 |
| D3 | 0.5 주 |
| D4 | 1 주 |
| **합계** | **4.5 주** (D3·D4 일부 병렬 시 4 주) |

---

## 5. 후속 (Phase D 종료 후 옵션)

| Phase | 내용 | 트리거 |
|-------|------|--------|
| D-F1 | FAQ 코퍼스 P3 (월 1 회 수동, 5~10 건 stub) | 심사원 요청 누적 |
| D-F2 | bge-reranker-v2-m3 ONNX rerank — Track 3 한정 | Track 3 검색 정확도 부족 시 |
| D-F3 | **Agentic RAG** — Spring AI ChatClient `.tools()` 활성, LLM autonomous tool calling, `LlmClient` ChatClient 위임 전환 | Phase D 안정화 + 운영 6 개월 후 |
| D-F4 | 외부 정책 문서 적재 — 금감원 가이드 PDF 파서, 신용정보법 조항 적재 | 정책위 요청 |
| D-F5 | bge-m3 자체 ONNX 호스팅 (`inference-server` 확장) | Vertex 비용·latency 부담 시 |

---

## 6. 착수 전 점검

- [ ] `ai_db` PostgreSQL 16 + pgvector 0.7+ 설치 확인 (전사 결정: PostgreSQL 16 고정)
- [ ] Vertex AI Embedding API 쿼터 (text-embedding-005 RPM)
- [ ] Spring AI 1.x 안정 버전 핀 (`spring-ai-bom` 정확한 마이너 버전)
- [ ] LOAN_REVIEW 합성 seed 1 만 건 준비 — HMDA + Home Credit 변환 산출물 경로 확정
- [ ] PII 마스킹 한국어 정규식 커버리지 점검 (`PiiPattern.java`)
- [ ] Phase B Drift 작업 완료 (현재 untracked drift/ 파일들 커밋) — 본 phase 착수 직전 정리
