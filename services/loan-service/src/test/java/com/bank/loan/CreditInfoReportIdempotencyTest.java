package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.creditreport.dto.CreditInfoReportResponse;
import com.bank.loan.creditreport.dto.SubmitReportRequest;
import com.bank.loan.creditreport.outbox.CreditInfoReportOutbox;
import com.bank.loan.creditreport.outbox.CreditInfoReportOutboxRepository;
import com.bank.loan.creditreport.service.CreditInfoReportService;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * plan 01 step 3: 신용정보 신고 멱등 가드 단위 검증.
 *
 *   1) (cntrId, dlqId, type, reason) 가 동일한 자동 발화 재호출 → 기존 crptId 반환
 *   2) reason 만 다르면 신규 row
 *   3) dlqId=null 인 수동 신고는 멱등 가드 비대상 → 매번 신규
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreditInfoReportIdempotencyTest extends AbstractLoanIntegrationTest {

    @Autowired private LoanApplicationRepository applicationRepository;
    @Autowired private CreditInfoReportService reportService;
    @Autowired private CreditInfoReportOutboxRepository outboxRepository;

    private static final long CONTRACTED_AMOUNT = 8_000_000L;
    private static final int  PERIOD_MONTHS     = 12;
    private static final int  RATE_BPS          = 600;
    private static final long DLQ_ID            = 9_991_001L;

    private Long cntrId;

    @BeforeAll
    void setup() throws Exception {
        Long prodId = createProduct();
        activateProduct(prodId);
        Long applId = createApplication(prodId);
        forceApprove(applId);
        cntrId = createContract(applId);
    }

    @Test @Order(1)
    void 동일_dlq_reason_재호출은_기존_row_반환() {
        SubmitReportRequest req = new SubmitReportRequest(
                "DELINQUENCY", "KCB", "EXISTING", "DELINQUENCY_OPENED",
                "{\"dlqDays\":1}");

        CreditInfoReportResponse first  = reportService.submit(cntrId, DLQ_ID, req);
        CreditInfoReportResponse second = reportService.submit(cntrId, DLQ_ID, req);

        // plan 02 step 4: 즉시 SENT 가 아니라 REQUESTED 로 적재된다. dispatch 가 SENT 로 전이.
        assertThat(first.crptStatusCd()).isEqualTo("REQUESTED");
        assertThat(first.dlqId()).isEqualTo(DLQ_ID);
        assertThat(second.crptId()).isEqualTo(first.crptId());
    }

    @Test @Order(2)
    void 같은_dlq_라도_reason_이_다르면_신규_row() {
        SubmitReportRequest stageAdvanced = new SubmitReportRequest(
                "DELINQUENCY", "KCB", "EXISTING", "DELINQUENCY_STAGE_ADVANCED",
                "{\"toStage\":\"STAGE_2\"}");

        CreditInfoReportResponse opened = reportService.submit(cntrId, DLQ_ID, new SubmitReportRequest(
                "DELINQUENCY", "KCB", "EXISTING", "DELINQUENCY_OPENED", "{}"));
        CreditInfoReportResponse advanced = reportService.submit(cntrId, DLQ_ID, stageAdvanced);

        assertThat(advanced.crptId()).isNotEqualTo(opened.crptId());
        assertThat(advanced.reportReasonCd()).isEqualTo("DELINQUENCY_STAGE_ADVANCED");
    }

    @Test @Order(3)
    void dlqId_null_은_멱등_비대상() {
        SubmitReportRequest req = new SubmitReportRequest(
                "NEW_LOAN", "KCB", "NEW", "NEW_LOAN_CONTRACTED", "{}");

        CreditInfoReportResponse a = reportService.submit(cntrId, req);
        CreditInfoReportResponse b = reportService.submit(cntrId, req);

        assertThat(a.dlqId()).isNull();
        assertThat(b.crptId()).isNotEqualTo(a.crptId());
    }

    @Test @Order(4)
    void submit_은_outbox_PENDING_row_를_함께_적재한다() {
        SubmitReportRequest req = new SubmitReportRequest(
                "DELINQUENCY", "NICE", "EXISTING", "DELINQUENCY_OPENED",
                "{\"trigger\":\"unit\"}");

        CreditInfoReportResponse created = reportService.submit(cntrId, 9_991_777L, req);

        CreditInfoReportOutbox outbox = outboxRepository
                .findByCrptIdAndDeletedAtIsNull(created.crptId()).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo("PENDING");
        assertThat(outbox.getAttemptNo()).isZero();
        assertThat(outbox.getMaxAttempt()).isEqualTo(CreditInfoReportOutbox.DEFAULT_MAX_ATTEMPT);
        assertThat(outbox.getNextAttemptAt()).isNotNull();
    }

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "IDEM_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"멱등 테스트", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":600,
                  "minAmount":1000000, "maxAmount":100000000,
                  "minPeriodMo":12, "maxPeriodMo":60,
                  "collateralRequiredYn":false, "guarantorRequiredYn":false
                }
                """.formatted(code);
        MvcResult result = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("prodId").asLong();
    }

    private void activateProduct(Long prodId) throws Exception {
        mockMvc.perform(patch("/api/loan-products/{prodId}", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"prodStatusCd\":\"ACTIVE\" }"))
                .andExpect(status().isOk());
    }

    private Long createApplication(Long prodId) throws Exception {
        String body = """
                {
                  "customerId":5101, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":%d, "requestedPeriodMo":%d,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId, CONTRACTED_AMOUNT, PERIOD_MONTHS);
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("applId").asLong();
    }

    private void forceApprove(Long applId) {
        LoanApplication app = applicationRepository.findByApplIdAndDeletedAtIsNull(applId).orElseThrow();
        app.markApproved();
        applicationRepository.save(app);
    }

    private Long createContract(Long applId) throws Exception {
        String body = """
                {
                  "applId":%d,
                  "contractedAmount":%d,
                  "contractedPeriodMo":%d,
                  "baseRateBps":%d,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"EQUAL"
                }
                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS, RATE_BPS);
        MvcResult result = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("cntrId").asLong();
    }
}
