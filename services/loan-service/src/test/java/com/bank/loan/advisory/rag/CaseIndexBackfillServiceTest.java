package com.bank.loan.advisory.rag;

import com.bank.loan.advisory.observability.AdvisoryMetrics;
import com.bank.loan.advisory.repository.AdvisoryCaseIndexRepository;
import com.bank.loan.review.domain.LoanReview;
import com.bank.loan.review.repository.LoanReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaseIndexBackfillServiceTest {

    LoanReviewRepository        reviewRepo      = mock(LoanReviewRepository.class);
    AdvisoryCaseIndexRepository caseIndexRepo   = mock(AdvisoryCaseIndexRepository.class);
    EmbeddingClient             embeddingClient = mock(EmbeddingClient.class);
    JdbcTemplate                jdbcTemplate    = mock(JdbcTemplate.class);
    AdvisoryMetrics             advisoryMetrics = mock(AdvisoryMetrics.class);

    CaseIndexBackfillService service;

    @BeforeEach
    void setUp() {
        service = new CaseIndexBackfillService(reviewRepo, caseIndexRepo, embeddingClient, jdbcTemplate, advisoryMetrics);
        when(embeddingClient.defaultModelCd()).thenReturn("OPENAI_3S");
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});
    }

    // ── 정상 흐름 ──────────────────────────────────────────────────────────

    @Test
    void 신규_케이스_임베딩_저장() {
        LoanReview review = stubReview(1L, "APPROVED");
        when(reviewRepo.findByRevStatusCdAndDeletedAtIsNull(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(review)))  // 첫 페이지
                .thenReturn(new PageImpl<>(List.of()));        // 두 번째 = 빈 페이지 → 종료
        when(caseIndexRepo.existsByRevId(1L)).thenReturn(false);

        CaseIndexBackfillService.BackfillResult result =
                service.backfill(null, null, false, 99L);

        assertThat(result.processed()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);
        verify(embeddingClient, times(1)).embed(anyString());
    }

    @Test
    void 이미_인덱스된_revId_는_건너뜀() {
        LoanReview review = stubReview(2L, "REJECTED");
        when(reviewRepo.findByRevStatusCdAndDeletedAtIsNull(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(review)))
                .thenReturn(new PageImpl<>(List.of()));
        when(caseIndexRepo.existsByRevId(2L)).thenReturn(true);  // 이미 존재

        CaseIndexBackfillService.BackfillResult result =
                service.backfill(null, null, false, 99L);

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.processed()).isEqualTo(0);
        verify(embeddingClient, never()).embed(anyString());
    }

    // ── dry-run ────────────────────────────────────────────────────────────

    @Test
    void dryRun_시_임베딩_및_INSERT_없음() {
        LoanReview review = stubReview(3L, "APPROVED");
        when(reviewRepo.findByRevStatusCdAndDeletedAtIsNull(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(review)))
                .thenReturn(new PageImpl<>(List.of()));
        when(caseIndexRepo.existsByRevId(3L)).thenReturn(false);

        CaseIndexBackfillService.BackfillResult result =
                service.backfill(null, null, true, 99L);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.processed()).isEqualTo(1);  // dry-run 도 processed 카운트
        verify(embeddingClient, never()).embed(anyString());
        verify(jdbcTemplate, never()).update(anyString(), (Object[]) any());
    }

    // ── 멱등성 ─────────────────────────────────────────────────────────────

    @Test
    void 동일_기간_재실행_시_전량_skip() {
        LoanReview r1 = stubReview(10L, "APPROVED");
        LoanReview r2 = stubReview(11L, "REJECTED");
        when(reviewRepo.findByRevStatusCdAndDeletedAtIsNull(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r1, r2)))
                .thenReturn(new PageImpl<>(List.of()));
        when(caseIndexRepo.existsByRevId(any())).thenReturn(true);  // 모두 존재

        CaseIndexBackfillService.BackfillResult result =
                service.backfill(null, null, false, 99L);

        assertThat(result.skipped()).isEqualTo(2);
        assertThat(result.processed()).isEqualTo(0);
        verify(embeddingClient, never()).embed(anyString());
    }

    // ── PII 마스킹 ─────────────────────────────────────────────────────────

    @Test
    void summary_text_에_PII_포함되지_않음() {
        // reviewerId 가 숫자이므로 PII 패턴 해당 없음 — 핵심은 심사관ID 가 그대로 노출되는지 확인
        LoanReview review = stubReview(20L, "APPROVED");
        when(review.getReviewerId()).thenReturn(9_999_999L);  // 숫자 ID — PII 아님

        when(reviewRepo.findByRevStatusCdAndDeletedAtIsNull(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(review)))
                .thenReturn(new PageImpl<>(List.of()));
        when(caseIndexRepo.existsByRevId(20L)).thenReturn(false);

        // embed 호출 시 인자를 캡처해서 PII 패턴 없음 확인
        service.backfill(null, null, false, 99L);

        verify(embeddingClient).embed(
                org.mockito.ArgumentMatchers.argThat(text ->
                        !text.contains("900101-")   &&  // 주민번호 패턴 없음
                        !text.contains("010-")       &&  // 전화번호 패턴 없음
                        text.contains("심사결정")           // 기본 구조 유지
                ));
    }

    // ── 실패 내성 ──────────────────────────────────────────────────────────

    @Test
    void 개별_케이스_실패_시_나머지_계속_처리() {
        LoanReview r1 = stubReview(30L, "APPROVED");
        LoanReview r2 = stubReview(31L, "REJECTED");
        when(reviewRepo.findByRevStatusCdAndDeletedAtIsNull(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r1, r2)))
                .thenReturn(new PageImpl<>(List.of()));
        when(caseIndexRepo.existsByRevId(any())).thenReturn(false);
        when(embeddingClient.embed(anyString()))
                .thenThrow(new RuntimeException("API 오류"))  // r1 실패
                .thenReturn(new float[]{0.1f, 0.2f, 0.3f});  // r2 성공

        CaseIndexBackfillService.BackfillResult result =
                service.backfill(null, null, false, 99L);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.processed()).isEqualTo(1);
    }

    // ── 메트릭 ────────────────────────────────────────────────────────────────

    @Test
    void 메트릭_처리_건너뜀_실패_카운터_증가() {
        // r1=신규, r2=이미 존재, r3=임베딩 실패
        LoanReview r1 = stubReview(40L, "APPROVED");
        LoanReview r2 = stubReview(41L, "REJECTED");
        LoanReview r3 = stubReview(42L, "APPROVED");
        when(reviewRepo.findByRevStatusCdAndDeletedAtIsNull(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(r1, r2, r3)))
                .thenReturn(new PageImpl<>(List.of()));
        when(caseIndexRepo.existsByRevId(40L)).thenReturn(false);
        when(caseIndexRepo.existsByRevId(41L)).thenReturn(true);   // 건너뜀
        when(caseIndexRepo.existsByRevId(42L)).thenReturn(false);
        when(embeddingClient.embed(anyString()))
                .thenReturn(new float[]{0.1f, 0.2f, 0.3f})        // r1 성공
                .thenThrow(new RuntimeException("API 오류"));       // r3 실패

        service.backfill(null, null, false, 99L);

        verify(advisoryMetrics, times(1)).incrementBackfillProcessed();
        verify(advisoryMetrics, times(1)).incrementBackfillSkipped();
        verify(advisoryMetrics, times(1)).incrementBackfillFailed();
    }

    @Test
    void dryRun_시_메트릭_processed_미발행() {
        LoanReview review = stubReview(50L, "APPROVED");
        when(reviewRepo.findByRevStatusCdAndDeletedAtIsNull(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(review)))
                .thenReturn(new PageImpl<>(List.of()));
        when(caseIndexRepo.existsByRevId(50L)).thenReturn(false);

        service.backfill(null, null, true, 99L);

        verify(advisoryMetrics, never()).incrementBackfillProcessed();
        verify(advisoryMetrics, never()).incrementBackfillFailed();
    }

    @Test
    void dryRun_시_기존_레코드도_skipped_메트릭_미발행() {
        // 이미 인덱스에 존재하는 레코드 — 실제 실행이면 skipped 메트릭이 오르지만 dryRun 은 미발행
        LoanReview review = stubReview(51L, "APPROVED");
        when(reviewRepo.findByRevStatusCdAndDeletedAtIsNull(anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(review)))
                .thenReturn(new PageImpl<>(List.of()));
        when(caseIndexRepo.existsByRevId(51L)).thenReturn(true);

        var result = service.backfill(null, null, true, 99L);

        assertThat(result.skipped()).isEqualTo(1);
        verify(advisoryMetrics, never()).incrementBackfillSkipped();
        verify(advisoryMetrics, never()).incrementBackfillProcessed();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private LoanReview stubReview(Long revId, String decisionCd) {
        LoanReview r = mock(LoanReview.class);
        when(r.getRevId()).thenReturn(revId);
        when(r.getRevDecisionCd()).thenReturn(decisionCd);
        when(r.getRevTypeCd()).thenReturn("AUTO");
        when(r.getApprovedAmount()).thenReturn(30_000_000L);
        when(r.getApprovedPeriodMo()).thenReturn(36);
        when(r.getApprovedRateBps()).thenReturn(450);
        when(r.getReviewerId()).thenReturn(1001L);
        return r;
    }
}
