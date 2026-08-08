package com.bank.fds.dispatch;

import com.bank.fds.detect.DetectionSignal;
import com.bank.fds.enrich.PaymentDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 사건을 조사 에이전트에 넘긴다.
 *
 * <p><b>탐지와 조사를 나눈 이유가 여기서 드러난다.</b> 조사는 건당 수 초~수십 초가
 * 걸리는데 탐지는 스트림을 따라가야 한다. 그래서 넘기기만 하고 결과를 기다리지 않는다.
 *
 * <p><b>실패해도 스트림을 세우지 않는다.</b> 조사 에이전트가 죽어도 탐지는 계속
 * 돌아야 한다. 다만 넘기지 못한 사건은 아무도 보지 않게 되므로, 조용히 지나가지 않고
 * 지표로 남긴다.
 *
 * <p>탐지 신호를 함께 넘긴다. 심사원이 "왜 이 건이 올라왔는가" 를 알아야 판단할 수 있고,
 * 조사 에이전트도 그 신호를 가설의 출발점으로 쓴다.
 */
@Slf4j
@Component
public class InvestigationDispatcher {

    private final RestClient restClient;

    @Value("${fds.investigation.enabled:true}")
    private boolean enabled;

    // 생성자가 둘이라(테스트 seam) 스프링이 어느 것을 쓸지 모른다.
    // 표시하지 않으면 부팅이 NoSuchMethodException 으로 죽는다.
    @Autowired
    public InvestigationDispatcher(
            RestClient.Builder builder,
            @Value("${fds.investigation.base-url:http://fraud-agent:8090}") String baseUrl,
            @Value("${fds.investigation.timeout-ms:5000}") int timeoutMs) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        factory.setReadTimeout(Duration.ofMillis(timeoutMs));

        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 테스트 seam. 운영 생성자는 타임아웃 때문에 requestFactory 를 직접 지정한다. */
    InvestigationDispatcher(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * @return 넘겼으면 true. 실패해도 예외를 던지지 않는다 — 호출부가 스트림을 계속 돌린다.
     */
    public boolean dispatch(PaymentDetail detail, List<DetectionSignal> signals, double anomalyScore) {
        if (!enabled) {
            return false;
        }

        Map<String, Object> tx = new HashMap<>();
        tx.put("alert_id", detail.paymentInstructionId());
        tx.put("customer_id", detail.senderUserId());
        tx.put("account", detail.senderAccountNo());
        tx.put("amount", detail.amount() == null ? 0L : detail.amount());
        tx.put("payee", detail.receiverAccountNo());
        tx.put("channel", detail.channel());
        tx.put("anomaly_score", anomalyScore);
        tx.put("signals", signals.stream().map(DetectionSignal::code).toList());
        if (detail.completedAt() != null) {
            tx.put("time", detail.completedAt().toString());
        }

        try {
            restClient.post()
                    .uri("/api/investigate")
                    .body(Map.of("transaction", tx))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            // 넘기지 못한 사건은 아무도 보지 않는다. 조용히 지나가면
            // "탐지는 도는데 큐가 비어 있다" 가 된다.
            log.error("조사 인계 실패 piId={} reason={}", detail.paymentInstructionId(), e.toString());
            return false;
        }
    }
}
