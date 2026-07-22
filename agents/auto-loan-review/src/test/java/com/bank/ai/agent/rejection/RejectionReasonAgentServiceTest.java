package com.bank.ai.agent.rejection;

import com.bank.ai.llm.client.LlmCallException;
import com.bank.ai.llm.client.LlmClient;
import com.bank.ai.llm.client.LlmRequest;
import com.bank.ai.llm.config.AgentProperties;
import com.bank.ai.llm.support.LlmRequestRateMeter;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.rule.domain.HardFailReason;
import com.bank.ai.rule.domain.Track;
import com.bank.ai.rule.domain.TrackDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RejectionReasonAgentServiceTest {

    @Mock private LlmRequestRateMeter rateMeter;
    @Mock private LlmClient llmClient;

    private RejectionReasonAgentService service;

    private static final AgentProperties ENABLED_PROPS =
            new AgentProperties(true, 6, 2, true, 0);
    private static final AgentProperties DISABLED_PROPS =
            new AgentProperties(false, 6, 2, true, 0);

    private static final TrackDecision HARD_FAIL_DECISION = new TrackDecision(
            Track.TRACK_2,
            List.of(HardFailReason.DSR_EXCEEDED, HardFailReason.LTV_EXCEEDED),
            0.60, 0.30, 0.347, 0.104, "DSR/LTV 정책 위반"
    );

    private static final TrackDecision PD_EXCEEDED_DECISION = new TrackDecision(
            Track.TRACK_2,
            List.of(),
            0.55, 0.40, 0.347, 0.104, "PD 매트릭스 임계 초과"
    );

    @BeforeEach
    void setUp() {
        service = new RejectionReasonAgentService(ENABLED_PROPS, rateMeter, llmClient);
    }

    private AutoReviewRequest stubRequest() {
        return new AutoReviewRequest(
                1L,
                null, 35, null, null, null, null, null, null, null, null, null, "regular",
                null, null, null, null, null, null,
                0.50, 0.80, null, null, 0, 750,
                "MORT_001", 100_000_000L, 360, "아파트 구입", null,
                null, null, null, null, null, null, null
        );
    }

    // ── 정상 경로 ────────────────────────────────────────────────────────

    @Test
    void hard_fail_사유_LLM_호출_정상_통보문_반환() {
        when(rateMeter.tryAcquire()).thenReturn(true);
        when(llmClient.call(any(), eq(RejectionNoticeDraft.class)))
                .thenReturn(new RejectionNoticeDraft("DSR 초과로 반려되었습니다."));

        var draft = service.draft(1L, stubRequest(), HARD_FAIL_DECISION);

        assertThat(draft.isFallback()).isFalse();
        assertThat(draft.notice()).isEqualTo("DSR 초과로 반려되었습니다.");
        assertThat(draft.reasonCodes()).containsExactly("DSR_EXCEEDED", "LTV_EXCEEDED");
        assertThat(draft.fallbackReason()).isNull();
    }

    @Test
    void PD_초과_사유_reasonCodes_PD_THRESHOLD_EXCEEDED() {
        when(rateMeter.tryAcquire()).thenReturn(true);
        when(llmClient.call(any(), eq(RejectionNoticeDraft.class)))
                .thenReturn(new RejectionNoticeDraft("PD 임계 초과로 반려되었습니다."));

        var draft = service.draft(2L, stubRequest(), PD_EXCEEDED_DECISION);

        assertThat(draft.reasonCodes()).containsExactly("PD_THRESHOLD_EXCEEDED");
        assertThat(draft.isFallback()).isFalse();
    }

    @Test
    void LLM_호출_시_promptId_revision_reason_draft_전달() {
        when(rateMeter.tryAcquire()).thenReturn(true);
        when(llmClient.call(any(), eq(RejectionNoticeDraft.class)))
                .thenReturn(new RejectionNoticeDraft("반려."));

        service.draft(1L, stubRequest(), PD_EXCEEDED_DECISION);

        var captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient).call(captor.capture(), eq(RejectionNoticeDraft.class));
        assertThat(captor.getValue().promptId()).isEqualTo("rejection_reason_draft");
        assertThat(captor.getValue().promptVer()).isEqualTo(1);
    }

    @Test
    void LLM_호출_시_상품_세그먼트_사유_포함된_userContent_전달() {
        when(rateMeter.tryAcquire()).thenReturn(true);
        when(llmClient.call(any(), eq(RejectionNoticeDraft.class)))
                .thenReturn(new RejectionNoticeDraft("반려."));

        service.draft(1L, stubRequest(), HARD_FAIL_DECISION);

        var captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient).call(captor.capture(), eq(RejectionNoticeDraft.class));
        assertThat(captor.getValue().userContent())
                .contains("MORT_001")
                .contains("regular")
                .contains("DSR_EXCEEDED");
    }

    // ── 폴백 경로 ────────────────────────────────────────────────────────

    @Test
    void kill_switch_비활성시_AGENT_DISABLED_template_반환() {
        var disabledService = new RejectionReasonAgentService(DISABLED_PROPS, rateMeter, llmClient);

        var draft = disabledService.draft(3L, stubRequest(), PD_EXCEEDED_DECISION);

        assertThat(draft.isFallback()).isTrue();
        assertThat(draft.fallbackReason()).isEqualTo("AGENT_DISABLED");
        assertThat(draft.notice()).contains("반려");
        verifyNoInteractions(rateMeter, llmClient);
    }

    @Test
    void rate_limit_초과시_LLM_RATE_LIMITED_template_반환() {
        when(rateMeter.tryAcquire()).thenReturn(false);

        var draft = service.draft(4L, stubRequest(), PD_EXCEEDED_DECISION);

        assertThat(draft.isFallback()).isTrue();
        assertThat(draft.fallbackReason()).isEqualTo("LLM_RATE_LIMITED");
        assertThat(draft.reasonCodes()).containsExactly("PD_THRESHOLD_EXCEEDED");
        verifyNoInteractions(llmClient);
    }

    @Test
    void LLM_예외시_TOOL_ERROR_template_반환() {
        when(rateMeter.tryAcquire()).thenReturn(true);
        when(llmClient.call(any(), eq(RejectionNoticeDraft.class)))
                .thenThrow(new LlmCallException("timeout"));

        var draft = service.draft(5L, stubRequest(), HARD_FAIL_DECISION);

        assertThat(draft.isFallback()).isTrue();
        assertThat(draft.fallbackReason()).isEqualTo("TOOL_ERROR");
        assertThat(draft.notice()).contains("반려");
    }

    // ── deriveReasonCodes 직접 검증 ───────────────────────────────────────

    @Test
    void deriveReasonCodes_hardFail_있으면_코드_목록_반환() {
        var codes = RejectionReasonAgentService.deriveReasonCodes(HARD_FAIL_DECISION);
        assertThat(codes).containsExactly("DSR_EXCEEDED", "LTV_EXCEEDED");
    }

    @Test
    void deriveReasonCodes_hardFail_없으면_PD_THRESHOLD_EXCEEDED_단일() {
        var codes = RejectionReasonAgentService.deriveReasonCodes(PD_EXCEEDED_DECISION);
        assertThat(codes).containsExactly("PD_THRESHOLD_EXCEEDED");
    }
}
