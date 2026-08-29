# A2 — Consultation DB 직접 접근 제거

> Status: ✅ 완료 (Phase 0–3, 2026-08-29)
>
> 범위: `agents/consultation` 런타임 코드의 core-banking `deposit` 스키마 직접 읽기 제거

## 결정

A2를 반나절짜리 단순 치환 작업으로 진행하지 않는다. 10개 런타임 파일에서
core-banking 테이블을 참조하며, `FROM`/`JOIN` 정적 검색 기준 77회가 확인됐다.
읽기 API는 권한과 조회 감사를 함께 제공해야 하므로 A2 구현은 B1 감사 API 설계와
하나의 세로 작업으로 진행한다.

`CONSULTATION_DATABASE_URL`과 DB 엔진은 Phase 2가 끝난 뒤에만 제거한다. 이 문서는
그 전까지 consultation 자체 상태·감사 저장소 접근은 범위 밖으로 명확히 구분한다.

## 완료 상태와 최종 경계

현재는 아래 경로가 존재한다.

```text
AI 상담 → consultation SQLAlchemy → core-banking deposit DB
```

최종 완료 조건은 SQL 문자열을 지우는 데 있지 않다. 아래 경로에서 인증·인가와
조회 감사가 보장되어야 한다.

```text
AI 상담
  → Internal API (X-Employee-Id, X-Access-Reason)
  → 인증·인가
  → access audit
  → Core Banking
  → DB
```

## Phase 0 — Inventory

- [x] 전체 DB 접근 지점 목록화
- [x] 접근 유형 분류
- [x] 기존 internal API 매핑
- [x] 신규 API 필요 여부 확인
- [x] 감사 대상 조회 식별
- [x] 마이그레이션 순서 결정

### 조사 방법과 범위

다음 명령으로 런타임의 core-banking 테이블 참조를 세었다.

```powershell
rg -i "\b(from|join)\s+(deposit_|banking_deposit_|employees\b)" agents/consultation/app --glob '*.py'
```

`deposit_*`, `banking_deposit_*`, `employees`를 대상으로 했으며, 모델 필드명·문서·
테스트 fixture는 수에서 제외했다. `FROM`/`JOIN` 한 줄을 한 번으로 계산하므로 조인
테이블은 여러 번 집계된다. 따라서 **10개 파일, 77개 테이블 참조(60개 이상)**는
마이그레이션 규모를 판단하는 보수적 인벤토리 수치이며 API 호출 수는 아니다.

배포 compose의 `CONSULTATION_DATABASE_URL`은 루트 `docker-compose.yml`에서
`core-banking-db-a`의 `deposit` search path를 직접 가리킨다. 반면
`agents/consultation/docker-compose.yml`은 로컬 데모용 별도 `postgres`를 가리킨다.
Phase 3에서는 두 구성을 혼동하지 않고, 배포 경로의 자격 증명과 네트워크 연결을 제거한다.

| 파일 | 테이블 참조 | 주된 읽기 |
|---|---:|---|
| `app/features/base.py` | 7 | 계좌·계약·거래·직원 활성 상태·금리 |
| `app/main.py` | 1 | 판매 중인 예적금 상품 |
| `app/features/maturity_agent.py` | 4 | 계약·상품·잔액 |
| `app/features/product.py` | 13 | 상품·대상 그룹·금리·약관 |
| `app/features/product_compare.py` | 3 | 상품 비교 |
| `app/features/savings_goal.py` | 4 | 목표 기반 상품 추천 |
| `app/features/spending_pattern_agent.py` | 2 | 고객 계좌와 거래 패턴 |
| `app/features/staff.py` | 4 | 직원의 고객 거래 조회 |
| `app/features/user_finance.py` | 8 | 이자·거래·추천 상품 |
| `app/services.py` | 31 | 계좌·거래·계약·상품·이체 전 사전 조회 |
| **합계** | **77** | |

### 접근 유형과 API 매핑

