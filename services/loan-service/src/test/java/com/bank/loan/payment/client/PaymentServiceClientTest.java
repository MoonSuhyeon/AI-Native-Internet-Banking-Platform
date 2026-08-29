package com.bank.loan.payment.client;

import com.bank.common.security.service.ServiceOperation;
import com.bank.loan.payment.client.dto.PaymentRequest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 여신이 결제를 부를 때의 <b>계약</b>을 고정한다.
 *
 * <p>예전 이 테스트는 {@code deriveAuthTokenId} 를 검증했다. 그 메서드는 payment 의
 * {@code auth_token_id} 가 UNIQUE 라는 이유로 멱등키를 해시해 채우던 것인데, core 는
 * 같은 헤더를 고객 승인 토큰으로 읽어 검증하러 갔다. 한 헤더가 두 의미를 겸했고,
 * 그래서 여신 자금이동이 어느 쪽으로도 통과할 수 없었다.
 *
 * <p>이제 검증할 것이 바뀌었다 — <b>고객 경로가 아니라 내부 경로로 가는가</b>,
 * <b>고객 승인 토큰을 만들어 보내지 않는가</b>, <b>작업을 선언하는가</b>.
 * 이 셋이 깨지면 그때의 실패로 되돌아간다.
 */
class PaymentServiceClientTest {

    private static WireMockServer server;
    private static PaymentServiceClient client;

    @BeforeAll
    static void startServer() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        server.stubFor(WireMock.post(WireMock.urlEqualTo("/api/v1/internal/payments"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"paymentInstructionId":"P-1","transactionNo":"T-1",
                                 "status":"COMPLETED","failureCategory":null}""")));

        PaymentServiceProperties props = new PaymentServiceProperties(
                server.baseUrl(),
                "dev-secret-loan-service-credential",
                new PaymentServiceProperties.Collection("088", "00000000000", "한국은행"),
                new PaymentServiceProperties.Disbursement("BANK_DISBURSE_001"));
        // WireMock 은 HTTP/1.1 만 받는데 Spring 6.1 의 기본 JDK 클라이언트는 HTTP/2 로
        // 붙어 RST_STREAM 으로 끊긴다. 여기서 보려는 것은 요청의 모양이지 전송 방식이
        // 아니므로 HTTP/1.1 클라이언트로 고정한다.
        client = new PaymentServiceClient(
                RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory()), props);
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    private static PaymentRequest disburseRequest() {
        return new PaymentRequest(
                ServiceOperation.LOAN_DISBURSE.code(),
                "0040000000002", "001", "001-2000-0000012", "홍길동",
                BigDecimal.valueOf(1_000_000L),
                "대출실행", "대출실행", "OPEN_BANKING", "CNTR-1");
    }

    @Test
    @DisplayName("고객 경로가 아니라 내부 경로로 보낸다")
    void callsInternalPath() {
        client.pay("EXEC-1-1", disburseRequest());

        server.verify(WireMock.postRequestedFor(
                WireMock.urlEqualTo("/api/v1/internal/payments")));
    }

    @Test
    @DisplayName("고객 승인 토큰을 만들어 보내지 않는다")
    void doesNotForgeCustomerApprovalToken() {
        client.pay("EXEC-1-2", disburseRequest());

        // X-Auth-Token-Id 는 고객 인증을 증명하는 값이다. 여신에게는 증명할 고객이
        // 없으므로 보내지 않는다. 보내면 core 가 그것을 승인 토큰으로 검증하러 간다.
        server.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/api/v1/internal/payments"))
                .withoutHeader("X-Auth-Token-Id"));
    }

    @Test
    @DisplayName("자격증명을 보내고, 신원을 주장하지 않는다")
    void sendsCredentialNotClaimedIdentity() {
        client.pay("EXEC-1-3", disburseRequest());

        // 신원은 자격증명에서 나온다. "나는 여신이다" 라고 말하는 헤더를 보내면,
        // 토큰 하나를 가진 무엇이든 여신을 사칭할 수 있다.
        server.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/api/v1/internal/payments"))
                .withHeader("X-Internal-Token",
                        WireMock.equalTo("dev-secret-loan-service-credential"))
                .withoutHeader("X-Service-Id"));
    }

    @Test
    @DisplayName("작업을 본문에 선언한다")
    void declaresOperation() {
        client.pay("EXEC-1-4", disburseRequest());

        server.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/api/v1/internal/payments"))
                .withRequestBody(WireMock.matchingJsonPath("$.operation",
                        WireMock.equalTo("LOAN_DISBURSE"))));
    }

    @Test
    @DisplayName("멱등키는 그대로 전달한다")
    void forwardsIdempotencyKey() {
        client.pay("AUTO-9-1-20260828", disburseRequest());

        server.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/api/v1/internal/payments"))
                .withHeader("X-Idempotency-Key", WireMock.equalTo("AUTO-9-1-20260828")));
    }

    @Test
    @DisplayName("응답을 그대로 돌려준다")
    void returnsResponse() {
        var resp = client.pay("EXEC-1-5", disburseRequest());

        assertThat(resp).isNotNull();
        assertThat(resp.status()).isEqualTo("COMPLETED");
        assertThat(resp.paymentInstructionId()).isEqualTo("P-1");
    }
}
