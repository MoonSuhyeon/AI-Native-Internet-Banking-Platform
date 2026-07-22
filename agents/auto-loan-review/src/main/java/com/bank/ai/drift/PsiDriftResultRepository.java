package com.bank.ai.drift;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PsiDriftResultRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public void insert(PsiDriftReport report, LocalDate calcWeek) {
        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("featureName", report.featureName())
            .addValue("calcWeek", calcWeek)
            .addValue("psiValue", report.psiValue())
            .addValue("status", report.status().name())
            .addValue("sampleCount", report.sampleCount())
            .addValue("modelVersion", report.modelVersion());

        String sql = """
            INSERT INTO psi_drift_result
                (feature_name, calc_week, psi_value, status, sample_count, model_version)
            VALUES (:featureName, :calcWeek, :psiValue, :status, :sampleCount, :modelVersion)
            ON CONFLICT (feature_name, calc_week, model_version)
            DO UPDATE SET psi_value    = EXCLUDED.psi_value,
                          status       = EXCLUDED.status,
                          sample_count = EXCLUDED.sample_count
            """;
        jdbc.update(sql, params);
    }

    public Optional<PsiDriftReport> findLatest(String featureName, String modelVersion) {
        String sql = """
            SELECT feature_name, psi_value, status, sample_count, model_version
            FROM psi_drift_result
            WHERE feature_name = :featureName AND model_version = :modelVersion
            ORDER BY calc_week DESC LIMIT 1
            """;
        List<PsiDriftReport> results = jdbc.query(sql,
            Map.of("featureName", featureName, "modelVersion", modelVersion),
            (rs, row) -> new PsiDriftReport(
                rs.getString("feature_name"),
                rs.getDouble("psi_value"),
                PsiStatus.valueOf(rs.getString("status")),
                rs.getInt("sample_count"),
                rs.getString("model_version")
            ));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
