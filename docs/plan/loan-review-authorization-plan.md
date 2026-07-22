# 대출심사 권한 체계 도입 계획

## 목표

대출심사(loan-service) 프로세스의 직원 권한을 실무 은행 지점 기준으로 설계·구현한다.

- 소액 개인대출만 대상. 고액·기업 건, 금액 임계값 기반 본부 상신·여신위원회·이사회 결재 라인은 **범위 밖**.
- 심사/승인 2인 4-eye 분리: **부지점장(심사자) ≠ 지점장(승인자)**.
- "관여자(라인)"와 "조직 소속(지점/본사)" 두 축으로 접근을 판정한다.
- 민감정보(PII)는 need-to-know 기준으로 차등 노출(마스킹)한다.
- 이상거래는 본사로 상신(escalate)하고, 본사는 상신된 건만 접근한다.

## 진행 규칙

- 작업을 시작하거나 완료할 때마다 이 파일의 진행 상태를 업데이트한다.
- 코드 수정 전에는 어떤 파일을 고칠지 먼저 기록한다.
- 파일 5개 이상 변경 또는 설계 변경 전에는 계획을 보여주고 승인받는다.
- `feat`와 `test`는 별도 커밋. 한 커밋에 섞지 않는다.
- 다단계 작업은 한 단계 끝낼 때마다 커밋+보고하고 멈춘다.
- deposit-service는 다른 팀 소유이므로 건드리지 않는다. (기존 `EmployeeRole` enum은 미사용 죽은 코드이며 참조하지 않는다.)

---

## 1. 핵심 결정 사항 (확정)

| # | 항목 | 결정 |
| --- | --- | --- |
| 1 | 권한 부여 방식 | 라인 역할은 **관계 기반(assignment)**. `loan` row의 `reviewer_id`/`approver_id`에 직원ID가 박히면 그 건의 역할을 가진 것으로 본다. 사람에 역할을 영구 부착하지 않는다(직원이 고객이 될 수도 있으므로). |
| 2 | 소액 라인 구성 | 텔러(담당/접수) → 부지점장(심사) → 지점장(승인). 같은 지점 내 3인. |
| 3 | 4-eye | 심사자 ≠ 승인자 강제 (현행 `LOAN_196` 확장). |
| 4 | 승인자 vs 상급자 | **구별한다.** 승인자=이 건의 결재권자(워크플로 역할), 상급자=조직 계층상 위(관리·조회 권한, 이 건 결재자가 아닐 수 있음). |
| 5 | 전담 심사직원 | 소액엔 별도 전담 인간 심사역 없음(시스템 스코어링 + 부지점장 검토). 본부 전담 심사역은 고액 영역 → 범위 밖. |
| 6 | 정보 노출 | 관여자=풀 PII, 그 외=마스킹/요약. need-to-know 기준. |
| 7 | 이상거래 | 본사로 상신(`ESCALATED`). 본사는 상신 건만 접근. |
| 8 | CEO/임원 결재권 | **대출 결재 role을 부여하지 않는다.** 버튼을 안 주는 것이 통제. 권력 남용은 독립 감사·불변 로그로 탐지. |
| 9 | 조직 모델 | 직원 1명 = 지점 1곳(many-to-one). 중간(다대다) 테이블 불필요. |
| 10 | 지점 스코핑 | **도입한다** (같은 지점/타 지점/지점장 전체조회). 단 무거운 테이블 대신 **JWT claim(`branch`,`grade`) + 직원 시드**로 가볍게 시작. |
| 11 | 직원 모델 출처 | customer-service의 **Party 모델 재사용**: 직원 = `party` + `party_role=EMPLOYEE`. 지점·직급만 신규. loan-service는 직접 조인 불가하므로 권위 정보는 **토큰 claim**으로 전달. |
| 12 | 감사로그 | **신규 `access_audit_log` 테이블.** `status_history`(상태 전이)와 분리. 조회·unmask·break-glass 같은 접근 이벤트 전용. |
| 13 | break-glass 승인 | MVP: **사유 필수 → 자동 허용 → 불변 로그 → 컴플라이언스 사후 점검.** 민감 조합(본인 결재건 우회 등)만 사전 차단. |

