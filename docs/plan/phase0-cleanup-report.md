# Phase 0 — 죽은 코드·스키마 정리 리포트

<!-- plan-status -->
> **상태: ✅ 구현됨** — 계획이 아니라 **결과 리포트**다
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).


> 작성: 2026-08-04
> 범위: 코드 참조가 0인 자산 제거 + 방치 자산 복구. **구조 변경 없음.**

## 배경

이 레포는 팀 프로젝트로 시작해 도메인별(여신·수신·결제·고객)로 담당자를 나눠 개발했다.
그 과정에서 (1) 통합 모델을 설계만 하고 코드는 이관하지 않은 스키마, (2) 소비자 없이
배포만 되던 서비스, (3) 설정 오류로 꺼둔 채 잊힌 대시보드가 남았다.

Phase 0 은 **설계 판단이 필요 없는 것**, 즉 "참조가 0이라 지워도 동작이 바뀌지 않는 것"만
정리한다. 서비스 경계 재설계(core-banking 병합)는 Phase 1 에서 별도로 다룬다.

---

## 0-1. deposit-db 유령 테이블 21개 제거

`V5__full_erd_schema.sql` 이 **전사 공통 ERD 전체**를 deposit-db 에 생성했으나,
deposit-service 코드는 V1 세대 스키마(`deposit_*`, `banking_deposit_*`)만 사용한다.
`V16` 에서 payment 소속 한글 테이블 6개만 정리됐고 나머지 타 도메인 테이블은 남아 있었다.

**미사용 근거 (전수 확인)**

| 확인 항목 | 결과 |
|---|---|
| JPA `@Table` 매핑 | 20개 전부 V1 세대. 아래 21개는 **0건** |
| MyBatis XML · 네이티브 쿼리 | 0건 |
| `application.yml` 참조 | 0건 |

**제거 대상** (`V18__drop_v5_cross_domain_ghost_tables.sql`)

| 도메인 | 테이블 |
|---|---|
| 여신 (loan-service 소속) | `loan_product` `loan_application` `loan_account` `loan_contract` `loan_review` `loan_execution` `loan_repayment` |
| 고객·당사자 (customer-service 소속) | `party` `party_person` `party_organization` `party_role` `party_relation` `customer` |
| 미구현 전사 공통 모델 | `common_product` `common_account` `common_contract` `common_transaction` `common_terms_template` `common_terms_consent` `terms_target_map` |
| 수신 구세대 | `deposit_product` (단수형) |

**원칙**: 각 도메인 테이블은 소유 서비스의 DB 에만 존재한다.

## 0-2. payment ↔ deposit API 합의서 정정

`services/payment-service/CLAUDE.md` 용어집이 deposit 의 거래내역 테이블을
`common_transaction` 으로 기재하고 있었다. 이는 **미구현 통합모델 테이블명**이며
실제 deposit-service 는 `deposit_transactions` 를 사용한다. 합의서가 유령 테이블
기준으로 작성된 상태였다. → 실제 테이블명으로 정정.

## 0-3. master-service 제거

공통코드(`code_master`) CRUD 전용 서비스. **소비자가 0** 이었다.

| 확인 대상 | 결과 |
|---|---|
| 백엔드 서비스의 `CodeService`·`CodeDto`·`@ValidCode` 사용 | **0건** |
| 프론트 `web/lib/master-api.ts` 를 import 하는 파일 | **0건** |
| 실제 코드 검증에 사용 | 없음 (검증 정본은 도메인 상수) |

**제거 범위**

- `services/master-service/` (Java 6파일 + Dockerfile + build.gradle)
- `settings.gradle` 모듈 등록
- `services/api-gateway` 의 `/api/codes/**` 라우트
- `docker-compose.yml` · `docker-compose.server.yml` · `infra/docker/docker-compose.prod.yml`
  — `master-service` · `master-db` · `master-db-data` 볼륨 · `MASTER_SERVICE_HOST` · `depends_on`
- `.github/workflows/deploy-master-service.yml`, `deploy.yml` 의 `ALL_SERVICES`
- `web/lib/master-api.ts`, `NEXT_PUBLIC_MASTER_API_URL`
- `.env.sample` · `.env.prod.sample` 의 `MASTER_*`

**보류**: `common/src/main/java/com/bank/common/code/` (7파일)는 이번 범위에서 제외.
`common/**` 변경은 5개 서비스의 프로덕션 재배포를 트리거하므로 별도 판단이 필요하다.

## 0-4. 방치 대시보드 2개 — 삭제가 아니라 복구

`infra/grafana/disabled_dashboards/` 의 `auto-loan-review-llm.json`, `llm-overview.json`.
당초 "통합 후 남은 잔재"로 보고 삭제를 검토했으나, **활성 대시보드가 다루지 않는
메트릭을 담고 있었다.**

| 메트릭 | `agent-unified.json` | disabled 2개 |
|---|---|---|
| `ai_agent_runs/latency/fallback/disagreement/hard_fail` | ✅ | ✅ |
| **`ai_agent_cost_usd_total`** (비용) | ❌ | ✅ |
| **`ai_agent_tokens_input/output_total`** (토큰) | ❌ | ✅ |
| **`ai_agent_rpm/rpd_remaining`** (레이트 한도) | ❌ | ✅ |
| **`rag_search_latency_seconds`** (RAG 검색) | ❌ | ✅ |
| **`ai_agent_llm_latency_seconds`** (LLM 지연) | ❌ | ✅ |

**꺼져 있던 원인**: datasource uid 가 `bfmta2uf2mebka`(로컬 Grafana 자동생성 uid)로
하드코딩되어 프로비저닝된 `prometheus` datasource 와 매칭되지 않았다. 패널이 전부
"datasource not found" 로 뜨니 폴더째 비활성화한 것으로 보인다.

**조치**: uid 를 `prometheus` 로 교체하고 `provisioning/dashboards/` 로 이동.
`disabled_dashboards/` 디렉토리 제거.

## 0-5. 수신 상품 테이블 5종 — 실사 결과 (삭제 없음)

| 테이블 | 엔티티 | 참조 |
|---|---|---|
| `banking_deposit_products` | `DepositProduct` | 5파일 |
| `deposit_banking_products` | `Product` | 9파일 |
| `deposit_savings_products` | `SavingsProduct` | 5파일 |
| `deposit_subscription_products` | `SubscriptionProduct` | 5파일 |
| `deposit_target_groups` | `TargetGroup` | 9파일 |

**전부 사용 중이므로 Phase 0 삭제 대상 아님.**

다만 실무 은행 상품 모델은 통상 `상품 1테이블 + 상품유형 구분컬럼 + 유형별 속성테이블`
구조를 쓴다. 현재는 유형별로 최상위 테이블이 분리돼 있어 상품 공통 속성이 중복된다.
이는 참조가 살아있는 **설계 사안**이므로 Phase 2(수신 상품 계층 정리)에서 다룬다.

---

## Phase 0 이 건드리지 않은 것

- 서비스 경계 (payment / deposit 병합 → **Phase 1**)
- 상품 모델 정규화 (→ **Phase 2**)
- `common/code` 모듈 (프로덕션 재배포 범위 이슈로 보류)
- loan-service (동기 결합 0. `@FeignClient`·`RestTemplate`·`WebClient` 미사용,
  payment 와는 Kafka 이벤트로만 연결되어 리팩토링 영향 없음)
