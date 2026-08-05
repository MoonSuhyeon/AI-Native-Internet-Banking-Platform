package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.TargetGroup;
import com.bank.deposit.dto.request.TargetGroupCreateRequest;
import com.bank.deposit.service.TargetGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/join-targets")
@RequiredArgsConstructor
public class JoinTargetController {

    private final TargetGroupService targetGroupService;

    @GetMapping
    public List<TargetGroup> list() {
        return targetGroupService.findAll(null);
    }

    @PostMapping
    public ResponseEntity<TargetGroup> create(@RequestBody TargetGroupCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(targetGroupService.create(req.targetGroupName(), req.description()));
    }
}
