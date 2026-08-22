package com.bank.payment;

import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.payment.domain.service.PaymentCommand;
import com.bank.payment.domain.service.PaymentOrchestrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실행할 수 없는 예약은 접수하지 않는다.
 *
 * <p><b>왜 필요한가.</b> 예약 실행 워커는 {@code executeScheduledIntraBank} 하나만
 * 부르고, 그 메서드는 타행이면 예외를 던진다("예약이체 타행/BOK 미구현 — 후속 단계").
 * 그런데 <b>등록은 은행을 가리지 않았다.</b> 그래서 타행 예약은 이렇게 끝났다.
 *
 * <ol>
 *   <li>SCHEDULED 로 등록된다 — 고객에게는 접수됐다고 나간다</li>
 *   <li>실행 시각에 워커가 claim 한다 (SCHEDULED &rarr; PROCESSING, 독립 트랜잭션이라 커밋된다)</li>
 *   <li>실행이 예외로 죽고, 워커는 로그만 남기고 다음 건으로 넘어간다</li>
 * </ol>
 *
 * <p>남는 것은 <b>PROCESSING 에 갇힌 지시</b>다. 실행되지도, 실패로 닫히지도 않는다.
 * 고객은 접수됐다고 들었고 돈은 움직이지 않으며, 종료 상태가 아니라서 실패 지표에도
 * 잡히지 않는다.
 *
 * <p>이 경로로 들어오는 길이 둘이었다 — 사용자가 건 예약이체와, 이상거래 점검이
 * 지시한 지연이다. 뒤쪽이 특히 나빴다. 위험하다고 판정된 거래가 조용히 갇혔다.
 *
 * <p><b>보지 않는 것.</b> 타행 예약을 어떻게 구현할지는 보지 않는다. 여기서 막는
 * 것은 <b>실행할 수 없는 것을 접수하는</b> 한 종류다. 실행이 생기면 이 검사를
 * 뒤집으면 된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ScheduledExternalRefusedTest {

    @Autowired
    private PaymentOrchestrator orchestrator;

    /** A은행 자행 코드. 이 값이 아니면 타행이다. */
    private static final String INTRA_BANK = "004";
    private static final String OTHER_BANK = "088";

    @Test
    @DisplayName("타행 예약 등록은 거절된다 — 실행할 수 없는 것을 접수하지 않는다")
    void externalScheduleIsRefused() {
        assertThatThrownBy(() -> orchestrator.registerScheduledPayment(
                command(OTHER_BANK), OffsetDateTime.now().plusMinutes(30)))
                .as("등록을 받아 두면 실행 시각에 claim 만 되고 PROCESSING 에 갇힌다. "
                    + "고객은 접수됐다고 듣고, 돈은 움직이지 않고, 종료 상태가 아니라 "
                    + "실패 지표에도 안 잡힌다")
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.SCHEDULED_TRANSFER_INTRABANK_ONLY);
    }

    @Test
    @DisplayName("미룰 수 있는지 묻는 답이 자행 판별과 같다")
    void delayCapabilityMatchesIntraBank() {
        assertThat(orchestrator.supportsDelayedExecution(INTRA_BANK))
                .as("자행은 예약 실행이 있다")
                .isTrue();
        assertThat(orchestrator.supportsDelayedExecution(OTHER_BANK))
                .as("타행은 없다. 호출부가 이 답을 보고 지연 대신 확인을 요구한다")
                .isFalse();
    }

    private PaymentCommand command(String receiverBankCode) {
        return new PaymentCommand(
                "001-2002-0000001", receiverBankCode, "110-999-0001", "테스트",
                1_000L, null, null, "WEB", null,
                "9111", null, "IDEM-" + System.nanoTime());
    }
}
