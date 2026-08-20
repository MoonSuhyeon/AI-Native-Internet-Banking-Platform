# 13. RAG 운영 전환 plan

<!-- plan-status -->
> **상태: 🔨 진행 중** — ES 하이브리드 코드는 있고(`EsHybridSearchService`) 실측이 없다 — OPEN_ITEMS §3
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).


> **선행**: Phase 1.7 (Agentic RAG 전체 흐름) — 2026-05-28 완료
> **목적**: 현재 stub 임베딩 + 합성 시드로 동작하는 RAG 를 실 임베딩 모델 + 정책 시드 + 합성 케이스 백필 기반으로 전환하고, 장기적으로 `advisory-service` 자체 RAG 를 `ai-service` RAG 로 통합
> **범위**: `advisory-service` + `ai-service` 양쪽. 시나리오 δ — ai-service 가 최종 단일 RAG 진실, advisory 는 클라이언트로 수렴

---

## 0. 진척도

상태 표기: ☑ 완료 / ◑ 진행중 / ☐ 미착수

| Stage | 작업 | 상태 | 잔여 |
|---|---|---|---|
| 0  | Phase 1.7 Agentic RAG 흐름 (인용 청크 회수, PII 마스킹, E2E 회귀) | ☑ | — |
| 1  | 실 임베딩 모델 전환 (Stub → OpenAI 3-small **또는** Ollama bge-m3) | ☑ | — |
| 2  | 정책문서 시드 (ai-service `seed-data/` + Admin API 업로드) | ☑ | — |
| 3  | 합성 케이스 생성 + `ADVISORY_CASE_INDEX` 백필 | ☑ | — |
| 4  | ivfflat 인덱스 튜닝 (rows 측정 후 결정) | ☑ | — |
| 5  | advisory ↔ ai-service RAG 통합 (시나리오 δ) | ☐ | 본 plan |
| 6  | 운영 메트릭/알람 (RagMetrics 기반) | ☐ | 본 plan |
| 7  | 잔여 정리 (advisory 자체 RAG 코드 deprecate, 시드 정합성 검증) | ☐ | 본 plan |

---

## 1. 현황 정리

### 1-1. 완료된 것 (Phase 1.7)

- 스키마: `ADVISORY_DOCUMENT`, `ADVISORY_DOCUMENT_CHUNK`, `ADVISORY_CASE_INDEX`, `ADVISORY_RETRIEVAL_LOG` (advisory V3) / `RAG_DOCUMENT`, `RAG_CHUNK`, `RAG_INGESTION_LOG` (ai V1)
- 인제스트 (advisory): `DocumentIngestionService` — 800자 청크 / 100자 overlap / append-only
- 인제스트 (ai-service): `IngestionService` + `TextChunker` + `TikaDocumentParser` (PDF/DOCX) + `RagIngestionScheduler`
- 검색 (advisory): `PolicyCitationRetriever`, `SimilarCaseRetriever`
- 검색 (ai-service): `RetrieverService` + `RagProfile` (PRODUCT / REVIEW / BIAS_AUDIT) + `POST /rag/search`
- Agentic 연동: `review-ai-gateway` 의 tool executor 4종 → AgenticLoop 호출
- 인용 회수: `AiAuditOpinion.citedChunkIds` 영속 + E2E 회귀 테스트
- PII 안전망: tool 결과 → LLM 전달 직전 강제 마스킹

### 1-2. 두 RAG 의 비교 (시나리오 δ 정당화)

| 기능 | advisory-service RAG | ai-service RAG |
|---|---|---|
| 파서 | 텍스트만 | Tika (PDF/DOCX) |
| 청크 | 문자 단위 고정 | 슬라이딩 윈도우 |
| 임베딩 | Stub 운영 중 | OpenAI + Mock 분리 |
| 인제스트 | 동기 | Scheduler 기반 |
| 프로파일 | 없음 | 3종 (PRODUCT/REVIEW/BIAS_AUDIT) |
| Admin API | 없음 | `POST /internal/rag/documents` |
| 사용처 | 4 tool + evaluator | 0 건 (미연동) |

