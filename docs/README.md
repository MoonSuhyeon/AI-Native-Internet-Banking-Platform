# 문서 색인

문서가 103개다. 진입점이 없으면 있는 문서를 못 찾아 같은 내용을 다시 쓰게 된다.
**찾는 목적별로** 정리한다.

> 문서를 새로 만들면 이 색인에 한 줄 추가한다. 색인에 없는 문서는 없는 것과 같다.

---

## 먼저 읽을 것

| 문서 | 언제 |
|---|---|
| [`../README.md`](../README.md) | 프로젝트가 무엇인지, 서비스 구성 |
| [`AI_GUIDELINES.md`](AI_GUIDELINES.md) | **AI 에이전트로 작업하기 전에.** 커밋 규칙·금지 사항 |
| [`OPEN_ITEMS.md`](OPEN_ITEMS.md) | **무엇이 아직 안 됐는지.** 미구현·임시값·후속 항목 한자리 |
| [`requirements-specification.md`](requirements-specification.md) | 요구사항 정의 |

---

## 왜 이렇게 만들었나 — 결정 기록

바꾸기 전에 읽는다. **되돌리려는 변경이 이미 검토된 것일 수 있다.**

| 결정 | 내용 |
|---|---|
| [`decisions/core-banking-merge.md`](decisions/core-banking-merge.md) | 수신·결제를 왜 한 서비스로 합쳤나 — **다시 쪼개려면 먼저 읽을 것** |
| [`decisions/agent-harness-consolidation.md`](decisions/agent-harness-consolidation.md) | 에이전트 공통 기반(harness)을 왜 모았나 |
| [`decisions/admin-role-model.md`](decisions/admin-role-model.md) | 어드민 역할 어휘를 BankRole 로 단일화한 이유 |
| [`decisions/repayment-concurrent-update.md`](decisions/repayment-concurrent-update.md) | 상환 동시성 처리 |
| [`decisions/cors-handling.md`](decisions/cors-handling.md) | CORS 를 어디서 처리하나 (현행) |
| [`decisions/security-zone-topology.md`](decisions/security-zone-topology.md) | **보안 존 구분과 게이트웨이 일원화 범위** — 남북/동서 구분, CORS 일원화 기준 |

---

## 도메인별

### 고객·인증보안계
- [`customer-auth-architecture.md`](customer-auth-architecture.md) — 인증 구조
- [`customer-service-api-spec.md`](customer-service-api-spec.md) — API
- [`customer_ddl_design.md`](customer_ddl_design.md) · [`auth_security_ddl_design.md`](auth_security_ddl_design.md) — 스키마 설계

### 수신·결제계 (core-banking)
- [`core-banking-deposit-api-spec.md`](core-banking-deposit-api-spec.md) — 수신 도메인 API
- [`core-banking-payment-api-spec.md`](core-banking-payment-api-spec.md) — 이체 도메인 API
- [`deposit-payment-api-spec.md`](deposit-payment-api-spec.md) — 두 도메인 사이의 내부 v1 API
- [`plan/transfer-step-up-auth.md`](plan/transfer-step-up-auth.md) — 이체 추가인증 설계

> `deposit-service` 와 `payment-service` 는 `core-banking` 한 서비스로 합쳐졌다
> ([결정](decisions/core-banking-merge.md)). **엔드포인트 경로는 그대로**이고 서비스
> 정체와 포트만 달라졌다(`core-banking`, 8082). 문서 이름도 그에 맞게 바꿨다 —
> 파일 이름이 없는 서비스를 가리키면 목록만 보고도 잘못 읽는다.

### 여신계
- [`loan-guide.md`](loan-guide.md) — 전반
- [`loan-service-api-spec.md`](loan-service-api-spec.md) — API
- [`loan_erd.md`](loan_erd.md) · [`loan_flows.md`](loan_flows.md) · [`loan_screens.md`](loan_screens.md) — 모델·흐름·화면
- [`loan-review-flow.md`](loan-review-flow.md) · [`loan-ai-agents-flow.md`](loan-ai-agents-flow.md) — 심사 흐름
- [`loan-payment-integration-spec.md`](loan-payment-integration-spec.md) — 결제계 연동
- [`loan-ai-test-guide.md`](loan-ai-test-guide.md) — AI 심사 테스트

### 공통
- [`data_dictionary.md`](data_dictionary.md) — **타입 규약의 정본**(금액=BIGINT 등)
- [`api-spec.md`](api-spec.md) — 통합 API 경로 인벤토리. `scripts/extract_api_spec.py` 가 소스에서 뽑아 쓴다
- [`misc-services-api-spec.md`](misc-services-api-spec.md) — 기타 서비스

---

## AI·에이전트

| 문서 | 내용 |
|---|---|
| [`ai/banking-review-llm.md`](ai/banking-review-llm.md) | 대출 심사 LLM 파이프라인 설계 |
| [`ai/MODEL_CARDS.md`](ai/MODEL_CARDS.md) | 모델 카드 |
| [`ai/PROMPT_REGISTRY.md`](ai/PROMPT_REGISTRY.md) | 프롬프트 레지스트리 |
| [`ai/DATASETS.md`](ai/DATASETS.md) | 데이터셋 |
| [`advisory-integration-guide.md`](advisory-integration-guide.md) | 자문 서비스 연동 |
| [`rag-flow-qa.md`](rag-flow-qa.md) | RAG 흐름 Q&A |

