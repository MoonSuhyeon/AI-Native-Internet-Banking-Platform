# 대출·AI 서비스 로컬 테스트 가이드 (플로우 기반)

`start.ps1` 로 로컬 스택을 띄운 뒤 **loan / auto-loan-review / review-ai-gateway / doc-agent** 네 서비스의
기능을 플로우 순서대로 검증하기 위한 문서다.

> 정확한 요청 바디는 각 서비스 Swagger(`http://localhost:<port>/swagger-ui.html`, loan 은 8083)에서 확인할 수 있다.
> 본 문서는 **엔드포인트 / 메서드 / 필요 권한 / 사전조건 / 호출 순서**를 검증된 코드 기준으로 정리한다.

---

## 0. 기동 & 헬스 체크

| 서비스 | host 포트 | health | Swagger |
|---|---|---|---|
| loan-service | 8083 | `GET /actuator/health` | `/swagger-ui.html` (Security 켜짐, swagger 는 permitAll) |
| auto-loan-review | 8089 | `GET /actuator/health` | `/swagger-ui.html` |
| review-ai-gateway | 8088 | `GET /actuator/health` | `/swagger-ui.html` |
| doc-agent | 8087 | `GET /actuator/health` | `/swagger-ui.html` |

```powershell
foreach ($p in 8083,8089,8088,8087) {
  try { "{0}: {1}" -f $p, (Invoke-RestMethod "http://localhost:$p/actuator/health").status }
  catch { "{0}: DOWN" -f $p }
}
```

모두 `UP` 이면 서비스 레벨 기동은 정상이다.

---

## 1. 서비스 간 base-url 오버라이드 (start.ps1 에 이미 반영됨)

`start.ps1` 은 모든 Spring 서비스를 **호스트에서 bootRun** 으로 띄운다.
loan-service 의 아래 두 base-url 기본값은 **도커 네트워크 호스트명**이라 호스트 실행 시 이름이 풀리지 않는데,
**`start.ps1` 이 이를 자동으로 localhost 로 오버라이드**하므로 별도 조치 없이 실연동이 동작한다.

| 설정 | 코드 기본값(도커용) | start.ps1 오버라이드 | 미설정 시 폴백 동작 |
|---|---|---|---|
| `AUTO_REVIEW_BASE_URL` | `http://auto-loan-review:8086` | `http://localhost:8089` ✅ | 가심사는 try/catch best-effort 라 호출 실패해도 흐름 성공, AI track 만 비어 있음 |
| `DOC_AGENT_BASE_URL` | `http://doc-agent:8087` | `http://localhost:8087` ✅ | 서류 업로드는 검증보류(PENDING)로 강등되어 성공 |
| `advisory.ai-gateway.base-url` | `http://localhost:8088` | (불필요) ✅ | 이미 localhost |

> auto-loan-review·doc-agent 는 도커가 아니라 호스트 bootRun 으로 뜨며 각자 `server.port`
> (8089 / 8087)로 listen 한다. start.ps1 의 오버라이드 포트가 이와 일치한다.

**loan-service 만 따로 수동 기동**할 경우엔 같은 변수를 직접 넣어야 한다:

```powershell
$env:AUTO_REVIEW_BASE_URL = "http://localhost:8089"
$env:DOC_AGENT_BASE_URL   = "http://localhost:8087"
.\gradlew.bat :services:loan-service:bootRun
```

> AI(LLM) 실제 호출이 일어나는 auto-loan-review / review-ai-gateway 는 `.env` 의 `OPENAI_API_KEY` 가 있어야
> 응답을 생성한다. 키가 없으면 health 는 UP 이어도 분석 호출은 실패한다.

---

## 2. 인증 (로컬 직접 호출)

운영에서는 API Gateway 가 JWT 검증 후 `X-User-Id` / `X-User-Role` 헤더를 주입한다.
**로컬에서 loan-service 를 직접 호출할 때는 이 헤더를 그대로 넣으면 인증된다** (`GatewayHeaderAuthFilter` 가 신뢰).

- `X-User-Id` : 숫자 customerId (직원도 숫자 ID)
- `X-User-Role` : `ROLE_*` 콤마 구분 멀티롤 (예: `ROLE_OPS`)
- (선택) `X-User-Branch`, `X-User-Grade`

