package com.bank.ai.metrics;

/**
 * Micrometer 태그 이름 상수 — 모든 메트릭에서 동일 문자열 사용 보장.
 *
 * <p>Grafana 쿼리 / AlertManager 규칙과 이름을 맞춰야 하므로 변경 시 대시보드 JSON 도 함께 갱신.
 */
public final class AgentMetricsTags {

    public static final String TRACK     = "track";
    public static final String OUTCOME   = "outcome";
    public static final String TOOL_NAME = "tool_name";
    public static final String MODEL     = "model";
    public static final String REASON    = "reason";
    public static final String STATUS    = "status";
    public static final String CORPUS    = "corpus";
    public static final String PHASE     = "phase";

    private AgentMetricsTags() {}
}
