package com.bank.customer.login;

import com.bank.common.security.jwt.JwtClaims;
import com.bank.common.security.jwt.JwtProvider;
import com.bank.common.security.jwt.TokenType;
import com.bank.common.web.ApiResponse;
import com.bank.common.web.BusinessException;
import com.bank.common.web.CommonErrorCode;
import com.bank.customer.login.dto.LoginRequest;
import com.bank.customer.login.dto.LoginResponse;
import com.bank.customer.login.dto.RefreshRequest;
import com.bank.customer.login.service.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class LoginController {

    private final LoginService loginService;
    private final JwtProvider jwtProvider;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        String ip = extractIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        return ResponseEntity.ok(ApiResponse.ok(loginService.login(request, ip, userAgent)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(
            @Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(loginService.refresh(request)));
    }

    /**
     * 직원 전용 로그인. 관리자 화면이 쓴다.
     *
     * <p>고객 계정으로 들어오면 403 이다 — 인증은 맞지만 이 문으로 들어올 자격이
     * 아니라는 뜻이다. 자격이 틀린 경우(401)와 구분되므로 화면이 다른 안내를 낼 수 있다.
     */
    @PostMapping("/employee/login")
    public ResponseEntity<ApiResponse<LoginResponse>> employeeLogin(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        String ip = extractIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");
        return ResponseEntity.ok(
                ApiResponse.ok(loginService.employeeLogin(request, ip, userAgent)));
    }

    /**
     * 토큰이 아직 쓸 수 있는 것인지 확인한다.
     *
     * <p><b>왜 필요한가.</b> 관리자 화면의 가드가 이 경로를 부르는데 백엔드에 없어
     * 404 가 났다. 가드는 그것을 무효 토큰으로 보고 로그인 직후 튕겨냈고, 화면에서는
     * 로그인이 안 되는 것과 구분되지 않았다.
     *
     * <p>이 경로는 게이트웨이의 공개 라우트라 JWT 필터를 거치지 않는다. 그래서
     * 여기서 직접 검증한다. <b>refresh 토큰으로는 통과할 수 없다</b> — 그것은 화면
     * 세션의 근거가 아니다.
     */
    @GetMapping("/verify")
    public ResponseEntity<ApiResponse<Void>> verify(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(CommonErrorCode.TOKEN_INVALID);
        }
        JwtClaims claims = jwtProvider.parseClaims(authorization.substring(7));
        if (claims.tokenType() != TokenType.ACCESS) {
            throw new BusinessException(CommonErrorCode.TOKEN_INVALID);
        }
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    /** X-Forwarded-For 우선, 없으면 RemoteAddr. 다중 IP면 첫 번째(실제 클라이언트)를 사용. */
    private String extractIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
