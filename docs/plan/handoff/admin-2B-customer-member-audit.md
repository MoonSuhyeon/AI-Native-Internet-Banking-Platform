# [전달] Admin 고객/회원/감사·동의 페이지 백엔드 연결

작성일: 2026-06-07
전달 사유: 본 영역은 loan/AI 담당 범위 밖. customer-service(또는 회원·감사 도메인) 담당자에게 이관.

## 현황: 전부 Mock 하드코딩 (백엔드 없음)

대상 페이지(모두 `@/lib/admin-mock-data` 상수만 렌더, 필터/조회 버튼 무동작):

| 페이지 | 파일 | mock 상수 |
|---|---|---|
| 고객 목록 | `web/app/(admin)/admin/customers/page.tsx` | `MOCK_CUSTOMERS` |
| 회원 목록 | `web/app/(admin)/admin/members/page.tsx` | `MOCK_MEMBERS` |
| 회원 상세 | `web/app/(admin)/admin/members/[id]/page.tsx` | `MOCK_MEMBERS` |
| 회원 상태관리 | `web/app/(admin)/admin/member-status/page.tsx` | `MOCK_MEMBERS`, `MOCK_STATUS_CHANGES` |
| 대시보드 | `web/app/(admin)/admin/dashboard/page.tsx` | `MOCK_CUSTOMERS`, `MOCK_AUDIT_LOGS` |
| 접근 감사로그 | `web/app/(admin)/admin/audit-log/page.tsx` | `MOCK_AUDIT_LOGS` |
| 동의 이력 | `web/app/(admin)/admin/consent-log/page.tsx` | `MOCK_CONSENT_LOGS` |

> 주의: `/admin/audit`(loan-service audit-api 연결됨)와 `/admin/audit-log`(mock, 고객 접근로그)는 **별개**.

## 필요 작업 (백엔드)

customer-service는 현재 **고객 셀프서비스 API만** 존재 (`/api/v1/customers/me/...`, auth/cert/pin/device).
**운영자용(admin) 조회·관리 컨트롤러가 전무**하므로 신설 필요:

- 운영자 고객/회원 목록·검색·상세 조회 API (지점/권한 스코프 반영 — 프론트는 `adminUser.branchId` 필터 가정)
- 회원 상태 변경 이력 API
- 고객정보 접근 감사로그 API (개인정보 접근 추적)
- 동의(약관/마케팅/제3자제공) 이력 조회 API
- 대시보드 집계 API

## 필요 작업 (프론트)

- `web/lib`에 admin 고객/회원/감사 전용 api 클라이언트 신설 (인증 헤더 패턴은 `loan-api.ts` 참고)
- 위 페이지들의 `MOCK_*` 의존 제거 → 실 API + 로딩/에러 상태 처리
- 공통 envelope: 백엔드 응답 `{code,message,data}` (`common/.../ApiResponse.java`), 목록은 `data.items` 형태 권장

## 참고 (공통 버그 패턴)
프론트에서 목록 파싱 시 `res.data`(envelope 안 payload)가 배열이 아니라 `{items:[...]}` 래퍼인 경우가 많음.
`setRows(res.data?.items ?? [])` 형태로 추출할 것.
</content>
