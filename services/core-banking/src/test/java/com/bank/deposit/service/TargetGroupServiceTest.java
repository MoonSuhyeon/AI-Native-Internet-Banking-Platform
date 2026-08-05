package com.bank.deposit.service;

import com.bank.deposit.domain.entity.TargetGroup;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.repository.TargetGroupRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("TargetGroupService")
class TargetGroupServiceTest {

    @InjectMocks
    private TargetGroupService targetGroupService;

    @Mock
    private TargetGroupRepository targetGroupRepository;

    @Nested
    @DisplayName("목록 조회")
    class FindAll {

        @Test
        @DisplayName("활성 여부 조건이 없으면 전체 목록을 조회한다")
        void findAllWithoutFilter() {
            given(targetGroupRepository.findAll()).willReturn(List.of(targetGroup("직장인", "급여소득 고객")));

            List<TargetGroup> result = targetGroupService.findAll(null);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTargetGroupName()).isEqualTo("직장인");
            then(targetGroupRepository).should().findAll();
        }

        @Test
        @DisplayName("활성 여부 조건이 있으면 해당 상태 목록을 조회한다")
        void findAllByActiveStatus() {
            given(targetGroupRepository.findByIsActive(true))
                    .willReturn(List.of(targetGroup("직장인", "급여소득 고객")));

            List<TargetGroup> result = targetGroupService.findAll(true);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getIsActive()).isTrue();
            then(targetGroupRepository).should().findByIsActive(true);
        }
    }

    @Nested
    @DisplayName("단건 조회")
    class FindById {

        @Test
        @DisplayName("존재하는 가입 대상 그룹을 조회한다")
        void findById() {
            given(targetGroupRepository.findById(1L))
                    .willReturn(Optional.of(targetGroup("직장인", "급여소득 고객")));

            TargetGroup result = targetGroupService.findById(1L);

            assertThat(result.getTargetGroupName()).isEqualTo("직장인");
        }

        @Test
        @DisplayName("존재하지 않으면 예외가 발생한다")
        void findByIdNotFound() {
            given(targetGroupRepository.findById(1L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> targetGroupService.findById(1L))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("가입 대상 그룹을 생성한다")
    void create() {
        given(targetGroupRepository.save(org.mockito.ArgumentMatchers.any(TargetGroup.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        TargetGroup result = targetGroupService.create("직장인", "급여소득 고객");

        assertThat(result.getTargetGroupName()).isEqualTo("직장인");
        assertThat(result.getDescription()).isEqualTo("급여소득 고객");
        assertThat(result.getIsActive()).isTrue();

        ArgumentCaptor<TargetGroup> captor = ArgumentCaptor.forClass(TargetGroup.class);
        then(targetGroupRepository).should().save(captor.capture());
        assertThat(captor.getValue().getTargetGroupName()).isEqualTo("직장인");
    }

    private TargetGroup targetGroup(String name, String description) {
        return TargetGroup.builder()
                .targetGroupName(name)
                .description(description)
                .build();
    }
}