→ 기능은 ai-service 가 우위, 사용처는 advisory 가 독점. **시나리오 δ** 로 통합.

### 1-3. 운영 전환을 막는 격차

| # | 격차 | 영향 |
|---|---|---|
| G1 | `StubEmbeddingClient` 가 prod 경로에서 동작 | 검색 품질 사실상 0 |
| G2 | 정책문서 시드 없음 (합성 5건만) | 커버리지 부족 |
| G3 | `ADVISORY_CASE_INDEX` 백필 미수행 | similar-cases tool 빈 결과 |
| G4 | ivfflat lists=100 임의값 / ai-service 는 주석 처리 | 데이터 규모 미확정 상태의 가정값 |
| G5 | advisory ↔ ai-service RAG 이중 구현 | 코드 중복 |
| G6 | `RagMetrics` 정의는 있으나 대시보드·알람 없음 | 운영 가시성 0 |

---

## 2. 비-목표

- **다중 임베딩 모델 동시 운영** — 단일 모델로 전환만, A/B 는 별도 plan
- **재랭킹 (BGE-Reranker 등)** — 1-stage 코사인 유지
- **다국어/영문 정책** — 한국어 정책만
- **외부 검색엔진 도입 (Elasticsearch, Qdrant)** — pgvector 유지
- **임베딩 캐시/배치 큐** — 동기 처리 유지, 본 plan 후 부하 보고 판단
- **3-large·내부 호스팅 모델** — Stage 1 옵션에서 제외 (포트폴리오 비용·복잡도 기준)

---

## 3. Stage 1. 실 임베딩 모델 전환 (G1)

### 옵션

| 옵션 | 모델 | 차원 | 비용 | 한국어 | 인프라 부담 |
|---|---|---|---|---|---|
| **A** | OpenAI `text-embedding-3-small` | 1536 | $0.02/1M 토큰 (포트폴리오 전체 약 $0.02) | 양호 | 카드 등록 + API key |
| **D** | Ollama `bge-m3` (로컬) | 1024 | **0원** | 우수 (다국어 특화) | Ollama 설치 + 모델 pull (~2GB) |

**선택은 사용자 결정 항목 (§7).** 옵션 D 선택 시 DDL `vector(1536)` → `vector(1024)` V4 마이그레이션 필요.

### 커밋 단계 (옵션 A 기준)

1. `feat(rag): OpenAiEmbeddingClient 를 advisory 기본 구현체로 승격` — `EmbeddingClient` 빈 우선순위 조정, `application.yml` 모델·api-key, `embedding_model_cd` 값 `OPENAI_3S` 표준화
2. `feat(rag): Stub 구현체를 test profile 로 격리` — `@Profile("test")`, prod 비활성
3. `feat(rag): 임베딩 호출 실패 시 BusinessException 매핑` — `LoanErrorCode.RAG_EMBEDDING_FAILED` 신설, 인제스트 트랜잭션 경계 재검토 (AI_GUIDELINES: 트랜잭션 안 외부 API 금지)
4. `test(rag): 임베딩 모델 전환 후 인제스트·검색 회귀`

### 커밋 단계 (옵션 D 기준)

1. `feat(rag): OllamaEmbeddingClient 신규 추가` — `EmbeddingClient` 구현, base-url·모델명 프로퍼티
2. `feat(rag): vector 차원 1024 로 V4 마이그레이션` — 기존 청크 데이터 truncate (운영 전이라 무방)
3. `feat(rag): Stub 구현체를 test profile 로 격리`
4. `feat(rag): 임베딩 호출 실패 시 BusinessException 매핑`
5. `test(rag): Ollama 클라이언트 mock 기반 단위 + 회귀`

**완료 조건:**
- prod profile 에서 의도한 `EmbeddingClient` 빈이 로드됨을 ApplicationContext 테스트로 확인
- 기존 stub 기반 회귀 전부 그린

---

## 4. Stage 2. 정책문서 시드 (G2)

### 4-1. 시드 위치

