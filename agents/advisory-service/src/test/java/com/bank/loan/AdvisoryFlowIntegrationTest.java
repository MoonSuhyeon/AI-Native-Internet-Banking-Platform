package com.bank.loan;

import com.bank.loan.advisory.domain.ReviewAdvisoryAck;
import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.domain.ReviewAdvisoryRule;
import com.bank.loan.advisory.engine.rules.DsrThresholdOverrideRule;
import com.bank.loan.advisory.repository.ReviewAdvisoryAckRepository;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.advisory.repository.ReviewAdvisoryRuleRepository;
import com.bank.loan.advisory.service.AdvisoryAckService;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 어드바이저리 동기 트리거 풀 시나리오:
 *   1) 정상 신청·가심사·신용평가·DSR PASS·본인확인 → 본심사 APPROVED (LoanReview row 생성)
 *   2) 본심사 정정(revise) 시점에 CRITICAL 어드바이저리 리포트 1건 직접 적재 (rev_id 연결)
 *   3) 약정 생성 시도 → 409 LOAN_192 (ack 게이트 차단)
 *   4) AdvisoryAckService.acknowledge() 호출 → 리포트 ACKED + ack row 적재
 *   5) 약정 생성 재시도 → 201 SIGNED
 *
 * 자동 발화 자체는 2-7 단위 테스트(Evaluator_DSR_FAIL_시나리오) 에서 검증됨. 본 테스트는
 * "리포트가 있을 때 약정 게이트가 차단하고, ack 후 해제되는" 풀 API 흐름에 집중한다.
 * 테스트 연도 격리: 2030.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdvisoryFlowIntegrationTest extends AbstractLoanIntegrationTest {

    private static final long AMOUNT = 30_000_000L;
    private static final int  MONTHS = 36;
    private static final int  BASE_BPS = 500;

    @Autowired LoanReviewRepository reviewRepo;
    @Autowired ReviewAdvisoryRuleRepository ruleRepo;
    @Autowired ReviewAdvisoryReportRepository reportRepo;
    @Autowired ReviewAdvisoryAckRepository ackRepo;
    @Autowired AdvisoryAckService ackService;

    @Test @Order(10)
    void 본심사_승인_후_CRITICAL_적재_약정_409_ack_후_약정_201_풀_시나리오() throws Exception {
        // 1. 풀 정상 흐름 → 본심사 APPROVED (LoanReview row 생성, 트리거 룰은 DSR PASS 라 발화 안 함)
        Long prodId = createActiveProduct();
        Long applId = createApplication(prodId);
        prepFullyEligible(applId);
        Long revId = runReviewApproved(applId);

        // 2. CRITICAL 어드바이저리 리포트 1건 직접 적재 — 자동 발화는 2-7 에서 검증, 본 테스트는 게이트 동작 검증
        Long ruleId = ruleRepo
                .findByRuleCdAndDeletedAtIsNull(DsrThresholdOverrideRule.RULE_CD)
                .map(ReviewAdvisoryRule::getRuleId)
                .orElseThrow();
        ReviewAdvisoryReport report = reportRepo.save(ReviewAdvisoryReport.builder()
                .revId(revId)
                .ruleId(ruleId)
                .advisoryTypeCd("REREVIEW_RECOMMEND")
                .severityCd(ReviewAdvisoryReport.SEVERITY_CRITICAL)
                .advrStatusCd(ReviewAdvisoryReport.STATUS_OPEN)
                .advrTitle("DSR 한도 초과 승인")
                .advrSummary("통합 테스트 — 운영 흐름에서는 evaluator 가 자동 적재")
                .targetReviewerId(99_201L)
                .generatedAt(OffsetDateTime.now())
                .build());
        Long advrId = report.getAdvrId();

        // 3. 약정 → 409 LOAN_192
        mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(contractBody(applId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_192"));

        // 4. ack — service-level (외부 컨트롤러는 Phase 4 산출물)
        ReviewAdvisoryAck ack = ackService.acknowledge(advrId,
                AdvisoryAckService.AdvisoryAckCommand.builder()
                        .ackResponseCd(ReviewAdvisoryAck.RESPONSE_MAINTAIN)
                        .decisionChangeYn("N")
                        .ackReasonCd("REVIEWER_JUDGMENT")
                        .ackRemark("정책 예외 검토 후 결론 유지")
                        .beforeDecisionCd(LoanReview.DECISION_APPROVED)
                        .afterDecisionCd(LoanReview.DECISION_APPROVED)
                        .ackReviewerId(99_201L)
                        .build());

        assertThat(ack.getAdvkId()).isNotNull();
        ReviewAdvisoryReport reloaded = reportRepo.findById(advrId).orElseThrow();
        assertThat(reloaded.getAdvrStatusCd()).isEqualTo(ReviewAdvisoryReport.STATUS_ACKED);
        List<ReviewAdvisoryAck> acks = ackRepo.findByAdvrIdOrderByAckedAtAsc(advrId);
        assertThat(acks).hasSize(1);
        assertThat(acks.get(0).getAckResponseCd()).isEqualTo(ReviewAdvisoryAck.RESPONSE_MAINTAIN);

        // 5. 약정 재시도 → 201
        MvcResult contractResult = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(contractBody(applId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.cntrStatusCd").value("SIGNED"))
                .andReturn();
        Long cntrId = extractData(contractResult).get("cntrId").asLong();
        assertThat(cntrId).isNotNull();

        // 6. 후속 — 미해결 CRITICAL 0건
        assertThat(reportRepo.findUnresolvedCriticalByRevId(revId)).isEmpty();
    }

    // ============================================================
    // helpers (LoanReviewFlowTest / LoanContractFlowTest 패턴 차용, 테스트 연도 2030 사용)
    // ============================================================

    private static String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createActiveProduct() throws Exception {
        String code = "ADV_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"어드바이저리 통합 테스트", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":%d,
                  "minAmount":1000000, "maxAmount":100000000,
                  "minPeriodMo":12, "maxPeriodMo":60,
                  "collateralRequiredYn":"N", "guarantorRequiredYn":"N"
                }
                """.formatted(code, BASE_BPS);
        MvcResult r = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        Long prodId = extractData(r).get("prodId").asLong();
        mockMvc.perform(patch("/api/loan-products/{prodId}", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "prodStatusCd":"ACTIVE" }
                                """))
                .andExpect(status().isOk());
        return prodId;
    }

    private Long createApplication(Long prodId) throws Exception {
        String body = """
                {
                  "customerId":2030, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":%d, "requestedPeriodMo":%d,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId, AMOUNT, MONTHS);
        MvcResult r = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(r).get("applId").asLong();
    }

    private void prepFullyEligible(Long applId) throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/prescreening", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "prescResultCd":"PASS", "estimatedGrade":"BBB", "estimatedScore":700 }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{applId}/credit-evaluation", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cevalEngine":"KCB",
                                  "cevalDecisionCd":"APPROVE",
                                  "cevalScore":720,
                                  "evalLimitAmount":50000000
                                }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{applId}/dsr-calculation", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "annualIncomeAmt":80000000,
                                  "newAnnualRepayAmt":10000000
                                }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{applId}/identity-verifications", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "idvMethodCd":"PASS_APP", "idvTargetCd":"BORROWER",
                                  "mobileNo":"01098765432" }
                                """))
                .andExpect(status().isCreated());
    }

    private Long runReviewApproved(Long applId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/loan-applications/{applId}/review", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revDecisionCd").value("APPROVED"))
                .andReturn();
        // LoanReviewResponse 에는 revId 가 있을 것이지만 안전을 위해 repo 로 조회
        return reviewRepo.findByApplIdAndDeletedAtIsNull(applId).orElseThrow().getRevId();
    }

    private String contractBody(Long applId) {
        return """
                {
                  "applId":%d,
                  "contractedAmount":%d,
                  "contractedPeriodMo":%d,
                  "baseRateBps":%d,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"EQUAL"
                }
                """.formatted(applId, AMOUNT, MONTHS, BASE_BPS);
    }
}
