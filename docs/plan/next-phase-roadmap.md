# 자동 심사 시스템 — 다음 단계 로드맵

<!-- plan-status -->
> **상태: 🔨 진행 중** — 로드맵. 링크 중 `phase-e-elasticsearch.md` 는 **없는 문서**다
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).


> Last updated: 2026-05-29
> 선행 문서: `banking-review-llm.md`, `pre-review-agent-plan.md`
> 현재 완료: Phase 1.5 · 1.6 · 1.6.1 · 1.8 (LLM 파이프라인 + Pre-Review Agent 전체)

---

## 현황 요약

```
완료 ✅                           미완 ⬜
───────────────────────────────   ───────────────────────────────────────
RuleEngine + TrackClassifier      Audit Log 영구 보존
HardConstraintEvaluator           Observability (Grafana 대시보드)
PolicyMatrix (MORT_001)           Shadow Mode
AutoReviewService (dual ML)       PSI Drift 감지
PurposeAnalysisService (LLM)      4/5ths Rule 공정성 리포트
ReviewReportService (LLM)         Admin Endpoint (재생성 / kill-switch)
PreReviewAgentService (Agent)     ML ONNX 모델 실 학습 + SHAP
RejectionReasonAgentService       RAG 3개 코퍼스 (보류)
AgentLoopGuard / GroundingValid.  심사원 대시보드 (Frontend)
LlmRequestRateMeter               2nd Opinion Agent (Phase 2)
StubLlmClient + E2E smoke         계약서 리스크 (Phase 3)
PROMPT_REGISTRY / MODEL_CARDS
```

---

## Phase B — 운영 준비 (가장 시급, ~4주)

> 현재 코드에 붙이는 관찰성·감사성·관리 계층.
> 이것 없이는 실제 심사원에게 배포 불가.

### B1. Audit Log 영구 보존

**목표**: 모든 자동 결정의 입력·출력·모델버전·프롬프트버전을 재현 가능하게 기록.

**산출물**
- Flyway `V3__create_audit_log.sql`
  ```sql
  CREATE TABLE ai_audit_log (
    id           BIGSERIAL PRIMARY KEY,
    rev_id       BIGINT NOT NULL,
    event_time   TIMESTAMPTZ NOT NULL DEFAULT now(),
    track        VARCHAR(10),
    model_id     VARCHAR(50),
    model_ver    VARCHAR(20),
    prompt_id    VARCHAR(60),
    prompt_ver   INTEGER,
    input_hash   CHAR(64),      -- SHA-256 of request JSON
    input_json   JSONB,
    output_json  JSONB,
    fallback_rsn VARCHAR(40),
    pii_masked   BOOLEAN NOT NULL DEFAULT TRUE
  );
  CREATE INDEX ai_audit_log_rev_idx ON ai_audit_log(rev_id);
  ```
- `AuditLogService` — `AutoReviewEventListener` 완료 시점에 기록
- `GET /api/ai/admin/audit/{revId}` — 특정 신청의 전체 감사 이력 조회
- 재현성 endpoint: `POST /api/ai/admin/replay/{revId}` — 동일 입력으로 파이프라인 재실행 (dry-run, 저장 X)

**단계**
| # | 작업 | 커밋 |
|---|------|------|
| B1-1 | Flyway + `AuditLogRepository` | feat(audit) |
| B1-2 | `AuditLogService` + `AutoReviewEventListener` 훅 | feat(audit) |
| B1-3 | Admin 조회 endpoint + 단위 테스트 | feat+test(audit) |

---

### B2. Observability — Micrometer 메트릭 + Grafana

**목표**: 에이전트 런타임 상태를 Prometheus + Grafana로 실시간 가시화.

**산출물 — 메트릭**
| 메트릭 이름 | 유형 | 태그 |
|------------|------|------|
| `ai_agent_runs_total` | Counter | `track`, `outcome(SUCCESS/FALLBACK)` |
| `ai_agent_tool_calls_total` | Counter | `tool_name` |
| `ai_agent_llm_latency_seconds` | Histogram | `prompt_id` |
| `ai_agent_rpm_remaining` | Gauge | — |
| `ai_agent_rpd_remaining` | Gauge | — |
| `ai_agent_disagreement_total` | Counter | `track` |
| `ai_llm_cost_tokens_total` | Counter | `prompt_id`, `token_type(input/output)` |
| `ai_pipeline_duration_seconds` | Histogram | `track` |

**산출물 — 대시보드**
- `infra/grafana/dashboards/ai-agent-overview.json`
  - 패널: 트랙별 처리량, 폴백률, LLM 지연, RPD/RPM 잔량, disagreement 발생률
