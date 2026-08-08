package com.bank.payment;

import com.bank.payment.domain.service.PaymentTransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 코드가 쓰는 값이 DB CHECK 제약에 실제로 있는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 지연 기능 하나를 붙이면서 같은 실수를 <b>세 번</b> 했다.
 * {@code payment_instruction.trigger_source}, {@code status_history.triggered_by},
 * {@code outbox_message.event_type} — 코드에 새 값을 추가하면서 제약을 넓히지 않았다.
 *
 * <p>세 번 다 같은 이유로 안 잡혔다. 단위 테스트는 매퍼를 목으로 대체하므로 DB 제약까지
 * 가지 않고, 상수를 비교하는 테스트는 값이 서로 다른지만 알 뿐 DB 가 받아 주는지는
 * 모른다. 그 경로를 실제로 태워 봐야 드러나는데, 지연 판정은 신호 두 개가 겹쳐야
 * 나오므로 평소 테스트에서는 거의 지나가지 않는다.
 *
 * <p>제약 정의를 직접 읽어 대조한다. 행을 넣어 보는 방식은 FK 등 다른 제약에 먼저
 * 걸려 무엇 때문에 실패했는지 흐려진다.
 */
class TriggerSourceConstraintTest extends AbstractPaymentIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String constraintDef(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
                String.class, name);
    }

    @Test
    @DisplayName("trigger_source: 코드가 쓰는 값이 제약에 모두 있다")
    void triggerSourceValuesAreAllowed() {
        String def = constraintDef("chk_payment_instruction_trigger_source");

        assertThat(def)
                .as("제약이 없으면 어떤 값이든 들어가 오타가 조용히 쌓인다")
                .isNotNull();
        assertThat(def)
                .as("코드가 쓰는 값이 빠지면 그 경로를 탈 때만 500 이 난다")
                .contains(PaymentTransactionService.TRIGGER_USER)
                .contains(PaymentTransactionService.TRIGGER_FDS_DELAY);
    }

    @Test
    @DisplayName("outbox event_type: 발행하는 이벤트가 제약에 모두 있다")
    void outboxEventTypesAreAllowed() {
        String def = constraintDef("chk_outbox_message_event_type");

        assertThat(def).isNotNull();
        // 결제 흐름이 실제로 내는 이벤트들. 하나라도 빠지면 그 흐름이 통째로 막힌다.
        assertThat(def)
                .contains("PAYMENT_COMPLETED")
                .contains("PAYMENT_FAILED")
                .contains("PAYMENT_REVERSED")
                .as("지연 알림 이벤트. 빠지면 지연 판정이 나온 이체가 실패한다")
                .contains("PAYMENT_DELAYED");
    }

    @Test
    @DisplayName("status_history triggered_by: 이력에 쓰는 행위자가 제약에 있다")
    void triggeredByValuesAreAllowed() {
        String def = constraintDef("chk_status_history_triggered_by");

        assertThat(def).isNotNull();
        // 지연 건도 이력에는 USER 로 남는다 — 전이를 일으킨 것은 고객의 이체 요청이고,
        // 지연 여부는 PI 의 trigger_source 가 들고 있다. 여기에 FDS_DELAY 를 넣었다가
        // 제약 위반으로 이체가 실패했었다.
        assertThat(def)
                .contains(PaymentTransactionService.TRIGGER_USER)
                .contains("SYSTEM")
                .contains("KFTC");
    }
}