| 유형 | 현재 직접 조회 대상 | 기존 API 상태 | Phase 1 판단 |
|---|---|---|---|
| 고객 계좌·잔액 | `deposit_accounts` | `/accounts`, `/accounts/{id}`, `/v1/balances/{accountNo}`는 존재 | 상담용 internal account summary 필요. 고객 소유권 확인 대신 호출 actor와 대상 고객을 검증해야 함 |
| 고객 거래·현금흐름 | `deposit_transactions`, `deposit_interest_history` | `/transactions`와 일부 interest endpoint 존재 | 기간·집계·최소 필드 응답을 가진 internal statement/cash-flow API 필요 |
| 계약·만기 | `deposit_contracts` | `/contracts`, 계약별 interest/terms endpoint 존재 | 계약 목록·만기 요약 internal API 필요 |
| 상품·금리·약관·대상 | `deposit_banking_products`, interest rate, special term, target group | `/products/**` 계열 존재 | 공개 상품 조회와 내부 추천 DTO를 분리해 필요한 필드만 제공 |
| 이체 전 계좌 확인 | 출금 계좌·수취 계좌 상태/잔액 | 이체 쓰기는 이미 `/transactions/transfer` 경유 | 별도 사전 조회를 없애고 transfer API가 원자적으로 검증하거나 전용 내부 검증 API를 제공 |
| 직원 활성/권한 | `employees` | core-banking controller에서 확인하지 못함 | **신규 employee authorization API 필요**. 직원 원본의 소유 서비스도 함께 확정 |

기존 controller의 `GET /accounts`, `GET /transactions`, `GET /contracts`, `GET /products/**`는
확인됐다. 다만 현 계약은 고객 헤더 기반 소유권 검증 또는 일반 조회이므로, 상담 서비스의
직원 대리 열람에 요구되는 `X-Employee-Id`, 접근 사유, 감사 기록을 보장하는 internal API로
그대로 재사용할 수 없다.

### 감사 대상

다음은 모두 고객 금융정보 또는 직원 권한 판단이므로 성공·거절 모두 감사 대상이다.

- 계좌/잔액, 계약/만기, 거래/현금흐름, 이자 내역 조회
- 직원 대리 조회 권한 확인
- 수취 계좌 존재·상태 확인과 이체 전 검증

감사 이벤트 최소 계약은 `actorEmployeeId`, `targetCustomerId` 또는 `accountId`,
`accessReason`, `operation`, `result`, `traceId`, `occurredAt`이다. 요청 본문이나 응답의
민감 정보 전체를 감사 로그에 복제하지 않는다.

## Phase 1 — Read API (B1과 함께 설계·구현)

- [ ] account 조회 API
- [ ] balance 조회 API
- [x] transaction 조회 API
- [x] employee authorization API
- [x] actor/reason/audit 공통 계약과 거절 감사
- [x] API 계약 테스트와 감사 저장 검증

모든 internal read 요청은 `X-Employee-Id`, `X-Access-Reason`, `X-Trace-Id`를 전달한다.
직원 조회가 아닌 본인 상담 흐름도 actor 종류를 구분해 같은 감사 계약으로 기록한다.

## Phase 2 — Migration

- [x] feature별 DB 접근 제거
- [x] X-Employee-Id 전파 (`app/access_context.py`, 요청 진입점 한 곳에서 담는다)
- [x] access reason 전파
- [x] DB engine 제거 — **수신 데이터에 한해서다.** 상담 자기 표에는 엔진을 계속 쓴다
- [x] SQL fixture 중심 테스트를 API stub/contract fixture로 전환

권장 순서는 공개 상품 → 고객 계약/만기 → 계좌/잔액 → 거래/현금흐름 → 직원 대리 조회와
이체 사전 검증이다. 마지막 두 항목은 권한·감사 의존성이 있으므로 B1 계약이 준비된 뒤
옮긴다. 한 기능 안의 읽기와 해당 테스트를 같은 변경으로 옮겨, 절반만 이전된 깨진 트리를
남기지 않는다.

