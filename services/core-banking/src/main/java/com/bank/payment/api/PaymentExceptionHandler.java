package com.bank.payment.api;

import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorResponse;
import com.bank.deposit.exception.RiskGuidedException;
import com.bank.payment.common.exception.PaymentCancelConflictException;
import com.bank.payment.common.exception.PaymentNotFoundException;
import com.bank.payment.common.exception.PaymentUnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 취소 엔드포인트 예외 매핑.
 * PaymentNotFoundException(404), PaymentUnauthorizedException(403), PaymentCancelConflictException(409).
 */
/**
 * payment 도메인 전용 예외 처리.
 *
 * <p>병합 전에는 서비스마다 GlobalExceptionHandler 하나씩이라 이름이 같아도 무방했다.
 * 한 프로세스에 둘이 들어오면서 빈 이름이 충돌했고, 그보다 중요한 문제로
 * 수신계의 Exception 포괄 핸들러가 결제계의 미처리 예외까지 가로채 응답 형식을
 * 바꿔버린다. 그래서 각자 자기 패키지의 컨트롤러에만 적용되도록 범위를 가둔다.
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.bank.payment")
public class PaymentExceptionHandler {

    /**
     * 이상거래로 세운 거래 — 근거와 행동요령을 함께 내보낸다.
     *
     * <p>수신계 핸들러와 <b>같은 응답 형태</b>를 쓴다. 화면은 자행/타행을 가리지 않고
     * 같은 코드로 안내를 꺼내기 때문에, 여기서 모양이 달라지면 타행 이체만 안내가
     * 안 뜬다.
     */
    @ExceptionHandler(RiskGuidedException.class)
    public ResponseEntity<ErrorResponse> handleRiskGuided(RiskGuidedException e) {
        log.warn("이상거래로 거래 중단: {} guidance={}", e.getMessage(), e.getGuidance() != null);
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(ErrorResponse.of(e.getErrorCode(), e.getMessage(), e.getGuidance()));
    }

    /**
     * 업무 예외 — <b>500 이 아니라 제 상태 코드로</b> 내보낸다.
     *
     * <p>이 핸들러가 없어서 결제 컨트롤러가 던지는 업무 예외가 전부 500 이었다.
     * 두 게이트가 같은 예외를 쓰는데(둘 다 {@code com.bank.deposit.exception}),
     * 이 어드바이스는 {@code com.bank.payment} 로 범위가 갇혀 있고 예외 셋만
     * 다뤘다. 수신계 핸들러는 {@code com.bank.deposit} 컨트롤러에만 붙으니
     * 아무도 잡지 않았다.
     *
     * <p>겉으로 드러난 증상이 컸다. 타행 이체에서 <b>승인 토큰이 없거나 틀리면
     * 401 이 아니라 500</b> 이 나갔고, 이상거래로 막혀도 403 대신 500 이었다.
     * 화면은 "서버 오류" 로 읽어 재시도를 권했다 — 막아야 할 거래에 대고.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        log.warn("BusinessException(payment): {}", e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(ErrorResponse.of(e.getErrorCode(), e.getMessage()));
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(PaymentNotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(PaymentUnauthorizedException.class)
    public ResponseEntity<Map<String, String>> handleUnauthorized(PaymentUnauthorizedException e) {
        return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(PaymentCancelConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(PaymentCancelConflictException e) {
        return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
    }
}
