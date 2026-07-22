package com.bank.docagent.forgery.service;

import com.bank.docagent.forgery.domain.ForgerySignalEntity;
import com.bank.docagent.forgery.domain.ForgerySignalRepository;
import com.bank.docagent.forgery.infra.dto.ForgeryAnalyzeRequest;
import com.bank.docagent.forgery.infra.dto.ForgeryAnalyzeResponse;
import com.bank.docagent.submission.dto.verification.ForgerySignal;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 위변조 시그널 분석 서비스.
 * Python 사이드카 /forgery/analyze 호출 → DB 저장 → 집계 점수 반환.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForgeryAnalysisService {

    private final RestClient restClient;
    private final ForgerySignalRepository signalRepository;

    @Value("${doc-agent.inference.base-url}")
    private String baseUrl;

    public record ForgeryResult(double aggregateScore, List<ForgerySignal> signals) {
        public static ForgeryResult empty() {
            return new ForgeryResult(0.0, List.of());
        }
    }

    @Transactional
    @CircuitBreaker(name = "forgery", fallbackMethod = "fallback")
    public ForgeryResult analyze(UUID submissionId, String docType,
                                 byte[] fileBytes, String contentType) {
        String b64 = Base64.getEncoder().encodeToString(fileBytes);

        ForgeryAnalyzeResponse resp = restClient.post()
            .uri(baseUrl + "/forgery/analyze")
            .body(new ForgeryAnalyzeRequest(submissionId.toString(), docType, b64, contentType))
            .retrieve()
            .body(ForgeryAnalyzeResponse.class);

        if (resp == null || resp.signals() == null) return ForgeryResult.empty();

        // DB 저장
        List<ForgerySignalEntity> entities = resp.signals().stream()
            .map(s -> ForgerySignalEntity.builder()
                .submissionId(submissionId)
                .category(s.category())
                .signalType(s.type())
                .score(s.score())
                .evidence("{\"detail\":\"" + escapeJson(s.evidence()) + "\"}")
                .build())
            .toList();
        signalRepository.saveAll(entities);

        // DTO 변환
        List<ForgerySignal> signals = resp.signals().stream()
            .map(s -> new ForgerySignal(s.category(), s.type(), s.score(), s.evidence()))
            .toList();

        log.info("위변조 분석 저장: submissionId={} signals={} score={}",
            submissionId, signals.size(), resp.aggregateScore());

        return new ForgeryResult(resp.aggregateScore(), signals);
    }

    ForgeryResult fallback(UUID submissionId, String docType,
                           byte[] fileBytes, String contentType, Throwable t) {
        log.warn("위변조 사이드카 장애, fallback. submissionId={} cause={}", submissionId, t.getMessage());
        return ForgeryResult.empty();
    }

    private String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
