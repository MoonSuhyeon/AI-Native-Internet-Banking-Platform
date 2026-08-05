package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.entity.Product;
import com.bank.deposit.domain.entity.ProductInterestRate;
import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.enums.AccountStatus;
import com.bank.deposit.domain.enums.DirectionType;
import com.bank.deposit.domain.enums.ProductStatus;
import com.bank.deposit.domain.enums.ProductType;
import com.bank.deposit.domain.enums.RateType;
import com.bank.deposit.domain.enums.TransactionStatus;
import com.bank.deposit.dto.response.CashFlowSummary;
import com.bank.deposit.dto.response.ProductRecommendResponse;
import com.bank.deposit.dto.response.RecommendedProduct;
import com.bank.deposit.domain.entity.ProductTargetGroup;
import com.bank.deposit.domain.entity.TargetGroup;
import com.bank.deposit.repository.AccountRepository;
import com.bank.deposit.repository.ProductInterestRateRepository;
import com.bank.deposit.repository.ProductRepository;
import com.bank.deposit.repository.ProductTargetGroupRepository;
import com.bank.deposit.repository.TargetGroupRepository;
import com.bank.deposit.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashflowBasedRecommendService {

    private static final int LOW_TRANSACTION_COUNT = 5;
    private static final int HIGH_TRANSACTION_COUNT = 10;
    /** 저축 성장형 고객의 적금 상품 totalScore 보정 배율 */
    private static final BigDecimal SAVINGS_GROWTH_WEIGHT = new BigDecimal("1.30");
    private static final BigDecimal TOTAL_SCORE_MAX      = new BigDecimal("100");
    private static final BigDecimal FINANCIAL_FIT_MAX    = new BigDecimal("40");
    private static final BigDecimal EXPECTED_RETURN_MAX  = new BigDecimal("30");
    private static final BigDecimal LIQUIDITY_MAX        = new BigDecimal("20");
    private static final BigDecimal BENEFIT_MAX          = new BigDecimal("10");

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final ProductRepository productRepository;
    private final ProductInterestRateRepository productInterestRateRepository;
    private final ProductTargetGroupRepository productTargetGroupRepository;
    private final TargetGroupRepository targetGroupRepository;
    private final Clock clock;

    // ──────────────────────────────────────────────────────────────────────────
    // public entry point
    // ──────────────────────────────────────────────────────────────────────────

    public ProductRecommendResponse recommend(String customerId, int periodMonth, Integer birthYear) {
        Assert.isTrue(periodMonth >= 1, "periodMonth must be greater than or equal to 1.");

        List<Account> activeAccounts = accountRepository.findByCustomerId(customerId).stream()
                .filter(a -> a.getAccountStatus() == AccountStatus.ACTIVE)
                .toList();
        List<Long> accountIds = activeAccounts.stream()
                .map(Account::getAccountId)
                .toList();

        if (accountIds.isEmpty()) {
            return emptyResult(customerId, periodMonth);
        }

        OffsetDateTime endAt   = OffsetDateTime.now(clock);
        OffsetDateTime startAt = endAt.minusMonths(periodMonth);
        List<Transaction> transactions = transactionRepository
                .findByAccountIdInAndTransactionAtBetweenAndStatus(
                        accountIds, startAt, endAt, TransactionStatus.SUCCESS);

        if (transactions.isEmpty()) {
            return emptyResult(customerId, periodMonth);
        }

        BigDecimal totalInflow  = sumByDirection(transactions, DirectionType.IN);
        BigDecimal totalOutflow = sumByDirection(transactions, DirectionType.OUT);
        BigDecimal netCashFlow  = totalInflow.subtract(totalOutflow);
        BigDecimal monthlySavings = netCashFlow.divide(
                BigDecimal.valueOf(periodMonth), 0, RoundingMode.DOWN);

        CashFlowSummary cashFlow = new CashFlowSummary(totalInflow, totalOutflow, netCashFlow, monthlySavings);

        BigDecimal currentBalance = activeAccounts.stream()
                .map(Account::getBalance)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        boolean savingsGrowth = isSavingsGrowthType(currentBalance, monthlySavings);
        log.info("[추천] customerId={} periodMonth={} currentBalance={} monthlySavings={} savingsGrowthType={}",
                customerId, periodMonth, currentBalance, monthlySavings, savingsGrowth);

        Integer customerAge = resolveCustomerAge(birthYear);
        Map<Long, List<TargetGroup>> targetGroupsByProductId = loadTargetGroups(
                productRepository.findByProductStatus(ProductStatus.SELLING).stream()
                        .map(Product::getProductId).toList());

        if (monthlySavings.compareTo(BigDecimal.ZERO) <= 0) {
            return balanceFallbackRecommend(customerId, periodMonth, cashFlow, currentBalance, transactions.size(), customerAge, targetGroupsByProductId);
        }

        List<Product> candidates = productRepository.findByProductStatus(ProductStatus.SELLING).stream()
                .filter(product -> isRecommendable(product, currentBalance, monthlySavings))
                .filter(product -> isAgeEligible(product, customerAge, targetGroupsByProductId))
                .toList();

        log.info("[추천] 후보 상품 {}건 (DEPOSIT={}, SAVINGS={})",
                candidates.size(),
                candidates.stream().filter(p -> p.getProductType() == ProductType.DEPOSIT).count(),
                candidates.stream().filter(p -> p.getProductType() == ProductType.SAVINGS).count());

        if (candidates.isEmpty()) {
            return new ProductRecommendResponse(customerId, periodMonth, cashFlow, List.of());
        }

        List<Long> productIds = candidates.stream().map(Product::getProductId).toList();
        List<ProductInterestRate> activeRates = productInterestRateRepository
                .findByProductIdInAndIsActive(productIds, true);
        Map<Long, BigDecimal> bestRateMap = calculateBestRateMap(candidates, activeRates);

        List<ScoredProduct> scoredProducts = candidates.stream()
                .map(product -> scoreProduct(
                        product, currentBalance, monthlySavings,
                        transactions.size(), periodMonth,
                        bestRateMap.getOrDefault(product.getProductId(), product.getBaseInterestRate()),
                        savingsGrowth))
                .toList();

        BigDecimal maxExpectedReturn = scoredProducts.stream()
                .map(ScoredProduct::expectedReturn)
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);

        List<RecommendedProduct> recommendations = scoredProducts.stream()
                .map(s -> s.withExpectedReturnScore(
                        normalizedExpectedReturnScore(s.expectedReturn(), maxExpectedReturn)))
                .map(ScoredProduct::withTotalScore)
                .peek(s -> log.info(
                        "[후보] {} ({}) rawScore={} adjustedScore={}",
                        s.product().getProductName(),
                        s.product().getProductType(),
                        formatScore(s.rawTotalScore()),
                        formatScore(s.adjustedTotalScore())))
                .sorted(Comparator.comparing(ScoredProduct::adjustedTotalScore).reversed())
                .limit(5)
                .map(this::toRecommended)
                .toList();

        return new ProductRecommendResponse(customerId, periodMonth, cashFlow, recommendations);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // helpers
    // ──────────────────────────────────────────────────────────────────────────

    private BigDecimal sumByDirection(List<Transaction> transactions, DirectionType direction) {
        return transactions.stream()
                .filter(t -> t.getDirectionType() == direction)
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static final String FALLBACK_REASON =
            "최근 거래 기준 월 잉여자금이 부족하여 현재 보유 자산 중심으로 추천했습니다.";

    private ProductRecommendResponse balanceFallbackRecommend(String customerId, int periodMonth,
                                                              CashFlowSummary cashFlow,
                                                              BigDecimal currentBalance,
                                                              int transactionCount,
                                                              Integer customerAge,
                                                              Map<Long, List<TargetGroup>> targetGroupsByProductId) {
        if (currentBalance.compareTo(BigDecimal.ZERO) <= 0) {
            return new ProductRecommendResponse(customerId, periodMonth, cashFlow, List.of(), FALLBACK_REASON);
        }

        List<Product> candidates = productRepository.findByProductStatus(ProductStatus.SELLING).stream()
                .filter(p -> p.getProductType() == ProductType.DEPOSIT)
                .filter(p -> !isSpecialTargetProduct(p))
                .filter(p -> isJoinAmountAvailable(p, currentBalance))
                .filter(p -> isAgeEligible(p, customerAge, targetGroupsByProductId))
                .toList();

        if (candidates.isEmpty()) {
            return new ProductRecommendResponse(customerId, periodMonth, cashFlow, List.of(), FALLBACK_REASON);
        }

        List<Long> productIds = candidates.stream().map(Product::getProductId).toList();
        List<ProductInterestRate> activeRates = productInterestRateRepository
                .findByProductIdInAndIsActive(productIds, true);
        Map<Long, BigDecimal> bestRateMap = calculateBestRateMap(candidates, activeRates);

        List<ScoredProduct> scoredProducts = candidates.stream()
                .map(product -> scoreProduct(
                        product, currentBalance, BigDecimal.ZERO,
                        transactionCount, periodMonth,
                        bestRateMap.getOrDefault(product.getProductId(), product.getBaseInterestRate()),
                        false))
                .toList();

        BigDecimal maxExpectedReturn = scoredProducts.stream()
                .map(ScoredProduct::expectedReturn)
                .max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);

        List<RecommendedProduct> recommendations = scoredProducts.stream()
                .map(s -> s.withExpectedReturnScore(
                        normalizedExpectedReturnScore(s.expectedReturn(), maxExpectedReturn)))
                .map(ScoredProduct::withTotalScore)
                .sorted(Comparator.comparing(ScoredProduct::adjustedTotalScore).reversed())
                .limit(5)
                .map(this::toRecommended)
                .toList();

        return new ProductRecommendResponse(customerId, periodMonth, cashFlow, recommendations, FALLBACK_REASON);
    }

    private boolean isRecommendable(Product product, BigDecimal currentBalance, BigDecimal monthlySavings) {
        if (product.getProductType() == ProductType.SUBSCRIPTION) return false;
        if (isSpecialTargetProduct(product)) return false;
        BigDecimal availableAmount = product.getProductType() == ProductType.DEPOSIT
                ? currentBalance : monthlySavings;
        return isJoinAmountAvailable(product, availableAmount);
    }

    private boolean isSpecialTargetProduct(Product product) {
        String text = searchableText(product);
        return text.contains("군인") || text.contains("장병") || text.contains("군무원");
    }

    /** birthYear → 만 나이 (현재 연도 기준). null이면 null 반환 (필터 생략). */
    private Integer resolveCustomerAge(Integer birthYear) {
        if (birthYear == null) return null;
        return LocalDate.now(clock).getYear() - birthYear;
    }

    /**
     * 상품 ID 목록에 대한 TargetGroup 맵을 로드한다.
     * ProductTargetGroup(중간 테이블) → TargetGroup 조인 처리.
     */
    private Map<Long, List<TargetGroup>> loadTargetGroups(List<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();

        List<ProductTargetGroup> mappings = productTargetGroupRepository.findByIdProductIdIn(productIds);
        if (mappings.isEmpty()) return Map.of();

        Set<Long> targetGroupIds = mappings.stream()
                .map(ptg -> ptg.getId().getTargetGroupId())
                .collect(Collectors.toSet());

        Map<Long, TargetGroup> tgById = targetGroupRepository.findByTargetGroupIdIn(targetGroupIds).stream()
                .collect(Collectors.toMap(TargetGroup::getTargetGroupId, tg -> tg));

        return mappings.stream()
                .filter(ptg -> tgById.containsKey(ptg.getId().getTargetGroupId()))
                .collect(Collectors.groupingBy(
                        ptg -> ptg.getId().getProductId(),
                        Collectors.mapping(ptg -> tgById.get(ptg.getId().getTargetGroupId()), Collectors.toList())));
    }

    /**
     * 나이 제한 충족 여부 판단.
     * <ul>
     *   <li>customerAge == null → 고객 나이 미확인, 필터 생략 (graceful degradation)</li>
     *   <li>상품에 TargetGroup이 없거나 모든 TG가 나이 제한 없음 → 통과</li>
     *   <li>하나 이상의 TG 나이 범위에 포함되면 통과, 모두 불만족 → 제외</li>
     * </ul>
     */
    private boolean isAgeEligible(Product product, Integer customerAge,
                                   Map<Long, List<TargetGroup>> targetGroupsByProductId) {
        if (customerAge == null) return true;

        List<TargetGroup> groups = targetGroupsByProductId.get(product.getProductId());
        if (groups == null || groups.isEmpty()) return true;

        // TargetGroup이 있지만 나이 제한이 없는 그룹만 있는 경우에도 통과
        boolean hasAgeRestriction = groups.stream()
                .anyMatch(tg -> tg.getMinAge() != null || tg.getMaxAge() != null);
        if (!hasAgeRestriction) return true;

        // 나이 제한 있는 TG 중 하나라도 범위에 포함되면 가입 가능
        return groups.stream()
                .filter(tg -> tg.getMinAge() != null || tg.getMaxAge() != null)
                .anyMatch(tg -> {
                    boolean minOk = tg.getMinAge() == null || customerAge >= tg.getMinAge();
                    boolean maxOk = tg.getMaxAge() == null || customerAge <= tg.getMaxAge();
                    return minOk && maxOk;
                });
    }

    private String searchableText(Product product) {
        return ((product.getProductName() == null ? "" : product.getProductName()) + " "
                + (product.getDescription() == null ? "" : product.getDescription()))
                .toLowerCase(Locale.ROOT);
    }

    private boolean isJoinAmountAvailable(Product product, BigDecimal amount) {
        BigDecimal minAmount = product.getMinJoinAmount();
        BigDecimal maxAmount = product.getMaxJoinAmount();
        if (minAmount != null && amount.compareTo(minAmount) < 0) return false;
        return maxAmount == null || amount.compareTo(maxAmount) <= 0;
    }

    private Map<Long, BigDecimal> calculateBestRateMap(List<Product> products, List<ProductInterestRate> activeRates) {
        Map<Long, BigDecimal> baseRateMap = activeRates.stream()
                .filter(r -> r.getRateType() == RateType.BASE || r.getRateType() == RateType.PERIOD_BASE)
                .collect(Collectors.toMap(
                        ProductInterestRate::getProductId,
                        ProductInterestRate::getRate,
                        BigDecimal::max));
        Map<Long, BigDecimal> preferentialRateMap = activeRates.stream()
                .filter(r -> r.getRateType() == RateType.PREFERENTIAL)
                .collect(Collectors.toMap(
                        ProductInterestRate::getProductId,
                        ProductInterestRate::getRate,
                        BigDecimal::add));
        return products.stream()
                .collect(Collectors.toMap(
                        Product::getProductId,
                        product -> baseRateMap
                                .getOrDefault(product.getProductId(), product.getBaseInterestRate())
                                .add(preferentialRateMap.getOrDefault(product.getProductId(), BigDecimal.ZERO))));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // scoring
    // ──────────────────────────────────────────────────────────────────────────

    private ScoredProduct scoreProduct(Product product,
                                       BigDecimal currentBalance,
                                       BigDecimal monthlySavings,
                                       int transactionCount,
                                       int periodMonth,
                                       BigDecimal bestRate,
                                       boolean savingsGrowth) {
        BigDecimal financialFitScore = calculateFinancialFitScore(product, currentBalance, monthlySavings);
        BigDecimal expectedReturn    = calculateExpectedReturn(product, currentBalance, monthlySavings, periodMonth, bestRate);
        BigDecimal liquidityScore    = calculateLiquidityScore(product, transactionCount);
        BigDecimal benefitScore      = calculateBenefitScore(product);

        return new ScoredProduct(
                product, bestRate, monthlySavings, expectedReturn,
                financialFitScore, BigDecimal.ZERO, liquidityScore, benefitScore,
                BigDecimal.ZERO, BigDecimal.ZERO, savingsGrowth);
    }

    /**
     * 재정 적합도 점수 (0~40점, cap 유지).
     * 가중치는 totalScore 단계에서 적용하므로 여기서는 순수 비율만 계산.
     */
    private BigDecimal calculateFinancialFitScore(Product product,
                                                  BigDecimal currentBalance,
                                                  BigDecimal monthlySavings) {
        BigDecimal minJoinAmount = positiveOrOne(product.getMinJoinAmount());
        BigDecimal fitRatio;
        if (product.getProductType() == ProductType.DEPOSIT) {
            fitRatio = currentBalance.divide(minJoinAmount, 4, RoundingMode.HALF_UP);
        } else {
            fitRatio = monthlySavings.divide(minJoinAmount.multiply(BigDecimal.valueOf(2)), 4, RoundingMode.HALF_UP);
        }
        BigDecimal score = cap(fitRatio, BigDecimal.valueOf(5))
                .divide(BigDecimal.valueOf(5), 4, RoundingMode.HALF_UP)
                .multiply(FINANCIAL_FIT_MAX);
        return cap(score, FINANCIAL_FIT_MAX);   // 40점 cap 유지
    }

    private boolean isSavingsGrowthType(BigDecimal currentBalance, BigDecimal monthlySavings) {
        return monthlySavings.multiply(BigDecimal.valueOf(12)).compareTo(currentBalance) > 0;
    }

    private BigDecimal calculateExpectedReturn(Product product,
                                               BigDecimal currentBalance,
                                               BigDecimal monthlySavings,
                                               int requestedPeriodMonth,
                                               BigDecimal bestRate) {
        int period = resolvePeriodMonth(product, requestedPeriodMonth);
        BigDecimal yearlyRate = bestRate.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);

        if (product.getProductType() == ProductType.DEPOSIT) {
            return currentBalance
                    .multiply(yearlyRate)
                    .multiply(BigDecimal.valueOf(period))
                    .divide(BigDecimal.valueOf(12), 0, RoundingMode.DOWN);
        }

        // 적금: 매월 적립 방식 (복리 근사)
        BigDecimal accumulatedWeightedMonths = BigDecimal.valueOf((long) period * (period + 1))
                .divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        return monthlySavings
                .multiply(accumulatedWeightedMonths)
                .multiply(yearlyRate)
                .divide(BigDecimal.valueOf(12), 0, RoundingMode.DOWN);
    }

    private int resolvePeriodMonth(Product product, int requestedPeriodMonth) {
        int minPeriod = product.getMinPeriodMonth() == null ? 1 : product.getMinPeriodMonth();
        int maxPeriod = product.getMaxPeriodMonth() == null
                ? Math.max(requestedPeriodMonth, 12)
                : product.getMaxPeriodMonth();
        return Math.max(minPeriod, Math.min(maxPeriod, Math.max(requestedPeriodMonth, 12)));
    }

    private BigDecimal normalizedExpectedReturnScore(BigDecimal expectedReturn, BigDecimal maxExpectedReturn) {
        if (maxExpectedReturn.compareTo(BigDecimal.ZERO) <= 0) return BigDecimal.ZERO;
        return expectedReturn
                .divide(maxExpectedReturn, 4, RoundingMode.HALF_UP)
                .multiply(EXPECTED_RETURN_MAX);
    }

    private BigDecimal calculateLiquidityScore(Product product, int transactionCount) {
        int period = product.getMaxPeriodMonth() == null
                ? (product.getMinPeriodMonth() == null ? 12 : product.getMinPeriodMonth())
                : product.getMaxPeriodMonth();
        BigDecimal score;
        if (transactionCount <= LOW_TRANSACTION_COUNT) {
            score = period >= 24 ? LIQUIDITY_MAX : period >= 12 ? BigDecimal.valueOf(14) : BigDecimal.valueOf(8);
        } else if (transactionCount >= HIGH_TRANSACTION_COUNT) {
            score = period <= 12 ? LIQUIDITY_MAX : period <= 24 ? BigDecimal.valueOf(14) : BigDecimal.valueOf(8);
        } else {
            score = BigDecimal.valueOf(14);
        }
        if (Boolean.TRUE.equals(product.getIsEarlyTerminationAllowed())) {
            score = score.add(BigDecimal.valueOf(2));
        }
        return cap(score, LIQUIDITY_MAX);
    }

    private BigDecimal calculateBenefitScore(Product product) {
        BigDecimal score = BigDecimal.ZERO;
        if (Boolean.TRUE.equals(product.getIsTaxBenefitAvailable())) {
            score = score.add(BigDecimal.valueOf(6));
        }
        if (Boolean.TRUE.equals(product.getIsEarlyTerminationAllowed())) {
            score = score.add(BigDecimal.valueOf(4));
        }
        return cap(score, BENEFIT_MAX);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // response mapping
    // ──────────────────────────────────────────────────────────────────────────

    private RecommendedProduct toRecommended(ScoredProduct sp) {
        Product product = sp.product();
        String monthlySavingsText = NumberFormat.getNumberInstance(Locale.KOREA)
                .format(sp.monthlySavings()) + "원";

        boolean adjusted = sp.savingsGrowthProfile()
                && product.getProductType() == ProductType.SAVINGS;

        String profileLabel = sp.savingsGrowthProfile() ? "저축 성장형" : "목돈 운용형";

        String scoreBreakdown;
        if (adjusted) {
            scoreBreakdown = String.format(
                    "원점수 %s점 → 보정 후 %s점(적금 가중치 %.0f%% 반영)",
                    formatScore(sp.rawTotalScore()),
                    formatScore(sp.adjustedTotalScore()),
                    SAVINGS_GROWTH_WEIGHT.subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100)));
        } else {
            scoreBreakdown = String.format("총점 %s점", formatScore(sp.adjustedTotalScore()));
        }

        String reason = String.format(
                "%s 진단: 월 평균 저축 여력(%s)과 현재 잔액을 함께 반영했습니다. " +
                        "%s (재정 %s / 수익 %s / 유동성 %s / 혜택 %s). %s%% 금리 기준 추천.",
                profileLabel,
                monthlySavingsText,
                scoreBreakdown,
                formatScore(sp.financialFitScore()),
                formatScore(sp.expectedReturnScore()),
                formatScore(sp.liquidityScore()),
                formatScore(sp.benefitScore()),
                sp.bestRate().toPlainString());

        return new RecommendedProduct(
                product.getProductId(),
                product.getProductName(),
                product.getProductType().name(),
                product.getBaseInterestRate(),
                sp.bestRate(),
                product.getMinJoinAmount(),
                product.getMaxJoinAmount(),
                product.getMinPeriodMonth(),
                product.getMaxPeriodMonth(),
                reason);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // utilities
    // ──────────────────────────────────────────────────────────────────────────

    private BigDecimal positiveOrOne(BigDecimal value) {
        return (value == null || value.compareTo(BigDecimal.ZERO) <= 0) ? BigDecimal.ONE : value;
    }

    private BigDecimal cap(BigDecimal value, BigDecimal max) {
        return value.compareTo(max) > 0 ? max : value;
    }

    private String formatScore(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private ProductRecommendResponse emptyResult(String customerId, int periodMonth) {
        CashFlowSummary zero = new CashFlowSummary(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        return new ProductRecommendResponse(customerId, periodMonth, zero, List.of());
    }

    // ──────────────────────────────────────────────────────────────────────────
    // inner record
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * @param rawTotalScore      4개 컴포넌트 합산 원점수 (≤ 100)
     * @param adjustedTotalScore 저축 성장형+적금이면 rawTotal×1.3 (≤ 100), 그 외 rawTotal과 동일
     * @param savingsGrowthProfile 저축 성장형 고객 여부
     */
    private record ScoredProduct(
            Product product,
            BigDecimal bestRate,
            BigDecimal monthlySavings,
            BigDecimal expectedReturn,
            BigDecimal financialFitScore,
            BigDecimal expectedReturnScore,
            BigDecimal liquidityScore,
            BigDecimal benefitScore,
            BigDecimal rawTotalScore,
            BigDecimal adjustedTotalScore,
            boolean savingsGrowthProfile
    ) {
        private ScoredProduct withExpectedReturnScore(BigDecimal score) {
            return new ScoredProduct(product, bestRate, monthlySavings, expectedReturn,
                    financialFitScore, score, liquidityScore, benefitScore,
                    rawTotalScore, adjustedTotalScore, savingsGrowthProfile);
        }

        private ScoredProduct withTotalScore() {
            BigDecimal raw = financialFitScore
                    .add(expectedReturnScore)
                    .add(liquidityScore)
                    .add(benefitScore);

            // 100점 체계 유지: 컴포넌트 합은 max 100점이므로 raw 자체는 cap 불필요
            BigDecimal adjusted = (savingsGrowthProfile
                    && product.getProductType() == ProductType.SAVINGS)
                    ? raw.multiply(SAVINGS_GROWTH_WEIGHT)
                            .min(new BigDecimal("100"))   // 100점 초과 방지
                    : raw;

            return new ScoredProduct(product, bestRate, monthlySavings, expectedReturn,
                    financialFitScore, expectedReturnScore, liquidityScore, benefitScore,
                    raw, adjusted, savingsGrowthProfile);
        }
    }
}
