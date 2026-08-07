package com.bank.customer.pin.service;

import com.bank.common.time.BusinessDate;
import com.bank.common.web.BusinessException;
import com.bank.customer.cert.domain.AuthMethod;
import com.bank.customer.cert.repository.AuthMethodRepository;
import com.bank.customer.customer.domain.Credential;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.customer.repository.CredentialRepository;
import com.bank.customer.customer.repository.CustomerRepository;
import com.bank.customer.device.domain.RegisteredDevice;
import com.bank.customer.device.repository.RegisteredDeviceRepository;
import com.bank.customer.login.dto.LoginResponse;
import com.bank.customer.login.service.AuthEventService;
import com.bank.customer.pin.domain.Pin;
import com.bank.customer.pin.dto.PinLoginRequest;
import com.bank.customer.pin.dto.RegisterPinRequest;
import com.bank.customer.pin.repository.PinRepository;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class PinService {

    private static final int    DEFAULT_MAX_FAILURES = 5;
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PinRepository               pinRepository;
    private final CredentialRepository        credentialRepository;
    private final CustomerRepository          customerRepository;
    private final RegisteredDeviceRepository  deviceRepository;
    private final AuthMethodRepository        authMethodRepository;
    private final PasswordEncoder             passwordEncoder;
    private final AuthEventService            authEventService;

    /** PIN 등록 — 기존 비밀번호로 본인 확인 후 디바이스+고객 기준 PIN 저장 */
    @Transactional
    public void register(Long customerId, RegisterPinRequest req) {
        Credential credential = credentialRepository.findByCustomerIdAndDeletedAtIsNull(customerId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_002));

        if (!passwordEncoder.matches(req.currentPassword(), credential.getPasswordHash())) {
            throw new BusinessException(CustomerErrorCode.CUST_020);
        }

        RegisteredDevice device = deviceRepository
                .findByDeviceIdAndCustomerIdAndDeletedAtIsNull(req.deviceId(), customerId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_070));

        if (!device.isActive()) {
            throw new BusinessException(CustomerErrorCode.CUST_070);
        }

        if (pinRepository.existsByCustomerIdAndDeviceIdAndDeletedAtIsNull(customerId, req.deviceId())) {
            throw new BusinessException(CustomerErrorCode.CUST_081);
        }

        Long authMethodId = resolvePinAuthMethod(customerId);

        pinRepository.save(Pin.builder()
                .customerId(customerId)
                .authMethodId(authMethodId)
                .deviceId(req.deviceId())
                .pinHash(passwordEncoder.encode(req.pin()))
                .pinLength(req.pin().length())
                .pinLoginFailureCount(0)
                .maxPinLoginFailureCount(DEFAULT_MAX_FAILURES)
                .pinStatusCode(Pin.STATUS_ACTIVE)
                .build());
    }

    /**
     * 고객의 활성 PIN 인증수단을 확보한다.
     * PIN은 그 자체로 하나의 인증수단(auth_method_type_code='PIN')이므로,
     * 기존 PIN 인증수단이 있으면 재사용하고 없으면 새로 생성한다.
     * (기기마다 PIN을 등록하더라도 인증수단은 고객당 1개를 공유)
     */
    private Long resolvePinAuthMethod(Long customerId) {
        return authMethodRepository
                .findByCustomerIdAndAuthMethodStatusCodeAndDeletedAtIsNull(customerId, AuthMethod.STATUS_ACTIVE)
                .stream()
                .filter(m -> AuthMethod.TYPE_PIN.equals(m.getAuthMethodTypeCode()))
                .map(AuthMethod::getAuthMethodId)
                .findFirst()
                .orElseGet(() -> authMethodRepository.save(AuthMethod.builder()
                        .customerId(customerId)
                        .authMethodTypeCode(AuthMethod.TYPE_PIN)
                        .authMethodAliasName("간편비밀번호")
                        .authMethodStatusCode(AuthMethod.STATUS_ACTIVE)
                        .primaryAuthMethodYn("F")
                        .authMethodRegisteredDate(BusinessDate.today())
                        .build())
                        .getAuthMethodId());
    }

    /**
     * PIN 로그인. 인증 검증만 수행하고 성공/실패 후처리(이력·토큰·세션·FDS)는
     * {@link AuthEventService} 에 위임한다. PIN은 모바일 채널로 기록한다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public LoginResponse login(PinLoginRequest req, String ip, String userAgent) {
        Long customerId = null;

        try {
            Credential credential = credentialRepository
                    .findByLoginIdAndDeletedAtIsNull(req.loginId())
                    .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_010));

            customerId = credential.getCustomerId();

            if (!credential.isActive()) {
                throw new BusinessException(CustomerErrorCode.CUST_012);
            }

            Pin pin = pinRepository
                    .findByCustomerIdAndDeviceIdAndDeletedAtIsNull(credential.getCustomerId(), req.deviceId())
                    .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_080));

            if (pin.isLocked()) {
                throw new BusinessException(CustomerErrorCode.CUST_083);
            }
            if (!pin.isActive()) {
                throw new BusinessException(CustomerErrorCode.CUST_080);
            }

            if (!passwordEncoder.matches(req.pin(), pin.getPinHash())) {
                pin.recordLoginFailure();
                throw new BusinessException(
                        pin.isLocked() ? CustomerErrorCode.CUST_083 : CustomerErrorCode.CUST_082);
            }

            Customer customer = customerRepository
                    .findByCustomerIdAndDeletedAtIsNull(credential.getCustomerId())
                    .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_002));

            if (!customer.isActive()) {
                throw new BusinessException(CustomerErrorCode.CUST_012);
            }

            pin.recordLoginSuccess();

            return authEventService.onLoginSuccess(
                    customer, req.loginId(), ip, userAgent, AuthEventService.CHANNEL_MOBILE);

        } catch (BusinessException e) {
            authEventService.onLoginFailure(req.loginId(), customerId, ip, userAgent,
                    AuthEventService.CHANNEL_MOBILE, e.getErrorCode().getCode(), true);
            throw e;
        }
    }

    /** PIN 변경 */
    @Transactional
    public void changePin(Long customerId, Long deviceId, String currentPin, String newPin) {
        Pin pin = pinRepository
                .findByCustomerIdAndDeviceIdAndDeletedAtIsNull(customerId, deviceId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_080));

        if (!passwordEncoder.matches(currentPin, pin.getPinHash())) {
            throw new BusinessException(CustomerErrorCode.CUST_082);
        }

        pin.changePin(passwordEncoder.encode(newPin));
    }

    /** PIN 해제(폐기) */
    @Transactional
    public void revoke(Long customerId, Long deviceId) {
        Pin pin = pinRepository
                .findByCustomerIdAndDeviceIdAndDeletedAtIsNull(customerId, deviceId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_080));
        pin.revoke();
    }
}
