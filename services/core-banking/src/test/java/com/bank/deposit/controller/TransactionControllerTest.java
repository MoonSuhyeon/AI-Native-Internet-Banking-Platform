package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.security.AuthenticatedCustomerValidator;
import com.bank.deposit.domain.enums.DirectionType;
import com.bank.deposit.domain.enums.TransactionChannel;
import com.bank.deposit.domain.enums.TransactionType;
import com.bank.deposit.domain.enums.TransferType;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.AccountRepository;
import com.bank.deposit.security.FdsPreCheckGate;
import com.bank.deposit.security.TransferApprovalGate;
import com.bank.deposit.service.AccountService;
import com.bank.deposit.repository.TransactionRepository;
import com.bank.deposit.service.TransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 병합으로 Spring Security 가 클래스패스에 들어오면서 슬라이스에 기본 보안이 걸린다.
// 컨트롤러 슬라이스의 관심사는 매핑·검증이지 인증이 아니므로 필터를 끈다.
// (실제 인증은 게이트웨이가 JWT 로 처리하고, 백엔드 체인은 permitAll 이다.)
@AutoConfigureMockMvc(addFilters = false)
@Import(AuthenticatedCustomerValidator.class)
@WebMvcTest(TransactionController.class)
@DisplayName("TransactionController")
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransactionService transactionService;

    // 소유권 검증기가 계좌↔고객 매핑을 조회한다. 슬라이스에는 리포지토리가 없으므로 mock 으로 채운다.
    @MockBean
    private AccountRepository accountRepository;

    @MockBean
    private TransactionRepository transactionRepository;

    // 승인 게이트는 인증보안계로 나가는 Feign 호출을 들고 있다. 슬라이스에서는 mock 으로 둔다 —
    // 게이트 자체의 동작은 TransferApprovalGateTest 가 본다.
    @MockBean
    private TransferApprovalGate transferApprovalGate;

    // 이상거래 사전 점검도 슬라이스에서는 mock 이다. 게이트 자체 동작은
    // FdsPreCheckGateTest 가 본다 — 특히 탐지기 장애 시 금액 구간 분기.
    @MockBean
    private FdsPreCheckGate fdsPreCheckGate;

    // 이체 컨트롤러가 승인 토큰 대조용 계좌번호를 여기서 찾는다.
    @MockBean
    private AccountService accountService;

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

        givenAccountOwner(1L, "CUST-001");

        mockMvc.perform(post("/transactions/transfer")
                        .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST-001")
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

        givenAccountOwner(1L, "CUST-001");

        mockMvc.perform(post("/transactions/savings-payment")
                        .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST-001")
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

    // ── 소유권 검증 ──────────────────────────────────────────────────────────
    //
    // 이 세 가지가 빠져 있어서, 유효한 토큰만 있으면 남의 계좌에서 돈을 뺄 수 있었다.
    // 이체는 서비스 간 호출이 없는 고객 전용 경로라 신원을 필수로 본다(fail-closed).

    @Test
    @DisplayName("이체 — 인증 헤더가 없으면 403을 반환한다")
    void transferWithoutIdentity() throws Exception {
        mockMvc.perform(post("/transactions/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRANSFER_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("이체 — 출금 계좌가 남의 것이면 403을 반환한다")
    void transferFromOthersAccount() throws Exception {
        givenAccountOwner(1L, "CUST-999");

        mockMvc.perform(post("/transactions/transfer")
                        .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRANSFER_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("출금 — 남의 계좌면 403을 반환한다")
    void withdrawFromOthersAccount() throws Exception {
        givenAccountOwner(1L, "CUST-999");

        mockMvc.perform(post("/transactions/withdraw")
                        .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 1,
                                  "amount": 50000,
                                  "channelType": "MOBILE"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("거래 조회 — 남의 계좌 거래면 403을 반환한다")
    void listOthersAccountTransactions() throws Exception {
        givenAccountOwner(1L, "CUST-999");

        mockMvc.perform(get("/transactions")
                        .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST-001")
                        .param("accountId", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("거래 취소 — 남의 거래면 403을 반환한다")
    void cancelOthersTransaction() throws Exception {
        given(transactionRepository.findById(1L)).willReturn(Optional.of(
                transaction("TRF-001", TransactionType.TRANSFER, DirectionType.OUT)));
        givenAccountOwner(1L, "CUST-999");

        mockMvc.perform(patch("/transactions/1/cancel")
                        .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("서비스 간 호출(신원 헤더 없음)은 출금이 통과한다")
    void internalWithdrawPasses() throws Exception {
        given(transactionService.withdraw(eq(1L), any(), eq(TransactionChannel.MOBILE), any()))
                .willReturn(transaction("WTH-001", TransactionType.WITHDRAW, DirectionType.OUT));

        // payment 오케스트레이터가 이 경로를 신원 없이 부른다. 막으면 결제가 깨진다.
        mockMvc.perform(post("/transactions/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": 1,
                                  "amount": 50000,
                                  "channelType": "MOBILE"
                                }
                                """))
                .andExpect(status().isCreated());
    }

    private static final String TRANSFER_BODY = """
            {
              "fromAccountId": 1,
              "toAccountId": 2,
              "toAccountNo": "ACC-002",
              "amount": 100000,
              "transferType": "INTERNAL",
              "channelType": "MOBILE"
            }
            """;

    /** 계좌 소유자 조회를 mock 한다. */
    private void givenAccountOwner(Long accountId, String customerId) {
        Account account = Account.builder()
                .accountId(accountId).customerId(customerId).accountNumber("001-001-00000" + accountId).build();
        given(accountRepository.findById(accountId)).willReturn(Optional.of(account));
        given(accountService.findById(accountId)).willReturn(account);
    }

    private Transaction transaction(String number, TransactionType type, DirectionType direction) {
        return Transaction.builder()
                .transactionNumber(number)
                .accountId(1L)
                .transactionType(type)
                .directionType(direction)
                .amount(50000L)
                .balanceBefore(100000L)
                .balanceAfter(150000L)
                .channelType(TransactionChannel.INTERNET)
                .transactionAt(OffsetDateTime.now())
                .build();
    }
}
