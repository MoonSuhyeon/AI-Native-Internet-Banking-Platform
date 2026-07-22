# 담당 도메인 Admin/User 페이지 연결 로드맵

작성일: 2026-06-07 (개정: 담당 범위로 재정렬)
담당 서비스: **loan-service / auto-loan-review / advisory-service / doc-agent**
선행 문서: `plan/admin-loan-frontend-backend-fix-plan.md`(로안 한정, 2026-06-05)
담당 외 이관 문서: `plan/handoff/admin-2B-customer-member-audit.md`, `admin-2D-marketing-event.md`, `admin-2E-admin-auth.md`

> 인접 서비스 `review-ai-gateway`(`/internal/audit` AuditAnalysis)도 심사 AI 계열 — 담당이면 본 문서에 포함.

## 0. 공통 버그 패턴

백엔드 응답 envelope = `{code, message, data}` (`common/.../ApiResponse.java`).
프론트 `const {data: res} = await axios(...)` → payload는 `res.data`.
**목록 payload는 배열이 아니라 `{items:[...]}` 래퍼**인데 다수 페이지가 `setRows(res.data ?? res ?? [])`로
객체째 넣어 `rows.length=undefined` → 테이블 미렌더 + 200이라 에러도 안 뜸.
→ 표준 수정: `setRows(res.data?.items ?? [])`.

---

## 1. 신고된 버그 (즉시 수정 대상)

### ① loan/calendar — 조회 파싱 버그 (등록은 정상)
- 증상: "조회해도 안뜨고 실패글씨도 안뜸 / 등록은 됨" — 정확히 일치.
- 경로·파라미터·등록(POST) 정상, 등록은 실제 DB 저장.
- 원인: `web/app/(admin)/admin/loan/calendar/page.tsx:41` `setRows(res.data ?? res ?? [])`
  → `res.data`는 `{fromDate,toDate,count,items:[...]}` 객체.
- 수정(1줄): `setRows(res.data?.items ?? [])`

### ② loan/credit-report — 운영자 목록 엔드포인트 부재 (404)
- 프론트 `GET /api/credit-info-reports` (`loan-api.ts:475`).
- 백엔드 `CreditInfoReportDirectController`(`/api/credit-info-reports`)엔 `/{crptId}`, `/{crptId}/retry`,
  `/{crptId}/ack`만 있고 **컬렉션 GET 없음** → 404. (목록은 계약별 `/api/loan-contracts/{cntrId}/credit-info-reports`만)
- 필드명 불일치: 프론트 `reportId/statusCd/agencyCd/requestedAt/respondedAt`
  vs DTO `crptId/crptStatusCd/crptAgencyCd/reportedAt/ackAt`.
- 수정: loan-service에 운영자용 `GET /api/credit-info-reports`(상태/기간 필터+페이지) 신설 → 프론트 필드 정렬.

### ③ loan/notification — 경로 불일치 (404)
- 프론트 `/api/notification-outbox` (`loan-api.ts:487`) vs 백엔드 `/api/notifications`
  (`NotificationOutboxController`). → 404. (선행 문서 "정상" 오기재였음, 정정)
- 경로 교정 후에도 `res.data.items` 추출(①과 동일) + 필드명(프론트 `notifTypeCd/recipientId/statusCd`
  vs DTO `eventTypeCd/referenceId/status`) 정렬 필요.

---

## 2. 담당 외 — 프론트 삭제 대상 (이관 완료)

아래는 담당 범위 밖이라 **프론트에서 제거**한다. 백엔드 신설은 각 handoff 문서로 이관.

### 2-A. 약관 (terms)
- 삭제: `web/app/(admin)/admin/terms/page.tsx`
- `web/lib/admin-mock-data.ts`에서 `MOCK_TERMS`, `TermRecord`, `TermStatus` 제거
- (관련 백엔드 신설 필요 시 별도 도메인 — 현재 어느 서비스에도 약관 도메인 없음)

### 2-C. AML / KYC
- 삭제 페이지:
  `screening/`, `edd/`, `fatca/`, `face-routing/`, `id-verify/`, `duplicates/`, `minor/`, `agent/`
  (각 `web/app/(admin)/admin/<name>/page.tsx`)
