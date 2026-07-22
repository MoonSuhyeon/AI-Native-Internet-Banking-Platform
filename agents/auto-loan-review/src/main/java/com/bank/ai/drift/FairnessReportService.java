package com.bank.ai.drift;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(DriftProperties.class)
public class FairnessReportService {

    private final FairnessReportRepository repository;
    private final DriftAlertService alertService;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final DriftProperties props;

    /** 주어진 월의 agent_audit_log 기반 공정성 리포트 생성 및 저장. */
    public List<FairnessReport> generateMonthlyReport(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to   = month.atEndOfMonth();

        // 전체 데이터 조회 — CAST로 H2/PG 호환
        String sql = """
            SELECT CAST(request_snapshot AS VARCHAR) AS req,
                   CAST(opinion_json AS VARCHAR)     AS opin
            FROM agent_audit_log
            WHERE created_at >= CAST(:from AS TIMESTAMP WITH TIME ZONE)
              AND created_at <  CAST(:to   AS TIMESTAMP WITH TIME ZONE)
            """;

        var rows = jdbc.queryForList(sql, Map.of(
            "from", from.atStartOfDay().toString(),
            "to",   to.plusDays(1).atStartOfDay().toString()));

        if (rows.isEmpty()) return List.of();

        // 전체 승인률 계산
        long totalApproved = rows.stream().filter(r -> isApproved((String) r.get("opin"))).count();
        double overallRate = (double) totalApproved / rows.size();

        // 연령대별 집계
        Map<String, List<Boolean>> groups = new LinkedHashMap<>();
        for (var row : rows) {
            String ageBand = resolveAgeBand((String) row.get("req"));
            boolean approved = isApproved((String) row.get("opin"));
            groups.computeIfAbsent("age_band:" + ageBand, k -> new ArrayList<>()).add(approved);
        }

        List<FairnessReport> reports = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            List<Boolean> outcomes = entry.getValue();
            double approvalRate = outcomes.stream().mapToInt(b -> b ? 1 : 0).average().orElse(0.0);
            double gap          = approvalRate - overallRate;
            boolean flagged     = Math.abs(gap) > props.fairnessGapThreshold();

            FairnessReport report = new FairnessReport(
                from, entry.getKey(), approvalRate,
                outcomes.size(), overallRate, gap, flagged);
            repository.insert(report);
            if (report.flagged()) alertService.alertFairness(report);
            reports.add(report);
        }
        return reports;
    }

    private boolean isApproved(String opinionJson) {
        try {
            var node = objectMapper.readTree(opinionJson);
            var riskLevel = node.path("risk_level").asText("");
            return "LOW".equals(riskLevel);
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveAgeBand(String reqJson) {
        try {
            var node = objectMapper.readTree(reqJson);
            int age = node.path("age").asInt(0);
            if (age < 30) return "20s";
            if (age < 40) return "30s";
            if (age < 50) return "40s";
            if (age < 60) return "50s";
            return "60plus";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
