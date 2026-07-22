package com.bank.ai.shadow.canary;

import com.bank.ai.shadow.ShadowResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Shadow 게이트 검사기 — E4-4 canary 진입 전 통과 여부 판정.
 *
 * <p>게이트 기준 (plan phase-e-elasticsearch.md §E4-4):
 * <ul>
 *   <li>shadow 결정 일치율 ≥ 95%  ({@code agreementRate})</li>
 *   <li>citation 누락률 ≤ 5%      ({@code citationMissRate})</li>
 *   <li>p99 검색 latency < 500ms  (Micrometer — 별도 Grafana 확인)</li>
 *   <li>p99 report latency < 8s   (Micrometer — 별도 Grafana 확인)</li>
 * </ul>
 *
 * <p>latency 게이트는 Micrometer → Prometheus → Grafana 에서 확인하며,
 * 본 클래스는 DB 집계 기반의 일치율·citation 두 지표만 검사한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(CanaryProperties.class)
public class ShadowGateChecker {

    static final double MIN_AGREEMENT_RATE     = 0.95;
    static final double MAX_CITATION_MISS_RATE = 0.05;

    private final ShadowResultRepository repository;
    private final CanaryProperties canaryProps;

    /**
     * 지정 기간의 shadow_run_result 를 집계해 게이트 통과 여부를 반환한다.
     *
     * @param from 집계 시작일 (inclusive)
     * @param to   집계 종료일 (inclusive)
     * @return {@link GateStatus}
     */
    public GateStatus check(LocalDate from, LocalDate to) {
        int total = repository.totalCount(from, to);

        if (total < canaryProps.minShadowRuns()) {
            log.warn("[Gate] 데이터 부족 total={} min={}", total, canaryProps.minShadowRuns());
            return GateStatus.insufficientData(total, canaryProps.minShadowRuns());
        }

        double agreementRate    = repository.agreementRate(from, to);
        double citationMissRate = repository.citationMissRate(from, to);

        List<String> failures = new ArrayList<>();
        if (agreementRate < MIN_AGREEMENT_RATE) {
            failures.add("agreement=%.3f < %.2f".formatted(agreementRate, MIN_AGREEMENT_RATE));
        }
        if (citationMissRate > MAX_CITATION_MISS_RATE) {
            failures.add("citation_miss=%.3f > %.2f".formatted(citationMissRate, MAX_CITATION_MISS_RATE));
        }

        boolean passed = failures.isEmpty();
        log.info("[Gate] from={} to={} total={} agreement={:.3f} citation_miss={:.3f} passed={}",
                from, to, total, agreementRate, citationMissRate, passed);

        if (passed) {
            return GateStatus.pass(total, agreementRate, citationMissRate);
        }
        return GateStatus.fail(total, agreementRate, citationMissRate, String.join(", ", failures));
    }
}
