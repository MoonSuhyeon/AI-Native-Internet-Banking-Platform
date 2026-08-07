package com.bank.customer.cert.service;

import com.bank.common.web.BusinessException;
import com.bank.customer.cert.domain.AuthMethod;
import com.bank.customer.cert.dto.AuthMethodResponse;
import com.bank.customer.cert.repository.AuthMethodRepository;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthMethodService {

    private final AuthMethodRepository authMethodRepository;

    @Transactional(readOnly = true)
    public List<AuthMethodResponse> listMyAuthMethods(Long customerId) {
        return authMethodRepository.findByCustomerIdAndDeletedAtIsNull(customerId)
                .stream().map(AuthMethodResponse::from).toList();
    }

    @Transactional
    public AuthMethodResponse updateAlias(Long customerId, Long authMethodId, String alias) {
        AuthMethod method = findOwned(customerId, authMethodId);
        method.updateAlias(alias);
        return AuthMethodResponse.from(method);
    }

    /** 주 인증수단 변경 — 기존 주 인증수단을 해제하고 새로 지정 */
    @Transactional
    public AuthMethodResponse setPrimary(Long customerId, Long authMethodId) {
        authMethodRepository.findByCustomerIdAndPrimaryAuthMethodYnAndDeletedAtIsNull(customerId, true)
                .ifPresent(prev -> prev.setPrimary(false));

        AuthMethod method = findOwned(customerId, authMethodId);
        if (!method.isActive()) throw new BusinessException(CustomerErrorCode.CUST_130);
        method.setPrimary(true);
        return AuthMethodResponse.from(method);
    }

    @Transactional
    public void deactivate(Long customerId, Long authMethodId) {
        AuthMethod method = findOwned(customerId, authMethodId);
        if (method.isPrimary()) throw new BusinessException(CustomerErrorCode.CUST_131);
        method.deactivate();
    }

    private AuthMethod findOwned(Long customerId, Long authMethodId) {
        return authMethodRepository
                .findByAuthMethodIdAndCustomerIdAndDeletedAtIsNull(authMethodId, customerId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_130));
    }
}
