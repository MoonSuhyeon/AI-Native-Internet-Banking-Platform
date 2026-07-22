package com.bank.docagent.forgery.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "loan_forgery_signal")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class ForgerySignalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "signal_id")
    private Long signalId;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @Column(name = "category", nullable = false)
    private String category;   // META | VISUAL | SEMANTIC | EXTERNAL

    @Column(name = "signal_type", nullable = false)
    private String signalType;

    @Column(name = "score", nullable = false)
    private double score;

    @Column(name = "evidence", columnDefinition = "jsonb")
    private String evidence;   // JSON 문자열

    @Column(name = "detected_at", nullable = false)
    @Builder.Default
    private LocalDateTime detectedAt = LocalDateTime.now();
}
