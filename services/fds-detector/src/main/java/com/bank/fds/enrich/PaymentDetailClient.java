package com.bank.fds.enrich;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Optional;

/**
 * 결제계 내부 API 로 거래 상세를 보강한다.
 *
 * <p><b>왜 이벤트에 다 안 싣고 조회하는가.</b> {@code payment.completed} 페이로드를 늘리면
 * 결제 트랜잭션 경로를 건드리게 된다. 탐지 때문에 결제가 느려지거나 실패하면 안 된다.
 *
 * <p><b>실패는 조용히 넘긴다(fail-soft).</b> 보강에 실패해도 예외를 던지지 않고
 * {@link Optional#empty()} 를 준다. 한 건을 못 읽었다고 스트림 처리를 멈추면
 * 그 뒤 거래가 전부 탐지되지 않는다 — 못 잡는 것보다 나쁜 결과다.
 * 대신 보강 실패는 지표로 세어 눈에 보이게 한다.
 */
@Slf4j
@Component
public class PaymentDetailClient {

    private final RestClient restClient;

    // 생성자가 둘이라(테스트 seam) 스프링이 어느 것을 쓸지 모른다.
    // 표시하지 않으면 부팅이 NoSuchMethodException 으로 죽는다.
    @Autowired
    public PaymentDetailClient(
            RestClient.Builder builder,
            @Value("${fds.core-banking.base-url:http://core-banking:8082}") String baseUrl,
            @Value("${fds.core-banking.timeout-ms:3000}") int timeoutMs) {

        // 타임아웃을 반드시 건다. 기본값은 무제한이라, 결제계가 응답만 안 하고
        // 연결은 살아 있으면 컨슈머 스레드가 그대로 묶여 탐지가 통째로 멈춘다.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));

        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 결제지시 상세. 없거나 조회에 실패하면 empty. */
    public Optional<PaymentDetail> fetch(String paymentInstructionId) {
        try {
            PaymentDetail detail = restClient.get()
                    .uri("/api/v1/internal/payments/{id}", paymentInstructionId)
                    .retrieve()
                    .body(PaymentDetail.class);
            return Optional.ofNullable(detail);
        } catch (Exception e) {
            // 404(없는 건)와 통신 실패를 같이 받는다. 탐지 입장에서는 둘 다
            // "보강 못 함" 으로 같고, 어느 쪽인지는 로그와 결제계 쪽에서 본다.
            log.warn("거래 상세 보강 실패 piId={} reason={}", paymentInstructionId, e.toString());
            return Optional.empty();
        }
    }

    /** 테스트에서 임의 RestClient 를 주입하기 위한 생성자. */
    PaymentDetailClient(RestClient restClient) {
        this.restClient = restClient;
    }
}