---

## 2. 현황 및 문제점

현재 직원 역할 권한 체계는 "반만 배선된" 상태다.

| 항목 | 상태 | 위치 |
| --- | --- | --- |
| `SecurityConfig`의 역할 요구 | 존재 (`ROLE_OPS`/`ROLE_SENIOR_REVIEWER`/`ROLE_INTERNAL`) | `loan-service/.../config/SecurityConfig.java:58-83` |
| API Gateway의 `X-User-Role` 헤더 주입 | **미구현** (`X-Customer-Id`/`X-Customer-Email`만 주입) | `api-gateway/.../filter/JwtAuthenticationFilter.java:66-71` |
| 직원 로그인/직원 역할 발급 | **미구현** (고객 로그인은 `ROLE_CUSTOMER`만) | `customer-service/.../login/service/LoginService.java:79-80` |
| `GatewayHeaderAuthFilter` 멀티롤 | **단일 값만** 처리 | `loan-service/.../security/GatewayHeaderAuthFilter.java:44-49` |
| 본심사 조회 스코프 | **없음** — 인증만 되면 전체 풀 노출 | `loan-service/.../review/service/LoanReviewService.java:270-276` |
| 신청 조회 스코프 | 소유권 기반 존재(`isOps`) — 서비스 간 불일치 | `loan-service/.../application/controller/LoanApplicationController.java:92-96` |
| 지점/조직 테이블 | **없음** (전 서비스 마이그레이션에 branch/org 테이블 0개) | — |
| 감사로그 토대 | `status_history` 존재 (활용 가능) | loan-service |

**결론**: `ROLE_OPS`/`ROLE_SENIOR_REVIEWER`가 걸린 엔드포인트는 현재 아무도 통과 못 하고, 나머지 `authenticated` 엔드포인트는 권한 구분 없이 전부 통과한다.

---

## 3. 권한 모델 설계

### 3.1 두 개의 축

| 축 | 성격 | 저장 위치 |
| --- | --- | --- |
| **라인 역할** (담당/심사/승인) | 그 대출 건에 대한 **동적 배정** | loan row: `owner_id`(신규), `reviewer_id`, `approver_id` |
| **직급·소속** (텔러/부지점장/지점장/본사) | 직원의 **정적 속성** | 신규 직원 디렉터리: `employee(employee_id, branch_id, grade)` |

접근 판정식:

```
볼 수 있나? =
   (내가 이 건의 라인에 있다)              // owner/reviewer/approver == 나  → 풀 또는 마스킹
 OR (내 지점 == 이 건의 지점 AND 직급 충족) // 같은 지점 상급자/지점장      → 마스킹
 OR (본사 소속 AND 이 건이 ESCALATED)      // 이상거래 상신 건만            → 마스킹(조사시 unmask)
 OR break-glass(사유 + 승인 + 로그)         // 비상 예외                    → 요약/시간제한
```

### 3.2 역할(직급) 정의

라인 역할(동적)과 별개인 **정적 직급/직무**:

| 코드 | 의미 | 비고 |
| --- | --- | --- |
| `ROLE_CUSTOMER` | 고객 | 기존. 본인 신청만 |
| `ROLE_TELLER` | 텔러(담당/접수) | 라인에서 담당자로 배정 |
| `ROLE_DEPUTY_MANAGER` | 부지점장(심사자) | 라인에서 심사자로 배정 |
| `ROLE_BRANCH_MANAGER` | 지점장(승인자/결재권자) | 라인에서 승인자로 배정. 지점 전체 조회 |
| `ROLE_HQ_REVIEWER` | 본사 담당자 | 상신된 이상거래 건만 |
| `ROLE_COMPLIANCE` | 감사/컴플라이언스 | read-only, break-glass 승인·점검 |
| `ROLE_OPS` | 운영(배치) | 기존. 내부 배치 엔드포인트 |
| `ROLE_INTERNAL` | 서비스 간 호출 | 기존. `X-Internal-Token` |
| `ROLE_ADMIN` | IT 운영 | 계정/설정 관리. **대출 결재권 없음** |

