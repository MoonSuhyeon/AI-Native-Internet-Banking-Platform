package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.TargetGroup;
import com.bank.deposit.dto.request.TargetGroupCreateRequest;
import com.bank.deposit.service.TargetGroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/target-groups")
@RequiredArgsConstructor
public class TargetGroupController {

    private final TargetGroupService targetGroupService;

    @GetMapping
    public List<TargetGroup> list(@RequestParam(required = false) Boolean isActive) {
        return targetGroupService.findAll(isActive);
    }

    @PostMapping
    public ResponseEntity<TargetGroup> create(@Valid @RequestBody TargetGroupCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(targetGroupService.create(req.targetGroupName(), req.description()));
    }

    @PutMapping("/{id}")
    public TargetGroup update(@PathVariable Long id, @Valid @RequestBody TargetGroupCreateRequest req) {
        return targetGroupService.update(id, req.targetGroupName(), req.description());
    }
}
