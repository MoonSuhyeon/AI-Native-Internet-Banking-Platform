package com.bank.customer.consent.repository;

import com.bank.customer.consent.domain.TermsConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TermsConsentRepository extends JpaRepository<TermsConsent, Long> {

    /** 고객의 전체 동의 이력. 최근 것이 먼저다. */
    List<TermsConsent> findByCustomerIdOrderByAgreedAtDesc(Long customerId);

    List<TermsConsent> findByCustomerIdAndBizDivCdOrderByAgreedAtDesc(Long customerId, String bizDivCd);

    /**
     * 지금 유효한 동의가 있는가.
     *
     * <p>"동의한 적 있는가" 가 아니라 <b>"지금 유효한가"</b> 를 묻는다. 철회했으면
     * 없는 것이고, 거절했으면 애초에 없는 것이다. 이력에 행이 있다는 사실만으로
     * 판단하면 철회한 고객에게 계속 마케팅이 나간다.
     */
    Optional<TermsConsent> findFirstByCustomerIdAndTermsTemplateIdAndAgreedYnTrueAndWithdrawnYnFalseOrderByAgreedAtDesc(
            Long customerId, Long termsTemplateId);

    Optional<TermsConsent> findByConsentIdAndCustomerId(Long consentId, Long customerId);
}
