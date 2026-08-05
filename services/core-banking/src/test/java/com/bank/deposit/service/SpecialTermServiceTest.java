package com.bank.deposit.service;

import com.bank.deposit.domain.entity.SpecialTerm;
import com.bank.deposit.domain.enums.SpecialTermStatus;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.repository.SpecialTermRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("SpecialTermService")
class SpecialTermServiceTest {

    private SpecialTermService specialTermService;

    @Mock
    private SpecialTermRepository specialTermRepository;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        specialTermService = new SpecialTermService(specialTermRepository, clock);
    }

    @Nested
    @DisplayName("특약 목록 조회")
    class FindAll {

        @Test
        @DisplayName("필터가 없으면 전체 특약을 조회한다")
        void findAllNoFilter() {
            given(specialTermRepository.findAll()).willReturn(List.of(term("개인정보 수집 동의", "1.0")));

            List<SpecialTerm> result = specialTermService.findAll(null);

            assertThat(result).hasSize(1);
            then(specialTermRepository).should().findAll();
        }

        @Test
        @DisplayName("활성 여부 true 로 조회하면 ACTIVE 특약만 반환된다")
        void findAllActive() {
            given(specialTermRepository.findByStatus(SpecialTermStatus.ACTIVE))
                    .willReturn(List.of(term("개인정보 수집 동의", "1.0")));

            List<SpecialTerm> result = specialTermService.findAll(true);

            assertThat(result).hasSize(1);
            then(specialTermRepository).should().findByStatus(SpecialTermStatus.ACTIVE);
        }

        @Test
        @DisplayName("활성 여부 false 로 조회하면 INACTIVE 특약만 반환된다")
        void findAllInactive() {
            given(specialTermRepository.findByStatus(SpecialTermStatus.INACTIVE))
                    .willReturn(List.of());

            List<SpecialTerm> result = specialTermService.findAll(false);

            assertThat(result).isEmpty();
            then(specialTermRepository).should().findByStatus(SpecialTermStatus.INACTIVE);
        }
    }

    @Nested
    @DisplayName("특약 단건 조회")
    class FindById {

        @Test
        @DisplayName("존재하는 특약을 조회한다")
        void findById() {
            given(specialTermRepository.findById(1L))
                    .willReturn(Optional.of(term("개인정보 수집 동의", "1.0")));

            SpecialTerm result = specialTermService.findById(1L);

            assertThat(result.getSpecialTermName()).isEqualTo("개인정보 수집 동의");
        }

        @Test
        @DisplayName("존재하지 않는 특약이면 예외가 발생한다")
        void findByIdNotFound() {
            given(specialTermRepository.findById(1L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> specialTermService.findById(1L))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("특약 생성")
    class Create {

        @Test
        @DisplayName("특약을 생성하면 기본 상태가 ACTIVE다")
        void create() {
            given(specialTermRepository.save(any(SpecialTerm.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            SpecialTerm result = specialTermService.create(
                    "개인정보 수집 동의", "개인정보를 수집합니다.", "요약본",
                    true, "1.0", "20260101", null);

            assertThat(result.getSpecialTermName()).isEqualTo("개인정보 수집 동의");
            assertThat(result.getIsRequired()).isTrue();
            assertThat(result.getStatus()).isEqualTo(SpecialTermStatus.ACTIVE);
        }

        @Test
        @DisplayName("isRequired null이면 false로 처리된다")
        void createWithNullRequired() {
            given(specialTermRepository.save(any(SpecialTerm.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            SpecialTerm result = specialTermService.create(
                    "마케팅 수신 동의", "마케팅 정보를 수신합니다.", null,
                    null, "1.0", null, null);

            assertThat(result.getIsRequired()).isFalse();
        }
    }

    @Test
    @DisplayName("특약 상태를 INACTIVE로 변경한다")
    void changeStatus() {
        given(specialTermRepository.findById(1L))
                .willReturn(Optional.of(term("개인정보 수집 동의", "1.0")));

        SpecialTerm result = specialTermService.changeStatus(1L, SpecialTermStatus.INACTIVE);

        assertThat(result.getStatus()).isEqualTo(SpecialTermStatus.INACTIVE);
        assertThat(result.getStatusChangedAt()).isEqualTo("20260101");
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private SpecialTerm term(String name, String version) {
        return SpecialTerm.builder()
                .specialTermName(name)
                .specialTermContent("특약 전문 내용입니다.")
                .specialTermVersion(version)
                .build();
    }
}
