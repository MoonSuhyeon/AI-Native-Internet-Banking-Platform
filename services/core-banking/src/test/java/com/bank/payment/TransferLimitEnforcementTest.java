package com.bank.payment;

import com.bank.deposit.client.dto.TransferLimitResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 고객당 인터넷뱅킹 이체한도가 <b>실제 이체 시점에</b> 적용되는지.
 *
 * <p><b>왜 단위 테스트로는 부족한가.</b> {@code TransferLimitPolicyTest} 는 판정 규칙이
 * 맞는지 본다. 그런데 이 기능에서 틀리기 쉬운 곳은 규칙이 아니라 <b>연결</b>이다 —
 * 고객 한도를 실제로 읽어 오는지, 당일 누적을 고객 단위로 세는지, 막았을 때 돈이
 * 움직이지 않았는지. 그게 어긋나면 규칙은 멀쩡히 돌면서 한도가 걸리지 않는다.
 *
 * <p>특히 <b>당일 누적</b>은 한 건짜리 테스트로는 절대 드러나지 않는다. 예전 구현이
 * 정확히 그래서 "1일 한도" 가 사실상 "1회 한도" 로 동작했다.
 */
class TransferLimitEnforcementTest extends AbstractPaymentIntegrationTest {

    /** 게이트웨이가 넣는 형식 — 숫자(customerId). */
    private static final String CUSTOMER_ID = "9111";

    private void givenCustomerLimit(long daily, long once) {
        Mockito.when(customerServiceClient.getTransferLimit(
                        ArgumentMatchers.anyLong(), ArgumentMatchers.anyString()))
                .thenReturn(new TransferLimitResponse(daily, once));
    }

    /**
     * 이체를 시도하고 결과 상태를 돌려준다.
     *
     * <p>이 API 는 검증 실패도 HTTP 200 으로 응답하고 결과를 본문 status 로 알린다.
     * 그래서 HTTP 코드로 성패를 보면 <b>막힌 것도 성공으로 읽힌다</b>.
     */
    private String transfer(long amount) throws Exception {
        String body = mockMvc.perform(postPayment(
                        "IDEM-" + UUID.randomUUID(),
                        CUSTOMER_ID,
                        "AUTH-" + UUID.randomUUID(),
                        SENDER_S1, "004", RECEIVER_S1, "김철수",
                        amount, "MOBILE"))
                .andReturn().getResponse().getContentAsString();
        return om.readTree(body).path("status").asText();
    }

    private String failureCategory() {
        return jdbc.queryForObject(
                "SELECT failure_category FROM payment.payment_instruction "
                        + "WHERE sender_user_id = ? AND status = 'FAILED' "
                        + "ORDER BY requested_at DESC LIMIT 1",
                String.class, CUSTOMER_ID);
    }

    private long completedCount() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment.payment_instruction "
                        + "WHERE sender_user_id = ? AND status = 'COMPLETED'",
                Long.class, CUSTOMER_ID);
        return n == null ? 0 : n;
    }

    // ── 1회 한도 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1회 한도를 넘으면 이체가 막히고 아무것도 남지 않는다")
    void onceLimitBlocksTransfer() throws Exception {
        givenCustomerLimit(10_000_000L, 500_000L);

        assertThat(transfer(600_000L)).isEqualTo("FAILED");
        assertThat(failureCategory()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(completedCount())
                .as("막힌 요청이 완료 건으로 남으면 안 된다")
                .isZero();
    }

    @Test
    @DisplayName("1회 한도와 같은 금액은 통과한다")
    void exactlyOnceLimitPasses() throws Exception {
        givenCustomerLimit(10_000_000L, 500_000L);

        assertThat(transfer(500_000L)).isEqualTo("COMPLETED");
    }

    // ── 1일 누적 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("당일 누적이 한도를 넘으면 막힌다 — 예전에는 이 누적이 없었다")
    void dailyCumulativeIsEnforced() throws Exception {
        // 1일 100만, 1회 60만. 60만을 두 번 보내면 120만이 되어 두 번째가 막혀야 한다.
        // 한 건만 보면 둘 다 1회 한도 이내라 통과한다 — 누적을 세지 않으면 그대로 나간다.
        givenCustomerLimit(1_000_000L, 600_000L);

        assertThat(transfer(600_000L)).as("첫 건은 통과").isEqualTo("COMPLETED");

        assertThat(transfer(600_000L))
                .as("두 번째는 누적 120만 > 한도 100만이라 막혀야 한다")
                .isEqualTo("FAILED");
        assertThat(failureCategory()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(completedCount()).as("성공한 것은 첫 건 하나뿐이어야 한다").isEqualTo(1);
    }

    @Test
    @DisplayName("누적이 한도에 딱 맞으면 통과한다")
    void cumulativeExactlyAtLimitPasses() throws Exception {
        givenCustomerLimit(1_000_000L, 600_000L);

        assertThat(transfer(600_000L)).isEqualTo("COMPLETED");
        assertThat(transfer(400_000L)).as("합계 100만 = 한도. 초과가 아니다")
                .isEqualTo("COMPLETED");
    }

    // ── 조회 실패 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("한도 조회가 실패하면 막는다 — 못 읽었다고 한도를 무시하면 약속을 깨는 것이다")
    void lookupFailureBlocks() throws Exception {
        Mockito.when(customerServiceClient.getTransferLimit(
                        ArgumentMatchers.anyLong(), ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("customer-service 응답 없음"));

        assertThat(transfer(10_000L)).isEqualTo("FAILED");
        assertThat(failureCategory()).isEqualTo("LIMIT_LOOKUP_FAILED");
        assertThat(completedCount()).isZero();
    }
}
