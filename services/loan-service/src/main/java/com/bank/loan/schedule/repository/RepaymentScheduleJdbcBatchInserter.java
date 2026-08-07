package com.bank.loan.schedule.repository;

import com.bank.common.persistence.CurrentActorProvider;
import com.bank.loan.schedule.domain.RepaymentSchedule;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 상환 스케줄 회차 일괄 insert 전용 JDBC 리포지토리.
 *
 * RepaymentSchedule 은 IDENTITY 채번 — saveAll() 을 써도 Hibernate 가 batch insert 를 비활성화하고
 * 회차 수(36/60/360) 만큼 개별 insert 가 날아간다. 인출은 사용자가 기다리는 동기 흐름이라
 * 회차 수가 늘수록 레이턴시가 선형 증가한다.
 *
 * 본 클래스는 같은 트랜잭션 안에서 JdbcTemplate.batchUpdate 로 한 번에 보낸다.
 *
 * 감사 컬럼: AuditingEntityListener 는 JPA 라이프사이클에서만 동작하므로
 * created_at/by, updated_at/by, version 을 호출 시점에 직접 채운다.
 */
@Repository
@RequiredArgsConstructor
public class RepaymentScheduleJdbcBatchInserter {

    private static final String INSERT_SQL = """
            INSERT INTO repayment_schedule (
                cntr_id, installment_no, due_date,
                scheduled_principal, scheduled_interest, scheduled_total,
                remaining_balance, applied_rate_bps,
                rsch_status_cd, rsch_version_cd, holiday_adjusted_yn,
                created_at, created_by, updated_at, updated_by, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final CurrentActorProvider currentActor;

    public void batchInsert(List<RepaymentSchedule> rows) {
        if (rows.isEmpty()) return;

        final OffsetDateTime now = OffsetDateTime.now();
        final Long actorId = currentActor.currentActorId();

        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                RepaymentSchedule r = rows.get(i);
                ps.setLong(1, r.getCntrId());
                ps.setInt(2, r.getInstallmentNo());
                ps.setString(3, r.getDueDate());
                ps.setLong(4, r.getScheduledPrincipal());
                ps.setLong(5, r.getScheduledInterest());
                ps.setLong(6, r.getScheduledTotal());
                ps.setLong(7, r.getRemainingBalance());
                ps.setInt(8, r.getAppliedRateBps());
                ps.setString(9, r.getRschStatusCd());
                ps.setString(10, r.getRschVersionCd());
                ps.setBoolean(11, Boolean.TRUE.equals(r.getHolidayAdjustedYn()));
                ps.setObject(12, now);
                ps.setLong(13, actorId);
                ps.setObject(14, now);
                ps.setLong(15, actorId);
                ps.setInt(16, 0);
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }
}