### 역할 ↔ 주요 권한 매트릭스 (loan SecurityConfig 기준)

| 역할 헤더 | 쓸 수 있는 대표 엔드포인트 |
|---|---|
| `ROLE_CUSTOMER` | 대출 신청/조회/취소, 가심사(고객 한도조회) |
| `ROLE_DEPUTY_MANAGER` | 본심사 실행/확정/편향확인, 신용평가·DSR 실행 |
| `ROLE_BRANCH_MANAGER` | 결정 정정(PATCH), 승인자 최종결재, 본사 상신 |
| `ROLE_HQ_REVIEWER` | 상신 건 조회, 편향 우회 승인(bias-override) |
| `ROLE_OPS` | 자동심사(auto-decide), EOD 배치, 소유권검사 없는 조회 |
| `ROLE_COMPLIANCE` | 감사로그(`/api/audit/**`) |
| `ROLE_INTERNAL` | 서비스 간 `/api/internal/**` (또는 `X-Internal-Token`) |

`/api/internal/**` 는 헤더 `X-Internal-Token: local-internal-token` 으로도 통과한다(로컬 기본값).

### PowerShell 헬퍼

```powershell
function Loan($method, $path, $role="ROLE_OPS", $body=$null, $uid=1001) {
  $h = @{ "X-User-Id"=$uid; "X-User-Role"=$role; "Content-Type"="application/json" }
  $args = @{ Method=$method; Uri="http://localhost:8083$path"; Headers=$h }
  if ($body) { $args.Body = ($body | ConvertTo-Json -Depth 8) }
  Invoke-RestMethod @args
}
# 예) Loan GET "/api/loan-applications?customerId=1001"
```

---

## 3. Flow A — 대출 신청 → 가심사 → 본심사 (loan 코어 + auto-review 연동)

대출 라이프사이클의 핵심 경로. 상태 전이: `SUBMITTED → PRESCREENED → APPROVED/REJECTED`.

| # | 단계 | 메서드·경로 | 권한 | 사전조건 / 비고 |
|---|---|---|---|---|
| A1 | 대출 신청 | `POST /api/loan-applications` | CUSTOMER | 바디 customerId 는 무시되고 토큰 principal 로 덮어씀. 상태 `SUBMITTED` |
| A2 | 신청 조회 | `GET /api/loan-applications/{applId}` | 본인 / OPS | OPS 는 소유권 검사 없이 조회 |
| A3 | **가심사 실행** | `POST /api/loan-applications/{applId}/prescreening` | (고객 한도조회와 동일) | `SUBMITTED→PRESCREENED/REJECTED`. PASS 시 한도/금리 자동 채움. **이 단계에서 auto-loan-review 의 evaluate 를 best-effort 호출** (실패해도 가심사는 성공) |
| A4 | 가심사 조회 | `GET /api/loan-applications/{applId}/prescreening` | authenticated | `aiTrackCd` 가 채워졌으면 auto-review 연동 성공 |
| A5 | 신용평가 | `POST /api/loan-applications/{applId}/credit-evaluation` | DEPUTY_MANAGER / OPS | |
| A6 | DSR 산정 | `POST /api/loan-applications/{applId}/dsr-calculation` | DEPUTY_MANAGER / OPS | |
| A7 | **본심사 실행** | `POST /api/loan-applications/{applId}/review` | DEPUTY_MANAGER / OPS | 사전조건: PRESCREENED + CB(APPROVE/REVIEW) + DSR PASS. `PRESCREENED→APPROVED/REJECTED` |

### auto-loan-review 연동 검증 포인트
- A3 호출 후 A4 응답의 `aiTrackCd` 가 `null` 이 아니면 → loan→auto-review 연동 OK.
- `null` 이면 → §1 의 `AUTO_REVIEW_BASE_URL` 미설정이거나 auto-review 미기동/키 없음. loan 로그에서 `auto-review evaluate 실패` 경고로 확인.