## Phase 3 — Enforcement

- [x] `CONSULTATION_DATABASE_URL` 이 원장 DB 를 가리키지 않게 한다
- [x] consultation → core-banking DB 네트워크 차단
- [x] 직접 접근 0건을 강제하는 테스트
- [x] compose/배포 구성에서 core-banking DB 자격 증명 제거

### 어떻게 막았나 — 세 겹

`CONSULTATION_DATABASE_URL` 을 **없애지 않고 옮겼다.** 상담은 자기 자료(상담 이력·
시나리오·메시지)를 담을 곳이 여전히 필요하다. 없애야 할 것은 변수가 아니라 **그것이
원장 DB 를 가리키는 것**이었다.

| 겹 | 무엇을 막나 | 어디 |
|---|---|---|
| 코드 | 표 이름이 SQL 에 다시 나타나는 것 | `test_no_direct_core_banking_access.py` |
| 설정 | 접속 문자열이 원장 DB 를 가리키는 것 | 〃 (루트 compose 를 읽는다) |
| 망 | 위 둘을 되돌려도 **닿지 않는 것** | `consultation-data`(internal) + `core-banking-db-a` 를 `agent-net` 에서 제거 |

앞의 둘만으로는 "코드에서는 지웠는데 길은 열려 있는" 상태가 남는다. 실제 통제는 망이고,
앞의 둘은 조기 경보다. 실물로 확인했다 — 상담 컨테이너에서 `core-banking-db-a` 는
이름 조회부터 실패한다(`gaierror`).

`core-banking-db-a` 가 `agent-net` 에 있던 유일한 이유가 상담이었다. 상담이 빠지면서
그 망 소속도 함께 없어졌다.

### 전용 DB 를 새로 세운 이유

상담 자기 표 14개를 담을 `consultation-db` 를 만들었다. 같은 인스턴스의 별도 스키마로
가면 싸지만, 인스턴스를 공유하므로 **망 차단을 할 수 없다** — 위 표의 세 번째 겹이
통째로 빠진다. 그것이 이 작업의 핵심이라 전용 컨테이너를 골랐다.

`agent-net` 에 얹지 않고 전용망(`consultation-data`)을 둔 것은, 얹으면 사이드카 넷이
상담 이력에 닿기 때문이다. 상담 이력은 상담 것이지 공용이 아니다.

## 범위 밖으로 남긴 것 — 직원 자격증명이 두 곳에 있다

`app/main.py` 의 `agent_login` 이 상담 자체 `employees` 표에서 `password_hash` 를 읽어
bcrypt 로 검증한다. **직원 자격증명의 정본이 customer-service 와 상담 두 곳**이고,
상담 쪽은 customer-service 의 상태 변화(퇴직·정지)를 모른다.

A2 의 목표는 "상담이 **수신 원장**에 붙지 못하게" 이고, `employees` 는 상담 소유 표라
전용 DB 로 함께 옮기는 것으로 A2 는 닫힌다. 다만 이중 정본 자체는 남는 문제다.

없애려면 어드민 콘솔의 로그인 경로를 함께 고쳐야 한다 —
`web/app/(admin)/admin/login/page.tsx` 가 이 API 로 로그인하고 **mock 토큰**을 만든다.
즉 상담의 `employees` 제거는 "어드민 콘솔을 실제 인증에 연결" 과 같은 작업이며,
A2 와 크기·성격이 다르다. **별도 항목으로 남긴다.**

## 보류 근거

기존 테스트 하네스는 직접 DB fixture에 의존한다. 부분 치환 상태에서는 테스트와 런타임의
데이터 계약이 달라질 수 있으므로, A2 구현을 지금 시작하지 않는다. Phase 1 API 계약과
테스트 대체 전략이 확정되기 전에는 A2-0 결과만 유지한다.
