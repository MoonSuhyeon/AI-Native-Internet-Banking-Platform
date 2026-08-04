package com.bank.loan;

import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * break-glass 긴급 접근 + 역할별 접근 제어 통합 테스트.
 *
 * 시나리오:
 *   10) 타 지점 지점장 → 403 LOAN_202 (break-glass 없음)
 *   20) break-glass 사유 10자 미만 → 400 LOAN_205
 *   21) break-glass 미존재 신청 → 404 LOAN_206
 *   30) break-glass 성공 → 200, logId + grantExpiresAt
 *   40) break-glass 후 접근 허용, estimatedIncomeAmt 노출(MASKED)
 *   50) 다른 actor 는 여전히 거부 → 403
 *   60) COMPLIANCE break-glass 감사로그 조회 → 이벤트 포함
 *   61) 비COMPLIANCE 감사로그 접근 → 403
 *   70) 본사담당자 비상신건 → 403 LOAN_202
 *   80) 특정 건 접근 이력 조회 (COMPLIANCE)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BreakGlassAccessControlTest extends AbstractLoanIntegrationTest {

    private static final long ACTOR_BM    = 456L;  // 타 지점 지점장
    private static final long ACTOR_OTHER = 555L;  // break-glass 없는 다른 actor
    private static final long ACTOR_COMP  = 789L;  // COMPLIANCE 담당자
    private static final long ACTOR_HQ    = 888L;  // 본사 담당자

    @Autowired JdbcTemplate jdbc;

    private Long prodId;
    private Long applId;

    @BeforeAll
    void setup() throws Exception {
        prodId = createProduct();
        activateProduct(prodId);
        applId  = createApplicationWithIncome(prodId, 60_000_000L);
        runPrescreening(applId);
        runCeval(applId, 50_000_000L);
        runDsrPass(applId);
        runIdv(applId);
        createReview(applId);

        // 신청에 지점을 배정한다.
        //
        // checkScope 는 "지점장 && (branch_id 가 null 이거나 본인 지점과 일치)" 면 접근을
        // 허용한다 — 지점 미배정(인터넷 채널) 건은 지점장 누구나 볼 수 있다는 의도된 정책.
        // 생성 API 에는 branchId 필드가 없어(V29 로 추가된 컬럼) 신청이 항상 미배정으로
        // 만들어졌고, 그 결과 "타지점 지점장 거부" 시나리오가 성립하지 않아
        // 접근이 허용되고 있었다. 지점을 0001 로 지정해 ACTOR_BM(0002)을 실제 타지점으로 만든다.
        jdbc.update("UPDATE loan_application SET branch_id = ? WHERE appl_id = ?", "0001", applId);
    }

    // ─── 역할별 접근 제어 ───────────────────────────────────────────────────

    @Test @Order(10)
    void 타지점_지점장_break_glass_없이_접근_거부() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{id}/review", applId)
                        .header("X-User-Id", ACTOR_BM)
                        .header("X-User-Role", "ROLE_BRANCH_MANAGER")
                        .header("X-User-Branch", "0002"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOAN_202"));
    }

    @Test @Order(11)
    void 본사담당자_비상신건_접근_거부() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{id}/review", applId)
                        .header("X-User-Id", ACTOR_HQ)
                        .header("X-User-Role", "ROLE_HQ_REVIEWER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOAN_202"));
    }

    // ─── break-glass 오류 케이스 ─────────────────────────────────────────────

    @Test @Order(20)
    void break_glass_사유_10자_미만_거부() throws Exception {
        mockMvc.perform(post("/api/break-glass")
                        .header("X-User-Id", ACTOR_BM)
                        .header("X-User-Role", "ROLE_BRANCH_MANAGER")
                        .header("X-User-Branch", "0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "applId": %d, "reason": "짧음" }
                                """.formatted(applId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LOAN_205"));
    }

    @Test @Order(21)
    void break_glass_미존재_신청_거부() throws Exception {
        mockMvc.perform(post("/api/break-glass")
                        .header("X-User-Id", ACTOR_BM)
                        .header("X-User-Role", "ROLE_BRANCH_MANAGER")
                        .header("X-User-Branch", "0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "applId": 99999,
                                  "reason": "고객 민원 긴급처리 - 고객 사망으로 인한 상속 처리입니다" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LOAN_206"));
    }

    // ─── break-glass 성공 + 접근 검증 ────────────────────────────────────────

    @Test @Order(30)
    void break_glass_성공_logId_grantExpiresAt_반환() throws Exception {
        mockMvc.perform(post("/api/break-glass")
                        .header("X-User-Id", ACTOR_BM)
                        .header("X-User-Role", "ROLE_BRANCH_MANAGER")
                        .header("X-User-Branch", "0002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "applId": %d,
                                  "reason": "고객 민원 긴급처리 - 고객 사망으로 인한 상속 처리입니다" }
                                """.formatted(applId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logId").isNumber())
                .andExpect(jsonPath("$.grantExpiresAt").isString());
    }

    @Test @Order(40)
    void break_glass_후_접근_허용_MASKED_PII() throws Exception {
        // MASKED 수준: estimatedIncomeAmt 노출, estimatedIncomeRange 미노출
        mockMvc.perform(get("/api/loan-applications/{id}/review", applId)
                        .header("X-User-Id", ACTOR_BM)
                        .header("X-User-Role", "ROLE_BRANCH_MANAGER")
                        .header("X-User-Branch", "0002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estimatedIncomeAmt").value(60_000_000));
    }

    @Test @Order(50)
    void break_glass_없는_다른_actor_여전히_거부() throws Exception {
        mockMvc.perform(get("/api/loan-applications/{id}/review", applId)
                        .header("X-User-Id", ACTOR_OTHER)
                        .header("X-User-Role", "ROLE_BRANCH_MANAGER")
                        .header("X-User-Branch", "0002"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOAN_202"));
    }

    // ─── 감사로그 컴플라이언스 조회 ──────────────────────────────────────────

    @Test @Order(60)
    void COMPLIANCE_break_glass_로그_조회() throws Exception {
        mockMvc.perform(get("/api/audit/break-glass")
                        .header("X-User-Id", ACTOR_COMP)
                        .header("X-User-Role", "ROLE_COMPLIANCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actionCd").value("BREAK_GLASS"))
                .andExpect(jsonPath("$[0].actorId").value(ACTOR_BM))
                .andExpect(jsonPath("$[0].breakGlassReason").isString());
    }

    @Test @Order(61)
    void COMPLIANCE_actorId_필터_조회() throws Exception {
        mockMvc.perform(get("/api/audit/break-glass")
                        .param("actorId", String.valueOf(ACTOR_BM))
                        .header("X-User-Id", ACTOR_COMP)
                        .header("X-User-Role", "ROLE_COMPLIANCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].actorId").value(ACTOR_BM));
    }

    @Test @Order(62)
    void 비COMPLIANCE_감사로그_접근_거부() throws Exception {
        mockMvc.perform(get("/api/audit/break-glass")
                        .header("X-User-Id", ACTOR_BM)
                        .header("X-User-Role", "ROLE_BRANCH_MANAGER"))
                .andExpect(status().isForbidden());
    }

    @Test @Order(63)
    void COMPLIANCE_특정건_접근이력_조회() throws Exception {
        mockMvc.perform(get("/api/audit/access-logs")
                        .param("targetType", "LOAN_APPLICATION")
                        .param("targetId", String.valueOf(applId))
                        .header("X-User-Id", ACTOR_COMP)
                        .header("X-User-Role", "ROLE_COMPLIANCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].targetType").value("LOAN_APPLICATION"))
                .andExpect(jsonPath("$[0].targetId").value(applId));
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private Long createProduct() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prodCd":"BG_TEST_%d", "prodName":"break-glass 테스트",
                                  "loanTypeCd":"CREDIT", "repaymentMethodCd":"EQUAL",
                                  "rateTypeCd":"FIXED", "baseRateBps":500,
                                  "minAmount":1000000, "maxAmount":100000000,
                                  "minPeriodMo":12, "maxPeriodMo":60,
                                  "collateralRequiredYn":"N", "guarantorRequiredYn":"N"
                                }
                                """.formatted(System.nanoTime() % 100000)))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("prodId").asLong();
    }

    private void activateProduct(Long prodId) throws Exception {
        mockMvc.perform(patch("/api/loan-products/{id}", prodId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"prodStatusCd\":\"ACTIVE\" }"))
                .andExpect(status().isOk());
    }

    private Long createApplicationWithIncome(Long prodId, long incomeAmt) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId":8010, "prodId":%d, "channelCd":"MOBILE",
                                  "requestedAmount":30000000, "requestedPeriodMo":36,
                                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL",
                                  "estimatedIncomeAmt":%d
                                }
                                """.formatted(prodId, incomeAmt)))
                .andExpect(status().isCreated())
                .andReturn();
        return extractData(result).get("applId").asLong();
    }

    private void runPrescreening(Long applId) throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/prescreening", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "prescResultCd":"PASS", "estimatedGrade":"BBB", "estimatedScore":700 }
                                """))
                .andExpect(status().isCreated());
    }

    private void runCeval(Long applId, long evalLimit) throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/credit-evaluation", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "cevalEngine":"KCB", "cevalDecisionCd":"APPROVE",
                                  "cevalScore":700, "evalLimitAmount":%d }
                                """.formatted(evalLimit)))
                .andExpect(status().isCreated());
    }

    private void runDsrPass(Long applId) throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/dsr-calculation", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "annualIncomeAmt":80000000, "newAnnualRepayAmt":10000000 }
                                """))
                .andExpect(status().isCreated());
    }

    private void runIdv(Long applId) throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/identity-verifications", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "idvMethodCd":"PASS_APP", "idvTargetCd":"BORROWER",
                                  "mobileNo":"01012345678" }
                                """))
                .andExpect(status().isCreated());
    }

    private void createReview(Long applId) throws Exception {
        mockMvc.perform(post("/api/loan-applications/{id}/review", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"MANUAL", "revDecisionCd":"APPROVED",
                                  "reviewerId":99001 }
                                """))
                .andExpect(status().isCreated());
    }
}
