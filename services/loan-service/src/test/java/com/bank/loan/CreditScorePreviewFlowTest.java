package com.bank.loan;

import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 한도조회(가심사 preview) 통합 테스트.
 *
 * 시나리오:
 *   10) PASS — 소득 충분(EMPLOYEE 60M) 200 + score/grade/한도/엔진버전 반환, DB 적재 없음
 *   11) REJECT — 소득 미제출 → 200 + decision=REJECT, INCOME_NOT_PROVIDED (한도조회는 거절도 정상 응답)
 *   12) REJECT — 신청금액이 연소득 5배 초과 → 200 + OVER_INCOME_5X
 *   13) consentYn=N 미동의 → 400
 *   14) consentYn 누락 → 400
 *   15) 필수 필드(requestedAmount) 누락 → 400
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CreditScorePreviewFlowTest extends AbstractLoanIntegrationTest {

    @Test @Order(10)
    void PASS_정규직_60M_충분한_소득() throws Exception {
        String body = """
                {
                  "customerId":7001, "loanTypeCd":"CREDIT",
                  "requestedAmount":30000000, "requestedPeriodMo":36,
                  "loanPurposeCd":"LIVING",
                  "employmentTypeCd":"EMPLOYEE", "estimatedIncomeAmt":60000000,
                  "consentYn":true
                }
                """;
        mockMvc.perform(post("/api/credit-score/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("PASS"))
                .andExpect(jsonPath("$.data.score").value(720))
                .andExpect(jsonPath("$.data.grade").value("BBB"))
                // 한도: min(requested 30M, income×5=300M) = 30M
                .andExpect(jsonPath("$.data.estimatedLimitAmt").value(30000000))
                .andExpect(jsonPath("$.data.engineVersion").value("MOCK-v1"))
                .andExpect(jsonPath("$.data.rejectReasonCd").doesNotExist());
    }

    @Test @Order(11)
    void REJECT_소득_미제출_INCOME_NOT_PROVIDED() throws Exception {
        String body = """
                {
                  "customerId":7002, "loanTypeCd":"CREDIT",
                  "requestedAmount":30000000, "requestedPeriodMo":36,
                  "consentYn":true
                }
                """;
        mockMvc.perform(post("/api/credit-score/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("REJECT"))
                .andExpect(jsonPath("$.data.rejectReasonCd").value("INCOME_NOT_PROVIDED"))
                .andExpect(jsonPath("$.data.estimatedLimitAmt").doesNotExist())
                .andExpect(jsonPath("$.data.score").doesNotExist());
    }

    @Test @Order(12)
    void REJECT_신청금액_연소득_5배_초과() throws Exception {
        // income 10M × 5 = 50M, requested 60M → REJECT
        String body = """
                {
                  "customerId":7003, "loanTypeCd":"CREDIT",
                  "requestedAmount":60000000, "requestedPeriodMo":36,
                  "employmentTypeCd":"EMPLOYEE", "estimatedIncomeAmt":10000000,
                  "consentYn":true
                }
                """;
        mockMvc.perform(post("/api/credit-score/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("REJECT"))
                .andExpect(jsonPath("$.data.rejectReasonCd").value("OVER_INCOME_5X"));
    }

    @Test @Order(13)
    void consentYn_N_미동의_400() throws Exception {
        String body = """
                {
                  "customerId":7004, "loanTypeCd":"CREDIT",
                  "requestedAmount":30000000, "requestedPeriodMo":36,
                  "employmentTypeCd":"EMPLOYEE", "estimatedIncomeAmt":60000000,
                  "consentYn":false
                }
                """;
        mockMvc.perform(post("/api/credit-score/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(14)
    void consentYn_누락_400() throws Exception {
        String body = """
                {
                  "customerId":7005, "loanTypeCd":"CREDIT",
                  "requestedAmount":30000000, "requestedPeriodMo":36
                }
                """;
        mockMvc.perform(post("/api/credit-score/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(15)
    void 필수필드_requestedAmount_누락_400() throws Exception {
        String body = """
                {
                  "customerId":7006, "loanTypeCd":"CREDIT",
                  "requestedPeriodMo":36,
                  "consentYn":true
                }
                """;
        mockMvc.perform(post("/api/credit-score/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }
}
