package com.bank.deposit.domain;

import com.bank.deposit.domain.entity.Transaction;
import com.bank.deposit.domain.entity.TransactionCertificate;
import com.bank.deposit.domain.enums.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 거래 증빙 발급 규칙.
 *
 * <p><b>무엇을 지키는 테스트인가.</b> "증빙을 뽑을 수 있다" 가 아니라 <b>거짓을
 * 증명하는 문서가 나오지 않는다</b> 를 지킨다. 증빙은 제출받는 쪽이 사실로 믿는
 * 문서라, 실패한 이체의 확인증이 한 장이라도 나오면 그 문서 전체의 신뢰가 무너진다.
 *
 * <p>규칙을 서비스가 아니라 엔티티에 둔 이유도 여기서 확인된다 — 발급 경로가 하나 더
 * 생겨도(일괄 발급·배치) 같은 검사를 지난다.
 */
class TransactionCertificateTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now();
    private static final String CUSTOMER = "9111";
    private static final String CERT_NO = "TC-20260817-7F3K9Q2M";

    private Transaction tx(TransactionType type, TransactionStatus status) {
        Transaction t = Transaction.builder()
                .transactionNumber("TXN-20260817-0001")
                .accountId(1L)
                .transactionType(type)
                .directionType(DirectionType.OUT)
                .amount(500_000L)
                .balanceBefore(1_000_000L)
                .balanceAfter(500_000L)
                .channelType(TransactionChannel.INTERNET)
                .transactionAt(NOW)
                .build();
        if (status == TransactionStatus.CANCELED) {
            t.cancel();
        }
        return t;
    }

    @Nested
    @DisplayName("거짓을 증명하지 않는다")
    class Truthfulness {

        @Test
        @DisplayName("성공하지 않은 거래는 증빙을 낼 수 없다")
        void onlySuccessful() {
            // 취소된 이체의 확인증은 "돈이 갔다" 는 없는 사실을 증명한다.
            Transaction canceled = tx(TransactionType.TRANSFER, TransactionStatus.CANCELED);

            assertThatThrownBy(() -> TransactionCertificate.issue(
                    canceled, CertificateType.TRANSFER_CONFIRMATION, CUSTOMER, CERT_NO, NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("성공한 거래만");
        }

        @Test
        @DisplayName("이체가 아니면 이체확인증을 낼 수 없다")
        void transferConfirmationNeedsTransfer() {
            // 상대가 없는 확인증은 제출해도 아무것도 증명하지 못한다.
            Transaction deposit = tx(TransactionType.DEPOSIT, TransactionStatus.SUCCESS);

            assertThatThrownBy(() -> TransactionCertificate.issue(
                    deposit, CertificateType.TRANSFER_CONFIRMATION, CUSTOMER, CERT_NO, NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("이체 거래에만");
        }

        @Test
        @DisplayName("영수증은 이체가 아닌 거래에도 낼 수 있다")
        void receiptAllowsAnyType() {
            // 영수증은 "내 계좌에서 무슨 일이 있었나" 라서 상대가 없어도 성립한다.
            Transaction deposit = tx(TransactionType.DEPOSIT, TransactionStatus.SUCCESS);

            TransactionCertificate cert = TransactionCertificate.issue(
                    deposit, CertificateType.RECEIPT, CUSTOMER, "RC-20260817-ABCD2345", NOW);

            assertThat(cert.getCertificateType()).isEqualTo(CertificateType.RECEIPT);
        }
    }

    @Nested
    @DisplayName("누구에게 발급했는지 남는다")
    class IssuedTo {

        @Test
        @DisplayName("발급 대상 없이는 낼 수 없다")
        void customerRequired() {
            Transaction transfer = tx(TransactionType.TRANSFER, TransactionStatus.SUCCESS);

            assertThatThrownBy(() -> TransactionCertificate.issue(
                    transfer, CertificateType.TRANSFER_CONFIRMATION, "  ", CERT_NO, NOW))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("발급 대상이 없다");
        }

        @Test
        @DisplayName("번호·대상·시각이 모두 남는다")
        void recordsIssuance() {
            Transaction transfer = tx(TransactionType.TRANSFER, TransactionStatus.SUCCESS);

            TransactionCertificate cert = TransactionCertificate.issue(
                    transfer, CertificateType.TRANSFER_CONFIRMATION, CUSTOMER, CERT_NO, NOW);

            assertThat(cert.getCertificateNo()).isEqualTo(CERT_NO);
            assertThat(cert.getIssuedToCustomerId()).isEqualTo(CUSTOMER);
            assertThat(cert.getIssuedAt()).isEqualTo(NOW);
        }
    }
}
