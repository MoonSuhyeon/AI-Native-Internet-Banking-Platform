package com.bank.deposit.service;

import com.bank.deposit.domain.entity.*;
import com.bank.deposit.domain.enums.*;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.repository.DepositProductRepository;
import com.bank.deposit.repository.ProductInterestRateRepository;
import com.bank.deposit.repository.ProductJoinChannelRepository;
import com.bank.deposit.repository.ProductRepository;
import com.bank.deposit.repository.ProductSpecialTermRepository;
import com.bank.deposit.repository.ProductTargetGroupRepository;
import com.bank.deposit.repository.SavingsProductRepository;
import com.bank.deposit.repository.TargetGroupRepository;
import com.bank.deposit.repository.SubscriptionProductRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductService")
class ProductServiceTest {

    @InjectMocks
    private ProductService productService;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private DepositProductRepository depositProductRepository;

    @Mock
    private SavingsProductRepository savingsProductRepository;

    @Mock
    private SubscriptionProductRepository subscriptionProductRepository;

    @Mock
    private ProductJoinChannelRepository joinChannelRepository;

    @Mock
    private ProductTargetGroupRepository targetGroupMappingRepository;

    @Mock
    private TargetGroupRepository targetGroupRepository;

    @Mock
    private ProductInterestRateRepository interestRateRepository;

    @Mock
    private ProductSpecialTermRepository productSpecialTermRepository;

    @Nested
    @DisplayName("상품 목록 조회")
    class FindAll {

        @Test
        @DisplayName("필터가 없으면 전체 상품을 조회한다")
        void findAllWithoutFilter() {
            given(productRepository.findAll()).willReturn(List.of(product("입출금 상품")));

            List<Product> result = productService.findAll(null, null);

            assertThat(result).hasSize(1);
            then(productRepository).should().findAll();
        }

        @Test
        @DisplayName("상품 유형과 상태로 필터링한다")
        void findAllByTypeAndStatus() {
            given(productRepository.findByProductTypeAndProductStatus(ProductType.DEPOSIT, ProductStatus.SELLING))
                    .willReturn(List.of(product("정기예금")));

            List<Product> result = productService.findAll(ProductType.DEPOSIT, ProductStatus.SELLING);

            assertThat(result).hasSize(1);
            then(productRepository).should()
                    .findByProductTypeAndProductStatus(ProductType.DEPOSIT, ProductStatus.SELLING);
        }

