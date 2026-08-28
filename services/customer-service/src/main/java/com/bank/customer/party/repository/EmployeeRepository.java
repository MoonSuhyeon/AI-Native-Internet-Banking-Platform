package com.bank.customer.party.repository;

import com.bank.customer.party.domain.Employee;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    /**
     * 활성 직원 조회. employee row(직급·지점)와 더불어 활성 EMPLOYEE party_role 의 존재를
     * 함께 확인한다 — party_role 을 직원 여부의 정식 게이트로 삼는다.
     */
    @Query("""
            SELECT e FROM Employee e
            WHERE e.partyId = :partyId
              AND e.statusCode = 'ACTIVE'
              AND e.deletedAt IS NULL
              AND EXISTS (SELECT 1 FROM PartyRole r
                          WHERE r.partyId = e.partyId
                            AND r.roleTypeCode = 'EMPLOYEE'
                            AND r.roleStatusCode = 'ACTIVE'
                            AND r.deletedAt IS NULL)
            """)
    Optional<Employee> findActiveByPartyId(@Param("partyId") Long partyId);

    /**
     * 직원 식별자로 활성 직원을 찾는다. 게이트가 partyId 판본과 같아야 한다 —
     * 한쪽만 party_role 을 보면 같은 직원이 경로에 따라 활성/비활성으로 갈린다.
     */
    @Query("""
            SELECT e FROM Employee e
            WHERE e.employeeId = :employeeId
              AND e.statusCode = 'ACTIVE'
              AND e.deletedAt IS NULL
              AND EXISTS (SELECT 1 FROM PartyRole r
                          WHERE r.partyId = e.partyId
                            AND r.roleTypeCode = 'EMPLOYEE'
                            AND r.roleStatusCode = 'ACTIVE'
                            AND r.deletedAt IS NULL)
            """)
    Optional<Employee> findActiveByEmployeeId(@Param("employeeId") Long employeeId);
}
