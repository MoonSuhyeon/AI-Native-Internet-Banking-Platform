package com.bank.customer.banking;

import com.bank.common.web.BusinessException;
import com.bank.common.web.CommonErrorCode;
import com.bank.customer.banking.dto.TransferLimitResponse;
import com.bank.customer.banking.service.TransferLimitService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서비스 간 조회용 고객 이체한도.
 *
 * <p><b>왜 본인용 GET 으로는 부족한가.</b> {@code /api/v1/customers/me/transfer-limit}
 * 은 {@code X-Customer-Id} 헤더의 본인 기준이다. 결제계가 이체를 처리할 때는 "그 고객"
 * 의 한도를 읽어야 하는데, 그 요청의 주체는 결제 서비스이지 고객이 아니다.
 *
 * <p>경로가 {@code /api/internal/...}(v1 없음)인 것은 레포 규약이다 — 서비스 간
 * 호출이라 게이트웨이 라우트를 두지 않고 망·호출자 헤더로 보호한다.
 * ({@code /api/v1/internal/...} 은 직원 화면이 쓰는 쪽이라 게이트웨이를 거친다.)
 *
 * <p><b>여기서 하지 않는 것.</b> 당일 누적 집계는 결제계가 권위 소스다. 이체 이력이
 * 그쪽에 있기 때문이다. 이 API 는 <b>한도 값</b>만 알려주고, 그 값을 넘겼는지 판단하고
 * 차단하는 것은 결제계가 한다.
 */
@RestController
@RequestMapping("/api/internal/customers")
@RequiredArgsConstructor
public class InternalTransferLimitController {

    private final TransferLimitService transferLimitService;

    /**
     * 고객의 인터넷뱅킹 이체한도(1일/1회).
     *
     * <p>행이 없으면 기본값을 돌려준다. 여기서 404 를 주면 결제계가 "한도 없음" 으로
     * 읽어 무제한 통과시킬 위험이 있다 — 한도를 한 번도 설정하지 않은 고객이 대부분인데,
     * 그들이 오히려 제한 없이 이체하게 된다.
     */
    @GetMapping("/{customerId}/transfer-limit")
    public ResponseEntity<TransferLimitResponse> getTransferLimit(
            @PathVariable Long customerId,
            @RequestHeader(name = "X-Caller-Service", required = false) String callerService) {

        if (callerService == null || callerService.isBlank()) {
            throw new BusinessException(CommonErrorCode.COMMON_403);
        }
        return ResponseEntity.ok(transferLimitService.getLimit(customerId));
    }
}
