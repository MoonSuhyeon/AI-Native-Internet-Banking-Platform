package com.bank.loan;

import com.bank.loan.advisory.domain.ReviewAdvisoryAck;
import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.domain.ReviewAdvisoryRule;
import com.bank.loan.advisory.domain.ReviewAdvisorySignal;
import com.bank.loan.advisory.domain.ReviewerDecisionSnapshot;
import com.bank.loan.advisory.repository.ReviewAdvisoryAckRepository;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.advisory.repository.ReviewAdvisoryRuleRepository;
import com.bank.loan.advisory.repository.ReviewAdvisorySignalRepository;
import com.bank.loan.advisory.repository.ReviewerDecisionSnapshotRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 어드바이저리 도메인 5종 (Rule/Report/Signal/Ack/Snapshot) 영속성 슬라이스 검증.
 * 통합 테스트 베이스(DB 공유)에서 unique rule_cd/임의 PK 로 다른 테스트와 격리한다.
 * 배치성 날짜 컬럼(snapshot_date)은 미사용 연도 2040 사용.
 */
class AdvisoryRepositoryPersistenceTest extends AbstractLoanIntegrationTest {

    @Autowired ReviewAdvisoryRuleRepository ruleRepo;
    @Autowired ReviewAdvisoryReportRepository reportRepo;
    @Autowired ReviewAdvisorySignalRepository signalRepo;
    @Autowired ReviewAdvisoryAckRepository ackRepo;
    @Autowired ReviewerDecisionSnapshotRepository snapshotRepo;

    // ============================================================
    // ReviewAdvisoryRule — 등록계, BaseEntity 감사 컬럼 자동 적재
    // ============================================================

    @Test
    void Rule_save_시_감사_컬럼_자동_적재() {
        ReviewAdvisoryRule saved = ruleRepo.save(buildRule(uniqueCd("RULE_AUDIT"), "Y"));

        ReviewAdvisoryRule loaded = ruleRepo.findByRuleCdAndDeletedAtIsNull(saved.getRuleCd()).orElseThrow();
        assertThat(loaded.getRuleId()).isNotNull();
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getCreatedBy()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(loaded.getUpdatedBy()).isNotNull();
        assertThat(loaded.getDeletedAt()).isNull();
        assertThat(loaded.getVersion()).isZero();
    }

    @Test
    void Rule_활성여부_필터_조회() {
        String onCd  = uniqueCd("RULE_ON");
        String offCd = uniqueCd("RULE_OFF");
        ruleRepo.save(buildRule(onCd, "Y"));
        ruleRepo.save(buildRule(offCd, "N"));

        List<String> activeCodes = ruleRepo.findByActiveYnAndDeletedAtIsNullOrderByRuleCdAsc("Y")
                .stream().map(ReviewAdvisoryRule::getRuleCd).toList();
        assertThat(activeCodes).contains(onCd).doesNotContain(offCd);
    }

    @Test
    void Rule_soft_delete_후_같은_rule_cd_재사용_가능() {
        String cd = uniqueCd("RULE_REUSE");
        ReviewAdvisoryRule first = ruleRepo.save(buildRule(cd, "Y"));
        first.softDelete(999L);
        ruleRepo.saveAndFlush(first);

        // 같은 rule_cd 로 새 row 등록 가능 (partial unique: WHERE deleted_at IS NULL)
        ReviewAdvisoryRule reused = ruleRepo.save(buildRule(cd, "Y"));
        assertThat(reused.getRuleId()).isNotEqualTo(first.getRuleId());

        ReviewAdvisoryRule activeOnly = ruleRepo.findByRuleCdAndDeletedAtIsNull(cd).orElseThrow();
        assertThat(activeOnly.getRuleId()).isEqualTo(reused.getRuleId());
    }

    // ============================================================
    // ReviewAdvisoryReport — 등록계, 상태 전이
    // ============================================================

