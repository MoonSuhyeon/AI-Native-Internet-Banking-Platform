package com.bank.loan.maturity.service;

import com.bank.common.time.BusinessDate;
import com.bank.common.audit.StatusChangeEvent;
import com.bank.common.audit.StatusHistoryPublisher;
import com.bank.common.persistence.CurrentActorProvider;
import com.bank.common.web.BusinessException;
import com.bank.loan.calendar.service.BusinessDayService;
import com.bank.loan.contract.domain.LoanContract;
import com.bank.loan.contract.repository.LoanContractRepository;
import com.bank.loan.maturity.domain.Maturity;
import com.bank.loan.maturity.dto.ExtendMaturityRequest;
import com.bank.loan.maturity.dto.MaturityResponse;
import com.bank.loan.maturity.repository.MaturityRepository;
import com.bank.loan.support.LoanErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 만기 관리 서비스.
 *
 * createOnContract: 약정 체결 시 LoanContractService 가 호출.
 *   original_maturity_date = cntr_end_date (원본 불변).
 *   current_maturity_date  = nextBusinessDay(cntr_end_date) — 휴일이면 다음 영업일로 보정.
 * extend: current_maturity_date 를 N개월 미루고 extension_count 증가.
 *   연장 후 날짜도 nextBusinessDay 로 보정. original 은 불변.
 *
 * 본 단계 자동 전이(ACTIVE→MATURED)·만기 알림은 후속 (별도 배치).
 */
@Service
@RequiredArgsConstructor
public class MaturityService {

    private static final String DOMAIN_CD = "LOAN";
    private static final String TARGET_TABLE_CD = "MATURITY";
    private static final String REASON_CREATED = "MATURITY_CREATED";
    private static final String REASON_EXTENDED = "MATURITY_EXTENDED";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MaturityRepository repository;
    private final LoanContractRepository contractRepository;
    private final StatusHistoryPublisher statusHistoryPublisher;
    private final CurrentActorProvider currentActor;
    private final BusinessDayService businessDayService;

    /** 약정 체결 시 LoanContractService 가 호출. 멱등 — 이미 있으면 no-op. */
    @Transactional
    public Maturity createOnContract(LoanContract contract) {
        return repository.findByCntrIdAndDeletedAtIsNull(contract.getCntrId()).orElseGet(() -> {
            // original 은 계약서 원본 날짜 그대로 보관, current 는 following 정책 적용
            String rawEndDate  = contract.getCntrEndDate();
            String adjustedEnd = businessDayService.nextBusinessDay(rawEndDate);
            Maturity saved = repository.save(Maturity.builder()
                    .cntrId(contract.getCntrId())
                    .originalMaturityDate(rawEndDate)
                    .currentMaturityDate(adjustedEnd)
                    .matStatusCd(Maturity.STATUS_ACTIVE)
                    .extensionCount(0)
                    .build());
            statusHistoryPublisher.publish(StatusChangeEvent.of(
                    DOMAIN_CD, TARGET_TABLE_CD, saved.getMatId(),
                    null, Maturity.STATUS_ACTIVE,
                    REASON_CREATED,
                    "originalMaturityDate=" + rawEndDate + " / currentMaturityDate=" + adjustedEnd,
                    currentActor.currentActorId()
            ));
            return saved;
        });
    }

    @Transactional
    public MaturityResponse extend(Long cntrId, ExtendMaturityRequest req) {
        Maturity maturity = repository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_130));

        if (!maturity.isExtendable()) {
            throw new BusinessException(LoanErrorCode.LOAN_131,
                    "current=" + maturity.currentStatus());
        }

        // 연장 후 산출된 날짜도 following 정책으로 보정
        String rawNewDate = LocalDate.parse(maturity.getCurrentMaturityDate(), DATE)
                .plusMonths(req.extendedPeriodMo())
                .format(DATE);
        String adjustedNewDate = businessDayService.nextBusinessDay(rawNewDate);
        String today = BusinessDate.today();

        maturity.extend(adjustedNewDate, req.extendedPeriodMo(), req.extensionTypeCd(), today);

        statusHistoryPublisher.publish(StatusChangeEvent.of(
                DOMAIN_CD, TARGET_TABLE_CD, maturity.getMatId(),
                maturity.currentStatus(), maturity.currentStatus(),
                REASON_EXTENDED,
                "extendedPeriodMo=" + req.extendedPeriodMo()
                        + " / new=" + maturity.getCurrentMaturityDate()
                        + (req.extensionTypeCd() == null ? "" : " / type=" + req.extensionTypeCd()),
                currentActor.currentActorId()
        ));

        return MaturityResponse.of(maturity);
    }

    @Transactional(readOnly = true)
    public MaturityResponse get(Long cntrId) {
        contractRepository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_062));
        return repository.findByCntrIdAndDeletedAtIsNull(cntrId)
                .map(MaturityResponse::of)
                .orElseThrow(() -> new BusinessException(LoanErrorCode.LOAN_130));
    }
}
