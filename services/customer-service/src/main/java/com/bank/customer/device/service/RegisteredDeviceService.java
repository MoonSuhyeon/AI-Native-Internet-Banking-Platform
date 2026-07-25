package com.bank.customer.device.service;

import com.bank.common.security.Sha256;
import com.bank.common.web.BusinessException;
import com.bank.customer.device.domain.RegisteredDevice;
import com.bank.customer.device.dto.RegisterDeviceRequest;
import com.bank.customer.device.dto.RegisteredDeviceResponse;
import com.bank.customer.device.repository.RegisteredDeviceRepository;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RegisteredDeviceService {

    private final RegisteredDeviceRepository deviceRepository;

    @Transactional
    public RegisteredDeviceResponse register(Long customerId, RegisterDeviceRequest req, String ip) {
        String hash = Sha256.hex(req.deviceIdentifier());

        // 이미 등록된 기기면 마지막 사용 시각만 갱신
        return deviceRepository
                .findByCustomerIdAndDeviceFingerprintHashAndDeletedAtIsNull(customerId, hash)
                .map(existing -> {
                    existing.recordUsed();
                    return RegisteredDeviceResponse.from(existing);
                })
                .orElseGet(() -> {
                    RegisteredDevice device = deviceRepository.save(RegisteredDevice.builder()
                            .customerId(customerId)
                            .deviceName(req.deviceName())
                            .deviceTypeCode(req.deviceTypeCode())
                            .deviceOsName(req.deviceOsName())
                            .deviceOsVersion(req.deviceOsVersion())
                            .deviceFingerprintHash(hash)
                            .trustedDeviceYn("F")
                            .designatedPcYn("F")
                            .deviceRegisteredIp(ip)
                            .deviceStatusCode(RegisteredDevice.STATUS_ACTIVE)
                            .build());
                    return RegisteredDeviceResponse.from(device);
                });
    }

    @Transactional(readOnly = true)
    public List<RegisteredDeviceResponse> listMyDevices(Long customerId) {
        return deviceRepository.findByCustomerIdAndDeletedAtIsNull(customerId)
                .stream()
                .map(RegisteredDeviceResponse::from)
                .toList();
    }

    @Transactional
    public RegisteredDeviceResponse trust(Long customerId, Long deviceId) {
        RegisteredDevice device = findOwned(customerId, deviceId);
        device.trust();
        return RegisteredDeviceResponse.from(device);
    }

    @Transactional
    public RegisteredDeviceResponse untrust(Long customerId, Long deviceId) {
        RegisteredDevice device = findOwned(customerId, deviceId);
        device.untrust();
        return RegisteredDeviceResponse.from(device);
    }

    @Transactional
    public RegisteredDeviceResponse designate(Long customerId, Long deviceId) {
        RegisteredDevice device = findOwned(customerId, deviceId);
        if (!RegisteredDevice.TYPE_PC.equals(device.getDeviceTypeCode())) {
            throw new BusinessException(CustomerErrorCode.CUST_071);
        }
        device.designate();
        return RegisteredDeviceResponse.from(device);
    }

    @Transactional
    public void revoke(Long customerId, Long deviceId) {
        RegisteredDevice device = findOwned(customerId, deviceId);
        device.revoke();
    }

    private RegisteredDevice findOwned(Long customerId, Long deviceId) {
        return deviceRepository
                .findByDeviceIdAndCustomerIdAndDeletedAtIsNull(deviceId, customerId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_070));
    }

}
