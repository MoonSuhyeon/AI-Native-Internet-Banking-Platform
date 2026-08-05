package com.bank.deposit.security;

import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class AuthenticatedCustomerValidator {

    public static final String CUSTOMER_ID_HEADER = "X-Customer-Id";

    public void validate(String authenticatedCustomerId, String requestedCustomerId) {
        if (requestedCustomerId == null || requestedCustomerId.isBlank()) {
            return;
        }
        if (authenticatedCustomerId == null || authenticatedCustomerId.isBlank()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "인증된 고객 ID가 필요합니다.");
        }
        if (!authenticatedCustomerId.equals(requestedCustomerId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "다른 고객의 데이터에는 접근할 수 없습니다.");
        }
    }
}
