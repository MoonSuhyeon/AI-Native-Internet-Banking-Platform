package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.*;
import com.bank.deposit.domain.enums.*;
import com.bank.deposit.dto.response.ProductResponse;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.service.ProductService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProductController.class)
@DisplayName("ProductController")
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

    @Test
    @DisplayName("상품 목록을 조회한다")
    void list() throws Exception {
        given(productService.findAllResponses(ProductType.DEPOSIT, ProductStatus.SELLING))
                .willReturn(List.of(ProductResponse.from(product("정기예금"), BigDecimal.valueOf(4.0))));

        mockMvc.perform(get("/products")
                        .param("productType", "DEPOSIT")
                        .param("productStatus", "SELLING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].productName").value("정기예금"))
                .andExpect(jsonPath("$[0].baseInterestRate").value(3.5))
                .andExpect(jsonPath("$[0].bestRate").value(4.0));
    }

    @Test
    @DisplayName("상품을 생성한다")
    void create() throws Exception {
        given(productService.create(eq(ProductType.DEPOSIT), eq("정기예금"), eq("설명"),
                eq(1L), any(), eq(6), eq(12), any(), any(),
                eq(true), eq(false), eq(true), eq("20260101")))
                .willReturn(product("정기예금"));

        mockMvc.perform(post("/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productType": "DEPOSIT",
                                  "productName": "정기예금",
                                  "description": "설명",
                                  "departmentId": 1,
                                  "baseInterestRate": 3.5,
                                  "minJoinAmount": 100000,
                                  "maxJoinAmount": 10000000,
                                  "minPeriodMonth": 6,
                                  "maxPeriodMonth": 12,
                                  "isEarlyTerminationAllowed": true,
                                  "isTaxBenefitAvailable": false,
                                  "isAutoRenewalAvailable": true,
                                  "releasedAt": "20260101"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productName").value("정기예금"));
    }

    @Test
    @DisplayName("상품 단건을 조회한다")
    void getById() throws Exception {
        given(productService.findResponseById(1L))
                .willReturn(ProductResponse.from(product("정기예금"), BigDecimal.valueOf(4.0)));

        mockMvc.perform(get("/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("정기예금"))
                .andExpect(jsonPath("$.bestRate").value(4.0));
    }

    @Test
    @DisplayName("존재하지 않는 상품 조회 시 404를 반환한다")
    void getNotFound() throws Exception {
        given(productService.findResponseById(999L))
                .willThrow(new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        mockMvc.perform(get("/products/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("상품 기본 정보를 수정한다")
    void update() throws Exception {
        given(productService.update(1L, "수정 예금", "수정 설명", BigDecimal.valueOf(4.0)))
                .willReturn(product("수정 예금"));

        mockMvc.perform(put("/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productName": "수정 예금",
                                  "description": "수정 설명",
                                  "baseInterestRate": 4.0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productName").value("수정 예금"));
    }

    @Test
    @DisplayName("상품 상태를 변경한다")
    void changeStatus() throws Exception {
        given(productService.changeStatus(1L, ProductStatus.SUSPENDED))
                .willReturn(Product.builder()
                        .productType(ProductType.DEPOSIT)
                        .productName("정기예금")
                        .baseInterestRate(BigDecimal.valueOf(3.5))
                        .productStatus(ProductStatus.SUSPENDED)
                        .build());

        mockMvc.perform(patch("/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productStatus\": \"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productStatus").value("SUSPENDED"));
    }

    @Test
    @DisplayName("예금 상품 상세를 생성한다")
    void createDeposit() throws Exception {
        given(productService.createDepositProduct(1L, DepositType.TERM, true))
                .willReturn(depositProduct());

        mockMvc.perform(post("/products/1/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "depositType": "TERM",
                                  "isCompoundInterest": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.depositType").value("TERM"));
    }

    @Test
    @DisplayName("예금 상품 상세를 조회한다")
    void getDeposit() throws Exception {
        given(productService.findDepositProduct(1L)).willReturn(depositProduct());

        mockMvc.perform(get("/products/1/deposit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.depositType").value("TERM"));
    }

    @Test
    @DisplayName("예금 상품 상세를 수정한다")
    void updateDeposit() throws Exception {
        given(productService.updateDepositProduct(1L, DepositType.DEMAND, false))
                .willReturn(DepositProduct.builder()
                        .productId(1L)
                        .depositType(DepositType.DEMAND)
                        .isCompoundInterest(false)
                        .build());

        mockMvc.perform(put("/products/1/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "depositType": "DEMAND",
                                  "isCompoundInterest": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.depositType").value("DEMAND"));
    }

    @Test
    @DisplayName("적금 상품 상세를 생성한다")
    void createSavings() throws Exception {
        given(productService.createSavingsProduct(eq(1L), eq(SavingType.FREE), any(), any()))
                .willReturn(savingsProduct());

        mockMvc.perform(post("/products/1/savings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "savingType": "FREE",
                                  "monthlyPaymentMinAmount": 10000,
                                  "monthlyPaymentMaxAmount": 1000000
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.savingType").value("FREE"));
    }

    @Test
    @DisplayName("적금 상품 상세를 조회한다")
    void getSavings() throws Exception {
        given(productService.findSavingsProduct(1L)).willReturn(savingsProduct());

        mockMvc.perform(get("/products/1/savings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savingType").value("FREE"));
    }

    @Test
    @DisplayName("적금 상품 상세를 수정한다")
    void updateSavings() throws Exception {
        given(productService.updateSavingsProduct(eq(1L), eq(SavingType.REGULAR), any(), any()))
                .willReturn(SavingsProduct.builder()
                        .productId(1L)
                        .savingType(SavingType.REGULAR)
                        .monthlyPaymentMinAmount(BigDecimal.valueOf(20_000))
                        .monthlyPaymentMaxAmount(BigDecimal.valueOf(2_000_000))
                        .build());

        mockMvc.perform(put("/products/1/savings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "savingType": "REGULAR",
                                  "monthlyPaymentMinAmount": 20000,
                                  "monthlyPaymentMaxAmount": 2000000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.savingType").value("REGULAR"));
    }

    @Test
    @DisplayName("청약 상품 상세를 생성한다")
    void createSubscription() throws Exception {
        given(productService.createSubscriptionProduct(eq(1L), any(), any(), any(), any()))
                .willReturn(subscriptionProduct());

        mockMvc.perform(post("/products/1/subscription")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "monthlyPaymentAmount": 100000,
                                  "minMonthlyPayment": 20000,
                                  "maxMonthlyPayment": 500000,
                                  "maxRecognizedPaymentAmount": 100000
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.monthlyPaymentAmount").value(100000));
    }

    @Test
    @DisplayName("청약 상품 상세를 조회한다")
    void getSubscription() throws Exception {
        given(productService.findSubscriptionProduct(1L)).willReturn(subscriptionProduct());

        mockMvc.perform(get("/products/1/subscription"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyPaymentAmount").value(100000));
    }

    @Test
    @DisplayName("청약 상품 상세를 수정한다")
    void updateSubscription() throws Exception {
        given(productService.updateSubscriptionProduct(eq(1L), any(), any(), any()))
                .willReturn(SubscriptionProduct.builder()
                        .productId(1L)
                        .monthlyPaymentAmount(BigDecimal.valueOf(200_000))
                        .minMonthlyPayment(BigDecimal.valueOf(30_000))
                        .maxMonthlyPayment(BigDecimal.valueOf(600_000))
                        .build());

        mockMvc.perform(put("/products/1/subscription")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "monthlyPaymentAmount": 200000,
                                  "minMonthlyPayment": 30000,
                                  "maxMonthlyPayment": 600000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyPaymentAmount").value(200000));
    }

    @Test
    @DisplayName("가입 채널 목록을 조회한다")
    void getJoinChannels() throws Exception {
        given(productService.findJoinChannels(1L))
                .willReturn(List.of(ProductJoinChannel.builder().productId(1L).joinChannelCode(JoinChannel.MOBILE).build()));

        mockMvc.perform(get("/products/1/join-channels"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].joinChannelCode").value("MOBILE"));
    }

    @Test
    @DisplayName("가입 채널을 추가한다")
    void addJoinChannel() throws Exception {
        given(productService.addJoinChannel(1L, JoinChannel.MOBILE))
                .willReturn(ProductJoinChannel.builder().productId(1L).joinChannelCode(JoinChannel.MOBILE).build());

        mockMvc.perform(post("/products/1/join-channels")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"joinChannelCode\": \"MOBILE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.joinChannelCode").value("MOBILE"));
    }

    @Test
    @DisplayName("상품 대상 그룹을 연결한다")
    void linkTargetGroup() throws Exception {
        given(productService.linkTargetGroup(1L, 10L))
                .willReturn(ProductTargetGroup.builder().id(new ProductTargetGroupId(1L, 10L)).build());

        mockMvc.perform(post("/products/1/target-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetGroupId\": 10}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id.productId").value(1))
                .andExpect(jsonPath("$.id.targetGroupId").value(10));
    }

    @Test
    @DisplayName("상품 대상 그룹 목록을 조회한다")
    void getProductTargetGroups() throws Exception {
        given(productService.findProductTargetGroups(1L))
                .willReturn(List.of(ProductTargetGroup.builder().id(new ProductTargetGroupId(1L, 10L)).build()));

        mockMvc.perform(get("/products/1/target-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id.targetGroupId").value(10));
    }

    @Test
    @DisplayName("상품 금리 목록을 조회한다")
    void getInterestRates() throws Exception {
        given(productService.findInterestRates(1L)).willReturn(List.of(interestRate()));

        mockMvc.perform(get("/products/1/interest-rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].rateType").value("BASE"));
    }

    @Test
    @DisplayName("상품 금리를 생성한다")
    void createInterestRate() throws Exception {
        given(productService.createInterestRate(eq(1L), eq(RateType.BASE), any(), eq("20260101"),
                eq(6), eq(12), any(), any(), eq("기본금리")))
                .willReturn(interestRate());

        mockMvc.perform(post("/products/1/interest-rates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rateType": "BASE",
                                  "rate": 3.5,
                                  "effectiveStartDate": "20260101",
                                  "minimumContractPeriod": 6,
                                  "maximumContractPeriod": 12,
                                  "minimumJoinAmount": 100000,
                                  "maximumJoinAmount": 10000000,
                                  "conditionDescription": "기본금리"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rateType").value("BASE"));
    }

    @Test
    @DisplayName("상품 금리를 만료한다")
    void expireInterestRate() throws Exception {
        ProductInterestRate expired = ProductInterestRate.builder()
                .productId(1L)
                .rateType(RateType.BASE)
                .rate(BigDecimal.valueOf(3.5))
                .effectiveStartDate("20260101")
                .isActive(false)
                .build();
        given(productService.expireInterestRate(1L, 2L)).willReturn(expired);

        mockMvc.perform(patch("/products/1/interest-rates/2/expire"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));
    }

    @Test
    @DisplayName("상품 금리 단건을 조회한다")
    void getInterestRate() throws Exception {
        given(productService.findInterestRate(1L, 2L)).willReturn(interestRate());

        mockMvc.perform(get("/products/1/interest-rates/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateType").value("BASE"));
    }

    @Test
    @DisplayName("상품 금리를 수정한다")
    void updateInterestRate() throws Exception {
        given(productService.updateInterestRate(eq(1L), eq(2L), any(), eq("20261231")))
                .willReturn(ProductInterestRate.builder()
                        .productId(1L)
                        .rateType(RateType.BASE)
                        .rate(BigDecimal.valueOf(3.8))
                        .effectiveStartDate("20260101")
                        .effectiveEndDate("20261231")
                        .build());

        mockMvc.perform(put("/products/1/interest-rates/2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rate": 3.8,
                                  "effectiveEndDate": "20261231"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(3.8));
    }

    @Test
    @DisplayName("상품 특약 목록을 조회한다")
    void getProductSpecialTerms() throws Exception {
        given(productService.findProductSpecialTerms(1L))
                .willReturn(List.of(ProductSpecialTerm.builder().productId(1L).specialTermId(7L).isRequired(true).build()));

        mockMvc.perform(get("/products/1/special-terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].specialTermId").value(7));
    }

    @Test
    @DisplayName("상품 특약을 연결한다")
    void linkSpecialTerm() throws Exception {
        given(productService.linkSpecialTerm(1L, 7L, true))
                .willReturn(ProductSpecialTerm.builder().productId(1L).specialTermId(7L).isRequired(true).build());

        mockMvc.perform(post("/products/1/special-terms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "specialTermId": 7,
                                  "isRequired": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.specialTermId").value(7));
    }

    @Test
    @DisplayName("상품 하위 리소스를 삭제한다")
    void deleteChildResources() throws Exception {
        mockMvc.perform(delete("/products/1/deposit"))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/products/1/join-channels/2"))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/products/1/target-groups/10"))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/products/1/special-terms/7"))
                .andExpect(status().isNoContent());
    }

    private Product product(String name) {
        return Product.builder()
                .productType(ProductType.DEPOSIT)
                .productName(name)
                .description("설명")
                .baseInterestRate(BigDecimal.valueOf(3.5))
                .productStatus(ProductStatus.SELLING)
                .build();
    }

    private DepositProduct depositProduct() {
        return DepositProduct.builder()
                .productId(1L)
                .depositType(DepositType.TERM)
                .isCompoundInterest(true)
                .build();
    }

    private SavingsProduct savingsProduct() {
        return SavingsProduct.builder()
                .productId(1L)
                .savingType(SavingType.FREE)
                .monthlyPaymentMinAmount(BigDecimal.valueOf(10_000))
                .monthlyPaymentMaxAmount(BigDecimal.valueOf(1_000_000))
                .build();
    }

    private SubscriptionProduct subscriptionProduct() {
        return SubscriptionProduct.builder()
                .productId(1L)
                .monthlyPaymentAmount(BigDecimal.valueOf(100_000))
                .minMonthlyPayment(BigDecimal.valueOf(20_000))
                .maxMonthlyPayment(BigDecimal.valueOf(500_000))
                .maxRecognizedPaymentAmount(BigDecimal.valueOf(100_000))
                .build();
    }

    private ProductInterestRate interestRate() {
        return ProductInterestRate.builder()
                .productId(1L)
                .rateType(RateType.BASE)
                .rate(BigDecimal.valueOf(3.5))
                .effectiveStartDate("20260101")
                .minimumContractPeriod(6)
                .maximumContractPeriod(12)
                .conditionDescription("기본금리")
                .build();
    }
}
