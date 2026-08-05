package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.TermApplicationManagement;
import com.bank.deposit.dto.request.TermApplicationManagementRequest;
import com.bank.deposit.service.TermApplicationManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/term-applications")
@RequiredArgsConstructor
public class TermApplicationManagementController {

    private final TermApplicationManagementService service;

    /**
     * 약관 적용 목록 조회.
     * GET /api/term-applications
     * GET /api/term-applications?businessTypeCode=DEPOSIT
     * GET /api/term-applications?commonTermId=1
     * GET /api/term-applications?isRequired=Y
     */
    @GetMapping
    public List<TermApplicationManagement> list(
            @RequestParam(required = false) String businessTypeCode,
            @RequestParam(required = false) Long commonTermId,
            @RequestParam(required = false) String isRequired) {

        if (businessTypeCode != null) {
            return service.findByBusinessTypeCode(businessTypeCode);
        }
        if (commonTermId != null) {
            return service.findByCommonTermId(commonTermId);
        }
        if (isRequired != null) {
            return service.findByIsRequired(isRequired);
        }
        return service.findAll();
    }

    /**
     * 단건 조회.
     * GET /api/term-applications/{id}
     */
    @GetMapping("/{id}")
    public TermApplicationManagement get(@PathVariable Long id) {
        return service.findById(id);
    }

    /**
     * 약관 적용 등록.
     * POST /api/term-applications
     */
    @PostMapping
    public ResponseEntity<TermApplicationManagement> create(
            @RequestBody TermApplicationManagementRequest req) {

        TermApplicationManagement entity = TermApplicationManagement.builder()
                .commonTermId(req.commonTermId())
                .termTargetId(req.termTargetId())
                .businessTypeCode(req.businessTypeCode())
                .isRequired(req.isRequired() != null ? req.isRequired() : "N")
                .registeredAt(req.registeredAt())
                .modifiedAt(req.modifiedAt())
                .build();

        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(entity));
    }

    /**
     * 약관 적용 삭제.
     * DELETE /api/term-applications/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
