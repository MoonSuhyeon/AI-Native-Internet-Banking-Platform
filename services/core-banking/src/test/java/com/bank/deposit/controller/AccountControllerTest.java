package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.enums.AccountStatus;
import com.bank.deposit.domain.enums.ProductType;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.security.AuthenticatedCustomerValidator;
import com.bank.deposit.service.AccountService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

@WebMvcTest(AccountController.class)
@Import(AuthenticatedCustomerValidator.class)
@DisplayName("AccountController")
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountService accountService;

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

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private Account account(String number, String customerId) {
        return Account.builder()
                .accountNumber(number)
                .customerId(customerId)
                .contractId(1L)
                .accountType(ProductType.DEPOSIT)
                .accountPassword("1234")
                .openedAt(java.time.LocalDate.of(2026, 1, 1))
                .balance(BigDecimal.valueOf(1_000_000))
                .build();
    }
}
