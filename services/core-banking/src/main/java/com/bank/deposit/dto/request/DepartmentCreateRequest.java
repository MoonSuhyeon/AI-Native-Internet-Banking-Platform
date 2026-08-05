package com.bank.deposit.dto.request;

import com.bank.deposit.domain.enums.DepartmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DepartmentCreateRequest(
        @NotBlank String departmentCode,
        @NotBlank String departmentName,
        @NotNull DepartmentType departmentType,
        Long parentDepartmentId
) {}
