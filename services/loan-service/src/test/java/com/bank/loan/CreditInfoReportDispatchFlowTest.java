package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.creditreport.domain.CreditInfoReport;
import com.bank.loan.creditreport.dto.CreditInfoReportResponse;
import com.bank.loan.creditreport.dto.SubmitReportRequest;
import com.bank.loan.creditreport.outbox.CreditInfoReportOutbox;
import com.bank.loan.creditreport.outbox.CreditInfoReportOutboxRepository;
import com.bank.loan.creditreport.repository.CreditInfoReportRepository;
import com.bank.loan.creditreport.service.CreditInfoReportService;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * plan 02 step 5: dispatch 배치 → 어댑터 호출 성공 경로 통합 테스트.
 *
 * 메모리 룰: 배치성 테스트는 다른 연도 — 본 테스트는 2034.
 *
 * 시나리오:
 *   - submit 후 outbox PENDING, 신고 row REQUESTED
 *   - dispatch 호출 → KCB stub 무조건 성공 → 신고 row SENT(externalTxNo 채움), outbox SENT(sentAt 채움)
 *   - dispatch 응답 summary 의 processed/sent 카운트 검증
 */
class CreditInfoReportDispatchFlowTest extends AbstractLoanIntegrationTest {

    @Autowired private LoanApplicationRepository applicationRepository;
    @Autowired private CreditInfoReportService reportService;
    @Autowired private CreditInfoReportRepository reportRepository;
    @Autowired private CreditInfoReportOutboxRepository outboxRepository;

    private static final long CONTRACTED_AMOUNT = 5_000_000L;
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

    @Test
    void submit_후_dispatch_는_SENT_전이() throws Exception {
        SubmitReportRequest req = new SubmitReportRequest(
                "NEW_LOAN", "KCB", "NEW", "DISBURSED", "{\"hint\":\"dispatch\"}");
        CreditInfoReportResponse submitted = reportService.submit(cntrId, req);

        assertThat(submitted.crptStatusCd()).isEqualTo("REQUESTED");
        CreditInfoReportOutbox pending = outboxRepository
                .findByCrptIdAndDeletedAtIsNull(submitted.crptId()).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo("PENDING");

        MvcResult dispatched = mockMvc.perform(post("/api/internal/credit-info-reports/dispatch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").isNumber())
                .andExpect(jsonPath("$.data.sent").isNumber())
                .andReturn();
        int sent = extractData(dispatched).get("sent").asInt();
        // 다른 테스트가 동일 컨텍스트에서 남긴 PENDING 도 같이 처리될 수 있으므로 lower bound 만.
        assertThat(sent).isGreaterThanOrEqualTo(1);

        CreditInfoReport report = reportRepository
                .findByCrptIdAndDeletedAtIsNull(submitted.crptId()).orElseThrow();
        assertThat(report.currentStatus()).isEqualTo("SENT");
        assertThat(report.getExternalTxNo()).startsWith("TX-").hasSize(24);
        assertThat(report.getReportedAt()).isNotNull();

        CreditInfoReportOutbox after = outboxRepository
                .findByCrptIdAndDeletedAtIsNull(submitted.crptId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("SENT");
        assertThat(after.getSentAt()).isNotNull();
        assertThat(after.getAttemptNo()).isZero();
    }

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProduct() throws Exception {
        String code = "DISP_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"디스패치 테스트", "loanTypeCd":"CREDIT",
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
                  "customerId":5301, "prodId":%d, "channelCd":"MOBILE",
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
                  "cntrStartDate":"20340101"
                }
                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS, RATE_BPS);
        MvcResult result = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("cntrId").asLong();
    }
}
