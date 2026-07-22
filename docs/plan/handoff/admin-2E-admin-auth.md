# [전달] Admin 로그인 실연동 (admin 인증)

작성일: 2026-06-07
전달 사유: admin 인증은 전 admin 도메인의 공통 선결 과제. 인증/customer-service 담당자에게 이관.
우선순위: 높음 — 이게 안 되면 모든 admin API 호출이 토큰 없이 동작 불가.

## 현황: 목업 로그인

- `web/app/(admin)/admin/login/page.tsx` 가 `ADMIN_ACCOUNTS`(admin-mock-data) 로 **클라이언트 측 가짜 인증**.
- 실제 JWT 미발급 → admin 페이지에서 백엔드 API 호출 시 토큰 없음/무효.
- 현재 로안 admin 검증은 `/login`(고객 실로그인)으로 토큰을 우회 확보하는 임시 방식.

## 필요 작업
- customer-service `/api/v1/auth/login`(또는 admin 전용 로그인)으로 admin01~05 계정 매핑.
  - 참고 계정: admin01~05(사번 9101~9105), employee01/02 (`Employee1234!`)
- 발급 토큰을 `localStorage.accessToken` 에 저장 → 기존 api 클라이언트 인터셉터가 자동 첨부
  (`loan-api.ts`/`advisory-api.ts` 등 모두 동일 패턴).
- admin 권한/지점 스코프 클레임 설계(프론트가 `adminUser.branchId`, `ROLE_LABELS` 사용 가정).
- 토큰 만료/갱신, 401 시 `/admin/login` 리다이렉트 처리.

## 검증 메모
- JWT 발급=검증 시크릿 일치 필요(`JWT_SECRET`). loan-service는 `JwtFallbackAuthFilter`로 Bearer 직접 파싱.
- loan-service 8083 / customer-service 8081 (로컬, 게이트웨이 미경유 구성).
</content>
