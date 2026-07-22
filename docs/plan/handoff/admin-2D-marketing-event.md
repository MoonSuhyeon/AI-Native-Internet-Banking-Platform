# [전달] Admin 마케팅/이벤트 페이지 백엔드 연결

작성일: 2026-06-07
전달 사유: 본 영역은 loan/AI 담당 범위 밖. 마케팅/이벤트 도메인 담당자에게 이관.

## 현황: 전부 Mock 하드코딩 (대응 서비스·도메인 없음)

| 페이지 | 파일 | mock 상수 |
|---|---|---|
| 배너 관리 | `web/app/(admin)/admin/banners/page.tsx` | `MOCK_BANNERS` |
| 캠페인 관리 | `web/app/(admin)/admin/campaigns/page.tsx` | `MOCK_CAMPAIGNS` |
| 이벤트 관리 | `web/app/(admin)/admin/events/page.tsx` | `MOCK_EVENTS` |
| 응모자 관리 | `web/app/(admin)/admin/applicants/page.tsx` | `MOCK_APPLICANTS`, `MOCK_EVENTS` |
| 당첨자 관리 | `web/app/(admin)/admin/winners/page.tsx` | `MOCK_WINNERS`, `MOCK_EVENTS` |
| 가입 통계 | `web/app/(admin)/admin/join-stats/page.tsx` | `JOIN_STATS` |
| 마케팅 통계 | `web/app/(admin)/admin/marketing-stats/page.tsx` | `MARKETING_STATS` |

> user-facing 이벤트 화면(`web/app/(personal)/support/event/[id]`)도 같은 도메인과 연결 필요.

## 필요 작업 (백엔드)
마케팅/이벤트 서비스(또는 도메인) **신설 필요**:
- 배너 CRUD + 노출기간/우선순위
- 캠페인 CRUD + 상태(예정/진행/종료)
- 이벤트 CRUD, 응모자/당첨자 관리(추첨·발송 상태)
- 가입/마케팅 집계 통계 API

## 필요 작업 (프론트)
- `web/lib`에 marketing 전용 api 클라이언트 신설
- 위 페이지들의 `MOCK_*` 의존 제거 → 실 API + 로딩/에러 처리
- 공통 envelope `{code,message,data}`, 목록은 `data.items` 추출(`res.data?.items ?? []`)
</content>
