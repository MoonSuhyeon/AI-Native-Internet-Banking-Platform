package com.bank.loan.prescreening.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * AutoReviewEvaluateClient 단위 테스트 — Spring 컨텍스트 없이 MockRestServiceServer 사용.
 */
class AutoReviewEvaluateClientTest {

    private static final String BASE_URL      = "http://auto-loan-review:8089";
    private static final String EVALUATE_PATH = "/api/ai/auto-review/evaluate";
    private static final String TOKEN         = "test-token";

    private MockRestServiceServer server;
    private AutoReviewEvaluateClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        RestClient.Builder builder = RestClient.builder(restTemplate);
        AutoReviewProperties props = new AutoReviewProperties(true, BASE_URL, TOKEN, 0, 0);
        client = new AutoReviewEvaluateClient(builder, props);
    }

    @Test
    void TRACK_1_응답_정상_매핑() {
        server.expect(requestTo(BASE_URL + EVALUATE_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", TOKEN))
                .andRespond(withSuccess("""
                        {
                          "code": "OK",
                          "message": "OK",
                          "data": {
                            "track": "TRACK_1",
                            "pd": 0.023456,
                            "rationale": "CB 우량, DSR 양호, 자동 승인"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        AutoReviewEvaluateResult result = client.evaluate(sampleRequest());

        assertThat(result.track()).isEqualTo(AutoReviewEvaluateResult.TRACK_1);
        assertThat(result.pd()).isEqualByComparingTo(new BigDecimal("0.023456"));
        assertThat(result.rationale()).contains("자동 승인");
        server.verify();
    }

    @Test
    void TRACK_2_응답_정상_매핑() {
        server.expect(requestTo(BASE_URL + EVALUATE_PATH))
                .andRespond(withSuccess("""
                        {
                          "code": "OK",
                          "message": "OK",
                          "data": {
                            "track": "TRACK_2",
                            "pd": 0.412000,
                            "rationale": "PD 임계값 초과, 자동 반려"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        AutoReviewEvaluateResult result = client.evaluate(sampleRequest());

        assertThat(result.track()).isEqualTo(AutoReviewEvaluateResult.TRACK_2);
        assertThat(result.pd()).isGreaterThan(new BigDecimal("0.4"));
        server.verify();
    }

    @Test
    void TRACK_3_응답_정상_매핑() {
        server.expect(requestTo(BASE_URL + EVALUATE_PATH))
                .andRespond(withSuccess("""
                        {
                          "code": "OK",
                          "message": "OK",
                          "data": {
                            "track": "TRACK_3",
                            "pd": 0.185000,
                            "rationale": "중간 위험군, 심사원 배정 필요"
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        AutoReviewEvaluateResult result = client.evaluate(sampleRequest());

        assertThat(result.track()).isEqualTo(AutoReviewEvaluateResult.TRACK_3);
        server.verify();
    }

    @Test
    void 서버_5xx_시_예외_전파() {
        server.expect(ExpectedCount.once(), requestTo(BASE_URL + EVALUATE_PATH))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.evaluate(sampleRequest()))
                .isInstanceOf(Exception.class);
        server.verify();
    }

    @Test
    void enabled_false_시_null_반환() {
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer noCallServer = MockRestServiceServer.bindTo(rt).build();
        AutoReviewProperties disabledProps = new AutoReviewProperties(false, BASE_URL, TOKEN, 0, 0);
        AutoReviewEvaluateClient disabledClient = new AutoReviewEvaluateClient(
                RestClient.builder(rt), disabledProps);

        AutoReviewEvaluateResult result = disabledClient.evaluate(sampleRequest());

        assertThat(result).isNull();
        noCallServer.verify(); // 실제 HTTP 호출이 없어야 함
    }

    private AutoReviewEvaluateRequest sampleRequest() {
        return new AutoReviewEvaluateRequest(
                null, 50000000L, 30000000L, 36,
                "LIVING_COST", "EMPLOYED", 720,
                null, null, "CREDIT_LOAN_01",
                null, null, null, null, null
        );
    }
}
