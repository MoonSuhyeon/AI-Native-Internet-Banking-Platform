package com.bank.payment;

import com.bank.payment.domain.PaymentInstruction;
import com.bank.payment.domain.mapper.PaymentInstructionMapper;
import com.bank.payment.domain.service.PaymentOrchestrator;
import com.bank.payment.domain.service.PaymentTransactionService;
import com.bank.payment.outbound.feign.mock.DepositAccountClientMock;
import com.bank.payment.scheduler.ScheduledPaymentWorker;
import org.junit.jupiter.api.AfterEach;
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
 * 예약이체 등록 통합테스트 (등록경로 DRAFT→AUTHORIZED→SCHEDULED).
 * 워커/실행/PROCESSING 전이/취소/Outbox/ledger 검증 없음 — 이 PR 범위 외.
 *
 * mock 프로파일 트리거 계좌:
 *   SENDER_S1       "12345678901234" — 정상 계좌, 잔액 20억
 *   RECEIVER_S1     "12345678905678" — 정상 계좌
 *   RECEIVER_CLOSED "99990000000003" — accountStatus=CLOSED → ACCOUNT_CLOSED
 */
class ScheduledPaymentTest extends AbstractPaymentIntegrationTest {

    @Autowired private PaymentTransactionService txService;
    @Autowired private PaymentInstructionMapper paymentInstructionMapper;
    @Autowired private PaymentOrchestrator orchestrator;
    @Autowired private ScheduledPaymentWorker scheduledPaymentWorker;
    @Autowired private DepositAccountClientMock accountClientMock;

    private static final String BANK_CODE_A      = "004";
    private static final String SENDER_S1        = "12345678901234";
    private static final String SENDER_F1        = "77770000000001";  // 잔액 500만 — 600만 이체 시 INSUFFICIENT_BALANCE
    private static final String RECEIVER_S1      = "12345678905678";
    private static final String RECEIVER_CLOSED  = "99990000000003";
    private static final String RECEIVER_F8      = "12345678909999";  // B-4 DEP-9001 → DepositInboundFailureException
    private static final String RECEIVER_F5      = "88880000";        // 분개 INSERT 강제 실패 → LedgerInsertFailureException

    @AfterEach
    void resetMockAccountState() {
        accountClientMock.resetAllClosed();
    }

    private MockHttpServletRequestBuilder postScheduledPayment(
            String idempotencyKey,
            String userId,
            String authTokenId,
            String senderAccountId,
            String receiverBankCode,
            String receiverAccountNo,
            String receiverHolderName,
            long transferAmount,
            String channel,
            LocalDateTime scheduledExecutionAt) throws Exception {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("senderAccountId",               senderAccountId);
        body.put("receiverBankCode",              receiverBankCode);
        body.put("receiverAccountNo",             receiverAccountNo);
        body.put("receiverHolderName",            receiverHolderName);
        body.put("transferAmount",                BigDecimal.valueOf(transferAmount));
        body.put("receiverMemo",                  "예약이체");
        body.put("senderMemo",                    "예약송금");
        body.put("channel",                       channel);
        body.put("receiverPassbookSenderDisplay", "이몽룡");
        body.put("scheduledExecutionAt",          scheduledExecutionAt);

        return post("/api/v1/payments/scheduled")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Key", idempotencyKey)
                .header("X-User-Id",          userId)
                .header("X-Auth-Token-Id",    authTokenId)
                .content(om.writeValueAsString(body));
    }

