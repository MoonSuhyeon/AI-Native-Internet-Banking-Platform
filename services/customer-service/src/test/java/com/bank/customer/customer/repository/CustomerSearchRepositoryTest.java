package com.bank.customer.customer.repository;

import com.bank.common.persistence.JpaAuditingConfig;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.customer.dto.CustomerSummaryResponse;
import com.bank.customer.party.domain.Party;
import com.bank.customer.party.domain.PartyRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CustomerRepository#searchCustomers} 검증 — 직원용 고객 목록·검색의 진입점.
 *
 * <p>이름은 party 도메인에 있어 Customer ⨝ Party 엔티티 조인으로 가져오므로, 조인·DTO 투영·
 * 선택 필터(keyword/status/grade)·PERSONAL 한정·soft delete 제외·정렬이 실제 영속성 컨텍스트
 * (H2 PostgreSQL 모드)에서 동작하는지 확인한다.
 */
@DataJpaTest
@Import(JpaAuditingConfig.class)
@ActiveProfiles("test")
@DisplayName("CustomerRepository.searchCustomers — 직원용 고객 목록·검색")
class CustomerSearchRepositoryTest {

    private static final Pageable FIRST_20 = PageRequest.of(0, 20);

    @Autowired
    TestEntityManager em;

    @Autowired
    CustomerRepository customerRepository;

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

    /** PERSONAL 파티 1건과 그에 묶인 고객 1건을 생성한다. */
    private Customer personalCustomer(String name, String status, String grade, String phone) {
        Party p = party(name, Party.TYPE_PERSONAL);
        return customerOf(p.getPartyId(), status, grade, phone);
    }

    private Customer customerOf(Long partyId, String status, String grade, String phone) {
        Customer c = Customer.builder()
                .partyId(partyId)
                .customerGradeCode(grade)
                .customerStatusCode(status)
                .mainCustomerYn(true)
                .smsReceiveYn(true)
                .emailReceiveYn(true)
                .postalReceiveYn(false)
                .phone(phone)
                .email("user@test.com")
                .joinedAt(OffsetDateTime.now())
                .build();
        em.persist(c);
        return c;
    }

    /**
     * 직원 역할(party_role 'EMPLOYEE')을 가진 PERSONAL 파티 + 그에 묶인 고객 1건.
     * (직원도 정본화 후 PERSONAL) {@code roleStatus} 로 현직(ACTIVE)·전직(CLOSED)을 구분한다.
     */
    private void employeeCustomer(String name, String phone, String roleStatus) {
        Party p = party(name, Party.TYPE_PERSONAL);
        customerOf(p.getPartyId(), Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, phone);
        em.persist(PartyRole.builder()
                .partyId(p.getPartyId())
                .roleTypeCode(PartyRole.TYPE_EMPLOYEE)
                .roleStatusCode(roleStatus)
                .roleStartDate("20260101")
                .build());
    }

