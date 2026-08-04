package com.bank.loan;

import com.bank.loan.notification.outbox.NotificationOutbox;
import com.bank.loan.notification.outbox.NotificationOutboxRepository;
import com.bank.loan.review.event.LoanBiasCheckRequestedPayload;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.TestPropertySource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 본심사 완료 후 BIAS_REVIEWING 전이 + Kafka outbox 이벤트 적재 통합 테스트.
 *
 * 시나리오:
 *   10) 수동 본심사 run() → revStatus = BIAS_REVIEWING, loan_application = PRESCREENED 유지
 *   11) run() 후 BIAS_CHECK_REQUESTED outbox row 1건 적재 확인
 *   20) autoDecide + confirm() → revStatus = BIAS_REVIEWING
 *   21) confirm() 후 BIAS_CHECK_REQUESTED outbox row 1건 적재 확인
 *
 * 날짜 격리: 연도 2033 사용.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestPropertySource(properties = "loan.review.bias-check.enabled=true")
class LoanReviewBiasCheckEventTest extends AbstractLoanIntegrationTest {

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    private static Long prodId;
    private static Long manualApplId;
    private static Long manualRevId;
    private static Long autoApplId;
    private static Long autoRevId;

    @BeforeAll
    void setup() throws Exception {
        prodId = createCreditProduct();
        activateProduct(prodId);

        manualApplId = createApplication(prodId, 20330001L);
        prepFullyEligible(manualApplId);

        autoApplId = createApplication(prodId, 20330002L);
        prepFullyEligible(autoApplId);
    }

    @Test @Order(10)
    void 수동_본심사_후_BIAS_REVIEWING_신청_PRESCREENED_유지() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/loan-applications/{id}/review", manualApplId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "revTypeCd":"MANUAL","revDecisionCd":"APPROVED",
                                  "reviewerId":20330101
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revStatusCd").value("BIAS_REVIEWING"))
                .andExpect(jsonPath("$.data.revDecisionCd").value("APPROVED"))
                .andExpect(jsonPath("$.data.approvedAt").doesNotExist())
                .andReturn();

        manualRevId = extractData(r).get("revId").asLong();

        // 신청 상태는 PRESCREENED 유지
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/loan-applications/{id}", manualApplId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applStatusCd").value("PRESCREENED"));
    }

    @Test @Order(11)
    void 수동_본심사_후_BIAS_CHECK_REQUESTED_outbox_적재() {
        String expectedKey = LoanBiasCheckRequestedPayload.EVENT_TYPE_CD
                + ":" + manualRevId + ":KAFKA_DOMAIN_EVENT";

        Optional<NotificationOutbox> outbox =
                outboxRepository.findByIdempotencyKeyAndDeletedAtIsNull(expectedKey);

        assertThat(outbox).isPresent();
        assertThat(outbox.get().getEventTypeCd())
                .isEqualTo(LoanBiasCheckRequestedPayload.EVENT_TYPE_CD);
        assertThat(outbox.get().getReferenceId()).isEqualTo(manualRevId);
        assertThat(outbox.get().getStatus()).isEqualTo(NotificationOutbox.STATUS_PENDING);
        assertThat(payloadOf(outbox.get()).path("revId").asLong()).isEqualTo(manualRevId);
        assertThat(outbox.get().getPayload()).contains("BIAS_CHECK_REQUESTED");
    }

    @Test @Order(20)
    void autoDecide_confirm_후_BIAS_REVIEWING() throws Exception {
        MvcResult autoResult = mockMvc.perform(
                        post("/api/loan-applications/{id}/review/auto-decide", autoApplId)
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.revStatusCd").value("PENDING_APPROVAL"))
                .andReturn();
        autoRevId = extractData(autoResult).get("revId").asLong();

        mockMvc.perform(post("/api/loan-applications/{id}/review/confirm", autoApplId)
                        .header("X-User-Id", "20330102")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "confirmRemark":"확정" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revStatusCd").value("BIAS_REVIEWING"))
                .andExpect(jsonPath("$.data.reviewerId").value(20330102))
                .andExpect(jsonPath("$.data.approvedAt").doesNotExist());

        // 신청 상태는 여전히 PRESCREENED
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/loan-applications/{id}", autoApplId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applStatusCd").value("PRESCREENED"));
    }

    @Test @Order(21)
    void confirm_후_BIAS_CHECK_REQUESTED_outbox_적재() {
        String expectedKey = LoanBiasCheckRequestedPayload.EVENT_TYPE_CD
                + ":" + autoRevId + ":KAFKA_DOMAIN_EVENT";

        Optional<NotificationOutbox> outbox =
                outboxRepository.findByIdempotencyKeyAndDeletedAtIsNull(expectedKey);

        assertThat(outbox).isPresent();
        assertThat(outbox.get().getReferenceId()).isEqualTo(autoRevId);
        assertThat(payloadOf(outbox.get()).path("applId").asLong()).isEqualTo(autoApplId);
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private Long createCreditProduct() throws Exception {
        String code = "BEV_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult r = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prodCd":"%s","prodName":"편향검증 신용대출","loanTypeCd":"CREDIT",
                                  "repaymentMethodCd":"EQUAL","rateTypeCd":"FIXED","baseRateBps":500,
                                  "minAmount":1000000,"maxAmount":100000000,
                                  "minPeriodMo":12,"maxPeriodMo":60,
                                  "collateralRequiredYn":"N","guarantorRequiredYn":"N"
                                }
                                """.formatted(code)))
                .andExpect(status().isCreated()).andReturn();
        return extractData(r).get("prodId").asLong();
    }

    private void activateProduct(Long prodId) throws Exception {
        mockMvc.perform(patch("/api/loan-products/{id}", prodId)
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
                                {"idvMethodCd":"PASS_APP","idvTargetCd":"BORROWER","mobileNo":"01011112033"}
                                """))
                .andExpect(status().isCreated());
    }

    /**
     * outbox payload 는 jsonb 컬럼이라 저장 시 키 순서와 공백이 재정렬된다.
     * 직렬화 형태에 기대지 않도록 파싱해서 값으로 검증한다.
     */
    private com.fasterxml.jackson.databind.JsonNode payloadOf(NotificationOutbox outbox) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(outbox.getPayload());
        } catch (Exception e) {
            throw new IllegalStateException("outbox payload 파싱 실패", e);
        }
    }
}