    @Test
    @DisplayName("sX 자행 예약등록 정상 — SCHEDULED, ledger 0건, outbox 0건, AUTH_PASSED+SCHEDULED_REGISTERED 이력")
    void sX_registerScheduled_scheduled() throws Exception {
        LocalDateTime futureTime = LocalDateTime.now().plusHours(2);

        MvcResult result = mockMvc.perform(postScheduledPayment(
                "SCHED-001-1", "USER-001", "AUTH-001",
                SENDER_S1, BANK_CODE_A, RECEIVER_S1, "성춘향",
                100_000L, "MOBILE", futureTime
        ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SCHEDULED"))
        .andReturn();

        String piId = om.readTree(result.getResponse().getContentAsString())
                .get("paymentInstructionId").asText();

        // payment_instruction 상태 검증
        Map<String, Object> pi = jdbc.queryForMap(
                "SELECT status, is_scheduled, scheduled_execution_at, trigger_source, " +
                "receiver_holder_name_snap, auth_token_id " +
                "FROM payment_instruction WHERE payment_instruction_id = ?", piId);
        assertThat(pi.get("status")).isEqualTo("SCHEDULED");
        assertThat(pi.get("is_scheduled")).isEqualTo(true);
        assertThat(pi.get("scheduled_execution_at")).isNotNull();
        assertThat(pi.get("trigger_source")).isEqualTo("USER");
        assertThat(pi.get("receiver_holder_name_snap")).isNotNull();
        assertThat(pi.get("auth_token_id")).isEqualTo("AUTH-001");

        // ledger 0건 (PROCESSING 진입 안 함)
        int ledgerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger WHERE payment_instruction_id = ?",
                Integer.class, piId);
        assertThat(ledgerCount).isZero();

        // outbox 0건
        int outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE payment_instruction_id = ?",
                Integer.class, piId);
        assertThat(outboxCount).isZero();

        // status_history: AUTH_PASSED 1건 + SCHEDULED_REGISTERED 1건
        int authPassedCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'AUTH_PASSED'",
                Integer.class, piId);
        assertThat(authPassedCount).isEqualTo(1);

