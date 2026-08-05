package com.bank.deposit.service;

import com.bank.deposit.domain.entity.SubscriptionPaymentRecognitionHistory;
import com.bank.deposit.domain.enums.RecognitionStatus;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.repository.SubscriptionPaymentRecognitionHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
@DisplayName("SubscriptionPaymentRecognitionHistoryService")
class SubscriptionPaymentRecognitionHistoryServiceTest {

    @InjectMocks
    private SubscriptionPaymentRecognitionHistoryService service;

    @Mock
    private SubscriptionPaymentRecognitionHistoryRepository repository;

    @Test
    @DisplayName("계약 ID로 전체 납입 인정 이력을 조회한다")
    void findByContractId() {
        given(repository.findByContractId(1L)).willReturn(List.of(history(1L, RecognitionStatus.RECOGNIZED)));

        List<SubscriptionPaymentRecognitionHistory> result = service.findByContractId(1L);

        assertThat(result).hasSize(1);
        then(repository).should().findByContractId(1L);
    }

    @Test
    @DisplayName("계약 ID와 상태로 납입 인정 이력을 조회한다")
    void findByContractIdAndStatus() {
        given(repository.findByContractIdAndRecognitionStatus(1L, RecognitionStatus.PENDING))
                .willReturn(List.of(history(1L, RecognitionStatus.PENDING)));

        List<SubscriptionPaymentRecognitionHistory> result =
                service.findByContractIdAndStatus(1L, RecognitionStatus.PENDING);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRecognitionStatus()).isEqualTo(RecognitionStatus.PENDING);
    }

    @Test
    @DisplayName("존재하는 이력을 단건 조회한다")
    void findById() {
        given(repository.findById(1L)).willReturn(Optional.of(history(1L, RecognitionStatus.RECOGNIZED)));

        SubscriptionPaymentRecognitionHistory result = service.findById(1L);

        assertThat(result.getContractId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("존재하지 않는 이력 조회 시 예외가 발생한다")
    void findByIdNotFound() {
        given(repository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(99L))
                .isInstanceOf(BusinessException.class);
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private SubscriptionPaymentRecognitionHistory history(Long contractId, RecognitionStatus status) {
        return SubscriptionPaymentRecognitionHistory.builder()
                .contractId(contractId)
                .paymentAmount(BigDecimal.valueOf(300_000))
                .recognizedAmount(BigDecimal.valueOf(300_000))
                .paymentMonth("202601")
                .recognitionStatus(status)
                .build();
    }
}
