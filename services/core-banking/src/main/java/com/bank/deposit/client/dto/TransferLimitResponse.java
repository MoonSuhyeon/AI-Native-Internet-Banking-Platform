package com.bank.deposit.client.dto;

/**
 * 고객당 인터넷뱅킹 이체한도 (customer-service 소관).
 *
 * <p>계좌당 출금한도({@code Account.dailyWithdrawLimit})와는 다른 개념이다.
 * 계좌 한도는 그 계좌에서 하루에 나갈 수 있는 총액이고, 이쪽은 <b>그 사람이</b>
 * 인터넷뱅킹으로 보낼 수 있는 한도다. 계좌를 여러 개 가진 고객에게 둘은 다르게 걸린다.
 *
 * @param dailyLimit 1일 한도(원)
 * @param onceLimit  1회 한도(원)
 */
public record TransferLimitResponse(Long dailyLimit, Long onceLimit) {
}
