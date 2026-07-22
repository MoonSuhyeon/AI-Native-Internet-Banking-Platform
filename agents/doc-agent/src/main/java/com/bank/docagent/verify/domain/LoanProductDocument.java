package com.bank.docagent.verify.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Entity
@Table(name = "loan_product_documents")
@IdClass(LoanProductDocument.LoanProductDocumentId.class)
@Getter
@NoArgsConstructor
public class LoanProductDocument {

    @Id
    @Column(name = "product_id")
    private String productId;

    @Id
    @Column(name = "req_doc_code")
    private String reqDocCode;

    @Column(name = "product_name")
    private String productName;

    @Column(name = "req_doc_name")
    private String reqDocName;

    @Column(name = "is_essential")
    private boolean essential;

    @Column(name = "valid_days")
    private Integer validDays;   // null = 만료 없음

    @Column(name = "accepted_formats")
    private String acceptedFormats;

    @Column(name = "min_dpi")
    private int minDpi;

    @Column(name = "issuer_type")
    private String issuerType;

    @Column(name = "auto_verify_enabled")
    private boolean autoVerifyEnabled;

    @Column(name = "retention_days")
    private Integer retentionDays;

    @Getter
    public static class LoanProductDocumentId implements Serializable {
        private String productId;
        private String reqDocCode;

        public LoanProductDocumentId() {}

        public LoanProductDocumentId(String productId, String reqDocCode) {
            this.productId = productId;
            this.reqDocCode = reqDocCode;
        }
    }
}
