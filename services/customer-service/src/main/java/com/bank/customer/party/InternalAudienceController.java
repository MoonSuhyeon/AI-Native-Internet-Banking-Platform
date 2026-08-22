package com.bank.customer.party;

import com.bank.common.web.ApiResponse;
import com.bank.customer.customer.repository.CustomerRepository;
import com.bank.customer.party.domain.AudienceBand;
import com.bank.customer.party.domain.PartyPerson;
import com.bank.customer.party.repository.PartyPersonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 안내 대상 구간 조회 — 직원 전용 읽기 전용 내부 API.
 *
 * <p><b>무엇에 쓰는가.</b> 이상거래로 이체가 멈췄을 때 화면에 띄우는 안내를 조사
 * 에이전트가 연령대에 맞춰 다듬는다. 고령층에게는 짧은 문장과 전화 확인 한 가지로,
 * 사회초년생에게는 기관 사칭 수법을 짚어서. 그 판단에 필요한 것이 이 값이다.
 *
 * <p><b>생년월일을 주지 않는다.</b> 구간만 준다. 안내문을 만드는 데 날짜는 필요
 * 없고, 넘기면 로그·트레이스·LLM 프롬프트에 남는다 — 자세한 이유는
 * {@link AudienceBand} 주석에 있다.
 *
 * <p>{@code /api/v1/internal/**} 는 SecurityConfig 에서 직원 역할로 보호된다.
 * 고객이 자기 구간을 직접 조회할 일은 없다 — 화면은 이 값을 보지 않고, 이미
 * 다듬어진 안내문만 받는다.
 */
@RestController
@RequestMapping("/api/v1/internal/audience")
@RequiredArgsConstructor
public class InternalAudienceController {

    private final CustomerRepository customerRepository;
    private final PartyPersonRepository partyPersonRepository;

    /**
     * 고객의 안내 대상 구간.
     *
     * <p>고객이 없거나 개인 party 가 없으면 {@link AudienceBand#UNKNOWN} 이다.
     * 404 를 내지 않는 이유는, 부르는 쪽이 안내문을 만들다 말고 실패하면 <b>안내가
     * 통째로 사라지기</b> 때문이다. 맞춤이 안 되는 것과 안내가 없는 것은 다르다.
     */
    @GetMapping("/{customerId}")
    public ResponseEntity<ApiResponse<AudienceResponse>> audience(@PathVariable Long customerId) {
        AudienceBand band = customerRepository.findById(customerId)
                .map(c -> c.getPartyId())
                .flatMap(partyPersonRepository::findByPartyIdAndDeletedAtIsNull)
                .map(PartyPerson::getBirthDate)
                .map(ymd -> AudienceBand.fromBirthDate(ymd, LocalDate.now()))
                .orElse(AudienceBand.UNKNOWN);

        return ResponseEntity.ok(ApiResponse.ok(new AudienceResponse(customerId, band.name())));
    }

    /** @param band {@link AudienceBand} 이름. 문자열로 내보내 호출부가 이 열거형에 묶이지 않게 한다. */
    public record AudienceResponse(Long customerId, String band) {
    }
}
