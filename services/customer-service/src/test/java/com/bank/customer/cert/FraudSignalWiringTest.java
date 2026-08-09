package com.bank.customer.cert;

import com.bank.customer.customer.InternalHolderController;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.customer.dto.HolderInfoResponse;
import com.bank.customer.customer.repository.CustomerRepository;
import com.bank.customer.history.repository.CertificateUseRepository;
import com.bank.customer.history.repository.PasswordHistoryRepository;
import com.bank.customer.party.domain.Party;
import com.bank.customer.party.domain.PartyPerson;
import com.bank.customer.party.repository.PartyPersonRepository;
import com.bank.customer.party.repository.PartyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 조사 에이전트가 쓰는 두 신호가 실제 데이터에서 나오는지 검증한다.
 *
 * <p><b>왜 필요한가.</b> 둘 다 오랫동안 {@code false} 로 하드코딩돼 있었다.
 * 컴파일도 되고 API 도 200 을 주며 화면도 멀쩡하다. 다만 <b>조사 에이전트의 판단 근거
 * 두 개가 늘 꺼져 있었다</b> — 에이전트를 아무리 다듬어도 입력이 없으면 소용없다.
 *
 * <ul>
 *   <li><b>사망 여부</b>는 결정적 사실이다. 참이면 조사가 예산·신뢰도를 보지 않고
 *       즉시 종료한다(fail-closed). 늘 false 면 그 경로가 한 번도 열리지 않는다.</li>
 *   <li><b>최근 비밀번호 변경</b>은 계정탈취(H2) 가설의 근거다. 탈취의 전형적인 순서가
 *       "탈취 → 비밀번호 변경 → 이체" 라, 인증 실패와 함께 볼 때 신호가 강해진다.</li>
 * </ul>
 *
 * <p>여기서는 "값이 흐르는가" 만 본다. 판정 로직은 에이전트 쪽에서 검증한다.
 */
class FraudSignalWiringTest {

    private static final Long CUSTOMER_ID = 9111L;
    private static final Long PARTY_ID = 501L;

    private PartyPersonRepository partyPersonRepository;
    private InternalHolderController holderController;

    private PasswordHistoryRepository passwordHistoryRepository;
    private InternalAuthEventsController authEventsController;

    @BeforeEach
    void setUp() {
        CustomerRepository customerRepository = mock(CustomerRepository.class);
        PartyRepository partyRepository = mock(PartyRepository.class);
        partyPersonRepository = mock(PartyPersonRepository.class);
        holderController = new InternalHolderController(
                customerRepository, partyRepository, partyPersonRepository);

        CertificateUseRepository certificateUseRepository = mock(CertificateUseRepository.class);
        passwordHistoryRepository = mock(PasswordHistoryRepository.class);
        authEventsController = new InternalAuthEventsController(
                certificateUseRepository, passwordHistoryRepository);

        Customer customer = mock(Customer.class);
        when(customer.getPartyId()).thenReturn(PARTY_ID);
        when(customerRepository.findByCustomerIdAndDeletedAtIsNull(CUSTOMER_ID))
                .thenReturn(Optional.of(customer));

        Party party = mock(Party.class);
        when(party.getPartyId()).thenReturn(PARTY_ID);
        when(party.getPartyName()).thenReturn("홍길동");
        when(party.getPartyTypeCode()).thenReturn("PERSON");
        when(partyRepository.findByPartyIdAndDeletedAtIsNull(PARTY_ID))
                .thenReturn(Optional.of(party));

        when(certificateUseRepository.countCertFailuresByCustomerSince(anyLong(), any()))
                .thenReturn(0L);
    }

    private void givenDeathDate(String deathDate) {
        PartyPerson person = mock(PartyPerson.class);
        when(person.getDeathDate()).thenReturn(deathDate);
        when(partyPersonRepository.findByPartyIdAndDeletedAtIsNull(PARTY_ID))
                .thenReturn(Optional.of(person));
    }

    private HolderInfoResponse holderInfo() {
        HolderInfoResponse body = holderController
                .getHolderInfo(CUSTOMER_ID, "payment-service").getBody();
        assertThat(body).isNotNull();
        return body;
    }

    @Test
    @DisplayName("사망일이 있으면 사망으로 나간다 — 하드코딩 false 로 되돌아가면 여기서 걸린다")
    void deathDateProducesDeceasedFlag() {
        givenDeathDate("20250401");

        assertThat(holderInfo().deceasedFlag())
                .as("조사 에이전트는 이 값을 결정적 사실로 쓴다 — 늘 false 면 "
                    + "fail-closed 경로가 한 번도 열리지 않는다")
                .isTrue();
    }

    @Test
    @DisplayName("사망일이 없으면 false")
    void livingCustomerIsNotDeceased() {
        givenDeathDate(null);

        assertThat(holderInfo().deceasedFlag()).isFalse();
    }

    @Test
    @DisplayName("개인 정보가 없으면 false — '확인했더니 생존' 이 아니라 '해당 없음' 이다")
    void nonPersonPartyIsNotDeceased() {
        // 법인 등은 party_person 행이 없다. 여기서 예외를 던지면 법인 이체가 막힌다.
        when(partyPersonRepository.findByPartyIdAndDeletedAtIsNull(PARTY_ID))
                .thenReturn(Optional.empty());

        assertThat(holderInfo().deceasedFlag()).isFalse();
    }

    @Test
    @DisplayName("창 안에 비밀번호 변경이 있으면 true 로 나간다")
    void recentPasswordChangeIsReported() {
        when(passwordHistoryRepository.countChangesSince(eq(CUSTOMER_ID), any())).thenReturn(2L);

        var body = authEventsController.getAuthEvents(CUSTOMER_ID, 24).getBody();

        assertThat(body).isNotNull();
        assertThat(body.data().passwordChangedRecently())
                .as("계정탈취의 전형적 순서가 '탈취 → 비밀번호 변경 → 이체' 다. "
                    + "늘 false 면 그 축의 가설이 오르지 않는다")
                .isTrue();
    }

    @Test
    @DisplayName("창 안에 변경이 없으면 false")
    void noRecentChangeIsFalse() {
        when(passwordHistoryRepository.countChangesSince(eq(CUSTOMER_ID), any())).thenReturn(0L);

        var body = authEventsController.getAuthEvents(CUSTOMER_ID, 24).getBody();

        assertThat(body).isNotNull();
        assertThat(body.data().passwordChangedRecently()).isFalse();
    }

    @Test
    @DisplayName("조회 구간이 windowHours 를 따른다 — 창을 무시하면 옛 변경도 최근으로 잡힌다")
    void windowIsHonored() {
        ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
        when(passwordHistoryRepository.countChangesSince(eq(CUSTOMER_ID), since.capture()))
                .thenReturn(0L);

        authEventsController.getAuthEvents(CUSTOMER_ID, 1);

        assertThat(since.getValue())
                .as("1시간 창이면 조회 시작점도 약 1시간 전이어야 한다")
                .isAfter(OffsetDateTime.now().minusHours(2))
                .isBefore(OffsetDateTime.now());
    }
}
