package com.bank.loan;

import com.bank.loan.product.domain.LoanProduct;
import com.bank.loan.product.repository.LoanProductRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Soft delete 동작 + 낙관적 락(@Version) 충돌 검증.
 */
class SoftDeleteAndLockingTest extends AbstractLoanIntegrationTest {

    @Autowired LoanProductRepository productRepo;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeAll
    void initTx() {
        tx = new TransactionTemplate(txManager);
    }

    // ============================================================
    // Soft delete — 서류
    // ============================================================

    @Test
    void 서류_삭제_후_목록에서_누락() throws Exception {
        Long prodId = createActiveProduct();
        Long applId = createApplication(prodId);
        Long keepDocId = uploadDocument(applId, "keep.pdf");
        Long delDocId = uploadDocument(applId, "delete.pdf");

        mockMvc.perform(delete("/api/loan-documents/{docId}", delDocId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.docStatusCd").value("DELETED"));

        // 목록 조회 결과: keepDocId 만 보이고 delDocId 는 누락
        mockMvc.perform(get("/api/loan-applications/{applId}/documents", applId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.items[0].docId").value(keepDocId));
    }

    @Test
    void 서류_삭제_후_다운로드_404() throws Exception {
        Long prodId = createActiveProduct();
        Long applId = createApplication(prodId);
        Long docId = uploadDocument(applId, "x.pdf");

        mockMvc.perform(delete("/api/loan-documents/{docId}", docId))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/loan-documents/{docId}/download", docId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_041"));
    }

    // ============================================================
    // 담보 — 해제는 soft delete 가 아님, 목록에 그대로 남음
    // ============================================================

    @Test
    void 담보_해제는_soft_delete_가_아니라_목록에_남음() throws Exception {
        Long prodId = createActiveProduct();
        Long applId = createApplication(prodId);
        Long colId = createCollateral(applId);

        mockMvc.perform(post("/api/collaterals/{colId}/release", colId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "releaseReasonCd":"LOAN_PAID" }
                                """))
                .andExpect(status().isOk());

        // 해제된 담보도 deleted_at IS NULL 이므로 목록에 노출됨 (status 만 RELEASED)
        mockMvc.perform(get("/api/loan-applications/{applId}/collaterals", applId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.items[0].colId").value(colId))
                .andExpect(jsonPath("$.data.items[0].colStatusCd").value("RELEASED"));
    }

    // ============================================================
    // 낙관적 락 — @Version 충돌
    // ============================================================

    @Test
    void 동시_수정_시_낙관적_락_충돌() throws Exception {
        Long prodId = createActiveProduct();

        // 1) stale 로드 (version=v0)
        LoanProduct stale = tx.execute(s -> productRepo.findById(prodId).orElseThrow());
        assertThat(stale).isNotNull();
        Integer staleVersion = stale.getVersion();

        // 2) 별도 트랜잭션에서 동일 행 갱신 → DB version 증가
        tx.executeWithoutResult(s -> {
            LoanProduct fresh = productRepo.findById(prodId).orElseThrow();
            fresh.update(
                    null, null, null, null, null,
                    999, null, null,
                    null, null, null, null,
                    null, null, null, null,
                    null, null, null, null,
                    null
            );
        });

        // 3) stale 인스턴스(이전 version) 로 저장 시도 → ObjectOptimisticLockingFailureException
        stale.update(
                null, null, null, null, null,
                111, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null
        );
        assertThatThrownBy(() ->
                tx.executeWithoutResult(s -> productRepo.saveAndFlush(stale))
        ).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // 검증: DB 상의 최종 값은 첫 번째 갱신 결과(999) — 두 번째 시도는 충돌로 무산
        LoanProduct latest = tx.execute(s -> productRepo.findById(prodId).orElseThrow());
        assertThat(latest.getBaseRateBps()).isEqualTo(999);
        assertThat(latest.getVersion()).isEqualTo(staleVersion + 1);
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createActiveProduct() throws Exception {
        String body = """
                {
                  "prodCd":"LOCK_%s", "prodName":"테스트", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":450,
                  "minAmount":1000000, "maxAmount":10000000,
                  "minPeriodMo":12, "maxPeriodMo":60,
                  "collateralRequiredYn":false, "guarantorRequiredYn":false
                }
                """.formatted(uniq());
        MvcResult r = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        Long prodId = extractData(r).get("prodId").asLong();
        mockMvc.perform(patch("/api/loan-products/{prodId}", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "prodStatusCd":"ACTIVE" }
                                """))
                .andExpect(status().isOk());
        return prodId;
    }

    private Long createApplication(Long prodId) throws Exception {
        String body = """
                {
                  "customerId":5001, "prodId":%d, "channelCd":"MOBILE",
                  "requestedAmount":5000000, "requestedPeriodMo":24,
                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                }
                """.formatted(prodId);
        MvcResult r = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(r).get("applId").asLong();
    }

    private Long uploadDocument(Long applId, String filename) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", filename, MediaType.APPLICATION_PDF_VALUE, ("content of " + filename).getBytes());
        MvcResult r = mockMvc.perform(multipart("/api/loan-applications/{applId}/documents", applId)
                        .file(file)
                        .param("docTypeCd", "INCOME_PROOF"))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(r).get("docId").asLong();
    }

    private Long createCollateral(Long applId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/loan-applications/{applId}/collaterals", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "colTypeCd":"REAL_ESTATE", "colName":"테스트담보", "declaredValue":300000000 }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(r).get("colId").asLong();
    }
}
