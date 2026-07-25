package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Contract;
import com.bank.deposit.domain.entity.Product;
import com.bank.deposit.domain.enums.*;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractService")
class ContractServiceTest {

    @InjectMocks
    private ContractService contractService;

    @Mock private ContractRepository contractRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ContractAppliedRateRepository appliedRateRepository;
    @Mock private ContractSpecialTermAgreementRepository agreementRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private Clock clock;

    @BeforeEach
    void setUpClock() {
        org.mockito.Mockito.lenient().when(clock.instant()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        org.mockito.Mockito.lenient().when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
    }

    @Nested
    @DisplayName("계약 목록 조회")
    class FindAll {

        @Test
        @DisplayName("필터 없이 전체 계약을 조회한다")
        void findAllNoFilter() {
            given(contractRepository.findAll()).willReturn(List.of(contract("CUST-001")));

            List<Contract> result = contractService.findAll(null, null);

            assertThat(result).hasSize(1);
            then(contractRepository).should().findAll();
        }

        @Test
        @DisplayName("고객 ID와 상태로 계약을 필터링한다")
        void findAllByCustomerAndStatus() {
            given(contractRepository.findByCustomerIdAndContractStatus("CUST-001", ContractStatus.ACTIVE))
                    .willReturn(List.of(contract("CUST-001")));

            List<Contract> result = contractService.findAll("CUST-001", ContractStatus.ACTIVE);

            assertThat(result).hasSize(1);
            then(contractRepository).should()
                    .findByCustomerIdAndContractStatus("CUST-001", ContractStatus.ACTIVE);
        }

        @Test
        @DisplayName("고객 ID만으로 계약을 조회한다")
        void findAllByCustomer() {
            given(contractRepository.findByCustomerId("CUST-001"))
                    .willReturn(List.of(contract("CUST-001")));

            List<Contract> result = contractService.findAll("CUST-001", null);

            assertThat(result).hasSize(1);
        }
    }

    @Nested
    @DisplayName("계약 단건 조회")
    class FindById {

        @Test
        @DisplayName("존재하는 계약을 조회한다")
        void findById() {
            given(contractRepository.findById(1L)).willReturn(Optional.of(contract("CUST-001")));

            Contract result = contractService.findById(1L);

            assertThat(result.getCustomerId()).isEqualTo("CUST-001");
        }

        @Test
        @DisplayName("존재하지 않으면 예외가 발생한다")
        void findByIdNotFound() {
            given(contractRepository.findById(1L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> contractService.findById(1L))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("계약 생성")
    class CreateContract {

        @Test
        @DisplayName("판매 중인 상품으로 계약을 생성한다")
        void createContract() {
            given(productRepository.findById(1L)).willReturn(Optional.of(sellingProduct()));
            given(contractRepository.save(any(Contract.class))).willAnswer(inv -> inv.getArgument(0));
            given(accountRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(passwordEncoder.encode(anyString())).willReturn("encoded-pw");

            Contract result = contractService.createContract(
                    "CUST-001", 1L, BigDecimal.valueOf(1_000_000), 12,
                    JoinChannel.WEB, null, null, null, false, false, null,
                    null, null, null, null, "1234");

            assertThat(result.getCustomerId()).isEqualTo("CUST-001");
            assertThat(result.getContractNumber()).startsWith("CTR-");
            assertThat(result.getContractStatus()).isEqualTo(ContractStatus.ACTIVE);
        }

        @Test
        @DisplayName("판매 종료된 상품으로 계약 생성 시 예외가 발생한다")
        void createWithSuspendedProduct() {
            Product suspended = Product.builder()
                    .productType(ProductType.DEPOSIT)
                    .productName("판매종료 상품")
                    .baseInterestRate(BigDecimal.valueOf(2.0))
                    .productStatus(ProductStatus.SUSPENDED)
                    .build();
            given(productRepository.findById(1L)).willReturn(Optional.of(suspended));

            assertThatThrownBy(() -> contractService.createContract(
                    "CUST-001", 1L, BigDecimal.valueOf(1_000_000), 12,
                    null, null, null, null, false, false, null,
                    null, null, null, null, "1234"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("계좌 비밀번호가 없으면 계약 생성 시 예외가 발생한다")
        void createWithoutPassword() {
            given(productRepository.findById(1L)).willReturn(Optional.of(sellingProduct()));

            assertThatThrownBy(() -> contractService.createContract(
                    "CUST-001", 1L, BigDecimal.valueOf(1_000_000), 12,
                    null, null, null, null, false, false, null,
                    null, null, null, null, null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("joinAmount가 minJoinAmount보다 작으면 예외가 발생한다")
        void createWithJoinAmountBelowMin() {
            Product product = Product.builder()
                    .productType(ProductType.DEPOSIT)
                    .productName("정기예금")
                    .baseInterestRate(BigDecimal.valueOf(3.0))
                    .productStatus(ProductStatus.SELLING)
                    .minJoinAmount(BigDecimal.valueOf(100_000))
                    .maxJoinAmount(BigDecimal.valueOf(100_000_000))
                    .build();
            given(productRepository.findById(1L)).willReturn(Optional.of(product));

            assertThatThrownBy(() -> contractService.createContract(
                    "CUST-001", 1L, BigDecimal.valueOf(100), 12,
                    JoinChannel.WEB, null, null, null, false, false, null,
                    null, null, null, null, "1234"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("최소 가입금액");
        }

        @Test
        @DisplayName("joinAmount가 maxJoinAmount보다 크면 예외가 발생한다")
        void createWithJoinAmountAboveMax() {
            Product product = Product.builder()
                    .productType(ProductType.DEPOSIT)
                    .productName("정기예금")
                    .baseInterestRate(BigDecimal.valueOf(3.0))
                    .productStatus(ProductStatus.SELLING)
                    .minJoinAmount(BigDecimal.valueOf(100_000))
                    .maxJoinAmount(BigDecimal.valueOf(100_000_000))
                    .build();
            given(productRepository.findById(1L)).willReturn(Optional.of(product));

            assertThatThrownBy(() -> contractService.createContract(
                    "CUST-001", 1L, BigDecimal.valueOf(200_000_000), 12,
                    JoinChannel.WEB, null, null, null, false, false, null,
                    null, null, null, null, "1234"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("최대 가입금액");
        }
    }

    // --- helpers ---

    private Contract contract(String customerId) {
        return Contract.builder()
                .customerId(customerId)
                .contractNumber("CTR-TEST")
                .contractStatus(ContractStatus.ACTIVE)
                .build();
    }

    private Product sellingProduct() {
        return Product.builder()
                .productType(ProductType.DEPOSIT)
                .productName("정기예금")
                .baseInterestRate(BigDecimal.valueOf(3.0))
                .productStatus(ProductStatus.SELLING)
                .minJoinAmount(BigDecimal.valueOf(100_000))
                .maxJoinAmount(BigDecimal.valueOf(100_000_000))
                .build();
    }
}
