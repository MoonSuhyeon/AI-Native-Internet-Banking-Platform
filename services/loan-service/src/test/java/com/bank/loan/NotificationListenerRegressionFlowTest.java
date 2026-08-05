package com.bank.loan;

import com.bank.loan.notification.event.ApplicationSubmittedEvent;
import com.bank.loan.notification.event.ContractSignedEvent;
import com.bank.loan.notification.event.InstallmentPaidEvent;
import com.bank.loan.notification.event.LoanApprovedEvent;
import com.bank.loan.notification.event.LoanDisbursedEvent;
import com.bank.loan.notification.outbox.NotificationOutbox;
import com.bank.loan.notification.outbox.NotificationOutboxRepository;
import com.bank.loan.support.AbstractLoanIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * plan 03 step 7: notification listener 5종 회귀. 연도 2035.
 *
 * 목적은 채널 분기 로직(이는 [[NotificationListenerOutboxTest]] 가 단위로 검증)이 아니라
 * `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` + outbox 적재까지의 배선이
 * 실제 Spring 컨텍스트에서 끊기지 않았는지를 본다.
 *
 * 트리거 방식:
 *   - TransactionTemplate 안에서 ApplicationEventPublisher 로 이벤트를 발행 → tx commit → AFTER_COMMIT 핵
 *   - @Async 라 ms 단위 지연이 있으므로 Awaitility 로 outbox row 등장을 폴링한다
 *
 * referenceId 격리:
 *   클래스 정적 시퀀스에서 채번한 큰 값 사용 — 다른 테스트와 충돌 가능성 0.
 */
class NotificationListenerRegressionFlowTest extends AbstractLoanIntegrationTest {

    /** 다른 테스트 클래스와 referenceId 가 겹치지 않게 격리. */
    private static final AtomicLong REF_SEQ = new AtomicLong(9_700_000L);
    private static long nextRef() { return REF_SEQ.incrementAndGet(); }

    private static final String SMS   = "SMS";
    private static final String KAKAO = "KAKAO_ALIMTALK";
    private static final String EMAIL = "EMAIL";

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private NotificationOutboxRepository outboxRepository;

    @Test
    void APPLICATION_SUBMITTED_는_3채널_적재() {
        long applId = nextRef();
        publishInTx(new ApplicationSubmittedEvent(applId, "APP-" + applId, 5101L, 7101L));

        awaitChannels("APPLICATION_SUBMITTED", applId, SMS, KAKAO, EMAIL);
    }

    @Test
    void LOAN_APPROVED_는_3채널_적재() {
        long applId = nextRef();
        publishInTx(new LoanApprovedEvent(applId, 4101L, 5102L, 10_000_000L));

        awaitChannels("LOAN_APPROVED", applId, SMS, KAKAO, EMAIL);
    }

    @Test
    void CONTRACT_SIGNED_는_3채널_적재() {
        long cntrId = nextRef();
        publishInTx(new ContractSignedEvent(cntrId, "CN-" + cntrId, 8101L, 5103L));

        awaitChannels("CONTRACT_SIGNED", cntrId, SMS, KAKAO, EMAIL);
    }

    @Test
    void LOAN_DISBURSED_는_3채널_적재() {
        long cntrId = nextRef();
        publishInTx(new LoanDisbursedEvent(cntrId, "CN-" + cntrId, 5104L, 9_000_000L));

        awaitChannels("LOAN_DISBURSED", cntrId, SMS, KAKAO, EMAIL);
    }

    @Test
    void INSTALLMENT_PAID_는_SMS_KAKAO_2채널_적재_이메일_없음() {
        long rtxId = nextRef();
        publishInTx(new InstallmentPaidEvent(rtxId, 8201L, 12_345L, 1, 100_000L, "AUTO_DEBIT"));

        awaitChannels("INSTALLMENT_PAID", rtxId, SMS, KAKAO);
        assertThat(loadByKey("INSTALLMENT_PAID", rtxId, EMAIL)).isEmpty();
    }

    private void publishInTx(Object event) {
        TransactionTemplate tt = new TransactionTemplate(txManager);
        tt.executeWithoutResult(s -> publisher.publishEvent(event));
    }

    private void awaitChannels(String eventType, long ref, String... channels) {
        await().atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(50))
                .until(() -> {
                    for (String ch : channels) {
                        if (loadByKey(eventType, ref, ch).isEmpty()) return false;
                    }
                    return true;
                });
        List<NotificationOutbox> rows = List.of(channels).stream()
                .map(ch -> loadByKey(eventType, ref, ch).orElseThrow())
                .toList();
        // 이 테스트의 관심사는 "리스너가 채널별로 outbox 행을 만들었는가" 다.
        // 발송 여부는 아니다 — 디스패처가 배경에서 돌고 있어 적재 직후 이미 SENT 로
        // 바뀌어 있을 수 있다. status/attemptNo 를 고정값으로 단언하면 머신이 느리거나
        // 절전에서 깨어난 순간 무작위로 깨진다(실제로 그렇게 깨졌다).
        for (NotificationOutbox row : rows) {
            assertThat(row.getStatus())
                    .as("적재됐거나 이미 발송됐거나 — 실패 상태만 아니면 된다")
                    .isIn(NotificationOutbox.STATUS_PENDING, NotificationOutbox.STATUS_SENT);
            assertThat(row.getAttemptNo())
                    .as("리스너가 새로 만든 행이므로 재시도 누적이 없다")
                    .isNotNull()
                    .isLessThanOrEqualTo(1);
            assertThat(row.getReferenceId()).isEqualTo(ref);
        }
        assertThat(rows).extracting(NotificationOutbox::getChannelCd)
                .containsExactlyInAnyOrder(channels);
    }

    private Optional<NotificationOutbox> loadByKey(String eventType, long ref, String channel) {
        return outboxRepository.findByIdempotencyKeyAndDeletedAtIsNull(
                NotificationOutbox.idempotencyKeyOf(eventType, ref, channel));
    }
}
