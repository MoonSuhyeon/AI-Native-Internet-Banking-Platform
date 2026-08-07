package com.bank.loan.creditscore.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 한도조회(가심사 preview) 요청 — 신청 전 단계 화면 A-2.
 *
 * 신청서 row 를 만들지 않고 외부 신용평가 엔진만 호출해 예상 한도/금리/점수를 회신한다.
 * 1회성 신용조회 동의(consentYn=Y) 가 필수 — 미동의 시 400.
 *
 * loanTypeCd 는 화면에서 선택한 상품 유형(CREDIT/MORTGAGE) — 상품 확정 전이라 prodId 대신 유형만 받는다.
 */
public record CreditScorePreviewRequest(

        @NotNull Long customerId,

        @NotBlank @Size(max = 50) String loanTypeCd,

        @NotNull @Min(1) Long requestedAmount,
        @NotNull @Min(1) Integer requestedPeriodMo,

        @Size(max = 50) String loanPurposeCd,
        @Size(max = 50) String employmentTypeCd,

        @Min(0) Long estimatedIncomeAmt,

        // 동의는 "값이 Y" 가 아니라 "참" 이어야 한다.
        // @AssertTrue 는 null 을 통과시키므로 @NotNull 을 함께 둔다 — 누락도 400 이어야 한다.
        @NotNull @AssertTrue(message = "신용조회 동의가 필요합니다.")
        Boolean consentYn
) {
}
