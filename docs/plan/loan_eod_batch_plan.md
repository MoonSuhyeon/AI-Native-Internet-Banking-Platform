# EOD 배치 구현 계획

<!-- plan-status -->
> **상태: ✅ 구현됨** — `EodBatchController`
>
> 상태 어휘는 넷뿐이다(구현됨 · 진행 중 · 설계만 · 폐기).
> 전체 목록은 [`docs/plan/README.md`](README.md).


## Context

대출(여신계)은 시간이 지나면 상태가 자동으로 변하는 금융 상품이다.
매 영업일 마감 시 이자 발생, 연체 판정, 자동이체 등을 배치로 처리해야 한다.

현재 codebase에는 개별 배치 서비스(InterestAccrualBatchService, AutoDebitBatchService,
ApplicationExpiryBatchService)가 REST 엔드포인트로 수동 트리거되는 형태로 존재한다.
Spring Batch 프레임워크는 아직 없다. 이번 작업에서 Spring Batch를 도입하고,
누락된 **연체 롤오버 스텝**을 구현하며, 전체 EOD 잡을 하나의 스케줄로 묶는다.

---

## 현재 상태 (탐색 결과)

### 이미 구현된 것
| 서비스 | 위치 | 역할 |
|---|---|---|
| `InterestAccrualBatchService` | `accrual/service/` | ACTIVE 약정 일별 이자 발생 (UNIQUE 제약으로 멱등) |
| `AutoDebitBatchService` | `autodebit/service/` | 영업일에 납부 예정 회차 자동이체 |
| `ApplicationExpiryBatchService` | `applicationexpiry/service/` | APPROVED 신청 만료 처리 |
| `NotificationDispatchService` | `notification/service/` | `@Scheduled(fixedDelay=5000)` 아웃박스 발송 |

### 핵심 누락
- **DelinquencyRolloverBatchService** — DUE 스케줄 → OVERDUE 전환, DELINQUENCY 갱신, 스냅샷 생성
- **LoanEodJob** (Spring Batch Job) — 전체 스텝 순서 조율
- **EodBatchScheduler** — 매일 자동 실행
- Spring Batch 의존성 자체 없음

### 핵심 엔티티 (이미 존재)
- `LoanContract` — 상태: SIGNED / **ACTIVE** / CLOSED
- `RepaymentSchedule` — 상태: **DUE** / **OVERDUE** / PAID / PARTIAL_PAID / SUPERSEDED
- `Delinquency` — 상태: **ACTIVE** / RESOLVED, `dlqStageCd` (STAGE_0~3), `dlqDays`
- `DelinquencyDailySnapshot` — append-only, UNIQUE(dlq_id, snapshot_date)
- `InterestAccrual` — append-only, UNIQUE(cntr_id, accrual_date)

---

## 구현 계획

### 스텝 실행 순서

```
LoanEodJob
  1. interestAccrualStep     — 이자 발생 (영업일 무관, 항상)
  2. autoDebitStep           — 자동이체 출금 (영업일만)
  3. delinquencyRolloverStep — 연체 판정·갱신 (자동이체 후 남은 미납 기준)
  4. applicationExpiryStep   — 승인만료 처리 (독립)
```

> 자동이체가 먼저 실행되어야 한다. 이체 성공 건은 연체에서 제외되기 때문.

---

### Phase 1 — Spring Batch 도입 (의존성·스키마)

**1-1. `services/loan-service/build.gradle`**
```groovy
implementation 'org.springframework.boot:spring-boot-starter-batch'
```

**1-2. Flyway 마이그레이션 추가**
`V13__spring_batch_schema.sql` — Spring Batch 기본 메타 테이블
(`spring.batch.jdbc.initialize-schema=always` 대신 Flyway로 직접 관리)

SQL 출처: Spring Batch 공식 스키마 (`schema-postgresql.sql`)

**1-3. `application.yml` 추가 설정**
```yaml
spring:
  batch:
    job:
      enabled: false          # 부팅 시 자동 실행 방지
    jdbc:
      initialize-schema: never  # Flyway로 관리
loan:
  batch:
    eod-cron: "0 0 1 * * *"   # 새벽 1시 (기본값, 환경변수로 오버라이드)
```

---

### Phase 2 — DelinquencyRolloverBatchService 구현 (신규)

`delinquency/service/DelinquencyRolloverBatchService.java`

**처리 흐름:**
1. `dueDate < baseDate AND rschStatusCd = 'DUE'` 인 스케줄 조회
   → `RepaymentScheduleRepository.findByRschStatusCdAndDueDateLessThanAndRschVersionCdAndDeletedAtIsNull()` 재활용
2. 각 스케줄: `claimStatusChange(rschId, "OVERDUE", ["DUE"])` — 낙관적 락 원자 전환
3. 계약별로 묶어 DELINQUENCY 갱신:
   - 없으면 새로 INSERT (dlqStartDate = 가장 오래된 overdue dueDate)
   - 있으면 UPDATE: `dlqDays`, `dlqStageCd`, `dlqPrincipalAmt`, `dlqInterestAmt`