    @Test
    void Report_save_후_revId_조회_및_상태_전이() {
        Long revId  = randomId();
        Long ruleId = randomId();
        ReviewAdvisoryReport saved = reportRepo.save(buildReport(revId, ruleId, ReviewAdvisoryReport.SEVERITY_CRITICAL));

        List<ReviewAdvisoryReport> found = reportRepo
                .findByRevIdAndDeletedAtIsNullOrderByGeneratedAtDesc(revId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getAdvrId()).isEqualTo(saved.getAdvrId());
        assertThat(found.get(0).isCritical()).isTrue();
        assertThat(found.get(0).isUnresolved()).isTrue();

        // 상태 전이: OPEN -> VIEWED -> ACKED -> RESOLVED
        // saveAndFlush 는 merge 결과(version 갱신된 managed copy)를 반환하므로 매번 재할당해야
        // 다음 단계에서 OptimisticLock 충돌이 발생하지 않는다.
        OffsetDateTime now = OffsetDateTime.now();
        ReviewAdvisoryReport r = found.get(0);
        r.markViewed(now);
        r = reportRepo.saveAndFlush(r);
        assertThat(r.getAdvrStatusCd()).isEqualTo(ReviewAdvisoryReport.STATUS_VIEWED);
        assertThat(r.getFirstViewedAt()).isNotNull();

        r.markAcked();
        r = reportRepo.saveAndFlush(r);
        assertThat(r.getAdvrStatusCd()).isEqualTo(ReviewAdvisoryReport.STATUS_ACKED);

        r.markResolved(now.plusMinutes(1));
        r = reportRepo.saveAndFlush(r);
        assertThat(r.getAdvrStatusCd()).isEqualTo(ReviewAdvisoryReport.STATUS_RESOLVED);
        assertThat(r.getResolvedAt()).isNotNull();
        assertThat(r.isUnresolved()).isFalse();
    }

    @Test
    void Report_target_reviewer_별_조회() {
        Long reviewerId = randomId();
        reportRepo.save(buildReport(randomId(), randomId(), ReviewAdvisoryReport.SEVERITY_WARN, reviewerId));
        reportRepo.save(buildReport(randomId(), randomId(), ReviewAdvisoryReport.SEVERITY_INFO, reviewerId));

        List<ReviewAdvisoryReport> rows = reportRepo
                .findByTargetReviewerIdAndDeletedAtIsNullOrderByGeneratedAtDesc(reviewerId);
        assertThat(rows).hasSize(2);
    }

    // ============================================================
    // ReviewAdvisorySignal — append-only, created_at/by 자동 적재
    // ============================================================

    @Test
    void Signal_append_only_저장_및_audit_적재() {
        Long advrId = randomId();
        signalRepo.save(buildSignal(advrId, "REJECT_RATE_DEVIATION", OffsetDateTime.now().minusMinutes(2)));
        signalRepo.save(buildSignal(advrId, "DSR_OVERRIDE",          OffsetDateTime.now().minusMinutes(1)));

        List<ReviewAdvisorySignal> rows = signalRepo.findByAdvrIdOrderByObservedAtAsc(advrId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getSignalKindCd()).isEqualTo("REJECT_RATE_DEVIATION");
        assertThat(rows.get(1).getSignalKindCd()).isEqualTo("DSR_OVERRIDE");
        assertThat(rows.get(0).getCreatedAt()).isNotNull();
        assertThat(rows.get(0).getCreatedBy()).isNotNull();
    }

    // ============================================================
    // ReviewAdvisoryAck — append-only, 동일 리포트에 다수 ack 적재
    // ============================================================

    @Test
    void Ack_같은_리포트에_다수_적재_가능() {
        Long advrId = randomId();
        OffsetDateTime t1 = OffsetDateTime.now().minusMinutes(2);
        OffsetDateTime t2 = OffsetDateTime.now();
        ackRepo.save(buildAck(advrId, ReviewAdvisoryAck.RESPONSE_NEEDS_MORE_INFO, t1));
        ackRepo.save(buildAck(advrId, ReviewAdvisoryAck.RESPONSE_MAINTAIN,        t2));

        List<ReviewAdvisoryAck> rows = ackRepo.findByAdvrIdOrderByAckedAtAsc(advrId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getAckResponseCd()).isEqualTo(ReviewAdvisoryAck.RESPONSE_NEEDS_MORE_INFO);
        assertThat(rows.get(1).getAckResponseCd()).isEqualTo(ReviewAdvisoryAck.RESPONSE_MAINTAIN);
        assertThat(rows.get(0).getCreatedAt()).isNotNull();
        assertThat(rows.get(0).getCreatedBy()).isNotNull();
    }

    // ============================================================
    // ReviewerDecisionSnapshot — append-only, 자연키 조회
    // ============================================================