```
services/ai-service/
└── seed-data/
    ├── README.md
    ├── law/
    │   ├── LAW_BANKING_ACT.pdf
    │   └── LAW_CREDIT_INFO.pdf
    ├── supervision/
    │   ├── SUPER_FSS_LOAN.pdf
    │   └── SUPER_FSS_DSR.pdf
    ├── internal/
    │   ├── INTERNAL_REVIEW_MANUAL.md
    │   └── INTERNAL_BIAS_GUIDE.md
    ├── product/
    │   ├── PRODUCT_MORTGAGE_TERMS.pdf
    │   └── PRODUCT_CREDIT_TERMS.pdf
    └── fair-lending/
        ├── FAIR_LENDING_ACT.pdf
        └── BIAS_CASE_2024_01.md
```

파일명 규칙: `{doc_cd}.{ext}` — `doc_cd` 가 그대로 파일 stem.

### 4-2. doc_type 매핑

| 폴더 | doc_type_cd | 비고 |
|---|---|---|
| `law/` | `LAW` | 법률 |
| `supervision/` | `SUPERVISION_GUIDE` | 감독규정·가이드라인 |
| `internal/` | `INTERNAL_RULE` | 내규·매뉴얼 |
| `product/` | `PRODUCT_TERMS` | 상품 약관 |
| `fair-lending/` | `FAIR_LENDING` (법령) / `BIAS_CASE` (사례) | 차별·공정대출 |

### 4-3. 커밋 단계

1. `feat(rag): seed-data 디렉토리 + README 추가` — 폴더 신설, doc_cd·doc_type 매핑·업로드 절차 명시
2. `feat(rag): SeedDataUploader 스크립트` — `services/ai-service/scripts/upload_seed_data.py` 또는 Java CLI, `seed-data/` 를 재귀 스캔해 `POST /internal/rag/documents` 로 적재. `--dry-run` 지원, doc_cd+version 중복 시 NO-OP (멱등)
3. `feat(rag): seed-data → BANKING_REVIEW 임베딩 적재 검증 엔드포인트` — Admin API 응답에 청크 수·임베딩 모델 코드 포함
4. `test(rag): seed-data 업로더 dry-run + 멱등성 회귀`

**완료 조건:**
- 사용자가 넣은 정책 PDF/MD 가 `RAG_DOCUMENT.active_yn='Y'` 상태로 적재
- 동일 파일 재업로드 시 신규 row 0건
- `seed_hmda_rag.py` (합성) 와 본 시드의 관계: `seed-data/` = prod 시드, `seed_hmda_rag.py` = dev/staging — README 에 명시

**제외:** 자동 크롤링·정기 동기화는 본 plan 비-목표.

---

## 5. Stage 3. 합성 케이스 생성 + 백필 (G3)

### 5-1. 흐름

```
synthetic-data-generator
  → BANKING_REVIEW 합성 행 1,000건 적재 (다양한 conclusion·bias 시나리오)
CaseIndexBackfillService
  → 완료된 BANKING_REVIEW 페이지 단위 읽기
  → PII 마스킹 후 자연어 summary 생성
  → 임베딩 (Stage 1 모델)
  → ADVISORY_CASE_INDEX 적재
```

### 5-2. 커밋 단계

1. `feat(rag): synthetic-data-generator 에 review-case 생성 모드 추가` — conclusion·bias 분포 파라미터, 1회 실행으로 1,000건 적재
2. `feat(rag): CaseIndexBackfillService` — Pageable, PII 마스킹, 임베딩 dispatch 분리 (트랜잭션 안 외부 API 금지)
3. `feat(rag): 백필 운영자 API` — `POST /api/internal/rag/case-index/backfill?from=...&to=...&dryRun=...`, `rev_id` UNIQUE 로 멱등
4. `feat(rag): 백필 진행 메트릭` — 처리/건너뜀/실패 카운터 (RagMetrics 확장)
5. `test(rag): 백필 멱등성 + PII 마스킹 검증`
6. `test(rag): 백필 후 similar-cases tool 응답 포함 E2E`

