package com.bank.payment.security;

/** 직원 운영 작업 감사 적재. INSERT 전용 표다. */
public interface EmployeeOperationMapper {

    void insertOperationLog(EmployeeOperationLogEntry entry);
}