        @Test
        @DisplayName("상품 목록 응답은 active 기본금리와 우대금리를 합산한 bestRate를 포함한다")
        void findAllResponsesWithBestRate() {
            Product product = productWithId(1L, "정기예금");
            given(productRepository.findByProductTypeAndProductStatus(ProductType.DEPOSIT, ProductStatus.SELLING))
                    .willReturn(List.of(product));
            given(interestRateRepository.findByProductIdInAndIsActive(List.of(1L), true))
                    .willReturn(List.of(
                            interestRate(1L, RateType.BASE, "2.15"),
                            interestRate(1L, RateType.PREFERENTIAL, "0.75")
                    ));

            var result = productService.findAllResponses(ProductType.DEPOSIT, ProductStatus.SELLING);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).baseInterestRate()).isEqualByComparingTo("3.5");
            assertThat(result.get(0).bestRate()).isEqualByComparingTo("2.90");
        }
    }

    @Test
    @DisplayName("상품 상세 응답은 active 금리 row 기준 bestRate를 계산한다")
    void findResponseByIdWithBestRate() {
        Product product = productWithId(1L, "내맘대로적금");
        given(productRepository.findById(1L)).willReturn(Optional.of(product));
        given(interestRateRepository.findByProductIdAndIsActive(1L, true))
                .willReturn(List.of(
                        interestRate(1L, RateType.BASE, "2.95"),
                        interestRate(1L, RateType.PREFERENTIAL, "0.60")
                ));

        var result = productService.findResponseById(1L);

        assertThat(result.baseInterestRate()).isEqualByComparingTo("3.5");
        assertThat(result.bestRate()).isEqualByComparingTo("3.55");
    }

    @Nested
    @DisplayName("상품 단건 조회")
    class FindById {

        @Test
        @DisplayName("존재하는 상품을 조회한다")
        void findById() {
            given(productRepository.findById(1L)).willReturn(Optional.of(product("정기예금")));

            Product result = productService.findById(1L);

            assertThat(result.getProductName()).isEqualTo("정기예금");
        }

        @Test
        @DisplayName("존재하지 않는 상품이면 예외가 발생한다")
        void findByIdNotFound() {
            given(productRepository.findById(1L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> productService.findById(1L))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("상품을 생성할 때 null 기본값을 보정한다")
    void createWithDefaultValues() {
        given(productRepository.save(any(Product.class))).willAnswer(invocation -> invocation.getArgument(0));

        Product result = productService.create(
                ProductType.DEPOSIT,
                "정기예금",
                "기본 정기예금",
                1L,
                null,
                12,
                36,
                BigDecimal.valueOf(100000),
                BigDecimal.valueOf(10000000),
                null,
                null,
                null,
                "20260520"
        );

        assertThat(result.getBaseInterestRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getIsEarlyTerminationAllowed()).isFalse();
        assertThat(result.getIsTaxBenefitAvailable()).isFalse();
        assertThat(result.getIsAutoRenewalAvailable()).isFalse();
        assertThat(result.getProductStatus()).isEqualTo(ProductStatus.SELLING);
    }

    @Test
    @DisplayName("상품 상태를 변경한다")
    void changeStatus() {
        Product product = product("정기예금");
        given(productRepository.findById(1L)).willReturn(Optional.of(product));

        Product result = productService.changeStatus(1L, ProductStatus.SUSPENDED);

        assertThat(result.getProductStatus()).isEqualTo(ProductStatus.SUSPENDED);
    }

    @Test
    @DisplayName("가입 채널을 추가한다")
    void addJoinChannel() {
        given(productRepository.findById(1L)).willReturn(Optional.of(product("정기예금")));
        given(joinChannelRepository.existsByProductIdAndJoinChannelCode(1L, JoinChannel.MOBILE))
                .willReturn(false);
        given(joinChannelRepository.save(any(ProductJoinChannel.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        ProductJoinChannel result = productService.addJoinChannel(1L, JoinChannel.MOBILE);

        assertThat(result.getProductId()).isEqualTo(1L);
        assertThat(result.getJoinChannelCode()).isEqualTo(JoinChannel.MOBILE);
    }

    @Test
    @DisplayName("예금 상품 상세를 중복 생성할 수 없다")
    void createDepositProductDuplicate() {
        given(productRepository.findById(1L)).willReturn(Optional.of(product("정기예금")));
        given(depositProductRepository.existsByProductId(1L)).willReturn(true);

        assertThatThrownBy(() -> productService.createDepositProduct(1L, DepositType.TERM, false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("예금 상품 상세를 생성한다")
    void createDepositProduct() {
        given(productRepository.findById(1L)).willReturn(Optional.of(product("정기예금")));
        given(depositProductRepository.existsByProductId(1L)).willReturn(false);
        given(depositProductRepository.save(any(DepositProduct.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        DepositProduct result = productService.createDepositProduct(1L, DepositType.TERM, true);

        assertThat(result.getProductId()).isEqualTo(1L);
        assertThat(result.getDepositType()).isEqualTo(DepositType.TERM);
        assertThat(result.getIsCompoundInterest()).isTrue();
    }

    @Test
    @DisplayName("상품 유형만으로 목록을 필터링한다")
    void findAllByTypeOnly() {
        given(productRepository.findByProductType(ProductType.SAVINGS))
                .willReturn(List.of(product("적금")));

        List<Product> result = productService.findAll(ProductType.SAVINGS, null);

        assertThat(result).hasSize(1);
        then(productRepository).should().findByProductType(ProductType.SAVINGS);
    }

    @Test
    @DisplayName("상품 상태만으로 목록을 필터링한다")
    void findAllByStatusOnly() {
        given(productRepository.findByProductStatus(ProductStatus.SELLING))
                .willReturn(List.of(product("정기예금")));

        List<Product> result = productService.findAll(null, ProductStatus.SELLING);

        assertThat(result).hasSize(1);
        then(productRepository).should().findByProductStatus(ProductStatus.SELLING);
    }

    @Test
    @DisplayName("상품 기본 정보를 수정한다")
    void updateProduct() {
        Product product = product("정기예금");
        given(productRepository.findById(1L)).willReturn(Optional.of(product));

        Product result = productService.update(1L, "수정 예금", "수정 설명", BigDecimal.valueOf(4.1));

        assertThat(result.getProductName()).isEqualTo("수정 예금");
        assertThat(result.getDescription()).isEqualTo("수정 설명");
        assertThat(result.getBaseInterestRate()).isEqualByComparingTo("4.1");
    }

    @Test
    @DisplayName("예금 상품 상세를 조회한다")
    void findDepositProduct() {
        given(depositProductRepository.findByProductId(1L))
                .willReturn(Optional.of(depositProduct()));

        DepositProduct result = productService.findDepositProduct(1L);

        assertThat(result.getProductId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("예금 상품 상세를 수정한다")
    void updateDepositProduct() {
        DepositProduct depositProduct = depositProduct();
        given(depositProductRepository.findByProductId(1L))
                .willReturn(Optional.of(depositProduct));

        DepositProduct result = productService.updateDepositProduct(1L, DepositType.DEMAND, false);

        assertThat(result.getDepositType()).isEqualTo(DepositType.DEMAND);
        assertThat(result.getIsCompoundInterest()).isFalse();
    }

    @Test
    @DisplayName("예금 상품 상세를 삭제한다")
    void deleteDepositProduct() {
        DepositProduct depositProduct = depositProduct();
        given(depositProductRepository.findByProductId(1L))
                .willReturn(Optional.of(depositProduct));

        productService.deleteDepositProduct(1L);

        then(depositProductRepository).should().delete(depositProduct);
    }

    @Test
    @DisplayName("적금 상품 상세를 생성한다")
    void createSavingsProduct() {
        given(productRepository.findById(1L)).willReturn(Optional.of(product("적금")));
        given(savingsProductRepository.existsByProductId(1L)).willReturn(false);
        given(savingsProductRepository.save(any(SavingsProduct.class))).willAnswer(inv -> inv.getArgument(0));

        SavingsProduct result = productService.createSavingsProduct(
                1L, SavingType.FREE, BigDecimal.valueOf(10_000), BigDecimal.valueOf(1_000_000));

        assertThat(result.getProductId()).isEqualTo(1L);
        assertThat(result.getSavingType()).isEqualTo(SavingType.FREE);
        assertThat(result.getMonthlyPaymentMinAmount()).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("적금 상품 상세를 수정한다")
    void updateSavingsProduct() {
        SavingsProduct savingsProduct = SavingsProduct.builder()
                .productId(1L)
                .savingType(SavingType.FREE)
                .monthlyPaymentMinAmount(BigDecimal.valueOf(10_000))
                .monthlyPaymentMaxAmount(BigDecimal.valueOf(1_000_000))
                .build();
        given(savingsProductRepository.findByProductId(1L)).willReturn(Optional.of(savingsProduct));

        SavingsProduct result = productService.updateSavingsProduct(
                1L, SavingType.REGULAR, BigDecimal.valueOf(30_000), BigDecimal.valueOf(2_000_000));

        assertThat(result.getSavingType()).isEqualTo(SavingType.REGULAR);
        assertThat(result.getMonthlyPaymentMaxAmount()).isEqualByComparingTo("2000000");
    }

    @Test
    @DisplayName("청약 상품 상세를 생성한다")
    void createSubscriptionProduct() {
        given(productRepository.findById(1L)).willReturn(Optional.of(product("청약")));
        given(subscriptionProductRepository.save(any(SubscriptionProduct.class))).willAnswer(inv -> inv.getArgument(0));

        SubscriptionProduct result = productService.createSubscriptionProduct(
                1L, BigDecimal.valueOf(100_000), BigDecimal.valueOf(20_000),
                BigDecimal.valueOf(500_000), BigDecimal.valueOf(100_000));

        assertThat(result.getProductId()).isEqualTo(1L);
        assertThat(result.getMonthlyPaymentAmount()).isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("청약 상품 상세를 수정한다")
    void updateSubscriptionProduct() {
        SubscriptionProduct subscriptionProduct = SubscriptionProduct.builder()
                .productId(1L)
                .monthlyPaymentAmount(BigDecimal.valueOf(100_000))
                .minMonthlyPayment(BigDecimal.valueOf(20_000))
                .maxMonthlyPayment(BigDecimal.valueOf(500_000))
                .maxRecognizedPaymentAmount(BigDecimal.valueOf(100_000))
                .build();
        given(subscriptionProductRepository.findById(1L)).willReturn(Optional.of(subscriptionProduct));

        SubscriptionProduct result = productService.updateSubscriptionProduct(
                1L, BigDecimal.valueOf(200_000), BigDecimal.valueOf(30_000), BigDecimal.valueOf(600_000));

        assertThat(result.getMonthlyPaymentAmount()).isEqualByComparingTo("200000");
        assertThat(result.getMaxMonthlyPayment()).isEqualByComparingTo("600000");
    }

    @Test
    @DisplayName("가입 채널 목록을 조회한다")
    void findJoinChannels() {
        given(joinChannelRepository.findByProductId(1L))
                .willReturn(List.of(ProductJoinChannel.builder().productId(1L).joinChannelCode(JoinChannel.MOBILE).build()));

        List<ProductJoinChannel> result = productService.findJoinChannels(1L);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("중복 가입 채널은 추가할 수 없다")
    void addJoinChannelDuplicate() {
        given(productRepository.findById(1L)).willReturn(Optional.of(product("정기예금")));
        given(joinChannelRepository.existsByProductIdAndJoinChannelCode(1L, JoinChannel.MOBILE))
                .willReturn(true);

        assertThatThrownBy(() -> productService.addJoinChannel(1L, JoinChannel.MOBILE))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("가입 채널을 삭제한다")
    void removeJoinChannel() {
        productService.removeJoinChannel(1L, 2L);

        then(joinChannelRepository).should().deleteByProductIdAndProductJoinChannelId(1L, 2L);
    }

    @Test
    @DisplayName("상품 대상 그룹을 연결한다")
    void linkTargetGroup() {
        given(productRepository.findById(1L)).willReturn(Optional.of(product("정기예금")));
        given(targetGroupMappingRepository.save(any(ProductTargetGroup.class))).willAnswer(inv -> inv.getArgument(0));

        ProductTargetGroup result = productService.linkTargetGroup(1L, 10L);

        assertThat(result.getId().getProductId()).isEqualTo(1L);
        assertThat(result.getId().getTargetGroupId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("상품 대상 그룹 연결을 해제한다")
    void unlinkTargetGroup() {
        productService.unlinkTargetGroup(1L, 10L);

        then(targetGroupMappingRepository).should().deleteById(new ProductTargetGroupId(1L, 10L));
    }

    @Test
    @DisplayName("상품 금리를 생성한다")
    void createInterestRate() {
        given(productRepository.findById(1L)).willReturn(Optional.of(product("정기예금")));
        given(interestRateRepository.save(any(ProductInterestRate.class))).willAnswer(inv -> inv.getArgument(0));

        ProductInterestRate result = productService.createInterestRate(
                1L, RateType.BASE, BigDecimal.valueOf(3.5), "20260101",
                6, 12, BigDecimal.valueOf(100_000), BigDecimal.valueOf(10_000_000), "기본금리");

        assertThat(result.getProductId()).isEqualTo(1L);
        assertThat(result.getRate()).isEqualByComparingTo("3.5");
    }

    @Test
    @DisplayName("상품 금리는 상품 ID가 일치해야 조회된다")
    void findInterestRateProductMismatch() {
        given(interestRateRepository.findById(2L)).willReturn(Optional.of(ProductInterestRate.builder()
                .productId(99L)
                .rateType(RateType.BASE)
                .rate(BigDecimal.valueOf(3.5))
                .effectiveStartDate("20260101")
                .build()));

        assertThatThrownBy(() -> productService.findInterestRate(1L, 2L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("상품 금리를 수정한다")
    void updateInterestRate() {
        ProductInterestRate rate = ProductInterestRate.builder()
                .productId(1L)
                .rateType(RateType.BASE)
                .rate(BigDecimal.valueOf(3.5))
                .effectiveStartDate("20260101")
                .build();
        given(interestRateRepository.findById(2L)).willReturn(Optional.of(rate));

        ProductInterestRate result = productService.updateInterestRate(1L, 2L, BigDecimal.valueOf(3.8), "20261231");

        assertThat(result.getRate()).isEqualByComparingTo("3.8");
        assertThat(result.getEffectiveEndDate()).isEqualTo("20261231");
    }

    @Test
    @DisplayName("상품 금리를 만료한다")
    void expireInterestRate() {
        ProductInterestRate rate = ProductInterestRate.builder()
                .productId(1L)
                .rateType(RateType.BASE)
                .rate(BigDecimal.valueOf(3.5))
                .effectiveStartDate("20260101")
                .build();
        given(interestRateRepository.findById(2L)).willReturn(Optional.of(rate));

        ProductInterestRate result = productService.expireInterestRate(1L, 2L);

        assertThat(result.getIsActive()).isFalse();
    }

    @Test
    @DisplayName("상품 특약을 연결한다")
    void linkSpecialTerm() {
        given(productRepository.findById(1L)).willReturn(Optional.of(product("정기예금")));
        given(productSpecialTermRepository.existsByProductIdAndSpecialTermId(1L, 7L)).willReturn(false);
        given(productSpecialTermRepository.save(any(ProductSpecialTerm.class))).willAnswer(inv -> inv.getArgument(0));

        ProductSpecialTerm result = productService.linkSpecialTerm(1L, 7L, true);

        assertThat(result.getProductId()).isEqualTo(1L);
        assertThat(result.getSpecialTermId()).isEqualTo(7L);
        assertThat(result.getIsRequired()).isTrue();
    }

    @Test
    @DisplayName("중복 상품 특약은 연결할 수 없다")
    void linkSpecialTermDuplicate() {
        given(productRepository.findById(1L)).willReturn(Optional.of(product("정기예금")));
        given(productSpecialTermRepository.existsByProductIdAndSpecialTermId(1L, 7L)).willReturn(true);

        assertThatThrownBy(() -> productService.linkSpecialTerm(1L, 7L, true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("상품 특약 연결을 해제한다")
    void unlinkSpecialTerm() {
        productService.unlinkSpecialTerm(1L, 7L);

        then(productSpecialTermRepository).should().deleteByProductIdAndSpecialTermId(1L, 7L);
    }

    private Product product(String name) {
        return Product.builder()
                .productType(ProductType.DEPOSIT)
                .productName(name)
                .description("상품 설명")
                .baseInterestRate(BigDecimal.valueOf(3.5))
                .productStatus(ProductStatus.SELLING)
                .build();
    }

    private Product productWithId(Long id, String name) {
        return Product.builder()
                .productId(id)
                .productType(ProductType.DEPOSIT)
                .productName(name)
                .description("상품 설명")
                .baseInterestRate(BigDecimal.valueOf(3.5))
                .productStatus(ProductStatus.SELLING)
                .build();
    }

    private ProductInterestRate interestRate(Long productId, RateType rateType, String rate) {
        return ProductInterestRate.builder()
                .productId(productId)
                .rateType(rateType)
                .rate(new BigDecimal(rate))
                .effectiveStartDate("20260101")
                .isActive(true)
                .build();
    }

    private DepositProduct depositProduct() {
        return DepositProduct.builder()
                .productId(1L)
                .depositType(DepositType.TERM)
                .isCompoundInterest(true)
                .build();
    }
}
