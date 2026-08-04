package com.bank.loan;

import com.bank.common.web.BusinessException;
import com.bank.loan.support.LoanErrorCode;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.creditreport.domain.CreditInfoReport;
import com.bank.loan.creditreport.dto.AckCallbackRequest;
import com.bank.loan.creditreport.dto.CreditInfoReportResponse;
import com.bank.loan.creditreport.dto.SubmitReportRequest;
import com.bank.loan.creditreport.outbox.CreditInfoReportOutbox;
import com.bank.loan.creditreport.outbox.CreditInfoReportOutboxRepository;
import com.bank.loan.creditreport.repository.CreditInfoReportRepository;
import com.bank.loan.creditreport.service.CreditInfoReportDispatchService;
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

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * plan 02 step 7: 운영자 재전송 통합 테스트. 연도 2036.
 *
 *   1) dispatch SENT 후 retry → 신고 REQUESTED 복귀 + outbox PENDING/attemptNo=0
 *   2) ACKED 신고에 retry → LOAN_152
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreditInfoReportRetryFlowTest extends AbstractLoanIntegrationTest {

    @Autowired private LoanApplicationRepository applicationRepository;
    @Autowired private CreditInfoReportService reportService;
    @Autowired private CreditInfoReportRepository reportRepository;
    @Autowired private CreditInfoReportOutboxRepository outboxRepository;
    @Autowired private CreditInfoReportDispatchService dispatchService;

    private static final long CONTRACTED_AMOUNT = 4_000_000L;
    private static final int  PERIOD_MONTHS     = 12;
    private static final int  RATE_BPS          = 600;

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
    void SENT_상태에서_retry_는_REQUESTED_복귀() throws Exception {
        CreditInfoReportResponse submitted = reportService.submit(cntrId, new SubmitReportRequest(
                "NEW_LOAN", "KCB", "NEW", "DISBURSED", "{}"));
        dispatchService.dispatch();

        // pre: SENT, outbox SENT
        CreditInfoReport before = reportRepository.findByCrptIdAndDeletedAtIsNull(submitted.crptId()).orElseThrow();
        assertThat(before.currentStatus()).isEqualTo("SENT");

        mockMvc.perform(post("/api/credit-info-reports/{crptId}/retry", submitted.crptId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.crptStatusCd").value("REQUESTED"))
                .andExpect(jsonPath("$.data.externalTxNo").doesNotExist());

        CreditInfoReport after = reportRepository.findByCrptIdAndDeletedAtIsNull(submitted.crptId()).orElseThrow();
        assertThat(after.currentStatus()).isEqualTo("REQUESTED");
        assertThat(after.getExternalTxNo()).isNull();
        assertThat(after.getReportedAt()).isNull();

        CreditInfoReportOutbox outbox = outboxRepository
                .findByCrptIdAndDeletedAtIsNull(submitted.crptId()).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo("PENDING");
        assertThat(outbox.getAttemptNo()).isZero();
        assertThat(outbox.getLastError()).isNull();
    }

    @Test @Order(2)
    void ACKED_신고는_retry_LOAN_152() throws Exception {
        CreditInfoReportResponse submitted = reportService.submit(cntrId, new SubmitReportRequest(
                "NEW_LOAN", "KCB", "NEW", "DISBURSED", "{}"));
        dispatchService.dispatch();
        reportService.ack(submitted.crptId(),
                new AckCallbackRequest("ACK-RTY", OffsetDateTime.parse("2036-02-01T00:00:00Z")));

        assertThatThrownBy(() -> reportService.retry(submitted.crptId()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(LoanErrorCode.LOAN_152);

        mockMvc.perform(post("/api/credit-info-reports/{crptId}/retry", submitted.crptId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOAN_152"));
    }

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "RTY_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"재전송 테스트", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":600,
                  "minAmount":1000000, "maxAmount":100000000,
                  "minPeriodMo":12, "maxPeriodMo":60,
                  "collateralRequiredYn":"N", "guarantorRequiredYn":"N"
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
                  "customerId":5501, "prodId":%d, "channelCd":"MOBILE",
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
                  "repaymentMethodCd":"EQUAL",
                  "cntrStartDate":"20360101"
                }
                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS, RATE_BPS);
        MvcResult result = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("cntrId").asLong();
    }
}