        int scheduledRegisteredCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'SCHEDULED_REGISTERED' " +
                "AND triggered_by = 'USER'",
                Integer.class, piId);
        assertThat(scheduledRegisteredCount).isEqualTo(1);
    }

    @Test
    @DisplayName("f_register_pastTime_400 — 과거 시각 → 400, payment_instruction 0건")
    void f_register_pastTime_400() throws Exception {
        LocalDateTime pastTime = LocalDateTime.now().minusMinutes(1);

        mockMvc.perform(postScheduledPayment(
                "SCHED-PAST-001-1", "USER-002", "AUTH-002",
                SENDER_S1, BANK_CODE_A, RECEIVER_S1, "성춘향",
                100_000L, "MOBILE", pastTime
        ))
        .andExpect(status().isBadRequest());

        int piCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_instruction", Integer.class);
        assertThat(piCount).isZero();
    }

    @Test
    @DisplayName("f_register_accountClosed_failed — 수신계좌 폐쇄 → A 검증 실패 FAILED, ACCOUNT_CHECK_FAILED 이벤트")
    void f_register_accountClosed_failed() throws Exception {
        LocalDateTime futureTime = LocalDateTime.now().plusHours(1);

        MvcResult result = mockMvc.perform(postScheduledPayment(
                "SCHED-CLOSED-001-1", "USER-003", "AUTH-003",
                SENDER_S1, BANK_CODE_A, RECEIVER_CLOSED, "홍길동",
                100_000L, "MOBILE", futureTime
        ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.failureCategory").value("ACCOUNT_CLOSED"))
        .andReturn();

        String piId = om.readTree(result.getResponse().getContentAsString())
                .get("paymentInstructionId").asText();

        String dbStatus = jdbc.queryForObject(
                "SELECT status FROM payment_instruction WHERE payment_instruction_id = ?",
                String.class, piId);
        assertThat(dbStatus).isEqualTo("FAILED");

        int eventCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'ACCOUNT_CHECK_FAILED'",
                Integer.class, piId);
        assertThat(eventCount).isEqualTo(1);

        // ledger 0건 (자금변동 없음)
        int ledgerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger WHERE payment_instruction_id = ?",
                Integer.class, piId);
        assertThat(ledgerCount).isZero();

        String prevStatusFailed = jdbc.queryForObject(
                "SELECT previous_status FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'PAYMENT_FAILED'",
                String.class, piId);
        assertThat(prevStatusFailed).isEqualTo("DRAFT");
    }

    // ── claim 단계 테스트 (단계 2) ─────────────────────────────────────────

    /**
     * 헬퍼: 예약이체 등록 후 piId 반환 (SCHEDULED 상태).
     */
    private String registerScheduled(String idKey, String userId, String authId,
                                     LocalDateTime scheduledAt) throws Exception {
        MvcResult result = mockMvc.perform(postScheduledPayment(
                idKey, userId, authId,
                SENDER_S1, BANK_CODE_A, RECEIVER_S1, "성춘향",
                100_000L, "MOBILE", scheduledAt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andReturn();
        return om.readTree(result.getResponse().getContentAsString())
                .get("paymentInstructionId").asText();
    }

    @Test
    @DisplayName("sched_claim_single — SCHEDULED PI claim → true, status=PROCESSING, version+1, SCHEDULED_TRIGGERED 이력, ledger/outbox 0건")
    void sched_claim_single() throws Exception {
        // 1. 예약 등록 (scheduled_execution_at = 미래)
        String piId = registerScheduled("SCHED-CLM-001-1", "USER-CLM-001", "AUTH-CLM-001",
                LocalDateTime.now().plusHours(1));

        // 2. scheduled_execution_at 을 과거로 설정 → 워커가 집을 수 있는 상태로 만듦
        jdbc.update("UPDATE payment_instruction SET scheduled_execution_at = ? " +
                    "WHERE payment_instruction_id = ?",
                LocalDateTime.now().minusMinutes(1), piId);

        // 3. selectDueScheduled 로 PI 읽기 (워커 폴링 대신 결정적 직접 호출)
        List<PaymentInstruction> due = paymentInstructionMapper.selectDueScheduled();
        PaymentInstruction pi = due.stream()
                .filter(p -> piId.equals(p.getPaymentInstructionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("selectDueScheduled 결과에 piId 없음: " + piId));

        int versionBeforeClaim = pi.getVersion();

        // 4. claim 직접 호출
        boolean claimed = txService.claimScheduled(pi);

        // 5. 검증
        assertThat(claimed).isTrue();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, version FROM payment_instruction WHERE payment_instruction_id = ?", piId);
        assertThat(row.get("status")).isEqualTo("PROCESSING");
        assertThat(((Number) row.get("version")).intValue()).isEqualTo(versionBeforeClaim + 1);

        // SCHEDULED_TRIGGERED 이력 1건, triggered_by=SCHEDULER
        int trigCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'SCHEDULED_TRIGGERED' " +
                "AND triggered_by = 'SCHEDULER'",
                Integer.class, piId);
        assertThat(trigCount).isEqualTo(1);

        // 이전 상태 = SCHEDULED, 다음 상태 = PROCESSING 확인
        Map<String, Object> hist = jdbc.queryForMap(
                "SELECT previous_status, next_status FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'SCHEDULED_TRIGGERED'", piId);
        assertThat(hist.get("previous_status")).isEqualTo("SCHEDULED");
        assertThat(hist.get("next_status")).isEqualTo("PROCESSING");

        // ledger 0건, outbox 0건 (실행 단계 미진입)
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger WHERE payment_instruction_id = ?",
                Integer.class, piId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE payment_instruction_id = ?",
                Integer.class, piId)).isZero();
    }

    @Test
    @DisplayName("sched_claim_double_idempotent — 이중선점 방지: 1회차 true, 2회차 false, DB status=PROCESSING 1회·이력 1건")
    void sched_claim_double_idempotent() throws Exception {
        // 1. 예약 등록 후 과거 시각으로 설정
        String piId = registerScheduled("SCHED-CLM-002-1", "USER-CLM-002", "AUTH-CLM-002",
                LocalDateTime.now().plusHours(1));
        jdbc.update("UPDATE payment_instruction SET scheduled_execution_at = ? " +
                    "WHERE payment_instruction_id = ?",
                LocalDateTime.now().minusMinutes(1), piId);

        // 2. selectDueScheduled 로 PI 획득 (version 고정)
        PaymentInstruction pi = paymentInstructionMapper.selectDueScheduled().stream()
                .filter(p -> piId.equals(p.getPaymentInstructionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PI 없음: " + piId));

        // 3. 1회차 claim
        boolean first = txService.claimScheduled(pi);
        assertThat(first).isTrue();

        // 4. 동일 pi 객체(version 변경 없음)로 2회차 claim → version 불일치로 false
        boolean second = txService.claimScheduled(pi);
        assertThat(second).isFalse();

        // 5. DB: status=PROCESSING, version은 1회만 올라감
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, version FROM payment_instruction WHERE payment_instruction_id = ?", piId);
        assertThat(row.get("status")).isEqualTo("PROCESSING");
        assertThat(((Number) row.get("version")).intValue()).isEqualTo(pi.getVersion() + 1);

        // SCHEDULED_TRIGGERED 이력 정확히 1건 (중복 INSERT 없음)
        int trigCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'SCHEDULED_TRIGGERED'",
                Integer.class, piId);
        assertThat(trigCount).isEqualTo(1);
    }

    @Test
    @DisplayName("sched_claim_notDue — scheduled_execution_at 미래인 PI는 selectDueScheduled 에 안 잡힘(0건)")
    void sched_claim_notDue() throws Exception {
        // 1. 예약 등록 (미래 시각 — 과거로 바꾸지 않음)
        String piId = registerScheduled("SCHED-CLM-003-1", "USER-CLM-003", "AUTH-CLM-003",
                LocalDateTime.now().plusHours(2));

        // 2. selectDueScheduled 결과에 해당 PI 없음
        List<PaymentInstruction> due = paymentInstructionMapper.selectDueScheduled();
        boolean found = due.stream().anyMatch(p -> piId.equals(p.getPaymentInstructionId()));
        assertThat(found).isFalse();

        // 3. DB status 여전히 SCHEDULED
        String status = jdbc.queryForObject(
                "SELECT status FROM payment_instruction WHERE payment_instruction_id = ?",
                String.class, piId);
        assertThat(status).isEqualTo("SCHEDULED");
    }

    // ── 실행 단계 테스트 (단계 3) ─────────────────────────────────────────────

    @Test
    @DisplayName("sched_execute_intra_completed — 자행 예약 정상 실행 e2e: COMPLETED, ledger 2건, outbox 1건, 이력 시퀀스")
    void sched_execute_intra_completed() throws Exception {
        // 1. 예약 등록 (SCHEDULED)
        String piId = registerScheduled("SCHED-EXEC-001-1", "USER-EXEC-001", "AUTH-EXEC-001",
                LocalDateTime.now().plusHours(1));

        // 2. scheduled_execution_at 과거로 설정 → 워커 폴링 대상
        jdbc.update("UPDATE payment_instruction SET scheduled_execution_at = ? " +
                    "WHERE payment_instruction_id = ?",
                LocalDateTime.now().minusMinutes(1), piId);

        // 3. selectDueScheduled → pi 획득 (version=V, DB=V)
        PaymentInstruction pi = paymentInstructionMapper.selectDueScheduled().stream()
                .filter(p -> piId.equals(p.getPaymentInstructionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("selectDueScheduled 에 PI 없음: " + piId));
        int versionAtScheduled = pi.getVersion();
        String snapBeforeExecution = pi.getReceiverHolderNameSnap(); // 등록 시 박제값 — 실행 후에도 불변 확인용

        // 4. claim (SCHEDULED→PROCESSING, DB version=V+1)
        boolean claimed = txService.claimScheduled(pi);
        assertThat(claimed).isTrue();

        // 5. executeScheduledIntraBank (정상 실행 경로)
        orchestrator.executeScheduledIntraBank(pi);

        // ── 단언 ──
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, version, completed_at, receiver_holder_name_snap " +
                "FROM payment_instruction WHERE payment_instruction_id = ?", piId);

        // status=COMPLETED
        assertThat(row.get("status")).isEqualTo("COMPLETED");

        // version = 등록후(V) + claim(+1) + COMPLETED(+1) = V+2
        assertThat(((Number) row.get("version")).intValue()).isEqualTo(versionAtScheduled + 2);

        // completed_at 세팅됨
        assertThat(row.get("completed_at")).isNotNull();

        // receiver_holder_name_snap 보존 — 실행 후에도 등록 시점 값과 동일 (재조회/덮어쓰기 없음)
        assertThat(row.get("receiver_holder_name_snap")).isNotNull();
        assertThat(row.get("receiver_holder_name_snap")).isEqualTo(snapBeforeExecution);

        // 실행 경로 external_call 에 ACCOUNT_OWNER_INQUIRY-RECEIVER attempt=2 가 없음
        // (sender 만 attempt=2 재검증, receiver 재조회 없음 — Option B)
        int receiverOwnerInquiry2Count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM external_call " +
                "WHERE call_idempotency_key = ?",
                Integer.class, piId + "-ACCOUNT_OWNER_INQUIRY-RECEIVER-2");
        assertThat(receiverOwnerInquiry2Count).isZero();

        // ledger 2건 (TRANSFER_OUT + TRANSFER_IN)
        int ledgerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger WHERE payment_instruction_id = ?",
                Integer.class, piId);
        assertThat(ledgerCount).isEqualTo(2);

        int outCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger " +
                "WHERE payment_instruction_id = ? AND journal_type = 'TRANSFER_OUT'",
                Integer.class, piId);
        assertThat(outCount).isEqualTo(1);

        int inCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger " +
                "WHERE payment_instruction_id = ? AND journal_type = 'TRANSFER_IN'",
                Integer.class, piId);
        assertThat(inCount).isEqualTo(1);

        // outbox PAYMENT_COMPLETED 1건
        int outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_message " +
                "WHERE payment_instruction_id = ? AND event_type = 'PAYMENT_COMPLETED'",
                Integer.class, piId);
        assertThat(outboxCount).isEqualTo(1);

        // 이력 시퀀스: SCHEDULED_TRIGGERED → PROCESSING_STARTED → PAYMENT_COMPLETED
        List<String> events = jdbc.queryForList(
                "SELECT event_type FROM status_history " +
                "WHERE payment_instruction_id = ? " +
                "ORDER BY sequence_in_payment ASC", String.class, piId);
        int trigIdx  = events.indexOf("SCHEDULED_TRIGGERED");
        int startIdx = events.indexOf("PROCESSING_STARTED");
        int compIdx  = events.indexOf("PAYMENT_COMPLETED");
        assertThat(trigIdx).isGreaterThanOrEqualTo(0);
        assertThat(startIdx).isGreaterThan(trigIdx);
        assertThat(compIdx).isGreaterThan(startIdx);

        // PROCESSING_STARTED 정확히 1건
        long procStartedCount = events.stream()
                .filter("PROCESSING_STARTED"::equals).count();
        assertThat(procStartedCount).isEqualTo(1);
    }

    // ── 실행 실패/보상 테스트 (3갈래) ─────────────────────────────────────────

    @Test
    @DisplayName("sched_execute_senderClosed_failed — claim 후 sender CLOSED → PROCESSING→FAILED, ACCOUNT_CLOSED, ledger 0")
    void sched_execute_senderClosed_failed() throws Exception {
        // 1. 정상 등록 (SENDER_S1 ACTIVE)
        String piId = registerScheduled("SCHED-SCLOS-001-1", "USER-SCLOS-001", "AUTH-SCLOS-001",
                LocalDateTime.now().plusHours(1));

        // 2. scheduled_execution_at 과거 설정
        jdbc.update("UPDATE payment_instruction SET scheduled_execution_at = ? WHERE payment_instruction_id = ?",
                LocalDateTime.now().minusMinutes(1), piId);

        // 3. selectDueScheduled + claim
        PaymentInstruction pi = paymentInstructionMapper.selectDueScheduled().stream()
                .filter(p -> piId.equals(p.getPaymentInstructionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PI 없음: " + piId));
        boolean claimed = txService.claimScheduled(pi);
        assertThat(claimed).isTrue();

        // 4. claim 후, 실행 전 sender 계좌 CLOSED 설정
        accountClientMock.closeAccount(SENDER_S1);

        // 5. 실행 → step2a_executeRevalidation CLOSED 감지 → PaymentValidationException → PROCESSING→FAILED
        orchestrator.executeScheduledIntraBank(pi);

        // 6. 검증
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, failure_category FROM payment_instruction WHERE payment_instruction_id = ?", piId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_category")).isEqualTo("ACCOUNT_CLOSED");
        assertThat(row.get("status")).isNotEqualTo("PROCESSING"); // stuck 아님

        // ledger 0건 (B-3 미도달)
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger WHERE payment_instruction_id = ?", Integer.class, piId)).isZero();

        // 이력 from_status="PROCESSING" (ACCOUNT_CHECK_FAILED 이벤트)
        String prevStatusEvent = jdbc.queryForObject(
                "SELECT previous_status FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'ACCOUNT_CHECK_FAILED'",
                String.class, piId);
        assertThat(prevStatusEvent).isEqualTo("PROCESSING");

        // 이력 from_status="PROCESSING" (PAYMENT_FAILED 전이)
        String prevStatusFailed = jdbc.queryForObject(
                "SELECT previous_status FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'PAYMENT_FAILED'",
                String.class, piId);
        assertThat(prevStatusFailed).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("sched_execute_insufficientBalance_failed — 잔액부족 sender → PROCESSING→FAILED, INSUFFICIENT_BALANCE, ledger 0")
    void sched_execute_insufficientBalance_failed() throws Exception {
        // SENDER_F1 잔액 500만, 이체금액 600만 → step2b 잔액부족 → INSUFFICIENT_BALANCE
        LocalDateTime futureTime = LocalDateTime.now().plusHours(1);
        MvcResult result = mockMvc.perform(postScheduledPayment(
                "SCHED-INSUF-001-1", "USER-INSUF-001", "AUTH-INSUF-001",
                SENDER_F1, BANK_CODE_A, RECEIVER_S1, "성춘향",
                6_000_000L, "MOBILE", futureTime
        ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SCHEDULED"))
        .andReturn();

        String piId = om.readTree(result.getResponse().getContentAsString())
                .get("paymentInstructionId").asText();

        jdbc.update("UPDATE payment_instruction SET scheduled_execution_at = ? WHERE payment_instruction_id = ?",
                LocalDateTime.now().minusMinutes(1), piId);

        PaymentInstruction pi = paymentInstructionMapper.selectDueScheduled().stream()
                .filter(p -> piId.equals(p.getPaymentInstructionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PI 없음: " + piId));

        txService.claimScheduled(pi);

        // 실행 → step2b 잔액부족 → PaymentValidationException → PROCESSING→FAILED
        orchestrator.executeScheduledIntraBank(pi);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, failure_category FROM payment_instruction WHERE payment_instruction_id = ?", piId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_category")).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(row.get("status")).isNotEqualTo("PROCESSING"); // stuck 아님

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger WHERE payment_instruction_id = ?", Integer.class, piId)).isZero();

        // 이력 from_status="PROCESSING"
        String prevStatusFailed = jdbc.queryForObject(
                "SELECT previous_status FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'PAYMENT_FAILED'",
                String.class, piId);
        assertThat(prevStatusFailed).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("sched_execute_F8_reversed — F8 receiver 입금실패 → PROCESSING→REVERSING→FAILED, version=등록후+3")
    void sched_execute_F8_reversed() throws Exception {
        // 1. F8 트리거 계좌로 예약 등록 (홍판서 — getHolder("12345678909999")="홍판서")
        LocalDateTime futureTime = LocalDateTime.now().plusHours(1);
        MvcResult result = mockMvc.perform(postScheduledPayment(
                "SCHED-F8-001-1", "USER-F8-001", "AUTH-F8-001",
                SENDER_S1, BANK_CODE_A, RECEIVER_F8, "홍판서",
                100_000L, "MOBILE", futureTime
        ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SCHEDULED"))
        .andReturn();

        String piId = om.readTree(result.getResponse().getContentAsString())
                .get("paymentInstructionId").asText();

        jdbc.update("UPDATE payment_instruction SET scheduled_execution_at = ? WHERE payment_instruction_id = ?",
                LocalDateTime.now().minusMinutes(1), piId);

        // 2. selectDueScheduled — version 기록
        PaymentInstruction pi = paymentInstructionMapper.selectDueScheduled().stream()
                .filter(p -> piId.equals(p.getPaymentInstructionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PI 없음: " + piId));
        int versionAtScheduled = pi.getVersion(); // SCHEDULED 시점 version (= 2)

        // 3. claim → PROCESSING (version V+1)
        boolean claimed = txService.claimScheduled(pi);
        assertThat(claimed).isTrue();

        // 4. execute → B-4 DEP-9001 → DepositInboundFailureException → 보상
        orchestrator.executeScheduledIntraBank(pi);

        // 5. 검증
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, failure_category, version FROM payment_instruction WHERE payment_instruction_id = ?", piId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_category")).isEqualTo("SYSTEM_ERROR");
        // version: SCHEDULED(V) + claim(+1) + txMarkReversing(+1) + txCompleteReversal(+1) = V+3
        assertThat(((Number) row.get("version")).intValue()).isEqualTo(versionAtScheduled + 3);

        // ledger 0건 (txCompleteReversal — 역분개 없음, P-026)
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger WHERE payment_instruction_id = ?", Integer.class, piId)).isZero();

        // SYSTEM_FAILURE_DETECTED: from_status="PROCESSING"
        String sfdPrev = jdbc.queryForObject(
                "SELECT previous_status FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'SYSTEM_FAILURE_DETECTED'",
                String.class, piId);
        assertThat(sfdPrev).isEqualTo("PROCESSING");

        // COMPENSATION_STARTED: from_status="PROCESSING"
        String csPrev = jdbc.queryForObject(
                "SELECT previous_status FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'COMPENSATION_STARTED'",
                String.class, piId);
        assertThat(csPrev).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("sched_execute_F5_reversed — F5 분개실패(txStep4Scheduled 롤백) → 보상, version=등록후+3")
    void sched_execute_F5_reversed() throws Exception {
        // 1. F5 트리거 계좌로 예약 등록 (변학도 — getHolder("88880000")="변학도")
        LocalDateTime futureTime = LocalDateTime.now().plusHours(1);
        MvcResult result = mockMvc.perform(postScheduledPayment(
                "SCHED-F5-001-1", "USER-F5-001", "AUTH-F5-001",
                SENDER_S1, BANK_CODE_A, RECEIVER_F5, "변학도",
                100_000L, "MOBILE", futureTime
        ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SCHEDULED"))
        .andReturn();

        String piId = om.readTree(result.getResponse().getContentAsString())
                .get("paymentInstructionId").asText();

        jdbc.update("UPDATE payment_instruction SET scheduled_execution_at = ? WHERE payment_instruction_id = ?",
                LocalDateTime.now().minusMinutes(1), piId);

        PaymentInstruction pi = paymentInstructionMapper.selectDueScheduled().stream()
                .filter(p -> piId.equals(p.getPaymentInstructionId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PI 없음: " + piId));
        int versionAtScheduled = pi.getVersion();

        txService.claimScheduled(pi);

        // execute → txStep4Scheduled 내 분개 INSERT 실패 → 롤백 → PROCESSING 복귀 → 보상
        orchestrator.executeScheduledIntraBank(pi);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, failure_category, version FROM payment_instruction WHERE payment_instruction_id = ?", piId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("failure_category")).isEqualTo("SYSTEM_ERROR");
        assertThat(((Number) row.get("version")).intValue()).isEqualTo(versionAtScheduled + 3);

        // ledger 0건 (txStep4Scheduled 롤백으로 분개도 롤백, txCompleteReversal 역분개 없음)
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger WHERE payment_instruction_id = ?", Integer.class, piId)).isZero();

        // REVERSAL 이력 from_status="PROCESSING"
        String sfdPrev = jdbc.queryForObject(
                "SELECT previous_status FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'SYSTEM_FAILURE_DETECTED'",
                String.class, piId);
        assertThat(sfdPrev).isEqualTo("PROCESSING");

        String csPrev = jdbc.queryForObject(
                "SELECT previous_status FROM status_history " +
                "WHERE payment_instruction_id = ? AND event_type = 'COMPENSATION_STARTED'",
                String.class, piId);
        assertThat(csPrev).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("sched_worker_intra_completed — 워커 triggerDueScheduled 직접 호출 → COMPLETED (배선 확인)")
    void sched_worker_intra_completed() throws Exception {
        // 1. 예약 등록 후 과거 시각 설정
        String piId = registerScheduled("SCHED-WRK-001-1", "USER-WRK-001", "AUTH-WRK-001",
                LocalDateTime.now().plusHours(1));
        jdbc.update("UPDATE payment_instruction SET scheduled_execution_at = ? " +
                    "WHERE payment_instruction_id = ?",
                LocalDateTime.now().minusMinutes(1), piId);

        // 2. 워커 폴링 1회 직접 호출 (claim + executeScheduledIntraBank 배선 검증)
        scheduledPaymentWorker.triggerDueScheduled();

        // 3. COMPLETED 검증
        String status = jdbc.queryForObject(
                "SELECT status FROM payment_instruction WHERE payment_instruction_id = ?",
                String.class, piId);
        assertThat(status).isEqualTo("COMPLETED");

        int ledgerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger WHERE payment_instruction_id = ?",
                Integer.class, piId);
        assertThat(ledgerCount).isEqualTo(2);
    }
}
