package com.bank.deposit.service;

import com.bank.deposit.domain.entity.SpecialTerm;
import com.bank.deposit.domain.enums.SpecialTermStatus;
import com.bank.deposit.exception.BusinessException;
import com.bank.deposit.exception.ErrorCode;
import com.bank.deposit.repository.SpecialTermRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SpecialTermService {

    private final SpecialTermRepository specialTermRepository;
    private final Clock clock;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    public List<SpecialTerm> findAll(Boolean isActive) {
        if (isActive != null) {
            return specialTermRepository.findByStatus(isActive ? SpecialTermStatus.ACTIVE : SpecialTermStatus.INACTIVE);
        }
        return specialTermRepository.findAll();
    }

    public SpecialTerm findById(Long id) {
        return specialTermRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "특약을 찾을 수 없습니다."));
    }

    @Transactional
    public SpecialTerm create(String specialTermName, String specialTermContent, String specialTermSummary,
                              Boolean isRequired, String specialTermVersion, String startedAt, String endedAt) {
        return specialTermRepository.save(SpecialTerm.builder()
                .specialTermName(specialTermName)
                .specialTermContent(specialTermContent)
                .specialTermSummary(specialTermSummary)
                .isRequired(isRequired != null && isRequired)
                .specialTermVersion(specialTermVersion)
                .startedAt(startedAt)
                .endedAt(endedAt)
                .build());
    }

    @Transactional
    public SpecialTerm update(Long id, String specialTermName, String specialTermContent, String specialTermVersion) {
        SpecialTerm term = findById(id);
        term.update(specialTermName, specialTermContent, specialTermVersion);
        return term;
    }

    @Transactional
    public SpecialTerm changeStatus(Long id, SpecialTermStatus status) {
        SpecialTerm term = findById(id);
        term.changeStatus(status, LocalDate.now(clock).format(DATE_FMT));
        return term;
    }
}
