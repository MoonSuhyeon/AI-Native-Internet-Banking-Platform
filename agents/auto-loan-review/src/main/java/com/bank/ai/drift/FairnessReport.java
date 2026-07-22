package com.bank.ai.drift;

import java.time.LocalDate;

public record FairnessReport(
    LocalDate reportMonth,
    String groupKey,
    double approvalRate,
    int sampleCount,
    double overallRate,
    double rateGap,
    boolean flagged
) {}
