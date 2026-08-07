package com.bank.customer.customer.service;

import com.bank.common.persistence.JpaAuditingConfig;
import com.bank.common.web.BusinessException;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.customer.dto.CustomerDetailResponse;
import com.bank.customer.party.domain.Party;
import com.bank.customer.party.domain.PartyPerson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CustomerQueryService#getCustomerDetail} 검증 — 회원 상세 화면의 데이터원.
 *
 * <p>한 사람의 정보가 customer·party·party_person 세 테이블에 흩어져 있어 합쳐 반환하는지,
 * party_person이 없는 경우(법인 등) 인적사항이 null로 채워지는지, 없는 고객이면 예외인지 확인한다.
 * 서비스가 3개 레포만 의존하므로 @DataJpaTest에 서비스 빈만 추가로 import한다.
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, CustomerQueryService.class})
@ActiveProfiles("test")
@DisplayName("CustomerQueryService.getCustomerDetail — 직원용 고객 상세")
class CustomerDetailQueryServiceTest {

    @Autowired
    TestEntityManager em;

    @Autowired
    CustomerQueryService queryService;

    // ── 공통 헬퍼 ─────────────────────────────────────────────────────────────

    private Party party(String name, String typeCode) {
        Party p = Party.builder()
                .partyTypeCode(typeCode)
                .partyName(name)
                .partyStatusCode(Party.STATUS_ACTIVE)
                .build();
        em.persist(p);
        return p;
    }

    private Customer customerOf(Long partyId) {
        Customer c = Customer.builder()
                .partyId(partyId)
                .customerGradeCode(Customer.GRADE_VIP)
                .creditRatingCode("AA")
                .creditEvaluationDate("20260101")
                .customerStatusCode(Customer.STATUS_ACTIVE)
                .mainCustomerYn(true)
                .smsReceiveYn(true)
                .emailReceiveYn(true)
                .postalReceiveYn(false)
                .email("vip@test.com")
                .phone("01012345678")
                .zipCode("06236")
                .address("서울시 강남구")
                .addressDetail("101동 202호")
                .joinChannelCode("MOBILE")
                .firstJoinDate("20180412")
                .joinedAt(OffsetDateTime.now())
                .build();
        em.persist(c);
        return c;
    }

    private void person(Long partyId, String birthDate, String gender, Boolean pep) {
        PartyPerson p = PartyPerson.builder()
                .partyId(partyId)
                .birthDate(birthDate)
                .genderCode(gender)
                .nationalityCode("KR")
                .isPepYn(pep)
                .build();
        em.persist(p);
    }

    // ── 테스트 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("customer·party·party_person을 합쳐 상세를 반환한다")
    void mergesThreeTables() {
        Party party = party("김철수", Party.TYPE_PERSONAL);
        Customer customer = customerOf(party.getPartyId());
        person(party.getPartyId(), "19850315", "M", false);
        em.flush();

        CustomerDetailResponse d = queryService.getCustomerDetail(customer.getCustomerId());

        // customer
        assertThat(d.customerId()).isEqualTo(customer.getCustomerId());
        assertThat(d.customerGradeCode()).isEqualTo(Customer.GRADE_VIP);
        assertThat(d.creditRatingCode()).isEqualTo("AA");
        assertThat(d.customerStatusCode()).isEqualTo(Customer.STATUS_ACTIVE);
        assertThat(d.firstJoinDate()).isEqualTo("20180412");
        // party
        assertThat(d.partyName()).isEqualTo("김철수");
        assertThat(d.partyStatusCode()).isEqualTo(Party.STATUS_ACTIVE);
        // party_person
        assertThat(d.birthDate()).isEqualTo("19850315");
        assertThat(d.genderCode()).isEqualTo("M");
        assertThat(d.nationalityCode()).isEqualTo("KR");
        assertThat(d.pep()).isFalse();
    }

    @Test
    @DisplayName("PEP 여부가 T이면 pep=true로 매핑된다")
    void pepFlagMapped() {
        Party party = party("정고위", Party.TYPE_PERSONAL);
        Customer customer = customerOf(party.getPartyId());
        person(party.getPartyId(), "19650208", "M", true);
        em.flush();

        CustomerDetailResponse d = queryService.getCustomerDetail(customer.getCustomerId());

        assertThat(d.pep()).isTrue();
    }

    @Test
    @DisplayName("party_person이 없으면 인적사항은 null로 채워진다")
    void noPartyPerson_personFieldsNull() {
        Party party = party("법인고객", Party.TYPE_ORGANIZATION);
        Customer customer = customerOf(party.getPartyId());
        em.flush();

        CustomerDetailResponse d = queryService.getCustomerDetail(customer.getCustomerId());

        assertThat(d.partyName()).isEqualTo("법인고객");
        assertThat(d.birthDate()).isNull();
        assertThat(d.genderCode()).isNull();
        assertThat(d.pep()).isNull();
    }

    @Test
    @DisplayName("soft delete된 고객은 조회되지 않아 예외가 발생한다")
    void softDeletedCustomer_throws() {
        Party party = party("삭제된고객", Party.TYPE_PERSONAL);
        Customer customer = customerOf(party.getPartyId());
        customer.softDelete(9001L);
        em.flush();

        assertThatThrownBy(() -> queryService.getCustomerDetail(customer.getCustomerId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("존재하지 않는 고객이면 예외가 발생한다")
    void notFound_throws() {
        assertThatThrownBy(() -> queryService.getCustomerDetail(999999L))
                .isInstanceOf(BusinessException.class);
    }
}
