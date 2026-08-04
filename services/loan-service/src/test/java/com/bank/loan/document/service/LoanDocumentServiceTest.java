package com.bank.loan.document.service;

import com.bank.common.audit.StatusChangeEvent;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.loan.application.domain.LoanApplication;
import com.bank.loan.application.repository.LoanApplicationRepository;
import com.bank.loan.document.docagent.DocAgentClient;
import com.bank.loan.document.docagent.DocAgentException;
import com.bank.loan.document.domain.LoanDocument;
import com.bank.loan.document.dto.LoanDocumentResponse;
import com.bank.loan.document.repository.LoanDocumentRepository;
import com.bank.loan.document.repository.LoanDocumentSubmissionRepository;
import com.bank.loan.document.storage.LoanDocumentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LoanDocumentService 단위 테스트 — doc-agent 장애 시 graceful degradation 경로.
 *
 * Testcontainers 통합 테스트와 별개로, doc-agent 호출이 DocAgentException 으로
 * 실패해도 업로드가 롤백되지 않고 검증만 보류(PENDING)로 강등되는지를 Docker 없이 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class LoanDocumentServiceTest {

    @Mock LoanDocumentRepository repository;
    @Mock LoanDocumentSubmissionRepository submissionRepository;
    @Mock LoanApplicationRepository applicationRepository;
    @Mock DocAgentClient docAgentClient;
    @Mock LoanDocumentStorage storage;
    @Mock CurrentActorProvider currentActor;
    @Mock StatusHistoryPublisher statusHistoryPublisher;

    @InjectMocks LoanDocumentService service;

    @Test
    void doc_agent_장애시_서류는_PENDING_으로_강등되고_업로드는_성공한다() {
        // given
        LoanApplication application = mock(LoanApplication.class);
        when(application.getApplId()).thenReturn(1L);
        lenient().when(application.getApplNo()).thenReturn("DEMO-A1");
        lenient().when(application.getProdId()).thenReturn(10L);
        when(applicationRepository.findByApplIdAndDeletedAtIsNull(1L))
                .thenReturn(Optional.of(application));

        // 저장된 엔티티를 그대로 돌려줘 이후 강등 로직이 동일 객체를 변형하도록 한다.
        when(repository.save(any(LoanDocument.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(currentActor.currentActorId()).thenReturn(0L);

        // doc-agent 가 재시도 소진 후 던지는 예외를 모사
        when(docAgentClient.submit(anyString(), anyString(), anyString(), any(MultipartFile.class)))
                .thenThrow(new DocAgentException("doc-agent unavailable", null));

        MultipartFile file = new MockMultipartFile(
                "file", "employment_cert.pdf", "application/pdf", "dummy".getBytes());

        // when
        LoanDocumentResponse res = service.upload(1L, "EMPLOYMENT_CERT", null, file);

        // then — 상태는 UPLOADED 유지, 검증 결과만 PENDING 으로 강등
        assertThat(res.docStatusCd()).isEqualTo(LoanDocument.STATUS_UPLOADED);
        assertThat(res.verifyResultCd()).isEqualTo(LoanDocument.VERIFY_PENDING);

        // doc-agent 응답이 없으므로 submission 레코드는 저장하지 않는다
        verify(submissionRepository, never()).save(any());
        // 보류 이벤트는 1회 발행된다 (롤백되지 않았음을 의미)
        verify(statusHistoryPublisher, times(1)).publish(any(StatusChangeEvent.class));
        verify(docAgentClient).submit(anyString(), anyString(), anyString(), any(MultipartFile.class));
    }
}
