package com.bank.loan;

import com.bank.commonaccount.contract.domain.CommonContract;
import com.bank.commonaccount.contract.repository.CommonContractRepository;
import com.bank.commonaccount.product.domain.CommonProduct;
import com.bank.commonaccount.product.repository.CommonProductRepository;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.commonsync.dto.CommonSyncDispatchSummary;
import com.bank.loan.commonsync.outbox.CommonSyncOutbox;
import com.bank.loan.commonsync.outbox.CommonSyncOutboxRepository;
import com.bank.loan.commonsync.service.CommonSyncBackfillService;
import com.bank.loan.commonsync.service.CommonSyncDispatchService;
import com.bank.loan.contract.domain.LoanContract;
import com.bank.loan.contract.repository.LoanContractRepository;
import com.bank.loan.product.domain.LoanProduct;
import com.bank.loan.product.repository.LoanProductRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * common_db write-through 동기화 통합 테스트 (B-5).
 *
 * 시나리오 순서:
 *   10  상품 등록 시 PRODUCT outbox PENDING 적재 검증
 *   20  dispatch → common_product 생성 + LoanProduct.productId 백필 + outbox DONE 전이
 *   30  계약 SIGNED 시 outbox 없음 검증
 *   40  최초 인출 → 계약 ACTIVE 전이 + CONTRACT outbox PENDING 적재
 *   50  dispatch → common_contract 생성 + LoanContract.contractId 백필 + outbox DONE 전이
 *   60  멱등 — 동일 상품 outbox requeue 후 재 dispatch → common_id 불변
 *   70  백필(상품) — outbox 삭제 후 backfillProducts → dispatch → productId 재백필
 *   80  백필(계약) — outbox 삭제 후 backfillContracts → dispatch → contractId 재백필
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommonSyncFlowTest extends AbstractLoanIntegrationTest {

    @Autowired private CommonSyncOutboxRepository outboxRepository;
    @Autowired private CommonSyncDispatchService  dispatchService;
    @Autowired private CommonSyncBackfillService  backfillService;
    @Autowired private CommonProductRepository    commonProductRepository;
    @Autowired private CommonContractRepository   commonContractRepository;
    @Autowired private LoanProductRepository      loanProductRepository;
    @Autowired private LoanContractRepository     loanContractRepository;
    @Autowired private LoanApplicationRepository  applicationRepository;

    private static final long CONTRACTED_AMOUNT = 5_000_000L;
    private static final int  PERIOD_MONTHS     = 12;

    private Long   prodId;
    private String prodCd;
    private Long   applId;
    private Long   cntrId;

    @BeforeAll
    void setup() throws Exception {
        prodCd = "SYNC_" + uniq();
        prodId = createProduct(prodCd);
        activateProduct(prodId);
        applId = createApplication(prodId);
        forceApprove(applId);
        cntrId = createContract(applId);
        registerAndVerifyRepaymentAccount(cntrId);
    }

    // ------------------------------------------------------------------
    // 10: 상품 등록 시 PRODUCT outbox 적재
    // ------------------------------------------------------------------

    @Test @Order(10)
    void 상품_등록시_PRODUCT_outbox_PENDING_적재됨() {
        CommonSyncOutbox outbox = outboxRepository
                .findByIdempotencyKeyAndDeletedAtIsNull(productKey(prodId)).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo(CommonSyncOutbox.STATUS_PENDING);
        assertThat(outbox.getTargetTypeCd()).isEqualTo(CommonSyncOutbox.TARGET_PRODUCT);
        assertThat(outbox.getSourceNo()).isEqualTo(prodCd);
        assertThat(outbox.getPayload()).isNotBlank();
        assertThat(outbox.getCommonId()).isNull();
    }

    // ------------------------------------------------------------------
    // 20: 상품 dispatch
    // ------------------------------------------------------------------

    @Test @Order(20)
    void 상품_dispatch_후_common_product_생성_및_productId_백필() {
        CommonSyncDispatchSummary summary = dispatchService.dispatch();
        assertThat(summary.done()).isGreaterThanOrEqualTo(1);

        // outbox DONE
        CommonSyncOutbox outbox = outboxRepository
                .findByIdempotencyKeyAndDeletedAtIsNull(productKey(prodId)).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo(CommonSyncOutbox.STATUS_DONE);
        assertThat(outbox.getCommonId()).isNotNull();
        assertThat(outbox.getSyncedAt()).isNotNull();

        // common_product 생성
        CommonProduct cp = commonProductRepository.findByProductCd(prodCd).orElseThrow();
        assertThat(cp.getProductId()).isNotNull();
        assertThat(cp.getProductName()).isNotBlank();
        assertThat(cp.getBizDivCd()).isEqualTo("LOAN");

        // LoanProduct.productId 백필
        LoanProduct lp = loanProductRepository.findByProdIdAndDeletedAtIsNull(prodId).orElseThrow();
        assertThat(lp.getProductId()).isEqualTo(cp.getProductId());
        assertThat(lp.getProductId()).isEqualTo(outbox.getCommonId());
    }

    // ------------------------------------------------------------------
    // 30: 계약 SIGNED — outbox 없음
    // ------------------------------------------------------------------

    @Test @Order(30)
    void 계약_SIGNED_시_CONTRACT_outbox_없음() {
        assertThat(
                outboxRepository.findByIdempotencyKeyAndDeletedAtIsNull(contractKey(cntrId))
        ).isEmpty();
    }

    // ------------------------------------------------------------------
    // 40: 최초 인출 → 계약 ACTIVE + CONTRACT outbox
    // ------------------------------------------------------------------

    @Test @Order(40)
    void 최초_인출_후_계약ACTIVE전이_및_CONTRACT_outbox_PENDING_적재() throws Exception {
        firstDrawdown(cntrId, 2_000_000L);

        LoanContract contract = loanContractRepository.findByCntrIdAndDeletedAtIsNull(cntrId).orElseThrow();
        assertThat(contract.getCntrStatusCd()).isEqualTo(LoanContract.STATUS_ACTIVE);

        CommonSyncOutbox outbox = outboxRepository
                .findByIdempotencyKeyAndDeletedAtIsNull(contractKey(cntrId)).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo(CommonSyncOutbox.STATUS_PENDING);
        assertThat(outbox.getTargetTypeCd()).isEqualTo(CommonSyncOutbox.TARGET_CONTRACT);
        assertThat(outbox.getCommonId()).isNull();
    }

    // ------------------------------------------------------------------
    // 50: 계약 dispatch
    // ------------------------------------------------------------------

    @Test @Order(50)
    void 계약_dispatch_후_common_contract_생성_및_contractId_백필() {
        // 전제: CONTRACT outbox 가 PENDING 상태여야 함 (test 40 에서 적재됨)
        CommonSyncOutbox pending = outboxRepository
                .findByIdempotencyKeyAndDeletedAtIsNull(contractKey(cntrId)).orElseThrow(
                        () -> new AssertionError("CONTRACT outbox not found before dispatch"));
        assertThat(pending.getStatus()).as("CONTRACT outbox should be PENDING before dispatch")
                .isEqualTo(CommonSyncOutbox.STATUS_PENDING);

        CommonSyncDispatchSummary summary = dispatchService.dispatch();
        assertThat(summary.done()).isGreaterThanOrEqualTo(1);

        // outbox DONE
        CommonSyncOutbox outbox = outboxRepository
                .findByIdempotencyKeyAndDeletedAtIsNull(contractKey(cntrId)).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo(CommonSyncOutbox.STATUS_DONE);
        assertThat(outbox.getCommonId()).isNotNull();

        // LoanContract.contractId 백필
        LoanContract lc = loanContractRepository.findByCntrIdAndDeletedAtIsNull(cntrId).orElseThrow();
        assertThat(lc.getContractId()).isNotNull();
        assertThat(lc.getContractId()).isEqualTo(outbox.getCommonId());

        // common_contract 생성
        CommonContract cc = commonContractRepository.findByContractNo(lc.getCntrNo()).orElseThrow();
        assertThat(cc.getContractId()).isEqualTo(lc.getContractId());
        assertThat(cc.getProductId()).isNotNull();       // LoanProduct.productId 와 연결
        assertThat(cc.getContractAmount()).isEqualTo(CONTRACTED_AMOUNT);
    }

    // ------------------------------------------------------------------
    // 60: 멱등 — 같은 상품 outbox requeue 후 재 dispatch → common_id 불변
    // ------------------------------------------------------------------

    @Test @Order(60)
    void 상품_동기화_멱등_재dispatch_후_commonId_불변() {
        Long before = loanProductRepository.findByProdIdAndDeletedAtIsNull(prodId)
                .orElseThrow().getProductId();
        assertThat(before).isNotNull();

        // outbox 를 PENDING 으로 되돌려 재처리 유도
        CommonSyncOutbox outbox = outboxRepository
                .findByIdempotencyKeyAndDeletedAtIsNull(productKey(prodId)).orElseThrow();
        outbox.requeue(OffsetDateTime.now());
        outboxRepository.save(outbox);

        dispatchService.dispatch();

        // common_id 불변 — common_db 의 product_cd UNIQUE 덕분에 기존 row 재사용
        Long after = loanProductRepository.findByProdIdAndDeletedAtIsNull(prodId)
                .orElseThrow().getProductId();
        assertThat(after).isEqualTo(before);

        CommonProduct cp = commonProductRepository.findByProductCd(prodCd).orElseThrow();
        assertThat(cp.getProductId()).isEqualTo(before);
    }

    // ------------------------------------------------------------------
    // 70: 상품 백필 — outbox 삭제 → backfillProducts → dispatch
    // ------------------------------------------------------------------

    @Test @Order(70)
    void 상품_백필_outbox_삭제후_backfill_및_dispatch() throws Exception {
        // 새 상품 등록 (→ outbox 자동 적재)
        String bkfProdCd = "BKF_" + uniq();
        Long bkfProdId = createProduct(bkfProdCd);

        // outbox 삭제 → 미동기 상태 시뮬레이션
        outboxRepository.findByIdempotencyKeyAndDeletedAtIsNull(productKey(bkfProdId))
                .ifPresent(o -> outboxRepository.delete(o));
        assertThat(outboxRepository.findByIdempotencyKeyAndDeletedAtIsNull(productKey(bkfProdId)))
                .isEmpty();
        assertThat(loanProductRepository.findByProdIdAndDeletedAtIsNull(bkfProdId)
                .orElseThrow().getProductId()).isNull();

        // 백필 호출 — 적어도 해당 상품 1건 이상 재적재
        int enqueued = backfillService.backfillProducts(500);
        assertThat(enqueued).isGreaterThanOrEqualTo(1);

        // outbox 재적재 확인
        CommonSyncOutbox outbox = outboxRepository
                .findByIdempotencyKeyAndDeletedAtIsNull(productKey(bkfProdId)).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo(CommonSyncOutbox.STATUS_PENDING);
        assertThat(outbox.getSourceNo()).isEqualTo(bkfProdCd);

        // dispatch → productId 백필
        dispatchService.dispatch();

        LoanProduct lp = loanProductRepository.findByProdIdAndDeletedAtIsNull(bkfProdId).orElseThrow();
        assertThat(lp.getProductId()).isNotNull();
        assertThat(commonProductRepository.findByProductCd(bkfProdCd)).isPresent();
    }

    // ------------------------------------------------------------------
    // 80: 계약 백필 — outbox 삭제 → backfillContracts → dispatch
    // ------------------------------------------------------------------

    @Test @Order(80)
    void 계약_백필_outbox_삭제후_backfill_및_dispatch() throws Exception {
        // 독립 데이터: 상품 → dispatch(productId 채움) → 신청 → 계약 → 최초 인출
        String bkfProdCd = "BKF2_" + uniq();
        Long bkfProdId = createProduct(bkfProdCd);
        activateProduct(bkfProdId);
        dispatchService.dispatch(); // 상품 동기화 (contractDispatch 의 resolveParentCommonId 에 필요)

        Long bkfApplId = createApplication(bkfProdId);
        forceApprove(bkfApplId);
        Long bkfCntrId = createContract(bkfApplId);
        registerAndVerifyRepaymentAccount(bkfCntrId);
        firstDrawdown(bkfCntrId, 1_000_000L); // → CONTRACT outbox 적재

        // outbox 삭제 → 미동기 상태 시뮬레이션
        outboxRepository.findByIdempotencyKeyAndDeletedAtIsNull(contractKey(bkfCntrId))
                .ifPresent(o -> outboxRepository.delete(o));
        assertThat(loanContractRepository.findByCntrIdAndDeletedAtIsNull(bkfCntrId)
                .orElseThrow().getContractId()).isNull();

        // 백필 호출
        int enqueued = backfillService.backfillContracts(500);
        assertThat(enqueued).isGreaterThanOrEqualTo(1);

        CommonSyncOutbox outbox = outboxRepository
                .findByIdempotencyKeyAndDeletedAtIsNull(contractKey(bkfCntrId)).orElseThrow();
        assertThat(outbox.getStatus()).isEqualTo(CommonSyncOutbox.STATUS_PENDING);

        // dispatch → contractId 백필
        dispatchService.dispatch();

        LoanContract lc = loanContractRepository.findByCntrIdAndDeletedAtIsNull(bkfCntrId).orElseThrow();
        assertThat(lc.getContractId()).isNotNull();
        CommonContract cc = commonContractRepository.findByContractNo(lc.getCntrNo()).orElseThrow();
        assertThat(cc.getContractId()).isEqualTo(lc.getContractId());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static String productKey(Long prodId) {
        return CommonSyncOutbox.TARGET_PRODUCT + ":" + prodId;
    }

    private static String contractKey(Long cntrId) {
        return CommonSyncOutbox.TARGET_CONTRACT + ":" + cntrId;
    }

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private Long createProduct(String prodCd) throws Exception {
        String body = """
                {
                  "prodCd":"%s", "prodName":"동기화 테스트 상품", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":500,
                  "minAmount":1000000, "maxAmount":100000000,
                  "minPeriodMo":12, "maxPeriodMo":60,
                  "collateralRequiredYn":false, "guarantorRequiredYn":false
                }
                """.formatted(prodCd);
        MvcResult result = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("prodId").asLong();
    }

    private void activateProduct(Long prodId) throws Exception {
        mockMvc.perform(patch("/api/loan-products/{prodId}", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"prodStatusCd\":\"ACTIVE\" }"))
                .andExpect(status().isOk());
    }

    private Long createApplication(Long prodId) throws Exception {
        String body = """
                {
                  "customerId":9901, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":%d, "requestedPeriodMo":%d,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId, CONTRACTED_AMOUNT, PERIOD_MONTHS);
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("applId").asLong();
    }

    private void forceApprove(Long applId) {
        LoanApplication app = applicationRepository.findByApplIdAndDeletedAtIsNull(applId).orElseThrow();
        app.markApproved();
        applicationRepository.save(app);
    }

    private Long createContract(Long applId) throws Exception {
        String body = """
                {
                  "applId":%d,
                  "contractedAmount":%d,
                  "contractedPeriodMo":%d,
                  "baseRateBps":500,
                  "rateTypeCd":"FIXED",
                  "repaymentMethodCd":"EQUAL"
                }
                """.formatted(applId, CONTRACTED_AMOUNT, PERIOD_MONTHS);
        MvcResult result = mockMvc.perform(post("/api/loan-contracts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("cntrId").asLong();
    }

    private void registerAndVerifyRepaymentAccount(Long cntrId) throws Exception {
        String body = """
                { "bankCd":"088", "accountNo":"1102345678901", "holderName":"홍길동",
                  "autoDebitYn":true, "debitDay":15 }
                """;
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/repayment-account/verify", cntrId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    private void firstDrawdown(Long cntrId, long amount) throws Exception {
        mockMvc.perform(post("/api/loan-contracts/{cntrId}/executions", cntrId)
                        .header("Idempotency-Key", "sync-exec-" + cntrId + "-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "executedAmount":%d,
                                  "disbursementBankCd":"088",
                                  "disbursementAccountNo":"1109999998888"
                                }
                                """.formatted(amount)))
                .andExpect(status().isCreated());
    }
}
