package com.bank.ai.drift;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PsiDriftBatchJob implements Tasklet {

    private final PsiBaselineRepository baselineRepo;
    private final PsiDriftResultRepository resultRepo;
    private final PsiDriftDetector detector;
    private final DriftAlertService alertService;
    private final NamedParameterJdbcTemplate jdbc;
    private final DriftProperties props;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        String calcWeekParam = (String) chunkContext.getStepContext()
            .getJobParameters().get("calcWeek");
        LocalDate calcWeek = (calcWeekParam != null)
            ? LocalDate.parse(calcWeekParam)
            : LocalDate.now().with(DayOfWeek.MONDAY);

        // 지난 주 데이터 (실제: 1주치; 테스트: 2040년 전체)
        LocalDate weekStart = calcWeek.minusWeeks(1);
        LocalDate weekEnd   = calcWeek;

        String sql = """
            SELECT CAST(request_snapshot AS VARCHAR) AS req
            FROM agent_audit_log
            WHERE created_at >= CAST(:from AS TIMESTAMP WITH TIME ZONE)
              AND created_at <  CAST(:to   AS TIMESTAMP WITH TIME ZONE)
            """;
        List<String> snapshots = jdbc.queryForList(sql,
            Map.of("from", weekStart.atStartOfDay().toString(),
                   "to",   weekEnd.atStartOfDay().toString()),
            String.class);

        if (snapshots.isEmpty()) {
            log.info("[PSI] 지난 주 데이터 없음 — 계산 생략");
            return RepeatStatus.FINISHED;
        }

        for (String feature : props.psiFeatures()) {
            processPsiForFeature(feature, snapshots, calcWeek);
        }
        return RepeatStatus.FINISHED;
    }

    private void processPsiForFeature(String feature, List<String> snapshots, LocalDate calcWeek) {
        List<PsiBaseline> baselines = baselineRepo.findByFeature(feature, props.modelVersion());
        if (baselines.isEmpty()) {
            log.warn("[PSI] baseline 없음 feature={}", feature);
            return;
        }

        List<double[]> bucketBounds = baselines.stream()
            .map(b -> new double[]{b.bucketLow(), b.bucketHigh()})
            .toList();
        List<Double> baselineRatios = baselines.stream().map(PsiBaseline::baselineRatio).toList();

        ObjectMapper om = new ObjectMapper();
        List<Double> currentValues = snapshots.stream()
            .mapMultiToDouble((s, consumer) -> {
                try {
                    double v = om.readTree(s).path(feature).asDouble(Double.NaN);
                    if (!Double.isNaN(v)) consumer.accept(v);
                } catch (Exception ignored) {}
            })
            .boxed().toList();

        if (currentValues.isEmpty()) return;

        List<Double> currentRatios = detector.bucketize(currentValues, bucketBounds);
        double psi = detector.calculatePsi(baselineRatios, currentRatios);
        PsiStatus status = detector.classify(psi);

        PsiDriftReport report = new PsiDriftReport(feature, psi, status, currentValues.size(), props.modelVersion());
        resultRepo.insert(report, calcWeek);
        alertService.alert(report);

        log.info("[PSI] feature={} psi={} status={} samples={}", feature, psi, status, currentValues.size());
    }
}
