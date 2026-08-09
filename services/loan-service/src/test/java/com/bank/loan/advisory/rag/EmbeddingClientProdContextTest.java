package com.bank.loan.advisory.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * test 프로파일 비활성 시 AdvisoryOpenAiEmbeddingClient 가 기본 로드됨을 검증.
 *
 * @ActiveProfiles 없음 → "test" 프로파일 비활성 → @Profile("!test") 조건 충족 → OpenAI 클라이언트 로드.
 * StubEmbeddingClient 는 @Profile("test") 라 이 컨텍스트에서 등록되지 않음.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = EmbeddingClientProdContextTest.TestConfig.class)
@TestPropertySource(properties = {
        "advisory.rag.embed.provider=openai",
        "advisory.rag.embed.model=text-embedding-3-small",
        "advisory.rag.embed.dimension=1536",
        "advisory.rag.embed.openai.base-url=https://api.openai.test",
        "advisory.rag.embed.openai.api-key=sk-test-key",
        "advisory.rag.embed.openai.connect-timeout-ms=1000",
        "advisory.rag.embed.openai.read-timeout-ms=5000",
        "advisory.rag.embed.openai.max-attempts=1",
        "advisory.rag.embed.openai.retry-backoff-ms=100"
})
class EmbeddingClientProdContextTest {

    @EnableConfigurationProperties(AdvisoryRagProperties.class)
    @Import(AdvisoryOpenAiEmbeddingClient.class)
    static class TestConfig {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    @Autowired
    EmbeddingClient embeddingClient;

    @Test
    void 비테스트_프로파일에서_OpenAi_클라이언트_로드() {
        assertThat(embeddingClient).isInstanceOf(AdvisoryOpenAiEmbeddingClient.class);
    }

    @Test
    void OpenAi_클라이언트_모델코드_확인() {
        assertThat(embeddingClient.defaultModelCd()).isEqualTo(AdvisoryOpenAiEmbeddingClient.MODEL_CD);
        assertThat(embeddingClient.dimension()).isEqualTo(1536);
    }
}