- `admin-mock-data.ts`에서 대응 상수/타입 제거:
  `MOCK_SCREENINGS/ScreeningRecord/ScreeningStatus/HitType`, `MOCK_EDD/EDDRecord/EDDType`,
  `MOCK_FATCA/FATCARecord/FATCAType`, `MOCK_FACE_ROUTING/FaceRoutingRecord/FaceRoutingStatus`,
  `MOCK_ID_VERIFY/IDVerifyRecord/DocStatus`, `MOCK_DUPLICATES/DuplicateRecord/DupStatus`,
  `MOCK_MINORS/MinorRecord/MinorStatus`, `MOCK_AGENTS/AgentRecord`

### 삭제 공통 절차
1. 페이지 디렉터리 삭제
2. `components/admin/AdminSidebar` 에서 해당 메뉴 링크 제거
3. `admin-mock-data.ts` 에서 미사용 상수/타입 제거 (다른 페이지 참조 없는지 grep 확인 후)
4. `npm run build` 로 미참조 import 오류 없음 확인

> 담당 외 잔여 영역(고객/회원/감사 2-B, 마케팅/이벤트 2-D, admin 로그인 2-E)은 삭제하지 않고
> handoff 문서로 담당자에게 전달함.

---

## 3. 담당 4개 서비스: 구현 기능 → 페이지 반영 (확정 요구사항)

목표: "구현된 모든 기능을 admin + user 페이지에 반영" — 아래 갭은 모두 **반영 확정**.

### 서비스 포트 / 프론트 클라이언트 (env)
| 서비스 | 포트 | 프론트 클라이언트 | env 키 | 상태 |
|---|---|---|---|---|
| loan-service | 8083 | `web/lib/loan-api.ts` | `NEXT_PUBLIC_LOAN_API_URL` | 있음 |
| advisory-service | 8084 | `web/lib/advisory-api.ts` | `NEXT_PUBLIC_ADVISORY_API_URL` | 있으나 미사용 |
| ai-service(RAG) | 8086 | `web/lib/ai-api.ts` | `NEXT_PUBLIC_AI_API_URL` | 있으나 미사용 |
| doc-agent | 8087 | **신설 필요** `web/lib/doc-agent-api.ts` | `NEXT_PUBLIC_DOC_AGENT_API_URL` | 없음 |
| auto-loan-review | 8089 | **신설 필요** `web/lib/auto-review-api.ts` | `NEXT_PUBLIC_AUTO_REVIEW_API_URL` | 없음 |

> `web/.env.local`에 advisory/ai/doc-agent/auto-review URL 키 추가 필요(현재 loan/deposit/consultation만 존재).

### 신규 admin 메뉴 구성 (채택 확정)
AI 심사지원 기능을 한 곳에 모아 신규 admin 그룹 **`admin/ai/`** 신설:
`admin/ai/rag-documents`(3-5), `admin/ai/advisory-rules`(3-3②), `admin/ai/audit-risk`(3-3③),
`admin/ai/doc-review`(3-4②). 심사 흐름에 종속된 패널(자문 리포트 3-3①, doc 추출결과 3-4①)은
기존 `loan/review/[applId]`·`loan/documents` 안에 인라인.

### 3-1. loan-service — 운영자 계약/사후관리 모니터링 화면 신설 **(필요)**
- 현재 계약·상환·금리·만기·연체·증명서·보증보험·이자발생은 **user 화면 위주**, 운영자 개입 화면 없음.
- 신설: `admin/loan/contracts`(계약 목록·상세 모니터링) + 상세 내 탭으로
  상환스케줄/상환이력(+역분개), 금리변경 이력·요청처리, 만기연장·해지, 연체 스냅샷, 보증보험, 이자발생, 증명서.
- 백엔드는 대부분 존재(`/api/loan-contracts/...`). 운영자 **목록 조회 API**가 없으면 신설 필요
  (현재 list는 `customerId` 파라미터 전제 → 운영자용 전체/상태/지점 필터 목록 추가).
- batch/internal(`/api/internal/...`) 경로는 UI 불요.

### 3-2. auto-loan-review — evaluate를 admin 심사 시뮬레이터로 노출 **(필요)**
- 엔드포인트: `POST /api/ai/auto-review`(8089), `POST /api/ai/auto-review/evaluate`.
- 신설: `web/lib/auto-review-api.ts` 클라이언트 + `admin/loan/review` 영역에 **자동심사 미리보기/시뮬레이션** 패널
  (신청 파라미터 입력 → evaluate 호출 → 결정/점수/근거 표시). 실제 결정과 분리된 dry-run 용도.

