package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.InterestHistory;
import com.bank.deposit.domain.enums.InterestReason;
import com.bank.deposit.domain.enums.TaxBenefitType;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.service.InterestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InterestController.class)
@DisplayName("InterestController")
class InterestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InterestService interestService;

    @Test
    @DisplayName("이자를 계산하고 지급한다")
    void calculate() throws Exception {
        given(interestService.payInterest(eq(1L), eq(10L), any(), any(), any(), any(),
                eq(TaxBenefitType.GENERAL), any(), eq(InterestReason.REGULAR_INTEREST),
                eq("20260101"), eq("20260331")))
                .willReturn(history());

        mockMvc.perform(post("/interests/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contractId": 1,
                                  "accountId": 10,
                                  "interestBeforeTax": 100000,
                                  "interestTaxAmount": 15000,
                                  "localIncomeTaxAmount": 1500,
                                  "appliedInterestRate": 3.5,
                                  "taxBenefitType": "GENERAL",
                                  "appliedTaxRate": 0.154,
                                  "interestReason": "REGULAR_INTEREST",
                                  "interestCalculationStartDate": "20260101",
                                  "interestCalculationEndDate": "20260331"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.interestAfterTax").value(83500));
    }

    @Test
    @DisplayName("계약 ID로 이자 이력을 조회한다")
    void list() throws Exception {
        given(interestService.findByContract(1L)).willReturn(List.of(history()));

        mockMvc.perform(get("/interests").param("contractId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("이자 이력 단건을 조회한다")
    void getById() throws Exception {
        given(interestService.findById(1L)).willReturn(history());

        mockMvc.perform(get("/interests/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractId").value(1));
    }

    @Test
    @DisplayName("존재하지 않는 이자 이력 조회 시 404를 반환한다")
    void getNotFound() throws Exception {
        given(interestService.findById(999L))
                .willThrow(new BusinessException(ErrorCode.NOT_FOUND, "이자 이력을 찾을 수 없습니다."));

        mockMvc.perform(get("/interests/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("계약 하위 경로로 이자 이력을 조회한다")
    void listByContract() throws Exception {
        given(interestService.findByContract(1L)).willReturn(List.of(history()));

        mockMvc.perform(get("/contracts/1/interests"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    private InterestHistory history() {
        return InterestHistory.builder()
                .contractId(1L)
                .accountId(10L)
                .appliedInterestRate(BigDecimal.valueOf(3.5))
                .taxBenefitType(TaxBenefitType.GENERAL)
                .appliedTaxRate(BigDecimal.valueOf(0.154))
                .interestBeforeTax(BigDecimal.valueOf(100_000))
                .interestTaxAmount(BigDecimal.valueOf(15_000))
                .localIncomeTaxAmount(BigDecimal.valueOf(1_500))
                .interestAfterTax(BigDecimal.valueOf(83_500))
                .interestAmount(BigDecimal.valueOf(83_500))
                .interestReason(InterestReason.REGULAR_INTEREST)
                .interestPaidAt(OffsetDateTime.now())
                .build();
    }
}
