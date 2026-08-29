# 계획 문서 색인

> **상태는 코드에 있는 것으로 판정했다.** 문서가 스스로 적은 상태는 믿지 않는다 —
> 실제로 둘이 어긋난 것이 두 건 있었다. `doc-agent.md` 는 머리말이 '구현 미착수'
> 인데 서비스가 돌고 있었고, `fds-realtime-detection.md` 는 '설계 — 승인 전' 인데
> 모듈이 이미 있었다. 계획서를 쓰고 구현한 뒤 머리말을 고치지 않은 것이다.
>
> 상태 어휘는 **넷뿐이다.** 늘리면 다시 제각각이 된다.

| 상태 | 뜻 |
|---|---|
| ✅ 구현됨 | 코드가 있다. 문서는 왜 그렇게 만들었는지의 기록이다 |
| 🔨 진행 중 | 일부만 있다. 무엇이 남았는지 각 문서가 적는다 |
| 📐 설계만 | 코드가 없다. **읽고 붙이면 안 된다** |
| 🗑 폐기 | 전제가 무너졌다. **따르지 말 것** |

총 35건.

---

## ✅ 구현됨 (17건)

| 문서 | 무엇 | 근거 |
|---|---|---|
| [`14_common_db_layer.md`](14_common_db_layer.md) | 14. 공통 계층(common_db) 확장 + loan-service 연결 | `com.bank.commonaccount.*` 패키지 |
| [`admin-full-recovery-plan.md`](admin-full-recovery-plan.md) | admin/user 화면 전면 복구 계획 | admin 화면 복구분 |
| [`admin-loan-frontend-backend-fix-plan.md`](admin-loan-frontend-backend-fix-plan.md) | Admin Loan 기능 프론트↔백엔드 계약 정합화 계획 | 문서 자체가 해소 내역을 기술한다 |
| [`chatbot-ui-execution-plan.md`](chatbot-ui-execution-plan.md) | 챗봇 UI 실행 계획 | `web/components/chatbot/ChatbotWidget.tsx` · `agents/consultation` |
| [`cicd-oracle-to-gcp-plan.md`](cicd-oracle-to-gcp-plan.md) | CI/CD 구축 계획 — Oracle Cloud 1대 배포 → GCP 확장 | 1단계만. `.github/workflows/` 17개로 실제 배포 중. GCP 확장(2단계)은 안 함 |
| [`deposit-service-review-fix-plan.md`](deposit-service-review-fix-plan.md) | Deposit Service Review Fix Plan | 체크 14/14 |
| [`doc-agent.md`](doc-agent.md) | 문서 검증 자동화 에이전트 (doc-agent) 계획서 | `DocAgentApplication`. ⚠ 문서 머리말이 '구현 미착수' 라고 말하지만 **서비스가 돈다** |
| [`fds-realtime-detection.md`](fds-realtime-detection.md) | 실시간 이상거래 탐지 (FDS 탐지 단계) | `services/fds-detector`. ⚠ 문서 머리말이 '설계 — 승인 전' 이라고 말하지만 **모듈이 있다** |
| [`llm-pipeline.md`](llm-pipeline.md) | LLM 보강·리포트 파이프라인 — 큰 그림 | `PurposeAnalysisTool` · `RejectionNoticeService`. 문서 안 미완 체크 7개는 Phase 1.7(RAG) 몫 |
| [`loan-review-authorization-plan.md`](loan-review-authorization-plan.md) | 대출심사 권한 체계 도입 계획 | 권한 22곳을 게이트웨이 검증 헤더(`X-User-Role`)로 전환 |
| [`loan-service-integration.md`](loan-service-integration.md) | loan-service ↔ AI 서비스 연결 가이드 | 연동 가이드. AI 서비스 호출 경로가 코드에 있다 |
| [`loan_eod_batch_plan.md`](loan_eod_batch_plan.md) | EOD 배치 구현 계획 | `EodBatchController` |
| [`loan_eod_batch_plan_v2.md`](loan_eod_batch_plan_v2.md) | EOD 배치 확장 계획 (v2) | `EodBatchController` 확장분 |
| [`observability-phoenix.md`](observability-phoenix.md) | 관측 일원화 — 성능은 Prometheus, 품질은 Phoenix | Phase 0~4 전부. `agents/harness-core/python/harness_core/tracing.py`·`pii.py` |
| [`phase0-cleanup-report.md`](phase0-cleanup-report.md) | Phase 0 — 죽은 코드·스키마 정리 리포트 | 계획이 아니라 **결과 리포트**다 |
| [`pre-review-agent-plan.md`](pre-review-agent-plan.md) | 사전 심사 에이전트 (Pre-Review Agent) — 실행 계획 | `PreReviewAgentService` · `AgentToolRegistry` |
| [`transfer-step-up-auth.md`](transfer-step-up-auth.md) | 이체 거래 승인 인증(step-up) 설계 노트 | `TransactionApprovalController` · `CUST_145/146` |

## 🔨 진행 중 (13건)

