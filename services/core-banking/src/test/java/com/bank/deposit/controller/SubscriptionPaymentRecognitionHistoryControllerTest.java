package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.SubscriptionPaymentRecognitionHistory;
import com.bank.deposit.domain.enums.RecognitionStatus;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.service.SubscriptionPaymentRecognitionHistoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SubscriptionPaymentRecognitionHistoryController.class)
@DisplayName("SubscriptionPaymentRecognitionHistoryController")
class SubscriptionPaymentRecognitionHistoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SubscriptionPaymentRecognitionHistoryService service;

    @Test
    @DisplayName("계약 ID로 청약 납입 인정 이력을 조회한다")
    void listByContractId() throws Exception {
        given(service.findByContractId(1L)).willReturn(List.of(history(RecognitionStatus.RECOGNIZED)));

        mockMvc.perform(get("/subscription-payment-histories").param("contractId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].recognitionStatus").value("RECOGNIZED"));
    }

    @Test
    @DisplayName("상태로 청약 납입 인정 이력을 필터링한다")
    void listByContractIdAndStatus() throws Exception {
        given(service.findByContractIdAndStatus(1L, RecognitionStatus.PENDING))
                .willReturn(List.of(history(RecognitionStatus.PENDING)));

        mockMvc.perform(get("/subscription-payment-histories")
                        .param("contractId", "1")
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].recognitionStatus").value("PENDING"));
    }

    @Test
    @DisplayName("청약 납입 인정 이력 단건을 조회한다")
    void getById() throws Exception {
        given(service.findById(1L)).willReturn(history(RecognitionStatus.RECOGNIZED));

        mockMvc.perform(get("/subscription-payment-histories/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractId").value(1));
    }

    @Test
    @DisplayName("존재하지 않는 납입 인정 이력 조회 시 404를 반환한다")
    void getNotFound() throws Exception {
        given(service.findById(999L))
                .willThrow(new BusinessException(ErrorCode.NOT_FOUND, "청약 납입 인정 이력을 찾을 수 없습니다."));

        mockMvc.perform(get("/subscription-payment-histories/999"))
                .andExpect(status().isNotFound());
    }

    private SubscriptionPaymentRecognitionHistory history(RecognitionStatus status) {
        return SubscriptionPaymentRecognitionHistory.builder()
                .contractId(1L)
                .paymentAmount(BigDecimal.valueOf(100_000))
                .recognizedAmount(BigDecimal.valueOf(100_000))
                .paymentMonth("202605")
                .recognitionStatus(status)
                .build();
    }
}
