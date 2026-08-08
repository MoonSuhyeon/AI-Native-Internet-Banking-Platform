package com.bank.customer.notification;

import com.bank.common.web.ApiResponse;
import com.bank.customer.notification.domain.CustomerNotification;
import com.bank.customer.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 고객 알림함.
 *
 * <p>이상거래로 지연된 이체처럼 <b>고객이 손쓸 시간이 있는</b> 알림이 여기 쌓인다.
 * 화면은 {@code actionDueAt} 이 지났는지 보고 취소 버튼을 감춰야 한다 —
 * 이미 실행된 거래에 취소 버튼을 남겨 두면 고객이 눌러 보고 실패를 겪는다.
 */
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    public record NotificationResponse(
            Long notificationId,
            String type,
            String title,
            String body,
            String referenceId,
            OffsetDateTime actionDueAt,
            boolean read,
            OffsetDateTime createdAt
    ) {
        static NotificationResponse from(CustomerNotification n) {
            return new NotificationResponse(
                    n.getNotificationId(), n.getNotificationType(), n.getTitle(), n.getBody(),
                    n.getReferenceId(), n.getActionDueAt(), n.isRead(), n.getCreatedAt());
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> list(
            @RequestHeader("X-Customer-Id") Long customerId) {

        List<NotificationResponse> body = notificationService.list(customerId).stream()
                .map(NotificationResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(body));
    }

    @PostMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<Void>> markRead(
            @RequestHeader("X-Customer-Id") Long customerId,
            @PathVariable Long notificationId) {

        notificationService.markRead(customerId, notificationId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
