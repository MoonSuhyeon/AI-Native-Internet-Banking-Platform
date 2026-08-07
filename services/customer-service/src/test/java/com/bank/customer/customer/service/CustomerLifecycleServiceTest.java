package com.bank.customer.customer.service;

import com.bank.common.persistence.JpaAuditingConfig;
import com.bank.common.web.BusinessException;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.customer.domain.CustomerStatusHistory;
import com.bank.customer.customer.repository.CustomerRepository;
import com.bank.customer.customer.repository.CustomerStatusHistoryRepository;
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
 * {@link CustomerLifecycleService}의 정지/해지/해제 전이 검증 — 회원 상태 관리 화면의 백엔드.
 *
 * <p>정지(SUSPENDED)·해지(CLOSED) 전이가 상태·시점 컬럼을 올바로 세우고 상태이력을 남기는지,
 * 해제(reactivate)가 정지에서도 활성으로 되돌리는지, 잘못된 전이는 막는지 확인한다.
 * 서비스가 customer·status_history·grade_history 레포만 의존하므로 @DataJpaTest에 서비스 빈만 추가한다.
 *
 * <p>주의: H2 엔티티 DDL은 chk_customer_lifecycle CHECK를 생성하지 않으므로 DB 제약 위반은
 * 여기서 검증되지 않는다(불변식은 V13 마이그레이션·실 Postgres에서 보장). 본 테스트는 전이 로직·이력만 본다.
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, CustomerLifecycleService.class})
@ActiveProfiles("test")
@DisplayName("CustomerLifecycleService — 정지/해지/해제 전이")
class CustomerLifecycleServiceTest {

    @Autowired
    TestEntityManager em;

    @Autowired
    CustomerLifecycleService service;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    CustomerStatusHistoryRepository statusHistoryRepository;

    private Customer activeCustomer() {
        Customer c = Customer.builder()
                .partyId(1L)
                .customerStatusCode(Customer.STATUS_ACTIVE)
                .mainCustomerYn(true)
                .smsReceiveYn(true)
                .emailReceiveYn(true)
                .postalReceiveYn(false)
                .joinedAt(OffsetDateTime.now())
                .build();
        em.persist(c);
        em.flush();
        return c;
    }

    private CustomerStatusHistory latestHistory(Long customerId) {
        return statusHistoryRepository
                .findTopByCustomerIdOrderByCustomerStatusHistoryIdDesc(customerId)
                .orElseThrow();
    }

    // ── 정지 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("활성 고객을 정지하면 SUSPENDED·suspended_at·REGULATORY 이력이 남는다")
    void suspend_active() {
        Customer c = activeCustomer();

        service.suspend(c.getCustomerId(), "FDS Alert #2024 이상거래", 9001L);
        em.flush();
        em.clear();

        Customer reloaded = customerRepository.findById(c.getCustomerId()).orElseThrow();
        assertThat(reloaded.isSuspended()).isTrue();
        assertThat(reloaded.getSuspendedAt()).isNotNull();

        CustomerStatusHistory h = latestHistory(c.getCustomerId());
        assertThat(h.getPreviousCustomerStatusCode()).isEqualTo(Customer.STATUS_ACTIVE);
        assertThat(h.getCustomerStatusCode()).isEqualTo(Customer.STATUS_SUSPENDED);
        assertThat(h.getCustomerStatusChangeReasonCode()).isEqualTo(CustomerStatusHistory.REASON_REGULATORY);
        assertThat(h.getCustomerStatusChangeReasonDetail()).isEqualTo("FDS Alert #2024 이상거래");
        assertThat(h.getChangedByEmployeeId()).isEqualTo(9001L);
    }

    @Test
    @DisplayName("정지 해제(reactivate)는 SUSPENDED→ACTIVE로 되돌리고 suspended_at을 비운다")
    void reactivate_fromSuspended() {
        Customer c = activeCustomer();
        service.suspend(c.getCustomerId(), "동결", 9001L);
        em.flush();

        service.reactivate(c.getCustomerId(), "소명 완료", 9001L);
        em.flush();
        em.clear();

        Customer reloaded = customerRepository.findById(c.getCustomerId()).orElseThrow();
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getSuspendedAt()).isNull();

        CustomerStatusHistory h = latestHistory(c.getCustomerId());
        assertThat(h.getPreviousCustomerStatusCode()).isEqualTo(Customer.STATUS_SUSPENDED);
        assertThat(h.getCustomerStatusCode()).isEqualTo(Customer.STATUS_ACTIVE);
        assertThat(h.getCustomerStatusChangeReasonCode()).isEqualTo(CustomerStatusHistory.REASON_REACTIVATE);
    }

    @Test
    @DisplayName("이미 해지된 고객은 정지할 수 없다")
    void suspend_closed_throws() {
        Customer c = activeCustomer();
        service.close(c.getCustomerId(), "CUST_REQ", null, 9001L);
        em.flush();

        assertThatThrownBy(() -> service.suspend(c.getCustomerId(), "동결", 9001L))
                .isInstanceOf(BusinessException.class);
    }

    // ── 해지(탈퇴) ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("고객을 해지하면 CLOSED·closed_at·close_reason_code·privacy_expiry가 세팅되고 이력이 남는다")
    void close_active() {
        Customer c = activeCustomer();

        service.close(c.getCustomerId(), "CUST_REQ", "고객 탈퇴 요청", 9001L);
        em.flush();
        em.clear();

        Customer reloaded = customerRepository.findById(c.getCustomerId()).orElseThrow();
        assertThat(reloaded.isClosed()).isTrue();
        assertThat(reloaded.getClosedAt()).isNotNull();
        assertThat(reloaded.getCloseReasonCode()).isEqualTo("CUST_REQ");
        assertThat(reloaded.getPrivacyExpiryDate()).isNotNull();

        CustomerStatusHistory h = latestHistory(c.getCustomerId());
        assertThat(h.getPreviousCustomerStatusCode()).isEqualTo(Customer.STATUS_ACTIVE);
        assertThat(h.getCustomerStatusCode()).isEqualTo(Customer.STATUS_CLOSED);
    }

    @Test
    @DisplayName("이미 해지된 고객을 다시 해지하면 예외가 발생한다")
    void close_alreadyClosed_throws() {
        Customer c = activeCustomer();
        service.close(c.getCustomerId(), "CUST_REQ", null, 9001L);
        em.flush();

        assertThatThrownBy(() -> service.close(c.getCustomerId(), "CUST_REQ", null, 9001L))
                .isInstanceOf(BusinessException.class);
    }
}
