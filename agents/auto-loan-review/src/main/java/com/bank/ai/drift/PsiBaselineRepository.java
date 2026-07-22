package com.bank.ai.drift;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class PsiBaselineRepository {

    private final NamedParameterJdbcTemplate jdbc;

    /** feature_name + model_version 기준 baseline 버킷 조회 (bucket_index 오름차순). */
    public List<PsiBaseline> findByFeature(String featureName, String modelVersion) {
        String sql = """
            SELECT feature_name, bucket_index, bucket_low, bucket_high, baseline_ratio
            FROM psi_baseline
            WHERE feature_name = :featureName AND model_version = :modelVersion
            ORDER BY bucket_index
            """;
        return jdbc.query(sql,
            Map.of("featureName", featureName, "modelVersion", modelVersion),
            (rs, row) -> new PsiBaseline(
                rs.getString("feature_name"),
                rs.getInt("bucket_index"),
                rs.getDouble("bucket_low"),
                rs.getDouble("bucket_high"),
                rs.getDouble("baseline_ratio")
            ));
    }

    public void insertBaseline(PsiBaseline b, LocalDate baselineDate, String modelVersion) {
        String sql = """
            INSERT INTO psi_baseline (feature_name, bucket_index, bucket_low, bucket_high,
                                       baseline_ratio, baseline_date, model_version)
            VALUES (:featureName, :bucketIndex, :bucketLow, :bucketHigh,
                    :baselineRatio, :baselineDate, :modelVersion)
            ON CONFLICT (feature_name, bucket_index, model_version) DO NOTHING
            """;
        jdbc.update(sql, new MapSqlParameterSource()
            .addValue("featureName", b.featureName())
            .addValue("bucketIndex", b.bucketIndex())
            .addValue("bucketLow", b.bucketLow())
            .addValue("bucketHigh", b.bucketHigh())
            .addValue("baselineRatio", b.baselineRatio())
            .addValue("baselineDate", baselineDate)
            .addValue("modelVersion", modelVersion));
    }
}