- Docker Compose provisioning (`infra/grafana/provisioning/`)

**단계**
| # | 작업 | 커밋 |
|---|------|------|
| B2-1 | `AgentMetrics` 빈 (`MeterRegistry` 래퍼) + `PreReviewAgentService` 훅 | feat(metrics) |
| B2-2 | Grafana JSON 대시보드 + Docker provisioning | feat(infra) |
| B2-3 | 메트릭 단위 테스트 (MockMeterRegistry) | test(metrics) |

---

### B3. Shadow Mode

**목표**: 배포 첫 2개월간 자동 심사 결과를 표시만 — 실제 결정권 없음. 사람 심사 일치율 측정.

**산출물**
- `application.yml`: `bank.ai.shadow-mode.enabled: true`
- `ShadowModeFilter` — shadow 모드일 때 `AutoReviewEvaluateResponse.track` → `SHADOW_{TRACK}` 표기, loan-service UPDATE 스킵
- `shadow_audit_log` 별도 테이블 — 자동 결정 + 최종 사람 결정 양쪽 기록
- 일치율 집계 배치 (`ShadowMatchRateBatchJob`, Spring Batch)
  - `shadow_match_rate{track}` Micrometer Gauge 노출
- 리포트: `/api/ai/admin/shadow-report?from=2026-06-01&to=2026-06-30`

**단계**
| # | 작업 | 커밋 |
|---|------|------|
| B3-1 | `ShadowModeFilter` + 설정 | feat(shadow) |
| B3-2 | `shadow_audit_log` 테이블 + 배치 잡 | feat(shadow) |
| B3-3 | 일치율 리포트 endpoint | feat(shadow) |
| B3-4 | 통합 테스트 (shadow 플래그 ON/OFF 각 1 케이스) | test(shadow) |

---

### B4. PSI Drift 감지 + 공정성 리포트

**목표**: Feature 분포 이상 조기 탐지 + 보호 속성별 트랙 진입률 4/5ths Rule 주간 검사.

**산출물**
- `PsiDriftDetector` — 기준 분포 vs 최근 7일 분포 PSI 계산
  - `ai_feature_psi{feature_name}` Gauge. 0.2 초과 시 `ai_feature_psi_alert` Counter 증가 + 경고 로그
  - 대상 피처: `decision_score`, `pd_score`, `dsr`, `credit_score`, `requested_amount_kw` (5종 우선)
- `FairnessReportBatchJob` — 주간 4/5ths Rule 리포트
  - 보호 속성: `sex`, `age_band(20대/30대/40대/50대+)`, `segment`
  - 검사 항목: Track 1 진입률 비율, Track 2 진입률 비율
  - `/api/ai/admin/fairness-report?week=2026-W23`

**단계**
| # | 작업 | 커밋 |
|---|------|------|
| B4-1 | `PsiDriftDetector` + 스케줄 배치 (`@Scheduled`, 매일 01:00) | feat(monitor) |
| B4-2 | `FairnessReportBatchJob` + endpoint | feat(monitor) |
| B4-3 | PSI 단위 테스트 (합성 분포 shift) | test(monitor) |

---

### B5. Admin Endpoint

**목표**: 운영자가 코드 배포 없이 에이전트 상태 제어.

**산출물**
- `POST /api/ai/admin/re-evaluate/{revId}` — 강제 재생성 (idempotency 체크 우회)
- `GET  /api/ai/admin/agent-status` — RPD/RPM 잔량, 최근 1시간 fallback_reason 분포
- `POST /api/ai/admin/kill-switch` body `{"enabled": false}` — 런타임 kill switch (Redis 또는 DB 플래그)
- 인증: `X-Admin-Token` 헤더 (개발 단계, 추후 OAuth2 전환)

---

## Phase C — ML 파이프라인 완성 (~6주)

> 현재 `InferenceClient`가 호출하는 inference-server를 실제 ONNX 모델로 채운다.

### C1. 합성 데이터 검증 결함 수정

`banking-review-llm.md §10`에서 Phase 1.1 "검증 결함 3개 수정 필요"로 표기된 항목 수정.
- `synthetic-data-generator/` 재실행 후 결과 검증

### C2. 실제 ML 모델 학습 + ONNX Export

**hmda_v1 (Decision Score)**
```
학습 스크립트: ml/train_hmda_v1.py
  - 데이터: HMDA 2018 + synthetic Layer 1~3
  - 알고리즘: XGBoost / LightGBM (교차검증 후 선택)
  - 목표: AUC-ROC ≥ 0.87
  - 출력: ml/models/hmda_v1.onnx
```

