package com.bank.payment.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직원 운영 작업 감사를 <b>호출부와 분리된 트랜잭션</b>으로 남긴다.
 *
 * <p>별도 빈인 것이 핵심이다. 같은 클래스 안에서 부르면 스프링 프록시를 거치지 않아
 * {@code REQUIRES_NEW} 가 적용되지 않는다. 그러면 거절을 기록한 뒤 호출부가 예외를
 * 던질 때 감사까지 같이 롤백되어, <b>막은 시도가 아무 흔적도 남기지 않는다.</b>
 *
 * <p>{@code ServiceAuthorizationAuditWriter} · {@code AccessAuditWriter} 가 같은
 * 이유로 분리돼 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeOperationAuditWriter {

    private final EmployeeOperationMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(EmployeeOperationLogEntry entry) {
        try {
            mapper.insertOperationLog(entry);
        } catch (Exception e) {
            // 감사 저장이 깨졌다고 운영 작업이 멈추면 장애 하나가 서비스 전체로 번진다.
            // 대신 조용히 넘어가지는 않는다 — 감사 공백을 아무도 모르는 것이 더 나쁘다.
            log.error("직원 운영 감사 기록 실패 — employee={} operation={} decision={}",
                    entry.employeeId(), entry.operation(), entry.decision(), e);
        }
    }
}
