package com.bank.loan.advisory.rag;

import com.bank.common.web.BusinessException;
import com.bank.loan.support.LoanErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * AdvisoryOpenAiEmbeddingClient 단위 테스트.
 * MockRestServiceServer 로 OpenAI 응답을 stub — 실제 API 호출 없음.
 */
class AdvisoryOpenAiEmbeddingClientTest {

    private static final String BASE_URL  = "https://api.openai.test";
    private static final String API_KEY   = "sk-test";
    private static final int    DIMENSION = 4;   // 테스트용 축소 차원

    private MockRestServiceServer server;
    private AdvisoryOpenAiEmbeddingClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new AdvisoryOpenAiEmbeddingClient(
                RestClient.builder(restTemplate),
                props(API_KEY, 3));
    }

    @Test
    void defaultModelCd_OPENAI_3S() {
        assertThat(client.defaultModelCd()).isEqualTo("OPENAI_3S");
    }

    @Test
    void dimension_설정값_반환() {
        assertThat(client.dimension()).isEqualTo(DIMENSION);
    }

    @Test
    void 정상_응답_float_배열_반환() {
        server.expect(requestTo(BASE_URL + "/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value("text-embedding-3-small"))
                .andExpect(jsonPath("$.dimensions").value(DIMENSION))
                .andExpect(jsonPath("$.input[0]").value("DSR 초과 정책"))
                .andRespond(withSuccess("""
                        {
                          "object":"list",
                          "model":"text-embedding-3-small",
                          "data":[{"object":"embedding","index":0,"embedding":[0.1,0.2,0.3,0.4]}]
                        }
                        """, MediaType.APPLICATION_JSON));

        float[] v = client.embed("DSR 초과 정책");

        assertThat(v).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        server.verify();
    }

    @Test
    void api_key_빈_문자열이면_Authorization_헤더_미부착() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer noKeyServer = MockRestServiceServer.bindTo(rt).build();
        AdvisoryOpenAiEmbeddingClient noKeyClient =
                new AdvisoryOpenAiEmbeddingClient(RestClient.builder(rt), props("", 3));

        noKeyServer.expect(requestTo(BASE_URL + "/v1/embeddings"))
                .andExpect(req -> assertThat(req.getHeaders().containsKey("Authorization"))
                        .as("Authorization 헤더가 없어야 함").isFalse())
                .andRespond(withSuccess("""
                        {"object":"list","model":"x","data":[
                          {"index":0,"embedding":[0.1,0.2,0.3,0.4]}]}
                        """, MediaType.APPLICATION_JSON));

        noKeyClient.embed("텍스트");
        noKeyServer.verify();
    }

    @Test
    void 응답_차원_불일치_LOAN_211() {
        server.expect(requestTo(BASE_URL + "/v1/embeddings"))
                .andRespond(withSuccess("""
                        {"object":"list","model":"x","data":[
                          {"index":0,"embedding":[0.1,0.2,0.3]}]}
                        """, MediaType.APPLICATION_JSON));   // 3차원 — 설정은 4

        assertThatThrownBy(() -> client.embed("청크"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(LoanErrorCode.LOAN_211));
    }

    @Test
    void 응답_비어있음_LOAN_211() {
        server.expect(requestTo(BASE_URL + "/v1/embeddings"))
                .andRespond(withSuccess("""
                        {"object":"list","model":"x","data":[]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embed("청크"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(LoanErrorCode.LOAN_211));
    }

    @Test
    void 외부_API_4xx_즉시_실패_LOAN_211() {
        server.expect(ExpectedCount.once(), requestTo(BASE_URL + "/v1/embeddings"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> client.embed("청크"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(LoanErrorCode.LOAN_211));
        server.verify();
    }

    @Test
    void 외부_API_5xx_3회_후_LOAN_210() {
        server.expect(ExpectedCount.times(3), requestTo(BASE_URL + "/v1/embeddings"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.embed("청크"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(LoanErrorCode.LOAN_210));
        server.verify();
    }

    @Test
    void 외부_API_5xx_2회_후_3회차_성공() {
        server.expect(ExpectedCount.times(2), requestTo(BASE_URL + "/v1/embeddings"))
                .andRespond(withServerError());
        server.expect(ExpectedCount.once(), requestTo(BASE_URL + "/v1/embeddings"))
                .andRespond(withSuccess("""
                        {"object":"list","model":"x","data":[
                          {"index":0,"embedding":[0.1,0.2,0.3,0.4]}]}
                        """, MediaType.APPLICATION_JSON));

        float[] v = client.embed("청크");

        assertThat(v).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
        server.verify();
    }

    private static AdvisoryRagProperties props(String apiKey, int maxAttempts) {
        return new AdvisoryRagProperties(
                "openai",
                "text-embedding-3-small",
                DIMENSION,
                new AdvisoryRagProperties.OpenAi(
                        BASE_URL, apiKey, 0, 0, maxAttempts, 10L)
        );
    }
}
