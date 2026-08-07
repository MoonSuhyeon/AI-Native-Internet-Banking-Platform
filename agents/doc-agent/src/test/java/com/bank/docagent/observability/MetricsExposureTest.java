package com.bank.docagent.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 지표가 {@code /actuator/prometheus} 로 실제로 나가는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 지표를 만들어 두고도 밖으로 나가지 않는 일이 이 레포에서
 * 실제로 있었다 — auto-loan-review 는 계측이 완비돼 있었지만 compose 에서 포트를 안 열어
 * 두 달 넘게 하나도 수집되지 않았다. 코드만 봐서는 알 수 없는 종류다.
 *
 * <p>여기서는 그 앞단, 즉 "액추에이터가 우리 지표를 내보내는가"를 본다.
 * {@code management.endpoints.web.exposure.include} 에서 prometheus 가 빠지거나
 * 레지스트리 배선이 끊기면 여기서 걸린다.
 *
 * <p>{@code @AutoConfigureObservability} 가 필요하다. Boot 는 테스트에서 지표 내보내기를
 * 기본으로 꺼서, 없으면 운영에서 멀쩡한 설정도 404 로 보인다 — 없는 문제를 쫓게 된다.
 *
 * <p>Micrometer 는 처음 기록될 때 미터를 등록하므로, 노출을 확인하려면 먼저 한 번
 * 기록해야 한다. 등록 전 스크레이프에서 지표가 안 보이는 것은 정상이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Spring Boot 는 테스트 컨텍스트에서 지표 내보내기를 기본으로 끈다
// (management.defaults.metrics.export.enabled=false). 이 애노테이션이 운영과 같은 상태로 되돌린다 —
// 없으면 PrometheusMeterRegistry 빈이 없어 /actuator/prometheus 가 404 다.
@AutoConfigureObservability
@ActiveProfiles("test")
class MetricsExposureTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocAgentMetrics metrics;

    @Test
    @DisplayName("actuator 가 서류 심사 지표를 프로메테우스 형식으로 내보낸다")
    void prometheusEndpointExposesDocAgentMetrics() throws Exception {
        metrics.recordVerdict("HOLD", "REGISTRY_DEED");
        metrics.recordHumanDecision("CONFIRMED_FORGERY");
        metrics.recordPipelineFailure("ocr");

        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("액추에이터 노출 목록에서 prometheus 가 빠지면 여기서 걸린다")
                .contains("doc_agent_verdict_total")
                .contains("doc_agent_human_review_total")
                .contains("doc_agent_pipeline_failed_total");

        // 라벨까지 나가는지 — 축이 없으면 대시보드에서 나눠 볼 수 없다.
        assertThat(body).contains("status=\"HOLD\"");
        assertThat(body).contains("decision=\"CONFIRMED_FORGERY\"");
    }
}
