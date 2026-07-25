package com.bank.loan.rag;

import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.review.domain.LoanReview;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * P2 케이스 청크 PII 유출 smoke 테스트 — Phase E (E3-7).
 *
 * <p>loan_review_outbox → Kafka → ES 파이프라인에서 색인되는
 * chunk_text / source_id / metadata 에 개인식별정보(PII)가 포함되지 않음을 정규식으로 검증.
 *
 * <p>검사 패턴:
 * <ul>
 *   <li>주민번호 — {@code YYMMDD-[1-4]XXXXXX}</li>
 *   <li>전화번호 — {@code 010-XXXX-XXXX} 형태</li>
 *   <li>계좌번호 — 연속 10자리 이상 숫자</li>
 * </ul>
 */
class SimilarCasePiiSmokeTest {

    // ── PII 정규식 패턴 ──────────────────────────────────────────────────────
    /** 주민등록번호: YYMMDD-GXXXXXX */
    private static final Pattern RESIDENT_NO =
            Pattern.compile("\\d{6}-[1-4]\\d{6}");
    /** 전화번호: 010/011/016-XXXX-XXXX (하이픈 또는 연속) */
    private static final Pattern PHONE_NO =
            Pattern.compile("01[016789][- ]?\\d{3,4}[- ]?\\d{4}");
    /** 계좌번호: 10자리 이상 연속 숫자 (은행 계좌 최소 길이) */
    private static final Pattern ACCOUNT_NO =
            Pattern.compile("\\d{10,}");

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private static LoanReview review(String decision, String revType, Long revId) {
        LoanReview r = mock(LoanReview.class);
        lenient().when(r.getRevId()).thenReturn(revId);
        lenient().when(r.getRevDecisionCd()).thenReturn(decision);
        lenient().when(r.getRevTypeCd()).thenReturn(revType);
        return r;
    }

    private static LoanApplication application(String empl, Long income, Long amount,
                                               Integer period, String purpose) {
        LoanApplication a = mock(LoanApplication.class);
        lenient().when(a.getEmploymentTypeCd()).thenReturn(empl);
        lenient().when(a.getEstimatedIncomeAmt()).thenReturn(income);
        lenient().when(a.getRequestedAmount()).thenReturn(amount);
        lenient().when(a.getRequestedPeriodMo()).thenReturn(period);
        lenient().when(a.getLoanPurposeCd()).thenReturn(purpose);
        return a;
    }

    private static void assertNoPii(String label, String value) {
        assertThat(RESIDENT_NO.matcher(value).find())
                .as("주민번호 패턴 검출 [%s]: %s", label, value).isFalse();
        assertThat(PHONE_NO.matcher(value).find())
                .as("전화번호 패턴 검출 [%s]: %s", label, value).isFalse();
        assertThat(ACCOUNT_NO.matcher(value).find())
                .as("계좌번호 패턴 검출 [%s]: %s", label, value).isFalse();
    }

    // ── chunk_text 검사 ──────────────────────────────────────────────────────

    @ParameterizedTest(name = "empl={0} income={1} amount={2} period={3}")
    @CsvSource({
            "EMPLOYEE,       30000000, 50000000,  120, HOUSING, APPROVE, AUTO",
            "EMPLOYEE,       60000000, 150000000, 360, HOUSING, APPROVE, AUTO",
            "SELF_EMPLOYED, 100000000, 300000000, 240, INVEST,  REJECT,  MANUAL",
            "UNEMPLOYED,           0,  30000000,  60, ETC,     DEFER,   AUTO",
            "EMPLOYEE,      500000000, 800000000, 360, HOUSING, APPROVE, AUTO",
    })
    void 다양한_입력_청크_텍스트_PII_없음(String empl, long income, long amount,
                                        int period, String purpose,
                                        String decision, String revType) {
        LoanReview rv = review(decision, revType, 1L);
        LoanApplication ap = application(empl, income, amount, period, purpose);

        String text = SimilarCaseChunkTemplate.build(rv, ap);
        assertNoPii("chunk_text", text);
    }

    @Test
    void null_필드_청크_텍스트_PII_없음() {
        LoanReview rv  = review(null, null, 999L);
        LoanApplication ap = application(null, null, null, null, null);

        String text = SimilarCaseChunkTemplate.build(rv, ap);
        assertNoPii("chunk_text(nulls)", text);
    }

    // ── source_id / corpus 검사 ─────────────────────────────────────────────

    @Test
    void source_id_PII_없음() {
        String sourceId = SimilarCaseChunkTemplate.sourceId(123456789L);
        // "rev-123456789" — 숫자만 있고 10자리 이상이면 계좌 패턴에 걸릴 수 있으나
        // "rev-" 접두어가 있어 순수 연속 숫자가 아님 → ACCOUNT_NO 불일치
        assertThat(RESIDENT_NO.matcher(sourceId).find()).isFalse();
        assertThat(PHONE_NO.matcher(sourceId).find()).isFalse();
        // rev-123456789 = "rev-" + 9자리 → 연속 10자리 숫자 없음
        assertThat(ACCOUNT_NO.matcher(sourceId).find()).isFalse();
    }

    @Test
    void corpus_PII_없음() {
        assertNoPii("corpus", SimilarCaseChunkTemplate.corpus());
    }

    // ── 전체 payload JSON 검사 ───────────────────────────────────────────────

    @Test
    void payload_JSON_PII_없음() throws Exception {
        LoanReview rv  = review("APPROVE", "AUTO", 42L);
        LoanApplication ap = application("EMPLOYEE", 80_000_000L, 200_000_000L, 360, "HOUSING");

        // publisher.buildPayload 는 private — ObjectMapper 로 동일 구조 재현
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("rev_type",  rv.getRevTypeCd());
        metadata.put("decision",  String.valueOf(rv.getRevDecisionCd()));

        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("corpus",    SimilarCaseChunkTemplate.corpus());
        payload.put("source_id", SimilarCaseChunkTemplate.sourceId(rv.getRevId()));
        payload.put("chunk_seq", 0);
        payload.put("chunk_text", SimilarCaseChunkTemplate.build(rv, ap));
        payload.put("metadata",  metadata);

        String json = new ObjectMapper().writeValueAsString(payload);
        assertNoPii("full_payload_json", json);
    }
}
