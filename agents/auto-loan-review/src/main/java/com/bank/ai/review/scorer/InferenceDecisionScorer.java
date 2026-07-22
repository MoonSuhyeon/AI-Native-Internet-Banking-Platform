package com.bank.ai.review.scorer;

import com.bank.ai.review.client.InferenceClient;
import com.bank.ai.review.client.dto.InferenceRequest;
import com.bank.ai.review.client.dto.InferenceResponse;
import com.bank.ai.review.client.dto.PdInferenceResponse;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.support.AiErrorCode;
import com.bank.common.web.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * inference-server(Python/XGBoost) HTTP 호출 스코어러.
 *
 * <p>모델 아티팩트({@code data/models/auto_review_v1/}) 가 배포된 환경에서만 사용.
 * 로컬·개발 환경에서는 {@link HeuristicDecisionScorer} 를 기본으로 사용한다.
 *
 * <p>활성화: {@code ai.scoring.engine=inference}.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ai.scoring.engine", havingValue = "inference")
@RequiredArgsConstructor
public class InferenceDecisionScorer implements DecisionScorer {

    private static final String POSITIVE_CLASS = "REJECT";

    private final InferenceClient inferenceClient;

    @Override
    public AutoReviewResponse score(AutoReviewRequest req) {
        Map<String, Object> features = toFeatureMap(req);
        InferenceRequest payload = new InferenceRequest(List.of(features));

        InferenceResponse decisionRes = inferenceClient.predict(payload);
        if (decisionRes == null || decisionRes.predictions() == null || decisionRes.predictions().isEmpty()) {
            log.error("inference /predict returned empty predictions");
            throw new BusinessException(AiErrorCode.INFERENCE_FAILED);
        }
        InferenceResponse.Prediction dp = decisionRes.predictions().get(0);

        Double pdScore = null;
        String pdModelVersion = null;
        try {
            PdInferenceResponse pdRes = inferenceClient.predictPd(payload);
            if (pdRes != null && pdRes.predictions() != null && !pdRes.predictions().isEmpty()) {
                pdScore = pdRes.predictions().get(0).pdScore();
                pdModelVersion = pdRes.modelVersion();
            }
        } catch (BusinessException e) {
            log.warn("PD inference unavailable, falling back to decision-only: {}", e.getMessage());
        }

        return new AutoReviewResponse(
                decisionRes.modelVersion(),
                dp.decision(),
                dp.score(),
                dp.proba(),
                pdScore,
                pdModelVersion
        );
    }

    private static Map<String, Object> toFeatureMap(AutoReviewRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sex", r.sex());
        m.put("age", r.age());
        m.put("marital_status", r.maritalStatus());
        m.put("military_status", r.militaryStatus());
        m.put("family_type", r.familyType());
        m.put("housing_type", r.housingType());
        m.put("education_level", r.educationLevel());
        m.put("bachelors_field", r.bachelorsField());
        m.put("occupation", r.occupation());
        m.put("district", r.district());
        m.put("province", r.province());
        m.put("applicant_segment", r.applicantSegment());
        m.put("income_quintile", r.incomeQuintile());
        m.put("annual_income_kw", r.annualIncomeKw());
        m.put("total_asset_kw", r.totalAssetKw());
        m.put("total_debt_kw", r.totalDebtKw());
        m.put("collateral_debt_kw", r.collateralDebtKw());
        m.put("credit_debt_kw", r.creditDebtKw());
        m.put("dsr", r.dsr());
        m.put("ltv", r.ltv());
        m.put("monthly_cashflow_mean_kw", r.monthlyCashflowMeanKw());
        m.put("monthly_cashflow_std_kw", r.monthlyCashflowStdKw());
        m.put("delinquency_history_24m", r.delinquencyHistory24m());
        m.put("credit_score_proxy", r.creditScoreProxy());
        m.put("product_code", r.productCode());
        m.put("requested_amount_kw", r.requestedAmountKw());
        m.put("requested_period_mo", r.requestedPeriodMo());
        m.put("purpose_cd", r.purposeCd());
        m.put("purpose_red_flag", r.purposeRedFlag());
        m.put("industry_cd", r.industryCd());
        m.put("region_risk_band", r.regionRiskBand());
        m.put("n_children", r.nChildren());
        m.put("employment_years", r.employmentYears());
        m.put("bureau_has_record", r.bureauHasRecord());
        m.put("bureau_n_active", r.bureauNActive());
        m.put("bureau_max_status_24m", r.bureauMaxStatus24m());
        return m;
    }
}
