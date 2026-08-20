package com.bank.customer.branch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/**
 * 지점 상담 예약.
 *
 * <p>고객번호를 받지 않는다 — 게이트웨이가 주입한 {@code X-Customer-Id} 로 정한다.
 */
public record CreateBranchReservationRequest(
        @NotNull(message = "지점을 선택해주세요.")
        Long branchId,

        @NotNull(message = "예약 일시를 선택해주세요.")
        OffsetDateTime reservedAt,

        @NotBlank(message = "상담 내용을 선택해주세요.")
        String topicCd,

        @Size(max = 500, message = "메모는 500자를 넘을 수 없습니다.")
        String memo,

        @NotBlank(message = "연락받을 번호를 입력해주세요.")
        String contactPhone
) {
}
