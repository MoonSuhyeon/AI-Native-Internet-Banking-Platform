package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.creditreport.channel.CreditInfoReportChannelAdapter;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * plan 02 step 8: CreditInfoReport 라이프사이클 통합 테스트. 연도 2037.
 *
 * 성공 경로: REQUESTED → SENT → ACKED (KCB stub 사용)
 * 실패 경로: REQUESTED → FAILED ... → DEAD (FAIL 어댑터로 강제 실패 시뮬레이션)
 *
 * 실패 경로의 백오프(2^attemptNo 분) 는 테스트에서 빠르게 진행하기 위해
 * 매 dispatch 사이에 outbox.nextAttemptAt 를 직접 과거로 되돌려 다음 사이클에서 픽업되도록 한다.
 */
@Import(CreditInfoReportLifecycleFlowTest.FailingAdapterConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreditInfoReportLifecycleFlowTest extends AbstractLoanIntegrationTest {

    /** dispatch 가 픽업하도록 FAIL 어댑터를 등록. agencyCd=FAIL_TEST 인 신고만 영향. */
    @TestConfiguration
    static class FailingAdapterConfig {
        public static final String AGENCY_FAIL = "FAIL_TEST";

        @Bean
        CreditInfoReportChannelAdapter failingAdapter() {
            return new CreditInfoReportChannelAdapter() {
                @Override public String getAgencyCd() { return AGENCY_FAIL; }
                @Override public SendResult send(CreditInfoReport report) {
                    return new SendResult(false, null, "9999", "stub failure");
                }
            };
        }
    }

    @Autowired private LoanApplicationRepository applicationRepository;
    @Autowired private CreditInfoReportService reportService;
    @Autowired private CreditInfoReportRepository reportRepository;
    @Autowired private CreditInfoReportOutboxRepository outboxRepository;
    @Autowired private CreditInfoReportDispatchService dispatchService;

    private static final long CONTRACTED_AMOUNT = 7_000_000L;
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
    void success_path_REQUESTED_SENT_ACKED() {
        CreditInfoReportResponse submitted = reportService.submit(cntrId, new SubmitReportRequest(
                "NEW_LOAN", "KCB", "NEW", "DISBURSED", "{}"));

        // REQUESTED + outbox PENDING
        assertThat(load(submitted.crptId()).currentStatus()).isEqualTo("REQUESTED");
        assertThat(loadOutbox(submitted.crptId()).getStatus()).isEqualTo("PENDING");

        // dispatch → SENT
        dispatchService.dispatch();
        CreditInfoReport sent = load(submitted.crptId());
        assertThat(sent.currentStatus()).isEqualTo("SENT");
        assertThat(sent.getExternalTxNo()).isNotBlank();
        assertThat(loadOutbox(submitted.crptId()).getStatus()).isEqualTo("SENT");

        // ACK callback → ACKED
        reportService.ack(submitted.crptId(),
                new AckCallbackRequest("ACK-LC-1", OffsetDateTime.parse("2037-01-01T00:00:00Z")));
        assertThat(load(submitted.crptId()).currentStatus()).isEqualTo("ACKED");
    }

    @Test @Order(2)
    void failure_path_REQUESTED_FAILED_DEAD() {
        CreditInfoReportResponse submitted = reportService.submit(cntrId, new SubmitReportRequest(
                "NEW_LOAN", FailingAdapterConfig.AGENCY_FAIL, "NEW", "DISBURSED", "{}"));

        int maxAttempt = CreditInfoReportOutbox.DEFAULT_MAX_ATTEMPT;
        for (int i = 1; i <= maxAttempt; i++) {
            // 백오프를 무시하고 다음 사이클이 즉시 픽업하도록 nextAttemptAt 를 과거로 되돌린다.
            CreditInfoReportOutbox row = loadOutbox(submitted.crptId());
            row.delayNextAttempt(OffsetDateTime.now().minusMinutes(1));
            outboxRepository.save(row);

            dispatchService.dispatch();

            CreditInfoReport rep = load(submitted.crptId());
            CreditInfoReportOutbox after = loadOutbox(submitted.crptId());
            if (i < maxAttempt) {
                assertThat(rep.currentStatus()).isEqualTo("FAILED");
                assertThat(after.getStatus()).isEqualTo("FAILED");
                assertThat(after.getAttemptNo()).isEqualTo(i);
            } else {
                assertThat(rep.currentStatus()).isEqualTo("DEAD");
                assertThat(after.getStatus()).isEqualTo("DEAD");
                assertThat(after.getAttemptNo()).isEqualTo(maxAttempt);
                assertThat(after.getLastError()).contains("stub failure");
            }
        }
    }

    @Test @Order(3)
    void dead_상태에서_retry_는_PENDING_복귀() {
        CreditInfoReportResponse submitted = reportService.submit(cntrId, new SubmitReportRequest(
                "NEW_LOAN", FailingAdapterConfig.AGENCY_FAIL, "NEW", "DISBURSED", "{}"));

        // 강제 DEAD 까지 진행
        int maxAttempt = CreditInfoReportOutbox.DEFAULT_MAX_ATTEMPT;
        for (int i = 1; i <= maxAttempt; i++) {
            CreditInfoReportOutbox row = loadOutbox(submitted.crptId());
            row.delayNextAttempt(OffsetDateTime.now().minusMinutes(1));
            outboxRepository.save(row);
            dispatchService.dispatch();
        }
        assertThat(load(submitted.crptId()).currentStatus()).isEqualTo("DEAD");

        // retry
        reportService.retry(submitted.crptId());

        assertThat(load(submitted.crptId()).currentStatus()).isEqualTo("REQUESTED");
        CreditInfoReportOutbox o = loadOutbox(submitted.crptId());
        assertThat(o.getStatus()).isEqualTo("PENDING");
        assertThat(o.getAttemptNo()).isZero();
    }

    private CreditInfoReport load(Long crptId) {
        return reportRepository.findByCrptIdAndDeletedAtIsNull(crptId).orElseThrow();
    }

    private CreditInfoReportOutbox loadOutbox(Long crptId) {
        return outboxRepository.findByCrptIdAndDeletedAtIsNull(crptId).orElseThrow();
    }

    private String uniq() { return UUID.randomUUID().toString().substring(0, 8); }

    private Long createProduct() throws Exception {
        String code = "LC_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"라이프사이클 테스트", "loanTypeCd":"CREDIT",
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
                  "customerId":5601, "prodId":%d, "channelCd":"MOBILE",
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
                  "cntrStartDate":"20370101"
                }
                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS, RATE_BPS);
        MvcResult result = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("cntrId").asLong();
    }
}
