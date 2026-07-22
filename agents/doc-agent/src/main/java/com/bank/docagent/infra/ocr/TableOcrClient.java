package com.bank.docagent.infra.ocr;

/**
 * PP-StructureV2 테이블 파싱 클라이언트 (등기부등본 전용).
 */
public interface TableOcrClient {
    /** @return 테이블 셀을 평탄화한 텍스트. 실패 시 빈 문자열. */
    String extractTable(byte[] imageBytes, String submissionId);
}
