package com.bank.loan;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.creditreport.channel.CreditInfoReportChannelAdapter;
import com.bank.loan.creditreport.domain.CreditInfoReport;
import com.bank.loan.creditreport.dto.CreditInfoReportDispatchSummary;
import com.bank.loan.creditreport.dto.CreditInfoReportResponse;
import com.bank.loan.creditreport.dto.SubmitReportRequest;
import com.bank.loan.creditreport.outbox.CreditInfoReportOutbox;
import com.bank.loan.creditreport.outbox.CreditInfoReportOutboxRepository;
import com.bank.loan.creditreport.repository.CreditInfoReportRepository;
import com.bank.loan.creditreport.service.CreditInfoReportDispatchService;
import com.bank.loan.creditreport.service.CreditInfoReportService;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

/**
 * plan 02 step 9: 디스패치·재전송 회귀 영역. 연도 2038.
 *
 *   1) nextAttemptAt 가 미래인 outbox row 는 dispatch 가 픽업하지 않는다 (백오프 준수)
 *   2) 같은 dispatch 사이클 안에 success/failure 가 섞이면 summary 카운트가 분리되어 잡힌다
 *   3) FAILED 상태(아직 DEAD 아님)에서도 retry 로 PENDING 복귀 가능
 *   4) outbox row 가 없는 신고(legacy) 에 retry 호출 시 새 outbox 가 생성된다
 */
@Import(CreditInfoReportDispatchRetryRegressionTest.RegressionAdapterConfig.class)
class CreditInfoReportDispatchRetryRegressionTest extends AbstractLoanIntegrationTest {

    @TestConfiguration
    static class RegressionAdapterConfig {
        public static final String AGENCY_FAIL = "REG_FAIL";

        @Bean
        CreditInfoReportChannelAdapter regressionFailingAdapter() {
            return new CreditInfoReportChannelAdapter() {
                @Override public String getAgencyCd() { return AGENCY_FAIL; }
                @Override public SendResult send(CreditInfoReport report) {
                    return new SendResult(false, null, "9999", "regression failure");
                }
            };
        }
    }

    @Autowired private LoanApplicationRepository applicationRepository;
    @Autowired private CreditInfoReportService reportService;
    @Autowired private CreditInfoReportRepository reportRepository;
    @Autowired private CreditInfoReportOutboxRepository outboxRepository;
    @Autowired private CreditInfoReportDispatchService dispatchService;

    private static final long CONTRACTED_AMOUNT = 5_500_000L;
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
    void 미래_nextAttemptAt_은_dispatch_가_픽업_안_함() {
        CreditInfoReportResponse submitted = reportService.submit(cntrId, new SubmitReportRequest(
                "NEW_LOAN", "KCB", "NEW", "DISBURSED", "{}"));

        // nextAttemptAt 를 미래로 강제 이동.
        CreditInfoReportOutbox row = loadOutbox(submitted.crptId());
        row.delayNextAttempt(OffsetDateTime.now().plusHours(1));
        outboxRepository.save(row);

        // dispatch 호출 → 본 row 는 픽업되지 않으므로 신고는 REQUESTED 유지.
        dispatchService.dispatch();

        assertThat(load(submitted.crptId()).currentStatus()).isEqualTo("REQUESTED");
        assertThat(loadOutbox(submitted.crptId()).getStatus()).isEqualTo("PENDING");
        assertThat(loadOutbox(submitted.crptId()).getAttemptNo()).isZero();
    }

    @Test
    void dispatch_summary_는_success_와_failure_를_분리해서_센다() {
        CreditInfoReportResponse okOne = reportService.submit(cntrId, new SubmitReportRequest(
                "NEW_LOAN", "KCB", "NEW", "DISBURSED", "{}"));
        CreditInfoReportResponse failOne = reportService.submit(cntrId, new SubmitReportRequest(
                "NEW_LOAN", RegressionAdapterConfig.AGENCY_FAIL, "NEW", "DISBURSED", "{}"));

        CreditInfoReportDispatchSummary summary = dispatchService.dispatch();

        // 다른 테스트가 남긴 PENDING 도 같이 처리될 수 있으므로 lower bound 검증.
        assertThat(summary.processed()).isGreaterThanOrEqualTo(2);
        assertThat(summary.sent()).isGreaterThanOrEqualTo(1);
        assertThat(summary.failed() + summary.dead()).isGreaterThanOrEqualTo(1);

        assertThat(load(okOne.crptId()).currentStatus()).isEqualTo("SENT");
        assertThat(load(failOne.crptId()).currentStatus()).isEqualTo("FAILED");
    }

