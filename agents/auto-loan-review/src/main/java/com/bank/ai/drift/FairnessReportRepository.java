package com.bank.ai.drift;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class FairnessReportRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public void insert(FairnessReport r) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("reportMonth", r.reportMonth())
            .addValue("groupKey", r.groupKey())
            .addValue("approvalRate", r.approvalRate())
            .addValue("sampleCount", r.sampleCount())
            .addValue("overallRate", r.overallRate())
            .addValue("rateGap", r.rateGap())
            .addValue("flagged", r.flagged());

        String sql = """
            INSERT INTO fairness_report
                (report_month, group_key, approval_rate, sample_count, overall_rate, rate_gap, flagged)
            VALUES (:reportMonth, :groupKey, :approvalRate, :sampleCount, :overallRate, :rateGap, :flagged)
            ON CONFLICT (report_month, group_key)
            DO UPDATE SET approval_rate = EXCLUDED.approval_rate,
                          sample_count  = EXCLUDED.sample_count,
                          overall_rate  = EXCLUDED.overall_rate,
                          rate_gap      = EXCLUDED.rate_gap,
                          flagged       = EXCLUDED.flagged
            """;
        jdbc.update(sql, params);
    }

    public List<FairnessReport> findFlaggedByMonth(LocalDate reportMonth) {
        String sql = """
            SELECT report_month, group_key, approval_rate, sample_count, overall_rate, rate_gap, flagged
            FROM fairness_report WHERE report_month = :reportMonth AND flagged = TRUE
            """;
        return jdbc.query(sql, Map.of("reportMonth", reportMonth),
            (rs, row) -> new FairnessReport(
                rs.getDate("report_month").toLocalDate(),
                rs.getString("group_key"),
                rs.getDouble("approval_rate"),
                rs.getInt("sample_count"),
                rs.getDouble("overall_rate"),
                rs.getDouble("rate_gap"),
                rs.getBoolean("flagged")
            ));
    }
}
