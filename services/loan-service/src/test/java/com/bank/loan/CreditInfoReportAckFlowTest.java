package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.creditreport.domain.CreditInfoReport;
import com.bank.loan.creditreport.dto.AckCallbackRequest;
import com.bank.loan.creditreport.dto.CreditInfoReportResponse;
import com.bank.loan.creditreport.dto.SubmitReportRequest;
import com.bank.loan.creditreport.repository.CreditInfoReportRepository;
import com.bank.loan.creditreport.service.CreditInfoReportDispatchService;
import com.bank.loan.creditreport.service.CreditInfoReportService;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import com.bank.common.web.BusinessException;
import com.bank.loan.support.LoanErrorCode;
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
 * plan 02 step 6: ACK callback 통합 테스트. 연도 2035.
 *
 *   1) REQUESTED 상태에서 ACK → LOAN_151 (SENT 필요)
 *   2) dispatch → SENT, 이후 ACK → ACKED + externalAckNo + ackAt
 *   3) ACKED 재호출 → 멱등 (같은 row 반환, 상태 유지)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreditInfoReportAckFlowTest extends AbstractLoanIntegrationTest {

    @Autowired private LoanApplicationRepository applicationRepository;
    @Autowired private CreditInfoReportService reportService;
    @Autowired private CreditInfoReportRepository reportRepository;
    @Autowired private CreditInfoReportDispatchService dispatchService;

    private static final long CONTRACTED_AMOUNT = 6_000_000L;
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
    void REQUESTED_상태에서_ACK_는_LOAN_151() throws Exception {
        CreditInfoReportResponse submitted = reportService.submit(cntrId, new SubmitReportRequest(
                "NEW_LOAN", "KCB", "NEW", "DISBURSED", "{}"));

        AckCallbackRequest req = new AckCallbackRequest("ACK-PRE-001", OffsetDateTime.now());

        assertThatThrownBy(() -> reportService.ack(submitted.crptId(), req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(LoanErrorCode.LOAN_151);

        // MVC 레이어도 동일 응답
        String body = String.format("{\"externalAckNo\":\"ACK-PRE-002\",\"ackedAt\":\"%s\"}",
                OffsetDateTime.now());
        mockMvc.perform(post("/api/credit-info-reports/{crptId}/ack", submitted.crptId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOAN_151"));
    }

    @Test @Order(2)
    void dispatch_후_ACK_는_ACKED_전이() throws Exception {
        CreditInfoReportResponse submitted = reportService.submit(cntrId, new SubmitReportRequest(
                "NEW_LOAN", "KCB", "NEW", "DISBURSED", "{}"));
        dispatchService.dispatch();

        OffsetDateTime ackedAt = OffsetDateTime.parse("2035-03-15T10:00:00Z");
        String body = String.format("{\"externalAckNo\":\"ACK-OK-001\",\"ackedAt\":\"%s\"}", ackedAt);
        mockMvc.perform(post("/api/credit-info-reports/{crptId}/ack", submitted.crptId())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.crptStatusCd").value("ACKED"));

        CreditInfoReport saved = reportRepository.findByCrptIdAndDeletedAtIsNull(submitted.crptId()).orElseThrow();
        assertThat(saved.currentStatus()).isEqualTo("ACKED");
        assertThat(saved.getExternalAckNo()).isEqualTo("ACK-OK-001");
        assertThat(saved.getAckAt()).isEqualTo(ackedAt);
    }

    @Test @Order(3)
    void ACKED_재호출_은_멱등() throws Exception {
        CreditInfoReportResponse submitted = reportService.submit(cntrId, new SubmitReportRequest(
                "NEW_LOAN", "KCB", "NEW", "DISBURSED", "{}"));
        dispatchService.dispatch();

        AckCallbackRequest first = new AckCallbackRequest("ACK-IDEM-1",
                OffsetDateTime.parse("2035-04-01T00:00:00Z"));
        CreditInfoReportResponse r1 = reportService.ack(submitted.crptId(), first);

        AckCallbackRequest second = new AckCallbackRequest("ACK-IDEM-2",
                OffsetDateTime.parse("2035-04-02T00:00:00Z"));
        CreditInfoReportResponse r2 = reportService.ack(submitted.crptId(), second);

        // 두 번째 호출은 첫 번째 결과를 그대로 반환 — externalAckNo 변경되지 않음
        assertThat(r2.crptId()).isEqualTo(r1.crptId());
        assertThat(r2.crptStatusCd()).isEqualTo("ACKED");
        CreditInfoReport saved = reportRepository.findByCrptIdAndDeletedAtIsNull(submitted.crptId()).orElseThrow();
        assertThat(saved.getExternalAckNo()).isEqualTo("ACK-IDEM-1");
    }

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "ACK_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"ACK 테스트", "loanTypeCd":"CREDIT",
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
                  "customerId":5401, "prodId":%d, "channelCd":"MOBILE",
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
                  "cntrStartDate":"20350101"
                }
                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS, RATE_BPS);
        MvcResult result = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("cntrId").asLong();
    }
}
