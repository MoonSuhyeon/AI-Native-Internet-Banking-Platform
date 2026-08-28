package com.bank.customer.authorization;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 직원 인가 판단 — 내부 API.
 *
 * <p>부르는 쪽은 AI 상담·조사 에이전트처럼 <b>직원을 대신해</b> 고객 금융정보를 읽는
 * 경로다. 그 경로가 각자 "이 직원이 봐도 되나" 를 다시 구현하면 규칙이 조금씩
 * 달라지고, 어느 것이 맞는지 아무도 모르게 된다.
 *
 * <p><b>이 API 는 자원을 주지 않는다.</b> 판단만 돌려준다. 계좌·거래는 여전히
 * core-banking 이 주고, 최종 접근 통제와 열람 감사도 거기에 있다.
 *
 * <pre>
 *   상담 → [여기] 인가 판단 → core-banking 내부 API → 자원 + 열람 감사
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/internal/authorization")
@RequiredArgsConstructor
public class EmployeeAuthorizationController {

    private final EmployeeAuthorizationService authorizationService;

    /**
     * 거절도 200 으로 돌려준다. 부르는 쪽이 거절 사유와 행위자 스냅샷을 받아
     * 감사에 남겨야 하기 때문이다 — 403 으로 던지면 그 정보가 같이 사라진다.
     */
    @PostMapping("/employee")
    public AuthorizationDecision decide(@Valid @RequestBody AuthorizationRequest request) {
        return authorizationService.decide(request);
    }
}
