package com.bank.customer.notification;

import com.bank.common.web.ApiResponse;
import com.bank.customer.notification.domain.AlertChannel;
import com.bank.customer.notification.domain.DepositAlertSubscription;
import com.bank.customer.notification.service.DepositAlertService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 입금통보 신청.
 *
 * <p><b>여기까지가 신청이다.</b> 실제 발송은 코어뱅킹이 입금 이벤트를 내보내야
 * 시작되는데 그 이벤트가 아직 없다(docs/OPEN_ITEMS.md). 신청을 먼저 만든 이유는,
 * 발송 경로가 생겼을 때 "누구에게" 를 물어볼 곳이 필요해서다.
 */
@RestController
@RequestMapping("/api/v1/notifications/deposit-alerts")
@RequiredArgsConstructor
public class DepositAlertController {

    private final DepositAlertService depositAlertService;

    public record SubscribeRequest(
            @NotBlank(message = "계좌번호가 필요합니다.")
            String accountNumber,

            @NotNull(message = "통보받을 방법을 선택해주세요.")
            AlertChannel channel,

            String contact,

            @PositiveOrZero(message = "통보 기준금액은 0원 이상이어야 합니다.")
            long minAmount
    ) {
    }

    public record SubscriptionResponse(
            Long subscriptionId,
            String accountNumber,
            AlertChannel channel,
            String contact,
            long minAmount,
            boolean active
    ) {
        static SubscriptionResponse from(DepositAlertSubscription s) {
            return new SubscriptionResponse(
                    s.getSubscriptionId(), s.getAccountNumber(), s.getChannel(),
                    s.getContact(), s.getMinAmount(), s.isActive());
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<SubscriptionResponse>>> list(
            @RequestHeader("X-Customer-Id") Long customerId) {

        List<SubscriptionResponse> body = depositAlertService.list(customerId).stream()
                .map(SubscriptionResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SubscriptionResponse>> subscribe(
            @RequestHeader("X-Customer-Id") Long customerId,
            @Valid @RequestBody SubscribeRequest request) {

        DepositAlertSubscription saved = depositAlertService.subscribe(
                customerId, request.accountNumber(), request.channel(),
                request.contact(), request.minAmount());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(SubscriptionResponse.from(saved)));
    }

    @DeleteMapping("/{subscriptionId}")
    public ResponseEntity<ApiResponse<Void>> unsubscribe(
            @RequestHeader("X-Customer-Id") Long customerId,
            @PathVariable Long subscriptionId) {

        depositAlertService.unsubscribe(customerId, subscriptionId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
