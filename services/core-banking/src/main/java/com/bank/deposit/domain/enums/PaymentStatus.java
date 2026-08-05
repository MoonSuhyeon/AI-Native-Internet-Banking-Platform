package com.bank.deposit.domain.enums;

public enum PaymentStatus {
    PENDING,    // 납입 예정
    PAID,       // 납입 완료
    OVERDUE,    // 납입 지연 (수동 납입 기한 경과)
    FAILED,     // 자동이체 실패
    SUSPENDED   // 정지 (연속 실패로 이체 중단)
}