    // ── 테스트 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("이름 부분일치로 검색하면 해당 고객만 반환하고 이름(party)이 조인되어 채워진다")
    void keyword_byNamePartialMatch() {
        personalCustomer("김철수", Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, "01011112222");
        personalCustomer("이영희", Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, "01033334444");
        em.flush();

        Page<CustomerSummaryResponse> result =
                customerRepository.searchCustomers("철수", null, null, FIRST_20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).partyName()).isEqualTo("김철수");
    }

    @Test
    @DisplayName("현직 직원(party_role EMPLOYEE·ACTIVE)은 party_type 가 PERSONAL 이어도 고객 목록에서 제외된다")
    void excludesEmployeesByPartyRole() {
        personalCustomer("일반고객", Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, "01000000001");
        employeeCustomer("심사직원", "01099998888", PartyRole.STATUS_ACTIVE);
        em.flush();

        Page<CustomerSummaryResponse> result =
                customerRepository.searchCustomers(null, null, null, FIRST_20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).partyName()).isEqualTo("일반고객");
    }

    @Test
    @DisplayName("전직 직원(EMPLOYEE 역할이 CLOSED)은 일반 고객으로 다시 목록에 노출된다")
    void includesFormerEmployeesWhoseRoleIsClosed() {
        personalCustomer("일반고객", Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, "01000000001");
        employeeCustomer("퇴직자", "01077776666", PartyRole.STATUS_CLOSED);
        em.flush();

        Page<CustomerSummaryResponse> result =
                customerRepository.searchCustomers(null, null, null, FIRST_20);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent()).extracting(CustomerSummaryResponse::partyName)
                .containsExactlyInAnyOrder("일반고객", "퇴직자");
    }

    @Test
    @DisplayName("전화번호 부분일치로도 검색된다")
    void keyword_byPhonePartialMatch() {
        personalCustomer("김철수", Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, "01011112222");
        personalCustomer("이영희", Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, "01033334444");
        em.flush();

        Page<CustomerSummaryResponse> result =
                customerRepository.searchCustomers("3333", null, null, FIRST_20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).partyName()).isEqualTo("이영희");
    }

    @Test
    @DisplayName("status 필터는 해당 상태 고객만 반환한다")
    void statusFilter() {
        personalCustomer("활성고객", Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, "01000000001");
        personalCustomer("휴면고객", Customer.STATUS_DORMANT, Customer.GRADE_NORMAL, "01000000002");
        em.flush();

        Page<CustomerSummaryResponse> result =
                customerRepository.searchCustomers(null, Customer.STATUS_DORMANT, null, FIRST_20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).partyName()).isEqualTo("휴면고객");
        assertThat(result.getContent().get(0).customerStatusCode()).isEqualTo(Customer.STATUS_DORMANT);
    }

    @Test
    @DisplayName("grade 필터는 해당 등급 고객만 반환한다")
    void gradeFilter() {
        personalCustomer("일반고객", Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, "01000000001");
        personalCustomer("VIP고객",  Customer.STATUS_ACTIVE, Customer.GRADE_VIP,    "01000000002");
        em.flush();

        Page<CustomerSummaryResponse> result =
                customerRepository.searchCustomers(null, null, Customer.GRADE_VIP, FIRST_20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).partyName()).isEqualTo("VIP고객");
    }

    @Test
    @DisplayName("필터가 모두 null이면 PERSONAL 고객 전체를 customer_id 역순으로 반환한다")
    void noFilter_returnsAllPersonal_orderedByIdDesc() {
        Customer c1 = personalCustomer("고객1", Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, "01000000001");
        Customer c2 = personalCustomer("고객2", Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, "01000000002");
        Customer c3 = personalCustomer("고객3", Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, "01000000003");
        em.flush();

        Page<CustomerSummaryResponse> result =
                customerRepository.searchCustomers(null, null, null, FIRST_20);

        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getContent()).extracting(CustomerSummaryResponse::customerId)
                .containsExactly(c3.getCustomerId(), c2.getCustomerId(), c1.getCustomerId());
    }

    @Test
    @DisplayName("ORGANIZATION 파티의 고객은 결과에서 제외된다")
    void organizationParty_isExcluded() {
        personalCustomer("개인고객", Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, "01000000001");
        Party org = party("법인고객", Party.TYPE_ORGANIZATION);
        customerOf(org.getPartyId(), Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, "01000000002");
        em.flush();

        Page<CustomerSummaryResponse> result =
                customerRepository.searchCustomers(null, null, null, FIRST_20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).partyName()).isEqualTo("개인고객");
    }

    @Test
    @DisplayName("soft delete된 고객은 결과에서 제외된다")
    void softDeletedCustomer_isExcluded() {
        personalCustomer("살아있는고객", Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, "01000000001");
        Customer deleted = personalCustomer("삭제된고객", Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, "01000000002");
        deleted.softDelete(9001L);
        em.flush();

        Page<CustomerSummaryResponse> result =
                customerRepository.searchCustomers(null, null, null, FIRST_20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).partyName()).isEqualTo("살아있는고객");
    }

    @Test
    @DisplayName("페이지 크기를 넘으면 분할되고 총 개수는 유지된다")
    void pagination_splitsBySize() {
        for (int i = 0; i < 3; i++) {
            personalCustomer("고객" + i, Customer.STATUS_ACTIVE, Customer.GRADE_NORMAL, "0100000000" + i);
        }
        em.flush();

        Page<CustomerSummaryResponse> page0 =
                customerRepository.searchCustomers(null, null, null, PageRequest.of(0, 2));

        assertThat(page0.getTotalElements()).isEqualTo(3);
        assertThat(page0.getContent()).hasSize(2);
        assertThat(page0.getTotalPages()).isEqualTo(2);
    }
}
