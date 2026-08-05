package com.bank.deposit.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class HomeController {

    @GetMapping("/")
    public Map<String, Object> home() {
        return Map.of(
                "service", "deposit-service",
                "status", "UP",
                "message", "API 서버입니다. Postman 컬렉션 또는 아래 엔드포인트로 테스트하세요.",
                "endpoints", List.of(
                        "/products",
                        "/contracts",
                        "/accounts?customerId=CUST001",
                        "/transactions?accountId=1",
                        "/interests?contractId=1",
                        "/special-terms",
                        "/target-groups",
                        "/departments"
                )
        );
    }
}
