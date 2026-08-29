package com.bank.customer.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 한 화면에서 받은 동의를 한 번에 기록한다.
 *
 * <p>항목을 하나씩 부르지 않는 이유는, 중간에 실패하면 <b>일부만 동의한 상태</b>가
 * 남기 때문이다. 화면에서는 한 번의 행위였는데 기록은 반만 남는 것이라, 나중에
 * 무엇에 동의했는지 말할 수 없게 된다. 한 트랜잭션으로 묶는다.
 */
public record ConsentRecordRequest(
        /** 어느 업무의 동의인가 (CERT · BANKING · LOAN · MARKETING). */
        @NotBlank String bizDivCd,
        /** 어느 건에 대한 동의인가. 없으면 업무 전반에 대한 동의다. */
        Long consentTargetId,
        /** 어떻게 받았는가 (WEB · MOBILE · BRANCH). */
        @NotBlank String consentMethodCd,
        /** 무엇으로 받았는가. 브라우저·앱 버전 등. */
        String consentTool,
        @NotEmpty List<Item> items
) {
    /**
     * 항목 하나.
     *
     * <p>{@code agreed} 가 거짓이어도 기록한다. "안 받았다" 와 "거절했다" 는 다른
     * 사실이고, 선택 약관은 거절이 정상적인 결과다.
     */
    public record Item(
            @NotBlank String termsNo,
            @NotNull Boolean agreed
    ) {}
}
