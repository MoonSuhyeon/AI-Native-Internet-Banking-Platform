package com.bank.loan.advisory.dto;

import com.bank.loan.advisory.domain.AdvisoryDocument;

public record DocumentRegisterResponse(
        Long   docId,
        String docCd,
        String docTitle,
        String docVersion,
        String activeYn,
        int    chunkCount
) {
    public static DocumentRegisterResponse of(AdvisoryDocument doc, int chunkCount) {
        return new DocumentRegisterResponse(
                doc.getDocId(), doc.getDocCd(), doc.getDocTitle(),
                doc.getDocVersion(), doc.getActiveYn(), chunkCount);
    }
}
