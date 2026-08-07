package com.bank.loan;

import com.bank.common.audit.StatusHistory;
import com.bank.common.audit.StatusHistoryRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @TransactionalEventListener(BEFORE_COMMIT) 가 실제로 status_history 행을 남기는지 검증.
 *
 * BEFORE_COMMIT 페이즈로 도메인 변경과 동일 트랜잭션에서 INSERT 되므로
 *   - 정상 커밋되면 행 존재
 *   - 컨트롤러에서 예외(409 등) 가 던져지면 트랜잭션 롤백 → 행 없음
 */
class StatusHistoryAuditTest extends AbstractLoanIntegrationTest {

    private static final String DOMAIN = "LOAN";

    @Autowired StatusHistoryRepository statusHistoryRepository;

    @Test
    void 상품_단종_상태이력_기록() throws Exception {
        Long prodId = createProductAndActivate();

        mockMvc.perform(post("/api/loan-products/{prodId}/discontinue", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "saleEndDate":"20261231", "reasonCd":"REGULATORY" }
                                """))
                .andExpect(status().isOk());

        List<StatusHistory> rows = statusHistoryRepository
                .findByTargetDomainCdAndTargetTableCdAndTargetIdOrderByChangedAtAsc(
                        DOMAIN, "LOAN_PRODUCT", prodId);

        assertThat(rows).hasSize(1);
        StatusHistory row = rows.get(0);
        assertThat(row.getBeforeStatusCd()).isEqualTo("ACTIVE");
        assertThat(row.getAfterStatusCd()).isEqualTo("DISCONTINUED");
        assertThat(row.getChangeReasonCd()).isEqualTo("REGULATORY");
        assertThat(row.getChangedAt()).isNotNull();
    }

    @Test
    void 신청_생성_상태이력_기록() throws Exception {
        Long prodId = createProductAndActivate();
        Long applId = createApplication(prodId);

        List<StatusHistory> rows = statusHistoryRepository
                .findByTargetDomainCdAndTargetTableCdAndTargetIdOrderByChangedAtAsc(
                        DOMAIN, "LOAN_APPLICATION", applId);

        assertThat(rows).hasSize(1);
        StatusHistory row = rows.get(0);
        assertThat(row.getBeforeStatusCd()).isNull();
        assertThat(row.getAfterStatusCd()).isEqualTo("SUBMITTED");
        assertThat(row.getChangeReasonCd()).isEqualTo("APPLICATION_SUBMITTED");
    }

    @Test
    void 신청_생성_후_취소_상태이력_2건() throws Exception {
        Long prodId = createProductAndActivate();
        Long applId = createApplication(prodId);

        mockMvc.perform(post("/api/loan-applications/{applId}/cancel", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "cancelReasonCd":"CUST_REQ", "cancelRemark":"마음바뀜" }
                                """))
                .andExpect(status().isOk());

        List<StatusHistory> rows = statusHistoryRepository
                .findByTargetDomainCdAndTargetTableCdAndTargetIdOrderByChangedAtAsc(
                        DOMAIN, "LOAN_APPLICATION", applId);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getAfterStatusCd()).isEqualTo("SUBMITTED");
        assertThat(rows.get(1).getBeforeStatusCd()).isEqualTo("SUBMITTED");
        assertThat(rows.get(1).getAfterStatusCd()).isEqualTo("CANCELED");
        assertThat(rows.get(1).getChangeReasonCd()).isEqualTo("CUST_REQ");
        assertThat(rows.get(1).getChangeRemark()).contains("마음바뀜");
    }

    @Test
    void 담보_해제_상태이력_기록() throws Exception {
        Long prodId = createProductAndActivate();
        Long applId = createApplication(prodId);
        Long colId = createCollateral(applId);

        mockMvc.perform(post("/api/collaterals/{colId}/release", colId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "releaseReasonCd":"LOAN_PAID", "releaseDate":"20261231" }
                                """))
                .andExpect(status().isOk());

        List<StatusHistory> rows = statusHistoryRepository
                .findByTargetDomainCdAndTargetTableCdAndTargetIdOrderByChangedAtAsc(
                        DOMAIN, "COLLATERAL", colId);

        assertThat(rows).hasSize(1);
        StatusHistory row = rows.get(0);
        assertThat(row.getBeforeStatusCd()).isEqualTo("REGISTERED");
        assertThat(row.getAfterStatusCd()).isEqualTo("RELEASED");
        assertThat(row.getChangeReasonCd()).isEqualTo("LOAN_PAID");
        assertThat(row.getChangeRemark()).contains("releaseDate=20261231");
    }

    @Test
    void 실패한_단종_요청은_이력_미기록() throws Exception {
        Long prodId = createProductAndActivate();

        // 1회 단종 (정상)
        mockMvc.perform(post("/api/loan-products/{prodId}/discontinue", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "saleEndDate":"20261231", "reasonCd":"REGULATORY" }
                                """))
                .andExpect(status().isOk());

        long countBefore = statusHistoryRepository
                .findByTargetDomainCdAndTargetTableCdAndTargetIdOrderByChangedAtAsc(
                        DOMAIN, "LOAN_PRODUCT", prodId).size();

        // 2회 단종 시도 (409 - 이미 단종됨)
        mockMvc.perform(post("/api/loan-products/{prodId}/discontinue", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "saleEndDate":"20261231", "reasonCd":"REGULATORY" }
                                """))
                .andExpect(status().isConflict());

        long countAfter = statusHistoryRepository
                .findByTargetDomainCdAndTargetTableCdAndTargetIdOrderByChangedAtAsc(
                        DOMAIN, "LOAN_PRODUCT", prodId).size();

        // 실패 케이스에서 추가 이력이 박히지 않았어야 함
        assertThat(countAfter).isEqualTo(countBefore);
    }

    // ============================================================
    // helpers
    // ============================================================

    private String uniq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Long createProductAndActivate() throws Exception {
        String body = """
                {
                  "prodCd":"AUDIT_%s", "prodName":"테스트", "loanTypeCd":"CREDIT",
                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                  "baseRateBps":450,
                  "minAmount":1000000, "maxAmount":10000000,
                  "minPeriodMo":12, "maxPeriodMo":60,
                  "collateralRequiredYn":false, "guarantorRequiredYn":false
                }
                """.formatted(uniq());
        MvcResult result = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        Long prodId = extractData(result).get("prodId").asLong();

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
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("applId").asLong();
    }

    private Long createCollateral(Long applId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-applications/{applId}/collaterals", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "colTypeCd":"REAL_ESTATE",
                                  "colName":"테스트담보",
                                  "declaredValue":300000000
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("colId").asLong();
    }
}