| 문서 | 무엇 | 근거 |
|---|---|---|
| [`00_overview.md`](00_overview.md) | loan-service 보완·개선·추가 plan — 개요 | 개요 문서. 아래 개별 계획을 묶는다 |
| [`13_rag_operationalization.md`](13_rag_operationalization.md) | 13. RAG 운영 전환 plan | ES 하이브리드 코드는 있고(`EsHybridSearchService`) 실측이 없다 — OPEN_ITEMS §3 |
| [`admin-full-connection-roadmap.md`](admin-full-connection-roadmap.md) | 담당 도메인 Admin/User 페이지 연결 로드맵 | Phase 0~2 중 일부. 후속이 `admin-full-recovery-plan.md` |
| [`agent-loop-limits-metrics-plan.md`](agent-loop-limits-metrics-plan.md) | 에이전트 루프 한도(도구 ≤6 · LLM ≤2 · 턴 5) 근거 계측 & 설정화 계획 | 루프 한도 상수는 있고 근거 계측이 없다 |
| [`banking-review-llm.md`](banking-review-llm.md) | 대출 심사원 보조 자동 대출 심사 — 큰 그림 | Phase 1.5·1.6 완료, 1.7(RAG) 보류. 문서 머리말이 스스로 밝힌다 |
| [`loan-frontend-backend-mapping.md`](loan-frontend-backend-mapping.md) | 대출 프론트 ↔ 백엔드 전체 연결 계획 | 대출 화면 연결분 |
| [`consultation-db-direct-access-removal.md`](consultation-db-direct-access-removal.md) | A2 — 상담의 수신 원장 DB 직접 조회 제거 | ✅ 구현됨. 코드·설정·망 세 겹 차단 · `test_no_direct_core_banking_access.py` |
| [`loan-payment-ledger-trace.md`](loan-payment-ledger-trace.md) | 여신 자금이동 경로 추적 — Loan → Payment → Deposit → Ledger | 결과 리포트. 차단 4겹 해소·실행 1건이 원장 도달. 회계 완결(C1 ③④⑤) 남음 |
| [`loan_review_bias_check_plan.md`](loan_review_bias_check_plan.md) | 본심사 편향 검증 + 승인자 단계 도입 Plan | `AuditFairnessAgent` 있음. 승인자 단계 미완 체크 10개 |
| [`next-phase-roadmap.md`](next-phase-roadmap.md) | 자동 심사 시스템 — 다음 단계 로드맵 | 로드맵. 링크 중 `phase-e-elasticsearch.md` 는 **없는 문서**다 |
| [`phase-b-operational.md`](phase-b-operational.md) | Phase B — 운영 준비 (Operational Readiness) 실행 계획 | 미완 체크 31개. 관측·배치 일부만 |
| [`rag-corpora.md`](rag-corpora.md) | RAG 3 코퍼스 구축 — 큰 그림 | 코퍼스 일부 적재. 미완 체크 8개 |
| [`rag-regulation-consolidation-plan.md`](rag-regulation-consolidation-plan.md) | 공통 규정 RAG 통합 + Elasticsearch 하이브리드 검색 계획 (doc-agent) | `agents/auto-loan-review/.../rag/es/` 존재 |

## 📐 설계만 — 코드 없음 (3건)

| 문서 | 무엇 | 근거 |
|---|---|---|
| [`hmda-localization.md`](hmda-localization.md) | HMDA → 한국 자동심사 학습 데이터 변환 큰 그림 | 문서 스스로 `design draft`. 코드에 HMDA 변환 경로 없음 |
| [`pd-label-acquisition.md`](pd-label-acquisition.md) | PD 라벨 데이터 확보 — 큰 그림 | 문서 스스로 `design draft`. 12개월 디폴트 추적 데이터가 없다 |
| [`phase-c-ml-pipeline.md`](phase-c-ml-pipeline.md) | Phase C — ML 파이프라인 완성 구현 계획 | 문서 스스로 `design draft`. PD 라벨이 선행인데 그것도 설계만이다 |

## 🗑 폐기 — 따르지 말 것 (2건)

| 문서 | 무엇 | 근거 |
|---|---|---|
| [`phase-d-rag.md`](phase-d-rag.md) | Phase D — RAG 도입 계획 | 문서가 `phase-e-elasticsearch.md` 로 대체됐다고 적었는데 **그 문서가 없다**. 실제 RAG 는 `13_rag_operationalization.md` 계열로 갔다 |
| [`phase-e-frontend.md`](phase-e-frontend.md) | Phase E — 심사원 대시보드 (Reviewer Dashboard Frontend) 실행 계획 | `services/reviewer-dashboard/`(React+Vite)를 전제하는데 그 디렉터리가 없다. 프론트는 `web/`(Next.js) 하나로 갔다 |

---

## 새 계획을 쓸 때

제목 바로 아래에 상태 줄을 넣는다. 없으면 `PlanStatusTest` 가 잡는다.

```markdown
# 무엇을 하는 계획인가

<!-- plan-status -->
> **상태: 📐 설계만 — 코드 없음** — 왜 그렇게 판단하는지 한 줄
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).
```

구현이 끝나면 **상태 줄을 고치는 것까지가 그 작업이다.** 고치지 않으면 다음 사람이
설계만 있는 줄 알고 다시 만들거나, 이미 있는 줄 알고 안 만든다.
