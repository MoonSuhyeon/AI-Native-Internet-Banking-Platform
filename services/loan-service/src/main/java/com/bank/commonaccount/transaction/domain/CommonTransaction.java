package com.bank.commonaccount.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 공통 거래원장 (common_db.common_transaction).
 *
 * 은행 공통 거래/분개 원장. loan-service 의 loan_execution / repayment_transaction 은
 * loan_db 에서 transaction_id 값으로 본 테이블을 참조한다(FK 없음, cross-DB).
 * account_id/contract_id 는 common_db 내부 FK 대상이며 값으로 세팅한다.
 * common datasource 전용 EMF(commonEntityManagerFactory)에 바인딩된다.
 */
@Getter
@Entity
@Table(name = "common_transaction")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class CommonTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "transaction_no", length = 50)
    private String transactionNo;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "contract_id")
    private Long contractId;

    @Column(name = "transaction_type_cd", length = 30)
    private String transactionTypeCd;

    @Column(name = "debit_credit_type", length = 10)
    private String debitCreditType;

    @Column(name = "transaction_amount")
    private Long transactionAmount;

    @Column(name = "balance_before")
    private Long balanceBefore;

    @Column(name = "balance_after")
    private Long balanceAfter;

    @Column(name = "fee_amount")
    private Long feeAmount;

    @Column(name = "channel_cd", length = 30)
    private String channelCd;

    @Column(name = "counterparty_bank_cd", length = 10)
    private String counterpartyBankCd;

    @Column(name = "counterparty_bank_name", length = 100)
    private String counterpartyBankName;

    @Column(name = "counterparty_account_no", length = 30)
    private String counterpartyAccountNo;

    @Column(name = "counterparty_name", length = 100)
    private String counterpartyName;

    @Column(name = "counterparty_customer_id")
    private Long counterpartyCustomerId;

    @Column(name = "counterparty_account_id")
    private Long counterpartyAccountId;

    @Column(name = "counterparty_name_verified_yn")
    private Boolean counterpartyNameVerifiedYn;

    @Column(name = "original_transaction_id")
    private Long originalTransactionId;

    @Column(name = "transaction_memo", length = 255)
    private String transactionMemo;

    @Column(name = "transaction_status", length = 20)
    private String transactionStatus;

    @Column(name = "transacted_at")
    private OffsetDateTime transactedAt;

    @Column(name = "currency_cd", length = 3)
    private String currencyCd;

    @Column(name = "available_balance")
    private Long availableBalance;

    @Column(name = "transaction_summary", length = 100)
    private String transactionSummary;

    @Column(name = "transfer_type_cd", length = 30)
    private String transferTypeCd;

    @Column(name = "transfer_requested_at")
    private OffsetDateTime transferRequestedAt;

    @Column(name = "transfer_completed_at")
    private OffsetDateTime transferCompletedAt;

    @Column(name = "transfer_failed_yn")
    private Boolean transferFailedYn;

    @Column(name = "payment_method_code", length = 30)
    private String paymentMethodCode;

    @Column(name = "card_payment_yn")
    private Boolean cardPaymentYn;

    @Column(name = "payment_failed_yn")
    private Boolean paymentFailedYn;

    @Column(name = "merchant_no", length = 50)
    private String merchantNo;

    @Column(name = "merchant_name", length = 100)
    private String merchantName;

    @Column(name = "failure_type_cd", length = 30)
    private String failureTypeCd;

    @Column(name = "failure_reason_cd", length = 50)
    private String failureReasonCd;

    @Column(name = "failure_cause_cd", length = 50)
    private String failureCauseCd;

    @Column(name = "failed_at")
    private OffsetDateTime failedAt;

    @Column(name = "retry_count")
    private Integer retryCount;

    @Column(name = "approval_no", length = 50)
    private String approvalNo;

    @Column(name = "external_transaction_no", length = 100)
    private String externalTransactionNo;

    @Column(name = "terminal_id", length = 50)
    private String terminalId;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "transaction_location", length = 100)
    private String transactionLocation;

    @Column(name = "ledger_posted_at")
    private OffsetDateTime ledgerPostedAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }
}
