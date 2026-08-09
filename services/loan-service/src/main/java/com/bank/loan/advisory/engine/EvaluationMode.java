package com.bank.loan.advisory.engine;

/**
 * 어드바이저리 룰 평가 모드.
 *   SYNC   본심사 완료 트랜잭션 내부에서 동기 평가 (개별 건). 예: DSR/LTV 한도 초과 승인.
 *   BATCH  일배치(스냅샷 갱신 직후)에서 일괄 평가. 예: 심사관 거절율 편차, 동료 결정 분기.
 */
public enum EvaluationMode {
    SYNC,
    BATCH
}
