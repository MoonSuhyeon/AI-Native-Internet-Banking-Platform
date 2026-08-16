package com.bank.loan.advisory.service;

import com.bank.common.web.BusinessException;
import com.bank.common.web.CommonErrorCode;
import com.bank.loan.advisory.domain.ReviewAdvisoryReport;
import com.bank.loan.advisory.dto.QuarantineDispositionRequest;
import com.bank.loan.advisory.dto.QuarantineReportResponse;
import com.bank.loan.advisory.repository.ReviewAdvisoryReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 격리 처분 — AI 가 격리한 리포트를 사람이 푼다.
 *
 * <p><b>왜 이 서비스가 생겼나.</b> {@code AuditFairnessAgent} 가 편향·위반을 의심하면
 * 리포트를 QUARANTINE 으로 바꾼다. 그런데 되돌리는 경로가 없어 한 번 격리되면
 * 영구 격리였다. 관리자 화면에 "재심사 배정 · 감사부 조사 의뢰 · 정상 판정(격리 해제)"
 * 버튼이 있었지만 셋 다 아무 데도 연결돼 있지 않았다.
 *
 * <p>README 는 "agents propose, humans dispose" 라고 말한다. propose 는 되고 있었고
 * dispose 가 없었다.
 *
 * <p><b>행위자는 여기서 정한다.</b> 컨트롤러가 요청 본문에서 받아 넘기게 두면 호출자가
 * 남의 사번을 적을 수 있다. 게이트웨이가 검증해 SecurityContext 에 넣은 값만 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuarantineDispositionService {

    private final ReviewAdvisoryReportRepository reportRepo;
    private final AdvisoryRoleGuard roleGuard;

    @Transactional
    public QuarantineReportResponse dispose(Long advrId, QuarantineDispositionRequest request) {
        roleGuard.requireAuditorOrAdmin();

        Long actor = currentActorId();
        ReviewAdvisoryReport report = reportRepo.findById(advrId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_404,
                        "advrId=" + advrId));

        try {
            report.dispose(request.disposition(), actor, request.note(), OffsetDateTime.now());
        } catch (IllegalStateException e) {
            // 이미 처분됐거나 격리 상태가 아니다 — 요청이 늦었을 뿐 서버 오류가 아니다.
            throw new BusinessException(CommonErrorCode.COMMON_409, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.COMMON_400, e.getMessage());
        }

        // 격리를 푼 사실 자체가 감사 대상이다. 저장이 끝난 뒤 남긴다.
        log.info("격리 처분: advrId={} disposition={} actor={} note={}",
                advrId, request.disposition(), actor, request.note());

        return QuarantineReportResponse.of(report);
    }

    /**
     * 지금 요청의 행위자.
     *
     * <p>게이트웨이를 거치지 않으면 SecurityContext 가 비고, 그때는 처분을 막는다.
     * 행위자를 모르는 채로 격리를 풀면 "누가 풀었는가" 에 답할 수 없고, 그 기록은
     * 없는 것과 같다.
     */
    private Long currentActorId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Long actorId)) {
            throw new BusinessException(CommonErrorCode.COMMON_403,
                    "행위자를 확인할 수 없다 — 게이트웨이를 거친 요청만 격리를 풀 수 있다");
        }
        return actorId;
    }
}