> 직급은 접근 "자격"을 정하고, 실제 결재 권한은 그 건의 라인 배정(`reviewer_id`/`approver_id`)으로 확정된다. 지점장 직급이라도 그 건의 `approver_id`가 본인이어야 결재한다.

### 3.3 정보 노출 매트릭스

| 주체 | 조회 범위 | PII |
| --- | --- | --- |
| 담당자/심사자 (그 건의 라인) | 그 건 **전체** | **풀** |
| 승인자/결재권자 (그 건의 라인) | 그 건 **전체** | **마스킹** (주민번호 뒷자리 등) |
| 같은 지점 무관 텔러 | 기본 **차단** → break-glass 시 **요약만** | 마스킹 |
| 지점장 (조직 상급자) | **해당 지점** 건 | **마스킹** |
| 타 지점 직원 | **차단** (break-glass 예외만) | — |
| 본사 담당자 | **ESCALATED 건만** 전체 | **마스킹** (조사 목적 시 unmask + 사유 + 로그) |
| 감사/컴플라이언스 | 전체 read-only | 마스킹 (필요시 unmask + 로그) |

### 3.4 4-eye 정책

- 심사자(`reviewer_id`) ≠ 승인자(`approver_id`) 강제. 동일인 시 거부.
- 부지점장이 심사, 지점장이 승인하는 흐름을 기본으로 하되, 시스템은 "두 ID가 달라야 한다"는 불변식만 강제한다.
- 현행 `LOAN_196`(승인자=심사원 동일) / `LOAN_200`(편향 본인 우회) 규칙을 유지·확장.

### 3.5 이상거래 본사 상신 (Escalation)

- `loan_review`에 상신 상태/플래그 추가 (`ESCALATED_TO_HQ` 또는 `escalated_at` 컬럼).
- 현행 편향 BLOCKED → `bias-override` 흐름이 에스컬레이션의 축소판 → 여기에 연결.
- `ROLE_HQ_REVIEWER`는 `escalated = true`인 건만 조회 가능. 정상 건은 미노출(상태 기반 ABAC).

### 3.6 Break-glass (비상 접근)

타지점/무관 건 예외 접근 프로세스 (MVP 확정안):

1. 접근 시도 → 기본 거부
2. **사유 필수 입력**
3. **자동 허용** (사전 승인 워크플로 없음 — 긴급 대응 보장)
4. **시간·범위 제한** 부여 (요약만, 한시적)
5. **불변 감사로그(`access_audit_log`)** 기록 → **컴플라이언스(`ROLE_COMPLIANCE`) 사후 정기 점검**
6. **민감 조합은 사전 차단**: 본인이 라인인 건의 4-eye 우회, 본인 결재건 자기 우회 등

> 더 엄격히 갈 경우: 같은 지점 무관 건은 지점장 사전승인, 타 지점·본사 건은 컴플라이언스 사전승인으로 2단계화. (MVP 이후 선택)

### 3.7 감사 / 부정 통제

- 모든 조회·결재·unmask·break-glass를 **append-only 감사로그**에 기록 (`status_history` 확장 또는 신규 `access_audit_log`).
- 단독 결재 불가(4-eye)로 최고권한자의 단독 부정 실행을 차단.
- 결재 라인과 분리된 `ROLE_COMPLIANCE`가 이상 패턴 점검.

---

## 4. 데이터 모델 변경

| 대상 | 변경 | 비고 |
| --- | --- | --- |
| `branch` (신규) | `branch_id`, `branch_name`, ... | 지점 마스터. MVP는 시드로 시작 |
| 직원 = `party` + `party_role=EMPLOYEE` | 지점(`branch_id`)·직급(`grade`) 속성 신규 | customer-service Party 모델 재사용. 별도 employee 테이블 신설 안 함 |
| JWT claim | `branch`, `grade`, 역할 | 직원 로그인 시 토큰에 주입 → loan-service는 `X-User-Branch`/`X-User-Role` 헤더로 수신 |
| 직원 속성 저장 | **config 시드(초경량)** 확정 | `customerId → {branch, grade, roles}` 매핑을 customer-service 설정으로. 스키마 변경 없음 |
| `loan_application` | `branch_id` 추가 | 접수 지점 |
| `loan_review` | `owner_id`(담당자) 추가, `escalated_at`/상태 추가 | 라인·상신 표현 |
| `access_audit_log` (신규) | 조회/unmask/break-glass 이력 | append-only. `status_history`와 분리 |

