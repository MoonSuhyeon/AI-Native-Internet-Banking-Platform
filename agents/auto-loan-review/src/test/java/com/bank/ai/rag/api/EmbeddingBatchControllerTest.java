package com.bank.ai.rag.api;

import com.bank.ai.rag.api.dto.ChunkBatchItem;
import com.bank.ai.rag.api.dto.EmbeddingBatchRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * EmbeddingBatchController 단위 테스트 — D3-1.
 * standalone MockMvc 사용 — application.yml 로딩 없음.
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingBatchControllerTest {

    private static final String VALID_TOKEN = "test-secret-token";

    @Mock
    private EmbeddingBatchService batchService;

    @InjectMocks
    private EmbeddingBatchController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(controller, "internalToken", VALID_TOKEN);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private EmbeddingBatchRequest twoItemRequest() {
        return new EmbeddingBatchRequest(List.of(
                new ChunkBatchItem("similar_cases", "rev-001", 0, "케이스 요약 텍스트", null, Map.of()),
                new ChunkBatchItem("similar_cases", "rev-002", 0, "케이스 요약 텍스트2", null, Map.of())
        ));
    }

    @Test
    void 유효한_토큰으로_배치_요청_200_반환() throws Exception {
        when(batchService.upsertAll(any())).thenReturn(2);

        mockMvc.perform(post("/api/internal/embeddings/batch")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(twoItemRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upserted").value(2));
    }

    @Test
    void 토큰_없으면_401_반환() throws Exception {
        mockMvc.perform(post("/api/internal/embeddings/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(twoItemRequest())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 잘못된_토큰이면_401_반환() throws Exception {
        mockMvc.perform(post("/api/internal/embeddings/batch")
                        .header("X-Internal-Token", "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(twoItemRequest())))
                .andExpect(status().isUnauthorized());
    }
}
