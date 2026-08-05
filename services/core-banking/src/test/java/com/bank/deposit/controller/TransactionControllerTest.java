package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.enums.DirectionType;
import com.bank.deposit.domain.enums.TransactionChannel;
import com.bank.deposit.domain.enums.TransactionType;
import com.bank.deposit.domain.enums.TransferType;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.service.TransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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

@WebMvcTest(TransactionController.class)
@DisplayName("TransactionController")
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransactionService transactionService;

    @Test
    @DisplayName("계좌 거래 목록을 조회한다")
    void list() throws Exception {
        given(transactionService.findByAccount(eq(1L), isNull(), isNull(), any()))
                .willReturn(new PageImpl<>(List.of(transaction("DEP-001", TransactionType.DEPOSIT, DirectionType.IN)),
                        PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/transactions").param("accountId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].transactionNumber").value("DEP-001"));
    }

    @Test
    @DisplayName("거래 단건을 조회한다")
    void getById() throws Exception {
        given(transactionService.findById(1L))
                .willReturn(transaction("DEP-001", TransactionType.DEPOSIT, DirectionType.IN));

        mockMvc.perform(get("/transactions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionNumber").value("DEP-001"));
    }

    @Test
    @DisplayName("존재하지 않는 거래 조회 시 404를 반환한다")
    void getNotFound() throws Exception {
        given(transactionService.findById(999L))
                .willThrow(new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND));

        mockMvc.perform(get("/transactions/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("입금 거래를 생성한다")
    void deposit() throws Exception {
        given(transactionService.deposit(eq(1L), any(), eq(TransactionChannel.INTERNET),
                eq("입금"), eq("CUST-001"), eq("김수신")))
                .willReturn(transaction("DEP-001", TransactionType.DEPOSIT, DirectionType.IN));

        mockMvc.perform(post("/transactions/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 1,
                                  "amount": 50000,
                                  "channelType": "INTERNET",
                                  "transactionMemo": "입금",
                                  "depositorCustomerId": "CUST-001",
                                  "depositorName": "김수신"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionType").value("DEPOSIT"));
    }

    @Test
    @DisplayName("출금 거래를 생성한다")
    void withdraw() throws Exception {
        given(transactionService.withdraw(eq(1L), any(), eq(TransactionChannel.ATM), eq("출금")))
                .willReturn(transaction("WDR-001", TransactionType.WITHDRAW, DirectionType.OUT));

        mockMvc.perform(post("/transactions/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 1,
                                  "amount": 30000,
                                  "channelType": "ATM",
                                  "transactionMemo": "출금"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.directionType").value("OUT"));
    }

    @Test
    @DisplayName("이체 거래를 생성한다")
    void transfer() throws Exception {
        given(transactionService.transfer(eq(1L), eq(2L), eq("ACC-002"), any(),
                eq(TransferType.INTERNAL), eq("001"), eq("우리은행"), eq("김수신"),
                eq(TransactionChannel.MOBILE), eq("이체"), any()))
                .willReturn(transaction("TRF-001", TransactionType.TRANSFER, DirectionType.OUT));

        mockMvc.perform(post("/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fromAccountId": 1,
                                  "toAccountId": 2,
                                  "toAccountNo": "ACC-002",
                                  "amount": 100000,
                                  "transferType": "INTERNAL",
                                  "counterpartyBankCode": "001",
                                  "counterpartyBankName": "우리은행",
                                  "counterpartyName": "김수신",
                                  "channelType": "MOBILE",
                                  "transactionMemo": "이체"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionType").value("TRANSFER"));
    }

    @Test
    @DisplayName("적금 납입 거래를 생성한다")
    void savingsPayment() throws Exception {
        given(transactionService.savingsPayment(eq(1L), eq(10L), any(), eq(3), eq(TransactionChannel.MOBILE)))
                .willReturn(transaction("SAV-001", TransactionType.SAVINGS_PAYMENT, DirectionType.IN));

        mockMvc.perform(post("/transactions/savings-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 1,
                                  "contractId": 10,
                                  "amount": 100000,
                                  "paymentRound": 3,
                                  "channelType": "MOBILE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionType").value("SAVINGS_PAYMENT"));
    }

    @Test
    @DisplayName("거래를 취소한다")
    void cancel() throws Exception {
        given(transactionService.reversal(1L, null))
                .willReturn(transaction("REV-001", TransactionType.REVERSAL, DirectionType.OUT));

        mockMvc.perform(patch("/transactions/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionType").value("REVERSAL"));
    }

    @Test
    @DisplayName("이체 요청에 fromAccountId가 없으면 400을 반환한다")
    void transferMissingFromAccountId() throws Exception {
        mockMvc.perform(post("/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "toAccountNo": "ACC-002",
                                  "amount": 100000,
                                  "transferType": "EXTERNAL"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이체 금액이 0이면 400을 반환한다")
    void transferZeroAmount() throws Exception {
        mockMvc.perform(post("/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fromAccountId": 1,
                                  "toAccountNo": "ACC-002",
                                  "amount": 0,
                                  "transferType": "EXTERNAL"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("이체 금액이 음수이면 400을 반환한다")
    void transferNegativeAmount() throws Exception {
        mockMvc.perform(post("/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fromAccountId": 1,
                                  "toAccountNo": "ACC-002",
                                  "amount": -1000,
                                  "transferType": "EXTERNAL"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("존재하지 않는 거래 취소 시 404를 반환한다")
    void cancelNotFound() throws Exception {
        given(transactionService.reversal(999L, null))
                .willThrow(new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND));

        mockMvc.perform(patch("/transactions/999/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("서비스 예외 발생 시 이체 API가 4xx를 반환한다")
    void transferServiceException() throws Exception {
        given(transactionService.transfer(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willThrow(new BusinessException(ErrorCode.INSUFFICIENT_BALANCE));

        mockMvc.perform(post("/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fromAccountId": 1,
                                  "toAccountNo": "ACC-002",
                                  "amount": 999999999,
                                  "transferType": "EXTERNAL"
                                }
                                """))
                .andExpect(status().is4xxClientError());
    }

    private Transaction transaction(String number, TransactionType type, DirectionType direction) {
        return Transaction.builder()
                .transactionNumber(number)
                .accountId(1L)
                .transactionType(type)
                .directionType(direction)
                .amount(BigDecimal.valueOf(50_000))
                .balanceBefore(BigDecimal.valueOf(100_000))
                .balanceAfter(BigDecimal.valueOf(150_000))
                .channelType(TransactionChannel.INTERNET)
                .transactionAt(OffsetDateTime.now())
                .build();
    }
}
