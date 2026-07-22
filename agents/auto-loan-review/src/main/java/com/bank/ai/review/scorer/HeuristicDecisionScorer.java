package com.bank.ai.review.scorer;

import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 인프로세스 규칙 기반 스코어러 — inference-server(Python/XGBoost) 없이 동작.
 *
 * <p>피처 → 리스크 점수 {@code r ∈ [0,1]} 변환 후 3-클래스 확률 생성:
 * <ul>
 *   <li>신용점수 프록시 (낮을수록 위험)</li>
 *   <li>DSR — Debt Service Ratio (높을수록 위험, 0.4 초과부터 가산)</li>
 *   <li>LTV — Loan To Value (0.7 초과부터 가산)</li>
 *   <li>연체이력 24개월 (건수 기반 가산)</li>
 *   <li>목적 위험 플래그</li>
 *   <li>신용조회 최악 상태 (bureauMaxStatus24m)</li>
 * </ul>
 *
 * <p>모델 버전: {@value #MODEL_VERSION} — 학습 모델과 구분하기 위해 별도 식별.
 *
 * <p>활성화: {@code ai.scoring.engine=heuristic} 또는 설정 없음(기본).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.scoring.engine", havingValue = "heuristic", matchIfMissing = true)
public class HeuristicDecisionScorer implements DecisionScorer {

    static final String MODEL_VERSION = "heuristic-v1";
    static final String PD_MODEL_VERSION = "heuristic-pd-v1";

    // ---- 임계값 상수 (별도 변경 용이) ----
    private static final double DSR_WARN  = 0.40;
    private static final double DSR_HIGH  = 0.55;
    private static final double DSR_CRIT  = 0.70;
    private static final double LTV_WARN  = 0.70;
    private static final double LTV_HIGH  = 0.85;
    private static final double CREDIT_EXCELLENT = 750;
    private static final double CREDIT_GOOD      = 650;
    private static final double CREDIT_FAIR      = 500;
    private static final double CREDIT_POOR      = 300;

    @Override
    public AutoReviewResponse score(AutoReviewRequest req) {
        double r = computeRisk(req);

        Map<String, Double> proba = toProba(r);
        String decision = topClass(proba);
        double score = proba.get(decision);

        log.debug("heuristic score: r={:.3f} decision={} score={:.3f}", r, decision, score);

        return new AutoReviewResponse(
                MODEL_VERSION,
                decision,
                score,
                proba,
                r,                 // pdScore = 리스크 점수를 부도확률 대리변수로 사용
                PD_MODEL_VERSION
        );
    }

    // ------------------------------------------------------------------ //
    //  리스크 점수 계산                                                     //
    // ------------------------------------------------------------------ //

    /**
     * 피처별 위험 기여도를 합산해 {@code [0,1]} 로 클램핑한 리스크 점수 반환.
     *
     * <p>각 기여도 상수는 실험적 튜닝 기준이며, 이 메서드를 단위 테스트로 검증한다.
     */
    double computeRisk(AutoReviewRequest req) {
        double r = 0.0;

        // 1. 신용점수 (높을수록 안전)
        r += creditRisk(req.creditScoreProxy());

        // 2. DSR
        r += dsrRisk(req.dsr());

        // 3. LTV
        r += ltvRisk(req.ltv());

        // 4. 연체이력 (건수)
        r += delinquencyRisk(req.delinquencyHistory24m());

        // 5. 목적 위험 플래그
        if (Boolean.TRUE.equals(req.purposeRedFlag())) {
            r += 0.12;
        }

        // 6. 신용조회 최악 상태
        r += bureauRisk(req.bureauMaxStatus24m());

        return Math.min(r, 1.0);
    }

    private static double creditRisk(Integer creditScoreProxy) {
        if (creditScoreProxy == null) return 0.12;
        if (creditScoreProxy > CREDIT_EXCELLENT) return 0.00;
        if (creditScoreProxy > CREDIT_GOOD)      return 0.05;
        if (creditScoreProxy > CREDIT_FAIR)      return 0.20;
        if (creditScoreProxy > CREDIT_POOR)      return 0.35;
        return 0.50;
    }

    private static double dsrRisk(Double dsr) {
        if (dsr == null) return 0.04;
        if (dsr > DSR_CRIT) return 0.35;
        if (dsr > DSR_HIGH) return 0.20;
        if (dsr > DSR_WARN) return 0.10;
        return 0.0;
    }

    private static double ltvRisk(Double ltv) {
        if (ltv == null) return 0.02;
        if (ltv > LTV_HIGH) return 0.18;
        if (ltv > LTV_WARN) return 0.08;
        return 0.0;
    }

    private static double delinquencyRisk(Integer delinquencyHistory24m) {
        if (delinquencyHistory24m == null) return 0.02;
        if (delinquencyHistory24m >= 3)    return 0.30;
        if (delinquencyHistory24m >= 1)    return 0.15;
        return 0.0;
    }

    private static double bureauRisk(Integer bureauMaxStatus24m) {
        if (bureauMaxStatus24m == null) return 0.0;
        if (bureauMaxStatus24m >= 3)    return 0.18;
        if (bureauMaxStatus24m >= 1)    return 0.06;
        return 0.0;
    }

    // ------------------------------------------------------------------ //
    //  리스크 → 3-클래스 확률 변환                                          //
    // ------------------------------------------------------------------ //

    /**
     * r → {APPROVE, CONDITIONAL, REJECT} 확률 맵.
     *
     * <p>CONDITIONAL 확률은 중간 구간(r ≈ 0.5)에서 피크를 가지도록
     * quadratic 형태({@code 4·p_a·p_r}) 로 산출, 양 끝 클래스에서 차감 후 정규화.
     */
    static Map<String, Double> toProba(double r) {
        double pA = Math.max(0.0, 1.0 - r);
        double pR = Math.max(0.0, r);

        // CONDITIONAL 분배: 중간 구간에서 최대 20 % 이탈
        double condRaw = 0.20 * 4.0 * pA * pR;
        pA = pA - 0.5 * condRaw;
        pR = pR - 0.5 * condRaw;

        // 음수 방지 + 정규화
        pA = Math.max(0.0, pA);
        pR = Math.max(0.0, pR);
        double sum = pA + condRaw + pR;

        Map<String, Double> proba = new LinkedHashMap<>();
        proba.put("APPROVE",      round(pA      / sum));
        proba.put("CONDITIONAL",  round(condRaw / sum));
        proba.put("REJECT",       round(pR      / sum));
        return proba;
    }

    private static String topClass(Map<String, Double> proba) {
        return proba.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("REJECT");
    }

    private static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