> `status_history`(loan-service)는 상태 전이(before→after) 전용 로그이므로 조회/열람 이벤트를 담기엔 부적합 → 접근 감사는 **신규 `access_audit_log`** 로 분리한다.

---

## 5. 코드 적용 지점

| 영역 | 변경 |
| --- | --- |
| API Gateway | `JwtAuthenticationFilter`에서 `claims.roles` → `X-User-Role` 헤더 주입 (콤마 구분 멀티롤) |
| `GatewayHeaderAuthFilter` | `X-User-Role` 멀티롤 파싱 → 복수 `GrantedAuthority` |
| 직원 인증 | 직원 로그인 + 직급 기반 JWT 발급 경로 추가 |
| `SecurityConfig` | 신규 역할 반영, 조회 엔드포인트 정책 정비 |
| `LoanReviewService.get` / 응답 DTO | 라인·지점·상신 기반 스코프 판정 + PII 마스킹 분기 |
| 신규 PII 마스킹 유틸 | 응답 직렬화 시 직급/관여 여부에 따라 마스킹 |
| break-glass / 감사로그 | 접근 기록·예외 승인 엔드포인트 |

---

## 6. 단계별 진행 계획

| 단계 | 상태 | 내용 |
| --- | --- | --- |
| 0 | 완료 | 현황 조사 및 권한 모델 설계 합의 (본 문서) |
| 1 | 완료 | `LoanRole` enum 9종 신설, `SecurityConfig` 권한 매트릭스 코드화(`SENIOR_REVIEWER`→`HQ_REVIEWER`). 직원 디렉터리·로그인 처리 = **customer-service** (config 시드 방식, §9) |
| 2 | 완료 | `branch` 테이블(V28) + `loan_application.branch_id`(V29) + `loan_review.owner_id`/`escalated_at`(V30) + `access_audit_log`(V31). `LoanReview.escalateToHq()` 메서드 추가 |
| 3 | 완료 | `JwtClaims` branch/grade 필드 추가, `JwtProvider` 직원용 오버로드, Gateway `X-User-Id/Role/Branch/Grade` 헤더 주입, `EmployeeDirectoryProperties` config 시드(직원 3명), `LoginService` 직원 분기, `GatewayHeaderAuthFilter` 멀티롤+`GatewayAuthDetails` |
| 4 | 완료 | `LoanActorContext` 신설, `SecurityConfig` 역할 매트릭스(심사/확정/acknowledge-bias/approver-approve/auto-decide), `LoanReviewService.get()` 4조건 스코프 판정 + `LOAN_202` |
| 5 | 완료 | PII 마스킹 유틸 + 응답 DTO 차등 노출 |
| 6 | 완료 | 이상거래 상신(`ESCALATED`) + 본사 담당자 접근 |
| 7 | 진행 예정 | break-glass 예외 접근 + 불변 감사로그 |
| 8 | 진행 예정 | 통합 테스트 (역할별 접근 시나리오, 4-eye, 마스킹, 상신) |

---

## 7. 범위 밖 (Out of Scope)

- 고액·기업 대출, 금액 임계값 기반 본부 상신·여신위원회·이사회 결재.
- CEO/임원의 대출 결재 권한 (의도적으로 부여하지 않음).
- deposit-service 및 그 `EmployeeRole` enum.
- 직원의 다지점 겸직(다대다) — 필요 시 추후 중간테이블 도입.

---

## 8. PII 마스킹 대상 필드 (스키마 확인 기준)

확인된 PII 보유 위치:

