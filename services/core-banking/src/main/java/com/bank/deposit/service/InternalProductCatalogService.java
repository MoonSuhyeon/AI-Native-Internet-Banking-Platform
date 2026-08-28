package com.bank.deposit.service;

import com.bank.deposit.domain.entity.DepositProduct;
import com.bank.deposit.domain.entity.Product;
import com.bank.deposit.domain.entity.ProductInterestRate;
import com.bank.deposit.domain.entity.ProductTargetGroup;
import com.bank.deposit.domain.entity.TargetGroup;
import com.bank.deposit.domain.enums.ProductStatus;
import com.bank.deposit.dto.internal.InternalProductCatalogEntry;
import com.bank.deposit.repository.DepositProductRepository;
import com.bank.deposit.repository.ProductInterestRateRepository;
import com.bank.deposit.repository.ProductRepository;
import com.bank.deposit.repository.ProductTargetGroupRepository;
import com.bank.deposit.repository.TargetGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 판매 중인 상품을 대상·금리·예금상세까지 묶어 한 번에 돌려준다.
 *
 * <p>상담은 이 넷을 늘 함께 쓴다. 개별 엔드포인트로 나눠 부르면 상품 수만큼 호출이
 * 늘어나므로(N+1), 여기서 <b>묶음 조회</b>로 한 번에 읽는다. 상품별로 나눠 담는 것은
 * 메모리에서 한다 — 카탈로그는 크지 않다.
 */
@Service
@RequiredArgsConstructor
public class InternalProductCatalogService {

    private final ProductRepository productRepository;
    private final ProductTargetGroupRepository productTargetGroupRepository;
    private final TargetGroupRepository targetGroupRepository;
    private final ProductInterestRateRepository interestRateRepository;
    private final DepositProductRepository depositProductRepository;

    @Transactional(readOnly = true)
    public List<InternalProductCatalogEntry> sellingCatalog() {
        List<Product> products = productRepository.findAll().stream()
                .filter(p -> p.getProductStatus() == ProductStatus.SELLING)
                .toList();
        if (products.isEmpty()) {
            return List.of();
        }

        List<Long> ids = products.stream().map(Product::getProductId).toList();

        Map<Long, List<TargetGroup>> groupsByProduct = targetGroupsByProduct(ids);
        // 활성 금리만 본다. 판매 중 상품의 안내에 중단된 금리를 섞으면 안내가 틀린다.
        Map<Long, List<ProductInterestRate>> ratesByProduct = interestRateRepository
                .findByProductIdInAndIsActive(ids, true).stream()
                .collect(Collectors.groupingBy(ProductInterestRate::getProductId));
        Map<Long, DepositProduct> depositByProduct = depositProductRepository.findByProductIdIn(ids).stream()
                .collect(Collectors.toMap(DepositProduct::getProductId, Function.identity(), (a, b) -> a));

        return products.stream()
                .map(p -> toEntry(p,
                        groupsByProduct.getOrDefault(p.getProductId(), List.of()),
                        ratesByProduct.getOrDefault(p.getProductId(), List.of()),
                        depositByProduct.get(p.getProductId())))
                .toList();
    }

    private Map<Long, List<TargetGroup>> targetGroupsByProduct(List<Long> productIds) {
        List<ProductTargetGroup> mappings = productTargetGroupRepository.findByIdProductIdIn(productIds);
        if (mappings.isEmpty()) {
            return Map.of();
        }
        Map<Long, TargetGroup> groups = targetGroupRepository.findAllById(
                        mappings.stream().map(m -> m.getId().getTargetGroupId()).distinct().toList()).stream()
                .collect(Collectors.toMap(TargetGroup::getTargetGroupId, Function.identity(), (a, b) -> a));

        return mappings.stream()
                .filter(m -> groups.containsKey(m.getId().getTargetGroupId()))
                .collect(Collectors.groupingBy(
                        m -> m.getId().getProductId(),
                        Collectors.mapping(m -> groups.get(m.getId().getTargetGroupId()), Collectors.toList())));
    }

    private InternalProductCatalogEntry toEntry(Product p, List<TargetGroup> groups,
                                                List<ProductInterestRate> rates, DepositProduct deposit) {
        return new InternalProductCatalogEntry(
                p.getProductId(),
                p.getProductName(),
                p.getProductType() != null ? p.getProductType().name() : null,
                p.getDescription(),
                p.getBaseInterestRate(),
                p.getPreferentialRateCondition(),
                p.getMinJoinAmount(),
                p.getMaxJoinAmount(),
                p.getMinPeriodMonth(),
                p.getMaxPeriodMonth(),
                p.getIsEarlyTerminationAllowed(),
                p.getIsTaxBenefitAvailable(),
                p.getIsAutoRenewalAvailable(),
                p.getIsPassbookIssued(),
                deposit != null ? deposit.getIsCompoundInterest() : null,
                deposit != null && deposit.getDepositType() != null ? deposit.getDepositType().name() : null,
                groups.stream()
                        .map(g -> new InternalProductCatalogEntry.TargetGroup(
                                g.getTargetGroupId(), g.getTargetGroupName(),
                                g.getDescription(), g.getMinAge(), g.getMaxAge()))
                        .toList(),
                rates.stream()
                        .map(r -> new InternalProductCatalogEntry.InterestRate(
                                r.getRateId(),
                                r.getRateType() != null ? r.getRateType().name() : null,
                                r.getMinimumContractPeriod(), r.getMaximumContractPeriod(),
                                r.getRate(), r.getConditionDescription()))
                        .toList()
        );
    }
}
