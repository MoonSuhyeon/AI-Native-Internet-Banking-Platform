package com.bank.ai.rag.api;

import com.bank.ai.privacy.PiiLeakageException;
import com.bank.ai.privacy.PiiMaskingFilter;
import com.bank.ai.rag.api.dto.ChunkBatchItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * EmbeddingBatchService 단위 테스트 — D3-1.
 * 적재 백엔드({@link CorpusIndexer}) 는 mock — PII 검사·위임 흐름만 검증.
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingBatchServiceTest {

    @Mock
    private CorpusIndexer corpusIndexer;
    @Mock
    private PiiMaskingFilter piiMaskingFilter;

    @InjectMocks
    private EmbeddingBatchService service;

    @Test
    void 정상_2건_upsert_완료() {
        var items = List.of(
                new ChunkBatchItem("similar_cases", "rev-001", 0, "승인 케이스 페르소나 요약", null, Map.of()),
                new ChunkBatchItem("similar_cases", "rev-002", 0, "반려 케이스 DSR 초과", "DSR 초과", Map.of("decision", "REJECT"))
        );

        int result = service.upsertAll(items);

        assertThat(result).isEqualTo(2);
        verify(corpusIndexer, times(2)).upsert(any(ChunkBatchItem.class));
    }

    @Test
    void PII_감지시_예외_throw_upsert_미실행() {
        doThrow(new PiiLeakageException("PII detected"))
                .when(piiMaskingFilter).assertNoSensitivePii(anyString(), anyString());

        var items = List.of(
                new ChunkBatchItem("similar_cases", "rev-003", 0, "홍길동 주민번호 노출 텍스트", null, Map.of())
        );

        assertThatThrownBy(() -> service.upsertAll(items))
                .isInstanceOf(PiiLeakageException.class);
        verify(corpusIndexer, never()).upsert(any(ChunkBatchItem.class));
    }

    @Test
    void 빈_목록은_0건_반환_적재_호출_없음() {
        int result = service.upsertAll(List.of());

        assertThat(result).isEqualTo(0);
        verifyNoInteractions(corpusIndexer);
    }
}
