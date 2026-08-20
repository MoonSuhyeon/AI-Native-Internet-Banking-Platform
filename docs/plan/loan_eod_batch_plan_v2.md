# EOD 배치 확장 계획 (v2)

<!-- plan-status -->
> **상태: ✅ 구현됨** — `EodBatchController` 확장분
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).


## Context

v1(`loan_eod_batch_plan.md`)에서 핵심 EOD 잡(이자발생 → 자동이체 → 연체롤오버 → 연체이자 → 승인만료)을 구축했다.
운영에 필요한 추가 기능과 도메인 확장 항목을 우선순위별로 정리한다.

대손/NPL 분류는 사용자 지시로 **범위 제외**.

---

## 현재 EOD 잡 상태 (완료분)

```
LoanEodJob
  1. interestAccrualStep         ✅
  2. autoDebitStep               ✅
  3. delinquencyRolloverStep     ✅
  4. overdueInterestAccrualStep  ✅
  5. applicationExpiryStep       ✅
```

---

## 🟢 Phase 1 — 즉시 가능 (짧은 작업 모음)

### A. 보증보험 만료 스텝 추가
- **상태**: `GuaranteeInsuranceExpiryBatchService.run(baseDate)` 이미 구현됨, EOD 미연결
- **할 일**: `BatchConfig` 에 `guaranteeInsuranceExpiryStep` 추가, 6번째 스텝으로 연결
- **테스트**: 만료 보증보험 1건 세팅 → EOD 실행 후 EXPIRED 확인
- **예상 라인**: ~20줄
- **커밋**: `feat(loan/batch): 보증보험 만료 스텝 EOD 추가`

### B. 만기 도래 배치
- **상태**: `Maturity` 엔티티/서비스는 조회·연장만 있음 — 배치 없음
- **할 일**:
  - 신규 `MaturityBatchService.run(baseDate)`
  - 처리: `maturityDate <= baseDate AND cntrStatusCd = ACTIVE` 약정 → 알림 outbox 적재
  - 자동 종결은 본 단계 범위 외 (잔액 처리·전표 필요)
- **신규 파일**: `MaturityBatchService`, `MaturityRunResponse`
- **테스트**: 만기 도달 약정 1건 세팅 → EOD 후 알림 outbox 1건 적재 확인
- **예상 라인**: ~120줄
- **커밋**: `feat(loan/maturity): 만기 도래 배치 추가 및 EOD 연결`

### C. EOD 실행 이력 조회 API
- **목적**: 운영자가 전일 EOD 결과를 한 화면에서 확인
- **할 일**: `GET /api/internal/eod/history?from=YYYYMMDD&to=YYYYMMDD`
  - Spring Batch `JobExplorer` 사용 (자동 메타 테이블 조회)
  - 응답: jobExecutionId, status, baseDate, 시작/종료시각, 소요시간, 스텝별 status
- **신규 파일**: `EodHistoryController`, `EodHistoryResponse`, `EodHistoryService`
- **테스트**: EOD 2회 실행 후 history 조회 → 2건 반환 확인
- **예상 라인**: ~150줄
- **커밋**: `feat(loan/batch): EOD 실행 이력 조회 API 추가`

### D. EOD 실패 재처리 API
- **목적**: 실패한 잡을 실패 스텝부터 재실행
- **할 일**: `POST /api/internal/eod/restart?baseDate=YYYYMMDD`
  - 동일 baseDate 의 마지막 실패 JobExecution 을 Spring Batch restart
  - `JobOperator.restart(executionId)` 사용
- **테스트**: 모킹으로 한 스텝 실패시킨 후 restart → COMPLETED 확인
- **예상 라인**: ~80줄
- **커밋**: `feat(loan/batch): EOD 실패 재처리 API 추가`

**Phase 1 총 분량**: 4커밋, ~370줄 + 테스트 4건

---

## 🟡 Phase 2 — 운영 안정화 (1~2주)

