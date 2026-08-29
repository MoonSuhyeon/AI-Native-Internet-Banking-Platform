package com.bank.customer.consent.service;

import com.bank.customer.consent.domain.TermsConsent;
import com.bank.customer.consent.domain.TermsTemplate;
import com.bank.customer.consent.dto.ConsentRecordRequest;
import com.bank.customer.consent.dto.ConsentResponse;
import com.bank.customer.consent.repository.TermsConsentRepository;
import com.bank.customer.consent.repository.TermsTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 약관 동의를 기록하고 조회한다. 고객 도메인이 이 사실의 정본이다.
 *
 * <p><b>왜 여기인가.</b> 동의는 고객에 대한 사실이므로 고객 신원과 상태를 아는 곳이
 * 지켜야 한다. 예전에는 {@code common_db} 에 자리만 있었고 고객번호에 FK 를 걸 수 없어
 * 없는 고객의 동의도 들어갈 수 있었다. 여기서는 FK 가 실제로 선다.
 */
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final TermsConsentRepository consentRepository;
    private final TermsTemplateRepository templateRepository;

    /**
     * 한 화면에서 받은 동의를 한 번에 남긴다.
     *
     * <p>항목 하나라도 모르는 약관이면 <b>전체를 거절한다.</b> 아는 것만 저장하면
     * 화면은 다 받았다고 여기는데 기록은 일부만 남아, 나중에 무엇에 동의했는지
     * 말할 수 없게 된다. 조용히 반만 남는 것이 가장 나쁘다.
     */
    @Transactional
    public List<ConsentResponse> record(Long customerId, ConsentRecordRequest request, String clientIp) {
        List<String> termsNos = request.items().stream().map(ConsentRecordRequest.Item::termsNo).toList();
        Map<String, TermsTemplate> templates = templateRepository.findByTermsNoIn(termsNos).stream()
                .collect(Collectors.toMap(TermsTemplate::getTermsNo, Function.identity(), (a, b) -> a));

        List<String> unknown = termsNos.stream().filter(no -> !templates.containsKey(no)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("등록되지 않은 약관이다: " + unknown);
        }

        // 필수 약관을 거절하면 그 업무가 진행되면 안 된다. 여기서 막지 않으면
        // "거절했는데 처리된" 기록이 남고, 그것이 가장 설명하기 어려운 상태다.
        List<String> rejectedRequired = request.items().stream()
                .filter(item -> !Boolean.TRUE.equals(item.agreed()))
                .filter(item -> templates.get(item.termsNo()).isRequiredYn())
                .map(ConsentRecordRequest.Item::termsNo)
                .toList();
        if (!rejectedRequired.isEmpty()) {
            throw new IllegalArgumentException("필수 약관에 동의하지 않았다: " + rejectedRequired);
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<TermsConsent> saved = request.items().stream()
                .map(item -> consentRepository.save(TermsConsent.builder()
                        .customerId(customerId)
                        .termsTemplateId(templates.get(item.termsNo()).getTermsTemplateId())
                        .bizDivCd(request.bizDivCd())
                        .consentTargetId(request.consentTargetId())
                        .agreedYn(Boolean.TRUE.equals(item.agreed()))
                        .agreedAt(now)
                        .consentMethodCd(request.consentMethodCd())
                        .consentTool(request.consentTool())
                        .clientIp(clientIp)
                        .withdrawnYn(false)
                        .createdAt(now)
                        .createdBy(customerId)
                        .updatedAt(now)
                        .updatedBy(customerId)
                        .build()))
                .toList();

        Map<Long, TermsTemplate> byId = templates.values().stream()
                .collect(Collectors.toMap(TermsTemplate::getTermsTemplateId, Function.identity()));
        return saved.stream().map(c -> ConsentResponse.of(c, byId.get(c.getTermsTemplateId()))).toList();
    }

    /** 고객의 동의 이력. 업무를 지정하면 그 업무 것만. */
    @Transactional(readOnly = true)
    public List<ConsentResponse> history(Long customerId, String bizDivCd) {
        List<TermsConsent> rows = (bizDivCd == null || bizDivCd.isBlank())
                ? consentRepository.findByCustomerIdOrderByAgreedAtDesc(customerId)
                : consentRepository.findByCustomerIdAndBizDivCdOrderByAgreedAtDesc(customerId, bizDivCd);
        return attachTemplates(rows);
    }

    /**
     * 철회한다.
     *
     * <p>남의 동의를 철회할 수 없도록 고객번호를 함께 조건에 넣어 찾는다. 아이디만으로
     * 찾은 뒤 소유자를 검사하면, 없는 건과 남의 건이 다른 응답을 내어 <b>존재 여부가
     * 새어 나간다.</b>
     */
    @Transactional
    public ConsentResponse withdraw(Long customerId, Long consentId, String reason) {
        TermsConsent consent = consentRepository.findByConsentIdAndCustomerId(consentId, customerId)
                .orElseThrow(() -> new IllegalArgumentException("동의 이력을 찾을 수 없다: " + consentId));

        consent.withdraw(reason, OffsetDateTime.now(), customerId);
        return attachTemplates(List.of(consent)).get(0);
    }

    /**
     * 지금 유효한 동의인가.
     *
     * <p>"동의한 적 있는가" 가 아니다. 철회했으면 없는 것이다 — 이력에 행이 있다는
     * 사실만 보면 철회한 고객에게 계속 마케팅이 나간다.
     */
    @Transactional(readOnly = true)
    public boolean hasEffectiveConsent(Long customerId, String termsNo) {
        return templateRepository.findByTermsNo(termsNo)
                .flatMap(t -> consentRepository
                        .findFirstByCustomerIdAndTermsTemplateIdAndAgreedYnTrueAndWithdrawnYnFalseOrderByAgreedAtDesc(
                                customerId, t.getTermsTemplateId()))
                .isPresent();
    }

    private List<ConsentResponse> attachTemplates(List<TermsConsent> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        // 건마다 템플릿을 따로 읽으면 이력이 길수록 조회가 늘어난다(N+1).
        Map<Long, TermsTemplate> templates = templateRepository.findAllById(
                        rows.stream().map(TermsConsent::getTermsTemplateId).distinct().toList()).stream()
                .collect(Collectors.toMap(TermsTemplate::getTermsTemplateId, Function.identity()));

        return rows.stream()
                .map(c -> ConsentResponse.of(c, templates.get(c.getTermsTemplateId())))
                .sorted(Comparator.comparing(ConsentResponse::agreedAt).reversed())
                .toList();
    }
}