```powershell
# A1 신청
$appl = Loan POST "/api/loan-applications" "ROLE_CUSTOMER" @{ prodId=1; requestedAmount=30000000; loanTermMonths=36 } 1001
$id = $appl.data.applId
# A3 가심사 (PASS)
Loan POST "/api/loan-applications/$id/prescreening" "ROLE_CUSTOMER" @{ result="PASS" } 1001
# A4 결과 — aiTrackCd 확인
(Loan GET "/api/loan-applications/$id/prescreening" "ROLE_CUSTOMER" $null 1001).data
```
> 바디 필드명은 Swagger 의 `CreateLoanApplicationRequest` / `RunPrescreeningRequest` 로 최종 확인.

---

## 4. Flow B — 자동심사(권고) → 확정 (auto-decide)

운영자 입력 없이 누적 CB·DSR·LTV 만으로 결정을 산출하는 경로.

| # | 단계 | 메서드·경로 | 권한 | 비고 |
|---|---|---|---|---|
| B1 | 자동결정(권고) | `POST /api/loan-applications/{applId}/review/auto-decide` | OPS | 결과는 권고(PENDING_APPROVAL)만 적재, 신청 상태는 PRESCREENED 유지 |
| B2 | 권고 확정 | `POST /api/loan-applications/{applId}/review/confirm` | DEPUTY_MANAGER / OPS | PENDING_APPROVAL 을 COMPLETED 로 마감하고 상태 전이 |
| B3 | (정정) | `PATCH /api/loan-applications/{applId}/review` | BRANCH_MANAGER | 결정/한도/금리/기간 갱신, 양쪽 status_history 기록 |

---

## 5. Flow C — 편향 검증 (loan ↔ review-ai-gateway, **Kafka 비동기**)

본심사 진행 중 편향(bias) 검증은 **이벤트 기반**이다:

```
loan (편향검증 요청 발행) ──Kafka──▶ review-ai-gateway (consumer)
        ▲                                  │ LLM(bias/compliance 프롬프트) 호출
        └──── REST 콜백 ───────────────────┘  (BiasResultCallbackController)
```

- loan→gateway base-url 은 `localhost:8088` 로 잡혀 있어 호스트 실행 OK.
- gateway→loan 콜백은 `loan-service.base-url` 설정을 사용(консumer).
- **관찰 가능한 결과**: 편향 리포트가 생성되면 본심사가 `BIAS_REVIEWING` 상태가 되고, 심사원이 확인해야 진행.

| # | 단계 | 메서드·경로 | 권한 | 비고 |
|---|---|---|---|---|
| C1 | 편향 리포트 확인 | `POST /api/loan-applications/{applId}/review/acknowledge-bias` | DEPUTY_MANAGER / OPS | 사전: BIAS_REVIEWING + 리포트 1건+ + severity≠BLOCKED |
| C2 | 편향 우회 승인 | `POST /api/loan-reviews/{id}/bias-override` | HQ_REVIEWER | BLOCKED 건을 상급자가 우회 |
| C3 | gateway 직접 분석(동기) | `POST /internal/audit/analyze` (port 8088) | internal | gateway 단독 호출 검증용. 바디는 gateway Swagger 참조 |
| C4 | gateway ping | `GET /internal/ping` (port 8088) | — | 살아있음 확인 |

> 이 플로우는 Kafka(`ib-kafka`) 와 `OPENAI_API_KEY` 가 둘 다 정상이어야 end-to-end 로 돈다.
> 키가 없으면 gateway 가 LLM 호출에서 실패하므로, C3 동기 엔드포인트로 먼저 키/프롬프트만 분리 검증하는 것을 권장.

---

## 6. Flow D — 본사 상신 / 승인자 4-eye

| # | 단계 | 메서드·경로 | 권한 | 비고 |
|---|---|---|---|---|
| D1 | 본사 상신 | `POST /api/loan-applications/{applId}/review/escalate-to-hq` | BRANCH_MANAGER | 이상거래 본사 상신. 이후 HQ_REVIEWER 만 조회 |
| D2 | 상신 건 목록 | `GET /api/loan-reviews/escalated` | HQ_REVIEWER | |
| D3 | 승인자 최종결재 | `POST /api/loan-applications/{applId}/review/approver-approve` | BRANCH_MANAGER | **4-eye: approverId ≠ reviewerId**. OVERRIDE 시 사유·금액 필수 |

