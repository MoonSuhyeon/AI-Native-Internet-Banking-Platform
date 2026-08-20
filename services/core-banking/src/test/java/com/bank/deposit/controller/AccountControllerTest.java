package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.enums.AccountStatus;
import com.bank.deposit.domain.enums.ProductType;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.AccountRepository;
import com.bank.deposit.repository.TransactionRepository;
import com.bank.deposit.security.AuthenticatedCustomerValidator;
import com.bank.deposit.service.AccountService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 병합으로 Spring Security 가 클래스패스에 들어오면서 슬라이스에 기본 보안이 걸린다.
// 컨트롤러 슬라이스의 관심사는 매핑·검증이지 인증이 아니므로 필터를 끈다.
// (실제 인증은 게이트웨이가 JWT 로 처리하고, 백엔드 체인은 permitAll 이다.)
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(AccountController.class)
@Import(AuthenticatedCustomerValidator.class)
@DisplayName("AccountController")
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountService accountService;

    @MockBean
    private com.bank.deposit.service.EarlyTerminationService earlyTerminationService;

    // 소유권 검증기가 계좌↔고객 매핑을 조회한다. 슬라이스에는 리포지토리가 없으므로 mock 으로 채운다.
    @MockBean
    private AccountRepository accountRepository;

    @MockBean
    private TransactionRepository transactionRepository;

    @Test
    @DisplayName("고객 ID로 계좌 목록을 조회한다")
    void list() throws Exception {
        given(accountService.findByCustomer("CUST-001")).willReturn(List.of(
                account("ACC-001", "CUST-001"),
                account("ACC-002", "CUST-001")
        ));

        mockMvc.perform(get("/accounts")
                        .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST-001")
                        .param("customerId", "CUST-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].accountNumber").value("ACC-001"))
                .andExpect(jsonPath("$[1].accountNumber").value("ACC-002"));
    }

    @Test
    @DisplayName("계좌 단건을 조회한다")
    void getById() throws Exception {
        given(accountService.findById(1L)).willReturn(account("ACC-001", "CUST-001"));

        mockMvc.perform(get("/accounts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("ACC-001"))
                .andExpect(jsonPath("$.customerId").value("CUST-001"));
    }

    @Test
    @DisplayName("존재하지 않는 계좌 조회 시 404를 반환한다")
    void getNotFound() throws Exception {
        given(accountService.findById(999L))
                .willThrow(new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        mockMvc.perform(get("/accounts/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("계좌를 생성하면 201을 반환한다")
    void create() throws Exception {
        given(accountService.create(eq("CUST-001"), eq(1L), eq(ProductType.DEPOSIT),
                isNull(), eq("내 예금"), eq("1234")))
                .willReturn(account("ACC-NEW", "CUST-001"));

        mockMvc.perform(post("/accounts")
                        .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "CUST-001",
                                  "contractId": 1,
                                  "accountType": "DEPOSIT",
                                  "accountAlias": "내 예금",
                                  "accountPassword": "1234"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNumber").value("ACC-NEW"));
    }

    @Test
    @DisplayName("다른 고객 계좌 목록 조회 시 403을 반환한다")
    void listCustomerMismatch() throws Exception {
        mockMvc.perform(get("/accounts")
                        .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST-999")
                        .param("customerId", "CUST-001"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("계좌 상태를 변경한다")
    void changeStatus() throws Exception {
        Account dormant = Account.builder()
                .accountNumber("ACC-001")
                .customerId("CUST-001")
                .contractId(1L)
                .accountType(ProductType.DEPOSIT)
                .accountPassword("1234")
                .openedAt(java.time.LocalDate.of(2026, 1, 1))
                .accountStatus(AccountStatus.DORMANT)
                .build();
        given(accountService.changeStatus(1L, AccountStatus.DORMANT)).willReturn(dormant);

        mockMvc.perform(patch("/accounts/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountStatus\": \"DORMANT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountStatus").value("DORMANT"));
    }

    @Test
    @DisplayName("계좌 별칭을 변경한다")
    void updateAlias() throws Exception {
        Account acc = account("ACC-001", "CUST-001");
        given(accountService.updateAlias(1L, "급여 통장")).willReturn(acc);

        mockMvc.perform(patch("/accounts/1/alias")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountAlias\": \"급여 통장\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("계좌 출금 한도를 변경한다")
    void updateLimits() throws Exception {
        Account acc = account("ACC-001", "CUST-001");
        given(accountService.updateLimits(eq(1L), any(), eq(5), any())).willReturn(acc);

        mockMvc.perform(patch("/accounts/1/limits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dailyWithdrawLimit": 3000000,
                                  "dailyWithdrawCountLimit": 5,
                                  "atmWithdrawLimit": 1000000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("ACC-001"));
    }

    @Test
    @DisplayName("인증 헤더 없이 계좌 생성 시 403을 반환한다")
    void createWithoutAuthHeader() throws Exception {
        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId": "CUST-001",
                                  "contractId": 1,
                                  "accountType": "DEPOSIT",
                                  "accountAlias": "내 예금",
                                  "accountPassword": "1234"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("계좌번호로 계좌를 조회한다")
    void getByNumber() throws Exception {
        given(accountService.findByAccountNumber("ACC-001"))
                .willReturn(account("ACC-001", "CUST-001"));

        mockMvc.perform(get("/accounts/by-number/ACC-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value("ACC-001"))
                .andExpect(jsonPath("$.customerId").value("CUST-001"));
    }

    @Test
    @DisplayName("존재하지 않는 계좌번호 조회 시 404를 반환한다")
    void getByNumberNotFound() throws Exception {
        given(accountService.findByAccountNumber("NONE-999"))
                .willThrow(new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        mockMvc.perform(get("/accounts/by-number/NONE-999"))
                .andExpect(status().isNotFound());
    }

    // ── 소유권 검증 ──────────────────────────────────────────────────────────
    //
    // 계좌 단건 조회·상태·한도·별칭 변경에 검증이 없어서, 남의 계좌 한도를 올리거나
    // 상태를 바꿀 수 있었다(IDOR). 목록·생성 두 곳에만 검증이 붙어 있었다.

    @Test
    @DisplayName("계좌 단건 조회 — 남의 계좌면 403을 반환한다")
    void getOthersAccount() throws Exception {
        givenAccountOwner(1L, "CUST-999");

        mockMvc.perform(get("/accounts/1")
                        .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST-001"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("출금 한도 변경 — 남의 계좌면 403을 반환한다")
    void updateOthersLimits() throws Exception {
        givenAccountOwner(1L, "CUST-999");

        mockMvc.perform(patch("/accounts/1/limits")
                        .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dailyWithdrawLimit": 100000000}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("계좌 상태 변경 — 남의 계좌면 403을 반환한다")
    void changeOthersStatus() throws Exception {
        givenAccountOwner(1L, "CUST-999");

        mockMvc.perform(patch("/accounts/1/status")
                        .header(AuthenticatedCustomerValidator.CUSTOMER_ID_HEADER, "CUST-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountStatus": "ACTIVE"}
                                """))
                .andExpect(status().isForbidden());
    }

    private void givenAccountOwner(Long accountId, String customerId) {
        given(accountRepository.findById(accountId)).willReturn(java.util.Optional.of(
                Account.builder().accountId(accountId).customerId(customerId).build()));
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private Account account(String number, String customerId) {
        return Account.builder()
                .accountNumber(number)
                .customerId(customerId)
                .contractId(1L)
                .accountType(ProductType.DEPOSIT)
                .accountPassword("1234")
                .openedAt(java.time.LocalDate.of(2026, 1, 1))
                .balance(1000000L)
                .build();
    }
}
