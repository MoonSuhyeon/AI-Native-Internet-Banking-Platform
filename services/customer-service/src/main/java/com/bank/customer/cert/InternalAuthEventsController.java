package com.bank.customer.cert;

import com.bank.common.web.ApiResponse;
import com.bank.customer.cert.dto.AuthEventsResponse;
import com.bank.customer.history.repository.CertificateUseRepository;
import com.bank.customer.history.repository.PasswordHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * 인증 이벤트 조회 — 직원 전용 읽기 전용 내부 API.
 * Fraud Investigation Agent 의 {@code get_auth_events} 도구가 호출해 계정탈취(H2) 신호로 쓴다.
 * {@code /api/v1/internal/**} 는 SecurityConfig 에서 직원 역할로 보호된다.
 */
@RestController
@RequestMapping("/api/v1/internal/auth")
@RequiredArgsConstructor
public class InternalAuthEventsController {

    private final CertificateUseRepository certificateUseRepository;
    private final PasswordHistoryRepository passwordHistoryRepository;

    /** 최근 windowHours 시간 내 인증서 실패 횟수 등 인증 이벤트 요약(읽기 전용). */
    @GetMapping("/{customerId}/events")
    public ResponseEntity<ApiResponse<AuthEventsResponse>> getAuthEvents(
            @PathVariable Long customerId,
            @RequestParam(defaultValue = "24") int windowHours) {

        OffsetDateTime since = OffsetDateTime.now().minusHours(windowHours);
        long fails = certificateUseRepository.countCertFailuresByCustomerSince(customerId, since);

        // 같은 창에서 비밀번호가 바뀌었는가. 계정탈취의 전형적인 순서가
        // "탈취 → 비밀번호 변경 → 이체" 라, 인증 실패와 함께 보면 신호가 훨씬 강해진다.
        //
        // 조사 에이전트가 계정탈취(H2) 가설의 근거로 쓴다. 늘 false 로 두면
        // 그 축의 가설이 오르지 않아, 조사가 틀리는 게 아니라 덜 아는 상태가 된다.
        boolean passwordChangedRecently =
                passwordHistoryRepository.countChangesSince(customerId, since) > 0;

        return ResponseEntity.ok(ApiResponse.ok(
                new AuthEventsResponse(customerId, windowHours, fails, passwordChangedRecently)));
    }
}