---

## 모니터링·운영

### 먼저
| 문서 | 내용 |
|---|---|
| [`monitoring/AI_OPERATIONS.md`](monitoring/AI_OPERATIONS.md) | **섀도·카나리·회귀·트레이스·자가치유** — 무엇이 있고 없는지 |
| [`monitoring/UNIFIED_MONITORING_GUIDE.md`](monitoring/UNIFIED_MONITORING_GUIDE.md) | 통합 모니터링 |
| [`monitoring/INFRA_PORTS.md`](monitoring/INFRA_PORTS.md) | 포트 배치 — **포트 충돌 의심되면 여기부터** |

### 지표 명세
| 문서 | 대상 |
|---|---|
| [`monitoring/doc-agent-metrics.md`](monitoring/doc-agent-metrics.md) | 서류 심사 에이전트 |
| [`monitoring/loan-service-metrics.md`](monitoring/loan-service-metrics.md) | 여신 AI (auto-loan-review·advisory·ai-service) |
| [`monitoring/TOOL_METRICS_GUIDE.md`](monitoring/TOOL_METRICS_GUIDE.md) | 에이전트 도구 호출 |

### 화면별 가이드
| 문서 | 대시보드 |
|---|---|
| [`monitoring/AGENT_UNIFIED_MONITORING_GUIDE.md`](monitoring/AGENT_UNIFIED_MONITORING_GUIDE.md) | 에이전트 통합 |
| [`monitoring/LLM_RAG_MONITORING_GUIDE.md`](monitoring/LLM_RAG_MONITORING_GUIDE.md) | LLM·RAG |
| [`monitoring/ML_LOAN_REVIEW_GUIDE.md`](monitoring/ML_LOAN_REVIEW_GUIDE.md) | ML 대출 심사 |
| [`monitoring/KAFKA_PAYMENT_GUIDE.md`](monitoring/KAFKA_PAYMENT_GUIDE.md) | 결제 중간망 |
| [`monitoring/CHATBOT_GUIDE.md`](monitoring/CHATBOT_GUIDE.md) | 챗봇 상담 |
| [`monitoring/INTERNET_BANKING_SERVICE_OVERVIEW_GUIDE.md`](monitoring/INTERNET_BANKING_SERVICE_OVERVIEW_GUIDE.md) | 서비스 개요 |
| [`monitoring/ADMIN_MONITORING_EMBED.md`](monitoring/ADMIN_MONITORING_EMBED.md) | 어드민 임베드 — **prod 로 옮기면 안 되는 설정 주의** |

---

## 배포·테스트

| 문서 | 내용 |
|---|---|
| [`DEPLOY.md`](DEPLOY.md) | 배포 |
| [`test-harness-conventions.md`](test-harness-conventions.md) | 테스트 하네스 규약 |
| [`plan/fds-realtime-detection.md`](plan/fds-realtime-detection.md) | 이상거래 탐지 설계 |

---

## 계획서 (`plan/`)

33건이 있다. **어느 것이 구현됐고 어느 것이 설계만인지는 상태별 색인에서 본다** —
[`plan/README.md`](plan/README.md).

| 상태 | 건수 | 읽는 법 |
|---|---:|---|
| ✅ 구현됨 | 17 | 코드가 있다. 문서는 왜 그렇게 만들었는지의 기록 |
| 🔨 진행 중 | 11 | 일부만 있다. 무엇이 남았는지 각 문서가 적는다 |
| 📐 설계만 | 3 | 코드가 없다. **읽고 붙이면 안 된다** |
| 🗑 폐기 | 2 | 전제가 무너졌다. **따르지 말 것** |

> 예전에는 이 자리가 "27건이 완료 표시를 달고 있다" 였다. 표시가 제각각이라 —
> `- [x]` · ✅ · 머리말 "상태:" 줄 · 아무것도 — 구분이 안 됐고, 문서가 코드와 어긋난
> 것도 둘 있었다. 지금은 상태 어휘가 넷뿐이고 `PlanStatusTest` 가 형식을 지킨다.

마크다운으로 없는 기획도 하나 있다 — 소득 안정화 오케스트레이터는
[`assets/portfolio/income-orchestrator.pdf`](assets/portfolio/income-orchestrator.pdf)
만 있어 검색·리뷰가 안 된다.

---

## 이 색인에 없는 것

- `wireframes/` (11) — 화면 설계 초안
- `share/`, `notes/`, `reason/`, `demo/`, `postman/` — 작업 중 메모·자료
- `assets/` — 포트폴리오·분석 참고자료 (정리 중 삭제 주의)

이들은 특정 작업에 매인 자료라 색인에 두지 않았다. 필요할 때 디렉터리에서 직접 본다.
