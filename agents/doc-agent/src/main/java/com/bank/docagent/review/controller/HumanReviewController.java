package com.bank.docagent.review.controller;

import com.bank.docagent.review.service.HumanReviewService;
import com.bank.docagent.submission.domain.DocumentSubmission;
import com.bank.docagent.submission.domain.DocumentSubmission.HumanReviewStatus;
import com.bank.docagent.submission.repository.DocumentSubmissionRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Tag(name = "심사원 검토", description = "HOLD 건 위변조 확정/해제 — 사람만 호출 가능")
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class HumanReviewController {

    private final HumanReviewService reviewService;
    private final DocumentSubmissionRepository submissionRepo;

    /**
     * 두 필드 모두 필수다.
     *
     * <p>심사원 없이 결정이 남으면 위조를 누가 확정했는지 추적할 수 없다. 예전에는
     * 검증이 없어 {@code reviewer_id} 를 빼고 부르면 결정이 먼저 반영되고 나서
     * 응답을 만들다 NPE 로 500 이 났다 — 호출자는 실패로 보는데 상태는 바뀌어 있었다.
     */
    public record ReviewRequest(
        @NotNull(message = "decision 은 필수입니다 (CLEARED | CONFIRMED_FORGERY)")
        @JsonProperty("decision")   HumanReviewStatus decision,

        @NotBlank(message = "reviewer_id 는 필수입니다 — 결정 주체가 남아야 합니다")
        @JsonProperty("reviewer_id") String reviewerId
    ) {}

    @Operation(summary = "휴먼리뷰 대기 목록",
               description = "humanReviewStatus=PENDING 인 제출 건 목록. 운영자 검토 큐.")
    @GetMapping("/queue")
    public ResponseEntity<List<Map<String, Object>>> queue() {
        // TODO: PENDING 전체를 한 번에 반환한다. 데모 단계라 무방하나
        //       운영에서 건수가 늘면 Pageable 을 받아 페이지 단위로 반환하도록 전환 필요.
        List<Map<String, Object>> items = submissionRepo
            .findByHumanReviewStatus(HumanReviewStatus.PENDING, Sort.by(Sort.Direction.ASC, "createdAt"))
            .stream()
            .map(s -> Map.<String, Object>of(
                "submission_id",       s.getSubmissionId().toString(),
                "application_id",      s.getApplicationId(),
                "doc_code",            s.getDocCode(),
                "verify_status",       s.getVerifyStatus().name(),
                "human_review_status", s.getHumanReviewStatus().name(),
                "forgery_score",       s.getForgeryScore() != null ? s.getForgeryScore() : "-",
                "legal_hold",          s.isLegalHold(),
                "created_at",          s.getCreatedAt().toString()
            ))
            .collect(Collectors.toList());
        return ResponseEntity.ok(items);
    }

    @Operation(summary = "심사원 위변조 확정/해제 (HOLD 건 전용)",
               description = "자동 해제 불가. CLEARED=통과, CONFIRMED_FORGERY=위변조 확정 후 감사팀 이관")
    @PostMapping("/{submissionId}/review")
    public ResponseEntity<Map<String, Object>> decide(
        @PathVariable UUID submissionId,
        @Valid @RequestBody ReviewRequest req
    ) {
        DocumentSubmission result = reviewService.decide(submissionId, req.decision(), req.reviewerId());
        return ResponseEntity.ok(Map.of(
            "submission_id",       result.getSubmissionId(),
            "verify_status",       result.getVerifyStatus(),
            "human_review_status", result.getHumanReviewStatus(),
            "reviewer_id",         result.getReviewerId()
        ));
    }
}
