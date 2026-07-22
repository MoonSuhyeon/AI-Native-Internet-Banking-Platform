package com.bank.ai.drift;

public record PsiBaseline(
    String featureName,
    int bucketIndex,
    double bucketLow,
    double bucketHigh,
    double baselineRatio
) {}
