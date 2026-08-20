package com.bank.customer.branch;

import com.bank.common.web.ApiResponse;
import com.bank.customer.branch.dto.BranchReservationResponse;
import com.bank.customer.branch.dto.BranchResponse;
import com.bank.customer.branch.dto.CreateBranchReservationRequest;
import com.bank.customer.branch.service.BranchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 영업점과 지점 상담 예약.
 *
 * <p>영업점 목록은 누구나 본다 — 지점 위치는 공개 정보이고, 로그인해야 지점을 찾을
 * 수 있게 하면 찾아갈 곳을 알아보려는 사람을 막는다. 예약은 본인만 한다.
 */
@RestController
@RequestMapping("/api/v1/branches")
@RequiredArgsConstructor
public class BranchController {

    private final BranchService branchService;

    /** 지점검색. 지점명·지역 어느 쪽으로 쳐도 찾는다. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<BranchResponse>>> search(
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(ApiResponse.ok(branchService.search(keyword)));
    }

    @PostMapping("/reservations")
    public ResponseEntity<ApiResponse<BranchReservationResponse>> reserve(
            @RequestHeader("X-Customer-Id") Long customerId,
            @Valid @RequestBody CreateBranchReservationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(branchService.reserve(customerId, request)));
    }

    @GetMapping("/reservations")
    public ResponseEntity<ApiResponse<List<BranchReservationResponse>>> myReservations(
            @RequestHeader("X-Customer-Id") Long customerId) {
        return ResponseEntity.ok(ApiResponse.ok(branchService.myReservations(customerId)));
    }

    @DeleteMapping("/reservations/{reservationId}")
    public ResponseEntity<ApiResponse<Void>> cancel(
            @RequestHeader("X-Customer-Id") Long customerId,
            @PathVariable Long reservationId) {
        branchService.cancel(customerId, reservationId);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
