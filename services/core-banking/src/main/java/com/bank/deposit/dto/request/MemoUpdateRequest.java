package com.bank.deposit.dto.request;

import jakarta.validation.constraints.Size;

/**
 * 개인 메모 수정.
 *
 * <p>내용을 비우면 메모를 지운 것으로 본다. 화면에서 다 지우고 저장하는 것이
 * "메모 삭제" 라서 별도 삭제 API 를 두지 않는다.
 */
public record MemoUpdateRequest(
        @Size(max = 255, message = "메모는 255자를 넘을 수 없습니다.")
        String memo
) {
}
