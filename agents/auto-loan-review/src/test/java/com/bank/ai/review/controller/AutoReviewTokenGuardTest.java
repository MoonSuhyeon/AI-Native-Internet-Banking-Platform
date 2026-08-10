package com.bank.ai.review.controller;

import com.bank.ai.review.service.AutoReviewService;
import com.bank.ai.rule.service.RuleEngineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 자동심사 API 가 서비스 간 토큰 없이 열리지 않는지.
 *
 * <p><b>왜 필요한가.</b> {@code /api/ai/auto-review*} 는 loan-service 만 부르는데
 * 오랫동안 아무 검사가 없었다. 같은 모듈의 {@code EmbeddingBatchController} 는
 * {@code X-Internal-Token} 을 확인하는데 이쪽만 빠져 있었고, 정작 부르는 쪽은 그
 * 헤더를 이미 보내고 있었다 — <b>받는 쪽만 안 보고 있었다.</b>
 *
 * <p>열려 있으면 심사 결정 로직을 아무나 호출해 어떤 조건이 승인/반려로 갈리는지
 * 탐색할 수 있고, 호출마다 ML 추론과 LLM 이 돌아 비용과 처리량이 소모된다.
 *
 * <p>기존 통합 시험들은 이 결함을 못 잡았다. 헤더 없이도 통과하던 상태를 그대로
 * 검증하고 있었기 때문이다. 그래서 <b>거부되는 경우</b>를 따로 본다.
 */
@WebMvcTest(controllers = AutoReviewController.class,
        excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet
                .SecurityAutoConfiguration.class)
@TestPropertySource(properties = "ai.internal-token=test-internal-token")
class AutoReviewTokenGuardTest {

    private static final String VALID_TOKEN = "test-internal-token";

    /** 컨트롤러가 스키마 검증 전에 토큰을 보는지까지 확인하려면 유효한 본문이어야 한다. */
    private static final String BODY = """
            {
              "applId": 1,
              "creditScore": 800,
              "annualIncome": 60000000,
              "dsr": 30.0,
              "ltv": 50.0,
              "loanAmount": 100000000,
              "age": 35,
              "delinquencyCount": 0
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AutoReviewService autoReviewService;

    @MockBean
    private RuleEngineService ruleEngineService;

    @ParameterizedTest(name = "{0} — 토큰 없이 열리지 않는다")
    @ValueSource(strings = {"/api/ai/auto-review", "/api/ai/auto-review/evaluate"})
    @DisplayName("토큰이 없으면 401 이고 추론도 돌지 않는다")
    void withoutTokenIsRejected(String path) throws Exception {
        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        // 상태 코드만 보면 "거절했지만 이미 추론은 돌았다" 를 놓친다. 비용이 드는
        // 경로라 그 구분이 중요하다.
        verifyNoInteractions(autoReviewService, ruleEngineService);
    }

    @ParameterizedTest(name = "{0} — 틀린 토큰은 거절한다")
    @ValueSource(strings = {"/api/ai/auto-review", "/api/ai/auto-review/evaluate"})
    @DisplayName("토큰이 틀리면 401")
    void wrongTokenIsRejected(String path) throws Exception {
        mockMvc.perform(post(path)
                        .header("X-Internal-Token", VALID_TOKEN + "x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(autoReviewService, ruleEngineService);
    }
}
