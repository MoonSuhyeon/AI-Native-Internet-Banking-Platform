package com.bank.payment.domain.reconciliation;

/**
 * 대사 불일치 한 건 — 내부 원장과 외부 청산 기록이 어긋난 지점.
 *
 * <p>금액을 둘 다 남기는 이유는 <b>차액만으로는 조치를 정할 수 없기</b> 때문이다.
 * 100만원이 0원과 어긋난 것(전송 유실)과 100만원이 99만원과 어긋난 것(수수료 처리
 * 차이)은 차액이 비슷해도 대응이 전혀 다르다.
 *
 * @param paymentInstructionId 어느 결제인가
 * @param type                 무엇이 어긋났는가
 * @param internalAmount       내부 원장의 청산대기 순액 (없으면 0)
 * @param externalAmount       외부 청산 기록의 금액 (없으면 0)
 * @param externalStatus       외부 청산 상태 (없으면 null)
 * @param detail               사람이 읽을 설명 — 조사 시작점
 */
public record ReconciliationBreak(
        String paymentInstructionId,
        ReconciliationBreakType type,
        long internalAmount,
        long externalAmount,
        String externalStatus,
        String detail) {

    /** 차액. 조사 우선순위를 매길 때 쓴다. */
    public long difference() {
        return internalAmount - externalAmount;
    }
}
