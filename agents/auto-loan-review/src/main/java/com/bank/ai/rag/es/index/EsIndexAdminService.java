package com.bank.ai.rag.es.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesRequest;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import co.elastic.clients.elasticsearch.indices.update_aliases.AddAction;
import co.elastic.clients.elasticsearch.indices.update_aliases.RemoveAction;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;

/**
 * ES 인덱스 생명주기 관리 서비스 — Phase E (E1-6).
 *
 * <p>기능:
 * <ul>
 *   <li>{@link #indexExists(String)} — 인덱스 존재 확인</li>
 *   <li>{@link #createIndex(String, String)} — classpath JSON 매핑으로 인덱스 생성</li>
 *   <li>{@link #ensureAliasExists(String, String)} — alias 없으면 생성</li>
 *   <li>{@link #swapAlias(String, String, String)} — zero-downtime alias 교체</li>
 *   <li>{@link #initializeAllIndexes()} — 애플리케이션 기동 시 3 코퍼스 인덱스/alias 초기화</li>
 * </ul>
 *
 * <p>{@code ai.rag.backend=es} 시에만 활성화.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.rag", name = "backend", havingValue = "es")
public class EsIndexAdminService {

    /** 버전별 실제 인덱스명 → alias 매핑 정의. */
    private static final IndexDef[] INDEX_DEFS = {
            new IndexDef("kb_policy_v1",        "kb_policy",        "es/mappings/kb_policy_v1.json"),
            new IndexDef("kb_similar_cases_v1", "kb_similar_cases", "es/mappings/kb_similar_cases_v1.json"),
            new IndexDef("kb_internal_faq_v1",  "kb_internal_faq",  "es/mappings/kb_internal_faq_v1.json"),
    };

    private final ElasticsearchClient esClient;

    /**
     * 애플리케이션 기동 시 3 코퍼스 인덱스와 alias 를 초기화.
     * 인덱스가 이미 존재하면 스킵 — 재인덱싱은 {@link #swapAlias} 로 별도 수행.
     */
    @PostConstruct
    public void initializeAllIndexes() {
        log.info("EsIndexAdminService: 인덱스 초기화 시작");
        for (IndexDef def : INDEX_DEFS) {
            try {
                if (!indexExists(def.indexName())) {
                    createIndex(def.indexName(), def.mappingResource());
                    log.info("EsIndexAdminService: 인덱스 생성 완료 — {}", def.indexName());
                } else {
                    log.debug("EsIndexAdminService: 인덱스 이미 존재 — {}", def.indexName());
                }
                ensureAliasExists(def.indexName(), def.alias());
            } catch (Exception e) {
                log.error("EsIndexAdminService: 인덱스 초기화 실패 — index={}", def.indexName(), e);
            }
        }
        log.info("EsIndexAdminService: 인덱스 초기화 완료");
    }

    /**
     * 인덱스 존재 여부 확인.
     *
     * @param indexName 확인할 인덱스명
     * @return 존재하면 {@code true}
     */
    public boolean indexExists(String indexName) throws IOException {
        return esClient.indices().exists(r -> r.index(indexName)).value();
    }

    /**
     * classpath JSON 매핑 파일로 인덱스를 생성.
     *
     * @param indexName       생성할 인덱스명
     * @param mappingResource classpath 경로 (예: {@code es/mappings/kb_policy_v1.json})
     */
    public void createIndex(String indexName, String mappingResource) throws IOException {
        ClassPathResource resource = new ClassPathResource(mappingResource);
        try (InputStream is = resource.getInputStream()) {
            esClient.indices().create(r -> r.index(indexName).withJson(is));
            log.info("EsIndexAdminService: 인덱스 생성 — index={} mapping={}", indexName, mappingResource);
        }
    }

    /**
     * alias 가 없으면 생성. 이미 존재하면 스킵.
     *
     * @param indexName 대상 인덱스명
     * @param alias     생성할 alias 명
     */
    public void ensureAliasExists(String indexName, String alias) throws IOException {
        boolean exists = esClient.indices().existsAlias(r -> r.name(alias)).value();
        if (!exists) {
            esClient.indices().putAlias(r -> r.index(indexName).name(alias));
            log.info("EsIndexAdminService: alias 생성 — index={} alias={}", indexName, alias);
        } else {
            log.debug("EsIndexAdminService: alias 이미 존재 — alias={}", alias);
        }
    }

    /**
     * Zero-downtime alias 교체 — 원자적 updateAliases 호출.
     *
     * @param alias     교체할 alias 명
     * @param oldIndex  현재 alias 가 가리키는 인덱스
     * @param newIndex  새로 alias 를 가리킬 인덱스
     */
    public void swapAlias(String alias, String oldIndex, String newIndex) throws IOException {
        esClient.indices().updateAliases(UpdateAliasesRequest.of(r -> r.actions(
                Action.of(a -> a.remove(RemoveAction.of(rem -> rem.index(oldIndex).alias(alias)))),
                Action.of(a -> a.add(AddAction.of(add -> add.index(newIndex).alias(alias))))
        )));
        log.info("EsIndexAdminService: alias swap 완료 — alias={} {} → {}", alias, oldIndex, newIndex);
    }

    // ────────────────────────────────────────────────────────────────────────

    private record IndexDef(String indexName, String alias, String mappingResource) {}
}
