package com.bank.ai.review.controller;

import com.bank.ai.review.dto.AutoReviewEvaluateResponse;
import com.bank.ai.review.dto.AutoReviewRequest;
import com.bank.ai.review.dto.AutoReviewResponse;
import com.bank.ai.review.service.AutoReviewService;
import com.bank.ai.rule.service.RuleEngineService;
import com.bank.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 자동 대출심사 추론 API.
 *
 * <p><b>loan-service 만 부른다.</b> 그런데 오랫동안 아무 검사가 없었다. 같은 모듈의
 * {@code EmbeddingBatchController} 는 {@code X-Internal-Token} 을 확인하는데 이쪽만
 * 빠져 있었고, 정작 부르는 쪽({@code AutoReviewEvaluateClient})은 그 헤더를 이미
 * 보내고 있었다 — 받는 쪽만 안 보고 있었다.
 *
 * <p>열려 있으면 두 가지가 문제다. 심사 결정 로직을 아무나 호출해 <b>어떤 조건이
 * 승인/반려로 갈리는지 탐색</b>할 수 있고, 호출마다 ML 추론과 LLM 이 돌아 비용과
 * 처리량이 소모된다.
 *
 * <p>토큰이 설정되지 않으면 모두 거부한다(fail-safe). 신원을 확인할 수 없는데
 * 통과시키면 검사가 있다는 착각만 남는다.
 */
@Slf4j
@Tag(name = "auto-review", description = "자동 대출심사 추론 + 트랙 분기")
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AutoReviewController {

    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    private final AutoReviewService autoReviewService;
    private final RuleEngineService ruleEngineService;

    @Value("${ai.internal-token:}")
    private String internalToken;

    /**
     * EmbeddingBatchController 와 같은 규약.
     *
     * <p>예외를 던지지 않고 응답을 직접 만든다. 전역 예외 핸들러가
     * {@code ResponseStatusException} 까지 잡아 500 으로 바꾸기 때문이다 —
     * 인가 거절이 서버 오류로 보이면 원인을 찾기 어렵고 알림도 엉뚱하게 뜬다.
     */
    private boolean tokenMismatch(String token) {
        if (internalToken.isBlank() || !internalToken.equals(token)) {
            log.warn("AutoReviewController: 내부 토큰 불일치 — 요청 거부");
            return true;
        }
        return false;
    }

    private boolean gatewayRoleForbidden(String userRoles) {
        // loan-service의 내부 호출은 X-User-Role 없이 내부 토큰으로만 인증된다.
        // 반대로 gateway가 역할을 붙인 브라우저 호출은 관리자 역할을 반드시 가져야 한다.
        return userRoles != null && !userRoles.isBlank()
                && java.util.Arrays.stream(userRoles.split(","))
                .map(String::trim)
                .noneMatch(ADMIN_ROLE::equals);
    }

    @Operation(summary = "자동심사 ML 추론 단건",
            description = "신청자 피처를 받아 APPROVE/REJECT 결정과 확률만 반환. "
                    + "트랙 분기·hard constraint 까지 포함한 종합 결과는 /evaluate 사용.")
    @PostMapping("/auto-review")
    public ResponseEntity<ApiResponse<AutoReviewResponse>> review(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-User-Role", required = false) String userRoles,
            @Valid @RequestBody AutoReviewRequest req) {
        if (tokenMismatch(token) || gatewayRoleForbidden(userRoles)) {
            return ResponseEntity.status(tokenMismatch(token) ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(ApiResponse.ok(autoReviewService.review(req)));
    }

    @Operation(summary = "자동심사 종합 평가 (ML + RuleEngine)",
            description = "ML PD score + hard constraint + PolicyMatrix lookup 으로 "
                    + "Track 1(자동승인) / Track 2(자동반려) / Track 3(사람심사) 분기 결과 반환.")
    @PostMapping("/auto-review/evaluate")
    public ResponseEntity<ApiResponse<AutoReviewEvaluateResponse>> evaluate(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-User-Role", required = false) String userRoles,
            @Valid @RequestBody AutoReviewRequest req) {
        if (tokenMismatch(token) || gatewayRoleForbidden(userRoles)) {
            return ResponseEntity.status(tokenMismatch(token) ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(ApiResponse.ok(ruleEngineService.evaluate(req)));
    }
}
