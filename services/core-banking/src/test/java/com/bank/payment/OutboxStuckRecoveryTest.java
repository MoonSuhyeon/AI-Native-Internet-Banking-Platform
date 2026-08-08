package com.bank.payment;

import com.bank.payment.domain.mapper.OutboxMessageMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 멈춘 아웃박스 메시지 복구.
 *
 * <p><b>왜 필요한가.</b> 매퍼 인터페이스는 {@code OffsetDateTime} 을 받는데 XML 의
 * {@code parameterType} 이 {@code LocalDateTime} 으로 남아 있었다. MyBatis 가 그 선언을
 * 보고 타입 핸들러를 고르기 때문에 실행 즉시 ClassCastException 이 났고,
 * 이 UPDATE 는 <b>매 주기 실패</b>했다.
 *
 * <p>그러면 {@code PUBLISHING} 에 갇힌 메시지를 아무도 되살리지 못한다. 발행 도중
 * 프로세스가 죽어 멈춘 이벤트가 영영 나가지 않는다 — 결제 완료 통지나 이상거래
 * 지연 알림이 조용히 유실되는 경로다.
 *
 * <p><b>컴파일로는 잡히지 않는다.</b> 인터페이스와 XML 은 따로 컴파일되고, 둘의
 * 어긋남은 그 구문을 실제로 실행해야 드러난다. 스케줄러 안에서 터지므로 요청은
 * 멀쩡하고 로그만 조용히 쌓인다.
 */
class OutboxStuckRecoveryTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private OutboxMessageMapper outboxMessageMapper;

    @Test
    @DisplayName("복구 쿼리가 실제로 실행된다 — 타입이 어긋나면 여기서 걸린다")
    void resetStuckPublishingExecutes() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(5);

        // 대상 행이 없어도 상관없다. 여기서 보는 것은 "몇 건을 되살렸는가" 가 아니라
        // 파라미터 타입이 맞아 구문이 돌기는 하는가다.
        assertThatCode(() -> outboxMessageMapper.resetStuckPublishing(cutoff))
                .as("매퍼 인터페이스와 XML 의 parameterType 이 어긋나면 "
                        + "ClassCastException 으로 실패한다")
                .doesNotThrowAnyException();
    }
}
