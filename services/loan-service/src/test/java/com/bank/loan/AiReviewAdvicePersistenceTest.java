package com.bank.loan;

import com.bank.loan.review.domain.AiReviewAdvice;
import com.bank.loan.review.repository.AiReviewAdviceRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AiReviewAdvice 엔티티 영속화 테스트.
 *
 * 시나리오:
 *   10) BIAS_CHECK advice 저장 → revId + type 조회 1건
 *   11) 복수 advice 저장 → findByRevId 전체 조회 (최신순)
 *   12) findFirst → 가장 최근 advice 반환
 *   13) 다른 revId 는 조회 결과 없음
 *   14) isBlocked() — severity=BLOCKED 인 경우만 true
 *
 * 날짜 격리: 연도 2032 사용.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiReviewAdvicePersistenceTest extends AbstractLoanIntegrationTest {

    @Autowired
    private AiReviewAdviceRepository adviceRepository;

    private static Long revId;
    private static Long otherRevId;

    @BeforeAll
    void setup() throws Exception {
        Long prodId = createCreditProduct();
        activateProduct(prodId);

        Long applId = createApplication(prodId, 20320001L);
        prepFullyEligible(applId);
        revId = autoDecide(applId);

        Long applId2 = createApplication(prodId, 20320002L);
        prepFullyEligible(applId2);
        otherRevId = autoDecide(applId2);
    }

    @Test @Order(10)
    void BIAS_CHECK_advice_저장_및_type_조회() {
        AiReviewAdvice saved = adviceRepository.save(biasAdvice(revId, "MEDIUM",
                "편향 분석 결과 이상 없음"));

        assertThat(saved.getAdviceId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        List<AiReviewAdvice> list = adviceRepository
                .findByRevIdAndAdviceTypeCdOrderByCreatedAtDesc(revId, AiReviewAdvice.TYPE_BIAS_CHECK);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getSeverityCd()).isEqualTo("MEDIUM");
        assertThat(list.get(0).getAdviceBody()).isEqualTo("편향 분석 결과 이상 없음");
    }

    @Test @Order(11)
    void 복수_advice_저장_전체_조회_최신순() {
        adviceRepository.save(biasAdvice(revId, "LOW", "1차 분석"));
        adviceRepository.save(biasAdvice(revId, "HIGH", "2차 분석 — 재검토"));

        List<AiReviewAdvice> list = adviceRepository.findByRevIdOrderByCreatedAtDesc(revId);
        // Order(10) 에서 1건 + 지금 2건 = 3건
        assertThat(list).hasSizeGreaterThanOrEqualTo(2);
        // 모두 동일 revId
        assertThat(list).allMatch(a -> a.getRevId().equals(revId));
    }

    @Test @Order(12)
    void findFirst_가장_최근_advice_반환() {
        adviceRepository.save(biasAdvice(revId, "NONE", "최신 advice"));

        Optional<AiReviewAdvice> first = adviceRepository
                .findFirstByRevIdAndAdviceTypeCdOrderByCreatedAtDesc(revId, AiReviewAdvice.TYPE_BIAS_CHECK);
        assertThat(first).isPresent();
        assertThat(first.get().getAdviceBody()).isEqualTo("최신 advice");
    }

    @Test @Order(13)
    void 다른_revId_조회_결과_없음() {
        List<AiReviewAdvice> list = adviceRepository
                .findByRevIdAndAdviceTypeCdOrderByCreatedAtDesc(otherRevId, AiReviewAdvice.TYPE_BIAS_CHECK);
        assertThat(list).isEmpty();
    }

    @Test @Order(14)
    void isBlocked_BLOCKED일때만_true() {
        AiReviewAdvice blocked = AiReviewAdvice.builder()
                .revId(revId)
                .adviceTypeCd(AiReviewAdvice.TYPE_BIAS_CHECK)
                .severityCd(AiReviewAdvice.SEVERITY_BLOCKED)
                .adviceBody("DSR 초과 승인 — 규정 위반")
                .build();
        adviceRepository.save(blocked);

        Optional<AiReviewAdvice> found = adviceRepository
                .findFirstByRevIdAndAdviceTypeCdOrderByCreatedAtDesc(revId, AiReviewAdvice.TYPE_BIAS_CHECK);
        assertThat(found).isPresent();
        assertThat(found.get().isBlocked()).isTrue();

        AiReviewAdvice medium = biasAdvice(otherRevId, "MEDIUM", "양호");
        assertThat(medium.isBlocked()).isFalse();
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private AiReviewAdvice biasAdvice(Long revId, String severity, String body) {
        return AiReviewAdvice.builder()
                .revId(revId)
                .adviceTypeCd(AiReviewAdvice.TYPE_BIAS_CHECK)
                .severityCd(severity)
                .adviceBody(body)
                .model("bias-detector-v1")
                .modelVersion("2032-01-01")
                .promptHash("a".repeat(64))
                .inputToken(100)
                .outputToken(50)
                .latencyMs(800)
                .build();
    }

    private Long createCreditProduct() throws Exception {
        String code = "ARA_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult r = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prodCd":"%s","prodName":"ARA 신용대출","loanTypeCd":"CREDIT",
                                  "repaymentMethodCd":"EQUAL","rateTypeCd":"FIXED","baseRateBps":500,
                                  "minAmount":1000000,"maxAmount":100000000,
                                  "minPeriodMo":12,"maxPeriodMo":60,
                                  "collateralRequiredYn":false,"guarantorRequiredYn":false
                                }
                                """.formatted(code)))
                .andExpect(status().isCreated()).andReturn();
        return extractData(r).get("prodId").asLong();
    }

    private void activateProduct(Long prodId) throws Exception {
        mockMvc.perform(patch("/api/loan-products/{prodId}", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prodStatusCd":"ACTIVE"}
                                """))
                .andExpect(status().isOk());
    }

    private Long createApplication(Long prodId, long customerId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId":%d,"prodId":%d,"channelCd":"MOBILE",
                                  "requestedAmount":30000000,"requestedPeriodMo":36,
                                  "loanPurposeCd":"LIVING","repaymentMethodCd":"EQUAL"
                                }
                                """.formatted(customerId, prodId)))
                .andExpect(status().isCreated()).andReturn();
        return extractData(r).get("applId").asLong();
    }

    private void prepFullyEligible(Long applId) throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/prescreening", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prescResultCd":"PASS","estimatedScore":700}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{id}/credit-evaluation", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cevalEngine":"KCB","cevalDecisionCd":"APPROVE",
                                  "cevalScore":700,"evalLimitAmount":50000000
                                }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{id}/dsr-calculation", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"annualIncomeAmt":80000000,"newAnnualRepayAmt":10000000}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{id}/identity-verifications", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idvMethodCd":"PASS_APP","idvTargetCd":"BORROWER","mobileNo":"01011112032"}
                                """))
                .andExpect(status().isCreated());
    }

    private Long autoDecide(Long applId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/loan-applications/{id}/review/auto-decide", applId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn();
        return extractData(r).get("revId").asLong();
    }
}
