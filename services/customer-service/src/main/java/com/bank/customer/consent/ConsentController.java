package com.bank.customer.consent;

import com.bank.common.web.ApiResponse;
import com.bank.customer.consent.dto.ConsentRecordRequest;
import com.bank.customer.consent.dto.ConsentResponse;
import com.bank.customer.consent.dto.ConsentWithdrawRequest;
import com.bank.customer.consent.dto.TermsTemplateResponse;
import com.bank.customer.consent.repository.TermsTemplateRepository;
import com.bank.customer.consent.service.ConsentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 약관 동의 — 받고, 보고, 거둔다.
 *
 * <p>동의는 고객 본인에 대한 사실이므로 게이트웨이가 넣어 준 {@code X-Customer-Id} 만
 * 믿는다. 요청 본문의 고객번호를 받지 않는 이유는, 받는 순간 남의 동의를 대신 기록할
 * 수 있게 되기 때문이다.
 */
@RestController
@RequestMapping("/api/v1/customers/me/consents")
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentService consentService;
    private final TermsTemplateRepository templateRepository;

    /** 이 업무에서 받아야 할 약관 목록. 화면이 체크박스를 그릴 때 쓴다. */
    @GetMapping("/terms")
    public ResponseEntity<ApiResponse<List<TermsTemplateResponse>>> terms(
            @RequestParam String bizDivCd) {
        return ResponseEntity.ok(ApiResponse.ok(
                templateRepository.findByBizDivCdAndActiveYnTrueOrderByTermsNo(bizDivCd).stream()
                        .map(TermsTemplateResponse::of)
                        .toList()));
    }

    /** 한 화면에서 받은 동의를 한 번에 기록한다. */
    @PostMapping
    public ResponseEntity<ApiResponse<List<ConsentResponse>>> record(
            @RequestHeader("X-Customer-Id") Long customerId,
            @Valid @RequestBody ConsentRecordRequest request,
            HttpServletRequest http) {
        return ResponseEntity.ok(ApiResponse.ok(
                consentService.record(customerId, request, clientIp(http))));
    }

    /** 내 동의 이력. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<ConsentResponse>>> history(
            @RequestHeader("X-Customer-Id") Long customerId,
            @RequestParam(required = false) String bizDivCd) {
        return ResponseEntity.ok(ApiResponse.ok(consentService.history(customerId, bizDivCd)));
    }

    /** 철회. 동의했던 사실은 남고 효력만 사라진다. */
    @PostMapping("/{consentId}/withdrawal")
    public ResponseEntity<ApiResponse<ConsentResponse>> withdraw(
            @RequestHeader("X-Customer-Id") Long customerId,
            @PathVariable Long consentId,
            @Valid @RequestBody ConsentWithdrawRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                consentService.withdraw(customerId, consentId, request.reason())));
    }

    /**
     * 요청자의 IP. 프록시를 거치면 원 주소가 헤더에 온다.
     *
     * <p>맨 앞 값만 쓴다 — {@code X-Forwarded-For} 는 거쳐 온 순서대로 이어 붙으므로
     * 뒤쪽은 중간 프록시다. 다만 이 헤더는 클라이언트가 지어낼 수 있으므로, 여기 담기는
     * 값은 <b>참고</b>이지 신원이 아니다. 신원은 게이트웨이가 넣은 헤더로만 판단한다.
     */
    private static String clientIp(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return http.getRemoteAddr();
    }
}