**homecredit_kr_v1 (PD Score)**
```
학습 스크립트: ml/train_homecredit_kr_v1.py
  - 데이터: Home Credit + synthetic Layer 4 PD 피처
  - 알고리즘: LightGBM
  - 목표: Gini ≥ 0.64, KS ≥ 0.41
  - 출력: ml/models/homecredit_kr_v1.onnx
```

**SHAP 통합**
- `shapiq` 또는 `shap` 라이브러리로 top-k=5 기여 피처 산출
- `InferenceResponse.shapTopK: List<ShapFeature>` 필드 추가
- `GroundingValidator` SHAP 값 numeric claim 검증 확장

### C3. Inference Server (Python FastAPI)

```
services/inference-server/
├── main.py             — FastAPI 앱
├── model_registry.py   — ONNX 모델 로드 (버전 핀)
├── endpoints/
│   ├── predict.py      — POST /predict (hmda_v1)
│   └── predict_pd.py   — POST /predict/pd (homecredit_kr_v1)
└── feature_builder.py  — AutoReviewRequest → feature vector
```

- ONNX Runtime 기반 서빙 (GPU 불필요)
- Feature contract: `AutoReviewRequest` 컬럼명 == 모델 입력 피처명 (교체 무결)
- 단위 테스트: `pytest` + `onnxruntime` mock

### C4. ML 회귀 검증

- Track 분포 검증: 합성 데이터 10K 건 일괄 처리 → Track 1/2/3 비율이 30~40/15~25/40~55 안에 드는지
- 4/5ths Rule: 보호 속성별 Track 1 진입률 비율 검사 (CI 자동화)
- `MLRegressionTest` (JUnit) — inference-server WireMock 응답으로 E2E 흐름 검증

---

## Phase D — RAG 구축 (⚠️ SUPERSEDED → Phase E 로 대체)

> ~~pgvector + PostgreSQL FTS 가중합 방안~~ — **Phase E (Elasticsearch) 로 교체됨**.
> 실행 계획: **[`phase-d-rag.md`](phase-d-rag.md)** (historical, superseded).
> 코퍼스 정의·인터페이스·LLM 패턴은 Phase E 에 승계.

---

## Phase E — Elasticsearch 하이브리드 검색 RAG (~5주)

> 실행 계획: **[`phase-e-elasticsearch.md`](phase-e-elasticsearch.md)** (E0~E4 단계·게이트·산출물).
> 설계: **[`rag-corpora.md`](rag-corpora.md)** (코퍼스 정의·메타 필터 — 유효).

**핵심 결정**: ES 8.15 네이티브 `retriever` RRF (BM25 + kNN), nori 한국어 분석기, dims=768 int8_hnsw.

| 단계 | 내용 | 기간 |
|------|------|------|
| **E0** | 결정 + 사전 정비 — phase-d-rag.md supersede, next-phase-roadmap 교체, rag-corpora.md ES 매핑 부록 | 0.5주 |
| **E1** | ES 인프라 + 인덱스 + 임베딩 — docker-compose profile `rag`, ES Java 클라이언트, 인덱스 템플릿 3종, `EmbeddingClient`(Stub 768d), `EsClientConfig`, `EsIndexAdminService` | 1.5주 |
| **E2** | 하이브리드 검색 통합 — `EsHybridSearchService` (RRF), `EsPolicyIndex`, `ai.rag.*` ES 설정, `RagRetrievalService` swap, 프롬프트 v2, `GroundingValidator` prefix 분기 | 1주 |
| **E3** | 코퍼스 적재 파이프라인 — P1 정책 seed, P3 FAQ endpoint, P2 outbox→Kafka→Connect, `CaseEmbeddingEnricher`, PII smoke, 합성 1만건 seed | 1.5주 |
| **E4** | 검증 + 운영 cutover — RAG 메트릭 5종 + Grafana, Shadow Mode 확장, E2E smoke 6케이스, canary (shadow→5%→25%→100%) | 0.5주 |

**옵션 후속 (E 종료 후)**: E-F1 `text_similarity_reranker` · E-F2 ELSER sparse · E-F3 Agentic RAG · E-F4 외부 정책 PDF.

---

## Phase F — 심사원 대시보드 (Frontend, ~5주)

> 심사원이 실제로 사용하는 화면. 백엔드 API는 이미 완성.

### F1. 기술 선택
- **React 18 + TypeScript + Vite**
- 상태 관리: Zustand (경량)
- UI: shadcn/ui + Tailwind
- 실시간: SSE (`EventSource`) — loan-service → ai-service → 대시보드

### F2. 핵심 화면

