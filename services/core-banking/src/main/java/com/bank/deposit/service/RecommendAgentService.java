package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Account;
import com.bank.deposit.domain.entity.Product;
import com.bank.deposit.domain.entity.ProductInterestRate;
import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.enums.AccountStatus;
import com.bank.deposit.domain.enums.DirectionType;
import com.bank.deposit.domain.enums.ProductStatus;
import com.bank.deposit.domain.enums.TransactionStatus;
import com.bank.deposit.dto.response.CashFlowSummary;
import com.bank.deposit.dto.response.ProductRecommendResponse;
import com.bank.deposit.dto.response.RecommendedProduct;
import com.bank.deposit.repository.AccountRepository;
import com.bank.deposit.repository.ProductInterestRateRepository;
import com.bank.deposit.repository.ProductRepository;
import com.bank.deposit.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendAgentService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final ProductRepository productRepository;
    private final ProductInterestRateRepository productInterestRateRepository;

    public ProductRecommendResponse recommend(String customerId, int periodMonth) {
        List<Long> accountIds = accountRepository.findByCustomerId(customerId).stream()
                .filter(a -> a.getAccountStatus() == AccountStatus.ACTIVE)
                .map(Account::getAccountId)
                .toList();

        if (accountIds.isEmpty()) {
            return emptyResult(customerId, periodMonth);
        }

        OffsetDateTime endAt = OffsetDateTime.now();
        OffsetDateTime startAt = endAt.minusMonths(periodMonth);
        List<Transaction> transactions = transactionRepository
                .findByAccountIdInAndTransactionAtBetweenAndStatus(
                        accountIds, startAt, endAt, TransactionStatus.SUCCESS);

        if (transactions.isEmpty()) {
            return emptyResult(customerId, periodMonth);
        }

        BigDecimal totalInflow = sumByDirection(transactions, DirectionType.IN);
        BigDecimal totalOutflow = sumByDirection(transactions, DirectionType.OUT);
        BigDecimal netCashFlow = totalInflow.subtract(totalOutflow);
        BigDecimal estimatedSavingsAmount = netCashFlow.divide(
                BigDecimal.valueOf(periodMonth), 0, RoundingMode.DOWN);

        CashFlowSummary cashFlow = new CashFlowSummary(
                totalInflow, totalOutflow, netCashFlow, estimatedSavingsAmount);

        if (estimatedSavingsAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return new ProductRecommendResponse(customerId, periodMonth, cashFlow, List.of());
        }

        List<RecommendedProduct> recommendations = productRepository
                .findByProductStatus(ProductStatus.SELLING).stream()
                .filter(p -> matchesAmount(p, estimatedSavingsAmount))
                .map(p -> toRecommended(p, estimatedSavingsAmount))
                .sorted(Comparator.comparing(RecommendedProduct::bestRate).reversed())
                .limit(5)
                .toList();

        return new ProductRecommendResponse(customerId, periodMonth, cashFlow, recommendations);
    }

    private BigDecimal sumByDirection(List<Transaction> transactions, DirectionType direction) {
        return transactions.stream()
                .filter(t -> t.getDirectionType() == direction)
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean matchesAmount(Product product, BigDecimal estimatedSavingsAmount) {
        if (product.getMinJoinAmount() != null
                && estimatedSavingsAmount.compareTo(product.getMinJoinAmount()) < 0) {
            return false;
        }
        if (product.getMaxJoinAmount() != null
                && estimatedSavingsAmount.compareTo(product.getMaxJoinAmount()) > 0) {
            return false;
        }
        return true;
    }

    private RecommendedProduct toRecommended(Product product, BigDecimal estimatedSavingsAmount) {
        BigDecimal bestRate = productInterestRateRepository
                .findByProductIdAndIsActive(product.getProductId(), true).stream()
                .map(ProductInterestRate::getRate)
                .max(Comparator.naturalOrder())
                .orElse(product.getBaseInterestRate());

        String formattedAmount = NumberFormat.getNumberInstance(Locale.KOREA)
                .format(estimatedSavingsAmount) + "원";
        String reason = String.format("월 평균 저축 가능 금액(%s) 기반 추천. 연 %s%% 금리 적용.",
                formattedAmount, bestRate.toPlainString());

        return new RecommendedProduct(
                product.getProductId(),
                product.getProductName(),
                product.getProductType().name(),
                product.getBaseInterestRate(),
                bestRate,
                product.getMinJoinAmount(),
                product.getMaxJoinAmount(),
                product.getMinPeriodMonth(),
                product.getMaxPeriodMonth(),
                reason
        );
    }

    private ProductRecommendResponse emptyResult(String customerId, int periodMonth) {
        CashFlowSummary zero = new CashFlowSummary(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        return new ProductRecommendResponse(customerId, periodMonth, zero, List.of());
    }
}
