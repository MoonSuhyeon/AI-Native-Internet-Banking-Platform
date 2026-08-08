package com.bank.fds.api;

import com.bank.fds.api.dto.PreCheckRequest;
import com.bank.fds.api.dto.PreCheckResponse;
import com.bank.fds.detect.PreCheckService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사전 점검 — 결제계가 승인 <b>전</b>에 부른다.
 *
 * <p><b>이 API 는 결제 임계 경로에 있다.</b> 느려지면 모든 이체가 느려진다.
 * 그래서 여기서는 조회와 가벼운 판정만 하고, 무거운 분석은 사후 탐지가 맡는다.
 *
 * <p><b>호출부가 지켜야 할 것.</b> 결제계는 짧은 타임아웃을 걸고, 실패하면
 * 이 응답을 기다리지 않고 자체 정책(금액 구간별)으로 진행해야 한다.
 * 탐지기가 죽었다고 이체가 전부 멈추면 그게 더 큰 사고다.
 */
@RestController
@RequestMapping("/api/v1/internal/fds")
@RequiredArgsConstructor
public class InternalPreCheckController {

    private final PreCheckService preCheckService;

    @PostMapping("/precheck")
    public ResponseEntity<PreCheckResponse> precheck(@Valid @RequestBody PreCheckRequest request) {
        return ResponseEntity.ok(preCheckService.evaluate(request));
    }
}
