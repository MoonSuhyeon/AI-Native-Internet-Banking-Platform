package com.bank.deposit.service;

import com.bank.deposit.domain.entity.*;
import com.bank.deposit.domain.enums.*;
import com.bank.deposit.dto.response.ProductResponse;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final DepositProductRepository depositProductRepository;
    private final SavingsProductRepository savingsProductRepository;
    private final SubscriptionProductRepository subscriptionProductRepository;
    private final ProductJoinChannelRepository joinChannelRepository;
    private final ProductTargetGroupRepository targetGroupMappingRepository;
    private final TargetGroupRepository targetGroupRepository;
    private final ProductInterestRateRepository interestRateRepository;
    private final ProductSpecialTermRepository productSpecialTermRepository;

    public List<Product> findAll(ProductType productType, ProductStatus productStatus) {
        if (productType != null && productStatus != null) {
            return productRepository.findByProductTypeAndProductStatus(productType, productStatus);
        } else if (productType != null) {
            return productRepository.findByProductType(productType);
        } else if (productStatus != null) {
            return productRepository.findByProductStatus(productStatus);
        }
        return productRepository.findAll();
    }

    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
    }

    public List<ProductResponse> findAllResponses(ProductType productType, ProductStatus productStatus) {
        List<Product> products = findAll(productType, productStatus);
        List<Long> productIds = products.stream().map(Product::getProductId).toList();
        Map<Long, BigDecimal> bestRateByProductId = calculateBestRates(products);
        Map<Long, List<ProductResponse.TargetGroupInfo>> targetGroupsByProductId = fetchTargetGroupInfos(productIds);
        Map<Long, SavingType> savingTypeByProductId = savingsProductRepository
                .findByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(SavingsProduct::getProductId, SavingsProduct::getSavingType));
        return products.stream()
                .map(product -> ProductResponse.from(
                        product,
                        bestRateByProductId.get(product.getProductId()),
                        savingTypeByProductId.get(product.getProductId()),
                        targetGroupsByProductId.getOrDefault(product.getProductId(), List.of())))
                .toList();
    }

    public ProductResponse findResponseById(Long id) {
        Product product = findById(id);
        BigDecimal bestRate = calculateBestRate(product,
                interestRateRepository.findByProductIdAndIsActive(id, true));
        List<ProductResponse.TargetGroupInfo> targetGroups = fetchTargetGroupInfos(List.of(id))
                .getOrDefault(id, List.of());
        SavingType savingType = savingsProductRepository.findByProductId(id)
                .map(SavingsProduct::getSavingType).orElse(null);
        return ProductResponse.from(product, bestRate, savingType, targetGroups);
    }

    @Transactional
    public Product create(ProductType productType, String productName, String description,
                          Long departmentId, BigDecimal baseInterestRate,
                          Integer minPeriodMonth, Integer maxPeriodMonth,
                          BigDecimal minJoinAmount, BigDecimal maxJoinAmount,
                          Boolean isEarlyTerminationAllowed, Boolean isTaxBenefitAvailable,
                          Boolean isAutoRenewalAvailable, String releasedAt) {
        return productRepository.save(Product.builder()
                .productType(productType)
                .productName(productName)
                .description(description)
                .departmentId(departmentId)
                .baseInterestRate(baseInterestRate != null ? baseInterestRate : BigDecimal.ZERO)
                .minPeriodMonth(minPeriodMonth)
                .maxPeriodMonth(maxPeriodMonth)
                .minJoinAmount(minJoinAmount)
                .maxJoinAmount(maxJoinAmount)
                .isEarlyTerminationAllowed(isEarlyTerminationAllowed != null && isEarlyTerminationAllowed)
                .isTaxBenefitAvailable(isTaxBenefitAvailable != null && isTaxBenefitAvailable)
                .isAutoRenewalAvailable(isAutoRenewalAvailable != null && isAutoRenewalAvailable)
                .releasedAt(releasedAt)
                .productStatus(ProductStatus.SELLING)
                .build());
    }

    @Transactional
    public Product update(Long id, String productName, String description, BigDecimal baseInterestRate) {
        Product product = findById(id);
        product.update(productName, description, baseInterestRate);
        return product;
    }

    @Transactional
    public Product changeStatus(Long id, ProductStatus status) {
        Product product = findById(id);
        product.changeStatus(status);
        return product;
    }

    // DepositProduct
    public DepositProduct findDepositProduct(Long productId) {
        return depositProductRepository.findByProductId(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "수신 상품 정보를 찾을 수 없습니다."));
    }

    @Transactional
    public DepositProduct createDepositProduct(Long productId, DepositType depositType, Boolean isCompoundInterest) {
        findById(productId);
        if (depositProductRepository.existsByProductId(productId)) {
            throw new BusinessException(ErrorCode.DUPLICATE, "이미 수신 상품 정보가 등록되어 있습니다.");
        }
        return depositProductRepository.save(DepositProduct.builder()
                .productId(productId)
                .depositType(depositType)
                .isCompoundInterest(isCompoundInterest != null && isCompoundInterest)
                .build());
    }

    @Transactional
    public DepositProduct updateDepositProduct(Long productId, DepositType depositType, Boolean isCompoundInterest) {
        DepositProduct dp = findDepositProduct(productId);
        dp.update(depositType, isCompoundInterest != null && isCompoundInterest);
        return dp;
    }

    // SavingsProduct
    public SavingsProduct findSavingsProduct(Long productId) {
        return savingsProductRepository.findByProductId(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "적금 상품 정보를 찾을 수 없습니다."));
    }

    @Transactional
    public SavingsProduct createSavingsProduct(Long productId, SavingType savingType,
                                               BigDecimal monthlyPaymentMinAmount, BigDecimal monthlyPaymentMaxAmount) {
        findById(productId);
        if (savingsProductRepository.existsByProductId(productId)) {
            throw new BusinessException(ErrorCode.DUPLICATE, "이미 적금 상품 정보가 등록되어 있습니다.");
        }
        return savingsProductRepository.save(SavingsProduct.builder()
                .productId(productId)
                .savingType(savingType)
                .monthlyPaymentMinAmount(monthlyPaymentMinAmount)
                .monthlyPaymentMaxAmount(monthlyPaymentMaxAmount)
                .build());
    }

    @Transactional
    public SavingsProduct updateSavingsProduct(Long productId, SavingType savingType,
                                               BigDecimal monthlyPaymentMinAmount, BigDecimal monthlyPaymentMaxAmount) {
        SavingsProduct sp = findSavingsProduct(productId);
        sp.update(savingType, monthlyPaymentMinAmount, monthlyPaymentMaxAmount);
        return sp;
    }

    // SubscriptionProduct
    public SubscriptionProduct findSubscriptionProduct(Long productId) {
        return subscriptionProductRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "청약 상품 정보를 찾을 수 없습니다."));
    }

    @Transactional
    public SubscriptionProduct createSubscriptionProduct(Long productId, BigDecimal monthlyPaymentAmount,
                                                         BigDecimal minMonthlyPayment, BigDecimal maxMonthlyPayment,
                                                         BigDecimal maxRecognizedPaymentAmount) {
        findById(productId);
        return subscriptionProductRepository.save(SubscriptionProduct.builder()
                .productId(productId)
                .monthlyPaymentAmount(monthlyPaymentAmount)
                .minMonthlyPayment(minMonthlyPayment)
                .maxMonthlyPayment(maxMonthlyPayment)
                .maxRecognizedPaymentAmount(maxRecognizedPaymentAmount)
                .build());
    }

    @Transactional
    public SubscriptionProduct updateSubscriptionProduct(Long productId, BigDecimal monthlyPaymentAmount,
                                                          BigDecimal minMonthlyPayment, BigDecimal maxMonthlyPayment) {
        SubscriptionProduct sp = findSubscriptionProduct(productId);
        sp.update(monthlyPaymentAmount, minMonthlyPayment, maxMonthlyPayment);
        return sp;
    }

    @Transactional
    public void deleteDepositProduct(Long productId) {
        DepositProduct dp = findDepositProduct(productId);
        depositProductRepository.delete(dp);
    }

    // JoinChannels
    public List<ProductJoinChannel> findJoinChannels(Long productId) {
        return joinChannelRepository.findByProductId(productId);
    }

    @Transactional
    public ProductJoinChannel addJoinChannel(Long productId, JoinChannel joinChannelCode) {
        findById(productId);
        if (joinChannelRepository.existsByProductIdAndJoinChannelCode(productId, joinChannelCode)) {
            throw new BusinessException(ErrorCode.DUPLICATE, "이미 등록된 가입 방식입니다.");
        }
        return joinChannelRepository.save(ProductJoinChannel.builder()
                .productId(productId)
                .joinChannelCode(joinChannelCode)
                .build());
    }

    @Transactional
    public void removeJoinChannel(Long productId, Long channelId) {
        joinChannelRepository.deleteByProductIdAndProductJoinChannelId(productId, channelId);
    }

    // TargetGroups
    public List<ProductTargetGroup> findProductTargetGroups(Long productId) {
        return targetGroupMappingRepository.findByIdProductId(productId);
    }

    @Transactional
    public ProductTargetGroup linkTargetGroup(Long productId, Long targetGroupId) {
        findById(productId);
        ProductTargetGroupId id = new ProductTargetGroupId(productId, targetGroupId);
        return targetGroupMappingRepository.save(ProductTargetGroup.builder().id(id).build());
    }

    @Transactional
    public void unlinkTargetGroup(Long productId, Long targetGroupId) {
        targetGroupMappingRepository.deleteById(new ProductTargetGroupId(productId, targetGroupId));
    }

    // InterestRates
    public List<ProductInterestRate> findInterestRates(Long productId) {
        return interestRateRepository.findByProductId(productId);
    }

    private Map<Long, List<ProductResponse.TargetGroupInfo>> fetchTargetGroupInfos(Collection<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        List<ProductTargetGroup> mappings = targetGroupMappingRepository.findByIdProductIdIn(productIds);
        if (mappings.isEmpty()) return Map.of();
        Set<Long> tgIds = mappings.stream().map(m -> m.getId().getTargetGroupId()).collect(Collectors.toSet());
        Map<Long, TargetGroup> tgById = targetGroupRepository.findByTargetGroupIdIn(tgIds).stream()
                .collect(Collectors.toMap(TargetGroup::getTargetGroupId, tg -> tg));
        return mappings.stream()
                .filter(m -> tgById.containsKey(m.getId().getTargetGroupId()))
                .collect(Collectors.groupingBy(
                        m -> m.getId().getProductId(),
                        Collectors.mapping(
                                m -> ProductResponse.TargetGroupInfo.from(tgById.get(m.getId().getTargetGroupId())),
                                Collectors.toList())));
    }

    private Map<Long, BigDecimal> calculateBestRates(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }

        Map<Long, Product> productById = products.stream()
                .filter(product -> product.getProductId() != null)
                .collect(Collectors.toMap(Product::getProductId, product -> product));
        if (productById.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<ProductInterestRate>> ratesByProductId = interestRateRepository
                .findByProductIdInAndIsActive(productById.keySet().stream().toList(), true)
                .stream()
                .collect(Collectors.groupingBy(ProductInterestRate::getProductId));

        return ratesByProductId.entrySet().stream()
                .map(entry -> new java.util.AbstractMap.SimpleEntry<>(
                        entry.getKey(),
                        calculateBestRate(productById.get(entry.getKey()), entry.getValue())))
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private BigDecimal calculateBestRate(Product product, List<ProductInterestRate> rates) {
        if (rates == null || rates.isEmpty()) {
            return null;
        }

        BigDecimal baseRate = rates.stream()
                .filter(rate -> rate.getRateType() == RateType.BASE || rate.getRateType() == RateType.PERIOD_BASE)
                .map(ProductInterestRate::getRate)
                .max(BigDecimal::compareTo)
                .orElse(null);

        BigDecimal preferentialRate = rates.stream()
                .filter(rate -> rate.getRateType() == RateType.PREFERENTIAL)
                .map(ProductInterestRate::getRate)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (baseRate == null) {
            if (preferentialRate.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            baseRate = product.getBaseInterestRate() != null ? product.getBaseInterestRate() : BigDecimal.ZERO;
        }

        return baseRate.add(preferentialRate);
    }

    public ProductInterestRate findInterestRate(Long productId, Long rateId) {
        return interestRateRepository.findById(rateId)
                .filter(r -> r.getProductId().equals(productId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "금리를 찾을 수 없습니다."));
    }

    @Transactional
    public ProductInterestRate createInterestRate(Long productId, RateType rateType, BigDecimal rate,
                                                  String effectiveStartDate, Integer minPeriod, Integer maxPeriod,
                                                  BigDecimal minJoinAmount, BigDecimal maxJoinAmount,
                                                  String conditionDescription) {
        findById(productId);
        return interestRateRepository.save(ProductInterestRate.builder()
                .productId(productId)
                .rateType(rateType)
                .rate(rate)
                .effectiveStartDate(effectiveStartDate)
                .minimumContractPeriod(minPeriod)
                .maximumContractPeriod(maxPeriod)
                .minimumJoinAmount(minJoinAmount)
                .maximumJoinAmount(maxJoinAmount)
                .conditionDescription(conditionDescription)
                .build());
    }

    @Transactional
    public ProductInterestRate updateInterestRate(Long productId, Long rateId, BigDecimal rate, String effectiveEndDate) {
        ProductInterestRate ir = findInterestRate(productId, rateId);
        ir.update(rate, effectiveEndDate);
        return ir;
    }

    @Transactional
    public ProductInterestRate expireInterestRate(Long productId, Long rateId) {
        ProductInterestRate ir = findInterestRate(productId, rateId);
        ir.expire();
        return ir;
    }

    // SpecialTerms
    public List<ProductSpecialTerm> findProductSpecialTerms(Long productId) {
        return productSpecialTermRepository.findByProductId(productId);
    }

    @Transactional
    public ProductSpecialTerm linkSpecialTerm(Long productId, Long specialTermId, Boolean isRequired) {
        findById(productId);
        if (productSpecialTermRepository.existsByProductIdAndSpecialTermId(productId, specialTermId)) {
            throw new BusinessException(ErrorCode.DUPLICATE, "이미 연결된 특약입니다.");
        }
        return productSpecialTermRepository.save(ProductSpecialTerm.builder()
                .productId(productId)
                .specialTermId(specialTermId)
                .isRequired(isRequired != null && isRequired)
                .build());
    }

    @Transactional
    public void unlinkSpecialTerm(Long productId, Long specialTermId) {
        productSpecialTermRepository.deleteByProductIdAndSpecialTermId(productId, specialTermId);
    }
}
