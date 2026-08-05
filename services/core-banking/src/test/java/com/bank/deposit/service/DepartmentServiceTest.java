package com.bank.deposit.service;

import com.bank.deposit.domain.entity.Department;
import com.bank.deposit.domain.enums.DepartmentType;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.repository.DepartmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("DepartmentService")
class DepartmentServiceTest {

    @InjectMocks
    private DepartmentService service;

    @Mock
    private DepartmentRepository repository;

    @Test
    @DisplayName("전체 부서 목록을 조회한다")
    void findAll() {
        given(repository.findAll()).willReturn(List.of(department("DEP")));

        List<Department> result = service.findAll();

        assertThat(result).hasSize(1);
        then(repository).should().findAll();
    }

    @Test
    @DisplayName("존재하는 부서를 조회한다")
    void findById() {
        given(repository.findById(1L)).willReturn(Optional.of(department("DEP")));

        Department result = service.findById(1L);

        assertThat(result.getDepartmentCode()).isEqualTo("DEP");
    }

    @Test
    @DisplayName("존재하지 않는 부서는 예외가 발생한다")
    void findByIdNotFound() {
        given(repository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("중복 코드가 아니면 부서를 생성한다")
    void create() {
        given(repository.existsByDepartmentCode("DEP")).willReturn(false);
        given(repository.save(any(Department.class))).willAnswer(inv -> inv.getArgument(0));

        Department result = service.create("DEP", "수신상품부", DepartmentType.PRODUCT, null);

        assertThat(result.getDepartmentCode()).isEqualTo("DEP");
        assertThat(result.getDepartmentName()).isEqualTo("수신상품부");
        assertThat(result.getDepartmentType()).isEqualTo(DepartmentType.PRODUCT);
    }

    @Test
    @DisplayName("중복 부서 코드면 생성할 수 없다")
    void createDuplicateCode() {
        given(repository.existsByDepartmentCode("DEP")).willReturn(true);

        assertThatThrownBy(() -> service.create("DEP", "수신상품부", DepartmentType.PRODUCT, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("부서명을 수정한다")
    void update() {
        Department department = department("DEP");
        given(repository.findById(1L)).willReturn(Optional.of(department));

        Department result = service.update(1L, "수신운영부", DepartmentType.OPERATION);

        assertThat(result.getDepartmentName()).isEqualTo("수신운영부");
        assertThat(result.getDepartmentType()).isEqualTo(DepartmentType.OPERATION);
    }

    @Test
    @DisplayName("부서를 비활성화한다")
    void deactivate() {
        Department department = department("DEP");
        given(repository.findById(1L)).willReturn(Optional.of(department));

        service.deactivate(1L);

        assertThat(department.getIsActive()).isFalse();
    }

    private Department department(String code) {
        return Department.builder()
                .departmentCode(code)
                .departmentName("수신상품부")
                .departmentType(DepartmentType.PRODUCT)
                .build();
    }
}
