package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Department;
import com.bank.deposit.domain.enums.DepartmentType;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.DepartmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DepartmentService {

    private final DepartmentRepository departmentRepository;

    public List<Department> findAll() {
        return departmentRepository.findAll();
    }

    public Department findById(Long id) {
        return departmentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "부서를 찾을 수 없습니다."));
    }

    @Transactional
    public Department create(String departmentCode, String departmentName, DepartmentType departmentType, Long parentDepartmentId) {
        if (departmentRepository.existsByDepartmentCode(departmentCode)) {
            throw new BusinessException(ErrorCode.DUPLICATE, "이미 존재하는 부서 코드입니다.");
        }
        return departmentRepository.save(Department.builder()
                .departmentCode(departmentCode)
                .departmentName(departmentName)
                .departmentType(departmentType)
                .parentDepartmentId(parentDepartmentId)
                .build());
    }

    @Transactional
    public Department update(Long id, String departmentName, DepartmentType departmentType) {
        Department department = findById(id);
        department.update(departmentName, departmentType);
        return department;
    }

    @Transactional
    public void deactivate(Long id) {
        Department department = findById(id);
        department.deactivate();
    }
}