- **loan-service**
  - `loan_identity_verification`: `mobile_no_enc/masked`(전화), `ci_hash`·`di_hash`, `external_tx_no`
  - `loan_document`: `doc_url`·`doc_name` — **제출 서류 원본 파일**(소득증빙·재직증명·주민등록등본·통장사본·사업자등록증 등. `doc_type_cd`는 자유문자)
  - `loan_document_ocr`: `extracted_text`·`extracted_fields` — **OCR 원문(주민번호·주소 포함 가능) = 최고 민감**
  - `repayment_account`: `account_no_enc/masked`, `holder_name_masked`
  - `collateral.col_address`(담보 주소)
  - `loan_application.estimated_income_amt`, `dsr_calculation.annual_income_amt`(소득=결정정보)
- **customer-service (party, claim 경유)**
  - `party_person.rrn_encrypted`(주민번호=최고 민감), `ci_value`, `workplace_name`, `occupation`, `annual_income_amount`
  - `customer.email`·`phone`·`address`·`zip_code`

노출 3단계:

| 대상 | 주민번호 | 전화/계좌/주소 | 소득/DSR(결정정보) | 서류원본·OCR원문 |
| --- | --- | --- | --- | --- |
| 담당/심사(라인) | 풀 | 풀 | 풀 | 열람 가능 |
| 승인자·지점장·본사 | 마스킹 | 마스킹 | 노출(결재 필요) | 메타만(파일명·종류) |
| 무관 텔러(break-glass 요약) | 제거 | 제거 | 금액대만 | 미노출 |

> 원칙: 결정정보(소득·DSR·신용)는 승인자에 노출(부실심사 방지), 직접식별자(주민·연락처·서류 원본)는 라인 밖이면 가린다. 서류 원본/OCR 텍스트가 최대 노출 위험점.

## 9. customer-service 변경 요청 (타 서비스 핸드오프)

직원 속성 = **config 시드(초경량)** 방식 기준. customer-service 담당자에게 요청할 변경분.

### 9.1 요청 사항

1. **직원 레지스트리 config 추가** — `application.yml`에 `customerId → {branch, grade, roles}` 매핑.
   ```yaml
   employee-directory:
     enabled: true
     employees:
       - customer-id: 1001
         branch: "0001"
         grade: BRANCH_MANAGER
         roles: [ROLE_BRANCH_MANAGER]
       - customer-id: 1002
         branch: "0001"
         grade: DEPUTY_MANAGER
         roles: [ROLE_DEPUTY_MANAGER]
   ```
   이를 읽는 `@ConfigurationProperties` 빈 1개 신설.

2. **`LoginService.login()` / `refresh()` 분기** ([현재 위치](services/customer-service/.../login/service/LoginService.java#L79)):
   - 로그인한 `customerId`가 레지스트리에 있으면 → `roles = 직원 roles`, JWT에 `branch`/`grade` claim 주입.
   - 없으면 → 기존대로 `["ROLE_CUSTOMER"]`, branch/grade 없음.

3. **직원 로그인 계정 시드** — 각 직원은 로그인하려면 `customer` + `credential` 행이 필요(`credential.customer_id` FK). 직원용 계정(loginId/password) 시드 또는 발급 방법 합의.

### 9.2 의존성 — `common` 모듈 (소유자 확인 필요)

- `JwtProvider.generateAccessToken`에 `branch`/`grade` claim 추가, `JwtClaims` record에 두 필드 추가 ([common/.../jwt/JwtProvider.java](common/src/main/java/com/bank/common/security/jwt/JwtProvider.java)).
- `common`은 전 서비스 공용이라 변경 시 영향 범위 확인 필요. (대안: branch/grade를 roles claim에 규약 인코딩 — 비권장)

### 9.3 우리(loan-service 측)가 처리할 부분 — 요청 대상 아님

- api-gateway `X-User-Role/Branch/Grade` 헤더 주입.
- loan-service `GatewayHeaderAuthFilter` 멀티롤 + 스코프 판정 + 마스킹.

## 10. 미결정 / 오픈 이슈

- 직원 로그인(직급·지점 claim 발급)을 어느 서비스에서 처리할지 — customer-service Party 재사용 vs 신규 인증 경로.
- `branch` 시드 데이터 범위/형식.
- break-glass 시간·범위 제한 구체값(예: 요약만 N시간).
- 마스킹 적용 위치(응답 DTO 직렬화 단계 vs 서비스 계층).
