package com.bank.loan.consent.service;

import com.bank.common.web.BusinessException;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.consent.domain.CreditConsent;
import com.bank.loan.consent.dto.CreateCreditConsentRequest;
import com.bank.loan.consent.dto.CreditConsentResponse;
import com.bank.loan.consent.repository.CreditConsentRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class CreditConsentService {

    private final CreditConsentRepository repository;
    private final LoanApplicationRepository applicationRepository;

    @Transactional
    public CreditConsentResponse create(Long applId, CreateCreditConsentRequest req,
                                        String clientIp, String device) {
        LoanApplication application = applicationRepository.findByApplIdAndDeletedAtIsNull(applId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_012));

        OffsetDateTime now = OffsetDateTime.now();
        CreditConsent saved = repository.save(CreditConsent.builder()
                .applId(application.getApplId())
                .customerId(application.getCustomerId())
                .consentTypeCd(req.consentTypeCd())
                .consentScopeCd(req.consentScopeCd())
                .consentTargetCd(req.consentTargetCd())
                .consentYn(true)
                .consentedAt(now)
                .consentMethodCd(req.consentMethodCd())
                .consentToken(req.consentToken())
                .signedDocUrl(req.signedDocUrl())
                .signedDocHash(req.signedDocHash())
                .clientIp(clientIp)
                .device(device)
                .retentionUntil(req.retentionUntil())
                .withdrawnYn(false)
                .build());

        return CreditConsentResponse.of(saved);
    }
}