    @Test
    void FAILED_상태에서도_retry_는_PENDING_복귀() {
        CreditInfoReportResponse submitted = reportService.submit(cntrId, new SubmitReportRequest(
                "NEW_LOAN", RegressionAdapterConfig.AGENCY_FAIL, "NEW", "DISBURSED", "{}"));
        dispatchService.dispatch();
        assertThat(load(submitted.crptId()).currentStatus()).isEqualTo("FAILED");
        assertThat(loadOutbox(submitted.crptId()).getAttemptNo()).isEqualTo(1);

        reportService.retry(submitted.crptId());

        assertThat(load(submitted.crptId()).currentStatus()).isEqualTo("REQUESTED");
        CreditInfoReportOutbox o = loadOutbox(submitted.crptId());
        assertThat(o.getStatus()).isEqualTo("PENDING");
        assertThat(o.getAttemptNo()).isZero();
        assertThat(o.getLastError()).isNull();
    }

    @Test
    void outbox_없는_신고_에_retry_는_새_outbox_생성() {
        // 신고 row 만 적재하고 outbox 를 강제로 soft-delete — legacy 시뮬레이션.
        CreditInfoReportResponse submitted = reportService.submit(cntrId, new SubmitReportRequest(
                "NEW_LOAN", "KCB", "NEW", "DISBURSED", "{}"));
        CreditInfoReportOutbox existing = loadOutbox(submitted.crptId());
        existing.softDelete(0L);
        outboxRepository.save(existing);
        assertThat(outboxRepository.findByCrptIdAndDeletedAtIsNull(submitted.crptId())).isEmpty();

        reportService.retry(submitted.crptId());

        CreditInfoReportOutbox created = loadOutbox(submitted.crptId());
        assertThat(created.getStatus()).isEqualTo("PENDING");
        assertThat(created.getAttemptNo()).isZero();
        assertThat(created.getOutboxId()).isNotEqualTo(existing.getOutboxId());
    }

    private CreditInfoReport load(Long crptId) {
        return reportRepository.findByCrptIdAndDeletedAtIsNull(crptId).orElseThrow();
    }

    private CreditInfoReportOutbox loadOutbox(Long crptId) {
        return outboxRepository.findByCrptIdAndDeletedAtIsNull(crptId).orElseThrow();
    }

    private String uniq() { return UUID.randomUUID().toString().substring(0, 8); }

    private Long createProduct() throws Exception {
        String code = "REG_" + uniq();
        String body = """
                {
                  "prodCd":"%s", "prodName":"회귀 테스트", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":600,
                  "minAmount":1000000, "maxAmount":100000000,
                  "minPeriodMo":12, "maxPeriodMo":60,
                  "collateralRequiredYn":false, "guarantorRequiredYn":false
                }
                """.formatted(code);
        MvcResult result = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn();
        return extractData(result).get("prodId").asLong();
    }

    private void activateProduct(Long prodId) throws Exception {
        mockMvc.perform(patch("/api/loan-products/{prodId}", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"prodStatusCd\":\"ACTIVE\" }"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    private Long createApplication(Long prodId) throws Exception {
        String body = """
                {
                  "customerId":5701, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":%d, "requestedPeriodMo":%d,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId, CONTRACTED_AMOUNT, PERIOD_MONTHS);
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
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
                  "cntrStartDate":"20380101"
                }
                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS, RATE_BPS);
        MvcResult result = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn();
        return extractData(result).get("cntrId").asLong();
    }
}