---

## 7. Flow E — doc-agent 서류 심사

doc-agent 는 독립 서비스로 직접 검증 가능(port 8087).

| # | 단계 | 메서드·경로 | 비고 |
|---|---|---|---|
| E1 | 서류 제출 | `POST /api/documents/submit` | 심사 큐 적재 |
| E2 | 심사 실행 | `POST /api/documents/{submissionId}/review` | |
| E3 | 큐 조회 | `GET /api/documents/queue` | |
| E4 | Legal hold | `POST /api/documents/{submissionId}/legal-hold/enable`·`/disable` | |
| E5 | health | `GET /health` | |

### loan→doc-agent 연동
- loan 의 서류 업로드: `POST /api/loan-applications/{applId}/documents` (multipart, 권한 authenticated).
- §1 의 `DOC_AGENT_BASE_URL` 미설정이면 doc-agent 호출 실패 → **업로드는 PENDING 으로 강등되어 성공**.
  완전 연동 검증은 env override 후 재기동 필요.

---

## 8. auto-loan-review 직접 호출 (port 8089)

| 메서드·경로 | 비고 |
|---|---|
| `POST /api/ai/auto-review` | 자동심사 본체 |
| `POST /api/ai/auto-review/evaluate` | 가심사 단계에서 loan 이 호출하는 엔드포인트 |
| `POST /api/internal/embeddings/batch` | 임베딩 배치. `X-Internal-Token` 필요(`AI_INTERNAL_TOKEN` 미설정 시 거부) |
| `GET /health` | |

> loan→auto-review 인증은 `X-Internal-Token` 헤더 사용(loan `auto-review.internal-token` = `local-internal-token`).

---

## 9. 빠른 점검 체크리스트

- [ ] 4개 서비스 `/actuator/health` 모두 UP
- [ ] `AUTO_REVIEW_BASE_URL`, `DOC_AGENT_BASE_URL` 오버라이드 — start.ps1 사용 시 자동, loan 단독 기동 시 수동
- [ ] (AI 테스트 시) `.env` 에 `OPENAI_API_KEY` 존재
- [ ] `ib-kafka` 컨테이너 healthy (편향 플로우)
- [ ] Flow A: 신청→가심사→본심사 상태 전이 확인, 가심사 `aiTrackCd` 채워짐
- [ ] Flow C: 편향 리포트 생성 → acknowledge → 진행
- [ ] Flow E: doc-agent 서류 제출→심사 큐 동작

---

## 10. 프론트엔드 기반 테스트 케이스 (Swagger/curl 대신 화면에서)

§2~§8 은 헤더(`X-User-Id`)를 직접 주입하는 백엔드 검증 방식이다.
아래는 **실제 데모 계정으로 로그인해 화면에서** 동일 시나리오를 검증하는 버전이다.
헤더의 임의 숫자(2001/2002) 대신 실제 로그인 계정을 사용한다.

### 10.1 로그인 계정 매핑

| 역할 | 로그인 ID | 이름/직책 | customerId | 비밀번호 | 로그인 화면 |
|---|---|---|---|---|---|
| 신청 고객 | `user01`~`user03` | 일반 고객 | 9111~9113 | `Employee1234!` | `/login` (개인) |
| 심사원(DEPUTY) | `deputy01` | 심사대리·부지점장(수동심사) | 9010 | `Employee1234!` | `/admin/login` |
| 승인자(BRANCH_MANAGER) | `employee01` | 박상우·지점장(최종결재) | 9001 | `Employee1234!` | `/admin/login` |
| 운영(OPS) | `ops01` | 운영담당(자동심사·EOD) | 9011 | `Employee1234!` | `/admin/login` |
| 본사심사(HQ_REVIEWER) | `review01` | 이심사 | 9004 | `Employee1234!` | `/admin/login` |
| 감사(COMPLIANCE) | `audit01` | 김감사 | 9003 | `Employee1234!` | `/admin/login` |

