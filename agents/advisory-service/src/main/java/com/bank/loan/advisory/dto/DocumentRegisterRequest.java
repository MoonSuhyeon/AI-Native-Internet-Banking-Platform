package com.bank.loan.advisory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 정책문서 등록 요청 (plan §11.5 — POST /internal/advisory/documents).
 *
 * content 가 제공되면 sourceUri 를 fetch 하지 않고 직접 청크 분할·임베딩·적재한다
 * (개발·테스트 편의 / 비공개 문서 직접 제공).
 * content 를 생략하면 sourceUri fetch 가 필요하나 현 단계 미구현.
 */
public record DocumentRegisterRequest(
        @NotBlank @Size(max = 50)  String docCd,
        @NotBlank @Size(max = 500) String docTitle,
        @NotBlank @Size(max = 50)  String docCategoryCd,
        @NotBlank @Size(max = 50)  String docVersion,
        @Pattern(regexp = "\\d{8}") String effectiveStartDate,
        @Pattern(regexp = "\\d{8}") String effectiveEndDate,
        @Size(max = 500) String sourceUri,
        @Size(max = 500) String docDesc,
        /** 문서 본문 직접 제공 (null 이면 sourceUri fetch, 현재는 필수). */
        String content
) {}
