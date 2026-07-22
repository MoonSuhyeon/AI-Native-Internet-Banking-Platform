package com.bank.ai.drift;

public record PsiDriftReport(
    String featureName,
    double psiValue,
    PsiStatus status,
    int sampleCount,
    String modelVersion
) {}
