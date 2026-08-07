package com.bank.customer.customer.service;

import com.bank.common.persistence.JpaAuditingConfig;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.customer.domain.CustomerAccessLog;
import com.bank.customer.customer.dto.AccessLogResponse;
import com.bank.customer.party.domain.Employee;
import com.bank.customer.party.domain.Party;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CustomerAccessLogService} — 조회 접근 감사로그 기록·조회.
 *
 * <p>기록 시점에 직원명·역할·지점·고객명을 스냅샷하는지, 키워드 검색(직원명·고객명·행위)이 동작하는지,
 * 직원 신원이 없으면(null) 적재하지 않는지 검증한다.
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, CustomerAccessLogService.class})
@ActiveProfiles("test")
@DisplayName("CustomerAccessLogService — 접근 감사로그 기록/조회")
class CustomerAccessLogServiceTest {

    @Autowired
    TestEntityManager em;

    @Autowired
    CustomerAccessLogService service;

    private Employee persistEmployee(String name, String grade, String branch) {
        Party p = em.persist(Party.builder()
                .partyTypeCode(Party.TYPE_PERSONAL).partyName(name).partyStatusCode(Party.STATUS_ACTIVE).build());
        return em.persist(Employee.builder()
                .partyId(p.getPartyId()).branchCode(branch).gradeCode(grade).statusCode(Employee.STATUS_ACTIVE).build());
    }

    private Customer persistCustomer(String name) {
        Party p = em.persist(Party.builder()
                .partyTypeCode(Party.TYPE_PERSONAL).partyName(name).partyStatusCode(Party.STATUS_ACTIVE).build());
        return em.persist(Customer.builder()
                .partyId(p.getPartyId()).customerStatusCode(Customer.STATUS_ACTIVE)
                .mainCustomerYn(true).smsReceiveYn(true).emailReceiveYn(true).postalReceiveYn(false)
                .joinedAt(OffsetDateTime.now()).build());
    }

    @Test
    @DisplayName("기록 시 직원명·역할·고객명을 스냅샷하고, 키워드로 검색된다")
    void record_snapshotsNames_andSearchable() {
        Employee emp = persistEmployee("김감사", "COMPLIANCE", "0000");
        Customer cust = persistCustomer("홍길동");
        em.flush();

        service.record(emp.getEmployeeId(), cust.getCustomerId(),
                CustomerAccessLog.ACTION_CONTACT_VIEW, "민원 처리");

        var all = service.search(null, null, PageRequest.of(0, 10)).getContent();
        assertThat(all).hasSize(1);
        AccessLogResponse r = all.get(0);
        assertThat(r.accessorEmployeeId()).isEqualTo(emp.getEmployeeId());
        assertThat(r.accessorName()).isEqualTo("김감사");
        assertThat(r.accessorRole()).isEqualTo("COMPLIANCE");
        assertThat(r.targetCustomerName()).isEqualTo("홍길동");
        assertThat(r.accessActionCode()).isEqualTo(CustomerAccessLog.ACTION_CONTACT_VIEW);
        assertThat(r.accessReason()).isEqualTo("민원 처리");

        // 고객명 부분일치
        assertThat(service.search("홍길", null, PageRequest.of(0, 10)).getContent()).hasSize(1);
        // 일치 없음
        assertThat(service.search("없는이름", null, PageRequest.of(0, 10)).getContent()).isEmpty();
    }

    @Test
    @DisplayName("직원 신원(accessorEmployeeId)이 없으면 적재하지 않는다")
    void record_skips_whenNoEmployee() {
        Customer cust = persistCustomer("홍길동");
        em.flush();

        service.record(null, cust.getCustomerId(), CustomerAccessLog.ACTION_CUSTOMER_DETAIL, null);

        assertThat(service.search(null, null, PageRequest.of(0, 10)).getContent()).isEmpty();
    }
}
