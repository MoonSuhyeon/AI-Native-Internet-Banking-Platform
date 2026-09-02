package com.bank.deposit.config;

import com.bank.deposit.domain.entity.*;
import com.bank.deposit.domain.enums.*;
import com.bank.deposit.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Component
// 시드를 실행할 환경.
//
// 이 목록이 이 클래스에서 가장 중요한 줄이다. 예전에 시드를 마이그레이션에서 이 시더로
// 옮기면서 프로필을 local/docker 로만 열어둔 결과, 배포 환경(prod)에는 데모 데이터가
// 사라졌다. 그래서 데모 계좌가 필요했던 사람이 "무조건 실행되는" 마이그레이션으로 되돌아갔고
// (V15), 그것이 마이그레이션 체인을 깨뜨렸다.
//
// 즉 규약을 어긴 것이 문제가 아니라 규약이 그 사람의 문제를 풀어주지 못한 것이 문제였다.
// 시연 환경에서도 시드가 돌게 열어두어야 같은 우회가 반복되지 않는다.
// 실제 고객 데이터가 있는 환경에서는 이 프로필을 켜지 않는다.
@Profile({"local", "docker", "demo", "prod"})
@RequiredArgsConstructor
public class LocalDataSeeder implements ApplicationRunner {

    private final DepartmentRepository departmentRepository;
    private final DepositProductRepository depositProductRepository;
    private final ProductInterestRateRepository interestRateRepository;
    private final ProductJoinChannelRepository joinChannelRepository;
    private final ProductRepository productRepository;
    private final ProductTargetGroupRepository productTargetGroupRepository;
    private final SavingsProductRepository savingsProductRepository;
    private final SubscriptionProductRepository subscriptionProductRepository;
    private final TargetGroupRepository targetGroupRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 여신 시스템계좌가 쓰는 상품. {@code V29__seed_loan_system_accounts.sql} 이 넣는다.
     *
     * <p>이 값을 여기 적어 두는 이유는 아래 가드 때문이다. 마이그레이션이 넣는 상품이라
     * 시더가 돌기 전에 이미 표에 있고, 데모 상품 카탈로그와는 아무 상관이 없다.
     */
    private static final long SYSTEM_PRODUCT_ID = 9001L;