    @Test
    void Snapshot_자연키_조회() {
        Long reviewerId = randomId();
        String snapshotDate = "20400115"; // 미사용 연도 — 다른 통합 테스트와 격리
        snapshotRepo.save(buildSnapshot(reviewerId, snapshotDate, "AGE_BAND",        "30S"));
        snapshotRepo.save(buildSnapshot(reviewerId, snapshotDate, "AGE_BAND",        "40S"));
        snapshotRepo.save(buildSnapshot(reviewerId, snapshotDate, "EMPLOYMENT_TYPE", "FULLTIME"));

        List<ReviewerDecisionSnapshot> rows = snapshotRepo
                .findByReviewerIdAndSnapshotDateOrderByCohortDimensionCdAscCohortValueAsc(reviewerId, snapshotDate);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).getCohortDimensionCd()).isEqualTo("AGE_BAND");
        assertThat(rows.get(0).getCohortValue()).isEqualTo("30S");
        assertThat(rows.get(2).getCohortDimensionCd()).isEqualTo("EMPLOYMENT_TYPE");
        assertThat(rows.get(0).getCreatedAt()).isNotNull();
    }

    // ============================================================
    // helpers
    // ============================================================

    private static String uniqueCd(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Long randomId() {
        return ThreadLocalRandom.current().nextLong(9_000_000L, 9_999_999L);
    }

    private static ReviewAdvisoryRule buildRule(String ruleCd, String activeYn) {
        return ReviewAdvisoryRule.builder()
                .ruleCd(ruleCd)
                .ruleName("테스트 룰 " + ruleCd)
                .advisoryTypeCd("BIAS_DETECTION")
                .ruleCategoryCd("REVIEWER_DEVIATION")
                .severityCd(ReviewAdvisoryReport.SEVERITY_WARN)
                .ruleVersion("v1.0")
                .activeYn(activeYn)
                .ruleDesc("슬라이스 테스트용 룰")
                .build();
    }

    private static ReviewAdvisoryReport buildReport(Long revId, Long ruleId, String severity) {
        return buildReport(revId, ruleId, severity, randomId());
    }

    private static ReviewAdvisoryReport buildReport(Long revId, Long ruleId, String severity, Long reviewerId) {
        return ReviewAdvisoryReport.builder()
                .revId(revId)
                .ruleId(ruleId)
                .advisoryTypeCd("BIAS_DETECTION")
                .severityCd(severity)
                .advrStatusCd(ReviewAdvisoryReport.STATUS_OPEN)
                .advrTitle("테스트 리포트")
                .advrSummary("테스트용 요약")
                .targetReviewerId(reviewerId)
                .generatedAt(OffsetDateTime.now())
                .build();
    }

    private static ReviewAdvisorySignal buildSignal(Long advrId, String kindCd, OffsetDateTime observedAt) {
        return ReviewAdvisorySignal.builder()
                .advrId(advrId)
                .signalKindCd(kindCd)
                .signalMetric("metric_" + kindCd.toLowerCase())
                .observedValue(new BigDecimal("0.42"))
                .thresholdValue(new BigDecimal("0.40"))
                .peerBaselineValue(new BigDecimal("0.25"))
                .sampleSize(120)
                .observedAt(observedAt)
                .build();
    }

    private static ReviewAdvisoryAck buildAck(Long advrId, String responseCd, OffsetDateTime ackedAt) {
        return ReviewAdvisoryAck.builder()
                .advrId(advrId)
                .ackReviewerId(randomId())
                .ackResponseCd(responseCd)
                .decisionChangeYn("N")
                .ackReasonCd("REVIEWER_JUDGMENT")
                .beforeDecisionCd("APPROVED")
                .afterDecisionCd("APPROVED")
                .ackedAt(ackedAt)
                .clientIp("127.0.0.1")
                .device("test")
                .build();
    }

    private static ReviewerDecisionSnapshot buildSnapshot(Long reviewerId, String date, String dim, String value) {
        return ReviewerDecisionSnapshot.builder()
                .reviewerId(reviewerId)
                .snapshotDate(date)
                .aggregationWindowCd("DAILY")
                .cohortDimensionCd(dim)
                .cohortValue(value)
                .totalReviewCount(50)
                .approveCount(30)
                .rejectCount(15)
                .pendingCount(5)
                .approveRateBps(6000)
                .rejectRateBps(3000)
                .peerAvgRejectRateBps(2500)
                .deviationSigma(new BigDecimal("1.8500"))
                .snapshottedAt(OffsetDateTime.now())
                .build();
    }
}
