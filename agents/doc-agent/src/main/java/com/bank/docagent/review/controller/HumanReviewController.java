package com.bank.docagent.review.controller;

import com.bank.docagent.review.service.HumanReviewService;
import com.bank.docagent.submission.domain.DocumentSubmission;
import com.bank.docagent.submission.domain.DocumentSubmission.HumanReviewStatus;
import com.bank.docagent.submission.repository.DocumentSubmissionRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    public record ReviewRequest(
        @JsonProperty("decision")   HumanReviewStatus decision,    // CLEARED | CONFIRMED_FORGERY
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
        @RequestBody  ReviewRequest req
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
