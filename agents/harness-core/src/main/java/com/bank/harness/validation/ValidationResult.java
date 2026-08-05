package com.bank.harness.validation;

import java.util.List;

/**
 * 검증 결과.
 *
 * <p>검증하는 <b>내용</b>은 도메인마다 다르다. 대출은 심사 의견의 수치를 보고, 상담은
 * 답변의 어투를 보고, 사기는 근거의 출처를 본다. 그러나 결과의 <b>모양</b>까지 다르면
 * "검증에 걸리면 fallback 으로 우회한다" 같은 규칙을 한 자리에 쓸 수 없다.
 * 어떤 검증기는 boolean 을, 어떤 검증기는 예외를, 어떤 검증기는 문자열을 돌려주는 상태가
 * 그것이다. 그래서 결과의 어휘만 하네스가 정한다.
 *
 * <p>{@code issues} 는 위반 사유를 <b>전부</b> 담는다. 첫 1건만 남기면 두 번째 원인을
 * 알아내는 데 LLM 을 한 번 더 돌려야 하고, 그 비용이 사유를 모아두는 비용보다 크다.
 */
public record ValidationResult(boolean passed, List<String> issues) {

    public ValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult fail(List<String> issues) {
        return new ValidationResult(false, issues);
    }
}