¹ 고객 데모 계정 `user01`~`user03`은 비밀번호 `Employee1234!`로 로그인한다(V23 마이그레이션으로 시드됨, 직원 데모와 동일 BCrypt 해시 재사용).
필요하면 `/join` 회원가입으로 신규 고객을 만들어 진행해도 된다. (4-eye 핵심인 심사·승인 단계는 위 직원 데모 계정으로 100% 재현 가능)

### 10.2 테스트 케이스

흐름 순서: **고객 신청(user01)** → **직원 심사(deputy01)** → **승인자 결재(employee01)** → 정정/상신 → AI/관리 화면.
4-eye 때문에 25번 승인은 반드시 deputy01과 **다른 계정 employee01**로 진행한다(같은 계정이면 27번처럼 거부).

| No | 화면(경로) | 로그인 계정 | 구분 | 테스트 케이스 | 사전조건 | 입력·동작(클릭) | 기대결과 |
|---|---|---|---|---|---|---|---|
| 1 | `/products/loan` | user01(고객) | 정상 | 대출 상품 목록 진입 | 고객 로그인 | 개인뱅킹>대출 메뉴 클릭 | 상품 카드 목록, 상품별 금리·한도 표시 |
| 2 | `/loans/apply` | user01(고객) | 정상 | 한도조회(미리보기) | 상품·금액 입력 | 금액 입력 후 **[한도조회]** | 신용점수·예상한도·예상금리·등급 표시(DB 미적재) |
| 3 | `/loans/apply` | user01(고객) | 검증 | 필수값 미입력 차단 | - | 상품/금액/목적/고용형태/동의 중 일부 누락 | **[대출 신청하기]** 버튼 비활성(회색) |
| 4 | `/loans/apply` | user01(고객) | 정상 | 대출 신청 제출 | 상품 9003·금액·기간·목적·고용형태+동의 | **[대출 신청하기]** | 신청 생성(SUBMITTED), applId 발급, 본인확인 화면 이동 |
| 5 | `/loans/apply/{applId}/identity-verification` | user01(고객) | 검증 | 휴대폰 형식 오류 | 신청 직후 | 10자리 미만 입력 후 **[본인확인]** | "휴대폰 번호를 올바르게…" 에러 |
| 6 | `/loans/apply/{applId}/identity-verification` | user01(고객) | 정상 | 본인확인(PASS stub) | 신청 직후 | 인증방법 선택+휴대폰 입력+**[본인확인]** | "본인확인 완료"·마스킹 번호, [신청 결과 확인] 노출 (본심사 선행 필수) |
| 7 | `/loans/apply/result?applId=` | user01(고객) | 정상 | 접수 직후 상태 | 본인확인 완료·심사 전 | **[신청 결과 확인]** | 상태=접수완료, 심사 진행현황 영역 표시 |
| 8 | `/admin/login` | - | 정상 | 직원 로그인 | - | deputy01 + Employee1234! + **[로그인]** | 대시보드 이동, 콘솔 진입 (데모칩 클릭=자동입력) |
| 9 | `/admin/login` | user01(고객) | 권한 | 고객 계정 콘솔 차단 | - | 고객 계정으로 로그인 | "관리자 콘솔 접근 권한이 없는 계정" 에러 |
| 10 | `/admin/loan/review` | deputy01(심사원) | 정상 | 본심사 목록·탭 조회 | - | 확정대기/승인자대기 탭 전환 | 상태별 건 목록·건수 표시 |
| 11 | `/admin/loan/review` | deputy01(심사원) | 정상 | 심사 상세 진입 | 목록에 건 존재 | 행의 **[상세]** 클릭 | 해당 신청 심사 상세 화면 이동 |
| 12 | `/admin/loan/review/{applId}` | deputy01(심사원) | 정상 | 1단계 가심사 실행 | 신청 SUBMITTED | **[가심사 실행]** | PRESCREENED, 예상한도/금리·CB·DSR 자동생성 (관측치: 한도3천만/380bps, CB=REVIEW·DSR=PASS) |
| 13 | `/admin/loan/review/{applId}` | deputy01(심사원) | 예외 | SUBMITTED 아닌데 가심사 | 이미 가심사됨/다른 상태 | 가심사 영역 확인 | 실행 버튼 대신 안내 문구 표시 |
| 14 | `/admin/loan/review/{applId}` | audit01(감사) | 권한 | 가심사 권한 없음 | - | 가심사 영역 확인 | "가심사 실행 권한이 없습니다(심사역·운영)" |
| 15 | `/admin/loan/review/{applId}` | deputy01(심사원) | 정상 | 2단계 신용평가 APPROVE | 가심사 PASS | CB엔진 KCB+결정 APPROVE+**[신용평가 실행]** | 신용평가 완료, 결정/점수/등급/PD 표시 |
| 16 | `/admin/loan/review/{applId}` | deputy01(심사원) | 분기 | 신용평가 REVIEW/REJECT | 가심사 PASS | 결정 REVIEW/REJECT 선택 후 실행 | 해당 결정 적재(REJECT는 본심사 거절로 이어짐) |
| 17 | `/admin/loan/review/{applId}` | deputy01(심사원) | 정상 | 3단계 DSR 산정 PASS | 신용평가 완료 | 연소득 입력+**[DSR 실행]** | DSR PASS·비율 표시(연소득 필수) |
| 18 | `/admin/loan/review/{applId}` | deputy01(심사원) | 분기 | DSR FAIL | 신용평가 완료 | 한도 초과 소득/부채 조건 | DSR FAIL 표시(본심사 거절 유도) |
| 19 | `/admin/loan/review/{applId}` | deputy01(심사원) | 정상 | 4단계 본심사 시작(수동) | DSR 완료·IDV 완료 | 유형 수동+결정 승인+**[본심사 시작]** | 심사 생성, 상태=편향검토중(BIAS_REVIEWING) (DSR 완료 전 버튼 비활성) |
| 20 | `/admin/loan/review/{applId}` | deputy01(심사원) | 정상 | 자동 결정 | DSR 완료 | **[자동 결정]** | 확정 대기(PENDING_APPROVAL) 권고 적재(CB.REVIEW면 수동권유 메시지) |
| 21 | `/admin/loan/review/{applId}` | deputy01(심사원) | 정상 | 심사 확정(권고) | 상태 PENDING_APPROVAL | 비고(선택)+**[심사 확정]** | 완료(COMPLETED) 전이 |
| 22 | `/admin/loan/review/{applId}` | deputy01(심사원) | 정상 | 편향 인지 처리 | BIAS_REVIEWING·severity≠BLOCKED | **[편향 인지 처리]** | 승인자 대기(PENDING_APPROVER) 전이(편향 콜백 severity=NONE 수신 후) |
| 23 | `/admin/loan/review/{applId}` | deputy01(심사원) | 분기 | 편향 BLOCKED 오버라이드 | severity=BLOCKED | 오버라이드 사유 입력+**[편향 오버라이드]** | 오버라이드 처리, 진행 가능(사유 필수) |
| 24 | `/admin/loan/review/{applId}` | deputy01(심사원) | 정상 | 체크 로그 추가 | 심사 존재 | 체크항목·결과 선택+**[체크 추가]** | 체크 로그에 행 추가 |
| 25 | `/admin/loan/review/{applId}` | **employee01(승인자)** | 정상 | 승인자 최종 승인(4-eye) | PENDING_APPROVER·심사자와 다른 계정 | **[승인]** | 승인 완료 → 신청 APPROVED (반드시 deputy01과 다른 계정) |
| 26 | `/admin/loan/review/{applId}` | employee01(승인자) | 정상 | 승인자 반려 | PENDING_APPROVER | **[반려]** | 반려 처리 → 신청 REJECTED |
| 27 | `/admin/loan/review/{applId}` | deputy01(심사원) | 권한 | 4-eye 위반(동일인 승인) | 심사자=로그인 직원 동일 | 승인자 대기에서 **[승인]** | 거부(승인자=심사원 금지) 에러 |
| 28 | `/admin/loan/review/{applId}` | employee01(지점장) | 정상 | 결정 정정 | 상태 COMPLETED | 결정/금액/금리·정정사유 입력+**[정정 저장]** | 결정 정정 반영, 체크로그 기록(약정 진입 후 불가) |
| 29 | `/admin/loan/review/{applId}` | deputy01(심사원) | 권한 | 정정 권한 없음 | 상태 COMPLETED | 정정 영역 확인 | "결정 정정 권한이 없습니다(지점장)" |
| 30 | `/admin/loan/review/{applId}` | employee01(지점장) | 정상 | 본사 상신(이상거래) | 진행중 건 | **[상신]** 동작 | ESCALATED_TO_HQ 처리 |
| 31 | `/admin/loan/review` | review01(본사심사) | 권한 | 상신 건 탭 조회 | HQ 심사 권한 | **[상신 건]** 탭 확인 | 상신 탭 노출·목록 조회(타 역할은 탭 숨김) |
| 32 | `/admin/loan/review` | deputy01(심사원) | 정상 | 통계 조회 | - | 통계 탭, 기간 입력+**[조회]** | 결정유형·상태·거절사유별 집계 표시 |
| 33 | `/loans/apply/result?applId=` | user01(고객) | 정상 | 승인 결과 표시 | 직원 심사 APPROVED 후 | 결과 화면 재방문 | "대출 승인" 배너·상태=승인·AI 트랙 배지 |
| 34 | `/loans/apply/result?applId=` | user01(고객) | 정상 | 거절 결과·사유 표시 | 직원 심사 REJECTED 후 | 결과 화면 재방문 | "대출 거절" 배너+거절 사유 안내 |
| 35 | `/loans/apply/result?applId=` | user01(고객) | 정상 | 상태 변경 이력 | 상태 전이 발생 | 결과 화면 하단 확인 | 상태 변경 이력 테이블(이전→변경) 표시 |
| 36 | `/loans/apply/{applId}/collateral` | user01(고객) | 정상 | 담보 등록 | 신청 비거절 | **[담보 등록]**→입력 | 담보 등록됨(담보대출 상품) |
| 37 | `/loans/apply/{applId}/guarantor` | user01(고객) | 정상 | 보증인 동의 | 신청 비거절 | **[보증인 동의]**→입력 | 보증 약정 등록 |
| 38 | `/loans/apply/{applId}/documents` | user01(고객) | 정상 | 서류 제출 | 신청 비거절 | **[서류 제출]**→파일 업로드 | 검증 결과(통과/재제출/보류) 표시(doc-agent 연계) |
| 39 | `/products/loan/status` | user01(고객) | 정상 | 진행현황 조회 | 신청 존재 | 대출>진행현황조회 진입 | 신청 건 진행 상태 표시 |
| 40 | `/products/loan/my` | user01(고객) | 정상 | 내 대출/계약 조회 | 계약 존재 | 대출>내 대출 진입 | 보유 대출·계약 목록 표시 |
| 41 | `/admin/loan/auto-review-sim` | ops01(운영) | 정상 | 자동심사 평가(auto-review) | 서비스 기동 | 평가 입력값+**[평가 실행]** | track=TRACK_1/PD/결정 등 결과 표시(서비스 UP) |
| 42 | `/admin/loan/documents` | review01/ops01 | 정상 | 문서 큐 관리(doc-agent) | 시드 존재 | 화면 조회 | 휴먼리뷰 큐 목록(위조점수·legal_hold) 표시(서비스 UP) |
| 43 | `/admin/loan/identity` | deputy01/ops01 | 정상 | 본인확인 관리 조회 | IDV 데이터 존재 | 화면 조회 | IDV 내역 표시 |
| 44 | `/admin/loan/contracts` | ops01(운영) | 정상 | 계약 목록 조회 | 계약 존재 | 화면 조회 | 계약 목록 표시 |
| 45 | `/admin/loan/credit-report` | ops01(운영) | 정상 | 신용정보 신고 조회 | 신고 데이터 존재 | 화면 조회 | 신용정보 신고 내역 표시 |

> 치환 규칙: **대상서비스/엔드포인트 → 화면 경로**, **X-User-Id 숫자 → 로그인 계정**, **입력값 → 화면 클릭 동작**.
> 순수 health 체크(서비스 기동 확인)는 해당 화면이 정상 동작하면 "UP"으로 갈음한다(프론트에 health 전용 화면 없음).
