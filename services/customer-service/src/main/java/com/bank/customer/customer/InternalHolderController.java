package com.bank.customer.customer;

import com.bank.common.web.BusinessException;
import com.bank.common.web.CommonErrorCode;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.customer.dto.HolderInfoResponse;
import com.bank.customer.customer.repository.CustomerRepository;
import com.bank.customer.party.domain.Party;
import com.bank.customer.party.repository.PartyPersonRepository;
import com.bank.customer.party.repository.PartyRepository;
import com.bank.customer.support.CustomerErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예금주 확인 — 서비스 간 내부 호출용 읽기 전용 API (A-2 흐름).
 *
 * <p>호출 체인: payment-service → deposit-service → customer-service(본 엔드포인트).
 * deposit 이 Feign 으로 호출해 계좌 예금주 실명을 채우고, payment 는 그 값으로 이체 전
 * 예금주 일치 여부를 검증한다.
 *
 * <p><b>경로 컨벤션</b>: 직원 전용 {@code /api/v1/internal/**}(SecurityConfig 가 직무 역할로
 * 게이팅) 과 구분되는 <em>서비스 간</em> 내부 경로 {@code /api/internal/**}(v1 없음)을 쓴다.
 * 후자는 SecurityConfig 매처에 걸리지 않아 {@code permitAll} 로 떨어지므로(게이트웨이
 * api-gateway application.yml 에 문서화된 컨벤션) 직원 토큰 없는 서비스 호출이 통과한다.
 * 직원 인가 대신 {@code X-Caller-Service} 헤더 존재만 확인한다(내부망 신뢰 전제).
 */
@RestController
@RequestMapping("/api/internal/customers")
@RequiredArgsConstructor
public class InternalHolderController {

    private final CustomerRepository customerRepository;
    private final PartyRepository partyRepository;
    private final PartyPersonRepository partyPersonRepository;

    @GetMapping("/{customerId}/holder-info")
    public ResponseEntity<HolderInfoResponse> getHolderInfo(
            @PathVariable Long customerId,
            @RequestHeader(name = "X-Caller-Service", required = false) String callerService) {

        if (callerService == null || callerService.isBlank()) {
            throw new BusinessException(CommonErrorCode.COMMON_403);
        }

        Customer customer = customerRepository.findByCustomerIdAndDeletedAtIsNull(customerId)
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_002));
        Party party = partyRepository.findByPartyIdAndDeletedAtIsNull(customer.getPartyId())
                .orElseThrow(() -> new BusinessException(CustomerErrorCode.CUST_002));

        // 사망 여부. party_person.death_date 가 채워져 있으면 사망으로 본다.
        //
        // 개인이 아닌 명의자(법인 등)는 party_person 행이 없다. 그 경우 false 다 —
        // "확인했는데 살아 있다" 가 아니라 "개인이 아니라 해당 없음" 이라는 뜻이다.
        //
        // 조사 에이전트는 이 값을 결정적 사실로 쓴다(fail-closed). 그래서 값이 틀리는 것보다
        // 조회에 실패하는 것이 낫고, 조회 자체가 안 되면 위에서 이미 404 로 끊긴다.
        boolean deceasedFlag = partyPersonRepository
                .findByPartyIdAndDeletedAtIsNull(party.getPartyId())
                .map(person -> person.getDeathDate() != null && !person.getDeathDate().isBlank())
                .orElse(false);
        return ResponseEntity.ok(new HolderInfoResponse(
                String.valueOf(customerId),
                party.getPartyName(),
                holderType(party.getPartyTypeCode()),
                deceasedFlag));
    }

    /** party_type_code(PERSONAL/ORGANIZATION) → 예금주 유형. Party 모델에 JOINT 없음 → 개인 기본값. */
    private static String holderType(String partyTypeCode) {
        return Party.TYPE_ORGANIZATION.equals(partyTypeCode) ? "CORPORATE" : "INDIVIDUAL";
    }
}
