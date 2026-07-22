package com.bank.ai.llm.support;

import com.bank.ai.llm.client.LlmClient;
import com.bank.ai.llm.client.LlmRequest;
import com.bank.ai.privacy.PiiLeakageException;
import com.bank.ai.privacy.PiiMaskingFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PiiAwareChatClient 단위 테스트 — plan/llm-pipeline.md §8.
 *
 * <p>입력 PII 마스킹 / 출력 PII 사후 검사 / 마스킹 토큰 오탐 방지 검증.
 */
class PiiAwareChatClientTest {

    private LlmClient mockDelegate;
    private PiiAwareChatClient client;

    /** 테스트용 LLM 응답 record — PII 없는 구조화 응답. */
    record SafeResponse(String message, double score) {}

    @BeforeEach
    void setUp() {
        mockDelegate = mock(LlmClient.class);
        client = new PiiAwareChatClient(mockDelegate, new PiiMaskingFilter(), new ObjectMapper());
    }

    private static LlmRequest req(String userContent) {
        return new LlmRequest("purpose_analysis", 1,
                "system prompt text", userContent, 512, 0.0);
    }

    // ── 정상 경로 ─────────────────────────────────────────────────────

    @Test
    void PII_없는_입력은_그대로_delegate_에_전달된다() {
        var expected = new SafeResponse("approved", 0.85);
        when(mockDelegate.call(any(), eq(SafeResponse.class))).thenReturn(expected);

        // 영문 입력: KOREAN_NAME 오탐 방지용 — 대출 사유 같은 일반 한국어 문장은
        // KOREAN_NAME 패턴에 의해 마스킹되므로, 여기서는 PII-free 판별이 명확한 영문 사용
        var result = client.call(req("mortgage loan for home purchase"), SafeResponse.class);

        assertThat(result).isEqualTo(expected);

        var captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(mockDelegate).call(captor.capture(), eq(SafeResponse.class));
        // PII 없으면 content 변경 없음
        assertThat(captor.getValue().userContent()).contains("mortgage loan for home purchase");
    }

    // ── 입력 마스킹 ───────────────────────────────────────────────────

    @Test
    void 이메일_PII_는_delegate_에_전달되기_전에_마스킹된다() {
        when(mockDelegate.call(any(), eq(SafeResponse.class)))
                .thenReturn(new SafeResponse("ok", 0.9));

        client.call(req("연락처: kim@example.com 로 답장 주세요"), SafeResponse.class);

        var captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(mockDelegate).call(captor.capture(), eq(SafeResponse.class));

        String sentContent = captor.getValue().userContent();
        assertThat(sentContent).doesNotContain("kim@example.com");
        assertThat(sentContent).contains("[[EMAIL_");
    }

    @Test
    void 전화번호_PII_는_delegate_에_전달되기_전에_마스킹된다() {
        when(mockDelegate.call(any(), eq(SafeResponse.class)))
                .thenReturn(new SafeResponse("ok", 0.9));

        client.call(req("문의: 010-1234-5678"), SafeResponse.class);

        var captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(mockDelegate).call(captor.capture(), eq(SafeResponse.class));

        String sentContent = captor.getValue().userContent();
        assertThat(sentContent).doesNotContain("010-1234-5678");
        // ACCOUNT 패턴이 PHONE 보다 먼저 적용될 수 있으므로 토큰 종류는 무관하게 마스킹 여부만 검증
        assertThat(sentContent).containsPattern("\\[\\[(PHONE|ACCT)_[0-9a-f]{8}\\]\\]");
    }

    // ── 출력 사후 검사 ─────────────────────────────────────────────────

    @Test
    void LLM_응답에_이메일_PII_포함시_PiiLeakageException() {
        // LLM 이 응답에 이메일을 생성한 경우
        when(mockDelegate.call(any(), eq(SafeResponse.class)))
                .thenReturn(new SafeResponse("user@leaked.com 으로 연락하세요", 0.5));

        assertThatThrownBy(() -> client.call(req("정상 입력"), SafeResponse.class))
                .isInstanceOf(PiiLeakageException.class)
                .hasMessageContaining("purpose_analysis");
    }

    @Test
    void LLM_응답에_전화번호_포함시_PiiLeakageException() {
        when(mockDelegate.call(any(), eq(SafeResponse.class)))
                .thenReturn(new SafeResponse("010-9999-8888 로 연락", 0.5));

        assertThatThrownBy(() -> client.call(req("정상 입력"), SafeResponse.class))
                .isInstanceOf(PiiLeakageException.class);
    }

    @Test
    void 마스킹_토큰이_출력에_있어도_PII_아니므로_예외_없음() {
        // LLM 이 마스킹 토큰([[EMAIL_abc123]])을 그대로 echoing 해도 raw PII 가 아님
        when(mockDelegate.call(any(), eq(SafeResponse.class)))
                .thenReturn(new SafeResponse("[[EMAIL_ab12cd34]] 참조", 0.9));

        // 예외 없이 정상 반환
        var result = client.call(req("정상 입력"), SafeResponse.class);
        assertThat(result.message()).contains("[[EMAIL_ab12cd34]]");
    }
}
