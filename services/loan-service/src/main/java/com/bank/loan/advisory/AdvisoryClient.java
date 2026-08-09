package com.bank.loan.advisory;

import com.bank.loan.advisory.service.AdvisoryReportListFilter;
import com.bank.loan.advisory.service.AdvisoryReportQueryService;
import com.bank.loan.advisory.service.AdvisoryViewerRole;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 심사 화면에서 쓰는 어드바이저리 리포트 조회.
 *
 * <p><b>HTTP 호출이었다가 직접 호출로 바뀌었다.</b> 예전에는 별도 프로세스인
 * advisory-service 를 {@code /api/advisory/reports} 로 불렀다. 그런데 그 서비스는
 * 빌드에 포함된 적이 없었다 — {@code settings.gradle} 에도, Dockerfile 에도,
 * compose 에도 없었다. 즉 이 호출은 <b>언제나 실패했다</b>.
 *
 * <p>그런데도 아무 일도 일어나지 않았다. 실패 시 빈 목록을 돌려주는 처리가 있었고,
 * 주석은 그것을 "advisory-service 장애 시 fail-open" 이라고 설명했다. 장애가 아니라
 * <b>상시 상태</b>였다 — 자문 리포트는 늘 비어 있었고 심사는 그대로 진행됐다.
 *
 * <p>모양도 맞지 않았다. 이 클라이언트는 배열({@code List})을 기대했지만 컨트롤러는
 * {@code ApiResponse} 로 감싼 객체를 돌려준다. 서비스가 떠 있었더라도 역직렬화에
 * 실패해 결과는 같았을 것이다.
 *
 * <p>이제 같은 프로세스이므로 질의 서비스를 직접 부른다. 네트워크 왕복도, 실패를
 * 삼키는 자리도 없어진다.
 */
@Component
@RequiredArgsConstructor
public class AdvisoryClient {

    private static final Logger log = LoggerFactory.getLogger(AdvisoryClient.class);

    private final AdvisoryReportQueryService queryService;

    /**
     * revId 에 연결된 어드바이저리 리포트 목록.
     *
     * <p>여기서 {@link AdvisoryViewerRole#AUDITOR} 로 조회하는 것은 <b>호출 맥락이
     * 사람이 아니기 때문</b>이다. 심사 진행 판정(계약 게이트 등)이 "리포트가 있는가" 를
     * 묻는 자리라, 조회자 본인 것만 보이면 남의 심사 건에서 경고를 놓친다.
     * 사람이 보는 화면은 컨트롤러를 거치며 각자의 역할로 필터링된다.
     *
     * <p>실패를 삼키지 않는다. 예전에는 예외를 잡아 빈 목록을 돌려줬는데, 그 처리
     * 때문에 서비스가 아예 없다는 사실이 몇 달간 드러나지 않았다. 같은 프로세스인
     * 지금은 예외가 곧 결함이므로 올려 보낸다.
     */
    public List<AdvisoryReportSummary> getReports(Long revId) {
        if (revId == null) {
            return Collections.emptyList();
        }
        var filter = new AdvisoryReportListFilter(null, revId, null, null, null);
        List<AdvisoryReportSummary> reports = queryService
                .findForViewer(null, AdvisoryViewerRole.AUDITOR, filter)
                .stream()
                .map(r -> new AdvisoryReportSummary(
                        r.advrId(),
                        r.revId(),
                        r.advisoryTypeCd(),
                        r.severityCd(),
                        r.advrStatusCd(),
                        r.advrTitle(),
                        null,
                        r.targetReviewerId() != null ? String.valueOf(r.targetReviewerId()) : null))
                .toList();

        log.debug("어드바이저리 리포트 조회 revId={} 건수={}", revId, reports.size());
        return reports;
    }
}
