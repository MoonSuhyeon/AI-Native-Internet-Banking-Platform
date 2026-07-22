package com.bank.ai.rag.api;

import com.bank.ai.rag.api.dto.EmbeddingBatchRequest;
import com.bank.ai.rag.api.dto.EmbeddingBatchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내부 임베딩 배치 적재 엔드포인트 — D3-1.
 *
 * <p>loan-service 에서만 호출. {@code X-Internal-Token} 헤더로 접근 제한.
 * 토큰 미설정({@code ai.internal-token} 빈 값) 시 모든 요청 거부 (fail-safe).
 */
@Slf4j
@RestController
@RequestMapping("/api/internal/embeddings")
@RequiredArgsConstructor
public class EmbeddingBatchController {

    private final EmbeddingBatchService batchService;

    @Value("${ai.internal-token:}")
    private String internalToken;

    @PostMapping("/batch")
    public ResponseEntity<EmbeddingBatchResponse> batch(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody EmbeddingBatchRequest request) {

        if (internalToken.isBlank() || !internalToken.equals(token)) {
            log.warn("EmbeddingBatchController: 내부 토큰 불일치 — 요청 거부");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        log.info("EmbeddingBatchController: 배치 적재 요청 items={}", request.items().size());
        int upserted = batchService.upsertAll(request.items());
        return ResponseEntity.ok(new EmbeddingBatchResponse(upserted));
    }
}