4. `DelinquencyDailySnapshot` INSERT (UNIQUE 제약 — 멱등 보장)
5. Kafka 이벤트 발행 (아래 Phase 3)
6. 연체 해소 감지: ACTIVE 연체 중 OVERDUE 스케줄이 0개인 계약 → RESOLVED 전환

**Stage 기준:**
```
dlqDays 1-4   → STAGE_0
dlqDays 5-29  → STAGE_1
dlqDays 30-89 → STAGE_2
dlqDays 90+   → STAGE_3
```

---

### Phase 3 — Kafka 이벤트 (신규)

`delinquency/event/LoanDelinquentEvent.java` (record)
```java
record LoanDelinquentEvent(
    Long cntrId, String cntrNo, Long customerId,
    String dlqStageCd, int dlqDays,
    long dlqPrincipalAmt, long dlqInterestAmt,
    String snapshotDate
) {}
```

발행 방식: 기존 `KafkaChannelAdapter` (topic: `loan-domain-events`) 직접 사용.
key = `"LOAN_DELINQUENT:{cntrId}:{snapshotDate}"`

---

### Phase 4 — LoanEodJob (Spring Batch Job)

`batch/job/LoanEodJob.java` / `batch/config/BatchConfig.java`

각 스텝은 기존 서비스를 **Tasklet**으로 래핑:

```java
@Bean
public Job loanEodJob(JobRepository jobRepository,
                       Step interestAccrualStep,
                       Step autoDebitStep,
                       Step delinquencyRolloverStep,
                       Step applicationExpiryStep) {
    return new JobBuilder("loanEodJob", jobRepository)
        .start(interestAccrualStep)
        .next(autoDebitStep)
        .next(delinquencyRolloverStep)
        .next(applicationExpiryStep)
        .build();
}
```

- JobParameters: `baseDate` (String YYYYMMDD) — 재실행 방지 키 겸 처리 기준일
- 각 Tasklet은 try-catch로 실패 격리: 한 스텝 실패 시 나머지 스텝은 계속 진행
  (Spring Batch Step-level skip 대신 Tasklet 내부에서 처리 — 기존 패턴 유지)

---

### Phase 5 — EodBatchScheduler

`batch/scheduler/EodBatchScheduler.java`

```java
@Scheduled(cron = "${loan.batch.eod-cron}")
public void runEod() {
    String baseDate = LocalDate.now(ZoneId.of("Asia/Seoul"))
                               .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    JobParameters params = new JobParametersBuilder()
        .addString("baseDate", baseDate)
        .toJobParameters();
    jobLauncher.run(loanEodJob, params);
}
```

---

### Phase 6 — 내부 REST 트리거 (수동 재실행용)

`batch/controller/LoanEodBatchController.java`

```
POST /api/internal/eod/run?baseDate=20260526
```

기존 개별 배치 컨트롤러와 동일한 패턴. 운영 중 재처리 시 사용.

---

## 생성/수정할 파일

| 구분 | 파일 |
|---|---|
| **수정** | `services/loan-service/build.gradle` |
| **수정** | `services/loan-service/src/main/resources/application.yml` |
| **신규** | `services/loan-service/src/main/resources/db/migration/V13__spring_batch_schema.sql` |
| **신규** | `services/loan-service/.../delinquency/service/DelinquencyRolloverBatchService.java` |
| **신규** | `services/loan-service/.../delinquency/event/LoanDelinquentEvent.java` |
| **신규** | `services/loan-service/.../batch/job/LoanEodJob.java` |
| **신규** | `services/loan-service/.../batch/config/BatchConfig.java` |
| **신규** | `services/loan-service/.../batch/scheduler/EodBatchScheduler.java` |
| **신규** | `services/loan-service/.../batch/controller/LoanEodBatchController.java` |

---

## 검증

1. `./gradlew :services:loan-service:build` — 컴파일 통과
2. `POST /api/internal/eod/run?baseDate=<과거날짜>` — 수동 트리거 후 로그 확인
3. DELINQUENCY_DAILY_SNAPSHOT 행 생성 여부 DB 확인
4. Kafka consumer에서 `LOAN_DELINQUENT` 이벤트 수신 확인
5. 동일 baseDate 재실행 → UNIQUE 제약으로 중복 없이 스킵 확인 (멱등성)

---

## 커밋 계획 (한 단계씩)

1. `feat(loan/batch): Spring Batch 의존성·스키마·기본 설정 추가`
2. `feat(loan/delinquency): 연체 롤오버 배치 서비스 및 Kafka 이벤트 구현`
3. `feat(loan/batch): LoanEodJob 스프링 배치 잡·스케줄러·컨트롤러 구현`
