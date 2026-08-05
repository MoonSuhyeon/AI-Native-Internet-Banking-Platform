package com.bank.deposit.controller;

import com.bank.deposit.dto.response.CashFlowSummary;
import com.bank.deposit.dto.response.ProductRecommendResponse;
import com.bank.deposit.dto.response.RecommendedProduct;
import com.bank.deposit.security.AuthenticatedCustomerValidator;
import com.bank.deposit.service.CashflowBasedRecommendService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 병합으로 Spring Security 가 클래스패스에 들어오면서 슬라이스에 기본 보안이 걸린다.
// 컨트롤러 슬라이스의 관심사는 매핑·검증이지 인증이 아니므로 필터를 끈다.
// (실제 인증은 게이트웨이가 JWT 로 처리하고, 백엔드 체인은 permitAll 이다.)
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(RecommendAgentController.class)
@Import(AuthenticatedCustomerValidator.class)
@DisplayName("RecommendAgentController")
class RecommendAgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CashflowBasedRecommendService cashflowBasedRecommendService;

    @Nested
    @DisplayName("GET /products/recommend-agent")
    class Recommend {

        @Test
        @DisplayName("정상 추천 - 200과 recommendations 목록을 반환한다")
        void success() throws Exception {
            given(cashflowBasedRecommendService.recommend("CUST001", 3, null))
                    .willReturn(response("CUST001", 3, List.of(
                            recommendedProduct(1L, "자유적금", "SAVINGS", new BigDecimal("3.50")),
                            recommendedProduct(2L, "정기예금", "DEPOSIT", new BigDecimal("3.20"))
                    )));

            mockMvc.perform(get("/products/recommend-agent")
                            .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST001")
                            .param("customerId", "CUST001")
                            .param("periodMonth", "3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customerId").value("CUST001"))
                    .andExpect(jsonPath("$.analysisPeriodMonth").value(3))
                    .andExpect(jsonPath("$.cashFlow.totalInflow").value(4500000))
                    .andExpect(jsonPath("$.cashFlow.estimatedSavingsAmount").value(500000))
                    .andExpect(jsonPath("$.cashFlow.estimatedSavingsAmount").value(greaterThan(0)))
                    .andExpect(jsonPath("$.recommendations", hasSize(2)))
                    .andExpect(jsonPath("$.recommendations[0].productName").value("자유적금"))
                    .andExpect(jsonPath("$.recommendations[0].bestRate").value(3.50))
                    .andExpect(jsonPath("$.recommendations[0].reason").value(not(emptyString())))
                    .andExpect(jsonPath("$.recommendations[0].reason").value(containsString("500,000")))
                    .andExpect(jsonPath("$.recommendations[0].reason").value(containsString("3.50")));
        }

        @Test
        @DisplayName("periodMonth 생략 시 기본값 3이 적용된다")
        void defaultPeriodMonth() throws Exception {
            given(cashflowBasedRecommendService.recommend("CUST001", 3, null))
                    .willReturn(response("CUST001", 3, List.of()));

            mockMvc.perform(get("/products/recommend-agent")
                            .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST001")
                            .param("customerId", "CUST001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.analysisPeriodMonth").value(3))
                    .andExpect(jsonPath("$.recommendations", hasSize(0)));
        }

        @Test
        @DisplayName("periodMonth=0 요청 시 400을 반환한다")
        void periodMonthZero() throws Exception {
            mockMvc.perform(get("/products/recommend-agent")
                            .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST001")
                            .param("customerId", "CUST001")
                            .param("periodMonth", "0"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("periodMonth=-1 요청 시 400을 반환한다")
        void periodMonthNegative() throws Exception {
            mockMvc.perform(get("/products/recommend-agent")
                            .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST001")
                            .param("customerId", "CUST001")
                            .param("periodMonth", "-1"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("customerId 누락 시 400을 반환한다")
        void missingCustomerId() throws Exception {
            mockMvc.perform(get("/products/recommend-agent")
                            .param("periodMonth", "3"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("인증 고객과 요청 고객이 다르면 403을 반환한다")
        void customerMismatch() throws Exception {
            mockMvc.perform(get("/products/recommend-agent")
                            .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST999")
                            .param("customerId", "CUST001")
                            .param("periodMonth", "3"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("서비스 예외 발생 시 500을 반환한다")
        void serviceException() throws Exception {
            given(cashflowBasedRecommendService.recommend("CUST001", 3, null))
                    .willThrow(new RuntimeException("예기치 않은 오류"));

            mockMvc.perform(get("/products/recommend-agent")
                            .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST001")
                            .param("customerId", "CUST001")
                            .param("periodMonth", "3"))
                    .andExpect(status().isInternalServerError());
        }
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private ProductRecommendResponse response(String customerId, int periodMonth,
                                               List<RecommendedProduct> products) {
        CashFlowSummary cashFlow = new CashFlowSummary(
                new BigDecimal("4500000"),
                new BigDecimal("3000000"),
                new BigDecimal("1500000"),
                new BigDecimal("500000")
        );
        return new ProductRecommendResponse(customerId, periodMonth, cashFlow, products);
    }

    private RecommendedProduct recommendedProduct(Long id, String name, String type, BigDecimal bestRate) {
        return new RecommendedProduct(
                id, name, type,
                new BigDecimal("3.00"), bestRate,
                new BigDecimal("10000"), new BigDecimal("1000000"),
                6, 36,
                "월 평균 저축 가능 금액(500,000원) 기반 추천. 연 " + bestRate.toPlainString() + "% 금리 적용."
        );
    }
}
