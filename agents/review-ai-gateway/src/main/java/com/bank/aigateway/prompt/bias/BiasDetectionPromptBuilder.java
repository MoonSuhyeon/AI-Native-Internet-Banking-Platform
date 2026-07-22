package com.bank.aigateway.prompt.bias;

import com.bank.aigateway.audit.dto.AuditAnalysisRequest;
import com.bank.aigateway.prompt.PromptRenderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class BiasDetectionPromptBuilder {

    private final String system;
    private final String userTemplate;

    @Autowired
    public BiasDetectionPromptBuilder(
            @Value("${prompt.bias.system-resource:classpath:prompts/bias/system.txt}") Resource systemResource,
            @Value("${prompt.bias.user-resource:classpath:prompts/bias/user.txt}") Resource userResource) {
        try {
            this.system       = systemResource.getContentAsString(StandardCharsets.UTF_8);
            this.userTemplate = userResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("편향 감지 프롬프트 파일 로드 실패", e);
        }
    }

    public BiasDetectionPromptBuilder(String system, String userTemplate) {
        this.system       = system;
        this.userTemplate = userTemplate;
    }

    public String buildSystem() {
        return system;
    }

    public String buildUser(AuditAnalysisRequest req) {
        return PromptRenderer.render(userTemplate, Map.of(
                "revId",         String.valueOf(req.revId()),
                "reviewerId",    String.valueOf(req.reviewerId()),
                "signals",       formatSignals(req),
                "reviewOpinion", req.reviewOpinionText() == null ? "(없음)" : req.reviewOpinionText(),
                "ragChunks",     formatRagChunks(req)
        ));
    }

    private String formatSignals(AuditAnalysisRequest req) {
        if (req.signals() == null || req.signals().isEmpty()) return "(없음)";
        return req.signals().stream()
                .map(s -> "- [%s/%s] metric=%s observed=%.4f threshold=%.4f".formatted(
                        s.ruleCd(), s.severityCd(), s.signalMetric(),
                        s.observedValue(), s.thresholdValue()))
                .collect(Collectors.joining("\n"));
    }

    private String formatRagChunks(AuditAnalysisRequest req) {
        if (req.ragChunks() == null || req.ragChunks().isEmpty()) return "(없음)";
        return req.ragChunks().stream()
                .map(c -> "출처: %s\n%s".formatted(c.source(), c.content()))
                .collect(Collectors.joining("\n---\n"));
    }
}
