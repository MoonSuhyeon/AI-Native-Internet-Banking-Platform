package com.bank.payment.api;

import com.bank.payment.api.dto.PaymentDetailResponse;
import com.bank.payment.domain.PaymentInstruction;
import com.bank.payment.domain.service.PaymentTransactionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 이상거래 탐지용 결제 상세 조회.
 *
 * <p><b>무엇을 지키는가.</b> 탐지 룰은 여기서 받은 필드로만 판정한다. 필드가 하나
 * 비어 오면 그 룰은 아무 말 없이 못 잡는다 — 예외도 안 나고 로그도 안 남는다.
 * 그래서 값이 오는지가 아니라 <b>어느 필드에서 오는지</b>까지 고정한다.
 *
 * <p>특히 계좌번호·예금주명은 스냅샷(거래 시점 값)이어야 한다. 나중에 별명이나
 * 정보가 바뀌어도 "그때 무엇으로 보냈는가"가 탐지·조사의 사실이기 때문이다.
 */
class InternalPaymentControllerTest {

    private final PaymentTransactionService service = mock(PaymentTransactionService.class);
    private final InternalPaymentController controller = new InternalPaymentController(service);

    private PaymentInstruction sample() {
        return PaymentInstruction.builder()
                .paymentInstructionId("PI-1")
                .transactionNo("TXN-1")
                .status("COMPLETED")
                .senderUserId("9001")
                .senderAccountId("ACC-INTERNAL-1")
                .senderAccountNoSnap("110-222-333333")
                .senderAccountAliasSnap("월급통장")
                .receiverBankCode("004")
                .receiverAccountNo("999-888-777777")
                .receiverHolderNameSnap("홍길동")
                .transferAmount(15_000_000L)
                .isIntraBank(false)
                .routingNetworkType("KFTC")
                .channel("MOBILE")
                .requestedAt(OffsetDateTime.parse("2026-08-08T01:00:00Z"))
                .completedAt(OffsetDateTime.parse("2026-08-08T01:00:05Z"))
                .businessDate("20260808")
                .build();
    }

    @Test
    @DisplayName("탐지 룰이 쓰는 필드가 모두 채워져 나온다")
    void returnsFieldsUsedByDetectionRules() {
        when(service.selectById(anyString())).thenReturn(sample());

        PaymentDetailResponse body = controller.getPaymentDetail("PI-1").getBody();

        assertThat(body).isNotNull();
        // 분할·속도 룰의 집계 키
        assertThat(body.senderUserId()).isEqualTo("9001");
        // 신규 수취인·fan-out 룰의 집계 키
        assertThat(body.receiverAccountNo()).isEqualTo("999-888-777777");
        assertThat(body.receiverBankCode()).isEqualTo("004");
        // 타행 집중 룰
        assertThat(body.intraBank()).isFalse();
        // 거액 룰
        assertThat(body.amount()).isEqualTo(15_000_000L);
        // 심야 거래 룰
        assertThat(body.completedAt()).isEqualTo(OffsetDateTime.parse("2026-08-08T01:00:05Z"));
        // 채널 이상 신호
        assertThat(body.channel()).isEqualTo("MOBILE");
    }

    @Test
    @DisplayName("계좌번호와 예금주명은 거래 시점 스냅샷을 쓴다")
    void usesSnapshotFieldsNotLiveReferences() {
        when(service.selectById(anyString())).thenReturn(sample());

        PaymentDetailResponse body = controller.getPaymentDetail("PI-1").getBody();

        assertThat(body).isNotNull();
        // senderAccountId(내부 식별자)가 아니라 senderAccountNoSnap 이어야 한다.
        assertThat(body.senderAccountNo())
                .as("내부 계좌 식별자가 아니라 거래 시점 계좌번호 스냅샷")
                .isEqualTo("110-222-333333")
                .isNotEqualTo("ACC-INTERNAL-1");
        assertThat(body.receiverHolderName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("없는 결제지시는 404 — 탐지기가 보강 실패를 구분할 수 있어야 한다")
    void unknownPaymentReturns404() {
        when(service.selectById(anyString())).thenReturn(null);

        ResponseEntity<PaymentDetailResponse> res = controller.getPaymentDetail("PI-NONE");

        // 200 에 빈 본문을 주면 탐지기가 "값이 없는 거래"와 "못 찾은 거래"를
        // 구별하지 못해 조용히 오탐/미탐이 된다.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).isNull();
    }
}
