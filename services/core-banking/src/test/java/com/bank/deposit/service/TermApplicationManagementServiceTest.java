package com.bank.deposit.service;

import com.bank.deposit.domain.entity.TermApplicationManagement;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.repository.TermApplicationManagementRepository;
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
@DisplayName("TermApplicationManagementService")
class TermApplicationManagementServiceTest {

    @InjectMocks
    private TermApplicationManagementService service;

    @Mock
    private TermApplicationManagementRepository repository;

    @Test
    @DisplayName("전체 약관 적용 목록을 조회한다")
    void findAll() {
        given(repository.findAll()).willReturn(List.of(entity("DEPOSIT")));

        List<TermApplicationManagement> result = service.findAll();

        assertThat(result).hasSize(1);
        then(repository).should().findAll();
    }

    @Test
    @DisplayName("업무 구분 코드로 조회한다")
    void findByBusinessTypeCode() {
        given(repository.findByBusinessTypeCode("SAVINGS")).willReturn(List.of(entity("SAVINGS")));

        List<TermApplicationManagement> result = service.findByBusinessTypeCode("SAVINGS");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getBusinessTypeCode()).isEqualTo("SAVINGS");
    }

    @Test
    @DisplayName("공통 약관 ID로 조회한다")
    void findByCommonTermId() {
        given(repository.findByCommonTermId(100L)).willReturn(List.of(entity("DEPOSIT")));

        List<TermApplicationManagement> result = service.findByCommonTermId(100L);

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("필수 여부로 조회한다")
    void findByIsRequired() {
        given(repository.findByIsRequired("Y")).willReturn(List.of(entity("DEPOSIT")));

        List<TermApplicationManagement> result = service.findByIsRequired("Y");

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("존재하지 않는 ID 조회 시 예외가 발생한다")
    void findByIdNotFound() {
        given(repository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(99L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("약관 적용을 등록한다")
    void create() {
        TermApplicationManagement entity = entity("DEPOSIT");
        given(repository.save(any(TermApplicationManagement.class))).willReturn(entity);

        TermApplicationManagement result = service.create(entity);

        assertThat(result.getBusinessTypeCode()).isEqualTo("DEPOSIT");
    }

    @Test
    @DisplayName("약관 적용을 삭제한다")
    void delete() {
        TermApplicationManagement entity = entity("DEPOSIT");
        given(repository.findById(1L)).willReturn(Optional.of(entity));

        service.delete(1L);

        then(repository).should().delete(entity);
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private TermApplicationManagement entity(String businessTypeCode) {
        return TermApplicationManagement.builder()
                .commonTermId(100L)
                .termTargetId(200L)
                .businessTypeCode(businessTypeCode)
                .isRequired("N")
                .registeredAt("20260101")
                .build();
    }
}
