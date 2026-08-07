package com.bank.customer.customer.service;

import com.bank.common.persistence.JpaAuditingConfig;
import com.bank.customer.customer.domain.Customer;
import com.bank.customer.customer.dto.JoinStatsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CustomerQueryService#getJoinStats} 검증 — 가입 대시보드의 집계 데이터원.
 *
 * <p>총원·오늘/이번달 가입 수·상태/등급/채널별 분포가 customer 집계로 올바로 산출되는지 확인한다.
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, CustomerQueryService.class})
@ActiveProfiles("test")
@DisplayName("CustomerQueryService.getJoinStats — 가입 현황 통계")
class JoinStatsServiceTest {

    @Autowired
    TestEntityManager em;

    @Autowired
    CustomerQueryService queryService;

    private void customer(Long partyId, String status, String grade, String channel) {
        Customer c = Customer.builder()
                .partyId(partyId)
                .customerStatusCode(status)
                .customerGradeCode(grade)
                .joinChannelCode(channel)
                .mainCustomerYn(true)
                .smsReceiveYn(true)
                .emailReceiveYn(true)
                .postalReceiveYn(false)
                .joinedAt(OffsetDateTime.now())
                .build();
        em.persist(c);
    }

    private Map<String, Long> asMap(java.util.List<JoinStatsResponse.CodeCount> rows) {
        return rows.stream().collect(Collectors.toMap(
                JoinStatsResponse.CodeCount::code, JoinStatsResponse.CodeCount::count));
    }

    @Test
    @DisplayName("총원·이번달 가입·상태/채널 분포를 집계한다")
    void aggregates() {
        customer(1L, Customer.STATUS_ACTIVE,  Customer.GRADE_NORMAL, "MOBILE");
        customer(2L, Customer.STATUS_ACTIVE,  Customer.GRADE_VIP,    "MOBILE");
        customer(3L, Customer.STATUS_ACTIVE,  Customer.GRADE_NORMAL, "WEB");
        customer(4L, Customer.STATUS_DORMANT, Customer.GRADE_NORMAL, "WEB");
        em.flush();

        JoinStatsResponse stats = queryService.getJoinStats();

        assertThat(stats.total()).isEqualTo(4);
        assertThat(stats.joinedThisMonth()).isEqualTo(4); // 모두 방금 가입
        assertThat(stats.joinedToday()).isEqualTo(4);

        assertThat(asMap(stats.byStatus()))
                .containsEntry(Customer.STATUS_ACTIVE, 3L)
                .containsEntry(Customer.STATUS_DORMANT, 1L);
        assertThat(asMap(stats.byChannel()))
                .containsEntry("MOBILE", 2L)
                .containsEntry("WEB", 2L);
        assertThat(asMap(stats.byGrade()))
                .containsEntry(Customer.GRADE_NORMAL, 3L)
                .containsEntry(Customer.GRADE_VIP, 1L);
    }

    @Test
    @DisplayName("고객이 없으면 총원 0, 분포는 비어있다")
    void empty() {
        JoinStatsResponse stats = queryService.getJoinStats();

        assertThat(stats.total()).isZero();
        assertThat(stats.byStatus()).isEmpty();
    }
}
