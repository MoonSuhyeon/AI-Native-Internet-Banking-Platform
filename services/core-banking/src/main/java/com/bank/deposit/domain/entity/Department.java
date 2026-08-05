package com.bank.deposit.domain.entity;

import com.bank.deposit.common.BaseEntity;
import com.bank.deposit.domain.enums.DepartmentType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "deposit_departments")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Department extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long departmentId;

    @Column(name = "department_code", length = 50, nullable = false, unique = true)
    private String departmentCode;

    @Column(name = "department_name", length = 100, nullable = false)
    private String departmentName;

    @Column(name = "parent_department_id")
    private Long parentDepartmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "department_type")
    private DepartmentType departmentType;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    public void deactivate() {
        this.isActive = false;
    }

    public void update(String departmentName, DepartmentType departmentType) {
        this.departmentName = departmentName;
        this.departmentType = departmentType;
    }
}
