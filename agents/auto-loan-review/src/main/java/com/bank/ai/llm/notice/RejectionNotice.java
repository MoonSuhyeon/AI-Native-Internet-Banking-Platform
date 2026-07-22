package com.bank.ai.llm.notice;

import java.util.List;

/**
 * 고객향 거절 통보문 구조 — plan/llm-pipeline.md §2 (D).
 */
public record RejectionNotice(
        String title,
        String body,
        List<String> legalCitations
) {
}
