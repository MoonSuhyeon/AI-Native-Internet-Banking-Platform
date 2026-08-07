package com.bank.customer.device.dto;

import com.bank.customer.device.domain.RegisteredDevice;

import java.time.OffsetDateTime;

public record RegisteredDeviceResponse(
        Long           deviceId,
        String         deviceName,
        String         deviceTypeCode,
        String         deviceOsName,
        String         deviceOsVersion,
        boolean        trusted,
        boolean        designatedPc,
        String         deviceStatusCode,
        OffsetDateTime deviceLastUsedAt,
        OffsetDateTime registeredAt
) {
    public static RegisteredDeviceResponse from(RegisteredDevice d) {
        return new RegisteredDeviceResponse(
                d.getDeviceId(),
                d.getDeviceName(),
                d.getDeviceTypeCode(),
                d.getDeviceOsName(),
                d.getDeviceOsVersion(),
                d.isTrusted(),
                Boolean.TRUE.equals(d.getDesignatedPcYn()),
                d.getDeviceStatusCode(),
                d.getDeviceLastUsedAt(),
                d.getCreatedAt());
    }
}
