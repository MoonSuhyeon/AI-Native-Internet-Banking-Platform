package com.bank.docagent.submission.dto;

import java.util.List;

public record OcrRegion(
    String text,
    double confidence,
    List<List<Integer>> bbox
) {}
