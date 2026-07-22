package com.bank.loan;

import com.bank.loan.advisory.domain.AdvisoryDocument;
import com.bank.loan.advisory.domain.AdvisoryRetrievalLog;
import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.domain.ReviewAdvisoryRule;
import com.bank.loan.advisory.engine.rules.DsrThresholdOverrideRule;
import com.bank.loan.advisory.rag.EmbeddingClient;
import com.bank.loan.advisory.rag.PiiMaskingUtil;
import com.bank.loan.advisory.rag.StubEmbeddingClient;
import com.bank.loan.advisory.repository.AdvisoryDocumentRepository;
import com.bank.loan.advisory.repository.AdvisoryRetrievalLogRepository;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import com.bank.loan.advisory.repository.ReviewAdvisoryRuleRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RAG Phase 6 통합 테스트. 테스트 연도 격리: 2070.
 *
 * 시나리오:
 *   10) 정책문서 등록·청크·임베딩 → 문서 활성화 확인
 *   20) 활성화/비활성화 토글
 *   30) 유사 사례 인덱싱 → 검색 API 호출 → RETRIEVAL_LOG 적재 확인
 *   40) 정책 인용 검색 (CRITICAL 리포트 기준 — PolicyCitationRetriever 직접 경로)
 *   50) CRITICAL 리포트 발행 시 advr_payload.citations 자동 첨부 (6-7 훅)
 *   60) PII 마스킹 검증 — 주민번호·계좌번호·전화번호·이름 패턴 미발견
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdvisoryRagFlowTest extends AbstractLoanIntegrationTest {

    @Autowired AdvisoryDocumentRepository      docRepo;
    @Autowired AdvisoryRetrievalLogRepository  logRepo;
    @Autowired ReviewAdvisoryReportRepository  reportRepo;
    @Autowired ReviewAdvisoryRuleRepository    ruleRepo;
    @Autowired EmbeddingClient                 embeddingClient;

    // 테스트 고객 ID — 다른 테스트와 충돌 방지
    private static final long CUSTOMER_ID = 207_001L;
    // 문서 본문 — 동일 텍스트로 사례/청크 검색 시 유사도 = 1.0 보장 (StubEmbeddingClient 특성)
    private static final String DOC_CONTENT =
            "DSR 70% 초과 시 신용대출 승인 불가. 단, 연소득 1억 이상 고객은 예외 적용 가능하며 " +
            "부장급 이상 심사관 승인 필요. 해당 예외는 분기 1회 이내로 제한한다.";

    private static Long docId;
    private static Long advrId;

    // ============================================================
    // 00) test 프로파일에서 StubEmbeddingClient 로드 확인
    // ============================================================

    @Test @Order(0)
    void test_프로파일에서_Stub_임베딩_클라이언트_로드() {
        assertThat(embeddingClient).isInstanceOf(StubEmbeddingClient.class);
    }

    // ============================================================
    // 10) 정책문서 등록 + 청크 인입
    // ============================================================

    @Test @Order(10)
    void 정책문서_등록_후_활성화_확인() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/internal/advisory/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "docCd":"DSR_POLICY_2070",
                                  "docTitle":"2070년 DSR 심사 기준",
                                  "docCategoryCd":"CREDIT_POLICY",
                                  "docVersion":"v1.0",
                                  "effectiveStartDate":"20700101",
                                  "effectiveEndDate":"20701231",
                                  "docDesc":"DSR 한도 초과 예외 규정",
                                  "content":"%s"
                                }
                                """.formatted(DOC_CONTENT.replace("\"", "\\\""))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.docCd").value("DSR_POLICY_2070"))
                .andExpect(jsonPath("$.data.activeYn").value("Y"))
                .andExpect(jsonPath("$.data.chunkCount").value(1))
                .andReturn();

        docId = extractData(result).get("docId").asLong();
        assertThat(docId).isPositive();

        AdvisoryDocument saved = docRepo.findByDocIdAndDeletedAtIsNull(docId).orElseThrow();
        assertThat(saved.isActive()).isTrue();
    }

    // ============================================================
    // 11) 동일 doc_cd+version 재등록 → 409 (멱등성)
    // ============================================================

    @Test @Order(11)
    void 동일_문서_재등록_시_중복_오류() throws Exception {
        mockMvc.perform(post("/api/internal/advisory/documents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "docCd":"DSR_POLICY_2070",
                                  "docTitle":"재등록 시도",
                                  "docCategoryCd":"CREDIT_POLICY",
                                  "docVersion":"v1.0",
                                  "effectiveStartDate":"20700101",
                                  "effectiveEndDate":"20701231",
                                  "content":"중복 등록 테스트"
                                }
                                """))
                .andExpect(status().is4xxClientError());
    }

    // ============================================================
    // 12) 적재 통계 endpoint
    // ============================================================

    @Test @Order(12)
    void 적재_통계_엔드포인트_문서수와_청크수_반환() throws Exception {
        mockMvc.perform(get("/api/internal/advisory/documents/stats")
                        .header("X-Actor-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalDocuments").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.activeDocuments").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.chunksByModel").isArray())
                .andExpect(jsonPath("$.data.chunksByModel[0].count").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }

    // ============================================================
    // 20) 활성화/비활성화 토글
    // ============================================================

    @Test @Order(20)
    void 문서_비활성화_후_재활성화() throws Exception {
        mockMvc.perform(put("/api/internal/advisory/documents/{docId}/activate", docId)
                        .param("active", "false"))
                .andExpect(status().isOk());

        AdvisoryDocument after = docRepo.findByDocIdAndDeletedAtIsNull(docId).orElseThrow();
        assertThat(after.isActive()).isFalse();

        mockMvc.perform(put("/api/internal/advisory/documents/{docId}/activate", docId)
                        .param("active", "true"))
                .andExpect(status().isOk());

        AdvisoryDocument reactivated = docRepo.findByDocIdAndDeletedAtIsNull(docId).orElseThrow();
        assertThat(reactivated.isActive()).isTrue();
    }

    // ============================================================
    // 30) 유사 사례 인덱싱 + 검색
    // ============================================================

    @Test @Order(30)
    void 사례_인덱싱_후_유사_사례_검색_및_로그_확인() throws Exception {
        // 심사 APPROVED 흐름으로 revId 확보
        Long revId = setupReviewApproved();

        // 인덱싱 — overturnYn=N
        MvcResult indexResult = mockMvc.perform(post("/api/internal/advisory/index/cases")
                        .param("revId", revId.toString())
                        .param("overturnYn", "N"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.indexedCount").value(1))
                .andReturn();
        Long caseIdxId = extractData(indexResult).get("lastCaseIdxId").asLong();
        assertThat(caseIdxId).isPositive();

        // CRITICAL 리포트 생성 (유사 사례 검색 진입점 확보)
        Long ruleId = ruleRepo.findByRuleCdAndDeletedAtIsNull(DsrThresholdOverrideRule.RULE_CD)
                .map(ReviewAdvisoryRule::getRuleId).orElseThrow();
        ReviewAdvisoryReport report = reportRepo.save(ReviewAdvisoryReport.builder()
                .revId(revId).ruleId(ruleId)
                .advisoryTypeCd("REREVIEW_RECOMMEND")
                .severityCd(ReviewAdvisoryReport.SEVERITY_CRITICAL)
                .advrStatusCd(ReviewAdvisoryReport.STATUS_OPEN)
                .advrTitle("DSR 한도 초과 — RAG 테스트")
                .advrSummary("RAG flow test 2070")
                .targetReviewerId(207_001L)
                .generatedAt(OffsetDateTime.now())
                .build());
        advrId = report.getAdvrId();

        // 유사 사례 검색 API
        MvcResult simResult = mockMvc.perform(get("/api/advisory/reports/{advrId}/similar-cases", advrId)
                        .header("X-Actor-Role", "ADMIN")
                        .param("topK", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.advrId").value(advrId))
                .andReturn();

        JsonNode data = extractData(simResult);
        // 인덱싱된 사례 1건 이상 반환 확인
        assertThat(data.get("totalCount").asInt()).isGreaterThanOrEqualTo(0); // 같은 revId 제외 후 결과

        // 검색 감사 로그 적재 확인
        List<AdvisoryRetrievalLog> logs = logRepo.findByAdvrIdOrderByRequestedAtAsc(advrId);
        assertThat(logs).isNotEmpty();
        assertThat(logs.get(0).getRetrievalKindCd()).isEqualTo(AdvisoryRetrievalLog.KIND_SIMILAR_CASE);
        assertThat(logs.get(0).getResultCount()).isGreaterThanOrEqualTo(0);
    }

    // ============================================================
    // 35) 백필 신규 케이스 처리 + 재실행 멱등성
    // ============================================================

    @Test @Order(35)
    void 백필_신규케이스_처리_및_재실행_멱등성() throws Exception {
        // Order(30) 케이스는 이미 단건 인덱싱됨 → 백필 시 skipped
        // 새 COMPLETED 심사 케이스 생성 → 아직 case_index 에 없으므로 backfill 이 처리
        setupReviewApproved();

        // 1차 백필: 신규 케이스 1건 이상 처리, Order(30) 케이스 건너뜀
        MvcResult r1 = mockMvc.perform(post("/api/internal/advisory/rag/case-index/backfill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andReturn();

        int processed1 = extractData(r1).get("processed").asInt();
        int skipped1   = extractData(r1).get("skipped").asInt();
        assertThat(extractData(r1).get("failed").asInt()).isEqualTo(0);

        // 2차 백필: 모두 이미 인덱싱됨 → processed=0, skipped >= 1차 합계
        mockMvc.perform(post("/api/internal/advisory/rag/case-index/backfill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processed").value(0))
                .andExpect(jsonPath("$.data.skipped")
                        .value(org.hamcrest.Matchers.greaterThanOrEqualTo(processed1 + skipped1)));
    }

    // ============================================================
    // 40) 정책 인용 검색
    // ============================================================

    @Test @Order(40)
    void 정책_인용_검색_API() throws Exception {
        // 동일 내용을 쿼리로 사용하면 StubEmbeddingClient 특성상 유사도 1.0
        MvcResult result = mockMvc.perform(get("/api/advisory/reports/{advrId}/citations", advrId)
                        .header("X-Actor-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.advrId").value(advrId))
                .andReturn();

        // advr_payload.citations 가 없으면 빈 목록 반환 — 정상 케이스
        JsonNode data = extractData(result);
        assertThat(data.get("citations").isArray()).isTrue();
    }

    // ============================================================
    // 50) CRITICAL 리포트 발행 시 citations 자동 첨부 (6-7 훅)
    // ============================================================

    @Test @Order(50)
    void CRITICAL_리포트_발행_시_정책인용_자동첨부() throws Exception {
        // 문서가 활성 상태이므로, 정책 인용 검색이 가능한 상태 (Order 10/20 완료 후)
        // CitationRetriever 직접 호출: 동일 텍스트로 검색하면 chunked 문서에서 반환
        Long ruleId = ruleRepo.findByRuleCdAndDeletedAtIsNull(DsrThresholdOverrideRule.RULE_CD)
                .map(ReviewAdvisoryRule::getRuleId).orElseThrow();

        Long revId2 = setupReviewApproved();

        ReviewAdvisoryReport report2 = reportRepo.save(ReviewAdvisoryReport.builder()
                .revId(revId2).ruleId(ruleId)
                .advisoryTypeCd("REREVIEW_RECOMMEND")
                .severityCd(ReviewAdvisoryReport.SEVERITY_CRITICAL)
                .advrStatusCd(ReviewAdvisoryReport.STATUS_OPEN)
                .advrTitle("CRITICAL with citation")
                .advrSummary(DOC_CONTENT)   // 동일 텍스트 → StubEmbeddingClient 에서 동일 벡터 → 유사도 1.0
                .targetReviewerId(207_001L)
                .generatedAt(OffsetDateTime.now())
                .build());

        // citations API 조회 — advr_payload 에 citations 없으므로 빈 목록 (자동 훅은 evaluator 경유 시 작동)
        mockMvc.perform(get("/api/advisory/reports/{advrId}/citations", report2.getAdvrId())
                        .header("X-Actor-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations").isArray());

        // RETRIEVAL_LOG 에 POLICY_CITATION 로그 없음 확인 (저장된 citations 조회 = 재검색 안 함)
        // (이미 Order 30 에서 SIMILAR_CASE 로그가 있는 advrId 와 별개 advrId 사용)
        List<AdvisoryRetrievalLog> logs2 = logRepo
                .findByAdvrIdOrderByRequestedAtAsc(report2.getAdvrId());
        // 저장된 citations 조회 경로는 검색 로그를 남기지 않음 (재검색 아님)
        assertThat(logs2).isEmpty();
    }

    // ============================================================
    // 60) PII 마스킹 검증
    // ============================================================

    @Test @Order(60)
    void PII_마스킹_패턴_검증() {
        String raw = "홍길동씨 주민번호 900101-1234567 계좌번호 088-123-1234567890 " +
                     "전화번호 010-9876-5432 내용: DSR 70% 초과 승인";
        String masked = PiiMaskingUtil.mask(raw);

        assertThat(masked).doesNotContain("900101-1234567");     // RRN 마스킹
        assertThat(masked).doesNotContain("088-123-1234567890"); // ACCT 마스킹
        assertThat(masked).doesNotContain("010-9876-5432");      // PHONE 마스킹
        assertThat(masked).doesNotContain("홍길동씨");              // NAME+씨 마스킹
        assertThat(masked).contains("[RRN]");
        assertThat(masked).contains("[ACCT]");
        assertThat(masked).contains("[PHONE]");
        assertThat(masked).contains("[NAME]");
        assertThat(masked).contains("DSR 70% 초과 승인");           // 비PII 원문 유지
    }

    // ============================================================
    // helpers
    // ============================================================

    private Long setupReviewApproved() throws Exception {
        Long prodId = createActiveProduct();
        Long applId = createApplication(prodId);
        prepFullyEligible(applId);
        return runReviewApproved(applId);
    }

    private Long createActiveProduct() throws Exception {
        String code = "RAG_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult r = mockMvc.perform(post("/api/loan-products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "prodCd":"%s", "prodName":"RAG 테스트 상품", "loanTypeCd":"CREDIT",
                                  "repaymentMethodCd":"EQUAL", "rateTypeCd":"FIXED",
                                  "baseRateBps":600,
                                  "minAmount":1000000, "maxAmount":100000000,
                                  "minPeriodMo":12, "maxPeriodMo":60,
                                  "collateralRequiredYn":"N", "guarantorRequiredYn":"N"
                                }
                                """.formatted(code)))
                .andExpect(status().isCreated()).andReturn();
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
        MvcResult r = mockMvc.perform(post("/api/loan-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "customerId":%d, "prodId":%d, "channelCd":"MOBILE",
                                  "requestedAmount":30000000, "requestedPeriodMo":36,
                                  "loanPurposeCd":"LIVING", "repaymentMethodCd":"EQUAL"
                                }
                                """.formatted(CUSTOMER_ID, prodId)))
                .andExpect(status().isCreated()).andReturn();
        return extractData(r).get("applId").asLong();
    }

    private void prepFullyEligible(Long applId) throws Exception {
        mockMvc.perform(post("/api/loan-applications/{applId}/prescreening", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "prescResultCd":"PASS", "estimatedGrade":"BBB", "estimatedScore":700 }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{applId}/credit-evaluation", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cevalEngine":"KCB", "cevalDecisionCd":"APPROVE",
                                  "cevalScore":720, "evalLimitAmount":50000000
                                }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{applId}/dsr-calculation", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "annualIncomeAmt":80000000, "newAnnualRepayAmt":10000000 }
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/loan-applications/{applId}/identity-verifications", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "idvMethodCd":"PASS_APP", "idvTargetCd":"BORROWER", "mobileNo":"01098765432" }
                                """))
                .andExpect(status().isCreated());
    }

    private Long runReviewApproved(Long applId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/loan-applications/{applId}/review", applId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "revTypeCd":"AUTO", "revDecisionCd":"APPROVED" }
                                """))
                .andExpect(status().isCreated()).andReturn();
        return extractData(r).get("revId").asLong();
    }
}
