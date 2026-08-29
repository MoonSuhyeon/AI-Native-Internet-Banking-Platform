package com.bank.payment.api;

import com.bank.payment.domain.mapper.ReconciliationBreakRow;
import com.bank.payment.domain.reconciliation.ReconciliationBreak;
import com.bank.payment.domain.service.ReconciliationService;
import com.bank.payment.security.EmployeeOperation;
import com.bank.payment.security.EmployeeOperationGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 대사 실행·조회 (직원용 내부 API).
 *
 * <p>실행을 수동으로도 걸 수 있게 열어 둔 이유는 <b>재실행이 흔하기 때문</b>이다.
 * 장애로 배치가 건너뛴 날, 대사 로직을 고친 뒤, 불일치를 조치한 뒤 — 모두 다시
 * 돌려 확인해야 한다. 적재는 (영업일·망·결제·유형) 유일키로 갱신되므로 여러 번
 * 돌려도 중복이 쌓이지 않는다.
 */
@RestController
@RequestMapping("/v1/internal/reconciliation")
@RequiredArgsConstructor
public class InternalReconciliationController {

    private final ReconciliationService reconciliationService;
    private final EmployeeOperationGuard employeeOperationGuard;

    /**
     * 대사 실행.
     *
     * @param businessDate yyyyMMdd
     * @param network      KFTC · BOK. 생략하면 두 망 모두 — 한쪽만 돌고 끝나는 실수를 막는다.
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(
            @RequestHeader(value = EmployeeOperationGuard.EMPLOYEE_ID_HEADER, required = false)
            String employeeId,
            @RequestHeader(value = EmployeeOperationGuard.ROLE_HEADER, required = false)
            String roles,
            @RequestParam String businessDate,
            @RequestParam(required = false) String network) {

        // 사람이 부르는 운영 작업이다. 서비스 자격증명이 아니라 직원 신원과 역할로
        // 인가한다 — 확인되지 않은 호출자를 위해 OPERATOR 라는 서비스 신원을 만들면
        // 그 신원이 곧 우회로가 된다.
        //
        // 헤더를 required=false 로 받는 이유는, 헤더가 없다는 사실 자체가 감사에
        // 남아야 하기 때문이다. required=true 면 스프링이 400 을 던져 아무것도
        // 남지 않는데, 그 시도가 가장 봐야 할 기록이다.
        //
        // 무엇을 실행하는지도 함께 남긴다. "누가 대사를 돌렸다" 만으로는 어느
        // 영업일을 다시 돌렸는지 알 수 없고, 재실행이 흔한 작업이라 그 구분이 곧
        // 조사의 출발점이다.
        employeeOperationGuard.require(
                employeeId, roles, EmployeeOperation.RECONCILIATION_RUN,
                "businessDate=" + businessDate + ",network="
                        + (network == null || network.isBlank() ? "ALL" : network),
                "/api/v1/internal/reconciliation/run");

        List<ReconciliationBreak> breaks = (network == null || network.isBlank())
                ? reconciliationService.runAll(businessDate)
                : reconciliationService.run(businessDate, network);

        // 건수만 주지 않고 유형별로 쪼개 준다. "12건" 만으로는 지금 사람을 불러야
        // 하는지 내일 봐도 되는지 판단할 수 없다.
        Map<String, Long> byType = new java.util.LinkedHashMap<>();
        for (ReconciliationBreak b : breaks) {
            byType.merge(b.type().name(), 1L, Long::sum);
        }

        return ResponseEntity.ok(Map.of(
                "businessDate", businessDate,
                "network", network == null || network.isBlank() ? "ALL" : network,
                "breakCount", breaks.size(),
                "byType", byType,
                "breaks", breaks));
    }

    /** 적재된 불일치 조회. 조사 상태가 함께 나온다. */
    @GetMapping("/breaks")
    public ResponseEntity<List<ReconciliationBreakRow>> breaks(
            @RequestParam String businessDate,
            @RequestParam(required = false) String network) {
        return ResponseEntity.ok(reconciliationService.breaks(businessDate, network));
    }
}
