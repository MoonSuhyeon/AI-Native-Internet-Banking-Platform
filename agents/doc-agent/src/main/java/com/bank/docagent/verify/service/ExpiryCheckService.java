package com.bank.docagent.verify.service;

import com.bank.docagent.submission.dto.verification.MissingDocument;
import com.bank.docagent.verify.domain.LoanProductDocument;
import com.bank.docagent.verify.domain.LoanProductDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 마스터 테이블 기반 누락 서류 및 유효기간 만료 검증.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpiryCheckService {

    private final LoanProductDocumentRepository masterRepo;

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy.MM.dd"),
        DateTimeFormatter.ofPattern("yyyyMMdd")
    );

    /**
     * @param productId     대출 상품 ID
     * @param submittedCodes 이번 신청에 제출된 서류 코드 목록
     * @param issueDateByCode 서류코드 → 발급일 (L4 추출값, null 가능)
     */
    public List<MissingDocument> check(String productId,
                                       Set<String> submittedCodes,
                                       java.util.Map<String, String> issueDateByCode) {
        List<LoanProductDocument> required = masterRepo.findByProductIdAndEssentialTrue(productId);
        List<MissingDocument> problems = new ArrayList<>();

        for (LoanProductDocument doc : required) {
            String code = doc.getReqDocCode();

            // 누락 검사
            if (!submittedCodes.contains(code)) {
                problems.add(new MissingDocument(code, doc.getReqDocName(), "MISSING"));
                continue;
            }

            // 만료일 검사
            if (doc.getValidDays() != null) {
                String issueDateStr = issueDateByCode.get(code);
                if (issueDateStr != null) {
                    LocalDate issueDate = parseDate(issueDateStr);
                    if (issueDate != null) {
                        LocalDate expiry = issueDate.plusDays(doc.getValidDays());
                        if (expiry.isBefore(LocalDate.now())) {
                            log.warn("서류 만료: code={} issuedAt={} expiredAt={}", code, issueDate, expiry);
                            problems.add(new MissingDocument(code, doc.getReqDocName(), "EXPIRED"));
                        }
                    }
                }
            }
        }
        return problems;
    }

    private LocalDate parseDate(String raw) {
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try { return LocalDate.parse(raw.trim(), fmt); }
            catch (DateTimeParseException ignored) {}
        }
        log.debug("날짜 파싱 실패: '{}'", raw);
        return null;
    }
}
