package com.bank.docagent.submission.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * L2 Classify: OCR 추출 텍스트 기반 서류 유형 키워드 분류.
 * 실서비스 전환 시 CLIP zero-shot 또는 파인튜닝 분류기로 교체.
 */
@Slf4j
@Service
public class DocumentClassifyService {

    public enum DocType {
        ID_CARD,              // 신분증
        RESIDENT_REGISTER,    // 주민등록등본
        EMPLOYMENT_CERT,      // 재직증명서
        INCOME_TAX_RECEIPT,   // 근로소득원천징수영수증
        REGISTRY_DEED,        // 부동산 등기부등본
        SALE_CONTRACT,        // 매매계약서
        UNKNOWN
    }

    private static final Map<DocType, List<String>> KEYWORD_MAP = Map.of(
        DocType.ID_CARD,             List.of("주민등록증", "운전면허증", "여권"),
        DocType.RESIDENT_REGISTER,   List.of("주민등록등본", "세대원", "세대주", "전입"),
        DocType.EMPLOYMENT_CERT,     List.of("재직증명서", "재직", "직위", "입사일"),
        DocType.INCOME_TAX_RECEIPT,  List.of("원천징수영수증", "근로소득", "귀속연도", "총급여"),
        DocType.REGISTRY_DEED,       List.of("등기부등본", "갑구", "을구", "소유권"),
        DocType.SALE_CONTRACT,       List.of("매매계약서", "매도인", "매수인", "목적물")
    );

    public DocType classify(String fullText) {
        String normalized = fullText.replaceAll("\\s+", "");
        for (var entry : KEYWORD_MAP.entrySet()) {
            long matched = entry.getValue().stream()
                .filter(normalized::contains)
                .count();
            if (matched >= 2) {
                log.debug("서류 분류 결과: {} (키워드 {}개 일치)", entry.getKey(), matched);
                return entry.getKey();
            }
        }
        // 키워드 1개라도 있으면 UNKNOWN 대신 해당 타입 반환
        for (var entry : KEYWORD_MAP.entrySet()) {
            boolean anyMatch = entry.getValue().stream().anyMatch(normalized::contains);
            if (anyMatch) {
                log.debug("서류 분류 결과 (1개 일치): {}", entry.getKey());
                return entry.getKey();
            }
        }
        log.warn("서류 유형 분류 실패 — UNKNOWN 반환");
        return DocType.UNKNOWN;
    }
}
