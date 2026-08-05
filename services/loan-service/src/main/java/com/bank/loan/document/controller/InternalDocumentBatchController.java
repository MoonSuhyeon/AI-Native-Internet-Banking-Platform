package com.bank.loan.document.controller;

import com.bank.common.web.ApiResponse;
import com.bank.loan.document.dto.PurgeExpiredDocumentsResponse;
import com.bank.loan.document.service.LoanDocumentRetentionBatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "신청서류 배치", description = "InternalDocumentBatch - 서류 관련 운영 배치 endpoint")
@RestController
@RequestMapping("/api/internal/loan-documents")
@RequiredArgsConstructor
@Validated
public class InternalDocumentBatchController {

    private final LoanDocumentRetentionBatchService retentionBatchService;

    @Operation(summary = "보존기한 경과 서류 원본 파기",
            description = "retention_until 이 오늘 이전이면서 아직 파기되지 않은 서류의 원본 파일을 삭제하고 "
                    + "purged_at 을 기록한다. 서류 row(이름·해시·크기·파기시각)는 남긴다 — "
                    + "무엇을 언제 파기했는지가 증빙이기 때문이다. "
                    + "soft delete 된 서류도 대상에 포함한다. "
                    + "건별 독립 트랜잭션이라 일부 실패해도 나머지는 진행되며, 실패분은 다음 실행에서 재시도된다.")
    @PostMapping("/purge-expired")
    public ApiResponse<PurgeExpiredDocumentsResponse> purgeExpired(
            @RequestParam(defaultValue = "500") @Min(1) @Max(5000) int batchSize) {
        return ApiResponse.ok(retentionBatchService.purgeExpired(batchSize));
    }
}