**심사 대기 목록**
- 트랙별 뱃지 (TRACK_1 초록 / TRACK_2 빨강 / TRACK_3 주황)
- `disagreement=true` → 빨간 경고 아이콘
- 리포트 생성 중 → 스피너 → SSE 완료 시 갱신

**트랙별 심사 카드**
```
Track 1 카드:
  ├─ PD 스코어 + Decision 스코어 (게이지)
  ├─ 정책 룰 통과 체크리스트
  ├─ LLM 리포트 1단락
  └─ [Sign-off] 버튼

Track 2 카드:
  ├─ Hard Fail 사유 목록 (DSR_EXCEEDED 등)
  ├─ 거절 통보문 초안 (편집 가능 textarea)
  ├─ 정책 인용 (citations[])
  └─ [거절 확정] 버튼

Track 3 카드:
  ├─ 이중 스코어 대시 (PD + Decision)
  ├─ SHAP top-5 위험 요인 바 차트
  ├─ What-if 시뮬레이션 결과 (scenario별 카드)
  ├─ LLM 리포트 3단락 (편집 가능)
  ├─ AgentOpinion 요약 + risk_level 뱃지
  └─ [의견 작성 → 결정] 폼
```

**관리자 패널**
- Shadow Mode 토글
- RPD/RPM 잔량 게이지
- Fallback 분포 파이 차트 (최근 24시간)
- Fairness Report 테이블

### F3. 실시간 Push
```
loan-service → LOAN_REVIEW 업데이트 → SSE 브로드캐스트
ai-service   → LLM 리포트 완료     → SSE 브로드캐스트
Frontend     → EventSource 구독    → 카드 갱신
```

---

## Phase F — Phase 2 에이전트 (후순위)

### F1. 2nd Opinion Agent
- Track 3에서 1차 AgentOpinion과 독립된 관점으로 재심사
- 다른 시나리오 세트 시뮬레이션 (amount_reduction_40pct, combined 등)
- `2nd_opinion_json` 컬럼 분리 저장
- 심사원 화면: 1차 의견 vs 2차 의견 비교 패널

### F2. 계약서 리스크 분석
- PDF 계약서 → OCR → 조항별 리스크 스코어
- 이상 조항 하이라이트 (LLM 구조화 출력)
- 기존 PII 마스킹 파이프라인 재사용

### F3. 실데이터 Transfer Learning
- 운영 적재 후 실데이터 확보 → hmda_v1 / homecredit_kr_v1 파인튜닝
- Feature contract 유지 (`synthetic_application` 컬럼명 == 모델 입력)
- Shadow 모드 일치율이 95% 이상일 때 실데이터 전환 검토

---

## 우선순위 요약

```
즉시 착수 (Phase B)
  B1 Audit Log           — 규제 필수 (여신전문금융업법 기록 의무)
  B2 Observability       — 운영 가시성
  B3 Shadow Mode         — 배포 전 신뢰 검증
  B4 PSI + 공정성        — AI 감독 규제 선제 대응
  B5 Admin Endpoint      — 운영 제어

그 다음 (Phase C)
  C1 합성 데이터 수정    — 모델 품질 기반
  C2 ML 모델 학습        — inference-server 실화
  C3 SHAP 통합           — Track 3 설명성
  C4 회귀 검증           — 트랙 분포 목표치 확인

선택 / 순서 유동 (Phase E·F)
  E  RAG (Elasticsearch) — E0→E1→E2→E3→E4 순 (phase-e-elasticsearch.md)
  F  Frontend            — C 완료 후 착수가 자연스러움 (API 안정 후 UI 바인딩)

후순위 (Phase F)
  F1 2nd Opinion         — E 완료 후
  F2 계약서 리스크        — 별도 로드맵
  F3 실데이터 전환        — 운영 6개월 후
```

---

## 예상 일정

| Phase | 내용 | 기간 |
|-------|------|------|
| B | 운영 준비 (B1~B5) | 3~4주 |
| C | ML 파이프라인 (C1~C4) | 5~6주 |
| E | RAG (Elasticsearch, E0~E4) | 5주 |
| F | Frontend 대시보드 | 4~5주 |
| F | Phase 2 에이전트 | 별도 로드맵 |

**MVP 완성 기준**: Phase B + C 완료 → 실제 심사원에게 Shadow Mode로 시범 배포 가능.

---

## 다음 착수 권장 — B1 Audit Log

현재 `AutoReviewEventListener`가 `loanServiceClient.updateReport()` 콜백을 호출하는 시점에 감사 로그만 붙이면 된다. 기존 파이프라인을 건드리는 범위가 최소여서 리스크 낮고, 규제 측면에서 가장 우선순위가 높다.

```
B1 착수 → B2 → B3 → B4 → B5 → (C1 ~ C4) → E
```
