package com.bank.aigateway.audit.dto;

public record SignalSummary(
        String ruleCd,
        String severityCd,
        String signalMetric,
        double observedValue,
        double thresholdValue
) {}
