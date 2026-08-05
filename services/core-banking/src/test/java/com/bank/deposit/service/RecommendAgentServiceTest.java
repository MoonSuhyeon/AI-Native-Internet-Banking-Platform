package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.entity.Product;
import com.bank.deposit.domain.entity.ProductInterestRate;
import com.bank.deposit.domain.entity.ProductTargetGroup;
import com.bank.deposit.domain.entity.ProductTargetGroupId;
import com.bank.deposit.domain.entity.TargetGroup;
import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.enums.*;
import com.bank.deposit.dto.response.ProductRecommendResponse;
import com.bank.deposit.repository.AccountRepository;
import com.bank.deposit.repository.ProductInterestRateRepository;
import com.bank.deposit.repository.ProductRepository;
import com.bank.deposit.repository.ProductTargetGroupRepository;
import com.bank.deposit.repository.TargetGroupRepository;
import com.bank.deposit.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecommendAgentService")
class RecommendAgentServiceTest {

    private CashflowBasedRecommendService recommendAgentService;

    @Mock private AccountRepository accountRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductInterestRateRepository productInterestRateRepository;
    @Mock private ProductTargetGroupRepository productTargetGroupRepository;
    @Mock private TargetGroupRepository targetGroupRepository;

    // 고정 시계: 2026-01-01 → 1990년생이면 만 36세, 1991년생이면 만 35세
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        recommendAgentService = new CashflowBasedRecommendService(
                accountRepository, transactionRepository, productRepository,
                productInterestRateRepository, productTargetGroupRepository,
                targetGroupRepository, FIXED_CLOCK);
    }

    // ── 나이 필터 없는 기본 추천 ───────────────────────────────────────────────

    @Nested
    @DisplayName("recommend() — 기본 현금흐름 추천")
    class Recommend {

        @Test
        @DisplayName("입금이 출금보다 많으면 추천 상품 목록을 반환한다")
        void normalRecommendation() {
            Account account = accountWithBalance("CUST001", 1L, new BigDecimal("2000000"));
            given(accountRepository.findByCustomerId("CUST001")).willReturn(List.of(account));
            given(transactionRepository.findByAccountIdInAndTransactionAtBetweenAndStatus(
                    eq(List.of(1L)), any(OffsetDateTime.class), any(OffsetDateTime.class),
                    eq(TransactionStatus.SUCCESS)))
                    .willReturn(List.of(
                            transaction(1L, DirectionType.IN,  new BigDecimal("1500000")),
                            transaction(1L, DirectionType.IN,  new BigDecimal("1500000")),
                            transaction(1L, DirectionType.IN,  new BigDecimal("1500000")),
                            transaction(1L, DirectionType.OUT, new BigDecimal("1000000")),
                            transaction(1L, DirectionType.OUT, new BigDecimal("1000000")),
                            transaction(1L, DirectionType.OUT, new BigDecimal("1000000"))
                    ));
            given(productRepository.findByProductStatus(ProductStatus.SELLING))
                    .willReturn(List.of(
                            product(1L, "자유적금", ProductType.SAVINGS,
                                    new BigDecimal("3.20"), new BigDecimal("10000"), new BigDecimal("1000000")),
                            product(2L, "정기예금", ProductType.DEPOSIT,
                                    new BigDecimal("2.80"), new BigDecimal("100000"), new BigDecimal("5000000"))
                    ));
            given(productInterestRateRepository.findByProductIdInAndIsActive(any(), eq(true)))
                    .willReturn(List.of(
                            interestRate(1L, new BigDecimal("3.50")),
                            interestRate(2L, new BigDecimal("3.00"))
                    ));
            given(productTargetGroupRepository.findByIdProductIdIn(any())).willReturn(List.of());

            ProductRecommendResponse result = recommendAgentService.recommend("CUST001", 3, null);

            assertThat(result.customerId()).isEqualTo("CUST001");
            assertThat(result.analysisPeriodMonth()).isEqualTo(3);
            assertThat(result.cashFlow().totalInflow()).isEqualByComparingTo("4500000");
            assertThat(result.cashFlow().totalOutflow()).isEqualByComparingTo("3000000");
            assertThat(result.cashFlow().netCashFlow()).isEqualByComparingTo("1500000");
            assertThat(result.cashFlow().estimatedSavingsAmount()).isEqualByComparingTo("500000");
            assertThat(result.recommendations()).isNotEmpty().hasSize(2);
            assertThat(result.recommendations()).allSatisfy(r -> assertThat(r.reason()).isNotBlank());
            assertThat(result.fallbackReason()).isNull();
        }

        @Test
        @DisplayName("고객 계좌가 없으면 cashFlow 전부 0, 빈 recommendations를 반환한다")
        void noAccounts() {
            given(accountRepository.findByCustomerId("CUST999")).willReturn(List.of());

            ProductRecommendResponse result = recommendAgentService.recommend("CUST999", 3, null);

            assertThat(result.customerId()).isEqualTo("CUST999");
            assertThat(result.cashFlow().totalInflow()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.cashFlow().estimatedSavingsAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.recommendations()).isEmpty();
        }

        @Test
        @DisplayName("거래내역이 없으면 cashFlow 전부 0, 빈 recommendations를 반환한다")
        void noTransactions() {
            given(accountRepository.findByCustomerId("CUST001"))
                    .willReturn(List.of(account("CUST001", 1L)));
            given(transactionRepository.findByAccountIdInAndTransactionAtBetweenAndStatus(
                    any(), any(), any(), any()))
                    .willReturn(List.of());

            ProductRecommendResponse result = recommendAgentService.recommend("CUST001", 3, null);

            assertThat(result.cashFlow().totalInflow()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.cashFlow().estimatedSavingsAmount()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.recommendations()).isEmpty();
        }

        @Test
        @DisplayName("estimatedSavingsAmount가 minJoinAmount 미만이면 해당 상품은 추천하지 않는다")
        void amountConditionNotMet() {
            given(accountRepository.findByCustomerId("CUST001"))
                    .willReturn(List.of(account("CUST001", 1L)));
            given(transactionRepository.findByAccountIdInAndTransactionAtBetweenAndStatus(
                    any(), any(), any(), any()))
                    .willReturn(List.of(
                            transaction(1L, DirectionType.IN,  new BigDecimal("200000")),
                            transaction(1L, DirectionType.OUT, new BigDecimal("50000"))
                    ));
            given(productRepository.findByProductStatus(ProductStatus.SELLING))
                    .willReturn(List.of());

            ProductRecommendResponse result = recommendAgentService.recommend("CUST001", 3, null);

            assertThat(result.recommendations()).isEmpty();
        }

        @Test
        @DisplayName("판매 중인 상품이 없으면 빈 recommendations를 반환한다")
        void noSellingProducts() {
            given(accountRepository.findByCustomerId("CUST001"))
                    .willReturn(List.of(account("CUST001", 1L)));
            given(transactionRepository.findByAccountIdInAndTransactionAtBetweenAndStatus(
                    any(), any(), any(), any()))
                    .willReturn(List.of(
                            transaction(1L, DirectionType.IN,  new BigDecimal("1000000")),
                            transaction(1L, DirectionType.OUT, new BigDecimal("400000"))
                    ));
            given(productRepository.findByProductStatus(ProductStatus.SELLING))
                    .willReturn(List.of());

            ProductRecommendResponse result = recommendAgentService.recommend("CUST001", 3, null);

            assertThat(result.recommendations()).isEmpty();
        }

        @Test
        @DisplayName("periodMonth=1 이면 순유입 전체가 estimatedSavingsAmount가 된다")
        void periodMonthOne() {
            given(accountRepository.findByCustomerId("CUST001"))
                    .willReturn(List.of(account("CUST001", 1L)));
            given(transactionRepository.findByAccountIdInAndTransactionAtBetweenAndStatus(
                    any(), any(), any(), any()))
                    .willReturn(List.of(
                            transaction(1L, DirectionType.IN,  new BigDecimal("1200000")),
                            transaction(1L, DirectionType.OUT, new BigDecimal("600000"))
                    ));
            given(productRepository.findByProductStatus(ProductStatus.SELLING))
                    .willReturn(List.of(product(1L, "자유적금", ProductType.SAVINGS,
                            new BigDecimal("3.50"), new BigDecimal("100000"), new BigDecimal("5000000"))));
            given(productInterestRateRepository.findByProductIdInAndIsActive(eq(List.of(1L)), eq(true)))
                    .willReturn(List.of(interestRate(1L, new BigDecimal("3.50"))));
            given(productTargetGroupRepository.findByIdProductIdIn(any())).willReturn(List.of());

            ProductRecommendResponse result = recommendAgentService.recommend("CUST001", 1, null);

            assertThat(result.analysisPeriodMonth()).isEqualTo(1);
            assertThat(result.cashFlow().estimatedSavingsAmount()).isEqualByComparingTo("600000");
            assertThat(result.recommendations()).isNotEmpty();
        }

        @Test
        @DisplayName("periodMonth=6 이면 순유입을 6으로 나눠 estimatedSavingsAmount를 계산한다")
        void periodMonthSix() {
            given(accountRepository.findByCustomerId("CUST001"))
                    .willReturn(List.of(account("CUST001", 1L)));
            given(transactionRepository.findByAccountIdInAndTransactionAtBetweenAndStatus(
                    any(), any(), any(), any()))
                    .willReturn(List.of(
                            transaction(1L, DirectionType.IN,  new BigDecimal("6000000")),
                            transaction(1L, DirectionType.OUT, new BigDecimal("3000000"))
                    ));
            given(productRepository.findByProductStatus(ProductStatus.SELLING))
                    .willReturn(List.of(product(1L, "자유적금", ProductType.SAVINGS,
                            new BigDecimal("3.50"), new BigDecimal("100000"), new BigDecimal("5000000"))));
            given(productInterestRateRepository.findByProductIdInAndIsActive(eq(List.of(1L)), eq(true)))
                    .willReturn(List.of(interestRate(1L, new BigDecimal("3.50"))));
            given(productTargetGroupRepository.findByIdProductIdIn(any())).willReturn(List.of());

            ProductRecommendResponse result = recommendAgentService.recommend("CUST001", 6, null);

            assertThat(result.analysisPeriodMonth()).isEqualTo(6);
            assertThat(result.cashFlow().estimatedSavingsAmount()).isEqualByComparingTo("500000");
            assertThat(result.recommendations()).isNotEmpty();
        }

        @Test
        @DisplayName("periodMonth=12 이면 순유입을 12로 나눠 estimatedSavingsAmount를 계산한다")
        void periodMonthTwelve() {
            given(accountRepository.findByCustomerId("CUST001"))
                    .willReturn(List.of(account("CUST001", 1L)));
            given(transactionRepository.findByAccountIdInAndTransactionAtBetweenAndStatus(
                    any(), any(), any(), any()))
                    .willReturn(List.of(
                            transaction(1L, DirectionType.IN,  new BigDecimal("12000000")),
                            transaction(1L, DirectionType.OUT, new BigDecimal("6000000"))
                    ));
            given(productRepository.findByProductStatus(ProductStatus.SELLING))
                    .willReturn(List.of(product(1L, "자유적금", ProductType.SAVINGS,
                            new BigDecimal("3.50"), new BigDecimal("100000"), new BigDecimal("5000000"))));
            given(productInterestRateRepository.findByProductIdInAndIsActive(eq(List.of(1L)), eq(true)))
                    .willReturn(List.of(interestRate(1L, new BigDecimal("3.50"))));
            given(productTargetGroupRepository.findByIdProductIdIn(any())).willReturn(List.of());

            ProductRecommendResponse result = recommendAgentService.recommend("CUST001", 12, null);

            assertThat(result.analysisPeriodMonth()).isEqualTo(12);
            assertThat(result.cashFlow().estimatedSavingsAmount()).isEqualByComparingTo("500000");
            assertThat(result.recommendations()).isNotEmpty();
        }

        @Test
        @DisplayName("출금이 입금보다 많으면 잔액 기반 fallback 추천을 반환한다")
        void outflowExceedsInflow() {
            Account acc = accountWithBalance("CUST001", 1L, new BigDecimal("5000000"));
            given(accountRepository.findByCustomerId("CUST001")).willReturn(List.of(acc));
            given(transactionRepository.findByAccountIdInAndTransactionAtBetweenAndStatus(
                    any(), any(), any(), any()))
                    .willReturn(List.of(
                            transaction(1L, DirectionType.IN,  new BigDecimal("1000000")),
                            transaction(1L, DirectionType.OUT, new BigDecimal("2000000"))
                    ));
            given(productRepository.findByProductStatus(ProductStatus.SELLING))
                    .willReturn(List.of(product(2L, "정기예금", ProductType.DEPOSIT,
                            new BigDecimal("2.80"), new BigDecimal("1000000"), new BigDecimal("10000000"))));
            given(productInterestRateRepository.findByProductIdInAndIsActive(eq(List.of(2L)), eq(true)))
                    .willReturn(List.of(interestRate(2L, new BigDecimal("2.80"))));
            given(productTargetGroupRepository.findByIdProductIdIn(any())).willReturn(List.of());

            ProductRecommendResponse result = recommendAgentService.recommend("CUST001", 3, null);

            assertThat(result.cashFlow().netCashFlow()).isNegative();
            assertThat(result.recommendations()).isNotEmpty();
            assertThat(result.fallbackReason()).contains("현재 보유 자산 중심");
        }

        @Test
        @DisplayName("monthlySavings <= 0이고 잔액도 0이면 fallbackReason은 있고 recommendations는 빈 리스트를 반환한다")
        void outflowExceedsInflowAndNoBalance() {
            given(accountRepository.findByCustomerId("CUST001"))
                    .willReturn(List.of(account("CUST001", 1L)));
            given(transactionRepository.findByAccountIdInAndTransactionAtBetweenAndStatus(
                    any(), any(), any(), any()))
                    .willReturn(List.of(
                            transaction(1L, DirectionType.IN,  new BigDecimal("1000000")),
                            transaction(1L, DirectionType.OUT, new BigDecimal("2000000"))
                    ));

            ProductRecommendResponse result = recommendAgentService.recommend("CUST001", 3, null);

            assertThat(result.recommendations()).isEmpty();
            assertThat(result.fallbackReason()).contains("현재 보유 자산 중심");
        }

        @Test
        @DisplayName("monthlySavings <= 0 fallback에서 SAVINGS 상품은 추천하지 않는다")
        void fallbackExcludesSavingsProducts() {
            Account acc = accountWithBalance("CUST001", 1L, new BigDecimal("5000000"));
            given(accountRepository.findByCustomerId("CUST001")).willReturn(List.of(acc));
            given(transactionRepository.findByAccountIdInAndTransactionAtBetweenAndStatus(
                    any(), any(), any(), any()))
                    .willReturn(List.of(
                            transaction(1L, DirectionType.IN,  new BigDecimal("500000")),
                            transaction(1L, DirectionType.OUT, new BigDecimal("1000000"))
                    ));
            given(productRepository.findByProductStatus(ProductStatus.SELLING))
                    .willReturn(List.of(
                            product(1L, "자유적금", ProductType.SAVINGS,
                                    new BigDecimal("3.20"), new BigDecimal("10000"), new BigDecimal("1000000")),
                            product(2L, "정기예금", ProductType.DEPOSIT,
                                    new BigDecimal("2.80"), new BigDecimal("1000000"), new BigDecimal("10000000"))
                    ));
            given(productInterestRateRepository.findByProductIdInAndIsActive(eq(List.of(2L)), eq(true)))
                    .willReturn(List.of(interestRate(2L, new BigDecimal("2.80"))));
            given(productTargetGroupRepository.findByIdProductIdIn(any())).willReturn(List.of());

            ProductRecommendResponse result = recommendAgentService.recommend("CUST001", 3, null);

            assertThat(result.recommendations()).hasSize(1);
            assertThat(result.recommendations().get(0).productType()).isEqualTo("DEPOSIT");
            assertThat(result.fallbackReason()).contains("현재 보유 자산 중심");
        }

        @Test
        @DisplayName("정상 추천(monthlySavings > 0)이면 fallbackReason은 null이다")
        void normalPathHasNoFallbackReason() {
            Account acc = accountWithBalance("CUST001", 1L, new BigDecimal("1000000"));
            given(accountRepository.findByCustomerId("CUST001")).willReturn(List.of(acc));
            given(transactionRepository.findByAccountIdInAndTransactionAtBetweenAndStatus(
                    any(), any(), any(), any()))
                    .willReturn(List.of(
                            transaction(1L, DirectionType.IN,  new BigDecimal("1500000")),
                            transaction(1L, DirectionType.OUT, new BigDecimal("500000"))
                    ));
            given(productRepository.findByProductStatus(ProductStatus.SELLING))
                    .willReturn(List.of(product(2L, "정기예금", ProductType.DEPOSIT,
                            new BigDecimal("2.80"), new BigDecimal("100000"), new BigDecimal("5000000"))));
            given(productInterestRateRepository.findByProductIdInAndIsActive(any(), eq(true)))
                    .willReturn(List.of(interestRate(2L, new BigDecimal("2.80"))));
            given(productTargetGroupRepository.findByIdProductIdIn(any())).willReturn(List.of());

            ProductRecommendResponse result = recommendAgentService.recommend("CUST001", 1, null);

            assertThat(result.fallbackReason()).isNull();
        }

        @Test
        @DisplayName("고객이 계좌를 2개 보유하면 두 계좌의 거래내역을 합산해 cashFlow를 계산한다")
        void multipleAccounts() {
            given(accountRepository.findByCustomerId("CUST001"))
                    .willReturn(List.of(account("CUST001", 1L), account("CUST001", 2L)));
            given(transactionRepository.findByAccountIdInAndTransactionAtBetweenAndStatus(
                    any(), any(), any(), any()))
                    .willReturn(List.of(
                            transaction(1L, DirectionType.IN,  new BigDecimal("2000000")),
                            transaction(1L, DirectionType.OUT, new BigDecimal("1000000")),
                            transaction(2L, DirectionType.IN,  new BigDecimal("1000000")),
                            transaction(2L, DirectionType.OUT, new BigDecimal("500000"))
                    ));
            given(productRepository.findByProductStatus(ProductStatus.SELLING))
                    .willReturn(List.of(product(1L, "자유적금", ProductType.SAVINGS,
                            new BigDecimal("3.50"), new BigDecimal("100000"), new BigDecimal("5000000"))));
            given(productInterestRateRepository.findByProductIdInAndIsActive(eq(List.of(1L)), eq(true)))
                    .willReturn(List.of(interestRate(1L, new BigDecimal("3.50"))));
            given(productTargetGroupRepository.findByIdProductIdIn(any())).willReturn(List.of());

            ProductRecommendResponse result = recommendAgentService.recommend("CUST001", 3, null);

            assertThat(result.cashFlow().totalInflow()).isEqualByComparingTo("3000000");
            assertThat(result.cashFlow().totalOutflow()).isEqualByComparingTo("1500000");
            assertThat(result.cashFlow().netCashFlow()).isEqualByComparingTo("1500000");
            assertThat(result.cashFlow().estimatedSavingsAmount()).isEqualByComparingTo("500000");
            assertThat(result.recommendations()).isNotEmpty();
        }
    }

    // ── 나이 기반 필터 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("recommend() — 나이 기반 필터 (DB TargetGroup 기준)")
    class AgeFilter {

        // 공통 거래 셋업: monthlySavings=500000 (IN=1500000, OUT=0 / 3개월)
        private void givenTransactions(Long accountId) {
            given(transactionRepository.findByAccountIdInAndTransactionAtBetweenAndStatus(
                    eq(List.of(accountId)), any(), any(), eq(TransactionStatus.SUCCESS)))
                    .willReturn(List.of(
                            transaction(accountId, DirectionType.IN, new BigDecimal("1500000"))
                    ));
        }

        @Test
        @DisplayName("birthYear=1991(만 35세)이면 maxAge=34인 청년 전용 상품이 제외된다")
        void youthProductExcludedWhenOver34() {
            // 2026 - 1991 = 만 35세 → maxAge=34 청년 전용 제외
            Account acc = accountWithBalance("CUST001", 1L, new BigDecimal("2000000"));
            given(accountRepository.findByCustomerId("CUST001")).willReturn(List.of(acc));
            givenTransactions(1L);

            Product youthSavings = product(10L, "청년도약계좌", ProductType.SAVINGS,
                    new BigDecimal("6.00"), new BigDecimal("10000"), new BigDecimal("700000"));
            Product generalDeposit = product(20L, "정기예금", ProductType.DEPOSIT,
                    new BigDecimal("3.00"), new BigDecimal("100000"), new BigDecimal("10000000"));
            given(productRepository.findByProductStatus(ProductStatus.SELLING))
                    .willReturn(List.of(youthSavings, generalDeposit));

            // 청년도약계좌 → targetGroup: minAge=19, maxAge=34
            TargetGroup youthGroup = targetGroup(3L, "청년", 19, 34);
            ProductTargetGroup mapping = productTargetGroup(10L, 3L);
            given(productTargetGroupRepository.findByIdProductIdIn(any()))
                    .willReturn(List.of(mapping));
            given(targetGroupRepository.findByTargetGroupIdIn(any()))
                    .willReturn(List.of(youthGroup));

            given(productInterestRateRepository.findByProductIdInAndIsActive(eq(List.of(20L)), eq(true)))
                    .willReturn(List.of(interestRate(20L, new BigDecimal("3.00"))));

            // when: birthYear=1991 → 만 35세
            ProductRecommendResponse result = recommendAgentService.recommend("CUST001", 3, 1991);

            assertThat(result.recommendations())
                    .extracting(r -> r.productId())
                    .doesNotContain(10L)
                    .contains(20L);
        }

        @Test
        @DisplayName("birthYear=1992(만 34세)이면 maxAge=34인 청년 전용 상품이 포함된다")
        void youthProductIncludedWhenUnder35() {
            // 2026 - 1992 = 만 34세 → maxAge=34 이내이므로 포함
            Account acc = accountWithBalance("CUST001", 1L, new BigDecimal("2000000"));
            given(accountRepository.findByCustomerId("CUST001")).willReturn(List.of(acc));
            givenTransactions(1L);

            Product youthSavings = product(10L, "청년도약계좌", ProductType.SAVINGS,
                    new BigDecimal("6.00"), new BigDecimal("10000"), new BigDecimal("700000"));
            given(productRepository.findByProductStatus(ProductStatus.SELLING))
                    .willReturn(List.of(youthSavings));

            TargetGroup youthGroup = targetGroup(3L, "청년", 19, 34);
            given(productTargetGroupRepository.findByIdProductIdIn(any()))
                    .willReturn(List.of(productTargetGroup(10L, 3L)));
            given(targetGroupRepository.findByTargetGroupIdIn(any()))
                    .willReturn(List.of(youthGroup));
            given(productInterestRateRepository.findByProductIdInAndIsActive(eq(List.of(10L)), eq(true)))
                    .willReturn(List.of(interestRate(10L, new BigDecimal("6.00"))));

            ProductRecommendResponse result = recommendAgentService.recommend("CUST001", 3, 1992);

            assertThat(result.recommendations())
                    .extracting(r -> r.productId())
                    .contains(10L);
        }

        @Test
        @DisplayName("birthYear=null 이면 나이 필터를 생략하고 전체 상품을 대상으로 추천한다")
        void nullBirthYearSkipsAgeFilter() {
            Account acc = accountWithBalance("CUST001", 1L, new BigDecimal("2000000"));
            given(accountRepository.findByCustomerId("CUST001")).willReturn(List.of(acc));
            givenTransactions(1L);

            Product youthSavings = product(10L, "청년도약계좌", ProductType.SAVINGS,
                    new BigDecimal("6.00"), new BigDecimal("10000"), new BigDecimal("700000"));
            given(productRepository.findByProductStatus(ProductStatus.SELLING))
                    .willReturn(List.of(youthSavings));

            // targetGroup이 있어도 birthYear=null 이면 제외되지 않음
            TargetGroup youthGroup = targetGroup(3L, "청년", 19, 34);
            given(productTargetGroupRepository.findByIdProductIdIn(any()))
                    .willReturn(List.of(productTargetGroup(10L, 3L)));
            given(targetGroupRepository.findByTargetGroupIdIn(any()))
                    .willReturn(List.of(youthGroup));
            given(productInterestRateRepository.findByProductIdInAndIsActive(eq(List.of(10L)), eq(true)))
                    .willReturn(List.of(interestRate(10L, new BigDecimal("6.00"))));

            ProductRecommendResponse result = recommendAgentService.recommend("CUST001", 3, null);

            assertThat(result.recommendations())
                    .extracting(r -> r.productId())
                    .contains(10L);
        }

        @Test
        @DisplayName("TargetGroup이 없는 상품은 나이 제한 없이 모든 고객에게 추천된다")
        void productWithoutTargetGroupIsAlwaysEligible() {
            Account acc = accountWithBalance("CUST001", 1L, new BigDecimal("2000000"));
            given(accountRepository.findByCustomerId("CUST001")).willReturn(List.of(acc));
            givenTransactions(1L);

            Product generalDeposit = product(20L, "일반정기예금", ProductType.DEPOSIT,
                    new BigDecimal("3.50"), new BigDecimal("100000"), new BigDecimal("10000000"));
            given(productRepository.findByProductStatus(ProductStatus.SELLING))
                    .willReturn(List.of(generalDeposit));
            // TargetGroup 없음
            given(productTargetGroupRepository.findByIdProductIdIn(any())).willReturn(List.of());
            given(productInterestRateRepository.findByProductIdInAndIsActive(eq(List.of(20L)), eq(true)))
                    .willReturn(List.of(interestRate(20L, new BigDecimal("3.50"))));

            // 만 60세 고객
            ProductRecommendResponse result = recommendAgentService.recommend("CUST001", 3, 1966);

            assertThat(result.recommendations())
                    .extracting(r -> r.productId())
                    .contains(20L);
        }
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private Account account(String customerId, Long accountId) {
        return accountWithBalance(customerId, accountId, BigDecimal.ZERO);
    }

    private Account accountWithBalance(String customerId, Long accountId, BigDecimal balance) {
        return Account.builder()
                .accountId(accountId)
                .accountNumber("ACC-" + accountId)
                .customerId(customerId)
                .contractId(accountId)
                .accountType(ProductType.DEPOSIT)
                .accountPassword("1234")
                .balance(balance)
                .openedAt(java.time.LocalDate.of(2026, 1, 1))
                .accountStatus(AccountStatus.ACTIVE)
                .build();
    }

    private Transaction transaction(Long accountId, DirectionType direction, BigDecimal amount) {
        return Transaction.builder()
                .transactionNumber("TX-" + System.nanoTime())
                .accountId(accountId)
                .transactionType(TransactionType.DEPOSIT)
                .directionType(direction)
                .amount(amount)
                .balanceBefore(BigDecimal.ZERO)
                .balanceAfter(amount)
                .channelType(TransactionChannel.INTERNET)
                .status(TransactionStatus.SUCCESS)
                .transactionAt(OffsetDateTime.now())
                .build();
    }

    private Product product(Long productId, String name, ProductType type,
                            BigDecimal baseRate, BigDecimal minJoin, BigDecimal maxJoin) {
        return Product.builder()
                .productId(productId)
                .productName(name)
                .productType(type)
                .baseInterestRate(baseRate)
                .minJoinAmount(minJoin)
                .maxJoinAmount(maxJoin)
                .minPeriodMonth(6)
                .maxPeriodMonth(36)
                .productStatus(ProductStatus.SELLING)
                .build();
    }

    private ProductInterestRate interestRate(Long productId, BigDecimal rate) {
        return ProductInterestRate.builder()
                .productId(productId)
                .rateType(RateType.BASE)
                .rate(rate)
                .effectiveStartDate("20260101")
                .isActive(true)
                .build();
    }

    private TargetGroup targetGroup(Long id, String name, Integer minAge, Integer maxAge) {
        return TargetGroup.builder()
                .targetGroupId(id)
                .targetGroupName(name)
                .minAge(minAge)
                .maxAge(maxAge)
                .build();
    }

    private ProductTargetGroup productTargetGroup(Long productId, Long targetGroupId) {
        return ProductTargetGroup.builder()
                .id(new ProductTargetGroupId(productId, targetGroupId))
                .build();
    }
}
