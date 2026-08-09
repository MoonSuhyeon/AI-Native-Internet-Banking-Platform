package com.bank.docagent.config;

import com.bank.common.web.BusinessException;
import com.bank.common.web.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 신뢰 경계 — 이 요청이 누구인가.
 *
 * <p><b>왜 필요한가.</b> {@code SecurityConfig} 에는 "doc-agent는 내부 서비스 전용 —
 * gateway 뒤에 위치하므로 자체 인증 불필요" 라고 적혀 있었다. 그런데 게이트웨이에
 * doc-agent 라우트가 없었다. 주석이 말하는 보호가 존재하지 않았고, 8087 은 브라우저가
 * 그대로 부를 수 있었다.
 *
 * <p>그 상태에서 무엇이 열려 있었는가.
 * <ul>
 *   <li><b>심사 결정.</b> {@code reviewer_id} 를 요청 body 로 받았다. 아무 이름이나
 *       적을 수 있었는데, 이 기록이 곧 <b>AI 채택률 지표의 근거</b>다. 지표는
 *       "자동 판정을 사람이 이만큼 뒤집었다" 고 말하지만 그 사람이 누구인지는
 *       아무도 확인하지 않았다.</li>
 *   <li><b>법적보존.</b> 삭제를 막는 규제 통제인데 아무나 끌 수 있었다.</li>
 *   <li><b>심사 대기 목록.</b> 고객 제출 서류의 위조 점수까지 그대로 나왔다.</li>
 * </ul>
 *
 * <p>이제 게이트웨이가 클라이언트발 {@code X-Employee-Id} 를 지우고 검증된 JWT
 * 클레임으로 덮어쓴다. 여기서는 <b>게이트웨이를 거쳤다는 증거가 맞을 때만</b> 그
 * 헤더를 믿는다. 상담 서비스·조사 에이전트와 같은 규약이다.
 *
 * <p><b>이 방어의 한계.</b> 공유 시크릿은 네트워크 격리의 대체재가 아니다. 8087 이
 * 브라우저에서 닿는 한 우회 여지는 남는다. 완결된 형태는 그 포트가 밖에서 안 보이는
 * 것이고, 시크릿은 그때까지의 최소 방어선이다.
 */
@Component
public class GatewayIdentity {

    private static final String HEADER_GATEWAY_AUTH = "X-Gateway-Auth";
    private static final String HEADER_EMPLOYEE_ID = "X-Employee-Id";

    private final String sharedSecret;

    public GatewayIdentity(
            @Value("${doc-agent.gateway.shared-secret:${DOC_AGENT_GATEWAY_SHARED_SECRET:}}")
            String sharedSecret) {
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret.trim();
    }

    /** 이 요청이 게이트웨이를 거쳐 왔는지. */
    private boolean gatewayVerified(HttpServletRequest request) {
        String presented = request.getHeader(HEADER_GATEWAY_AUTH);
        if (sharedSecret.isEmpty() || presented == null || presented.isEmpty()) {
            return false;
        }
        // 타이밍 공격 회피 — 시크릿 비교에 equals 를 쓰지 않는다.
        return MessageDigest.isEqual(
                sharedSecret.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 게이트웨이가 검증해 주입한 직원 ID. 확인되지 않으면 {@code null}.
     *
     * <p>게이트웨이는 고객 토큰에도 이 헤더를 붙이되 빈 문자열을 넣는다. 빈 값을
     * 신원으로 넘기면 "권한 없음" 이 아니라 "이름이 빈 심사원" 이 기록에 남는다.
     */
    public String employeeId(HttpServletRequest request) {
        if (!gatewayVerified(request)) {
            return null;
        }
        String raw = request.getHeader(HEADER_EMPLOYEE_ID);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    /**
     * 직원 전용 동작의 문지기.
     *
     * @throws BusinessException 403. 401 이 아닌 이유는 자격 증명을 다시 보내라는
     *         뜻이 아니기 때문이다 — 게이트웨이를 거치지 않은 요청은 무엇을 붙여도
     *         통과할 수 없다.
     */
    public String requireEmployee(HttpServletRequest request) {
        String id = employeeId(request);
        if (id == null) {
            throw new BusinessException(CommonErrorCode.COMMON_403,
                    "직원 권한이 필요합니다. 게이트웨이를 통해 직원 계정으로 요청해주세요.");
        }
        return id;
    }
}
