package com.bank.deposit.client;

import com.bank.deposit.client.dto.HolderInfoResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "customer-service", url = "${client.customer-service.url}")
public interface CustomerServiceClient {

    @GetMapping("/api/internal/customers/{customerId}/holder-info")
    HolderInfoResponse getHolderInfo(
            @PathVariable("customerId") Long customerId,
            @RequestHeader("X-Caller-Service") String callerService
    );
}
