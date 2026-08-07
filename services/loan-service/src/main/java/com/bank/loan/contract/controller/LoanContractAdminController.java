package com.bank.loan.contract.controller;

import com.bank.common.web.ApiResponse;
import com.bank.loan.contract.dto.LoanContractAdminListResponse;
import com.bank.loan.contract.dto.LoanContractResponse;
import com.bank.loan.contract.service.LoanContractService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "대출계약-운영자", description = "운영자 전용 계약 목록 모니터링")
@RestController
@RequestMapping("/api/admin/loan-contracts")
@RequiredArgsConstructor
public class LoanContractAdminController {

    private final LoanContractService service;

    @Operation(summary = "운영자 계약 목록 조회",
               description = "전체 계약을 상태·시작일 범위로 필터링해 페이지 단위로 반환.")
    @GetMapping
    public ApiResponse<LoanContractAdminListResponse> list(
            @RequestParam(required = false) String cntrStatusCd,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        Page<LoanContractResponse> result =
                service.listForAdmin(cntrStatusCd, dateFrom, dateTo, page, size);

        return ApiResponse.ok(LoanContractAdminListResponse.of(result));
    }
}
