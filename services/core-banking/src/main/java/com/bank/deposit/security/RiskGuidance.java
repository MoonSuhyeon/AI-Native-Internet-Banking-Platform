package com.bank.deposit.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 탐지기가 만든 고객 안내를 결제계가 그대로 나른다.
 *
 * <p><b>왜 결제계가 문구를 만들지 않는가.</b> 신호 코드의 의미를 아는 쪽은 탐지기다.
 * 여기서 "HIGH_AMOUNT 면 이런 문구" 를 다시 쓰면 규칙이 두 곳에 흩어지고, 새 규칙이
 * 생겼을 때 한쪽만 고쳐진다. 결제계는 <b>운반만</b> 한다.
 *
 * <p><b>왜 그대로 나르는데 타입이 따로 있는가.</b> 탐지기의 클래스를 공유하면 결제계가
 * 탐지기 모듈에 컴파일 의존하게 된다. 지금 둘은 HTTP 로만 이어져 있고, 그 경계를
 * 유지하는 편이 낫다 — 대신 필드가 어긋나면 값이 조용히 비므로,
 * {@code GuidanceContractTest} 가 두 타입의 필드 이름을 대조한다.
 *
 * <p>{@code ignoreUnknown} 을 명시하는 이유는 {@code FdsPreCheckGate.PreCheckResult} 와
 * 같다. 탐지기가 필드를 하나 늘렸다고 이체가 전부 막히면 안 된다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RiskGuidance(
        String headline,
        List<String> evidence,
        List<String> actionSteps,
        List<String> choices
) {
}
