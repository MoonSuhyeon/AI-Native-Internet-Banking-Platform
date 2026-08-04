package com.bank.loan.autodebit;

import com.bank.loan.autodebit.domain.AutoDebitClearingPending;
import com.bank.loan.autodebit.repository.AutoDebitClearingPendingRepository;
import com.bank.loan.autodebit.service.ClearingResultService;
import com.bank.loan.repayment.domain.RepaymentTransaction;
import com.bank.loan.repayment.dto.RepayInstallmentRequest;
import com.bank.loan.repayment.service.RepaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClearingResultServiceTest {

    @Mock AutoDebitClearingPendingRepository pendingRepository;
    @Mock RepaymentService repaymentService;
    @InjectMocks ClearingResultService service;

    private AutoDebitClearingPending pending(String piId) {
        return AutoDebitClearingPending.of(piId, 100L, 200L, 3, "20260601",
                "AUTO-100-200-20260601");
    }

    @Test
    void 완결이벤트_상환완결_및_대기해소_DONE() {
        AutoDebitClearingPending p = pending("PI-1");
        when(pendingRepository.findByPiId("PI-1")).thenReturn(Optional.of(p));

        service.handle("PI-1", true);

        ArgumentCaptor<RepayInstallmentRequest> reqCap = ArgumentCaptor.forClass(RepayInstallmentRequest.class);
        verify(repaymentService).repayInstallment(eq(100L), reqCap.capture(),
                eq("AUTO-100-200-20260601"), isNull(), eq("PI-1"));
        RepayInstallmentRequest req = reqCap.getValue();
        assertThat(req.installmentNo()).isEqualTo(3);
        assertThat(req.channelCd()).isEqualTo("AUTO_DEBIT");
        assertThat(req.valueDate()).isEqualTo("20260601");
        assertThat(p.getStatus()).isEqualTo(AutoDebitClearingPending.STATUS_DONE);
    }

    @Test
    void 실패이벤트_STATUS_FAILED_기록_및_대기해소_FAILED() {
        AutoDebitClearingPending p = pending("PI-2");
        when(pendingRepository.findByPiId("PI-2")).thenReturn(Optional.of(p));

        service.handle("PI-2", false);

        // FAILED 기록은 배치 멱등키와 충돌하지 않도록 -FAIL 네임스페이스를 쓴다
        verify(repaymentService).repayInstallment(eq(100L), any(),
                eq("AUTO-100-200-20260601-FAIL"), eq(RepaymentTransaction.STATUS_FAILED), eq("PI-2"));
        assertThat(p.getStatus()).isEqualTo(AutoDebitClearingPending.STATUS_FAILED);
    }

    @Test
    void 대기건없음_상환미호출() {
        when(pendingRepository.findByPiId("PI-X")).thenReturn(Optional.empty());

        service.handle("PI-X", true);

        verify(repaymentService, never()).repayInstallment(any(), any(), any(), any(), any());
    }

    @Test
    void 이미해소된_대기건_중복이벤트_무시() {
        AutoDebitClearingPending p = pending("PI-3");
        p.resolve(true); // 이미 DONE
        when(pendingRepository.findByPiId("PI-3")).thenReturn(Optional.of(p));

        service.handle("PI-3", true);

        verify(repaymentService, never()).repayInstallment(any(), any(), any(), any(), any());
    }
}
