package com.bank.deposit.domain.entity;

import com.bank.deposit.common.BaseEntity;
import com.bank.deposit.domain.enums.CertificateType;
import com.bank.deposit.domain.enums.TransactionStatus;
import com.bank.deposit.domain.enums.TransactionType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 거래 증빙 발급 이력.
 *
 * <p><b>왜 조회가 아니라 발급인가.</b> 증빙을 "거래를 조회해 보여주기" 로 만들면 발급
 * 사실이 남지 않는다. 증빙은 발급하는 순간 문서가 되고, 그 문서에는 나중에 대조할 수
 * 있는 번호가 있어야 한다. 누구에게 언제 발급됐는지는 그 자체가 개인정보 접근 기록이다.
 *
 * <p><b>재발급을 막지 않는다.</b> 실무에서 증빙은 다시 뽑을 수 있다. 대신 뽑을 때마다
 * 새 번호가 붙고 이력이 쌓인다 — 같은 번호를 재사용하면 "언제 발급된 문서인가" 를
 * 구별할 수 없다.
 */
@Entity
@Table(name = "deposit_transaction_certificate")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TransactionCertificate extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "certificate_id")
    private Long certificateId;

    @Column(name = "certificate_no", length = 40, nullable = false, unique = true)
    private String certificateNo;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "certificate_type", length = 30, nullable = false)
    private CertificateType certificateType;

    @Column(name = "issued_to_customer_id", length = 30, nullable = false)
    private String issuedToCustomerId;

    @Column(name = "issued_at", nullable = false)
    private OffsetDateTime issuedAt;

    /**
     * 이 거래에 이 종류의 증빙을 발급할 수 있는지 본다.
     *
     * <p>규칙을 서비스가 아니라 여기 두는 이유는, 발급 경로가 나중에 하나 더 생겨도
     * (일괄 발급·배치·관리자 도구) 같은 검사를 지나게 하기 위해서다. 서비스에 두면
     * 새 경로가 검사를 건너뛸 수 있고, 그러면 실패한 이체의 확인증이 발급된다.
     *
     * @throws IllegalStateException 발급할 수 없는 거래일 때
     */
    public static void assertIssuable(Transaction transaction, CertificateType type) {
        if (transaction.getStatus() != TransactionStatus.SUCCESS) {
            // 실패·취소·대기 거래의 증빙은 "돈이 오갔다" 는 잘못된 사실을 증명한다.
            throw new IllegalStateException(
                    "성공한 거래만 증빙을 발급할 수 있다: status=" + transaction.getStatus());
        }
        if (type == CertificateType.TRANSFER_CONFIRMATION
                && transaction.getTransactionType() != TransactionType.TRANSFER) {
            // 이체확인증은 "누구에게 보냈다" 를 증명하는 문서다. 이체가 아니면 적을
            // 상대가 없고, 상대가 빈 확인증은 제출해도 아무것도 증명하지 못한다.
            throw new IllegalStateException(
                    "이체확인증은 이체 거래에만 발급할 수 있다: type="
                            + transaction.getTransactionType());
        }
    }

    /** 발급. 번호는 채번기가 만든다 — 엔티티가 형식을 정하지 않는다. */
    public static TransactionCertificate issue(
            Transaction transaction, CertificateType type,
            String issuedToCustomerId, String certificateNo, OffsetDateTime at) {

        assertIssuable(transaction, type);
        if (issuedToCustomerId == null || issuedToCustomerId.isBlank()) {
            throw new IllegalArgumentException("발급 대상이 없다 — 누구에게 발급했는지 남지 않는다");
        }
        return TransactionCertificate.builder()
                .certificateNo(certificateNo)
                .transactionId(transaction.getTransactionId())
                .certificateType(type)
                .issuedToCustomerId(issuedToCustomerId)
                .issuedAt(at)
                .build();
    }
}
