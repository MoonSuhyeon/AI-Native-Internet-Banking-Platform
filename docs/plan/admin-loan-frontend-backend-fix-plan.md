# Admin Loan 기능 프론트↔백엔드 계약 정합화 계획

작성일: 2026-06-05
대상: `web/app/(admin)/admin/loan/**` 페이지 + `web/lib/loan-api.ts` ↔ `services/loan-service`

## 배경 / 근본 원인 패턴

admin 대출상품(products) 페이지가 "등록·중단이 안 됨"에서 출발해 다음을 차례로 해소함:
1. 웹 공유 `api` baseURL이 loan-service(8083)를 가리켜 **로그인 트래픽 오라우팅** → 토큰 미발급
   - 수정: `web/lib/loan-api.ts` loan 전용 인스턴스 분리, `web/.env.local` 공유 api=8081 / loan=8083
2. customer-service **CORS 미설정** → 브라우저 로그인 프리플라이트 403
   - 수정: `customer-service/SecurityConfig.java` CORS 허용(localhost:3000/3001) 추가
3. loan `application-local.yml` jwt.secret을 `${JWT_SECRET:...}`로 정리(발급=검증 시크릿 일치)
4. products 페이지 payload 누락(`prodCd`,`rateTypeCd` / discontinue `saleEndDate`,`reasonCd`)
   - 수정: 폼 필드 추가·전송 → **검증 통과, create 200 / discontinue 200 확인 완료**

> 공통 교훈: admin 페이지 대부분이 **백엔드 DTO 필수값을 누락하거나 필드명/코드값/엔드포인트가 어긋남**.
> 또한 `/admin/login`은 목업이라 토큰 미발급 → 실제 토큰은 `/login` 실로그인으로 확보해야 함(별도 과제).

## 감사 결과 (2026-06-05, 서브에이전트 2팀 병렬)

이미 정상(수정 불필요): `documents`, `identity`, `notification`

### Tier 1 — 필드 추가만 (간단·저위험)
| 페이지 | 액션 | 엔드포인트 | 문제 | 수정 |
|---|---|---|---|---|
| collateral | 담보해제 | POST `/api/collaterals/{colId}/release` | `releaseDate`(\d{8}) 누락 | 오늘 날짜 자동/입력 추가 |
| credit-report | ack | POST `/api/credit-info-reports/{id}/ack` | `externalAckNo`,`ackedAt` 누락(빈 body) | 입력칸/자동값 추가 |
| review/new | 심사시작 | POST `/api/loan-applications/{applId}/review` | `revDecisionCd`(APPROVED/REJECTED) 누락 | 결정 선택 추가 |

### Tier 2 — 엔드포인트/필드명 교정 (loan-api.ts + 페이지)
| 페이지 | 문제 | 수정 |
|---|---|---|
| calendar | 경로 복수→단수 `/api/business-calendars`→`/api/business-calendar`; 메서드 PATCH→PUT; 필드 `isBusinessDay`(bool)→`businessDayYn`([YN]); 목록 파라미터 `year`→`from`/`to` | loan-api.ts `businessCalendarApi` + 페이지 동시 수정 |
| rate-policy | 우대금리 등록: `discountBps`→`preferentialRateBps`, `conditionDesc`→`conditionCd`; `effectiveStartDate`/`effectiveEndDate` 필요여부 확인 | 폼/매핑 수정 |

### Tier 3 — 심사 상세 워크플로 `review/[applId]` (최대, 일부 입력폼 신설)
| 액션 | 엔드포인트 | 문제 |
|---|---|---|
| 신용평가 | POST `.../credit-evaluation` | 빈 body → `cevalEngine`,`cevalDecisionCd` 필요 |
| DSR | POST `.../dsr-calculation` | 빈 body → `annualIncomeAmt`(@NotNull) 필요 |
| 본심사 시작 | POST `.../review` | `revDecisionCd` 누락 |
| 승인자 결재 | POST `.../review/approver-approve` | 필드명 `approvalDecision`→`approverDecisionCd` |
| 결정 정정 | PATCH `.../review` | `revisitReasonCd`(@NotBlank) 누락 |
| 점검로그 | POST `/api/loan-reviews/{revId}/checks` | 필드명 `checkItem/checkResult`→`checkItemCd/checkResultCd` |

> 주의: 신용평가/DSR/본심사는 프론트가 "실행" 버튼이지만 백엔드가 결과값(엔진·결정·소득)을 요청에서 요구.
> 단순 필드 추가가 아니라 **소형 입력 폼 신설** 필요. 백엔드 의도(자동계산 vs 결과기록) 재확인 후 진행.

## 진행 방식

- **한 페이지씩**: 백엔드 DTO 직접 재확인 → 프론트 수정 → 실제 토큰으로 검증(curl은 ASCII로) → 커밋 → 보고 후 멈춤
- 순서: Tier 1 → Tier 2 → Tier 3
- 커밋: `<type>(<scope>): <한글 subject>` 한 줄 (AI 흔적 금지), 기능/테스트 분리
- 브랜치: `claude/<short-desc>` (현재 작업 브랜치 확인 후)

## 검증 환경 메모

- loan-service 8083 가동 중(JWT_SECRET=`local-dev-secret-key-must-be-at-least-32-chars-long`, 로그 `loan-debug.log`)
- customer_db 포트 **15432**, loan_db **5434** (docker)
- 토큰 검증 시 한글 본문은 파일(@file)+UTF-8로 보낼 것 (콘솔 직접 입력 시 0xdf UTF-8 깨짐)
- 직원/관리자 계정: admin01~05(9101~9105), employee01/02(`Employee1234!`)

## 별도 과제(범위 밖, 추후)
- `/admin/login` 목업 → 실제 백엔드 로그인(customer-service `/api/v1/auth/login`, admin01~05 매핑)으로 전환해 토큰 자동 발급