    /**
     * 데모 상품 카탈로그가 이미 심겼는가.
     *
     * <p><b>왜 전체 개수가 아닌가.</b> 예전에는 {@code productRepository.count() > 0} 이었다.
     * 그런데 V29 가 시스템계좌용 상품 하나(9001)를 마이그레이션으로 넣으면서, <b>비어 있는
     * DB 에서도 개수가 1</b> 이 됐다. 시더는 "이미 심겼다" 고 판단해 상품 카탈로그를
     * 만들지 않고 건너뛰었고, 곧바로 데모 계약을 넣다가 없는 상품을 참조해 죽었다 —
     * {@code violates foreign key constraint "fk_deposit_contracts_product"}.
     *
     * <p>그 예외는 잡히지 않아 ApplicationContext 기동 실패로 이어진다. 즉 <b>깨끗한
     * DB 에서는 core-banking 이 아예 뜨지 않았다.</b> 이미 시드된 DB 로 도는 곳에서는
     * 드러나지 않아, 볼륨을 지우거나 CI 가 새 DB 를 세울 때만 나타났다.
     *
     * <p>V29 의 주석이 "마이그레이션은 시드에 기대면 안 된다" 고 적었는데, 반대 방향으로
     * 같은 결합이 생겼던 셈이다 — 시드가 마이그레이션의 부작용에 걸렸다.
     */
    private boolean demoCatalogSeeded() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from deposit_banking_products where banking_product_id <> ?",
                Integer.class,
                SYSTEM_PRODUCT_ID);
        return count != null && count > 0;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (demoCatalogSeeded()) {
            seedDemoCustomerAccounts();
            seedDemoCustomerTransactions();
            seedDemoLoginAccounts();
            // 9001(홍길동) 계좌·계약·거래. refreshHongKildongDemoData() 가 이 데이터를 전제로
            // 잔액과 날짜만 갱신하므로 반드시 그보다 먼저 실행한다.
            seedEmployeeDemoAccounts();
            seedPaymentDemoAccounts();
            refreshHongKildongDemoData();
            return;
        }

        Department productDepartment = departmentRepository.save(Department.builder()
                .departmentCode("DEP-PRODUCT")
                .departmentName("수신상품부")
                .departmentType(DepartmentType.PRODUCT)
                .build());

        // ── 대상 그룹 ──────────────────────────────────────────────────────────
        TargetGroup personal = targetGroupRepository.save(TargetGroup.builder()
                .targetGroupName("개인고객")
                .description("개인 인터넷뱅킹 고객")
                .build());
        TargetGroup youth = targetGroupRepository.save(TargetGroup.builder()
                .targetGroupName("청년고객")
                .description("만 19~34세 청년 고객")
                .minAge(19).maxAge(34)
                .build());
        TargetGroup military = targetGroupRepository.save(TargetGroup.builder()
                .targetGroupName("국군장병")
                .description("현역 군인")
                .minAge(18).maxAge(27)
                .build());

        // ══════════════════════════════════════════════════════════════════════
        // TERM 정기예금 4개
        // ══════════════════════════════════════════════════════════════════════
        Product axfulRegular = productRepository.save(Product.builder()
                .productType(ProductType.DEPOSIT)
                .productName("AXful 정기예금")
                .description("Digital AXful의 대표 정기예금")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("2.15"))
                .minJoinAmount(1000000L)
                .minPeriodMonth(1).maxPeriodMonth(36)
                .isEarlyTerminationAllowed(true).isTaxBenefitAvailable(true)
                .isAutoRenewalAvailable(true)
                .releasedAt("20260101").build());

        Product axfulSuper = productRepository.save(Product.builder()
                .productType(ProductType.DEPOSIT)
                .productName("AXful 수퍼정기예금(개인)")
                .description("가입 조건을 직접 설계하는")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("2.10"))
                .minJoinAmount(1000000L)
                .minPeriodMonth(1).maxPeriodMonth(36)
                .isTaxBenefitAvailable(true)
                .releasedAt("20260101").build());

        Product generalDeposit = productRepository.save(Product.builder()
                .productType(ProductType.DEPOSIT)
                .productName("일반정기예금")
                .description("목돈 모아 안정수익 마음든든")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("2.10"))
                .minJoinAmount(1000000L)
                .minPeriodMonth(1).maxPeriodMonth(36)
                .isEarlyTerminationAllowed(true).isTaxBenefitAvailable(true)
                .releasedAt("20260101").build());

        Product youthLeap = productRepository.save(Product.builder()
                .productType(ProductType.DEPOSIT)
                .productName("AXful 청년도약계좌")
                .description("청년의 자산형성을 응원합니다")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("3.50"))
                .minJoinAmount(1000L)
                .minPeriodMonth(60).maxPeriodMonth(60)
                .isTaxBenefitAvailable(true)
                .releasedAt("20260101").build());

        depositProductRepository.save(DepositProduct.builder()
                .productId(axfulRegular.getProductId()).depositType(DepositType.TERM).isCompoundInterest(false).build());
        depositProductRepository.save(DepositProduct.builder()
                .productId(axfulSuper.getProductId()).depositType(DepositType.TERM).isCompoundInterest(false).build());
        depositProductRepository.save(DepositProduct.builder()
                .productId(generalDeposit.getProductId()).depositType(DepositType.TERM).isCompoundInterest(false).build());
        depositProductRepository.save(DepositProduct.builder()
                .productId(youthLeap.getProductId()).depositType(DepositType.TERM).isCompoundInterest(false).build());

        // AXful 정기예금 기간별 BASE 7구간 + 우대 1개
        interestRateRepository.save(ProductInterestRate.builder().productId(axfulRegular.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(1).maximumContractPeriod(2)
                .minimumJoinAmount(1000000L).rate(bd("1.80"))
                .conditionDescription("1개월 이상~3개월 미만 기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(axfulRegular.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(3).maximumContractPeriod(5)
                .minimumJoinAmount(1000000L).rate(bd("2.00"))
                .conditionDescription("3개월 이상~6개월 미만 기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(axfulRegular.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(6).maximumContractPeriod(8)
                .minimumJoinAmount(1000000L).rate(bd("2.10"))
                .conditionDescription("6개월 이상~9개월 미만 기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(axfulRegular.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(9).maximumContractPeriod(11)
                .minimumJoinAmount(1000000L).rate(bd("2.10"))
                .conditionDescription("9개월 이상~12개월 미만 기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(axfulRegular.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(12).maximumContractPeriod(23)
                .minimumJoinAmount(1000000L).rate(bd("2.15"))
                .conditionDescription("12개월 이상~24개월 미만 기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(axfulRegular.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(24).maximumContractPeriod(35)
                .minimumJoinAmount(1000000L).rate(bd("2.20"))
                .conditionDescription("24개월 이상~36개월 미만 기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(axfulRegular.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(36).maximumContractPeriod(36)
                .minimumJoinAmount(1000000L).rate(bd("2.20"))
                .conditionDescription("36개월 기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(axfulRegular.getProductId())
                .rateType(RateType.PREFERENTIAL).minimumContractPeriod(3).maximumContractPeriod(23)
                .minimumJoinAmount(1000000L).rate(bd("0.75"))
                .conditionDescription("비대면(인터넷·스타뱅킹) 가입 우대금리").effectiveStartDate("20260101").build());

        // AXful 수퍼정기예금(개인) BASE 4구간 + 우대 1개
        interestRateRepository.save(ProductInterestRate.builder().productId(axfulSuper.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(1).maximumContractPeriod(5)
                .minimumJoinAmount(1000000L).rate(bd("1.90"))
                .conditionDescription("1개월 이상~6개월 미만 기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(axfulSuper.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(6).maximumContractPeriod(11)
                .minimumJoinAmount(1000000L).rate(bd("2.00"))
                .conditionDescription("6개월 이상~12개월 미만 기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(axfulSuper.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(12).maximumContractPeriod(23)
                .minimumJoinAmount(1000000L).rate(bd("2.10"))
                .conditionDescription("12개월 이상~24개월 미만 기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(axfulSuper.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(24).maximumContractPeriod(35)
                .minimumJoinAmount(1000000L).rate(bd("2.15"))
                .conditionDescription("24개월 이상~36개월 미만 기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(axfulSuper.getProductId())
                .rateType(RateType.PREFERENTIAL).minimumContractPeriod(12).maximumContractPeriod(35)
                .minimumJoinAmount(1000000L).rate(bd("0.20"))
                .conditionDescription("비대면 가입 우대금리").effectiveStartDate("20260101").build());

        // 일반정기예금 BASE 3구간
        interestRateRepository.save(ProductInterestRate.builder().productId(generalDeposit.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(1).maximumContractPeriod(5)
                .minimumJoinAmount(1000000L).rate(bd("1.85"))
                .conditionDescription("1개월 이상~6개월 미만 기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(generalDeposit.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(6).maximumContractPeriod(11)
                .minimumJoinAmount(1000000L).rate(bd("2.00"))
                .conditionDescription("6개월 이상~12개월 미만 기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(generalDeposit.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(12).maximumContractPeriod(36)
                .minimumJoinAmount(1000000L).rate(bd("2.10"))
                .conditionDescription("12개월 이상~36개월 기본금리").effectiveStartDate("20260101").build());

        // AXful 청년도약계좌 BASE + 우대 2개
        interestRateRepository.save(ProductInterestRate.builder().productId(youthLeap.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(60).maximumContractPeriod(60)
                .minimumJoinAmount(1000L).rate(bd("3.50"))
                .conditionDescription("60개월 기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(youthLeap.getProductId())
                .rateType(RateType.PREFERENTIAL).minimumContractPeriod(60).maximumContractPeriod(60)
                .minimumJoinAmount(1000L).rate(bd("1.00"))
                .conditionDescription("기본 소득요건 충족 우대 (+1.0%)").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(youthLeap.getProductId())
                .rateType(RateType.PREFERENTIAL).minimumContractPeriod(60).maximumContractPeriod(60)
                .minimumJoinAmount(1000L).rate(bd("1.50"))
                .conditionDescription("고소득 요건 추가 충족 우대 (+1.5%)").effectiveStartDate("20260101").build());

        // TERM 채널
        joinChannelRepository.save(ProductJoinChannel.builder().productId(axfulRegular.getProductId()).joinChannelCode(JoinChannel.WEB).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(axfulRegular.getProductId()).joinChannelCode(JoinChannel.MOBILE).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(axfulSuper.getProductId()).joinChannelCode(JoinChannel.BRANCH).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(generalDeposit.getProductId()).joinChannelCode(JoinChannel.BRANCH).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(youthLeap.getProductId()).joinChannelCode(JoinChannel.WEB).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(youthLeap.getProductId()).joinChannelCode(JoinChannel.MOBILE).build());

        // TERM 대상 그룹
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(axfulRegular.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(axfulSuper.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(generalDeposit.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(youthLeap.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(youthLeap.getProductId(), youth.getTargetGroupId())).build());

        // ══════════════════════════════════════════════════════════════════════
        // SAVINGS 적금 5개
        // ══════════════════════════════════════════════════════════════════════
        Product diySavings = productRepository.save(Product.builder()
                .productType(ProductType.SAVINGS)
                .productName("AXful 내맘대로적금")
                .description("누구나 쉽게 자유롭게 DIY")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("2.95"))
                .minJoinAmount(10000L).maxJoinAmount(50000000L)
                .minPeriodMonth(1).maxPeriodMonth(36)
                .isEarlyTerminationAllowed(true).isTaxBenefitAvailable(true)
                .releasedAt("20260101").build());

        Product dollarSavings = productRepository.save(Product.builder()
                .productType(ProductType.SAVINGS)
                .productName("AXful 달러자적금")
                .description("달러 가치상승 응원하는 두배이율")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("1.00"))
                .minJoinAmount(10000L).maxJoinAmount(10000000L)
                .minPeriodMonth(1).maxPeriodMonth(6)
                .isEarlyTerminationAllowed(true)
                .releasedAt("20260101").build());

        Product clearSkySavings = productRepository.save(Product.builder()
                .productType(ProductType.SAVINGS)
                .productName("AXful 맑은하늘적금")
                .description("맑은하늘 인증코드 금리도 Up")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("2.85"))
                .minJoinAmount(10000L).maxJoinAmount(50000000L)
                .minPeriodMonth(1).maxPeriodMonth(36)
                .isEarlyTerminationAllowed(true).isTaxBenefitAvailable(true)
                .releasedAt("20260101").build());

        Product militarySavings = productRepository.save(Product.builder()
                .productType(ProductType.SAVINGS)
                .productName("AXful 장병내일준비적금")
                .description("국군장병 미래대비 앞날준비")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("5.00"))
                .minJoinAmount(1000L).maxJoinAmount(1000000L)
                .minPeriodMonth(24).maxPeriodMonth(24)
                .isTaxBenefitAvailable(true)
                .releasedAt("20260101").build());

        Product specialSavings = productRepository.save(Product.builder()
                .productType(ProductType.SAVINGS)
                .productName("AXful 특★한 적금")
                .description("고객 모두의 높은 수익을 위한 특별한 준비")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("2.00"))
                .minJoinAmount(10000L).maxJoinAmount(30000000L)
                .minPeriodMonth(1).maxPeriodMonth(1)
                .releasedAt("20260101").build());

        savingsProductRepository.save(SavingsProduct.builder()
                .productId(diySavings.getProductId()).savingType(SavingType.FREE)
                .monthlyPaymentMinAmount(10000L).monthlyPaymentMaxAmount(1000000L).build());
        savingsProductRepository.save(SavingsProduct.builder()
                .productId(dollarSavings.getProductId()).savingType(SavingType.FREE)
                .monthlyPaymentMinAmount(10000L).monthlyPaymentMaxAmount(500000L).build());
        savingsProductRepository.save(SavingsProduct.builder()
                .productId(clearSkySavings.getProductId()).savingType(SavingType.FREE)
                .monthlyPaymentMinAmount(10000L).monthlyPaymentMaxAmount(1000000L).build());
        savingsProductRepository.save(SavingsProduct.builder()
                .productId(militarySavings.getProductId()).savingType(SavingType.REGULAR)
                .monthlyPaymentMinAmount(1000L).monthlyPaymentMaxAmount(100000L).build());
        savingsProductRepository.save(SavingsProduct.builder()
                .productId(specialSavings.getProductId()).savingType(SavingType.FREE)
                .monthlyPaymentMinAmount(10000L).monthlyPaymentMaxAmount(3000000L).build());

        // SAVINGS 금리
        interestRateRepository.save(ProductInterestRate.builder().productId(diySavings.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(1).maximumContractPeriod(36)
                .minimumJoinAmount(10000L).rate(bd("2.95"))
                .conditionDescription("기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(diySavings.getProductId())
                .rateType(RateType.PREFERENTIAL).minimumContractPeriod(1).maximumContractPeriod(36)
                .minimumJoinAmount(10000L).rate(bd("0.60"))
                .conditionDescription("자동이체 설정 우대").effectiveStartDate("20260101").build());

        interestRateRepository.save(ProductInterestRate.builder().productId(dollarSavings.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(1).maximumContractPeriod(6)
                .minimumJoinAmount(10000L).rate(bd("1.00"))
                .conditionDescription("기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(dollarSavings.getProductId())
                .rateType(RateType.PREFERENTIAL).minimumContractPeriod(6).maximumContractPeriod(6)
                .minimumJoinAmount(10000L).rate(bd("6.20"))
                .conditionDescription("달러 환전 실적 우대").effectiveStartDate("20260101").build());

        interestRateRepository.save(ProductInterestRate.builder().productId(clearSkySavings.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(1).maximumContractPeriod(36)
                .minimumJoinAmount(10000L).rate(bd("2.85"))
                .conditionDescription("기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(clearSkySavings.getProductId())
                .rateType(RateType.PREFERENTIAL).minimumContractPeriod(1).maximumContractPeriod(36)
                .minimumJoinAmount(10000L).rate(bd("1.00"))
                .conditionDescription("맑은하늘 인증코드 등록 우대").effectiveStartDate("20260101").build());

        interestRateRepository.save(ProductInterestRate.builder().productId(militarySavings.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(24).maximumContractPeriod(24)
                .minimumJoinAmount(1000L).rate(bd("5.00"))
                .conditionDescription("기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(militarySavings.getProductId())
                .rateType(RateType.PREFERENTIAL).minimumContractPeriod(24).maximumContractPeriod(24)
                .minimumJoinAmount(1000L).rate(bd("5.50"))
                .conditionDescription("정부 기여금 및 납입 완료 우대").effectiveStartDate("20260101").build());

        interestRateRepository.save(ProductInterestRate.builder().productId(specialSavings.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(1).maximumContractPeriod(1)
                .minimumJoinAmount(10000L).rate(bd("2.00"))
                .conditionDescription("기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(specialSavings.getProductId())
                .rateType(RateType.PREFERENTIAL).minimumContractPeriod(1).maximumContractPeriod(1)
                .minimumJoinAmount(10000L).rate(bd("4.00"))
                .conditionDescription("특별 조건 충족 우대").effectiveStartDate("20260101").build());

        // SAVINGS 채널
        joinChannelRepository.save(ProductJoinChannel.builder().productId(diySavings.getProductId()).joinChannelCode(JoinChannel.WEB).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(diySavings.getProductId()).joinChannelCode(JoinChannel.MOBILE).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(dollarSavings.getProductId()).joinChannelCode(JoinChannel.MOBILE).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(clearSkySavings.getProductId()).joinChannelCode(JoinChannel.WEB).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(clearSkySavings.getProductId()).joinChannelCode(JoinChannel.MOBILE).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(militarySavings.getProductId()).joinChannelCode(JoinChannel.MOBILE).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(specialSavings.getProductId()).joinChannelCode(JoinChannel.MOBILE).build());

        // SAVINGS 대상 그룹
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(diySavings.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(dollarSavings.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(clearSkySavings.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(militarySavings.getProductId(), military.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(specialSavings.getProductId(), personal.getTargetGroupId())).build());

        // ══════════════════════════════════════════════════════════════════════
        // SUBSCRIPTION 청약 2개
        // ══════════════════════════════════════════════════════════════════════
        Product housingSubscription = productRepository.save(Product.builder()
                .productType(ProductType.SUBSCRIPTION)
                .productName("주택청약종합저축")
                .description("청약 자격 및 주택마련 저축 상품")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("3.10"))
                .minJoinAmount(20000L).maxJoinAmount(500000L)
                .minPeriodMonth(24).maxPeriodMonth(600)
                .releasedAt("20260101").build());

        Product youthHousing = productRepository.save(Product.builder()
                .productType(ProductType.SUBSCRIPTION)
                .productName("청년 주택드림 청약통장")
                .description("만 19~34세 청년을 위한 우대 청약통장")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("3.10"))
                .minJoinAmount(20000L).maxJoinAmount(1000000L)
                .minPeriodMonth(24).maxPeriodMonth(600)
                .isTaxBenefitAvailable(true)
                .releasedAt("20260101").build());

        subscriptionProductRepository.save(SubscriptionProduct.builder()
                .productId(housingSubscription.getProductId())
                .monthlyPaymentAmount(100000L)
                .minMonthlyPayment(20000L).maxMonthlyPayment(500000L)
                .maxRecognizedPaymentAmount(10000000L).build());
        subscriptionProductRepository.save(SubscriptionProduct.builder()
                .productId(youthHousing.getProductId())
                .monthlyPaymentAmount(200000L)
                .minMonthlyPayment(20000L).maxMonthlyPayment(1000000L)
                .maxRecognizedPaymentAmount(10000000L).build());

        // SUBSCRIPTION 금리
        interestRateRepository.save(ProductInterestRate.builder().productId(housingSubscription.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(24).maximumContractPeriod(600)
                .minimumJoinAmount(20000L).rate(bd("3.10"))
                .conditionDescription("기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(youthHousing.getProductId())
                .rateType(RateType.BASE).minimumContractPeriod(24).maximumContractPeriod(600)
                .minimumJoinAmount(20000L).rate(bd("3.10"))
                .conditionDescription("기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(youthHousing.getProductId())
                .rateType(RateType.PREFERENTIAL).minimumContractPeriod(24).maximumContractPeriod(600)
                .minimumJoinAmount(20000L).rate(bd("1.40"))
                .conditionDescription("청년 소득 조건 충족 우대").effectiveStartDate("20260101").build());

        // SUBSCRIPTION 채널
        joinChannelRepository.save(ProductJoinChannel.builder().productId(housingSubscription.getProductId()).joinChannelCode(JoinChannel.WEB).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(housingSubscription.getProductId()).joinChannelCode(JoinChannel.MOBILE).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(youthHousing.getProductId()).joinChannelCode(JoinChannel.MOBILE).build());

        // SUBSCRIPTION 대상 그룹
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(housingSubscription.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(youthHousing.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(youthHousing.getProductId(), youth.getTargetGroupId())).build());

        // ══════════════════════════════════════════════════════════════════════
        // DEMAND 입출금자유 10개
        // ══════════════════════════════════════════════════════════════════════
        Product ssokMoney = productRepository.save(Product.builder()
                .productType(ProductType.DEPOSIT).productName("AXful 쏙머니통장")
                .description("쇼핑용 아껴 쏙머니가 쏙~")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("0.10")).minJoinAmount(0L)
                .releasedAt("20260101").build());
        Product electionAccount = productRepository.save(Product.builder()
                .productType(ProductType.DEPOSIT).productName("당선통장")
                .description("각종 공직선거 입후보자 및 입후보예정자 선거자금 관리 통장")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("0.10")).minJoinAmount(0L)
                .releasedAt("20260101").build());
        Product livelihoodAccount = productRepository.save(Product.builder()
                .productType(ProductType.DEPOSIT).productName("AXful 생계비계좌")
                .description("생계 유지에 필요한 자금을 최대 250만원까지 보호하는 압류방지 전용통장")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("0.10")).minJoinAmount(0L).maxJoinAmount(2500000L)
                .releasedAt("20260101").build());
        Product gsPayAccount = productRepository.save(Product.builder()
                .productType(ProductType.DEPOSIT).productName("AXful GS Pay통장")
                .description("GS25와의 만남으로 더 풍성해진 혜택")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("0.10")).minJoinAmount(0L)
                .releasedAt("20260101").build());
        Product dailyInterest = productRepository.save(Product.builder()
                .productType(ProductType.DEPOSIT).productName("모니모 AXful 매일이자 통장")
                .description("하루만 넣어도 이자가 쌓이는")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("0.50")).minJoinAmount(0L)
                .releasedAt("20260101").build());
        Product groupSafe = productRepository.save(Product.builder()
                .productType(ProductType.DEPOSIT).productName("AXful 모임금고")
                .description("고인 여유자금을 연 2.0%(최대 1천만원)로 불리는")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("2.00")).minJoinAmount(0L).maxJoinAmount(10000000L)
                .releasedAt("20260101").build());
        Product starAccount = productRepository.save(Product.builder()
                .productType(ProductType.DEPOSIT).productName("AXful 스타통장")
                .description("Digital AXful의 대표 통장")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("0.10")).minJoinAmount(0L)
                .releasedAt("20260101").build());
        Product walletAccount = productRepository.save(Product.builder()
                .productType(ProductType.DEPOSIT).productName("AXful 지갑통장")
                .description("일상의 모든 지출을 한 곳에서 관리")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("0.10")).minJoinAmount(0L)
                .releasedAt("20260101").build());
        Product freeAccount = productRepository.save(Product.builder()
                .productType(ProductType.DEPOSIT).productName("AXful 자유입출금통장")
                .description("언제든 자유롭게 입출금 가능한 기본 통장")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("0.10")).minJoinAmount(0L)
                .releasedAt("20260101").build());
        Product youthAccount = productRepository.save(Product.builder()
                .productType(ProductType.DEPOSIT).productName("AXful 청년우대통장")
                .description("만 19~34세 청년을 위한 우대금리 제공")
                .departmentId(productDepartment.getDepartmentId())
                .baseInterestRate(bd("0.50")).minJoinAmount(0L)
                .releasedAt("20260101").build());

        depositProductRepository.save(DepositProduct.builder().productId(ssokMoney.getProductId()).depositType(DepositType.DEMAND).isCompoundInterest(false).build());
        depositProductRepository.save(DepositProduct.builder().productId(electionAccount.getProductId()).depositType(DepositType.DEMAND).isCompoundInterest(false).build());
        depositProductRepository.save(DepositProduct.builder().productId(livelihoodAccount.getProductId()).depositType(DepositType.DEMAND).isCompoundInterest(false).build());
        depositProductRepository.save(DepositProduct.builder().productId(gsPayAccount.getProductId()).depositType(DepositType.DEMAND).isCompoundInterest(false).build());
        depositProductRepository.save(DepositProduct.builder().productId(dailyInterest.getProductId()).depositType(DepositType.DEMAND).isCompoundInterest(true).build());
        depositProductRepository.save(DepositProduct.builder().productId(groupSafe.getProductId()).depositType(DepositType.DEMAND).isCompoundInterest(false).build());
        depositProductRepository.save(DepositProduct.builder().productId(starAccount.getProductId()).depositType(DepositType.DEMAND).isCompoundInterest(false).build());
        depositProductRepository.save(DepositProduct.builder().productId(walletAccount.getProductId()).depositType(DepositType.DEMAND).isCompoundInterest(false).build());
        depositProductRepository.save(DepositProduct.builder().productId(freeAccount.getProductId()).depositType(DepositType.DEMAND).isCompoundInterest(false).build());
        depositProductRepository.save(DepositProduct.builder().productId(youthAccount.getProductId()).depositType(DepositType.DEMAND).isCompoundInterest(false).build());

        // DEMAND 금리 (명시적 금리 있는 상품만)
        interestRateRepository.save(ProductInterestRate.builder().productId(dailyInterest.getProductId())
                .rateType(RateType.BASE).minimumJoinAmount(0L).rate(bd("0.50"))
                .conditionDescription("매일 이자 지급 기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(groupSafe.getProductId())
                .rateType(RateType.BASE).minimumJoinAmount(0L).maximumJoinAmount(10000000L).rate(bd("2.00"))
                .conditionDescription("모임금고 기본금리 (최대 1천만원 한도)").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(youthAccount.getProductId())
                .rateType(RateType.BASE).minimumJoinAmount(0L).rate(bd("0.50"))
                .conditionDescription("기본금리").effectiveStartDate("20260101").build());
        interestRateRepository.save(ProductInterestRate.builder().productId(youthAccount.getProductId())
                .rateType(RateType.PREFERENTIAL).minimumJoinAmount(0L).rate(bd("0.50"))
                .conditionDescription("청년 우대금리 (+0.5%)").effectiveStartDate("20260101").build());

        // DEMAND 채널
        joinChannelRepository.save(ProductJoinChannel.builder().productId(ssokMoney.getProductId()).joinChannelCode(JoinChannel.BRANCH).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(electionAccount.getProductId()).joinChannelCode(JoinChannel.BRANCH).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(livelihoodAccount.getProductId()).joinChannelCode(JoinChannel.MOBILE).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(gsPayAccount.getProductId()).joinChannelCode(JoinChannel.MOBILE).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(dailyInterest.getProductId()).joinChannelCode(JoinChannel.BRANCH).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(groupSafe.getProductId()).joinChannelCode(JoinChannel.MOBILE).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(starAccount.getProductId()).joinChannelCode(JoinChannel.MOBILE).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(walletAccount.getProductId()).joinChannelCode(JoinChannel.WEB).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(walletAccount.getProductId()).joinChannelCode(JoinChannel.MOBILE).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(freeAccount.getProductId()).joinChannelCode(JoinChannel.WEB).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(freeAccount.getProductId()).joinChannelCode(JoinChannel.MOBILE).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(youthAccount.getProductId()).joinChannelCode(JoinChannel.WEB).build());
        joinChannelRepository.save(ProductJoinChannel.builder().productId(youthAccount.getProductId()).joinChannelCode(JoinChannel.MOBILE).build());

        // DEMAND 대상 그룹
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(ssokMoney.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(electionAccount.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(livelihoodAccount.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(gsPayAccount.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(dailyInterest.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(groupSafe.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(starAccount.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(walletAccount.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(freeAccount.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(youthAccount.getProductId(), personal.getTargetGroupId())).build());
        productTargetGroupRepository.save(ProductTargetGroup.builder().id(new ProductTargetGroupId(youthAccount.getProductId(), youth.getTargetGroupId())).build());

        seedDemoCustomerAccounts();
        seedDemoCustomerTransactions();
        seedDemoLoginAccounts();
        seedEmployeeDemoAccounts();
        seedPaymentDemoAccounts();
        refreshHongKildongDemoData();
    }

    private void seedDemoCustomerAccounts() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from deposit_accounts where customer_id = ?",
                Integer.class,
                "1");
        if (count != null && count > 0) {
            return;
        }

        jdbcTemplate.update("""
                insert into deposit_contracts (
                    contract_id, contract_number, customer_id, banking_product_id,
                    is_monthly_payment, payment_count_total, join_amount,
                    contract_interest_rate, total_preferential_rate, final_interest_rate,
                    tax_benefit_type, applied_tax_rate, expected_interest_amount,
                    contract_period_month, started_at, maturity_at,
                    is_auto_renewal, auto_transfer_enabled, contract_status, join_channel,
                    is_proxy_joined, is_power_of_attorney_verified, created_at, consecutive_miss_count
                ) values
                (1001, 'DEMO-CUST1-DEP-001', '1', 1, false, null, 5000000,
                 2.15, 0, 2.15, 'GENERAL', 15.40, 107500,
                 12, current_date, current_date + interval '12 months',
                 false, false, 'ACTIVE', 'WEB', false, false, now(), 0),
                (1002, 'DEMO-CUST1-SAV-001', '1', 5, true, 12, 100000,
                 2.95, 0, 2.95, 'GENERAL', 15.40, 35400,
                 12, current_date, current_date + interval '12 months',
                 false, true, 'ACTIVE', 'WEB', false, false, now(), 0)
                on conflict (contract_id) do nothing
                """);

        jdbcTemplate.update("""
                insert into deposit_accounts (
                    account_number, customer_id, contract_id, account_type, saving_type,
                    bank_code, balance, total_paid_amount, total_interest_amount,
                    currency, account_password,
                    is_withdrawable, is_online_banking_enabled, is_mobile_banking_enabled, is_phone_banking_enabled,
                    account_status, opened_at, maturity_at, created_at, version
                ) values
                ('001-123-000001', '1', 1001, 'DEPOSIT', null,
                 '001', 100000, 0, 0, 'KRW', '$2a$10$012345678901234567890u3JcU7Q64k9GZ3f3hQz8hC7cWv9q1y6K',
                 true, true, true, true, 'ACTIVE', current_date, current_date + interval '12 months', now(), 0),
                ('001-123-000002', '1', 1002, 'SAVINGS', 'REGULAR',
                 '001', 100000, 100000, 0, 'KRW', '$2a$10$012345678901234567890u3JcU7Q64k9GZ3f3hQz8hC7cWv9q1y6K',
                 false, true, true, true, 'ACTIVE', current_date, current_date + interval '12 months', now(), 0)
                on conflict (account_number) do update set balance = excluded.balance, total_paid_amount = excluded.total_paid_amount
                """);
    }

    private void seedDemoCustomerTransactions() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from deposit_transactions t" +
                " join deposit_accounts a on a.account_id = t.account_id" +
                " where a.customer_id = '1'",
                Integer.class);
        if (count != null && count > 0) return;

        // 입출금 계좌(DEPOSIT) account_id subquery
        String acctSub = "(select account_id from deposit_accounts where account_number = '001-123-000001' limit 1)";

        // 최근 3개월 거래 시드: 월 입금 1,500,000 / 월 지출 800,000 → monthlySurplus ≈ 700,000
        // monthlySurplus * 12 = 8,400,000 > totalBalance(6,200,000) → isAccumulateType = true
        jdbcTemplate.update("""
                insert into deposit_transactions (
                    transaction_number, account_id, transaction_type, direction_type,
                    amount, balance_before, balance_after, fee_amount,
                    currency, status, channel_type, transaction_at, retry_count,
                    transaction_summary, created_at, updated_at
                ) values
                -- 3개월 전: 급여입금
                ('SEED-TX-001', %1$s, 'DEPOSIT',  'IN',  1500000, 3500000, 5000000, 0, 'KRW', 'SUCCESS', 'INTERNET', now() - interval '3 months' + interval '5 days',  0, '급여 입금', now(), now()),
                -- 3개월 전: 생활비 출금
                ('SEED-TX-002', %1$s, 'WITHDRAW', 'OUT', 500000,  5000000, 4500000, 0, 'KRW', 'SUCCESS', 'INTERNET', now() - interval '3 months' + interval '10 days', 0, '마트 이용', now(), now()),
                -- 3개월 전: 관리비 출금
                ('SEED-TX-003', %1$s, 'WITHDRAW', 'OUT', 300000,  4500000, 4200000, 0, 'KRW', 'SUCCESS', 'INTERNET', now() - interval '3 months' + interval '15 days', 0, '관리비', now(), now()),
                -- 2개월 전: 급여입금
                ('SEED-TX-004', %1$s, 'DEPOSIT',  'IN',  1500000, 4200000, 5700000, 0, 'KRW', 'SUCCESS', 'INTERNET', now() - interval '2 months' + interval '5 days',  0, '급여 입금', now(), now()),
                -- 2개월 전: 생활비 출금
                ('SEED-TX-005', %1$s, 'WITHDRAW', 'OUT', 500000,  5700000, 5200000, 0, 'KRW', 'SUCCESS', 'INTERNET', now() - interval '2 months' + interval '10 days', 0, '마트 이용', now(), now()),
                -- 2개월 전: 관리비 출금
                ('SEED-TX-006', %1$s, 'WITHDRAW', 'OUT', 300000,  5200000, 4900000, 0, 'KRW', 'SUCCESS', 'INTERNET', now() - interval '2 months' + interval '15 days', 0, '관리비', now(), now()),
                -- 1개월 전: 급여입금
                ('SEED-TX-007', %1$s, 'DEPOSIT',  'IN',  1500000, 4900000, 6400000, 0, 'KRW', 'SUCCESS', 'INTERNET', now() - interval '1 month'  + interval '5 days',  0, '급여 입금', now(), now()),
                -- 1개월 전: 생활비 출금
                ('SEED-TX-008', %1$s, 'WITHDRAW', 'OUT', 500000,  6400000, 5900000, 0, 'KRW', 'SUCCESS', 'INTERNET', now() - interval '1 month'  + interval '10 days', 0, '마트 이용', now(), now()),
                -- 1개월 전: 관리비 출금
                ('SEED-TX-009', %1$s, 'WITHDRAW', 'OUT', 300000,  5900000, 5600000, 0, 'KRW', 'SUCCESS', 'INTERNET', now() - interval '1 month'  + interval '15 days', 0, '관리비', now(), now())
                on conflict (transaction_number) do nothing
                """.formatted(acctSub));
    }

    /**
     * 데모 로그인 계정(user01~10·직원·관리자, customer_id 9001~9120)용 입출금 계좌·잔액 시드.
     * <p>상품 카탈로그(banking_product_id=1)가 깔린 뒤 실행되어야 FK 제약을 위반하지 않으므로,
     * Flyway 마이그레이션이 아니라 상품 시딩 이후의 이 런타임 시더에서 처리한다.
     * local 프로파일에서만 동작하며, ON CONFLICT DO NOTHING / GREATEST 로 멱등하다.
     */
    private void seedDemoLoginAccounts() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from deposit_accounts where customer_id = ?",
                Integer.class,
                "9111");
        if (count != null && count > 0) {
            return;
        }

        // 1) 계약(deposit_contracts): 5001~5026 계정별 기본 계좌 / 5201~5203 자가이체용 보조 계좌
        jdbcTemplate.update("""
                insert into deposit_contracts (
                    contract_id, contract_number, customer_id, banking_product_id,
                    is_monthly_payment, join_amount, contract_interest_rate, total_preferential_rate,
                    final_interest_rate, tax_benefit_type, applied_tax_rate, contract_period_month,
                    started_at, maturity_at, is_auto_renewal, auto_transfer_enabled, contract_status,
                    join_channel, is_proxy_joined, is_power_of_attorney_verified,
                    created_at, updated_at, consecutive_miss_count)
                select v.contract_id, v.contract_number, v.customer_id, 1,
                       false, 50000000, 0.10, 0.00, 0.10, 'GENERAL', 15.40, 12,
                       current_date, current_date + interval '12 months', false, false, 'ACTIVE',
                       'WEB', false, false, now(), now(), 0
                from (values
                    (5001, 'SEED-9001-DEP',  '9001'), (5002, 'SEED-9002-DEP',  '9002'),
                    (5003, 'SEED-9003-DEP',  '9003'), (5004, 'SEED-9004-DEP',  '9004'),
                    (5005, 'SEED-9005-DEP',  '9005'), (5006, 'SEED-9006-DEP',  '9006'),
                    (5007, 'SEED-9007-DEP',  '9007'), (5008, 'SEED-9008-DEP',  '9008'),
                    (5009, 'SEED-9009-DEP',  '9009'), (5010, 'SEED-9010-DEP',  '9010'),
                    (5011, 'SEED-9011-DEP',  '9011'), (5012, 'SEED-9101-DEP',  '9101'),
                    (5013, 'SEED-9102-DEP',  '9102'), (5014, 'SEED-9103-DEP',  '9103'),
                    (5015, 'SEED-9104-DEP',  '9104'), (5016, 'SEED-9105-DEP',  '9105'),
                    (5017, 'SEED-9111-DEP',  '9111'), (5018, 'SEED-9112-DEP',  '9112'),
                    (5019, 'SEED-9113-DEP',  '9113'), (5020, 'SEED-9114-DEP',  '9114'),
                    (5021, 'SEED-9115-DEP',  '9115'), (5022, 'SEED-9116-DEP',  '9116'),
                    (5023, 'SEED-9117-DEP',  '9117'), (5024, 'SEED-9118-DEP',  '9118'),
                    (5025, 'SEED-9119-DEP',  '9119'), (5026, 'SEED-9120-DEP',  '9120'),
                    (5201, 'SEED-9111-DEP2', '9111'), (5202, 'SEED-9112-DEP2', '9112'),
                    (5203, 'SEED-9113-DEP2', '9113')
                ) as v(contract_id, contract_number, customer_id)
                on conflict (contract_id) do nothing
                """);

        // 2) 계좌(deposit_accounts): 기본 5천만원 / 보조 3천만원, account_password 는 데모 공용 bcrypt 해시
        jdbcTemplate.update("""
                insert into deposit_accounts (
                    account_number, customer_id, contract_id, account_type, bank_code,
                    balance, currency, account_password,
                    is_withdrawable, is_online_banking_enabled, is_mobile_banking_enabled, is_phone_banking_enabled,
                    account_status, opened_at, created_at, created_by)
                select v.account_number, v.customer_id, v.contract_id, 'DEPOSIT', '001',
                       v.balance, 'KRW', '$2a$10$hTp1Rac9BsYw/6ZzFl..IeTX048JgErR1o.F7GzTM5PuSPLgNTYjG',
                       true, true, true, true,
                       'ACTIVE', current_date, now(), 'seed-v14'
                from (values
                    ('001-2000-0000001', '9001', 5001, 50000000), ('001-2000-0000002', '9002', 5002, 50000000),
                    ('001-2000-0000003', '9003', 5003, 50000000), ('001-2000-0000004', '9004', 5004, 50000000),
                    ('001-2000-0000005', '9005', 5005, 50000000), ('001-2000-0000006', '9006', 5006, 50000000),
                    ('001-2000-0000007', '9007', 5007, 50000000), ('001-2000-0000008', '9008', 5008, 50000000),
                    ('001-2000-0000009', '9009', 5009, 50000000), ('001-2000-0000010', '9010', 5010, 50000000),
                    ('001-2000-0000011', '9011', 5011, 50000000), ('001-2000-0000012', '9101', 5012, 50000000),
                    ('001-2000-0000013', '9102', 5013, 50000000), ('001-2000-0000014', '9103', 5014, 50000000),
                    ('001-2000-0000015', '9104', 5015, 50000000), ('001-2000-0000016', '9105', 5016, 50000000),
                    ('001-2000-0000017', '9111', 5017, 50000000), ('001-2000-0000018', '9112', 5018, 50000000),
                    ('001-2000-0000019', '9113', 5019, 50000000), ('001-2000-0000020', '9114', 5020, 50000000),
                    ('001-2000-0000021', '9115', 5021, 50000000), ('001-2000-0000022', '9116', 5022, 50000000),
                    ('001-2000-0000023', '9117', 5023, 50000000), ('001-2000-0000024', '9118', 5024, 50000000),
                    ('001-2000-0000025', '9119', 5025, 50000000), ('001-2000-0000026', '9120', 5026, 50000000),
                    ('001-2002-0000001', '9111', 5201, 30000000), ('001-2002-0000002', '9112', 5202, 30000000),
                    ('001-2002-0000003', '9113', 5203, 30000000)
                ) as v(account_number, customer_id, contract_id, balance)
                on conflict (account_number) do nothing
                """);

        // 3) 기존 활성 계좌 잔액 보충: 본 시드가 만든 계좌(보조 30M 등)는 건드리지 않는다.
        // 홍길동(9001)은 현금흐름 추천 시나리오용 잔액(V15/V16)을 유지해야 하므로 제외.
        jdbcTemplate.update("""
                update deposit_accounts
                   set balance = greatest(balance, 50000000),
                       updated_at = now(),
                       updated_by = 'seed-v14'
                 where account_status = 'ACTIVE'
                   and balance < 50000000
                   and created_by is distinct from 'seed-v14'
                   and customer_id != '9001'
                """);
    }

    /**
     * 홍길동(9001) 챗봇 추천 시나리오용 데모 데이터를 매 부팅마다 현행화한다.
     * - 잔액: 총 6,200,000원 (현금흐름 채점: 월 잉여 1,640,000 × 12 = 19.68M > 잔액 → 저축 성장형)
     * - 거래 날짜: 항상 90일 cutoff 이내 유지 (Flyway NOW()는 실행 시각에 동결되므로 여기서 처리)
     */
    private void refreshHongKildongDemoData() {
        // 잔액 보정: V15 ON CONFLICT DO NOTHING 으로 50M이 유지된 경우를 교정
        jdbcTemplate.update("UPDATE deposit_accounts SET balance = 5000000.00  WHERE account_id = 2001 AND customer_id = '9001'");
        jdbcTemplate.update("UPDATE deposit_accounts SET balance = 1200000.00  WHERE account_id = 2002 AND customer_id = '9001'");
        jdbcTemplate.update("UPDATE deposit_accounts SET balance = 0.00        WHERE account_id = 2003 AND customer_id = '9001'");
        jdbcTemplate.update("UPDATE deposit_accounts SET balance = 0.00        WHERE account_id = 2004 AND customer_id = '9001'");

        // 거래 날짜 재조정: 90일 cutoff 이내에 항상 위치하도록 매 부팅마다 갱신
        jdbcTemplate.update("UPDATE deposit_transactions SET transaction_at = NOW() - INTERVAL '80 days' WHERE transaction_number = 'TX-9001-M3-IN-01'");
        jdbcTemplate.update("UPDATE deposit_transactions SET transaction_at = NOW() - INTERVAL '75 days' WHERE transaction_number = 'TX-9001-M3-OUT-01'");
        jdbcTemplate.update("UPDATE deposit_transactions SET transaction_at = NOW() - INTERVAL '55 days' WHERE transaction_number = 'TX-9001-M2-IN-01'");
        jdbcTemplate.update("UPDATE deposit_transactions SET transaction_at = NOW() - INTERVAL '45 days' WHERE transaction_number = 'TX-9001-M2-OUT-01'");
        jdbcTemplate.update("UPDATE deposit_transactions SET transaction_at = NOW() - INTERVAL '25 days' WHERE transaction_number = 'TX-9001-M1-IN-01'");
        jdbcTemplate.update("UPDATE deposit_transactions SET transaction_at = NOW() - INTERVAL '15 days' WHERE transaction_number = 'TX-9001-M1-OUT-01'");
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    /**
     * 9001(홍길동) 데모 계좌 시드.
     *
     * <p>원래 {@code V15__seed_employee01_accounts.sql} 마이그레이션이었다. 그 SQL 이
     * 참조하는 상품(deposit_banking_products)은 마이그레이션이 아니라 이 시더가 넣기 때문에,
     * 마이그레이션으로 두면 깨끗한 DB 에서 항상 FK 위반으로 실패했다. 순서를 바로잡기 위해
     * 시더로 옮겼다.
     *
     * <p>SQL 은 Java 로 옮겨쓰지 않고 리소스로 남겼다 — 82줄짜리 INSERT 를 문자열로 끌고
     * 오는 것보다 SQL 파일로 두는 편이 읽고 고치기 쉽다. 스크립트 자체가
     * {@code ON CONFLICT DO NOTHING} 이라 여러 번 실행해도 안전하다.
     */
    private void seedEmployeeDemoAccounts() {
        runSeedScript("db-deposit/seed/employee01-accounts.sql", "9001 데모 계좌");
    }

    /**
     * 이체 시연용 계좌.
     *
     * <p>병합 전에는 DepositBalanceClientMock 에 하드코딩돼 있었다. Mock 은 스텁이면서
     * 시나리오 픽스처를 겸하고 있어서, 걷어내면 한도초과·거액이체 시연도 함께 사라진다.
     * 실제 계좌와 실제 한도 설정으로 옮겨 시연이 실제 원장 위에서 성립하게 한다.
     */
    private void seedPaymentDemoAccounts() {
        runSeedScript("db-deposit/seed/payment-demo-accounts.sql", "이체 시연 계좌");
    }

    /**
     * 시드 SQL 스크립트 실행.
     *
     * <p>SQL 을 Java 문자열로 옮겨쓰지 않고 리소스로 두는 편이 읽고 고치기 쉽다.
     * 스크립트는 모두 {@code ON CONFLICT DO NOTHING} 이라 여러 번 실행해도 안전하다.
     */
    private void runSeedScript(String location, String label) {
        ClassPathResource script = new ClassPathResource(location);
        if (!script.exists()) {
            log.warn("[seed] {} 없음 — {} 를 건너뛴다", location, label);
            return;
        }

        // 이 시더와 **같은 트랜잭션의 연결**로 돌려야 한다.
        //
        // 예전에는 dataSource.getConnection() 으로 새 연결을 열었다. 그러면 스크립트가
        // 별개 트랜잭션에서 돌아, 바로 위에서 JPA 로 만든 상품이 아직 커밋되지 않아
        // 보이지 않는다. 스크립트가 그 상품을 참조하므로 깨끗한 DB 에서는 언제나
        // FK 위반으로 실패했다 — 잡아서 로그만 남기니 기동은 되고 데모 계좌만
        // 조용히 없는 상태가 됐다.
        //
        // DataSourceUtils 는 트랜잭션에 묶인 연결이 있으면 그것을 준다. 닫는 것도
        // 여기서 하지 않는다(트랜잭션이 끝날 때 정리된다).
        var dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            log.warn("[seed] 데이터소스가 없다 — {} 를 건너뛴다", label);
            return;
        }
        var connection = DataSourceUtils.getConnection(dataSource);
        try {
            ScriptUtils.executeSqlScript(connection, script);
            log.info("[seed] {} 적용 완료", label);
        } catch (Exception e) {
            // 시연 데이터 실패가 기동을 막지는 않게 한다. 없으면 화면이 비어 보일 뿐이다.
            log.error("[seed] {} 시드 실패", label, e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
