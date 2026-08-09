package com.bank.docagent.review.controller;

import com.bank.docagent.config.GatewayIdentity;
import com.bank.docagent.review.service.HumanReviewService;
import com.bank.docagent.submission.domain.DocumentSubmission;
import com.bank.docagent.submission.domain.DocumentSubmission.HumanReviewStatus;
import com.bank.docagent.submission.repository.DocumentSubmissionRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
    private final GatewayIdentity gatewayIdentity;

    /**
     * 결정만 받는다. <b>심사원은 body 로 받지 않는다.</b>
     *
     * <p>예전에는 {@code reviewer_id} 를 여기서 받았다. 필수 검증을 붙여 두긴 했지만
     * 그것은 <b>값이 있는지</b>만 보고 <b>누구인지</b>는 보지 않는다 — 아무 이름이나
     * 적으면 그대로 기록됐다.
     *
     * <p>이 값이 특히 중요한 이유는 AI 채택률 지표의 근거이기 때문이다. 지표는
     * "자동 판정을 사람이 이만큼 뒤집었다" 고 말하는데, 그 사람이 자칭이면 숫자의
     * 출처가 검증되지 않는다. 성능을 재는 축이 위조 가능한 입력 위에 서 있었다.
     *
     * <p>이제 심사원은 게이트웨이가 JWT 에서 주입한 {@code X-Employee-Id} 로만 정해진다.
     */
    public record ReviewRequest(
        @NotNull(message = "decision 은 필수입니다 (CLEARED | CONFIRMED_FORGERY)")
        @JsonProperty("decision")   HumanReviewStatus decision
    ) {}

    @Operation(summary = "휴먼리뷰 대기 목록",
               description = "humanReviewStatus=PENDING 인 제출 건 목록. 운영자 검토 큐.")
    @GetMapping("/queue")
    public ResponseEntity<List<Map<String, Object>>> queue(HttpServletRequest request) {
        // 고객이 제출한 서류와 위조 점수가 그대로 나온다. 직원만 볼 수 있어야 한다.
        gatewayIdentity.requireEmployee(request);
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
        @Valid @RequestBody ReviewRequest req,
        HttpServletRequest request
    ) {
        String reviewerId = gatewayIdentity.requireEmployee(request);
        DocumentSubmission result = reviewService.decide(submissionId, req.decision(), reviewerId);
        return ResponseEntity.ok(Map.of(
            "submission_id",       result.getSubmissionId(),
            "verify_status",       result.getVerifyStatus(),
            "human_review_status", result.getHumanReviewStatus(),
            "reviewer_id",         result.getReviewerId()
        ));
    }
}
