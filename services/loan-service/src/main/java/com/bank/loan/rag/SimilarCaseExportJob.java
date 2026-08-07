package com.bank.loan.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 유사 케이스 코퍼스 일배치 — D3-2.
 *
 * <p>매일 새벽 2시 KST 에 전일 결정 완료 케이스를 임베딩 코퍼스로 내보낸다.
 * {@code ai.similar-case-export.enabled=true} 시에만 활성.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ai.similar-case-export", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SimilarCaseExportJob {

    private final SimilarCaseExporter exporter;

    /**
     * 매일 02:00 KST. cron: 초 분 시 * * *
     *
     * <p>전에는 zone 이 없어 UTC 로 해석되는 것을 알고 17:00 으로 적어 손으로 보정했다.
     * 맞는 시각에 돌긴 했지만, 읽는 사람이 매번 9시간을 되짚어야 했고 다른 배치들은
     * 같은 보정을 하지 않아 9시간씩 밀려 있었다. zone 을 선언해 적힌 대로 돌게 한다.
     */
    @Scheduled(cron = "${ai.similar-case-export.cron:0 0 2 * * *}", zone = "Asia/Seoul")
    public void run() {
        log.info("SimilarCaseExportJob: 유사 케이스 일배치 시작");
        try {
            int count = exporter.exportYesterday();
            log.info("SimilarCaseExportJob: 완료 — {} 건", count);
        } catch (Exception e) {
            log.error("SimilarCaseExportJob: 배치 실패", e);
        }
    }
}
