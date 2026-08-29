package com.bank.loan.batch.config;

import com.bank.loan.accounting.service.AccountingSummaryBatchService;
import com.bank.loan.reconciliation.LoanLedgerReconciliationService;
import com.bank.loan.accrual.service.InterestAccrualBatchService;
import com.bank.loan.applicationexpiry.service.ApplicationExpiryBatchService;
import com.bank.loan.autodebit.service.AutoDebitBatchService;
import com.bank.loan.delinquency.service.DelinquencyRolloverService;
import com.bank.loan.delinquency.service.OverdueInterestAccrualBatchService;
import com.bank.loan.guaranteeinsuranceexpiry.service.GuaranteeInsuranceExpiryBatchService;
import com.bank.loan.batch.listener.EodNotificationListener;
import com.bank.loan.maturity.service.MaturityBatchService;
import com.bank.loan.notification.service.NotificationFlushBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * EOD(일마감) 배치 잡 설정.
 *
 * 스텝 순서:
 *   1. interestAccrualStep         — 이자 발생 (영업일 무관)
 *   2. autoDebitStep               — 자동이체 (영업일만, 비영업일은 서비스 내부에서 skip)
 *   3. delinquencyRolloverStep     — 연체 판정·갱신·스냅샷
 *   4. overdueInterestAccrualStep  — 연체 이자 일별 발생 (rollover 직후 ACTIVE dlq 기준)
 *   5. applicationExpiryStep       — 승인 만료 처리
 *   6. guaranteeInsuranceExpiryStep — 보증보험 만기 처리 (gins_end_date < baseDate)
 *   7. maturityStep                 — 약정 만기 도래 ACTIVE → MATURED 전이
 *   8. accountingSummaryStep        — 일일 회계 요약 적재 (이자/연체이자/자동이체/실행 합계)
 *   9. notificationFlushStep        — 알림 outbox PENDING 일괄 처리 (백로그 해소)
 *
 * 스텝 실패 정책:
 *   - CRITICAL (이자·자동이체·연체·연체이자·회계요약): 예외를 rethrow → Job FAILED, 후속 스텝 중단.
 *     선행 스텝 실패 시 회계 요약에 잘못된 데이터가 집계되는 것을 방지.
 *   - INDEPENDENT (승인만료·보증보험만기·약정만기·알림): 예외를 catch·log 후 계속 진행.
 *     재무 정합성에 영향 없는 부가 처리이므로 단독 실패가 Job 전체를 중단시키지 않음.
 * Spring Batch JobRepository 에 스텝별 실행 이력이 기록된다.
 *
 * 잡 종료 후 EodNotificationListener.afterJob() 이 결과를 NotificationOutbox 에 적재한다.
 * outbox dispatch 배치가 Kafka 토픽 "loan-domain-events" 로 발행 → 외부 모니터링 시스템 수신.
 *
 * 멱등성: baseDate 를 JobParameter 로 사용한다.
 *   같은 baseDate 로 재실행 시 이미 완료된 JobExecution 이 존재하면 JobInstanceAlreadyCompleteException 발생.
 *   실패한 잡은 재실행 가능 (Spring Batch 기본 동작).
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class BatchConfig {

    private final InterestAccrualBatchService interestAccrualBatchService;
    private final AutoDebitBatchService autoDebitBatchService;
    private final DelinquencyRolloverService delinquencyRolloverService;
    private final OverdueInterestAccrualBatchService overdueInterestAccrualBatchService;
    private final ApplicationExpiryBatchService applicationExpiryBatchService;
    private final GuaranteeInsuranceExpiryBatchService guaranteeInsuranceExpiryBatchService;
    private final MaturityBatchService maturityBatchService;
    private final NotificationFlushBatchService notificationFlushBatchService;
    private final AccountingSummaryBatchService accountingSummaryBatchService;
    private final LoanLedgerReconciliationService loanLedgerReconciliationService;

    @Bean
    public Job loanEodJob(JobRepository jobRepository,
                          Step interestAccrualStep,
                          Step autoDebitStep,
                          Step delinquencyRolloverStep,
                          Step overdueInterestAccrualStep,
                          Step applicationExpiryStep,
                          Step guaranteeInsuranceExpiryStep,
                          Step maturityStep,
                          Step accountingSummaryStep,
                          Step ledgerReconciliationStep,
                          Step notificationFlushStep,
                          EodNotificationListener eodNotificationListener) {
        return new JobBuilder("loanEodJob", jobRepository)
                .listener(eodNotificationListener)
                .start(interestAccrualStep)
                .next(autoDebitStep)
                .next(delinquencyRolloverStep)
                .next(overdueInterestAccrualStep)
                .next(applicationExpiryStep)
                .next(guaranteeInsuranceExpiryStep)
                .next(maturityStep)
                .next(accountingSummaryStep)
                .next(ledgerReconciliationStep)
                .next(notificationFlushStep)
                .build();
    }

    @Bean
    public Step interestAccrualStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
        return new StepBuilder("interestAccrualStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    String baseDate = baseDate(chunkContext);
                    try {
                        var result = interestAccrualBatchService.run(baseDate);
                        log.info("[EOD][{}] interestAccrual processed={} skipped={}",
                                baseDate, result.processed(), result.skipped());
                    } catch (Exception e) {
                        // CRITICAL: 이자 발생 실패 시 회계 요약 집계 불가 → Job 중단
                        log.error("[EOD][{}] interestAccrual 실패 — Job 중단: {}", baseDate, e.getMessage(), e);
                        throw e;
                    }
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }

    @Bean
    public Step autoDebitStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
        return new StepBuilder("autoDebitStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    String baseDate = baseDate(chunkContext);
                    try {
                        var result = autoDebitBatchService.run(baseDate);
                        log.info("[EOD][{}] autoDebit processed={} skipped={} skipReason={}",
                                baseDate, result.processed(), result.skipped(), result.skipReason());
                    } catch (Exception e) {
                        // CRITICAL: 자동이체 실패 시 회계 요약 자동이체 집계 불가 → Job 중단
                        log.error("[EOD][{}] autoDebit 실패 — Job 중단: {}", baseDate, e.getMessage(), e);
                        throw e;
                    }
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }

    @Bean
    public Step delinquencyRolloverStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
        return new StepBuilder("delinquencyRolloverStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    String baseDate = baseDate(chunkContext);
                    try {
                        var result = delinquencyRolloverService.rollover(baseDate);
                        log.info("[EOD][{}] delinquencyRollover newlyOverdue={} active={} resolved={} snapshots={}",
                                baseDate, result.newlyOverdueInstallments(),
                                result.activeDelinquencies(), result.resolvedDelinquencies(),
                                result.snapshotsCreated());
                    } catch (Exception e) {
                        // CRITICAL: 연체 판정 실패 시 연체이자 발생·회계 요약 기준 오염 → Job 중단
                        log.error("[EOD][{}] delinquencyRollover 실패 — Job 중단: {}", baseDate, e.getMessage(), e);
                        throw e;
                    }
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }

    @Bean
    public Step overdueInterestAccrualStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
        return new StepBuilder("overdueInterestAccrualStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    String baseDate = baseDate(chunkContext);
                    try {
                        var result = overdueInterestAccrualBatchService.run(baseDate);
                        log.info("[EOD][{}] overdueInterestAccrual processed={} skipped={}",
                                baseDate, result.processed(), result.skipped());
                    } catch (Exception e) {
                        // CRITICAL: 연체이자 발생 실패 시 회계 요약 연체이자 집계 불가 → Job 중단
                        log.error("[EOD][{}] overdueInterestAccrual 실패 — Job 중단: {}", baseDate, e.getMessage(), e);
                        throw e;
                    }
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }

    @Bean
    public Step applicationExpiryStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
        return new StepBuilder("applicationExpiryStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    String baseDate = baseDate(chunkContext);
                    try {
                        var result = applicationExpiryBatchService.run(baseDate);
                        log.info("[EOD][{}] applicationExpiry expired={}", baseDate, result.processed());
                    } catch (Exception e) {
                        log.error("[EOD][{}] applicationExpiry 실패: {}", baseDate, e.getMessage(), e);
                    }
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }

    @Bean
    public Step guaranteeInsuranceExpiryStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
        return new StepBuilder("guaranteeInsuranceExpiryStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    String baseDate = baseDate(chunkContext);
                    try {
                        var result = guaranteeInsuranceExpiryBatchService.run(baseDate);
                        log.info("[EOD][{}] guaranteeInsuranceExpiry total={} expired={}",
                                baseDate, result.totalCandidates(), result.processed());
                    } catch (Exception e) {
                        log.error("[EOD][{}] guaranteeInsuranceExpiry 실패: {}", baseDate, e.getMessage(), e);
                    }
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }

    @Bean
    public Step maturityStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
        return new StepBuilder("maturityStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    String baseDate = baseDate(chunkContext);
                    try {
                        var result = maturityBatchService.run(baseDate);
                        log.info("[EOD][{}] maturity total={} matured={}",
                                baseDate, result.totalCandidates(), result.processed());
                    } catch (Exception e) {
                        log.error("[EOD][{}] maturity 실패: {}", baseDate, e.getMessage(), e);
                    }
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }

    @Bean
    public Step accountingSummaryStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
        return new StepBuilder("accountingSummaryStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    String baseDate = baseDate(chunkContext);
                    try {
                        var result = accountingSummaryBatchService.run(baseDate);
                        log.info("[EOD][{}] accountingSummary created={} interest={} overdueInt={} autoDebitCnt={} disbursedCnt={}",
                                baseDate, result.created(), result.interestRevenue(),
                                result.overdueInterestRevenue(),
                                result.autoDebitCount(), result.disbursedCount());
                    } catch (Exception e) {
                        // CRITICAL: 회계 요약 적재 실패 = 일일 재무 데이터 유실 → Job 중단
                        log.error("[EOD][{}] accountingSummary 실패 — Job 중단: {}", baseDate, e.getMessage(), e);
                        throw e;
                    }
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }

    /**
     * 보조부와 원장을 맞춰 본다.
     *
     * <p>회계 요약 다음이다. 보조부가 만들어진 뒤라야 견줄 값이 생긴다.
     *
     * <p><b>불일치라고 Job 을 멈추지 않는다.</b> 멈추면 뒤따르는 단계가 통째로 밀리고
     * 다음 날 대사도 못 하게 된다 — 발견을 위한 장치가 발견을 막는 셈이다. 대신
     * 기록을 남기고 error 로 남긴다.
     *
     * <p>다만 <b>대사 자체가 돌지 못한 것</b>은 다르다. 원장을 읽지 못했다면 맞는지
     * 틀린지를 모르는 상태이고, 그것을 통과로 넘기면 대사가 있는 것이 없는 것보다
     * 나빠진다. 그때는 Job 을 세운다.
     */
    @Bean
    public Step ledgerReconciliationStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
        return new StepBuilder("ledgerReconciliationStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    String baseDate = baseDate(chunkContext);
                    var result = loanLedgerReconciliationService.reconcile(baseDate);
                    if (result.isMismatch()) {
                        log.error("[EOD][{}] 대사 불일치 — {}", baseDate, result.getBreakDetail());
                    }
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }

    @Bean
    public Step notificationFlushStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
        return new StepBuilder("notificationFlushStep", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    String baseDate = baseDate(chunkContext);
                    try {
                        var result = notificationFlushBatchService.run();
                        log.info("[EOD][{}] notificationFlush iter={} processed={} sent={} stop={}",
                                baseDate, result.iterations(), result.totalProcessed(),
                                result.totalSent(), result.stopReason());
                    } catch (Exception e) {
                        log.error("[EOD][{}] notificationFlush 실패: {}", baseDate, e.getMessage(), e);
                    }
                    return RepeatStatus.FINISHED;
                }, txManager)
                .build();
    }

    private static String baseDate(org.springframework.batch.core.scope.context.ChunkContext ctx) {
        return ctx.getStepContext().getJobParameters().get("baseDate").toString();
    }
}