**완료 조건:**
- staging 환경에서 1,000건 백필 성공, 재실행 시 신규 0건
- similar-cases tool 결과 비어 있지 않음

---

## 6. Stage 4. ivfflat 인덱스 튜닝 (G4)

### 6-1. lists 결정 룰

pgvector 공식 권장:
- `rows ≤ 1M` → `lists = rows / 1000`
- `rows > 1M` → `lists = sqrt(rows)`

| 청크 수 | 권장 lists | 권장 probes | 비고 |
|---|---|---|---|
| < 5,000 | **인덱스 제거 (seq scan)** | — | 청크 적을 땐 인덱스 오버헤드가 더 큼 |
| 5,000 | 50 | 5~10 | |
| 10,000 | 100 | 10 | 현재 advisory DDL 값 |
| 50,000 | 224 | 15 | sqrt(50k) |
| 100,000 | 316 | 20 | |

### 6-2. 커밋 단계

선행: Stage 2·3 완료로 실제 청크 수 측정 가능해진 뒤 진행.

1. `feat(rag): ADVISORY_DOCUMENT_CHUNK ivfflat lists 재산정 (V4)` — 실제 rows 기준 lists 계산, REINDEX
2. `feat(rag): RAG_CHUNK 의 ivfflat 인덱스 활성화 (V2)` — ai-service V1 의 주석 해제 또는 새 마이그레이션
3. `feat(rag): ADVISORY_CASE_INDEX ivfflat 인덱스 신설`
4. `test(rag): EXPLAIN ANALYZE 인덱스 사용 검증` — 1회성 결과 캡쳐, 회귀 아님

**완료 조건:**
- p95 검색 latency < 200ms (`rag.search.duration`)
- EXPLAIN 결과에 `Index Scan using ... ivfflat` 출현

---

## 7. Stage 5. advisory ↔ ai-service RAG 통합 (시나리오 δ, G5)

### 7-1. 방향

ai-service RAG 가 단일 진실. advisory 는 ai-service `/rag/search` 를 호출하는 클라이언트로 수렴.

```
[ Before ]
advisory.PolicyCitationRetriever ─→ ADVISORY_DOCUMENT_CHUNK
advisory.SimilarCaseRetriever    ─→ ADVISORY_CASE_INDEX
ai-service.RetrieverService      ─→ RAG_CHUNK  (사용처 0)

[ After ]
review-ai-gateway tool executors ─→ ai-service /rag/search ─→ RAG_CHUNK + 케이스 인덱스
advisory 의 자체 RAG 코드        : Stage 7 에서 deprecate
```

### 7-2. 커밋 단계

1. `feat(rag): ai-service 에 case-index 검색 추가` — `RetrieverService.searchCases(...)` 또는 별도 `CaseRetrieverService`, 기존 `RagProfile` 와 호환되는 새 프로파일 (`SIMILAR_CASE`) 추가
2. `feat(rag): ai-service 의 case-index 테이블 신설 (V2)` — 기존 `ADVISORY_CASE_INDEX` 데이터 이전 또는 ai-service 가 advisory DB 를 참조하지 않도록 케이스 인덱스도 `RAG_CHUNK` 의 doc_type 으로 흡수 (`doc_type_cd='REVIEW_CASE'`)
3. `feat(rag): review-ai-gateway tool executor 4종을 ai-service 호출로 전환` — `PolicyCitationToolExecutor` 가 advisory 가 아닌 ai-service `/rag/search` 호출, `SimilarCasesToolExecutor` 동일
4. `feat(rag): advisory 의 PolicyCitationRetriever·SimilarCaseRetriever 를 ai-service 호출 어댑터로 교체` — 기존 호출처(`AdvisoryEvaluator`, `AdvisoryRagController`) 가 그대로 동작하도록 인터페이스 유지
5. `test(rag): 통합 후 4 tool executor + AdvisoryEvaluator 회귀` — 동일 시나리오에서 동일 결과 보장

**완료 조건:**
- review-ai-gateway 4 tool 호출 경로가 ai-service 로 통일
- advisory 의 ADVISORY_DOCUMENT_CHUNK·ADVISORY_CASE_INDEX 는 읽기 없음 (deprecate 대상)
- E2E 회귀 그린

