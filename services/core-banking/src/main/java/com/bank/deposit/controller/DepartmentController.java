package com.bank.deposit.controller;

import com.bank.deposit.domain.entity.Department;
import com.bank.deposit.dto.request.DepartmentCreateRequest;
import com.bank.deposit.service.DepartmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    @GetMapping
    public List<Department> list() {
        return departmentService.findAll();
    }

    @PostMapping
    public ResponseEntity<Department> create(@Valid @RequestBody DepartmentCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(departmentService.create(req.departmentCode(), req.departmentName(),
                        req.departmentType(), req.parentDepartmentId()));
    }

    @GetMapping("/{departmentId}")
    public Department get(@PathVariable Long departmentId) {
        return departmentService.findById(departmentId);
    }

    @PutMapping("/{departmentId}")
    public Department update(@PathVariable Long departmentId, @Valid @RequestBody DepartmentCreateRequest req) {
        return departmentService.update(departmentId, req.departmentName(), req.departmentType());
    }

    @DeleteMapping("/{departmentId}")
    public ResponseEntity<Void> deactivate(@PathVariable Long departmentId) {
        departmentService.deactivate(departmentId);
        return ResponseEntity.noContent().build();
    }
}
