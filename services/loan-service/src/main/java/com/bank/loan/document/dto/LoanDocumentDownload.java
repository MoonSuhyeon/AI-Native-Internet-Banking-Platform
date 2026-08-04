package com.bank.loan.document.dto;

/**
 * 서류 다운로드 응답 본체.
 *
 * <p>메타데이터 DTO({@code LoanDocumentResponse})와 달리 원본 바이트를 실어 나른다.
 * mimeType 은 업로드 시점에 받은 값이며, 비어 있으면 컨트롤러가 옥텟 스트림으로 내린다.
 */
public record LoanDocumentDownload(
        String fileName,
        String mimeType,
        byte[] content
) {
}
