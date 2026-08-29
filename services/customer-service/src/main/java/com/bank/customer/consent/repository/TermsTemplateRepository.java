package com.bank.customer.consent.repository;

import com.bank.customer.consent.domain.TermsTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TermsTemplateRepository extends JpaRepository<TermsTemplate, Long> {

    Optional<TermsTemplate> findByTermsNo(String termsNo);

    List<TermsTemplate> findByTermsNoIn(List<String> termsNos);

    /** 화면이 "이 업무에서 받아야 할 약관" 을 물을 때 쓴다. */
    List<TermsTemplate> findByBizDivCdAndActiveYnTrueOrderByTermsNo(String bizDivCd);
}
