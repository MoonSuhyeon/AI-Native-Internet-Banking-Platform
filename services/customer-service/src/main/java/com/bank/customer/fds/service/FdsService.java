package com.bank.customer.fds.service;

import com.bank.common.web.BusinessException;
import com.bank.customer.fds.domain.FdsDetection;
import com.bank.customer.fds.domain.FdsIncident;
import com.bank.customer.fds.domain.FdsRule;
import com.bank.customer.fds.dto.FdsDetectionResponse;
import com.bank.customer.fds.dto.FdsIncidentRequest;
import com.bank.customer.fds.dto.FdsIncidentResponse;
import com.bank.customer.fds.dto.FdsRuleRequest;
import com.bank.customer.fds.dto.FdsRuleResponse;
import com.bank.customer.fds.repository.FdsDetectionRepository;
import com.bank.customer.fds.repository.FdsIncidentRepository;
import com.bank.customer.fds.repository.FdsRuleRepository;
import com.bank.customer.history.repository.CertificateUseRepository;
import com.bank.customer.history.repository.PasswordHistoryRepository;
import com.bank.customer.login.repository.LoginAttemptRepository;
import com.bank.customer.support.CustomerErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FdsService {

    private final FdsRuleRepository        fdsRuleRepository;
    private final FdsDetectionRepository   fdsDetectionRepository;
    private final FdsIncidentRepository    fdsIncidentRepository;
    private final LoginAttemptRepository   loginAttemptRepository;
    private final CertificateUseRepository certificateUseRepository;
    private final PasswordHistoryRepository passwordHistoryRepository;
    private final ObjectMapper             objectMapper;

    // -------------------------------------------------------------------------
    // 탐지 평가 — LoginService / CertLoginService 에서 호출
    // -------------------------------------------------------------------------

    /**
     * 이벤트 발생 시 해당 eventType 의 활성 룰을 모두 평가한다.
     * BLOCK 액션 룰이 조건을 충족하면 BusinessException 을 던진다.
     *
     * @param customerId   대상 고객 ID
     * @param eventType    FdsDetection.EVENT_* 상수
     * @param referenceId  이벤트 원본 ID (login_attempt_id 등)
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public void evaluate(Long customerId, String eventType, Long referenceId) {
        List<FdsRule> rules = fdsRuleRepository
                .findByFdsRuleTargetEventCodeAndFdsRuleActiveYnAndDeletedAtIsNull(eventType, true);

        for (FdsRule rule : rules) {
            if (matches(rule, customerId)) {
                FdsDetection detection = fdsDetectionRepository.save(FdsDetection.builder()
                        .customerId(customerId)
                        .fdsRuleId(rule.getFdsRuleId())
                        .fdsDetectionEventTypeCode(eventType)
                        .fdsDetectionEventReferenceId(referenceId)
                        .fdsDetectedAt(OffsetDateTime.now())
                        .fdsDetectionStatusCode(FdsDetection.STATUS_PENDING)
                        .build());

                log.warn("FDS 탐지: customerId={} ruleCode={} detectionId={}",
                        customerId, rule.getFdsRuleCode(), detection.getFdsDetectionId());

                if (FdsRule.ACTION_BLOCK.equals(rule.getFdsRuleActionTypeCode())) {
                    throw new BusinessException(CustomerErrorCode.CUST_060);
                }
            }
        }
    }

    /**
     * 룰 조건 평가.
     * condition_json: {"window_minutes": 30, "threshold": 5}
     */
    private boolean matches(FdsRule rule, Long customerId) {
        try {
            JsonNode cond = objectMapper.readTree(rule.getFdsRuleConditionJson());
            int windowMinutes = cond.path("window_minutes").asInt(30);
            int threshold     = cond.path("threshold").asInt(5);
            OffsetDateTime since = OffsetDateTime.now().minusMinutes(windowMinutes);

            return switch (rule.getFdsRuleCategoryCode()) {
                case FdsRule.CATEGORY_LOGIN_FAILURE_COUNT ->
                        loginAttemptRepository.countFailuresSince(customerId, since) >= threshold;
                case FdsRule.CATEGORY_CERT_FAILURE_COUNT ->
                        certificateUseRepository.countCertFailuresByCustomerSince(customerId, since) >= threshold;
                case FdsRule.CATEGORY_PASSWORD_CHANGE_FREQ -> {
                    int days = cond.path("window_days").asInt(1);
                    OffsetDateTime sinceDays = OffsetDateTime.now().minusDays(days);
                    yield passwordHistoryRepository.countChangesSince(customerId, sinceDays) >= threshold;
                }
                default -> false;
            };
        } catch (JsonProcessingException e) {
            log.error("FDS 룰 condition_json 파싱 실패: ruleId={}", rule.getFdsRuleId(), e);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // 룰 관리 (직원용)
    // -------------------------------------------------------------------------

    @Transactional
    public FdsRuleResponse createRule(FdsRuleRequest req) {
        if (fdsRuleRepository.existsByFdsRuleCodeAndDeletedAtIsNull(req.fdsRuleCode())) {
            throw new BusinessException(CustomerErrorCode.CUST_061);
        }
        FdsRule rule = fdsRuleRepository.save(FdsRule.builder()
                .fdsRuleCode(req.fdsRuleCode())
                .fdsRuleName(req.fdsRuleName())
                .fdsRuleCategoryCode(req.fdsRuleCategoryCode())
                .fdsRuleTargetEventCode(req.fdsRuleTargetEventCode())
                .fdsRuleConditionJson(req.fdsRuleConditionJson())
                .fdsRuleRiskWeight(req.fdsRuleRiskWeight())
                .fdsRuleActionTypeCode(req.fdsRuleActionTypeCode())
                .fdsRuleActiveYn(false)
                .fdsRuleEffectiveDate(req.fdsRuleEffectiveDate())
                .fdsRuleExpiryDate(req.fdsRuleExpiryDate())
                .build());
        return FdsRuleResponse.from(rule);
    }

    @Transactional
    public FdsRuleResponse activateRule(Long ruleId) {
        FdsRule rule = findRule(ruleId);
        rule.activate();
        return FdsRuleResponse.from(rule);
    }

    @Transactional
    public FdsRuleResponse deactivateRule(Long ruleId) {
        FdsRule rule = findRule(ruleId);
        rule.deactivate();
        return FdsRuleResponse.from(rule);
    }

    @Transactional(readOnly = true)
    public List<FdsRuleResponse> listRules() {
        return fdsRuleRepository.findAll().stream()
                .filter(r -> r.getDeletedAt() == null)
                .map(FdsRuleResponse::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // 탐지 조회 (직원용)
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<FdsDetectionResponse> listPendingDetections(Pageable pageable) {
        return fdsDetectionRepository
                .findByFdsDetectionStatusCodeOrderByFdsDetectedAtDesc(
                        FdsDetection.STATUS_PENDING, pageable)
                .map(FdsDetectionResponse::from);
    }

    @Transactional
    public FdsDetectionResponse confirmDetection(Long detectionId) {
        FdsDetection d = findDetection(detectionId);
        d.confirm();
        return FdsDetectionResponse.from(d);
    }

    @Transactional
    public FdsDetectionResponse markFalsePositive(Long detectionId) {
        FdsDetection d = findDetection(detectionId);
        d.markFalsePositive();
        return FdsDetectionResponse.from(d);
    }

    // -------------------------------------------------------------------------
    // 사고 처리 (직원용)
    // -------------------------------------------------------------------------

    @Transactional
    public FdsIncidentResponse openIncident(FdsIncidentRequest req) {
        FdsDetection detection = findDetection(req.fdsDetectionId());
        if (!detection.isPending() && !FdsDetection.STATUS_CONFIRMED.equals(detection.getFdsDetectionStatusCode())) {
            throw new BusinessException(CustomerErrorCode.CUST_062);
        }
        if (fdsIncidentRepository.findByFdsDetectionId(req.fdsDetectionId()).isPresent()) {
            throw new BusinessException(CustomerErrorCode.CUST_063);
        }

        detection.confirm();

        FdsIncident incident = fdsIncidentRepository.save(FdsIncident.builder()
                .fdsDetectionId(req.fdsDetectionId())
                .fdsIncidentHandlerEmployeeId(req.handlerEmployeeId())
                .fdsIncidentTypeCode(req.fdsIncidentTypeCode())
                .fdsIncidentProcessStatusCode(
                        req.handlerEmployeeId() != null
                                ? FdsIncident.PROCESS_STATUS_PROCESSING
                                : FdsIncident.PROCESS_STATUS_OPEN)
                .fdsIncidentFssReportedYn(false)
                .build());

        return FdsIncidentResponse.from(incident);
    }

    @Transactional
    public FdsIncidentResponse closeIncident(Long incidentId) {
        FdsIncident incident = findIncident(incidentId);
        incident.close();
        return FdsIncidentResponse.from(incident);
    }

    @Transactional
    public FdsIncidentResponse reportToFss(Long incidentId) {
        FdsIncident incident = findIncident(incidentId);
        incident.reportToFss();
        return FdsIncidentResponse.from(incident);
    }

    @Transactional(readOnly = true)
    public Page<FdsIncidentResponse> listOpenIncidents(Pageable pageable) {
        return fdsIncidentRepository
                .findByFdsIncidentProcessStatusCodeOrderByFdsIncidentIdDesc(
                        FdsIncident.PROCESS_STATUS_OPEN, pageable)
                .map(FdsIncidentResponse::from);
    }

    // -------------------------------------------------------------------------
    // 내부 헬퍼
    // -------------------------------------------------------------------------

    private FdsRule findRule(Long ruleId) {
        return fdsRuleRepository.findById(ruleId)
                .filter(r -> r.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_064));
    }

    private FdsDetection findDetection(Long detectionId) {
        return fdsDetectionRepository.findById(detectionId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_062));
    }

    private FdsIncident findIncident(Long incidentId) {
        return fdsIncidentRepository.findById(incidentId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_063));
    }
}