**비-목표:** advisory 의 RAG DDL 삭제 — Stage 7 에서 처리.

---

## 8. Stage 6. 운영 메트릭/알람 (G6)

### 8-1. 커밋 단계

1. `feat(rag): RagMetrics 에 tool 별 호출 메트릭 추가` — `docs/monitoring/TOOL_METRICS_GUIDE.md` 참조
2. `feat(rag): 임베딩 API 메트릭` — `rag.embedding.duration{model,status}`, `rag.embedding.calls{model,status}`
3. `feat(rag): Grafana 대시보드 RAG 섹션` — `docs/monitoring/INTERNET_BANKING_SERVICE_OVERVIEW_GUIDE.md` 갱신 (검색 QPS, p95, 실패율, 인용 청크 분포)
4. `feat(rag): Prometheus alertmanager 룰` — 검색 실패율 > 5% 5분, 임베딩 latency p95 > 2s 5분

**완료 조건:**
- 대시보드에 RAG 섹션 노출
- 알람 룰 staging 배포 + 인위적 실패 트리거로 발화 확인

---

## 9. Stage 7. 잔여 정리 (최후)

Stage 5 통합이 안정화된 뒤 정리.

1. `feat(rag): advisory 의 RAG 패키지 deprecate` — `@Deprecated` + README 에 "ai-service 로 이전됨" 명시
2. `feat(rag): advisory 의 RAG DDL 제거 마이그레이션 (V5)` — `ADVISORY_DOCUMENT*`, `ADVISORY_CASE_INDEX`, `ADVISORY_RETRIEVAL_LOG` drop. 데이터는 Stage 5 에서 ai-service 로 이전 완료 전제
3. `feat(rag): 사용자가 추가한 seed-data 정합성 검증 스크립트` — doc_cd 유일성, 폴더↔doc_type 매핑, 청크 임베딩 누락 0건 확인
4. `test(rag): 통합 RAG 전체 흐름 최종 회귀` — Phase 1.7 의 E2E 가 시나리오 δ 상에서도 그린

---

## 10. 단계 간 의존

```
Stage 1 ── Stage 2 ── Stage 3 ── Stage 4 ── Stage 5 ── Stage 7
                                              └─ Stage 6 (Stage 5 이후 점진)
```

- Stage 1 없이 2/3 진행하면 stub 임베딩으로 적재 → Stage 1 후 전량 재인제스트 필요 → 1 우선
- Stage 4 는 데이터 규모가 보여야 의미 → 2·3 뒤
- Stage 5 는 1·2·3 이 advisory 측에서 안정화된 뒤
- Stage 6 은 Stage 5 이후 메트릭이 ai-service 단일 경로로 일원화돼야 의미 있음
- Stage 7 은 최후

---

## 11. 공통 검증

- 각 stage 마지막에 `:services:advisory-service:test` + `:services:review-ai-gateway:test` + `:services:ai-service:test` 풀런
- `AdvisoryRagFlowTest`, `AgenticRagE2ETest` (Phase 1.7 회귀) 그린 유지
- 메모리 룰 준수: 배치 테스트 연도 분리 / feat·test 분리 커밋 / 한 단계 후 보고 / AI 흔적 금지 / 커밋 메시지 한 줄

---

## 12. 확정된 결정 사항

| 항목 | 결정 | 비고 |
|---|---|---|
| 임베딩 모델 | **OpenAI `text-embedding-3-small`** | 차원 1536, DDL 변경 불필요 |
| 케이스 인덱스 위치 | **`RAG_CHUNK` 에 `doc_type_cd='REVIEW_CASE'` 로 흡수** | 별도 테이블 없음, Stage 5 에서 advisory 의 `ADVISORY_CASE_INDEX` 를 폐기 |

Stage 1 부터 단계별로 진행. 사용자가 정책 PDF/MD 를 `services/ai-service/seed-data/` 에 직접 채우는 작업은 Stage 2 와 병렬로 가능.
