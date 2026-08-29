# 🧩 와이어프레임 (Wireframes)

Internet Banking MVP 전체 화면의 **로우-피델리티 와이어프레임** 모음이다.
실제 픽셀 디자인이 아니라, 화면별 **레이아웃 구조 · 주요 영역 · 핵심 컴포넌트 · 화면 흐름**을 ASCII 박스로 표현한다.

- 프론트엔드: Next.js(App Router) `web/app/(personal)`, `web/app/(admin)`
- 화면 기능 정의(텍스트): [`loan_screens.md`](../loan_screens.md) 등 도메인별 문서 참조
- 시스템 구조: [`architecture.md`](../architecture.md), 서비스 책임 분리: 아래 [00-system-overview](./00-system-overview.md)

---

## 📁 문서 구성

| 파일 | 범위 | 대상 |
|------|------|------|
| [00-system-overview.md](./00-system-overview.md) | 서비스 아키텍처 · 사이트맵 · 공통 레이아웃 셸 | 전체 |
| [01-customer-auth.md](./01-customer-auth.md) | 로그인 / PIN / 인증서 발급·관리 | 고객 |
| [02-customer-dashboard-accounts.md](./02-customer-dashboard-accounts.md) | 대시보드 / 계좌조회 / 거래내역 | 고객 |
| [03-customer-deposit.md](./03-customer-deposit.md) | 예·적금 상품 / 가입 / 해지 / 계산기 | 고객 |
| [04-customer-loan.md](./04-customer-loan.md) | 대출 상품 / 가심사 / 신청(멀티스텝) / 관리 | 고객 |
| [05-customer-transfer.md](./05-customer-transfer.md) | 당행/타행 이체 / 자동이체 / 이체내역 | 고객 |
| [06-customer-support-settings.md](./06-customer-support-settings.md) | 고객지원 / 설정 / 마이페이지 / 법인뱅킹 | 고객 |
| [07-admin-dashboard-audit.md](./07-admin-dashboard-audit.md) | 관리자 대시보드 / 직원 / 고객 / 감사로그 | 직원 |
| [08-admin-loan-review.md](./08-admin-loan-review.md) | 여신 심사 큐 / 본심사 / 서류 / 배치 | 직원 |
| [09-admin-ai-docagent.md](./09-admin-ai-docagent.md) | AI 문서검토 / RAG / 어드바이저리 / 리스크 | 직원 |

---

## 🔤 와이어프레임 표기 규칙 (Legend)

```
┌─────────────┐   영역/카드 경계
│             │
├─────────────┤   영역 구분선
└─────────────┘

[ 버튼 ]          기본 버튼 (액션)
[[ 주요버튼 ]]    Primary CTA (강조)
( 텍스트입력 )    input 필드
( ▼ 셀렉트 )      드롭다운/셀렉트
[x] / [ ]         체크박스 (선택/미선택)
( • ) / ( )       라디오 (선택/미선택)
<탭1>|탭2|탭3      탭 (꺽쇠=활성)
🔵 / 🟡 / 🔴      상태 배지 (성공/대기/위험)
▤ 표              테이블 그리드
… 페이지네이션     « 1 2 3 »
🔒                인증·권한 게이트 (PIN/인증서/4-eye)
{변수}            동적 값/바인딩 데이터
→                화면 전환 흐름
```

- **Masked** 표기는 권한(role)에 따라 PII가 마스킹됨을 의미: `홍길*` / `010-****-1234`
- 모든 고객 화면은 [공통 셸](./00-system-overview.md#공통-레이아웃-셸) 안에 렌더된다 (GNB + 본문 + 푸터).