### 3-3. advisory-service — orphan 클라이언트 활성화 + 신규 화면 **(필요)**
`web/lib/advisory-api.ts`는 전 기능 구현됨(현재 import 0). 아래로 연결:
- **① 심사상세 패널**: `loan/review/[applId]`에 자문 리포트(목록/상세/view/ack), 유사사례(`/similar-cases`),
  정책인용(`/citations`) 패널 인라인 추가. (기존 loan-service 프록시 대신/병행 advisory 직접 호출)
- **② 자문 규칙 관리**: `admin/ai/advisory-rules` 신설 — 규칙 목록(`/api/advisory/rules`)·수정(PUT).
- **③ 감사·리스크 대시보드**: `admin/ai/audit-risk` 신설 — 최근 감사의견(`/audit/opinions/recent`),
  리뷰어 리스크점수·top-bias·top-compliance(`/audit/risk-scores/...`), quarantine(`/audit/quarantine`),
  리뷰어 통계(`/api/advisory/stats/reviewers/{id}`).
  > 주의: 기존 `admin/audit/quarantine`(audit-api)와 중복 가능 — 통합/정리 필요.

### 3-4. doc-agent — 클라이언트 신설 + 휴먼리뷰 큐 **(필요)**
- 백엔드: 제출+AI추출(`POST /api/documents/submit`, 8087), 휴먼리뷰 결정
  (`POST /api/documents/{id}/review`), 리걸홀드(`PATCH .../legal-hold/enable|disable`).
- **클라이언트 신설**: `web/lib/doc-agent-api.ts`.
- **① 추출결과 표시**: user `loans/apply/[applId]/documents` + admin `loan/documents`에 doc-agent 추출 결과 표시.
- **② 휴먼리뷰 큐**: `admin/ai/doc-review` 신설 — 검토 대기 목록 → 승인/반려 결정.
- **③ 리걸홀드 토글**: 위 화면에서 enable/disable.
  > 참고: 현재 서류 흐름은 loan-service `/api/loan-documents` 사용 — doc-agent와의 연계 지점(제출 시
  >       doc-agent로 추출 위임 여부) 백엔드 설계 확인 필요.

### 3-5. ai-service RAG 문서관리 — `admin/ai/rag-documents`에 배치 **(필요)**
- `web/lib/ai-api.ts`(검색/문서목록/업로드/reindex/ingestion-logs/bootstrap, 8086) 구현됨, 미사용.
- **배치 위치 (채택 확정)**: 신규 `admin/ai/rag-documents`. 이유 — RAG 지식베이스는 advisory(정책인용)와
  auto-review가 공통으로 쓰는 횡단 자산이라 특정 심사 화면이 아닌 **AI 공통 admin 그룹**에 두는 게 적절.
- 기능: 문서 목록/상세, 업로드, reindex, ingestion-log 조회, bootstrap(시드).

---

## 4. 권장 진행 순서

**Phase 0 — 신고 버그·정리 (프론트 위주, 빠름)**
1. ① calendar 파싱 + ③ notification 경로/파싱/필드 → 두 화면 즉시 복구.
2. ② credit-report 목록: loan-service 운영자 컬렉션 API 신설 + 프론트 정렬.
3. 삭제(§2): terms(2-A) + AML/KYC(2-C) 프론트 제거 + 사이드바/mock 정리.
4. 로안 잔여 정합화: 선행 문서 Tier1→2→3.

**Phase 1 — orphan 클라이언트 활성화 (백엔드 변경 최소)**
5. 3-3① 심사상세 자문 패널(advisory-api 연결).
6. 3-5 RAG 문서관리(`admin/ai/rag-documents`, ai-api 연결).
7. env 키 추가(advisory/ai/doc-agent/auto-review) + `admin/ai/` 메뉴 골격.

**Phase 2 — 신규 화면**
8. 3-3② 자문 규칙 관리 / ③ 감사·리스크 대시보드(quarantine 통합).
9. 3-4 doc-agent 클라이언트 + 휴먼리뷰 큐 + 리걸홀드 + 추출결과 표시.
10. 3-2 auto-review 시뮬레이터.
11. 3-1 운영자 계약 모니터링(필요 시 loan-service 운영자 목록 API 선행).

각 단계: 백엔드 DTO 재확인 → 프론트 수정 → 실토큰 검증 → 커밋(한 줄, 기능/테스트 분리) → 보고 후 멈춤.
</content>
