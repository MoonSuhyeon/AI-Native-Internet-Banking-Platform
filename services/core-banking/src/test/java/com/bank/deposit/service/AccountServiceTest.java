package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.enums.AccountStatus;
import com.bank.deposit.domain.enums.ProductType;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccountService")
class AccountServiceTest {

    private AccountService accountService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        accountService = new AccountService(accountRepository, passwordEncoder, clock);
    }

    @Nested
    @DisplayName("고객별 계좌 목록 조회")
    class FindByCustomer {

        @Test
        @DisplayName("고객 ID로 계좌 목록을 조회한다")
        void findByCustomer() {
            given(accountRepository.findByCustomerId("CUST-001"))
                    .willReturn(List.of(account("ACC-001", "CUST-001")));

            List<Account> result = accountService.findByCustomer("CUST-001");

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getCustomerId()).isEqualTo("CUST-001");
            then(accountRepository).should().findByCustomerId("CUST-001");
        }

        @Test
        @DisplayName("계좌가 없는 고객은 빈 리스트를 반환한다")
        void findByCustomerEmpty() {
            given(accountRepository.findByCustomerId("CUST-NONE"))
                    .willReturn(List.of());

            List<Account> result = accountService.findByCustomer("CUST-NONE");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("계좌 단건 조회")
    class FindById {

        @Test
        @DisplayName("존재하는 계좌를 조회한다")
        void findById() {
            given(accountRepository.findById(1L))
                    .willReturn(Optional.of(account("ACC-001", "CUST-001")));

            Account result = accountService.findById(1L);

            assertThat(result.getAccountNumber()).isEqualTo("ACC-001");
        }

        @Test
        @DisplayName("존재하지 않는 계좌는 예외가 발생한다")
        void findByIdNotFound() {
            given(accountRepository.findById(1L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.findById(1L))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("활성 계좌 조회")
    class FindActive {

        @Test
        @DisplayName("활성 계좌를 정상 조회한다")
        void findActive() {
            given(accountRepository.findById(1L))
                    .willReturn(Optional.of(account("ACC-001", "CUST-001")));

            Account result = accountService.findActive(1L);

            assertThat(result.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("비활성 계좌 조회 시 예외가 발생한다")
        void findActiveButClosed() {
            Account closed = Account.builder()
                    .accountNumber("ACC-CLOSED")
                    .customerId("CUST-001")
                    .contractId(1L)
                    .accountType(ProductType.DEPOSIT)
                    .accountPassword("1234")
                    .openedAt(java.time.LocalDate.of(2026, 1, 1))
                    .accountStatus(AccountStatus.CLOSED)
                    .build();
            given(accountRepository.findById(1L)).willReturn(Optional.of(closed));

            assertThatThrownBy(() -> accountService.findActive(1L))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("활성");
        }
    }

    @Nested
    @DisplayName("계좌 생성")
    class Create {

        @Test
        @DisplayName("계약에 계좌가 없으면 새 계좌를 생성한다")
        void create() {
            given(accountRepository.findByContractId(1L)).willReturn(Optional.empty());
            given(accountRepository.nextAccountNumberSequenceValue()).willReturn(100000000001L);
            given(passwordEncoder.encode("1234")).willReturn("$2a$10$abcdefghijklmnopqrstuu9QwmFAnNd0x5QyZ9LhQOW7bpcE6Pj2a");
            given(accountRepository.save(any(Account.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            Account result = accountService.create("CUST-001", 1L, ProductType.DEPOSIT,
                    null, "내 예금", "1234");

            assertThat(result.getCustomerId()).isEqualTo("CUST-001");
            assertThat(result.getContractId()).isEqualTo(1L);
            assertThat(result.getAccountNumber()).startsWith("001-");
        }

        @Test
        @DisplayName("이미 계좌가 있는 계약이면 예외가 발생한다")
        void createDuplicateAccount() {
            given(accountRepository.findByContractId(1L))
                    .willReturn(Optional.of(account("ACC-001", "CUST-001")));

            assertThatThrownBy(() -> accountService.create("CUST-001", 1L, ProductType.DEPOSIT,
                    null, "내 예금", "1234"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("계좌 상태 변경")
    class ChangeStatus {

        @Test
        @DisplayName("계좌 상태를 DORMANT으로 변경한다")
        void changeStatus() {
            Account acc = account("ACC-001", "CUST-001");
            given(accountRepository.findById(1L)).willReturn(Optional.of(acc));

            Account result = accountService.changeStatus(1L, AccountStatus.DORMANT);

            assertThat(result.getAccountStatus()).isEqualTo(AccountStatus.DORMANT);
        }
    }

    @Nested
    @DisplayName("한도 변경")
    class UpdateLimits {

        @Test
        @DisplayName("출금 한도를 변경한다")
        void updateLimits() {
            Account acc = account("ACC-001", "CUST-001");
            given(accountRepository.findById(1L)).willReturn(Optional.of(acc));

            Account result = accountService.updateLimits(1L,
                    3000000L, 5, 1000000L);

            assertThat(result.getDailyWithdrawLimit()).isEqualTo(3000000L);
        }
    }

    @Test
    @DisplayName("계좌 별칭을 변경한다")
    void updateAlias() {
        Account acc = account("ACC-001", "CUST-001");
        given(accountRepository.findById(1L)).willReturn(Optional.of(acc));

        Account result = accountService.updateAlias(1L, "급여 통장");

        assertThat(result.getAccountAlias()).isEqualTo("급여 통장");
    }

    @Nested
    @DisplayName("계좌번호로 계좌 조회")
    class FindByAccountNumber {

        @Test
        @DisplayName("계좌번호로 계좌를 조회한다")
        void findByAccountNumber() {
            given(accountRepository.findByAccountNumber("ACC-001"))
                    .willReturn(Optional.of(account("ACC-001", "CUST-001")));

            Account result = accountService.findByAccountNumber("ACC-001");

            assertThat(result.getAccountNumber()).isEqualTo("ACC-001");
            assertThat(result.getCustomerId()).isEqualTo("CUST-001");
        }

        @Test
        @DisplayName("존재하지 않는 계좌번호 조회 시 예외가 발생한다")
        void findByAccountNumberNotFound() {
            given(accountRepository.findByAccountNumber("NONE-999"))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> accountService.findByAccountNumber("NONE-999"))
                    .isInstanceOf(BusinessException.class);
        }
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
                .build();
    }
}
