package com.bank.payment.api;

import com.bank.payment.api.dto.PaymentDetailResponse;
import com.bank.payment.domain.PaymentInstruction;
import com.bank.payment.domain.service.PaymentTransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제지시 상세 조회 — 직원 범위 읽기 전용 내부 API.
 *
 * <p><b>누가 부르는가.</b> 이상거래 탐지기가 {@code payment.completed} 이벤트를 받고
 * 이 API 로 거래 내용을 보강한다. 조사 에이전트의 도구 계층도 같은 경로를 쓴다.
 *
 * <p><b>왜 이벤트에 다 싣지 않는가.</b> payload 확장은 결제 트랜잭션 경로를 건드린다.
 * 탐지 때문에 결제가 느려지거나 실패하면 안 되므로, 이벤트는 지금대로 두고
 * 필요한 쪽이 조회하게 한다. 탐지가 죽어도 결제는 그대로 돈다.
 *
 * <p>{@code /api/v1/internal/**} 는 customer-service 의 내부 API 와 같은 규약이다.
 * 게이트웨이가 직원 역할을 확인하고, 백엔드는 게이트웨이 뒤에 둔다.
 *
 * <p><b>쓰기 없음.</b> 조회만 둔다. 탐지·조사는 결제 상태를 바꾸지 않는다 —
 * 지급정지 같은 조치는 권고까지만 하고 사람이 실행한다(HITL).
 */
@RestController
// context-path 가 이미 /api 다. 여기에 또 /api 를 쓰면 외부 URL 이
// /api/api/... 가 되어 호출부가 404 를 받는다. 결제 컨트롤러가 /v1/payments 로
// 두는 것과 같은 이유다.
@RequestMapping("/v1/internal/payments")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final PaymentTransactionService paymentTransactionService;

    /**
     * 결제지시 한 건의 상세.
     *
     * @return 없으면 404. 탐지기는 이 경우 보강 없이 금액 룰만 적용하고 넘어간다 —
     *         한 건을 못 읽었다고 스트림 처리를 멈추지 않는다.
     */
    @GetMapping("/{paymentInstructionId}")
    public ResponseEntity<PaymentDetailResponse> getPaymentDetail(
            @PathVariable String paymentInstructionId) {

        PaymentInstruction pi = paymentTransactionService.selectById(paymentInstructionId);
        if (pi == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(PaymentDetailResponse.from(pi));
    }
}