### E. EOD 잡 완료 알림
- **목적**: 잡 종료 시 결과 요약 즉시 전달
- **할 일**:
  - `JobExecutionListener` 구현 → `afterJob()` 에서 결과 적재
  - 성공: Kafka 토픽 `loan-domain-events` 에 `LOAN_EOD_COMPLETED` 발행
  - 실패: 운영자 알림 outbox 에 적재 (HIGH priority)
- **예상 라인**: ~150줄

### F. 알림 아웃박스 EOD 일괄 처리 스텝
- **문제**: 현재 `NotificationDispatchService` 5초 폴링만 — EOD 마감 시점 PENDING 잔여분 누적
- **할 일**: EOD 마지막 스텝으로 `notificationFlushStep` 추가
  - 기존 `NotificationDispatchService.dispatchPendingNotifications()` 호출 반복 (PENDING=0 까지)
  - 또는 타임아웃 (예: 30초) 도달 시 종료
- **예상 라인**: ~80줄

---

## 🟠 Phase 3 — 도메인 확장

### G. 회계 전표(요약) 생성 스텝
- **범위**: 본격 계정계 연동 X, 일일 합계만 별도 테이블 적재
- **신규 테이블** `daily_accounting_summary`:
  - `summary_date`, `interest_revenue`, `overdue_interest_revenue`,
    `auto_debit_principal`, `auto_debit_interest`,
    `disbursed_amount`, `principal_balance_total`
- **신규 파일**: `V15__daily_accounting_summary.sql`, 엔티티/서비스
- **예상 라인**: ~300줄

### H. EOM (월마감) 잡 — 별도 Job
- **트리거**: 매월 1일 새벽 (cron 별도)
- **스텝**:
  - 월별 신규 약정 / 종결 약정 집계
  - 월말 잔액 / 평균 금리
  - 월말 활성 연체 건수 / 금액
  - NPL 잔액 집계 (대손분류는 제외하더라도 STAGE_3 합계는 가능)
- **신규 파일**: `LoanEomJob`, `EomBatchScheduler`, `EomBatchController`, 다수 Step
- **예상 라인**: ~800줄

### I. 휴일 캘린더 자동 갱신
- **문제**: V9 마이그레이션이 2026~2035 만 시드 → 2036 부터 EOD 가 영업일 판단 불가
- **할 일**: 매년 12월 1일 다음 해 캘린더 자동 시드 (외부 공휴일 API 또는 수동 입력)
- **예상 라인**: ~200줄

---

## 🔴 Phase 4 — 큰 도메인 (별도 프로젝트급)

### J. IFRS9 ECL 산출
- PD/LGD/EAD 모델 + 과거 데이터 통계
- 외부 리스크 엔진 어댑터 (`mock` / `http`)
- 충당금 테이블 + 월별 산출
- **예상 라인**: ~1500줄

### K. 본격 회계 전표 — 계정계 연동
- 복식부기 전표 생성
- 외부 계정계(GL) 시스템 연동 어댑터
- 정산·검증 로직
- **예상 라인**: ~2000줄

---

## 추천 진행 순서

| 우선순위 | 항목 | 사유 |
|---|---|---|
| 1 | **A** 보증보험 만료 | 코드 있음, 즉시 |
| 2 | **C** 이력 조회 | 운영자가 EOD 결과를 볼 수 없는 게 가장 큰 페인 |
| 3 | **D** 실패 재처리 | 운영 사고 대응 필수 |
| 4 | **B** 만기 도래 | 도메인 완성도 |
| 5 | **E** 완료 알림 | 새벽 시간대 모니터링 |
| 6 | **F** 알림 백로그 | 사용자 경험 |
| 7 | **G** 회계 요약 | 리포팅 기반 |
| 8 | **I** 캘린더 갱신 | 2036 전에 필요 (시급도 낮음) |
| 9 | **H** EOM | 별도 사이클 |
| 10 | J, K | 별도 프로젝트 |

---

## 검증 공통

각 작업 후:
1. 단위 테스트 / 통합 테스트 통과
2. `POST /api/internal/eod/run?baseDate=<과거날짜>` 수동 실행 + 로그 확인
3. 신규 스텝의 멱등성 검증 (재실행 시 중복 없음)
4. `기능/테스트 분리 커밋` 규칙 준수
