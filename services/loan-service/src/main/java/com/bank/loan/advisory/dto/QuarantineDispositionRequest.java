package com.bank.loan.advisory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 격리 처분 요청.
 *
 * <p><b>행위자를 받지 않는다.</b> 누가 풀었는지는 게이트웨이가 검증한
 * {@code X-User-Id} 로만 정한다. 요청 본문에 넣게 두면 호출자가 남의 사번을 적을 수
 * 있고, 그러면 감사 기록의 "누가" 가 자칭이 된다 — 검사가 있어도 그 값이 거짓이면
 * 없는 것과 같다.
 *
 * @param disposition RELEASED · REVIEW_REASSIGNED · AUDIT_REFERRED
 * @param note        처분 사유. 격리를 푸는 판단의 근거라 비울 수 없다.
 */
public record QuarantineDispositionRequest(
        @NotBlank(message = "처분을 지정해야 한다")
        String disposition,

        @NotBlank(message = "처분 사유를 적어야 한다 — 판단의 근거가 남지 않는다")
        @Size(max = 2000, message = "처분 사유는 2000자를 넘을 수 없다")
        String note
) {
}
