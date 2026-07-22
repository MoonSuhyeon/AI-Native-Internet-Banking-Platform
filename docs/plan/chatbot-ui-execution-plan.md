# 챗봇 UI 실행 계획

## 목표

고객과 직원이 웹 화면에서 상담 챗봇을 사용할 수 있도록 UI를 구현한다.

- 고객: 본인 현금 흐름 조회, 현금 흐름 기반 상품 추천, 시나리오 기반 상담 진행
- 고객 첫 화면: `예금`, `적금`, `청약`, `저축` 상품군 선택 버튼 제공
- 고객 첫 화면: 현금 흐름 기반 추천을 바로 실행할 수 있는 `상품 추천` 버튼 제공
- 직원: 고객 번호 기준 고객 현금 흐름 조회
- 백엔드: `consultation-service`에 이미 구성된 챗봇/기능 실행 API 사용

## 진행 규칙

- 작업을 시작하거나 완료할 때마다 이 파일의 진행 상태를 업데이트한다.
- 코드 수정 전에는 어떤 파일을 고칠지 먼저 기록한다.
- 검증 결과는 성공/실패와 실패 사유를 함께 기록한다.
- `.env`, venv, `__pycache__`, `start-*.bat`, API 키/토큰은 커밋 대상에 포함하지 않는다.
- 기존 사용자 변경사항은 되돌리지 않고, 필요한 파일만 좁게 수정한다.

## 작업 체크리스트

| 단계 | 상태 | 내용 |
| --- | --- | --- |
| 1 | 완료 | 프론트엔드와 `consultation-service` 백엔드 구조 파악 |
| 2 | 완료 | 챗봇 API 계약과 요청/응답 스키마 확인 |
| 3 | 완료 | 웹 프론트용 consultation API 클라이언트 작성 |
| 4 | 완료 | 고객용 챗봇 위젯 UI 작성: 첫 화면 상품군 선택 및 상품 추천 버튼 포함 |
| 5 | 완료 | 현금 흐름/상품 추천 응답 렌더링 작성 |
| 6 | 완료 | 직원용 고객 현금 흐름 패널 작성 |
| 7 | 완료 | 개인/관리자 화면에 챗봇 UI 연결 |
| 8 | 부분 진행 | 린트/빌드는 기존 `LoanSidebar.tsx` 머지 충돌로 전체 검증 보류 |
| 9 | 완료 | Next 프록시 적용 후 브라우저에서 상품 추천/내 현금흐름 호출 확인 |
| 10 | 진행 예정 | README 또는 작업 문서 최종 업데이트 |

## 현재까지 확인한 내용

- 웹 프론트엔드는 `web` 폴더의 Next.js 앱이다.
- 고객 화면 공통 레이아웃은 `web/app/(personal)/layout.tsx`를 사용한다.
- 기존 상담 진입 UI는 `web/components/layout/FloatingSidebar.tsx`, `web/components/layout/ConsultModal.tsx`에 있다.
- 관리자 상담 관련 화면 후보는 `web/app/(admin)/admin/agent/page.tsx`다.
- `consultation-service` 백엔드에는 다음 API가 있다.
  - `POST /chatbot/scenarios/default`
  - `GET /chatbot/categories`
  - `GET /chatbot/features`
  - `GET /chatbot/features/{code}`
  - `POST /chatbot/features/{code}/execute`
  - `POST /chatbot/consultations/start`
  - `POST /chatbot/consultations/{chatbot_consultation_id}/messages`
- 현재 확인된 기능 코드는 다음과 같다.
  - `MY_CASH_FLOW`
  - `CASH_FLOW_RECOMMEND`
  - `STAFF_CASH_FLOW`
- 기능 실행 API 요청은 `customer_no`, `query`, `staff_id`, `chatbot_consultation_id`를 받을 수 있다.
- 기능 실행 API 응답은 `feature_code`, `status`, `message`, `data[]`, `requires_auth`, `requires_staff_auth` 구조다.
- 챗봇 대화 시작/메시지 API는 `message`, `buttons[]`, `agent_transfer_required`를 반환한다.

## 다음 실행 단계

브라우저에서 `localhost:8087` 직접 호출이 실패해 `web/app/api/consultation/[...path]/route.ts` 프록시를 추가하고, 프론트 API baseURL을 `/api/consultation`으로 변경했다. `CUST001` 기준 상품 추천 응답과 내 현금흐름 응답이 화면에 표시되는 것을 확인했다.
