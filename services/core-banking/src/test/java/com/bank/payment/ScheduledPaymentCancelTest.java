package com.bank.payment;

import com.bank.payment.domain.PaymentInstruction;
import com.bank.payment.domain.mapper.PaymentInstructionMapper;
import com.bank.payment.domain.service.PaymentTransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 예약취소 통합테스트.
 * SCHEDULED→CANCELED 전이, 권한(403), 상태충돌(409), 이중취소(409), 레이스(claim vs cancel) 검증.
 */
class ScheduledPaymentCancelTest extends AbstractPaymentIntegrationTest {

    @Autowired private PaymentTransactionService txService;
    @Autowired private PaymentInstructionMapper paymentInstructionMapper;

    private static final String BANK_CODE_A = "004";
    private static final String SENDER_S1   = "12345678901234";
    private static final String RECEIVER_S1 = "12345678905678";

    // ── 헬퍼 ──────────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder postScheduledPayment(
            String idKey, String userId, String authId, LocalDateTime scheduledAt) throws Exception {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("senderAccountId",               SENDER_S1);
        body.put("receiverBankCode",              BANK_CODE_A);
        body.put("receiverAccountNo",             RECEIVER_S1);
        body.put("receiverHolderName",            "성춘향");
        body.put("transferAmount",                BigDecimal.valueOf(100_000L));
        body.put("receiverMemo",                  "예약이체");
        body.put("senderMemo",                    "예약송금");
        body.put("channel",                       "MOBILE");
        body.put("receiverPassbookSenderDisplay", "이몽룡");
        body.put("scheduledExecutionAt",          scheduledAt);

        return post("/api/v1/payments/scheduled")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Key", idKey)
                .header("X-User-Id",         userId)
                .header("X-Auth-Token-Id",   authId)
                .content(om.writeValueAsString(body));
    }

    private String registerScheduled(String idKey, String userId, String authId) throws Exception {
        LocalDateTime futureTime = LocalDateTime.now().plusHours(2);
        MvcResult result = mockMvc.perform(postScheduledPayment(idKey, userId, authId, futureTime))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString())
                .get("paymentInstructionId").asText();
    }

    private MockHttpServletRequestBuilder postCancel(String piId, String userId) throws Exception {
        return post("/api/v1/payments/scheduled/" + piId + "/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", userId);
    }

    private MockHttpServletRequestBuilder postCancelWithReason(
            String piId, String userId, String reason) throws Exception {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);

        return post("/api/v1/payments/scheduled/" + piId + "/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", userId)
                .content(om.writeValueAsString(body));
    }

    // ── 테스트 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cancel_scheduled_success — 본인 취소 → CANCELED, SCHEDULED_CANCELED 이력 1건(USER), version+1, ledger/outbox 0건")
    void cancel_scheduled_success() throws Exception {
        String piId = registerScheduled("CANCEL-S1-001-1", "USER-C001", "AUTH-C001");

        int versionBefore = jdbc.queryForObject(
                "SELECT version FROM payment_instruction WHERE payment_instruction_id = ?",
                Integer.class, piId);

        mockMvc.perform(postCancelWithReason(piId, "USER-C001", "단순변심"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        // payment_instruction 검증
        Map<String, Object> pi = jdbc.queryForMap(
                "SELECT status, version FROM payment_instruction WHERE payment_instruction_id = ?", piId);
        assertThat(pi.get("status")).isEqualTo("CANCELED");
        assertThat(((Number) pi.get("version")).intValue()).isEqualTo(versionBefore + 1);

        // SCHEDULED_CANCELED 이력 1건, triggered_by=USER
        int cancelHistCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'SCHEDULED_CANCELED' " +
                "AND triggered_by = 'USER'",
                Integer.class, piId);
        assertThat(cancelHistCount).isEqualTo(1);

        // previous=SCHEDULED, next=CANCELED, reason_message 박제
        Map<String, Object> hist = jdbc.queryForMap(
                "SELECT previous_status, next_status, reason_message FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'SCHEDULED_CANCELED'", piId);
        assertThat(hist.get("previous_status")).isEqualTo("SCHEDULED");
        assertThat(hist.get("next_status")).isEqualTo("CANCELED");
        assertThat(hist.get("reason_message")).isEqualTo("단순변심");

        // ledger 0건, outbox 0건 (자금변동 없음)
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger WHERE payment_instruction_id = ?",
                Integer.class, piId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE payment_instruction_id = ?",
                Integer.class, piId)).isZero();
    }

    @Test
    @DisplayName("cancel_notOwner_forbidden — 다른 사용자 취소 시도 → 403, status 여전히 SCHEDULED")
    void cancel_notOwner_forbidden() throws Exception {
        String piId = registerScheduled("CANCEL-F1-001-1", "USER-C002", "AUTH-C002");

        mockMvc.perform(postCancel(piId, "USER-INTRUDER"))
                .andExpect(status().isForbidden());

        // 상태 변경 없음
        String dbStatus = jdbc.queryForObject(
                "SELECT status FROM payment_instruction WHERE payment_instruction_id = ?",
                String.class, piId);
        assertThat(dbStatus).isEqualTo("SCHEDULED");

        // 이력에 SCHEDULED_CANCELED 없음
        int cancelCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'SCHEDULED_CANCELED'",
                Integer.class, piId);
        assertThat(cancelCount).isZero();
    }

    @Test
    @DisplayName("cancel_alreadyProcessing_conflict ★레이스 — claim 후 취소 시도 → 409, status=PROCESSING 유지")
    void cancel_alreadyProcessing_conflict() throws Exception {
        String piId = registerScheduled("CANCEL-F2-001-1", "USER-C003", "AUTH-C003");

        // scheduled_execution_at 을 과거로 변경 → selectDueScheduled 폴링 대상
        jdbc.update("UPDATE payment_instruction SET scheduled_execution_at = ? " +
                    "WHERE payment_instruction_id = ?",
                LocalDateTime.now().minusMinutes(1), piId);

        // 워커 대신 직접 claim
        List<PaymentInstruction> due = paymentInstructionMapper.selectDueScheduled();
        PaymentInstruction pi = due.stream()
                .filter(p -> piId.equals(p.getPaymentInstructionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("selectDueScheduled 에 piId 없음: " + piId));
        boolean claimed = txService.claimScheduled(pi);
        assertThat(claimed).isTrue();

        // 취소 시도 → 409 (이미 PROCESSING)
        mockMvc.perform(postCancel(piId, "USER-C003"))
                .andExpect(status().isConflict());

        // status=PROCESSING 유지
        String dbStatus = jdbc.queryForObject(
                "SELECT status FROM payment_instruction WHERE payment_instruction_id = ?",
                String.class, piId);
        assertThat(dbStatus).isEqualTo("PROCESSING");

        // SCHEDULED_CANCELED 이력 없음
        int cancelCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'SCHEDULED_CANCELED'",
                Integer.class, piId);
        assertThat(cancelCount).isZero();
    }

    @Test
    @DisplayName("cancel_doubleCancel_conflict — 취소 성공 후 재취소 → 409, SCHEDULED_CANCELED 이력 1건만")
    void cancel_doubleCancel_conflict() throws Exception {
        String piId = registerScheduled("CANCEL-F3-001-1", "USER-C004", "AUTH-C004");

        // 1회 취소 성공
        mockMvc.perform(postCancel(piId, "USER-C004"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        // 2회차 취소 → 409
        mockMvc.perform(postCancel(piId, "USER-C004"))
                .andExpect(status().isConflict());

        // SCHEDULED_CANCELED 이력 정확히 1건
        int cancelCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'SCHEDULED_CANCELED'",
                Integer.class, piId);
        assertThat(cancelCount).isEqualTo(1);
    }

    @Test
    @DisplayName("cancel_then_workerSkips — 취소 후 selectDueScheduled 에 미포함, claimScheduled affected=0")
    void cancel_then_workerSkips() throws Exception {
        String piId = registerScheduled("CANCEL-F4-001-1", "USER-C005", "AUTH-C005");

        // scheduled_execution_at 을 과거로 변경 (워커가 집을 수 있는 조건)
        jdbc.update("UPDATE payment_instruction SET scheduled_execution_at = ? " +
                    "WHERE payment_instruction_id = ?",
                LocalDateTime.now().minusMinutes(1), piId);

        // 취소
        mockMvc.perform(postCancel(piId, "USER-C005"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        // selectDueScheduled 결과에 piId 없음 (status=CANCELED → WHERE status='SCHEDULED' 불일치)
        List<PaymentInstruction> due = paymentInstructionMapper.selectDueScheduled();
        boolean inDue = due.stream().anyMatch(p -> piId.equals(p.getPaymentInstructionId()));
        assertThat(inDue).isFalse();

        // claimScheduledForExecution 직접 호출 → affected=0
        int affected = paymentInstructionMapper.cancelScheduledForUser(piId, 999);
        assertThat(affected).isZero();
    }
}
