package com.bank.payment.api;

import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.exception.ErrorResponse;
import com.bank.deposit.exception.RiskGuidedException;
import com.bank.deposit.security.RiskGuidance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제 경로의 업무 예외가 500 이 아니라 제 상태 코드로 나가는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 두 게이트({@code TransferApprovalGate}·{@code FdsPreCheckGate})는
 * {@code com.bank.deposit.exception.BusinessException} 을 던진다. 그런데 예외를
 * 매핑하는 어드바이스는 패키지로 범위가 갇혀 있다 — 수신계 핸들러는
 * {@code com.bank.deposit} 컨트롤러에만 붙고, 결제계 핸들러는 자기 예외 셋만
 * 다뤘다. 그래서 <b>결제 컨트롤러가 던진 업무 예외를 아무도 잡지 않았다.</b>
 *
 * <p>실제로 이렇게 나왔다.
 *
 * <pre>
 *   타행 고액 이체 → 500
 *   {"status":500,"error":"Internal Server Error","path":"/api/v1/payments"}
 * </pre>
 *
 * <p>승인 토큰이 없거나 틀려도, 이상거래로 막혀도 똑같이 500 이었다. 화면은
 * 그것을 "서버 오류" 로 읽어 <b>재시도를 권한다</b> — 막아야 할 거래에 대고.
 * 그리고 이상거래 안내는 응답에 실려도 화면까지 가지 못했다.
 *
 * <p><b>왜 슬라이스가 아니라 직접 부르는가.</b> 패키지 범위가 맞는지는
 * 어드바이스 클래스 자체가 그 예외를 다루는지로 결정된다. 컨텍스트를 띄우면
 * 느리기만 하고, 정작 "이 어드바이스에 핸들러가 있는가" 는 그대로 드러난다.
 */
class PaymentBusinessExceptionMappingTest {

    private final PaymentExceptionHandler handler = new PaymentExceptionHandler();

    @Test
    @DisplayName("승인 인증 실패는 500 이 아니라 401 로 나간다")
    void approvalFailureKeepsItsStatus() {
        ResponseEntity<ErrorResponse> res =
                handler.handleBusiness(new BusinessException(ErrorCode.TRANSFER_APPROVAL_REQUIRED));

        assertThat(res.getStatusCode().value())
                .as("결제 경로의 업무 예외를 아무도 잡지 않으면 500 이 되고, 화면은 "
                    + "'서버 오류' 로 읽어 재시도를 권한다 — 인증이 안 된 이체에 대고")
                .isEqualTo(401);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().code()).isEqualTo("TRANSFER_APPROVAL_REQUIRED");
    }

    @Test
    @DisplayName("이상거래 차단은 403 으로, 근거와 행동요령을 실어 나간다")
    void riskBlockCarriesGuidance() {
        RiskGuidance guidance = new RiskGuidance(
                "최근 피해 사례와 비슷한 패턴이라 이체를 멈췄습니다",
                List.of("고액 이체 25000000원"),
                List.of("보내기 전에 받는 분에게 직접 전화해 확인하세요."),
                List.of("CANCEL", "CONSULT"));

        ResponseEntity<ErrorResponse> res =
                handler.handleRiskGuided(new RiskGuidedException(ErrorCode.TRANSFER_BLOCKED_BY_RISK, guidance));

        assertThat(res.getStatusCode().value()).isEqualTo(403);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().guidance())
                .as("안내가 빠지면 화면은 이 작업 이전과 똑같이 '고객센터로 문의' 한 "
                    + "줄만 띄운다. 자행은 되고 타행만 안 되는 상태가 특히 나쁘다 — "
                    + "고쳤다고 믿게 되기 때문이다")
                .isEqualTo(guidance);
    }

    @Test
    @DisplayName("응답 형태가 수신계와 같다 — 화면이 자행·타행을 가리지 않는다")
    void shapeMatchesDepositPath() {
        RiskGuidance guidance = new RiskGuidance("h", List.of("e"), List.of("s"), List.of("CANCEL"));

        ErrorResponse fromPayment =
                handler.handleRiskGuided(new RiskGuidedException(ErrorCode.TRANSFER_BLOCKED_BY_RISK, guidance)).getBody();
        ErrorResponse fromDeposit =
                ErrorResponse.of(ErrorCode.TRANSFER_BLOCKED_BY_RISK, ErrorCode.TRANSFER_BLOCKED_BY_RISK.getMessage(), guidance);

        assertThat(fromPayment).isNotNull();
        assertThat(fromPayment.code()).isEqualTo(fromDeposit.code());
        assertThat(fromPayment.guidance()).isEqualTo(fromDeposit.guidance());
    }
}
